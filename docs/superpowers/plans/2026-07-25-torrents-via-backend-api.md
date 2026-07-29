# Búsqueda de torrents vía backend Mirror API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar la búsqueda de torrents on-device por consultas al backend Arkiv Mirror API (`https://torrents.comparadorinternet.co`) para películas, series y anime, sin cambiar la reproducción ni la capa de streaming web.

**Architecture:** Enfoque A — `TorrentSearchApi` sigue siendo la fachada pública (mismos métodos que consumen ViewModels y player), pero su motor interno pasa de fan-out on-device a 2 llamadas HTTP: resolver el `slug` del título (por `tmdb_id`, con fallback a texto) y traer `/api/title/{slug}`, filtrar por tipo/temporada/episodio, mapear a `TorrentResult` y rankear con el comparator existente.

**Tech Stack:** Kotlin, OkHttp 4.12.0, `org.json`, DI manual (`AppGraph`), tests JUnit4 + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-07-25-torrents-via-backend-api-design.md`

## Global Constraints

- Sin Retrofit/Ktor: usar OkHttp + `org.json` (coherente con el resto del repo).
- DI manual en `app/src/main/java/com/arkiv/player/AppGraph.kt` (singletons `by lazy`).
- Tests unitarios JVM; correr con `./gradlew testDebugUnitTest`.
- Commits con identidad **lordmacu** (`user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`), **sin** línea `Co-Authored-By`.
- **Nunca `git add -A`** (varias sesiones comparten el working tree): agregar SIEMPRE rutas explícitas.
- No tocar `alfa-api/`, `balandro-addon/`, `build/` (quedan fuera del repo).
- Base URL default del backend: `https://torrents.comparadorinternet.co`.
- `TorrentResult`, `TorrentSource`, `resolveSource`, `TorrentLang`, `seedBucket`, `qualityRank`, `animeLangPriority` ya existen en `data/catalog/TorrentSearchApi.kt` y se REUSAN.

---

### Task 1: `MirrorTorrent` + `MirrorApiClient`

Cliente HTTP del backend: resolver slug y traer torrents de un título, con caché en memoria.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorApiClient.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorApiClientTest.kt`

**Interfaces:**
- Consumes: `ContentType` (`data/catalog/providers/SearchModels.kt`).
- Produces:
  - `data class MirrorTorrent(magnet:String?, infohash:String?, season:Int?, episode:Int?, episodeEnd:Int?, isPack:Boolean, langNorm:String?, langRaw:String?, quality:String?, seeders:Int?, sizeBytes:Long, sizeLabel:String?, source:String?, name:String?)`
  - `class MirrorApiClient(baseUrl:()->String, client:OkHttpClient=…, ttlMs:Long=1_800_000, nowMs:()->Long={…})`
  - `suspend fun MirrorApiClient.resolveSlug(tmdbId:Int?, kind:ContentType, titleFallback:String?): String?`
  - `suspend fun MirrorApiClient.titleTorrents(slug:String): List<MirrorTorrent>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MirrorApiClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(now: Long = 0L) =
        MirrorApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, nowMs = { now })

    @Test fun `resolveSlug por tmdbId devuelve el slug del primer resultado`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad","tmdb_id":"1396"}]}"""))
        val slug = client().resolveSlug(1396, ContentType.TV, titleFallback = null)
        assertEquals("breaking-bad", slug)
        val req = server.takeRequest()
        assert(req.path!!.contains("tmdb_id=1396")) { "path=${req.path}" }
        assert(req.path!!.contains("kind=serie")) { "path=${req.path}" }
    }

    @Test fun `resolveSlug cae a q cuando tmdbId no matchea`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))            // tmdb_id: vacío
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"matrix"}]}""")) // q: match
        val slug = client().resolveSlug(999999, ContentType.MOVIE, titleFallback = "Matrix")
        assertEquals("matrix", slug)
        server.takeRequest() // tmdb_id
        val q = server.takeRequest()
        assert(q.path!!.contains("q=Matrix")) { "path=${q.path}" }
    }

    @Test fun `resolveSlug devuelve null si no hay match ni fallback`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertNull(client().resolveSlug(1, ContentType.MOVIE, titleFallback = null))
    }

    @Test fun `titleTorrents parsea campos y tolera seeders null`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"slug":"breaking-bad","torrents":[
                {"magnet":"magnet:?xt=urn:btih:AAA","infohash":"aaa","season":1,"episode":1,
                 "episode_end":null,"is_pack":false,"lang_norm":"LATINO","lang_raw":"Latino/Inglés",
                 "quality":"WEB-DL 1080p","seeders":null,"size_bytes":2877628088,"size_label":"2.68 GB",
                 "source":"pelispanda","name":null}
            ]}""",
        ))
        val list = client().titleTorrents("breaking-bad")
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals(1, t.season); assertEquals(1, t.episode)
        assertEquals("LATINO", t.langNorm); assertEquals(0, t.seeders ?: 0)
        assertEquals(2877628088L, t.sizeBytes); assertEquals("magnet:?xt=urn:btih:AAA", t.magnet)
    }

    @Test fun `titleTorrents cachea por slug dentro del TTL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"torrents":[{"size_bytes":1,"is_pack":false}]}"""))
        val c = client(now = 0L)
        c.titleTorrents("x"); c.titleTorrents("x")
        assertEquals(1, server.requestCount) // segundo llamado vino de caché
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorApiClientTest"`
Expected: FAIL con "unresolved reference: MirrorApiClient".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Un torrent tal como lo devuelve el backend Mirror API (campos ya normalizados). */
data class MirrorTorrent(
    val magnet: String?,
    val infohash: String?,
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int?,
    val isPack: Boolean,
    val langNorm: String?,
    val langRaw: String?,
    val quality: String?,
    val seeders: Int?,
    val sizeBytes: Long,
    val sizeLabel: String?,
    val source: String?,
    val name: String?,
)

/**
 * Cliente del backend Arkiv Mirror API. Resuelve el `slug` de un título (por tmdb_id, con fallback
 * a texto) y trae sus torrents. Caché en memoria de torrents por slug (TTL configurable).
 */
class MirrorApiClient(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Cached(val torrents: List<MirrorTorrent>, val atMs: Long)
    private val torrentCache = ConcurrentHashMap<String, Cached>()

    private fun kindParam(kind: ContentType): String = when (kind) {
        ContentType.MOVIE -> "movie"
        ContentType.TV -> "serie"
        ContentType.ANIME -> "anime"
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()?.let { JSONObject(it) }
        }
    }.getOrNull()

    private fun firstSlug(url: String): String? {
        val arr = getJson(url)?.optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("slug")?.takeIf { it.isNotBlank() }
    }

    suspend fun resolveSlug(tmdbId: Int?, kind: ContentType, titleFallback: String?): String? =
        withContext(Dispatchers.IO) {
            val base = baseUrl().trimEnd('/')
            val k = kindParam(kind)
            if (tmdbId != null) {
                firstSlug("$base/api/search?tmdb_id=$tmdbId&kind=$k")?.let { return@withContext it }
            }
            val q = titleFallback?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext null
            val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            firstSlug("$base/api/search?q=$enc&kind=$k")
        }

    suspend fun titleTorrents(slug: String): List<MirrorTorrent> = withContext(Dispatchers.IO) {
        torrentCache[slug]?.takeIf { nowMs() - it.atMs < ttlMs }?.let { return@withContext it.torrents }
        val base = baseUrl().trimEnd('/')
        val arr = getJson("$base/api/title/$slug")?.optJSONArray("torrents") ?: return@withContext emptyList()
        val out = ArrayList<MirrorTorrent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MirrorTorrent(
                magnet = o.optString("magnet").takeIf { it.isNotBlank() },
                infohash = o.optString("infohash").takeIf { it.isNotBlank() },
                season = if (o.isNull("season")) null else o.optInt("season"),
                episode = if (o.isNull("episode")) null else o.optInt("episode"),
                episodeEnd = if (o.isNull("episode_end")) null else o.optInt("episode_end"),
                isPack = o.optBoolean("is_pack", false),
                langNorm = o.optString("lang_norm").takeIf { it.isNotBlank() },
                langRaw = o.optString("lang_raw").takeIf { it.isNotBlank() },
                quality = o.optString("quality").takeIf { it.isNotBlank() },
                seeders = if (o.isNull("seeders")) null else o.optInt("seeders"),
                sizeBytes = o.optLong("size_bytes", 0L),
                sizeLabel = o.optString("size_label").takeIf { it.isNotBlank() },
                source = o.optString("source").takeIf { it.isNotBlank() },
                name = o.optString("name").takeIf { it.isNotBlank() },
            )
        }
        torrentCache[slug] = Cached(out, nowMs())
        out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorApiClientTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorApiClient.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorApiClientTest.kt
git commit -m "feat(torrents): MirrorApiClient (resolveSlug + titleTorrents) con caché"
```

---

### Task 2: `MirrorLang` + `MirrorMapper`

Mapeo puro `MirrorTorrent` → `TorrentResult`, usando `lang_norm`/`quality` del backend.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorMapper.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorMapperTest.kt`

**Interfaces:**
- Consumes: `MirrorTorrent` (Task 1), `TorrentResult`/`TorrentLang` (`data/catalog/TorrentSearchApi.kt`).
- Produces:
  - `object MirrorLang { fun fromNorm(norm:String?): TorrentLang }`
  - `object MirrorMapper { fun toResult(t:MirrorTorrent): TorrentResult }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorMapperTest {
    private fun t(langNorm: String?, name: String? = null, quality: String? = null, seeders: Int? = null) =
        MirrorTorrent(magnet = "magnet:?xt=urn:btih:H", infohash = "h", season = null, episode = null,
            episodeEnd = null, isPack = false, langNorm = langNorm, langRaw = "raw", quality = quality,
            seeders = seeders, sizeBytes = 1_000_000_000L, sizeLabel = "0.9 GB", source = "x", name = name)

    @Test fun `lang_norm mapea a TorrentLang`() {
        assertEquals(TorrentLang.LATINO, MirrorLang.fromNorm("LATINO"))
        assertEquals(TorrentLang.CASTELLANO, MirrorLang.fromNorm("Castellano"))
        assertEquals(TorrentLang.DUAL, MirrorLang.fromNorm("DUAL"))
        assertEquals(TorrentLang.OTHER, MirrorLang.fromNorm(null))
        assertEquals(TorrentLang.OTHER, MirrorLang.fromNorm("marciano"))
    }

    @Test fun `toResult usa magnet, lang y seeders null como 0`() {
        val r = MirrorMapper.toResult(t(langNorm = "LATINO", name = "Peli 1080p"))
        assertEquals(TorrentLang.LATINO, r.lang)
        assertEquals("magnet:?xt=urn:btih:H", r.magnetUri)
        assertEquals(0, r.seeders)
        assertEquals(1_000_000_000L, r.sizeBytes)
    }

    @Test fun `toResult sintetiza nombre desde quality cuando name es null`() {
        val r = MirrorMapper.toResult(t(langNorm = "CASTELLANO", name = null, quality = "WEB-DL 1080p"))
        assertEquals(true, r.name.contains("1080p")) // qualityRank necesita ver la calidad
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorMapperTest"`
Expected: FAIL con "unresolved reference: MirrorMapper".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult

/** Mapea el `lang_norm` del backend al enum de idioma de la app. */
object MirrorLang {
    fun fromNorm(norm: String?): TorrentLang = when (norm?.trim()?.uppercase()) {
        "LATINO" -> TorrentLang.LATINO
        "CASTELLANO", "ESPAÑOL", "ESPANOL", "ES" -> TorrentLang.CASTELLANO
        "DUAL" -> TorrentLang.DUAL
        "VOSE", "JAP_SUB", "SUBTITULADO", "SUB" -> TorrentLang.JAP_SUB
        "INGLES", "INGLÉS", "ENGLISH", "EN" -> TorrentLang.ENGLISH
        else -> TorrentLang.OTHER
    }
}

/** Convierte un [MirrorTorrent] del backend en el [TorrentResult] que consume la UI/player. */
object MirrorMapper {
    fun toResult(t: MirrorTorrent): TorrentResult {
        val display = t.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(t.langRaw, t.quality, t.sizeLabel).joinToString(" · ")
                .takeIf { it.isNotBlank() } ?: (t.source ?: "torrent")
        return TorrentResult(
            name = display,
            seeders = t.seeders ?: 0,
            sizeBytes = t.sizeBytes,
            lang = MirrorLang.fromNorm(t.langNorm),
            infoHash = t.infohash,
            magnetUri = t.magnet,
            downloadUrl = null,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorMapperTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorMapper.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorMapperTest.kt
git commit -m "feat(torrents): mapeo MirrorTorrent→TorrentResult (lang_norm + quality del backend)"
```

---

### Task 3: `MirrorFilter`

Selección por tipo/temporada/episodio (película = todo; serie = S/E + packs; anime = nº abs/relativo) + corte por tamaño (packs exentos).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorFilter.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorFilterTest.kt`

**Interfaces:**
- Consumes: `MirrorTorrent` (Task 1), `ContentType`.
- Produces:
  - `object MirrorFilter`
  - `fun select(torrents:List<MirrorTorrent>, type:ContentType, season:Int, episodeNumbers:Set<Int>, maxSizeBytes:Long): List<MirrorTorrent>`
  - `fun selectAll(torrents:List<MirrorTorrent>, maxSizeBytes:Long): List<MirrorTorrent>`  // browse anime

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorFilterTest {
    private fun t(season: Int? = null, episode: Int? = null, episodeEnd: Int? = null,
                  isPack: Boolean = false, size: Long = 1L, tag: String = "") =
        MirrorTorrent("magnet:$tag", tag.ifBlank { "h$season$episode" }, season, episode, episodeEnd, isPack,
            "LATINO", "raw", "1080p", 1, size, "1B", "src", "n$tag")

    @Test fun `movie devuelve todos y aplica corte de tamaño (packs exentos)`() {
        val big = 30L shl 30; val small = 1L shl 30; val max = 21L shl 30
        val list = listOf(t(size = small, tag = "a"), t(size = big, tag = "b"),
            t(size = big, isPack = true, tag = "c"))
        val r = MirrorFilter.select(list, ContentType.MOVIE, 0, emptySet(), max)
        assertEquals(setOf("magnet:a", "magnet:c"), r.map { it.magnet }.toSet()) // b cae por tamaño
    }

    @Test fun `serie filtra por temporada y episodio exactos`() {
        val list = listOf(
            t(season = 1, episode = 1, tag = "ok"),
            t(season = 2, episode = 1, tag = "otraTemp"),
            t(season = 1, episode = 2, tag = "otroEp"),
        )
        val r = MirrorFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(1), maxSizeBytes = 0)
        assertEquals(listOf("magnet:ok"), r.map { it.magnet })
    }

    @Test fun `serie incluye pack que cubre el episodio por rango`() {
        val list = listOf(t(season = 1, episode = 1, episodeEnd = 8, isPack = true, tag = "pack"))
        val r = MirrorFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(5), maxSizeBytes = 0)
        assertEquals(listOf("magnet:pack"), r.map { it.magnet })
    }

    @Test fun `anime matchea nº absoluto o relativo sin exigir temporada`() {
        val list = listOf(
            t(season = 1, episode = 1085, tag = "abs"),   // fansub S01E<absoluto>
            t(season = 21, episode = 5, tag = "rel"),     // numeración relativa
            t(season = 1, episode = 3, tag = "no"),
        )
        val r = MirrorFilter.select(list, ContentType.ANIME, season = 0,
            episodeNumbers = setOf(5, 1085), maxSizeBytes = 0)
        assertEquals(setOf("magnet:abs", "magnet:rel"), r.map { it.magnet }.toSet())
    }

    @Test fun `selectAll devuelve todo respetando el corte de tamaño`() {
        val list = listOf(t(size = 1L shl 30, tag = "a"), t(size = 30L shl 30, tag = "b"))
        val r = MirrorFilter.selectAll(list, 21L shl 30)
        assertEquals(listOf("magnet:a"), r.map { it.magnet })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorFilterTest"`
Expected: FAIL con "unresolved reference: MirrorFilter".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType

/** Selección de torrents del backend por tipo/temporada/episodio + corte por tamaño (packs exentos). */
object MirrorFilter {

    fun select(
        torrents: List<MirrorTorrent>,
        type: ContentType,
        season: Int,
        episodeNumbers: Set<Int>,
        maxSizeBytes: Long,
    ): List<MirrorTorrent> {
        val byType = when (type) {
            ContentType.MOVIE -> torrents
            ContentType.TV -> torrents.filter { matches(it, season, episodeNumbers, seasonStrict = true) }
            ContentType.ANIME -> torrents.filter { matches(it, season, episodeNumbers, seasonStrict = false) }
        }
        return byType.filter { sizeOk(it, maxSizeBytes) }
    }

    /** Browse: todas las fuentes del título (sin filtro de episodio), respetando el corte de tamaño. */
    fun selectAll(torrents: List<MirrorTorrent>, maxSizeBytes: Long): List<MirrorTorrent> =
        torrents.filter { sizeOk(it, maxSizeBytes) }

    private fun sizeOk(t: MirrorTorrent, maxSizeBytes: Long): Boolean =
        maxSizeBytes <= 0 || t.sizeBytes <= 0 || t.sizeBytes <= maxSizeBytes || t.isPack

    private fun matches(t: MirrorTorrent, season: Int, epNums: Set<Int>, seasonStrict: Boolean): Boolean {
        if (t.isPack) {
            val start = t.episode; val end = t.episodeEnd
            if (start != null && end != null) return epNums.any { it in start..end }
            // pack de temporada/serie completa sin rango: acepta si la temporada casa (o no se exige)
            return !seasonStrict || season <= 0 || t.season == null || t.season == season
        }
        val e = t.episode ?: return false
        if (seasonStrict && season > 0 && t.season != null && t.season != season) return false
        return e in epNums
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorFilterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/MirrorFilter.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorFilterTest.kt
git commit -m "feat(torrents): MirrorFilter (peli/serie/anime + corte de tamaño con packs exentos)"
```

---

### Task 4: Rewire `TorrentSearchApi` al motor Mirror + `tmdbId`

`TorrentSearchApi` pasa a depender de `MirrorApiClient`. Se reimplementan los métodos públicos (mismos nombres, se añade `tmdbId`) usando resolveSlug → titleTorrents → MirrorFilter → MirrorMapper → nuevo `finalizeMirror` (reusa el comparator existente). Se exponen accesores en `SourceQuerySpec` para el anime.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` (constructor `207-210`; métodos `searchEpisode 243`, `searchMovie 259`, `searchAnime 280`, `searchAnimeBrowse 294`; `*Flow 639-668`)
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt` (`SourceQuerySpec`, exponer accesores)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/TorrentSearchMirrorTest.kt`

**Interfaces:**
- Consumes: `MirrorApiClient` (Task 1), `MirrorFilter` (Task 3), `MirrorMapper` (Task 2).
- Produces (firmas nuevas de la fachada; los `titles` quedan como fallback de texto):
  - `class TorrentSearchApi(mirror: MirrorApiClient)`
  - `suspend fun searchMovie(titles, year, langs, maxSizeBytes=0, tmdbId:Int?=null): List<TorrentResult>`
  - `suspend fun searchEpisode(titles, season, episode, langs, maxSizeBytes=0, tmdbId:Int?=null): List<TorrentResult>`
  - `suspend fun searchAnime(spec:SourceQuerySpec, langs, maxSizeBytes=0, tmdbId:Int?=null): List<TorrentResult>`
  - `suspend fun searchAnimeBrowse(titles, langs, maxSizeBytes=0, tmdbId:Int?=null): List<TorrentResult>`
  - `*Flow(...)` con los mismos params extra, emitiendo una sola vez.
  - En `SourceQuerySpec`: `val episodeNumbers:Set<Int>`, `val seasonForMirror:Int`, `val primaryTitle:String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentSearchApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TorrentSearchMirrorTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = TorrentSearchApi(
        MirrorApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, nowMs = { 0L }),
    )

    @Test fun `searchMovie resuelve slug por tmdbId y devuelve torrents mapeados`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"matrix"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[{"magnet":"magnet:?xt=urn:btih:AAA","infohash":"aaa","is_pack":false,
                "lang_norm":"CASTELLANO","quality":"1080p","seeders":null,"size_bytes":1073741824}]}""",
        ))
        val r = api().searchMovie(listOf("Matrix"), "1999", emptySet(), tmdbId = 603)
        assertEquals(1, r.size)
        assertEquals(TorrentLang.CASTELLANO, r[0].lang)
        assertTrue(r[0].magnetUri!!.startsWith("magnet:"))
    }

    @Test fun `searchEpisode filtra al capítulo pedido`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[
                {"magnet":"magnet:S1E1","infohash":"a","season":1,"episode":1,"is_pack":false,
                 "lang_norm":"LATINO","size_bytes":1000},
                {"magnet":"magnet:S1E2","infohash":"b","season":1,"episode":2,"is_pack":false,
                 "lang_norm":"LATINO","size_bytes":1000}
            ]}""",
        ))
        val r = api().searchEpisode(listOf("Breaking Bad"), 1, 1, emptySet(), tmdbId = 1396)
        assertEquals(listOf("magnet:S1E1"), r.map { it.magnetUri })
    }

    @Test fun `título inexistente en el backend devuelve vacío`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertEquals(emptyList<Any>(), api().searchMovie(listOf("noexiste"), "", emptySet()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.TorrentSearchMirrorTest"`
Expected: FAIL de compilación (constructor `TorrentSearchApi(MirrorApiClient)` no existe / falta `tmdbId`).

- [ ] **Step 3a: Exponer accesores en `SourceQuerySpec`**

En `AnimeEpisodeResolver.kt`, dentro de `class SourceQuerySpec` (después de `epNumbers`), agregar:

```kotlin
    /** Números que identifican el episodio (relativo y absoluto) para filtrar torrents del backend. */
    val episodeNumbers: Set<Int> get() = epNumbers
    /** Temporada a exigir en el backend (0 = no exigir, cuando no hay mapeo TVDB). */
    val seasonForMirror: Int get() = if (seasonKnown) season else 0
    /** Título principal para el fallback por texto del backend. */
    val primaryTitle: String? get() = searchTitles.firstOrNull()
```

- [ ] **Step 3b: Cambiar el constructor y borrar el motor on-device de `TorrentSearchApi`**

Reemplazar la cabecera de clase (`207-210`):

```kotlin
class TorrentSearchApi(
    private val mirror: com.arkiv.player.data.catalog.mirror.MirrorApiClient,
) {
```

Eliminar los miembros que ya no se usan: `rawCache`/`CacheEntry`/`cacheTtlMs`, `LANG_HINT_TOKENS`, `fastBackendIds`, `runSearch`, `runSearchFlow`, `finalize`, y los helpers de query on-device que queden sin referencias (`withVariants`, `buildMovieQueries`, `buildEpisodeQueries`, `movieRelevance`, `episodeRelevance`, `animeTitleRelevance`, `titleAnchors`, `significantWords`, `normalizeQuery`, `classify`, `LANG_HINT_TOKENS`). **Conservar:** `TorrentResult`, `RawTorrent`, `TorrentSource`, `TorrentLang`, `animeLangPriority`, `seedBucket`, `qualityRank`, `isPack`, `resolveSource`, `resolveDownloadUrl`, `buildMagnet`, `TRACKERS`, `noRedirectClient`, `BROWSER_UA`.

> Nota de ejecución: eliminar exactamente lo que quede sin referencias tras el resto del step; compilar y dejar que el compilador señale huérfanos. `ApibayBackend`/`KnabenBackend`/`TorrentBackend` se retiran en la Task 6 (aquí basta con dejar de instanciarlos).

- [ ] **Step 3c: Agregar `finalizeMirror` y reimplementar los métodos públicos**

Agregar el pipeline final (reusa el comparator; NO descarta seeders=0, porque el backend no reporta seeders fiables):

```kotlin
    private fun finalizeMirror(
        torrents: List<com.arkiv.player.data.catalog.mirror.MirrorTorrent>,
        langs: Set<TorrentLang>,
        langPriority: (TorrentLang) -> Int,
    ): List<TorrentResult> {
        val mapped = torrents.map { com.arkiv.player.data.catalog.mirror.MirrorMapper.toResult(it) }
        val deduped = mapped.distinctBy { it.dedupKey }
        val afterLang = deduped.filter { langs.isEmpty() || it.lang in langs || it.lang == TorrentLang.OTHER }
        return afterLang.sortedWith(
            compareBy<TorrentResult> { langPriority(it.lang) }
                .thenBy { seedBucket(it.seeders) }
                .thenBy { qualityRank(it.name, it.sizeBytes) }
                .thenByDescending { it.seeders },
        )
    }
```

Reimplementar los métodos públicos (import `com.arkiv.player.data.catalog.mirror.MirrorFilter`, `kotlinx.coroutines.flow.flow`, `kotlinx.coroutines.flow.Flow`):

```kotlin
    suspend fun searchMovie(
        titles: List<String>, year: String, langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0, tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.MOVIE, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.select(mirror.titleTorrents(slug), ContentType.MOVIE, 0, emptySet(), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    suspend fun searchEpisode(
        titles: List<String>, season: Int, episode: Int, langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0, tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.TV, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.select(mirror.titleTorrents(slug), ContentType.TV, season, setOf(episode), maxSizeBytes)
        return finalizeMirror(sel, langs) { it.ordinal }
    }

    suspend fun searchAnime(
        spec: SourceQuerySpec, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null,
    ): List<TorrentResult> {
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, spec.primaryTitle) ?: return emptyList()
        val sel = MirrorFilter.select(
            mirror.titleTorrents(slug), ContentType.ANIME, spec.seasonForMirror, spec.episodeNumbers, maxSizeBytes,
        )
        return finalizeMirror(sel, langs, ::animeLangPriority)
    }

    suspend fun searchAnimeBrowse(
        titles: List<String>, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val slug = mirror.resolveSlug(tmdbId, ContentType.ANIME, ts.firstOrNull()) ?: return emptyList()
        val sel = MirrorFilter.selectAll(mirror.titleTorrents(slug), maxSizeBytes)
        return finalizeMirror(sel, langs, ::animeLangPriority)
    }

    fun searchMovieFlow(titles: List<String>, year: String, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchMovie(titles, year, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    fun searchEpisodeFlow(titles: List<String>, season: Int, episode: Int, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchEpisode(titles, season, episode, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    fun searchAnimeFlow(spec: SourceQuerySpec, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchAnime(spec, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }

    fun searchAnimeBrowseFlow(titles: List<String>, langs: Set<TorrentLang>, maxSizeBytes: Long = 0, tmdbId: Int? = null): Flow<List<TorrentResult>> =
        flow { searchAnimeBrowse(titles, langs, maxSizeBytes, tmdbId).takeIf { it.isNotEmpty() }?.let { emit(it) } }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.TorrentSearchMirrorTest"`
Expected: PASS (3 tests).
Nota: si `AppGraph.kt` aún no compila por el cambio de constructor, ese arreglo es la Task 5; corré este test con `--tests` puntual (compila solo lo necesario) o ejecutá la Task 5 seguida.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/TorrentSearchMirrorTest.kt
git commit -m "feat(torrents): TorrentSearchApi consume el backend Mirror (tmdbId + filtrado por tipo)"
```

---

### Task 5: `SettingsStore.torrentApiUrl` + `AppGraph` wiring + call sites

Cablear el `MirrorApiClient`, desconectar el tier on-device, y pasar `tmdbId` en los 3 call sites. Deja la app compilando y usando SOLO el backend para torrents.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt` (nuevo campo `torrentApiUrl`)
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (`torrentSearchApi` `107-112`; quitar refs on-device en `torrentSearchApi`)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt:143-144`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt` (calls `~145`, `~248`, y el episodio si aplica)
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt` (pasar `tmdbId`; añadir `tmdbId` a `ShowMeta`)
- Test: extender `app/src/test/java/com/arkiv/player/data/SettingsStoreTest.kt` si existe; si no, verificación por compilación + smoke manual.

**Interfaces:**
- Consumes: `MirrorApiClient` (Task 1), fachada nueva de `TorrentSearchApi` (Task 4).
- Produces: `SettingsStore.torrentApiUrl: StateFlow<String>`, `SettingsStore.setTorrentApiUrl(v)`, `SettingsStore.DEFAULT_TORRENT_API_URL`.

- [ ] **Step 1: Agregar `torrentApiUrl` a `SettingsStore`**

En `SettingsStore.kt`, junto a `_webResolverUrl`:

```kotlin
    private val _torrentApiUrl = MutableStateFlow(prefs.getString(KEY_TORRENT_API_URL, DEFAULT_TORRENT_API_URL)!!)
    val torrentApiUrl: StateFlow<String> = _torrentApiUrl
```

setter junto a los otros:

```kotlin
    fun setTorrentApiUrl(v: String) { prefs.edit().putString(KEY_TORRENT_API_URL, v).apply(); _torrentApiUrl.value = v }
```

en `companion object`:

```kotlin
        private const val KEY_TORRENT_API_URL = "torrent_api_url"
        const val DEFAULT_TORRENT_API_URL = "https://torrents.comparadorinternet.co"
```

- [ ] **Step 2: Cablear `MirrorApiClient` en `AppGraph` y desconectar on-device**

En `AppGraph.kt`, reemplazar el bloque `torrentSearchApi` (`107-112`):

```kotlin
    val mirrorApiClient: com.arkiv.player.data.catalog.mirror.MirrorApiClient by lazy {
        com.arkiv.player.data.catalog.mirror.MirrorApiClient(baseUrl = { settings.torrentApiUrl.value })
    }

    val torrentSearchApi: TorrentSearchApi by lazy {
        TorrentSearchApi(mirror = mirrorApiClient)
    }
```

Dejar de referenciar `registryProviderBackend`/`ApibayBackend()`/`KnabenBackend()` desde `torrentSearchApi` (ya hecho arriba). No borrar aún los otros miembros on-device (Task 6). Si `refreshRemoteProviders()`/`prewarmCloudflareHosts()` se llaman en `init`/arranque, pueden quedarse (siguen compilando); si molestan, comentarlos con `// TODO(Task 6): retirar`.

- [ ] **Step 3: Pasar `tmdbId` en `CineDetailScreen`**

En `CineDetailScreen.kt:143-144`:

```kotlin
                val flow = if (ep != null) graph.torrentSearchApi.searchEpisodeFlow(d.searchTitles, ep.season, ep.episode, langs, maxBytes, tmdbId = d.id)
                           else graph.torrentSearchApi.searchMovieFlow(d.searchTitles, d.year, langs, maxBytes, tmdbId = d.id)
```

- [ ] **Step 4: Pasar `tmdbId` en `SearchViewModel`**

En `SearchViewModel.kt`, en cada llamada a `searchMovieFlow`/`searchEpisodeFlow` donde exista un detalle TMDB `d`, pasar `tmdbId = d?.id`. En la búsqueda por texto libre sin detalle (`~145`, `searchMovie(listOf(q), ...)`), dejar `tmdbId = null` (usa el fallback por texto del backend). Ejemplo en `~248`:

```kotlin
                        torrentSearchApi.searchMovieFlow(titles, d?.year ?: "", ALL_LANGS, maxBytes, tmdbId = d?.id)
```

- [ ] **Step 5: Pasar `tmdbId` en `AnimeSourceProvider`**

Añadir `tmdbId: Int?` al `ShowMeta` que produce `showMeta(show)` (calcularlo como `mapping?.tmdbId ?: simkl?.tmdbId`, igual que en `buildTitles`), y pasarlo en las 4 llamadas:

```kotlin
        return torrentSearchApi.searchAnime(spec, langs, tmdbId = meta.tmdbId)
        // …searchAnimeFlow(spec, langs, maxSizeBytes, tmdbId = meta.tmdbId)
        // …searchAnimeBrowse(showMeta(show).titles, langs, tmdbId = meta.tmdbId)
        // …searchAnimeBrowseFlow(titles, langs, maxSizeBytes, tmdbId = meta.tmdbId)
```

> Ejecución: leer la definición de `ShowMeta`/`showMeta` en `AnimeSourceProvider.kt` y agregar el campo respetando su forma actual.

- [ ] **Step 6: Compilar y correr toda la suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; todos los tests verdes (incluye Tasks 1-4).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SettingsStore.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt
git commit -m "feat(torrents): cablear backend Mirror en AppGraph + tmdbId en catálogo/búsqueda/anime"
```

- [ ] **Step 8: Smoke test en device (ADB)**

Instalar y verificar a mano (memoria: build en Mac, instalar por ADB WiFi al S24+ o Fire Stick):
- Película del catálogo → "Buscar fuentes" muestra torrents del backend.
- Un capítulo de serie → solo ese capítulo (o pack que lo cubra).
- Un episodio de anime → fuentes correctas.
- Reproducir un magnet → arranca en VLC (reproducción sin cambios).
- Streaming web (pelisplus/gnula) → sigue funcionando.

---

### Task 6 (commit aparte): borrar el código muerto on-device

Una vez validado en device, retirar los archivos del tier on-device que quedaron sin uso.

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/catalog/providers/` (RegistryProviderBackend, JsonProviderBackend, DeclarativeHtmlBackend, HttpFetcher, HtmlParser, JsRenderer, CloudflareSolver, CfClearanceStore, ProviderRegistry, ProviderDefinition, SearchModels salvo lo que use `mirror`)
- Delete: `app/src/main/assets/providers.json`
- Delete en `TorrentSearchApi.kt`: `ApibayBackend`, `KnabenBackend`, `TorrentBackend`, `RawTorrent` si queda sin uso (lo usa `MirrorMapper`? no — `MirrorMapper` produce `TorrentResult`; verificar refs antes de borrar `RawTorrent`).
- Delete en `AppGraph.kt`: `providerFetcher`, `jsRenderer`, `registryProviderBackend`, `providerDefinitions`, `loadBundledProviderDefinitions`, `refreshRemoteProviders`, `prewarmCloudflareHosts`, `cloudflareSolver` (si no lo usa la capa web).
- Delete tests huérfanos: `HttpFetcherCacheTest`, `bitsearch_sample.html`, `bundled_providers.json`, etc.

**Cuidado:** `SearchContext`/`ContentType`/`ProviderBackend` en `SearchModels.kt` — `ContentType` y (posiblemente) `SearchContext` los usa la capa `mirror`; NO borrar `ContentType`. La capa de **streaming web** (`web/`) NO se toca.

- [ ] **Step 1:** Buscar referencias colgantes antes de borrar

Run: `grep -rn "ProviderBackend\|ApibayBackend\|KnabenBackend\|RegistryProviderBackend\|providers.json\|HttpFetcher\|CloudflareSolver" app/src/main`
Confirmar que solo aparecen en los archivos a borrar (o en `mirror`/`web`, que se conservan).

- [ ] **Step 2:** Borrar archivos y correr la suite

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, verde.

- [ ] **Step 3:** Compilar el APK debug para asegurar que no quedó nada roto

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -u app/src/main/java/com/arkiv/player/data/catalog/providers app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers
git commit -m "refactor(torrents): retirar el tier de búsqueda on-device (backend Mirror único)"
```

---

## Self-Review

**Spec coverage:**
- §1 objetivo/alcance → Tasks 4-5 (motor + wiring), §7 borrado → Task 6. ✅
- §2 contrato backend → Task 1 (resolveSlug + titleTorrents). ✅
- §3 enfoque A → Task 4 (fachada intacta). ✅
- §4 MirrorApiClient + settings → Task 1 + Task 5 step 1-2. ✅
- §5 mapeo → Task 2. ✅
- §6 filtrado por tipo → Task 3 + Task 4. ✅
- §7 threading tmdb_id → Task 4 (firmas) + Task 5 (call sites). ✅
- §8 qué se retira → Task 5 (desconectar) + Task 6 (borrar). ✅
- §9 testing → tests en Tasks 1-5. ✅
- §10 no cambia (streaming web, reproducción) → explícito en Tasks 5-6. ✅

**Placeholder scan:** sin TBD/TODO salvo el marcador intencional `// TODO(Task 6)` (código muerto temporal). Los pasos que dependen de forma existente (`ShowMeta`, huérfanos) llevan nota de ejecución concreta. ✅

**Type consistency:** `MirrorTorrent`, `MirrorApiClient.resolveSlug/titleTorrents`, `MirrorMapper.toResult`, `MirrorFilter.select/selectAll`, `finalizeMirror`, y las firmas `searchMovie/searchEpisode/searchAnime(+tmdbId)` coinciden entre tasks. `episodeNumbers/seasonForMirror/primaryTitle` definidos en Task 4 y consumidos ahí mismo. ✅
