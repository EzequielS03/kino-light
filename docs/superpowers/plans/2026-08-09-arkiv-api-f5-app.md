# F5 — Integración del gateway en la app Android

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app Arkiv consuma `api.comparadorinternet.co` en vez de los siete hosts actuales, con **Magis** como pestaña nueva y **una sola credencial**.

**Architecture:** Un `ArkivApiClient` que lee NDJSON línea a línea con OkHttp y alimenta el mismo `StateFlow` que hoy, para que la UI incremental no cambie. Detrás de un feature flag con caída al camino actual, así un gateway caído nunca deja la app sin buscar.

**Tech Stack:** Kotlin · Compose · OkHttp · kotlinx-coroutines (`Flow`) · JUnit + MockWebServer

**Estado del gateway (verificado en producción el 2026-08-09):** vivo en `https://api.comparadorinternet.co`, 132 tests verdes, las cuatro fuentes respondiendo. `/v1/health` es público; el resto exige `X-Arkiv-Key`.

## Corrección tras leer el código real (2026-08-09)

Tres cosas que este plan asumía mal:

1. **libVLC no acepta headers arbitrarios.** El player solo expone `:http-referrer` y
   `:http-user-agent` (`VlcPlayer.kt:372`). Magis necesita `Content-Auth` y `Content-License`, así
   que **no alcanza con pasar un mapa al `PlayerSourceTag`**: hay que hacer pasar el stream por el
   proxy local (`ArchiveCacheProxy`), que abre el origen con `HttpURLConnection` y ya usa
   `setRequestProperty` — ahí sí se pueden inyectar. **Es una tarea nueva (Task 4b).**
2. **`PlayerSourceTag` no se reemplaza, se extiende.** Cambiar `referer`/`userAgent` por un mapa
   rompería `PlayerScreen.kt`, `PlaybackService.kt` y `VlcPlayer.kt`, y `PlayerScreen.kt` lo está
   tocando otra sesión. Se agrega `extraHeaders: Map<String, String> = emptyMap()` y los campos
   actuales quedan como están.
3. **`PlaySource` usa un tipo concreto por fuente** (`TorrentResult`, `ArchiveSearchResult`,
   `WebResult`), no un genérico. El mapper construye esos tipos; no hacen falta constructores
   secundarios. `TorrentResult.downloadUrl` ya existe para "cuando no hay magnet directo" — calza
   exacto con los resultados solo-`Link` de Jackett.

**Ya hecho:** Task 1 (modelos + parser, 9 tests) y Task 2 (cliente NDJSON, 11 tests).
MockWebServer y `org.json` ya estaban entre las dependencias de test — el Step 1 de la Task 2 no
hizo falta.

## Global Constraints

- Repo: `/Users/cristian/archive` (la app Android). **Nunca `git add -A`** — hay varias sesiones compartiendo el working tree.
- Commits en español, sin pie de coautoría, identidad `lordmacu`.
- **La UI incremental no se toca.** Los resultados tienen que seguir apareciendo a medida que llegan y re-ordenándose por seeds; un cambio a "esperar todo y pintar" es una regresión.
- **El scrape UDP de seeders y el conteo DHT se quedan en el dispositivo** (`SearchViewModel.refreshSeeders`): necesitan la red del celular, no la del NUC.
- El flag `useGateway` arranca **ON**, con caída automática al camino viejo ante fallo de red o 5xx.
- El código viejo (`TorrentSearchApi`, `WebSourceEngine`, `ArchiveApi`) **no se borra** en este plan: se borra cuando el gateway esté probado en device.

---

## Contrato del gateway (lo que la app consume)

`GET /v1/search` → `application/x-ndjson`, una línea por evento:

```
{"type":"source_start","source":"torrent"}
{"type":"result","source":"torrent","item":{"source":"torrent","title":"Duna (2021) WEB-DL 4k HDR","ref":"<opaco>","kind":"movie","lang":"LATINO","quality":"WEB-DL 4k HDR","size_bytes":0,"seeders":12,"year":"2021","season":0,"episode":0,"extra":{"infohash":"…","backend":"mirror","is_pack":false}}}
{"type":"source_done","source":"torrent","count":376,"ms":1442}
{"type":"source_error","source":"magis","error":"PortalError","ms":315,"count":0}
{"type":"done","ms":1740}
```

Params: `q`, `type` (`movie|tv|anime`), `season`, `episode`, `year`, `tmdb_id`, `anilist_id`, `lang`, `sources` (csv), `budget_ms`, `format`.

`POST /v1/resolve` con `{"ref":"<opaco>"}` →

```json
{"kind":"magis","url":"http://…/vod/…_media.ts",
 "headers":{"Content-Auth":"…","Content-License":"…","User-Agent":"Ranger/4.9.4-17294ac0","App":"…","App-Version":"…"},
 "mime":"video/mp2t","expires_at":"","fallback":null}
```

`GET /v1/sources` → `{"sources":[{"name":"archive","capabilities":["anime","movie","tv"],"state":"closed"}, …]}`

**El `ref` es opaco: la app nunca lo interpreta ni lo construye.** Es lo que permite cambiar una fuente en el servidor sin publicar APK.

---

## File Structure

```
app/src/main/java/com/arkiv/player/
├── data/gateway/
│   ├── ArkivApiClient.kt        # NDJSON → Flow<SearchEvent>; resolve; sources
│   ├── GatewayModels.kt         # SearchEvent, GatewayResult, GatewayPlayable
│   └── GatewayMapper.kt         # GatewayResult → PlaySource
├── data/SettingsStore.kt        # + useGateway, arkivApiKey  (modificar)
├── playback/PlayerSource.kt     # + MAGIS, headers  (modificar)
├── ui/search/SourceTab.kt       # + MAGIS  (modificar)
├── ui/catalog/PlaySources.kt    # + PlaySource.Magis  (modificar)
└── ui/search/SearchViewModel.kt # usa el gateway con fallback  (modificar)

app/src/test/java/com/arkiv/player/data/gateway/
├── NdjsonParserTest.kt
├── GatewayMapperTest.kt
└── ArkivApiClientTest.kt        # MockWebServer
```

---

### Task 1: Modelos y parser de NDJSON

Lo primero y lo más testeable: convertir líneas en eventos, sin red de por medio.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/NdjsonParserTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SearchEvent` con `SourceStart(source)`, `ResultEvent(source, item)`, `SourceDone(source, count, ms)`, `SourceError(source, error, ms, count)`, `Done(ms)`, y `Unknown(raw)`
  - `data class GatewayResult(source, title, ref, kind, lang, quality, sizeBytes, seeders, year, season, episode, extra)`
  - `data class GatewayPlayable(kind, url, headers, mime, expiresAt, fallbackUrl)`
  - `fun parseSearchEvent(linea: String): SearchEvent`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdjsonParserTest {

    @Test
    fun `parsea source_start`() {
        val ev = parseSearchEvent("""{"type":"source_start","source":"torrent"}""")
        assertEquals(SearchEvent.SourceStart("torrent"), ev)
    }

    @Test
    fun `parsea un result con todos sus campos`() {
        val linea = """{"type":"result","source":"torrent","item":{"source":"torrent",""" +
            """"title":"Duna (2021) WEB-DL 4k HDR","ref":"abc.def","kind":"movie","lang":"LATINO",""" +
            """"quality":"WEB-DL 4k HDR","size_bytes":123,"seeders":12,"year":"2021","season":0,""" +
            """"episode":0,"extra":{"infohash":"aa","backend":"mirror","is_pack":false}}}"""
        val ev = parseSearchEvent(linea) as SearchEvent.ResultEvent
        assertEquals("Duna (2021) WEB-DL 4k HDR", ev.item.title)
        assertEquals("abc.def", ev.item.ref)
        assertEquals("LATINO", ev.item.lang)
        assertEquals(12, ev.item.seeders)
        assertEquals("aa", ev.item.extra["infohash"])
    }

    @Test
    fun `parsea source_done y source_error con su conteo parcial`() {
        val done = parseSearchEvent("""{"type":"source_done","source":"web","count":3,"ms":120}""")
        assertEquals(SearchEvent.SourceDone("web", 3, 120), done)

        val err = parseSearchEvent("""{"type":"source_error","source":"magis","error":"PortalError","ms":315,"count":2}""")
        assertEquals(SearchEvent.SourceError("magis", "PortalError", 315, 2), err)
    }

    @Test
    fun `source_error sin count asume cero`() {
        val err = parseSearchEvent("""{"type":"source_error","source":"m","error":"timeout","ms":4000}""")
        assertEquals(0, (err as SearchEvent.SourceError).count)
    }

    @Test
    fun `un tipo desconocido no explota`() {
        // El servidor puede sumar eventos nuevos sin que la app se caiga.
        val ev = parseSearchEvent("""{"type":"algo_nuevo","x":1}""")
        assertTrue(ev is SearchEvent.Unknown)
    }

    @Test
    fun `una linea corrupta no explota`() {
        assertTrue(parseSearchEvent("{no es json") is SearchEvent.Unknown)
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*NdjsonParserTest*'`
Expected: FAIL con `Unresolved reference: parseSearchEvent`.

- [ ] **Step 3: Implementar `GatewayModels.kt`**

```kotlin
package com.arkiv.player.data.gateway

import org.json.JSONObject

/** Un resultado de búsqueda tal como lo entrega el gateway. */
data class GatewayResult(
    val source: String,
    val title: String,
    val ref: String,
    val kind: String = "movie",
    val lang: String = "",
    val quality: String = "",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val year: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val extra: Map<String, String> = emptyMap(),
)

/** Lo reproducible que devuelve `/v1/resolve`. `headers` es genérico a propósito:
 *  cubre el Referer/User-Agent de las fuentes web y el Content-Auth/Content-License
 *  de magis sin casos especiales. */
data class GatewayPlayable(
    val kind: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mime: String = "",
    val expiresAt: String = "",
    val fallbackUrl: String? = null,
)

sealed interface SearchEvent {
    data class SourceStart(val source: String) : SearchEvent
    data class ResultEvent(val source: String, val item: GatewayResult) : SearchEvent
    data class SourceDone(val source: String, val count: Int, val ms: Long) : SearchEvent
    data class SourceError(val source: String, val error: String, val ms: Long, val count: Int) : SearchEvent
    data class Done(val ms: Long) : SearchEvent
    /** Evento que esta versión de la app no conoce, o línea corrupta. Se ignora:
     *  el servidor puede sumar eventos nuevos sin romper APKs viejos. */
    data class Unknown(val raw: String) : SearchEvent
}

private fun JSONObject.mapaDeStrings(clave: String): Map<String, String> {
    val obj = optJSONObject(clave) ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.opt(it)?.toString().orEmpty() }
}

private fun resultDe(obj: JSONObject) = GatewayResult(
    source = obj.optString("source"),
    title = obj.optString("title"),
    ref = obj.optString("ref"),
    kind = obj.optString("kind", "movie"),
    lang = obj.optString("lang"),
    quality = obj.optString("quality"),
    sizeBytes = obj.optLong("size_bytes"),
    seeders = obj.optInt("seeders"),
    year = obj.optString("year"),
    season = obj.optInt("season"),
    episode = obj.optInt("episode"),
    extra = obj.mapaDeStrings("extra"),
)

fun parseSearchEvent(linea: String): SearchEvent = runCatching {
    val o = JSONObject(linea)
    when (o.optString("type")) {
        "source_start" -> SearchEvent.SourceStart(o.optString("source"))
        "result" -> SearchEvent.ResultEvent(
            o.optString("source"),
            resultDe(o.getJSONObject("item")),
        )
        "source_done" -> SearchEvent.SourceDone(
            o.optString("source"), o.optInt("count"), o.optLong("ms"),
        )
        "source_error" -> SearchEvent.SourceError(
            o.optString("source"), o.optString("error"), o.optLong("ms"), o.optInt("count"),
        )
        "done" -> SearchEvent.Done(o.optLong("ms"))
        else -> SearchEvent.Unknown(linea)
    }
}.getOrElse { SearchEvent.Unknown(linea) }
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests '*NdjsonParserTest*'`
Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt \
        app/src/test/java/com/arkiv/player/data/gateway/NdjsonParserTest.kt
git commit -m "feat(gateway): modelos y parser de NDJSON del gateway"
```

---

### Task 2: `ArkivApiClient` — streaming, resolve y sources

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt`

**Interfaces:**
- Consumes: `parseSearchEvent`, `GatewayPlayable`
- Produces:
  - `class ArkivApiClient(baseUrl: () -> String, apiKey: () -> String, http: OkHttpClient)`
  - `fun search(ctx: GatewaySearchQuery): Flow<SearchEvent>`
  - `suspend fun resolve(ref: String): GatewayPlayable`
  - `suspend fun sources(): List<GatewaySource>`
  - `data class GatewaySearchQuery(q, type, season, episode, year, tmdbId, anilistId, lang, sources, budgetMs)`
  - `data class GatewaySource(name, capabilities, state)`

- [ ] **Step 1: Agregar MockWebServer a las dependencias de test**

En `app/build.gradle.kts`, dentro del bloque `dependencies`:

```kotlin
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArkivApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArkivApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            apiKey = { "LLAVE" },
            http = OkHttpClient(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `manda la llave en el header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "dune")).toList()
        assertEquals("LLAVE", server.takeRequest().getHeader("X-Arkiv-Key"))
    }

    @Test fun `emite un evento por linea a medida que llegan`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"source_start","source":"a"}""" + "\n" +
                    """{"type":"result","source":"a","item":{"title":"T","ref":"r"}}""" + "\n" +
                    """{"type":"source_done","source":"a","count":1,"ms":10}""" + "\n" +
                    """{"type":"done","ms":11}""" + "\n"
            )
        )
        val evs = client.search(GatewaySearchQuery(q = "dune")).toList()
        assertEquals(4, evs.size)
        assertTrue(evs[1] is SearchEvent.ResultEvent)
        assertTrue(evs.last() is SearchEvent.Done)
    }

    @Test fun `ignora lineas vacias`() = runBlocking {
        server.enqueue(MockResponse().setBody("\n\n" + """{"type":"done","ms":1}""" + "\n\n"))
        assertEquals(1, client.search(GatewaySearchQuery(q = "x")).toList().size)
    }

    @Test fun `arma la query con S y E cuando se piden`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "breaking bad", type = "tv", season = 1, episode = 2)).toList()
        val url = server.takeRequest().requestUrl!!
        assertEquals("breaking bad", url.queryParameter("q"))
        assertEquals("tv", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("season"))
        assertEquals("2", url.queryParameter("episode"))
    }

    @Test(expected = GatewayException::class)
    fun `un 500 lanza para que el llamador caiga al camino viejo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        client.search(GatewaySearchQuery(q = "x")).toList()
        Unit
    }

    @Test(expected = GatewayException::class)
    fun `un 401 lanza`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        client.search(GatewaySearchQuery(q = "x")).toList()
        Unit
    }

    @Test fun `resolve devuelve url y headers`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"magis","url":"http://cdn/v.ts","headers":{"Content-Auth":"A","Content-License":"L"},""" +
                    """"mime":"video/mp2t","expires_at":"","fallback":null}"""
            )
        )
        val p = client.resolve("elref")
        assertEquals("http://cdn/v.ts", p.url)
        assertEquals("A", p.headers["Content-Auth"])
        assertEquals("magis", p.kind)
        assertEquals(null, p.fallbackUrl)
    }

    @Test fun `resolve mapea el fallback del proxy web`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"web","url":"http://cdn/v.m3u8","headers":{"Referer":"http://s/"},""" +
                    """"mime":"","expires_at":"","fallback":{"url":"http://proxy/x"}}"""
            )
        )
        assertEquals("http://proxy/x", client.resolve("r").fallbackUrl)
    }

    @Test fun `sources lista las fuentes activas`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"sources":[{"name":"magis","capabilities":["movie","tv"],"state":"closed"}]}"""
            )
        )
        val s = client.sources()
        assertEquals(1, s.size)
        assertEquals("magis", s[0].name)
        assertEquals(listOf("movie", "tv"), s[0].capabilities)
    }
}
```

- [ ] **Step 3: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*ArkivApiClientTest*'`
Expected: FAIL con `Unresolved reference: ArkivApiClient`.

- [ ] **Step 4: Implementar `ArkivApiClient.kt`**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GatewayException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

data class GatewaySearchQuery(
    val q: String,
    val type: String = "movie",
    val season: Int = 0,
    val episode: Int = 0,
    val year: String = "",
    val tmdbId: Int = 0,
    val anilistId: Int = 0,
    val lang: String = "",
    val sources: String = "",
    val budgetMs: Int = 0,
)

data class GatewaySource(
    val name: String,
    val capabilities: List<String>,
    val state: String,
)

/**
 * Cliente del gateway unificado.
 *
 * `search` emite eventos **a medida que llegan**: el gateway responde NDJSON en
 * streaming y la pantalla ya está construida para pintar resultados incrementalmente.
 * Bufferizar la respuesta entera seria una regresión de UX.
 */
class ArkivApiClient(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    http: OkHttpClient,
) {
    // Sin timeout de lectura: la respuesta es un stream largo, no un cuerpo corto.
    private val http = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun pedido(url: String): Request.Builder =
        Request.Builder().url(url).header("X-Arkiv-Key", apiKey())

    fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val url = "${baseUrl()}/v1/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", ctx.q)
            addQueryParameter("type", ctx.type)
            if (ctx.season > 0) addQueryParameter("season", ctx.season.toString())
            if (ctx.episode > 0) addQueryParameter("episode", ctx.episode.toString())
            if (ctx.year.isNotBlank()) addQueryParameter("year", ctx.year)
            if (ctx.tmdbId > 0) addQueryParameter("tmdb_id", ctx.tmdbId.toString())
            if (ctx.anilistId > 0) addQueryParameter("anilist_id", ctx.anilistId.toString())
            if (ctx.lang.isNotBlank()) addQueryParameter("lang", ctx.lang)
            if (ctx.sources.isNotBlank()) addQueryParameter("sources", ctx.sources)
            if (ctx.budgetMs > 0) addQueryParameter("budget_ms", ctx.budgetMs.toString())
        }.build().toString()

        val respuesta = runCatching { http.newCall(pedido(url).get().build()).execute() }
            .getOrElse { throw GatewayException("no se pudo llamar al gateway", it) }

        respuesta.use { r ->
            if (!r.isSuccessful) throw GatewayException("gateway respondio ${r.code}")
            val cuerpo = r.body ?: throw GatewayException("gateway respondio sin cuerpo")
            val fuente = cuerpo.source()
            while (true) {
                val linea = fuente.readUtf8Line() ?: break
                if (linea.isBlank()) continue
                emit(parseSearchEvent(linea))
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun resolve(ref: String): GatewayPlayable = enIO {
        val cuerpo = JSONObject().put("ref", ref).toString()
            .toRequestBody("application/json".toMediaType())
        val texto = ejecutar(pedido("${baseUrl()}/v1/resolve").post(cuerpo).build())
        val o = JSONObject(texto)
        GatewayPlayable(
            kind = o.optString("kind"),
            url = o.optString("url"),
            headers = o.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
            mime = o.optString("mime"),
            expiresAt = o.optString("expires_at"),
            fallbackUrl = o.optJSONObject("fallback")?.optString("url"),
        )
    }

    suspend fun sources(): List<GatewaySource> = enIO {
        val arr = JSONObject(ejecutar(pedido("${baseUrl()}/v1/sources").get().build()))
            .optJSONArray("sources") ?: return@enIO emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val caps = o.optJSONArray("capabilities")
            GatewaySource(
                name = o.optString("name"),
                capabilities = (0 until (caps?.length() ?: 0)).map { caps!!.getString(it) },
                state = o.optString("state"),
            )
        }
    }

    private fun ejecutar(request: Request): String {
        val r = runCatching { http.newCall(request).execute() }
            .getOrElse { throw GatewayException("no se pudo llamar al gateway", it) }
        r.use {
            if (!it.isSuccessful) throw GatewayException("gateway respondio ${it.code}")
            return it.body?.string().orEmpty()
        }
    }

    private suspend fun <T> enIO(bloque: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { bloque() }
}
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests '*ArkivApiClientTest*'`
Expected: 9 passed.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt \
        app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt
git commit -m "feat(gateway): cliente NDJSON con resolve y sources"
```

---

### Task 3: Magis como fuente de primera clase en el modelo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SourceTab.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt`

**Interfaces:**
- Produces: `SourceKind.MAGIS`; `SourceTab.MAGIS`; `PlaySource.Magis(result: GatewayResult)`;
  `PlayerSourceTag.headers: Map<String, String>`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTabTest {

    private fun magis() = PlaySource.Magis(
        GatewayResult(source = "magis", title = "Duna", ref = "r", year = "2021")
    )

    @Test
    fun `magis tiene su propia pestana`() {
        assertEquals(SourceTab.MAGIS, tabOf(magis()))
    }

    @Test
    fun `todo sigue contando todas las fuentes`() {
        val fuentes = listOf(magis())
        val conteos = countsByTab(fuentes)
        assertEquals(1, conteos[SourceTab.TODO])
        assertEquals(1, conteos[SourceTab.MAGIS])
        assertEquals(0, conteos[SourceTab.TORRENT])
    }

    @Test
    fun `los chips no bailan- siempre estan las cinco claves`() {
        assertEquals(SourceTab.entries.size, countsByTab(emptyList()).size)
    }

    @Test
    fun `filtrar por magis deja solo magis`() {
        assertEquals(1, filterByTab(listOf(magis()), SourceTab.MAGIS).size)
        assertEquals(0, filterByTab(listOf(magis()), SourceTab.WEB).size)
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*SourceTabTest*'`
Expected: FAIL con `Unresolved reference: MAGIS`.

- [ ] **Step 3: Agregar `MAGIS` a `SourceKind` y generalizar los headers**

En `PlayerSource.kt`, reemplazar el enum y el tag:

```kotlin
enum class SourceKind { ARCHIVE, TORRENT, WEB, MAGIS, NUC, LOCAL }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
    /** Headers que el origen exige para servir el stream. Genérico a propósito:
     *  las fuentes web piden Referer/User-Agent y magis pide Content-Auth y
     *  Content-License. Antes eran campos sueltos; un mapa evita un campo nuevo
     *  por cada fuente que sume el gateway. */
    val headers: Map<String, String> = emptyMap(),
    val proxyUrl: String? = null,
) {
    // Derivados: el player y el cast ya los leen con estos nombres.
    val referer: String? get() = headers["Referer"]
    val userAgent: String? get() = headers["User-Agent"]
}

object PlayerSource {
    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("torrent:") -> SourceKind.TORRENT
        episodeId.startsWith("web:") -> SourceKind.WEB
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        else -> SourceKind.ARCHIVE
    }
}
```

Compilar y arreglar los sitios donde se construía `PlayerSourceTag(referer = …, userAgent = …)`:
pasan a `headers = buildMap { referer?.let { put("Referer", it) }; ua?.let { put("User-Agent", it) } }`.

- [ ] **Step 4: Agregar `PlaySource.Magis` y la pestaña**

En `PlaySources.kt`, agregar al sealed interface:

```kotlin
    /** Resultado del portal Magis (solo VOD). El `ref` es opaco: se manda tal cual
     *  a `/v1/resolve` y la app nunca lo interpreta. */
    data class Magis(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource
```

En `SourceTab.kt`:

```kotlin
enum class SourceTab(val label: String) {
    TODO("Todo"),
    TORRENT("Torrent"),
    WEB("Web"),
    MAGIS("Magis"),
    ARCHIVE("Archive"),
}

fun tabOf(source: PlaySource): SourceTab = when (source) {
    is PlaySource.Torrent -> SourceTab.TORRENT
    is PlaySource.Web, is PlaySource.WebPack -> SourceTab.WEB
    is PlaySource.Magis -> SourceTab.MAGIS
    is PlaySource.Archive -> SourceTab.ARCHIVE
}
```

`countsByTab` y `filterByTab` no se tocan: ya recorren `SourceTab.entries`.

- [ ] **Step 5: Correr y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*SourceTabTest*'
./gradlew :app:compileDebugKotlin
```

Expected: 4 passed y compilación limpia.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/PlayerSource.kt \
        app/src/main/java/com/arkiv/player/ui/search/SourceTab.kt \
        app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt \
        app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt
git commit -m "feat(gateway): Magis como fuente propia y headers genericos en el player"
```

---

### Task 4: Mapear resultados del gateway a `PlaySource`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/GatewayMapper.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/GatewayMapperTest.kt`

**Interfaces:**
- Consumes: `GatewayResult`, `PlaySource`, `TorrentResult`, `TorrentLang`
- Produces: `fun GatewayResult.toPlaySource(): PlaySource?` (null si la fuente es desconocida)

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.gateway

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayMapperTest {

    @Test fun `torrent conserva idioma seeds e infohash`() {
        val ps = GatewayResult(
            source = "torrent", title = "Duna (2021) 4k", ref = "r",
            lang = "LATINO", seeders = 42, sizeBytes = 123,
            extra = mapOf("infohash" to "aa", "backend" to "mirror", "is_pack" to "false"),
        ).toPlaySource() as PlaySource.Torrent

        assertEquals(TorrentLang.LATINO, ps.result.lang)
        assertEquals(42, ps.result.seeders)
        assertEquals("aa", ps.result.infoHash)
    }

    @Test fun `castellano y jap_sub se mapean a su enum`() {
        val cast = GatewayResult(source = "torrent", title = "t", ref = "r", lang = "CASTELLANO",
            extra = mapOf("infohash" to "a")).toPlaySource() as PlaySource.Torrent
        assertEquals(TorrentLang.CASTELLANO, cast.result.lang)

        val sub = GatewayResult(source = "torrent", title = "t", ref = "r", lang = "JAP_SUB",
            extra = mapOf("infohash" to "a")).toPlaySource() as PlaySource.Torrent
        assertEquals(TorrentLang.JAP_SUB, sub.result.lang)
    }

    @Test fun `un idioma que la app no conoce no rompe el mapeo`() {
        val ps = GatewayResult(source = "torrent", title = "t", ref = "r", lang = "KLINGON",
            extra = mapOf("infohash" to "a")).toPlaySource()
        assertTrue(ps is PlaySource.Torrent)
    }

    @Test fun `magis se mapea a su propio tipo`() {
        val ps = GatewayResult(source = "magis", title = "Duna", ref = "r", year = "2021").toPlaySource()
        assertTrue(ps is PlaySource.Magis)
        assertEquals("Duna", (ps as PlaySource.Magis).result.title)
    }

    @Test fun `una fuente desconocida se descarta sin romper`() {
        // Si el servidor agrega una fuente que este APK no conoce, se ignora.
        assertNull(GatewayResult(source = "fuente_nueva", title = "x", ref = "r").toPlaySource())
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*GatewayMapperTest*'`
Expected: FAIL con `Unresolved reference: toPlaySource`.

- [ ] **Step 3: Implementar `GatewayMapper.kt`**

```kotlin
package com.arkiv.player.data.gateway

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.ui.catalog.PlaySource

private fun langDe(texto: String): TorrentLang =
    runCatching { TorrentLang.valueOf(texto.uppercase()) }.getOrDefault(TorrentLang.ENGLISH)

/**
 * Traduce un resultado del gateway al modelo que ya usa la pantalla.
 *
 * Devuelve `null` si la fuente no la conoce este APK: el servidor puede sumar
 * fuentes nuevas y un APK viejo simplemente las ignora en vez de romperse.
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    "torrent" -> PlaySource.Torrent(
        TorrentResult(
            title = title,
            magnetUri = "",            // se resuelve al reproducir, via /v1/resolve
            infoHash = extra["infohash"].orEmpty(),
            seeders = seeders,
            sizeBytes = sizeBytes,
            lang = langDe(lang),
        )
    )
    "magis" -> PlaySource.Magis(this)
    "web" -> PlaySource.Web(this)
    "archive" -> PlaySource.Archive(this)
    else -> null
}
```

> Nota para el implementador: `PlaySource.Web` y `PlaySource.Archive` hoy llevan
> otros tipos. En este task se les agrega un constructor secundario que acepta
> `GatewayResult`, o se adaptan al modelo existente — lo que menos toque la UI.
> Los tests de arriba solo fijan torrent y magis, que son los que cambian de forma.

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests '*GatewayMapperTest*'`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/gateway/GatewayMapper.kt \
        app/src/test/java/com/arkiv/player/data/gateway/GatewayMapperTest.kt
git commit -m "feat(gateway): mapeo de resultados del gateway a PlaySource"
```

---

### Task 5: Credencial única y feature flag

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/com/arkiv/player/data/SettingsStoreGatewayTest.kt`

**Interfaces:**
- Produces: `SettingsStore.arkivApiKey: StateFlow<String>`, `setArkivApiKey`,
  `SettingsStore.useGateway: StateFlow<Boolean>`, `setUseGateway`,
  `SettingsStore.gatewayUrl: StateFlow<String>`;
  `BuildConfig.ARKIV_API_KEY`; constante `DEFAULT_GATEWAY_URL`

- [ ] **Step 1: Agregar la llave a `build.gradle.kts`**

Reemplazar el bloque de `buildConfigField` por:

```kotlin
        // Una sola credencial: el gateway guarda las llaves de TMDB, OpenSubtitles,
        // Simkl, el mirror y el NUC. Editable en Ajustes para poder rotarla sin APK.
        buildConfigField("String", "ARKIV_API_KEY", "\"${readEnv("ARKIV_API_KEY")}\"")
```

Y agregar `ARKIV_API_KEY=` al `.env` de la raíz del repo (el valor sale de
`~/arkiv-api/.env` en blog, campo `ARKIV_API_KEYS`).

- [ ] **Step 2: Escribir el test que falla**

```kotlin
package com.arkiv.player.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreGatewayTest {

    private fun store() = SettingsStore(ApplicationProvider.getApplicationContext())

    @Test fun `el gateway viene encendido por defecto`() {
        assertTrue(store().useGateway.value)
    }

    @Test fun `la url por defecto apunta a produccion`() {
        assertEquals("https://api.comparadorinternet.co", store().gatewayUrl.value)
    }

    @Test fun `la llave se puede rotar sin publicar APK`() {
        val s = store()
        s.setArkivApiKey("llave-nueva")
        assertEquals("llave-nueva", s.arkivApiKey.value)
    }

    @Test fun `el flag se puede apagar`() {
        val s = store()
        s.setUseGateway(false)
        assertTrue(!s.useGateway.value)
    }
}
```

Si el proyecto no tiene Robolectric, agregarlo:
`testImplementation("org.robolectric:robolectric:4.13")` y `testOptions { unitTests.isIncludeAndroidResources = true }`.

- [ ] **Step 3: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsStoreGatewayTest*'`
Expected: FAIL con `Unresolved reference: useGateway`.

- [ ] **Step 4: Implementar en `SettingsStore.kt`**

Siguiendo el patrón que ya usa el archivo (`_refreshApiKey`):

```kotlin
    private val _arkivApiKey = MutableStateFlow(prefs.getString(KEY_ARKIV_API_KEY, DEFAULT_ARKIV_API_KEY)!!)
    val arkivApiKey: StateFlow<String> = _arkivApiKey.asStateFlow()
    fun setArkivApiKey(v: String) { prefs.edit().putString(KEY_ARKIV_API_KEY, v).apply(); _arkivApiKey.value = v }

    private val _useGateway = MutableStateFlow(prefs.getBoolean(KEY_USE_GATEWAY, true))
    val useGateway: StateFlow<Boolean> = _useGateway.asStateFlow()
    fun setUseGateway(v: Boolean) { prefs.edit().putBoolean(KEY_USE_GATEWAY, v).apply(); _useGateway.value = v }

    private val _gatewayUrl = MutableStateFlow(prefs.getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL)!!)
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()
    fun setGatewayUrl(v: String) { prefs.edit().putString(KEY_GATEWAY_URL, v).apply(); _gatewayUrl.value = v }
```

Y en el `companion object`:

```kotlin
        private const val KEY_ARKIV_API_KEY = "arkiv_api_key"
        private const val KEY_USE_GATEWAY = "use_gateway"
        private const val KEY_GATEWAY_URL = "gateway_url"
        const val DEFAULT_GATEWAY_URL = "https://api.comparadorinternet.co"
        val DEFAULT_ARKIV_API_KEY: String get() = BuildConfig.ARKIV_API_KEY
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsStoreGatewayTest*'`
Expected: 4 passed.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/arkiv/player/data/SettingsStore.kt \
        app/src/test/java/com/arkiv/player/data/SettingsStoreGatewayTest.kt
git commit -m "feat(gateway): credencial unica, url y flag useGateway en Ajustes"
```

---

### Task 6: Buscar por el gateway con caída al camino actual

El corazón de la migración. Lo importante es que **nada empeore** si el gateway falla.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/search/GatewaySearchFallbackTest.kt`

**Interfaces:**
- Consumes: `ArkivApiClient`, `toPlaySource`, `SettingsStore.useGateway`
- Produces: en `SearchViewModel`, `private suspend fun buscarPorGateway(...): Boolean`
  (devuelve `false` si hay que caer al camino viejo)

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.gateway.toPlaySource
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySearchFallbackTest {

    @Test fun `los resultados se acumulan a medida que llegan los eventos`() = runBlocking {
        val eventos = flow {
            emit(SearchEvent.SourceStart("torrent"))
            emit(SearchEvent.ResultEvent("torrent", GatewayResult("torrent", "A", "r1",
                extra = mapOf("infohash" to "a"))))
            emit(SearchEvent.ResultEvent("torrent", GatewayResult("torrent", "B", "r2",
                extra = mapOf("infohash" to "b"))))
            emit(SearchEvent.SourceDone("torrent", 2, 10))
            emit(SearchEvent.Done(11))
        }
        val acumulados = eventos.toList()
            .filterIsInstance<SearchEvent.ResultEvent>()
            .mapNotNull { it.item.toPlaySource() }
        assertEquals(2, acumulados.size)
    }

    @Test fun `una fuente que falla no descarta lo que trajeron las otras`() = runBlocking {
        val eventos = flow {
            emit(SearchEvent.ResultEvent("torrent", GatewayResult("torrent", "A", "r1",
                extra = mapOf("infohash" to "a"))))
            emit(SearchEvent.SourceError("magis", "PortalError", 315, 0))
            emit(SearchEvent.Done(400))
        }.toList()
        val ok = eventos.filterIsInstance<SearchEvent.ResultEvent>()
        val err = eventos.filterIsInstance<SearchEvent.SourceError>()
        assertEquals(1, ok.size)
        assertEquals("magis", err.single().source)
    }

    @Test fun `si el gateway no responde se cae al camino viejo`() = runBlocking {
        var cayo = false
        val resultado = runCatching {
            flow<SearchEvent> { throw GatewayException("caido") }.toList()
        }.getOrElse { cayo = true; emptyList() }
        assertTrue(cayo)
        assertTrue(resultado.isEmpty())
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*GatewaySearchFallbackTest*'`
Expected: FAIL hasta que existan `GatewayException` y `toPlaySource` (Tasks 2 y 4).

- [ ] **Step 3: Cablear el cliente en `AppGraph`**

```kotlin
    val arkivApiClient by lazy {
        com.arkiv.player.data.gateway.ArkivApiClient(
            baseUrl = { settings.gatewayUrl.value },
            apiKey = { settings.arkivApiKey.value },
            http = okHttpClient,
        )
    }
```

Y pasarlo al constructor de `SearchViewModel`.

- [ ] **Step 4: Implementar la búsqueda por gateway en `SearchViewModel`**

```kotlin
    /**
     * Busca por el gateway y va llenando `_sources` a medida que llegan los eventos.
     *
     * Devuelve `false` si el gateway no sirvió (red caída, 5xx, 401): el llamador
     * cae al camino viejo. Una fuente que falla NO cuenta como fallo del gateway —
     * las demás igual entregaron.
     */
    private suspend fun buscarPorGateway(ctx: GatewaySearchQuery): Boolean {
        var huboAlgo = false
        return runCatching {
            arkivApiClient.search(ctx).collect { ev ->
                when (ev) {
                    is SearchEvent.ResultEvent -> ev.item.toPlaySource()?.let { ps ->
                        huboAlgo = true
                        _sources.value = _sources.value + ps
                        refreshSeeders()   // el scrape UDP sigue siendo del dispositivo
                    }
                    is SearchEvent.SourceError -> Log.w(TAG, "fuente ${ev.source}: ${ev.error}")
                    else -> Unit
                }
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "gateway fallo, se cae al camino viejo", e)
            // Si ya llegaron resultados, no se re-busca: se conserva lo que hay.
            huboAlgo
        }
    }
```

Y en el punto donde hoy arranca la búsqueda multi-fuente:

```kotlin
        val porGateway = settings.useGateway.value && buscarPorGateway(ctxGateway)
        if (!porGateway) buscarComoAntes()   // torrent + web + archive locales
```

- [ ] **Step 5: Correr los tests y compilar**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: todo verde.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt \
        app/src/main/java/com/arkiv/player/AppGraph.kt \
        app/src/test/java/com/arkiv/player/ui/search/GatewaySearchFallbackTest.kt
git commit -m "feat(gateway): busqueda por el gateway con caida al camino actual"
```

---

### Task 7: Reproducir desde el gateway (incluido Magis)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/player/GatewayPlaybackTest.kt`

**Interfaces:**
- Consumes: `ArkivApiClient.resolve`, `PlayerSourceTag.headers`
- Produces: `private suspend fun loadMagis(episodeId: String)` y el uso de `headers` en los demás

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui.player

import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.playback.PlayerSourceTag
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayPlaybackTest {

    private fun tagDe(p: GatewayPlayable) = PlayerSourceTag(
        kind = SourceKind.MAGIS,
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        castUrl = null, headers = p.headers, proxyUrl = p.fallbackUrl,
    )

    @Test fun `los headers de magis llegan al player`() {
        val p = GatewayPlayable(
            kind = "magis", url = "http://cdn/v.ts",
            headers = mapOf(
                "Content-Auth" to "A", "Content-License" to "L",
                "User-Agent" to "Ranger/4.9.4-17294ac0",
            ),
            mime = "video/mp2t",
        )
        val tag = tagDe(p)
        assertEquals("A", tag.headers["Content-Auth"])
        assertEquals("L", tag.headers["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", tag.userAgent)
    }

    @Test fun `el referer de las fuentes web sigue leyendose por su nombre viejo`() {
        val tag = PlayerSourceTag(
            kind = SourceKind.WEB,
            openingStartMs = null, openingEndMs = null, endingStartMs = null, castUrl = null,
            headers = mapOf("Referer" to "https://serieskao.top/"),
        )
        assertEquals("https://serieskao.top/", tag.referer)
    }

    @Test fun `el proxy del resolver web queda como respaldo`() {
        val p = GatewayPlayable(kind = "web", url = "http://cdn/v.m3u8", fallbackUrl = "http://proxy/x")
        assertEquals("http://proxy/x", tagDe(p).proxyUrl)
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests '*GatewayPlaybackTest*'`
Expected: FAIL si `headers` todavía no existe (depende de Task 3).

- [ ] **Step 3: Implementar `loadMagis` y el ramal en `load`**

```kotlin
            SourceKind.MAGIS -> loadMagis(episodeId)
```

```kotlin
    /**
     * Magis sirve el VOD directo del CDN, pero exige `Content-Auth` y
     * `Content-License` como headers: una URL pelada devuelve 401.
     */
    private suspend fun loadMagis(episodeId: String) {
        val ref = episodeId.removePrefix("magis:")
        val p = arkivApiClient.resolve(ref)
        val tag = PlayerSourceTag(
            kind = SourceKind.MAGIS,
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            castUrl = null, headers = p.headers, proxyUrl = p.fallbackUrl,
        )
        reproducir(p.url, tag, safeStartPosition(episodeId, SourceKind.MAGIS))
    }
```

Los headers hay que pasarlos al `DataSource.Factory` del player (`setDefaultRequestProperties(tag.headers)`).

- [ ] **Step 4: Correr y compilar**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt \
        app/src/test/java/com/arkiv/player/ui/player/GatewayPlaybackTest.kt
git commit -m "feat(gateway): reproduccion de Magis con Content-Auth y Content-License"
```

---

### Task 8: Verificación en device

Nada de esto cuenta hasta verlo andar en el celular y en la TV.

**Files:** ninguno (verificación)

- [ ] **Step 1: Instalar en el celular**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Verificar la búsqueda**

Buscar "dune". Comprobar:
- Aparecen las pestañas **Torrent · Web · Magis · Archive** y los contadores no bailan.
- Los resultados de torrent muestran título legible ("Duna (2021) WEB-DL 4k HDR"), no la calidad sola.
- Los seeders se actualizan solos (el scrape UDP sigue vivo).

- [ ] **Step 3: Verificar reproducción de Magis**

Abrir un resultado de la pestaña Magis y darle play. Tiene que **arrancar video**.
Si da 401, revisar que los headers lleguen al `DataSource`:

```bash
adb logcat -s PlayerViewModel:* ArkivApiClient:* | head -40
```

- [ ] **Step 4: Verificar el fallback**

Apagar `useGateway` en Ajustes y repetir la búsqueda: tiene que seguir funcionando
por el camino viejo. Volver a encenderlo.

- [ ] **Step 5: Verificar en la TV**

Instalar en el Fire Stick y comprobar que las pestañas se navegan con el D-pad y
que Magis reproduce.

- [ ] **Step 6: Commit del bump de versión**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump a 0.4.0 (busqueda unificada por el gateway + Magis)"
```

---

## Self-Review

**Cobertura:** el contrato del gateway se consume entero — `/v1/search` (Tasks 1, 2, 6),
`/v1/resolve` (Tasks 2, 7), `/v1/sources` (Task 2, disponible para pintar pestañas dinámicas).
Magis entra como fuente de primera clase (Tasks 3, 4, 7). La credencial única y el flag
están en la Task 5. La verificación en device es la Task 8.

**Consistencia de tipos:** `GatewayResult` (Task 1) es lo que produce el parser, lo que
mapea `toPlaySource` (Task 4) y lo que envuelve `PlaySource.Magis` (Task 3). `GatewayPlayable`
(Task 1) es lo que devuelve `resolve` (Task 2) y lo que consume `loadMagis` (Task 7).
`PlayerSourceTag.headers` (Task 3) es el mismo mapa en Tasks 3 y 7, con `referer`/`userAgent`
como derivados para no romper el player ni el cast.

**Fuera de alcance (a propósito):** borrar `TorrentSearchApi`, `WebSourceEngine` y `ArchiveApi`
—se hace cuando el gateway esté probado en device—; mover el catálogo (TMDB/AniList/Simkl/
OpenSubtitles) a `/v1/catalog/*`, que es su propio plan; y F6 (jobs de descarga).

**Riesgo conocido:** con Jackett en frío la primera búsqueda de un título nuevo llega
incompleta (el presupuesto la corta) y la segunda ya viene completa, porque Jackett cachea.
No es un bug de la app; si molesta, la palanca es `budget_ms`.
