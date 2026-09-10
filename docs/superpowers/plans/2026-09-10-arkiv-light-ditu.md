# Sub-proyecto 3A: Caracol (Ditu) directo — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app reproduzca el catálogo VOD y los canales en vivo de Caracol Streaming (Ditu) hablándole directo a su API desde el aparato, con DASH+Widevine, sin pasar por ningún servidor propio.

**Architecture:** Un cliente nuevo en `data/ditu/` con el mismo molde que `data/magis/` (cliente HTTP → capas de catálogo y resolución → una clase `Fuente` que implementa `FuenteDeContenido`). Como ahora hay dos fuentes, `AppGraph.fuenteDeContenido` pasa a ser una `FuenteCompuesta` que reparte por el prefijo del `ref` y mezcla las búsquedas; ninguna pantalla se entera. La reproducción suma un `DituExoPlayer` que negocia Widevine vía `MediaItem.DrmConfiguration`.

**Tech Stack:** Kotlin, Compose, OkHttp, `org.json`, media3/ExoPlayer (`media3-exoplayer-dash` ya está en el build), JUnit + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-10-arkiv-light-ditu-rcn-design.md`

## Global Constraints

- **Cero servidor propio.** Las únicas llamadas nuevas permitidas son a `middleware.ditu.caracoltv.com`, al CDN de imágenes `image-registry.ditu.caracoltv.com` y al CDN de video que devuelva la propia API. Nada de gateway, nada de PocketBase.
- **Headers obligatorios en TODA llamada a la API de Ditu**, exactamente estos: `restful: yes`, `Accept: application/json, text/plain, */*`, `User-Agent: okhttp/4.12.0`. Sin ellos el CDN responde 403 en el manifiesto y en los segmentos.
- **Base de la API, exacta:** `https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL`.
- **URL de licencia Widevine:** `https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL/CONTENT/LICENSE`.
- **Los refs son locales y no vencen** (mismo criterio que `MagisRef`). Prefijo `ditu1`.
- **No se intenta filtrar la publicidad.** Ya se midió en `main` que no se puede; lo que se hace es recuperarse del corte.
- **Español de Bogotá en todo lo que se escribe** —UI, KDoc, mensajes de commit—: `tú`, `tienes`, `dime`. Nunca `vos`/`tenés`.
- **Ningún commit lleva pie de coautoría de Claude.** Ni `Co-Authored-By`, ni `Claude-Session`.
- Cada tarea termina con `./gradlew :app:testDebugUnitTest` en verde. Las tareas 9 y 10 además con `./gradlew :app:assembleDebug`.

---

### Task 1: `DituRef` — la referencia local

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituRef.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituRefTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `internal data class DituRef(val contentId: String, val contentType: String)` con `codificar(): String`, `esSerie: Boolean`, y `DituRef.decodificar(ref: String): DituRef?`. Constante `DituRef.PREFIJO = "ditu1"`.

Lo que el `ref` tiene que llevar es exactamente lo que la API necesita para resolver: el `contentId` y el `contentType` (`VOD`, `BUNDLE` o `GROUP_OF_BUNDLES`). Nada más, y nada que venza.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DituRefTest {

    @Test fun `ida y vuelta de una pelicula`() {
        val ref = DituRef(contentId = "12345", contentType = "VOD")
        assertEquals("ditu1:VOD:12345", ref.codificar())
        assertEquals(ref, DituRef.decodificar("ditu1:VOD:12345"))
    }

    @Test fun `ida y vuelta de una serie`() {
        val ref = DituRef(contentId = "998", contentType = "BUNDLE")
        assertEquals("ditu1:BUNDLE:998", ref.codificar())
        assertEquals(ref, DituRef.decodificar(ref.codificar()))
    }

    /** El contentId va ÚLTIMO para que no importe si algún día trae un `:` adentro. */
    @Test fun `un contentId con dos puntos sobrevive`() {
        val ref = DituRef(contentId = "a:b:c", contentType = "VOD")
        assertEquals(ref, DituRef.decodificar(ref.codificar()))
    }

    @Test fun `solo BUNDLE y GROUP_OF_BUNDLES son series`() {
        assertTrue(DituRef("1", "BUNDLE").esSerie)
        assertTrue(DituRef("1", "GROUP_OF_BUNDLES").esSerie)
        assertFalse(DituRef("1", "VOD").esSerie)
    }

    @Test fun `un ref de otra fuente no se entiende`() {
        assertNull(DituRef.decodificar("magis1:movie:0:C42"))
        assertNull(DituRef.decodificar(""))
        assertNull(DituRef.decodificar("ditu1:VOD"))
        assertNull(DituRef.decodificar("ditu1:VOD:"))
    }

    /**
     * Un ref viejo del gateway (`base64url(json).hmac`) se lee igual: es opaco por contrato, no
     * por criptografía. La firma no se valida —no hay con qué, y lo que sale de acá no autoriza
     * nada, solo dice qué pedirle a Caracol— y el vencimiento se ignora a propósito.
     */
    @Test fun `un ref viejo del gateway se entiende`() {
        val json = """{"s":"ditu","p":{"content_id":"777","content_type":"BUNDLE"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val leido = DituRef.decodificar("$datos.firmaquenadievalida")
        assertEquals(DituRef("777", "BUNDLE"), leido)
    }

    /** El ref viejo de OTRA fuente no es nuestro, aunque tenga la misma forma. */
    @Test fun `un ref viejo de magis no lo reclama ditu`() {
        val json = """{"s":"magis","p":{"content_id":"C42","program_type":"movie"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertNull(DituRef.decodificar("$datos.firma"))
    }

    /** Sin `content_type` el gateway mandaba VOD implícito. */
    @Test fun `un ref viejo sin content_type cae a VOD`() {
        val json = """{"s":"ditu","p":{"content_id":"5"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals(DituRef("5", "VOD"), DituRef.decodificar("$datos.firma"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituRefTest*'`
Expected: FAIL — no compila, `DituRef` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONObject
import java.util.Base64

/**
 * Qué hay que reproducir de Caracol, en una cadena que la app guarda en su base.
 *
 * Mismo criterio que [com.arkiv.player.data.magis.MagisRef]: el gateway acuñaba un ref firmado que
 * vencía, y sin servidor no hay a quién pedirle uno nuevo. Tampoco hace falta — para resolver,
 * Caracol solo necesita el `contentId` y el `contentType`, que no vencen. Y a diferencia de Magis,
 * acá eso importa de verdad: los ids de Caracol son estables, así que un capítulo guardado en la
 * biblioteca sigue reproduciendo el mes que viene.
 *
 * [contentType] es lo que la API llama al contenido: `VOD` (película o capítulo suelto), `BUNDLE`
 * (una temporada) o `GROUP_OF_BUNDLES` (una serie con varias temporadas).
 */
internal data class DituRef(
    val contentId: String,
    val contentType: String = "VOD",
) {
    val esSerie: Boolean get() = contentType in SERIES

    /** `ditu1:<contentType>:<contentId>` — el contentId va último para que no importe si algún
     *  día trae un `:` adentro. */
    fun codificar(): String = "$PREFIJO:$contentType:$contentId"

    internal companion object {
        const val PREFIJO = "ditu1"

        /** Los tipos que hay que listar antes de poder reproducir. */
        val SERIES = setOf("BUNDLE", "GROUP_OF_BUNDLES")

        /** Lee un ref propio o uno viejo del gateway. `null` si no es de Ditu o no se entiende. */
        fun decodificar(ref: String): DituRef? {
            if (ref.isBlank()) return null
            if (ref.startsWith("$PREFIJO:")) {
                val partes = ref.split(":", limit = 3)
                if (partes.size < 3) return null
                val contentId = partes[2].takeIf { it.isNotBlank() } ?: return null
                return DituRef(contentId = contentId, contentType = partes[1].ifBlank { "VOD" })
            }
            return deRefDelGateway(ref)
        }

        private fun deRefDelGateway(ref: String): DituRef? {
            val datos = ref.substringBefore('.').takeIf { it.isNotBlank() && it != ref } ?: return null
            val json = runCatching {
                // `java.util.Base64` y no `android.util.Base64`: el de Android es un stub que
                // devuelve null en los tests de JVM, y este es justo el camino que solo corre una
                // vez, en silencio, para no perder lo que ya está guardado.
                JSONObject(String(Base64.getUrlDecoder().decode(datos), Charsets.UTF_8))
            }.getOrNull() ?: return null
            if (json.optString("s") != "ditu") return null
            val p = json.optJSONObject("p") ?: return null
            val contentId = p.optString("content_id").takeIf { it.isNotBlank() } ?: return null
            return DituRef(
                contentId = contentId,
                contentType = p.optString("content_type").ifBlank { "VOD" },
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituRefTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituRef.kt app/src/test/java/com/arkiv/player/data/ditu/DituRefTest.kt
git commit -m "feat(ditu): ref local que no vence, y lee los viejos del gateway"
```

---

### Task 2: `DituEntitlement` — por qué no se puede ver

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituEntitlement.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituEntitlementTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `internal object DituEntitlement { fun bloqueo(userData: JSONObject): String? }` — el mensaje del primer bloqueo activo, o `null` si no hay ninguno.

Caracol dice por qué no dejas ver, y son siete motivos distintos. Si se colapsan a "no se pudo reproducir", un geobloqueo se lee como un bug de la app y se diagnostica a ciegas. Esta pieza es pura para poder probar los siete sin red.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DituEntitlementTest {

    private fun userData(vararg flags: Pair<String, Boolean>): JSONObject {
        val ent = JSONObject()
        flags.forEach { (k, v) -> ent.put(k, v) }
        return JSONObject().put(
            "resultObj",
            JSONObject().put("containers", org.json.JSONArray().put(JSONObject().put("entitlement", ent))),
        )
    }

    @Test fun `sin ningun flag no hay bloqueo`() {
        assertNull(DituEntitlement.bloqueo(userData("isGeoBlocked" to false)))
    }

    @Test fun `cada flag tiene su propio mensaje`() {
        assertEquals("solo disponible en Colombia", DituEntitlement.bloqueo(userData("isGeoBlocked" to true)))
        assertEquals("requiere suscripción", DituEntitlement.bloqueo(userData("isChannelNotSubscribed" to true)))
        assertEquals("control parental activo", DituEntitlement.bloqueo(userData("isPCBlocked" to true)))
        assertEquals("contenido OOH bloqueado", DituEntitlement.bloqueo(userData("isContentOOHBlocked" to true)))
        assertEquals("geofence bloqueado", DituEntitlement.bloqueo(userData("isGeofencedBlocked" to true)))
        assertEquals("deportes en blackout", DituEntitlement.bloqueo(userData("isSportBlackoutBlocked" to true)))
        assertEquals("plataforma no permitida", DituEntitlement.bloqueo(userData("isPlatformBlacklisted" to true)))
    }

    /** Con varios activos gana el más informativo, que es el primero de la lista. */
    @Test fun `con varios bloqueos gana el geo`() {
        val d = userData("isPlatformBlacklisted" to true, "isGeoBlocked" to true)
        assertEquals("solo disponible en Colombia", DituEntitlement.bloqueo(d))
    }

    /**
     * Una respuesta sin la forma esperada NO es un bloqueo. Tratarla como tal diría "requiere
     * suscripción" ante un error de red, que manda a la persona a buscar el problema donde no está.
     */
    @Test fun `una respuesta rara no bloquea`() {
        assertNull(DituEntitlement.bloqueo(JSONObject()))
        assertNull(DituEntitlement.bloqueo(JSONObject().put("resultObj", JSONObject())))
        assertNull(DituEntitlement.bloqueo(userData()))
    }

    /** El flag como string "true" no cuenta: solo el booleano de verdad. */
    @Test fun `solo el booleano true bloquea`() {
        val d = JSONObject().put(
            "resultObj",
            JSONObject().put(
                "containers",
                org.json.JSONArray().put(JSONObject().put("entitlement", JSONObject().put("isGeoBlocked", "true"))),
            ),
        )
        assertNull(DituEntitlement.bloqueo(d))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituEntitlementTest*'`
Expected: FAIL — `DituEntitlement` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Por qué Caracol no te deja ver algo.
 *
 * La API responde con siete banderas distintas y cada una manda a la persona a un lado diferente:
 * un geobloqueo se arregla con VPN o no se arregla, una suscripción se compra, un control parental
 * se desactiva en el aparato. Colapsarlas a "no se pudo reproducir" convierte cualquiera de las
 * siete en un bug aparente de la app.
 *
 * El orden importa: con varias activas gana la primera, que es la más informativa.
 */
internal object DituEntitlement {

    private val BLOQUEOS = linkedMapOf(
        "isGeoBlocked" to "solo disponible en Colombia",
        "isChannelNotSubscribed" to "requiere suscripción",
        "isPCBlocked" to "control parental activo",
        "isContentOOHBlocked" to "contenido OOH bloqueado",
        "isGeofencedBlocked" to "geofence bloqueado",
        "isSportBlackoutBlocked" to "deportes en blackout",
        "isPlatformBlacklisted" to "plataforma no permitida",
    )

    /**
     * El mensaje del primer bloqueo activo, o `null` si no hay ninguno.
     *
     * Una respuesta que no tiene la forma esperada devuelve `null` a propósito: no saber si hay
     * bloqueo no es lo mismo que haberlo, y afirmarlo mandaría a buscar el problema donde no está.
     */
    fun bloqueo(userData: JSONObject): String? {
        val contenedores = userData.optJSONObject("resultObj")?.optJSONArray("containers") ?: return null
        val ent = contenedores.optJSONObject(0)?.optJSONObject("entitlement") ?: return null
        for ((flag, mensaje) in BLOQUEOS) {
            // `opt` y no `optBoolean`: optBoolean("x", false) devuelve true para el string "true",
            // y un string no es lo que la API manda cuando de verdad bloquea.
            if (ent.opt(flag) == true) return mensaje
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituEntitlementTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituEntitlement.kt app/src/test/java/com/arkiv/player/data/ditu/DituEntitlementTest.kt
git commit -m "feat(ditu): los siete motivos de bloqueo, cada uno con su mensaje"
```

---

### Task 3: `DituCliente` — el HTTP y la cookie

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituCliente.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituClienteTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `internal class DituException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)`
  - `internal data class DituRespuesta(val json: JSONObject, val playbackToken: String)`
  - `internal interface DituClienteLike { suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject; suspend fun getConToken(path: String): DituRespuesta }`
  - `internal class DituCliente(baseUrl: String = DituCliente.BASE, http: OkHttpClient = …) : DituClienteLike` con `companion object { const val BASE = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL" }`

Dos cosas que solo se ven acá: los tres headers sin los cuales el CDN responde 403, y que el `playback_token` **no viene en el cuerpo sino en el `Set-Cookie` de la respuesta**. Sin ese token, el servidor de licencias responde 500 y no hay imagen.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DituClienteTest {

    private lateinit var server: MockWebServer

    @Before fun arranca() {
        server = MockWebServer()
        server.start()
    }

    @After fun apaga() {
        server.shutdown()
    }

    private fun cliente() = DituCliente(baseUrl = server.url("/AGL").toString().trimEnd('/'))

    /** Sin estos tres headers el CDN responde 403, tanto en el manifiesto como en los segmentos. */
    @Test fun `manda los headers que la API exige`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        cliente().get("TRAY/SEARCH/VOD")

        val pedido = server.takeRequest()
        assertEquals("yes", pedido.getHeader("restful"))
        assertEquals("okhttp/4.12.0", pedido.getHeader("User-Agent"))
        assertEquals("application/json, text/plain, */*", pedido.getHeader("Accept"))
    }

    @Test fun `arma la ruta bajo la base y agrega los parametros`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        cliente().get("TRAY/SEARCH/VOD", mapOf("query" to "rigo", "filter_contentType" to "BUNDLE"))

        val pedido = server.takeRequest()
        assertTrue(pedido.path!!.startsWith("/AGL/TRAY/SEARCH/VOD?"))
        assertTrue(pedido.path!!.contains("query=rigo"))
        assertTrue(pedido.path!!.contains("filter_contentType=BUNDLE"))
    }

    /** LA COOKIE. No viene en el cuerpo: viene en el Set-Cookie de la respuesta. */
    @Test fun `getConToken saca el playback_token del Set-Cookie`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}""")
                .addHeader("Set-Cookie", "playback_token=abc123; Path=/; HttpOnly"),
        )

        val r = cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("abc123", r.playbackToken)
        assertEquals("https://cdn/x.mpd", r.json.getJSONObject("resultObj").getString("src"))
    }

    /**
     * Sin cookie NO se falla: el token vacío tiene que llegar hasta el reproductor, porque el
     * síntoma real (licencia en 500) se diagnostica mucho más rápido si se ve "sin playback_token"
     * en el log que si la resolución explota antes con otro mensaje.
     */
    @Test fun `sin Set-Cookie el token queda vacio y no falla`() = runTest {
        server.enqueue(MockResponse().setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}"""))

        val r = cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("", r.playbackToken)
    }

    @Test fun `otras cookies no se confunden con el token`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"ok":1}""")
                .addHeader("Set-Cookie", "session=zzz; Path=/")
                .addHeader("Set-Cookie", "playback_token=elbueno; Path=/"),
        )

        assertEquals("elbueno", cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2").playbackToken)
    }

    @Test fun `un status de error se convierte en DituException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))

        val e = runCatching { cliente().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("esperaba DituException y vino $e", e is DituException)
        assertTrue(e!!.message!!.contains("500"))
    }

    @Test fun `un cuerpo que no es JSON se convierte en DituException`() = runTest {
        server.enqueue(MockResponse().setBody("<html>error</html>"))

        val e = runCatching { cliente().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("esperaba DituException y vino $e", e is DituException)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituClienteTest*'`
Expected: FAIL — `DituCliente` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class DituException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

/**
 * Una respuesta de Caracol junto con el `playback_token` que traía, o `""` si no traía.
 *
 * El token existe como campo aparte porque NO viene en el cuerpo: viaja en el `Set-Cookie` de la
 * respuesta de `CONTENT/VIDEOURL`, y es lo que después autoriza la petición de licencia Widevine.
 */
internal data class DituRespuesta(val json: JSONObject, val playbackToken: String)

internal interface DituClienteLike {
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject
    suspend fun getConToken(path: String): DituRespuesta
}

/**
 * La app hablándole a la API AVS de Caracol Streaming.
 *
 * No hay autenticación de ningún tipo: el contenido gratuito se pide y se sirve. Lo que sí es
 * obligatorio son los tres headers de [CABECERAS] — sin ellos el CDN responde 403 tanto en el
 * manifiesto como en los segmentos, que es un fallo que se lee como "el video no existe".
 */
internal class DituCliente(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : DituClienteLike {

    override suspend fun get(path: String, params: Map<String, String>): JSONObject =
        pedir(path, params).json

    override suspend fun getConToken(path: String): DituRespuesta = pedir(path, emptyMap())

    private suspend fun pedir(path: String, params: Map<String, String>): DituRespuesta =
        withContext(Dispatchers.IO) {
            val url = ("$baseUrl/$path").toHttpUrlOrNull()?.newBuilder()
                ?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                ?.build()
                ?: throw DituException("URL inválida: $baseUrl/$path")

            val pedido = Request.Builder().url(url).apply {
                CABECERAS.forEach { (k, v) -> header(k, v) }
            }.build()

            val respuesta = runCatching { http.newCall(pedido).execute() }
                .getOrElse { throw DituException("Caracol no responde: ${it.message}", it) }

            respuesta.use {
                if (!it.isSuccessful) throw DituException("Caracol respondió ${it.code} en $path")
                val cuerpo = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(cuerpo) }
                    .getOrElse { e -> throw DituException("Caracol devolvió algo que no es JSON en $path", e) }
                // `headers("Set-Cookie")` y no el body: el token viaja como cookie.
                val token = it.headers("Set-Cookie")
                    .firstOrNull { c -> c.startsWith("$COOKIE_TOKEN=") }
                    ?.substringAfter("$COOKIE_TOKEN=")
                    ?.substringBefore(';')
                    .orEmpty()
                DituRespuesta(json, token)
            }
        }

    internal companion object {
        const val BASE = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL"

        /** La misma base con la ruta de licencia. Existe una variante de ANDROIDTV
         *  (`.../ANDROIDTV/ALL/CONTENT/LICENSE`) que el gateway define pero nunca usa; acá se porta
         *  la que está probada. */
        const val LICENCIA = "$BASE/CONTENT/LICENSE"

        const val COOKIE_TOKEN = "playback_token"

        val CABECERAS = mapOf(
            "restful" to "yes",
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "okhttp/4.12.0",
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituClienteTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituCliente.kt app/src/test/java/com/arkiv/player/data/ditu/DituClienteTest.kt
git commit -m "feat(ditu): cliente HTTP con los headers que exige el CDN y la cookie de la licencia"
```

---

### Task 4: `DituCatalogo` — catálogo, búsqueda y canales

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituCatalogo.kt`
- Create: `app/src/test/java/com/arkiv/player/data/ditu/FakeDituCliente.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituCatalogoTest.kt`

**Interfaces:**
- Consumes: `DituClienteLike`, `DituRef`.
- Produces:
  - `internal data class DituItem(val contentId: String, val titulo: String, val contentType: String, val posterUrl: String, val anio: String, val esPelicula: Boolean)`
  - `internal data class DituCanal(val channelId: Int, val nombre: String, val logoUrl: String, val assetId: Int, val orden: Int)`
  - `internal class DituCatalogo(cliente: DituClienteLike)` con `suspend fun catalogo(): List<DituItem>`, `suspend fun buscar(q: String): List<DituItem>`, `suspend fun canales(): List<DituCanal>`.

El mismo endpoint (`TRAY/SEARCH/VOD`) sirve el catálogo entero con `query` vacío y la búsqueda con `query` lleno. Las imágenes salen del CDN propio de Caracol armando la URL a mano desde `metadata.pictureUrl`.

- [ ] **Step 1: Write the fake and the failing test**

`FakeDituCliente.kt`:

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Cliente de mentira para probar las capas de arriba sin red. Guarda lo que le pidieron —el test
 * afirma sobre la RUTA y los PARÁMETROS, que es donde están los errores de puerto— y devuelve el
 * JSON que se le haya cargado para esa ruta.
 */
internal class FakeDituCliente : DituClienteLike {
    val llamadas = mutableListOf<Pair<String, Map<String, String>>>()
    private val respuestas = mutableMapOf<String, JSONObject>()
    var token: String = ""
    var falla: Throwable? = null

    fun responde(path: String, json: String) {
        respuestas[path] = JSONObject(json)
    }

    override suspend fun get(path: String, params: Map<String, String>): JSONObject {
        llamadas += path to params
        falla?.let { throw it }
        return respuestas[path] ?: JSONObject("""{"resultObj":{"containers":[]}}""")
    }

    override suspend fun getConToken(path: String): DituRespuesta =
        DituRespuesta(get(path, emptyMap()), token)
}
```

`DituCatalogoTest.kt`:

```kotlin
package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituCatalogoTest {

    private val TRAY = "TRAY/SEARCH/VOD"

    @Test fun `el catalogo se pide con query vacio`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).catalogo()

        val (path, params) = fake.llamadas.first()
        assertEquals(TRAY, path)
        assertEquals("", params["query"])
    }

    @Test fun `la busqueda manda el texto`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).buscar("rigo")

        assertEquals("rigo", fake.llamadas.first().second["query"])
    }

    @Test fun `se quedan series y peliculas, y nada mas`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Serie A","contentType":"BUNDLE","pictureUrl":"pa"}},
          {"id":"2","metadata":{"title":"Grupo B","contentType":"GROUP_OF_BUNDLES","pictureUrl":"pb"}},
          {"id":"3","metadata":{"title":"Peli C","contentType":"VOD","contentSubtype":"MOVIE","pictureUrl":"pc"}},
          {"id":"4","metadata":{"title":"Clip D","contentType":"VOD","contentSubtype":"CLIP"}},
          {"id":"5","metadata":{"title":"Vivo E","contentType":"LIVE"}}
        ]}}
        """)

        val items = DituCatalogo(fake).catalogo()

        assertEquals(listOf("1", "2", "3"), items.map { it.contentId })
        assertEquals(listOf("BUNDLE", "GROUP_OF_BUNDLES", "VOD"), items.map { it.contentType })
        assertEquals(listOf(false, false, true), items.map { it.esPelicula })
    }

    @Test fun `sin id o sin titulo el item se descarta`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"metadata":{"title":"Sin id","contentType":"BUNDLE"}},
          {"id":"9","metadata":{"title":"","contentType":"BUNDLE"}},
          {"id":"10","metadata":{"title":"Buena","contentType":"BUNDLE"}}
        ]}}
        """)

        assertEquals(listOf("10"), DituCatalogo(fake).catalogo().map { it.contentId })
    }

    /** El póster se arma a mano contra el CDN propio de Caracol desde `pictureUrl`. */
    @Test fun `el poster sale del CDN de Caracol`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","pictureUrl":"carpeta/img"}}
        ]}}
        """)

        assertEquals(
            "https://image-registry.ditu.caracoltv.com/carpeta/img/portrait-thin-promotional-tablet.jpg",
            DituCatalogo(fake).catalogo().first().posterUrl,
        )
    }

    /** Sin `pictureUrl` se cae al posterList del propio container antes que quedarse sin imagen. */
    @Test fun `sin pictureUrl usa el posterList`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE"},
           "posterList":[{"fileType":"otro","fileUrl":"https://x/no.jpg"},
                         {"fileType":"icon","fileUrl":"https://x/si.jpg"}]}
        ]}}
        """)

        assertEquals("https://x/si.jpg", DituCatalogo(fake).catalogo().first().posterUrl)
    }

    @Test fun `el anio sale del primer campo de fecha que tenga cuatro digitos`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","releaseDate":"2019-04-02"}},
          {"id":"2","metadata":{"title":"B","contentType":"BUNDLE","releaseYear":"2021"}},
          {"id":"3","metadata":{"title":"C","contentType":"BUNDLE","releaseDate":"nada"}}
        ]}}
        """)

        assertEquals(listOf("2019", "2021", ""), DituCatalogo(fake).catalogo().map { it.anio })
    }

    // --- canales en vivo -------------------------------------------------------

    @Test fun `los canales se piden ordenados por orderId`() = runTest {
        val fake = FakeDituCliente()
        DituCatalogo(fake).canales()

        val (path, params) = fake.llamadas.first()
        assertEquals("TRAY/LIVECHANNELS", path)
        assertEquals("orderId", params["orderBy"])
        assertEquals("asc", params["sortOrder"])
    }

    /**
     * El assetId sale de ESTA respuesta y no del EPG: el EPG devuelve `assets` vacío para el
     * programa en curso, así que pedirlo ahí es un viaje que vuelve sin nada.
     */
    @Test fun `de cada canal salen id, nombre, logo y assetId del MASTER`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true,"orderId":1},
           "assets":[{"assetType":"OTRO","assetId":11,"logoSmall":"s.png"},
                     {"assetType":"MASTER","assetId":22,"logoMedium":"m.png"}]}
        ]}}
        """)

        val canal = DituCatalogo(fake).canales().single()
        assertEquals(7, canal.channelId)
        assertEquals("Caracol", canal.nombre)
        assertEquals(22, canal.assetId)
        assertEquals("m.png", canal.logoUrl)
    }

    @Test fun `sin MASTER se usa el primer asset con id`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true},
           "assets":[{"assetType":"OTRO","assetId":11}]}
        ]}}
        """)

        assertEquals(11, DituCatalogo(fake).canales().single().assetId)
    }

    @Test fun `los canales inactivos o sin nombre no entran`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":1,"channelName":"Apagado","isActive":false},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":2,"channelName":"","isActive":true},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":3,"channelName":"Bueno","isActive":true},"assets":[{"assetId":9}]}
        ]}}
        """)

        assertEquals(listOf(3), DituCatalogo(fake).canales().map { it.channelId })
    }

    /** Un canal sin ningún assetId no se puede reproducir: no tiene sentido ofrecerlo. */
    @Test fun `un canal sin assetId no entra`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":4,"channelName":"Sin asset","isActive":true},"assets":[]}
        ]}}
        """)

        assertTrue(DituCatalogo(fake).canales().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituCatalogoTest*'`
Expected: FAIL — `DituCatalogo` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONArray
import org.json.JSONObject

/** Un título del catálogo de Caracol: serie (`BUNDLE`/`GROUP_OF_BUNDLES`) o película (`VOD`). */
internal data class DituItem(
    val contentId: String,
    val titulo: String,
    val contentType: String,
    val posterUrl: String = "",
    val anio: String = "",
) {
    val esPelicula: Boolean get() = contentType == "VOD"
    fun ref(): String = DituRef(contentId, contentType).codificar()
}

/** Un canal en vivo de Caracol, con el `assetId` que hace falta para resolverlo. */
internal data class DituCanal(
    val channelId: Int,
    val nombre: String,
    val logoUrl: String,
    val assetId: Int,
    val orden: Int = 0,
)

/**
 * Qué hay para ver en Caracol.
 *
 * El mismo endpoint sirve las dos cosas: `TRAY/SEARCH/VOD` con `query` vacío devuelve el catálogo
 * entero (unos 330 títulos en una sola llamada) y con `query` lleno, la búsqueda.
 */
internal class DituCatalogo(private val cliente: DituClienteLike) {

    suspend fun catalogo(): List<DituItem> = itemsDe(cliente.get(TRAY, mapOf("query" to "")))

    suspend fun buscar(q: String): List<DituItem> = itemsDe(cliente.get(TRAY, mapOf("query" to q)))

    suspend fun canales(): List<DituCanal> {
        val json = cliente.get(LIVECHANNELS, mapOf("orderBy" to "orderId", "sortOrder" to "asc"))
        return contenedoresDe(json).mapNotNull { canalDe(it) }
    }

    private fun itemsDe(json: JSONObject): List<DituItem> =
        contenedoresDe(json).mapNotNull { itemDe(it) }

    private fun itemDe(c: JSONObject): DituItem? {
        val m = c.optJSONObject("metadata") ?: JSONObject()
        val tipo = m.optString("contentType").uppercase()
        val subtipo = (m.optString("contentSubtype").ifBlank { m.optString("contentSubType") }).uppercase()
        val tipoDeRef = when {
            tipo == "BUNDLE" || tipo == "GROUP_OF_BUNDLES" -> tipo
            tipo == "VOD" && subtipo == "MOVIE" -> "VOD"
            // Todo lo demás (clips, LIVE, promos) no es algo que se pueda abrir como título.
            else -> return null
        }
        val id = c.optString("id").takeIf { it.isNotBlank() } ?: return null
        val titulo = m.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        return DituItem(
            contentId = id,
            titulo = titulo,
            contentType = tipoDeRef,
            posterUrl = posterDe(c),
            anio = anioDe(m),
        )
    }

    private fun canalDe(c: JSONObject): DituCanal? {
        val m = c.optJSONObject("metadata") ?: return null
        if (m.opt("isActive") != true) return null
        val id = m.optInt("channelId", 0).takeIf { it != 0 } ?: return null
        val nombre = m.optString("channelName").trim().takeIf { it.isNotBlank() } ?: return null
        val assets = c.optJSONArray("assets") ?: JSONArray()
        val lista = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        // El assetId sale de ACÁ y no del EPG: el EPG devuelve `assets` vacío para el programa en
        // curso, así que ese viaje vuelve sin nada.
        val asset = lista.firstOrNull { it.optString("assetType") == "MASTER" && it.optInt("assetId", 0) != 0 }
            ?: lista.firstOrNull { it.optInt("assetId", 0) != 0 }
            ?: return null
        val logo = lista.firstNotNullOfOrNull { it.optString("logoMedium").takeIf { s -> s.isNotBlank() } }
            ?: lista.firstNotNullOfOrNull { it.optString("logoBig").takeIf { s -> s.isNotBlank() } }
            ?: lista.firstNotNullOfOrNull { it.optString("logoSmall").takeIf { s -> s.isNotBlank() } }
            ?: ""
        return DituCanal(
            channelId = id,
            nombre = nombre,
            logoUrl = logo,
            assetId = asset.optInt("assetId"),
            orden = m.optInt("orderId", 0),
        )
    }

    internal companion object {
        const val TRAY = "TRAY/SEARCH/VOD"
        const val LIVECHANNELS = "TRAY/LIVECHANNELS"
        const val CDN_IMAGENES = "https://image-registry.ditu.caracoltv.com/"
        const val POSTER = "portrait-thin-promotional-tablet.jpg"
        const val FONDO = "landscape-regular-clean-tablet.jpg"

        fun contenedoresDe(json: JSONObject): List<JSONObject> {
            val arr = json.optJSONObject("resultObj")?.optJSONArray("containers") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        /** Póster vertical del CDN propio; si no hay `pictureUrl`, el `icon` del `posterList`. */
        fun posterDe(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            if (pic.isNotBlank()) return "$CDN_IMAGENES$pic/$POSTER"
            val arr = c.optJSONArray("posterList") ?: return ""
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("fileType") == "icon" }
                ?.optString("fileUrl").orEmpty()
        }

        /** Fondo apaisado del CDN propio; "" si no hay `pictureUrl`. */
        fun fondoDe(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            return if (pic.isBlank()) "" else "$CDN_IMAGENES$pic/$FONDO"
        }

        fun anioDe(m: JSONObject): String {
            for (campo in listOf("releaseDate", "releaseYear", "year")) {
                val v = m.optString(campo)
                if (v.length >= 4 && v.take(4).all { it.isDigit() }) return v.take(4)
            }
            return ""
        }

        /** `assetId` del asset MASTER, o del primero que tenga uno. `null` si ninguno. */
        fun assetMaster(c: JSONObject): Int? {
            val arr = c.optJSONArray("assets") ?: return null
            val lista = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            return lista.firstOrNull { it.optString("assetType") == "MASTER" && it.optInt("assetId", 0) != 0 }
                ?.optInt("assetId")
                ?: lista.firstOrNull { it.optInt("assetId", 0) != 0 }?.optInt("assetId")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituCatalogoTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituCatalogo.kt app/src/test/java/com/arkiv/player/data/ditu/
git commit -m "feat(ditu): catalogo, busqueda y canales en vivo"
```

---

### Task 5: `DituEpisodios` — temporadas y capítulos

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituEpisodios.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituEpisodiosTest.kt`

**Interfaces:**
- Consumes: `DituClienteLike`, `DituCatalogo.Companion` (`contenedoresDe`, `assetMaster`, `posterDe`, `fondoDe`), `DituRef`.
- Produces:
  - `internal data class DituEpisodio(val numero: Int, val temporada: Int, val titulo: String, val contentId: String)`
  - `internal data class DituTemporada(val episodios: List<DituEpisodio>, val tituloSerie: String, val posterUrl: String, val fondoUrl: String, val temporada: Int)`
  - `internal class DituEpisodios(cliente: DituClienteLike)` con `suspend fun de(ref: DituRef): DituTemporada`.

Acá está la trampa del puerto: **en un `GROUP_OF_BUNDLES` el número de temporada NO es un campo del episodio, es la posición del bundle en la lista de hijos.** Un `BUNDLE` suelto sí usa `metadata.season`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituEpisodiosTest {

    private fun bundleCon(vararg eps: String) = """
    {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},
      "containers":[${eps.joinToString(",")}]}]}}
    """

    private fun ep(id: String, num: Int, temporada: Int?, titulo: String, asset: Int? = 1): String {
        val season = temporada?.let { ""","season":$it""" } ?: ""
        val assets = asset?.let { ""","assets":[{"assetType":"MASTER","assetId":$it}]""" } ?: ""
        return """{"id":"$id","metadata":{"episodeNumber":$num,"episodeTitle":"$titulo"$season}$assets}"""
    }

    @Test fun `un BUNDLE lista sus capitulos con su temporada`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(
            ep("e1", 1, 2, "Uno"),
            ep("e2", 2, 2, "Dos"),
        ))

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))

        assertEquals(listOf(1, 2), t.episodios.map { it.numero })
        assertEquals(listOf(2, 2), t.episodios.map { it.temporada })
        assertEquals(listOf("Uno", "Dos"), t.episodios.map { it.titulo })
        assertEquals("Rigo", t.tituloSerie)
        assertEquals(2, t.temporada)
    }

    @Test fun `sin season el capitulo es de la temporada 1`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 1, null, "Uno")))

        assertEquals(1, DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.single().temporada)
    }

    @Test fun `sin titulo el capitulo se llama por su numero`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 7, 1, "")))

        assertEquals("Episodio 7", DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.single().titulo)
    }

    /** Sin assetId no se puede reproducir: mostrarlo sería ofrecer algo que falla al tocarlo. */
    @Test fun `un capitulo sin assetId no entra`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(
            ep("e1", 1, 1, "Sin asset", asset = null),
            ep("e2", 2, 1, "Con asset"),
        ))

        assertEquals(listOf("e2"), DituEpisodios(fake).de(DituRef("99", "BUNDLE")).episodios.map { it.contentId })
    }

    /**
     * LA TRAMPA. En un grupo, la temporada de cada capítulo es la POSICIÓN de su bundle en la lista
     * de hijos, no el `season` que traiga el episodio: los bundles de un grupo suelen venir todos
     * con `season: 1` y sin esto las cuatro temporadas se pisarían entre sí.
     */
    @Test fun `en un grupo la temporada es la posicion del bundle`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        fake.responde("CONTENT/DETAIL/BUNDLE/b1", bundleCon(ep("e1", 1, 1, "T1E1")))
        fake.responde("CONTENT/DETAIL/BUNDLE/b2", bundleCon(ep("e2", 1, 1, "T2E1")))

        val t = DituEpisodios(fake).de(DituRef("g9", "GROUP_OF_BUNDLES"))

        assertEquals(listOf(1, 2), t.episodios.map { it.temporada })
        assertEquals(listOf("e1", "e2"), t.episodios.map { it.contentId })
    }

    @Test fun `los hijos del grupo se piden filtrando por parentId`() = runTest {
        val fake = FakeDituCliente()
        DituEpisodios(fake).de(DituRef("g9", "GROUP_OF_BUNDLES"))

        val (path, params) = fake.llamadas.first()
        assertEquals("TRAY/SEARCH/VOD", path)
        assertEquals("g9", params["filter_parentId"])
        assertEquals("BUNDLE", params["filter_contentType"])
    }

    @Test fun `un bundle sin containers no explota, devuelve vacio`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """{"resultObj":{"containers":[]}}""")

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))
        assertTrue(t.episodios.isEmpty())
    }

    @Test fun `las imagenes salen del CDN de Caracol`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", bundleCon(ep("e1", 1, 1, "Uno")))

        val t = DituEpisodios(fake).de(DituRef("99", "BUNDLE"))
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/portrait-thin-promotional-tablet.jpg", t.posterUrl)
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/landscape-regular-clean-tablet.jpg", t.fondoUrl)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituEpisodiosTest*'`
Expected: FAIL — `DituEpisodios` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import org.json.JSONObject

/** Un capítulo de Caracol. El [contentId] es lo único que hace falta para resolverlo. */
internal data class DituEpisodio(
    val numero: Int,
    val temporada: Int,
    val titulo: String,
    val contentId: String,
) {
    fun ref(): String = DituRef(contentId, "VOD").codificar()
}

/** Una temporada con lo que hace falta para pintar su pantalla. */
internal data class DituTemporada(
    val episodios: List<DituEpisodio>,
    val tituloSerie: String = "",
    val posterUrl: String = "",
    val fondoUrl: String = "",
    val temporada: Int = 1,
)

/**
 * Los capítulos de una serie de Caracol.
 *
 * Hay dos formas y no se parecen. Un `BUNDLE` es UNA temporada y sus capítulos vienen dentro del
 * detalle. Un `GROUP_OF_BUNDLES` es una serie de varias temporadas: hay que pedir sus bundles
 * hijos y aplanar.
 *
 * En ese segundo caso, **el número de temporada es la posición del bundle en la lista de hijos**,
 * no el `season` del episodio: los bundles de un grupo suelen venir todos con `season: 1` y creerles
 * haría que las temporadas se pisen entre sí.
 */
internal class DituEpisodios(private val cliente: DituClienteLike) {

    suspend fun de(ref: DituRef): DituTemporada =
        if (ref.contentType == "GROUP_OF_BUNDLES") deGrupo(ref.contentId) else deBundle(ref.contentId, 0)

    private suspend fun deGrupo(groupId: String): DituTemporada {
        val hijos = cliente.get(
            DituCatalogo.TRAY,
            mapOf("filter_parentId" to groupId, "filter_contentType" to "BUNDLE"),
        )
        val ids = DituCatalogo.contenedoresDe(hijos).mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }
        val todos = mutableListOf<DituEpisodio>()
        var primera: DituTemporada? = null
        ids.forEachIndexed { indice, bundleId ->
            val t = deBundle(bundleId, temporadaForzada = indice + 1)
            if (primera == null) primera = t
            todos += t.episodios
        }
        val cabeza = primera
        return DituTemporada(
            episodios = todos,
            tituloSerie = cabeza?.tituloSerie.orEmpty(),
            posterUrl = cabeza?.posterUrl.orEmpty(),
            fondoUrl = cabeza?.fondoUrl.orEmpty(),
            temporada = 1,
        )
    }

    /** [temporadaForzada] en 0 significa "usa la que diga el episodio". */
    private suspend fun deBundle(bundleId: String, temporadaForzada: Int): DituTemporada {
        val detalle = cliente.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val externo = DituCatalogo.contenedoresDe(detalle).firstOrNull()
            ?: return DituTemporada(emptyList())
        val crudos = externo.optJSONArray("containers")
        val episodios = buildList {
            for (i in 0 until (crudos?.length() ?: 0)) {
                val ep = crudos!!.optJSONObject(i) ?: continue
                add(episodioDe(ep, temporadaForzada) ?: continue)
            }
        }
        val meta = externo.optJSONObject("metadata") ?: JSONObject()
        return DituTemporada(
            episodios = episodios,
            tituloSerie = meta.optString("title").trim(),
            posterUrl = DituCatalogo.posterDe(externo),
            fondoUrl = DituCatalogo.fondoDe(externo),
            temporada = temporadaForzada.takeIf { it > 0 } ?: (episodios.firstOrNull()?.temporada ?: 1),
        )
    }

    private fun episodioDe(ep: JSONObject, temporadaForzada: Int): DituEpisodio? {
        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: return null
        // Sin assetId no hay nada que reproducir: ofrecerlo sería prometer algo que falla al tocarlo.
        DituCatalogo.assetMaster(ep) ?: return null
        val m = ep.optJSONObject("metadata") ?: JSONObject()
        val numero = m.optInt("episodeNumber", 0)
        return DituEpisodio(
            numero = numero,
            temporada = temporadaForzada.takeIf { it > 0 } ?: m.optInt("season", 1).takeIf { it > 0 } ?: 1,
            titulo = m.optString("episodeTitle").trim().ifBlank { "Episodio $numero" },
            contentId = id,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituEpisodiosTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituEpisodios.kt app/src/test/java/com/arkiv/player/data/ditu/DituEpisodiosTest.kt
git commit -m "feat(ditu): capitulos de un bundle y de un grupo de temporadas"
```

---

### Task 6: `DituResolve` — los tres pasos y la licencia

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituResolve.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituResolveTest.kt`

**Interfaces:**
- Consumes: `DituClienteLike`, `DituEntitlement`, `DituCatalogo.Companion.assetMaster`, `DituRef`.
- Produces: `internal class DituResolve(cliente: DituClienteLike)` con `suspend fun vod(ref: DituRef): GatewayPlayable` y `suspend fun vivo(canal: DituCanal): GatewayPlayable`.

Los tres pasos son `DETAIL` (para el `assetId`) → `USERDATA` (para el entitlement) → `VIDEOURL` (para la URL y la cookie). El orden importa: el entitlement se revisa **antes** de pedir la URL, para que un geobloqueo dé su mensaje en vez de un fallo del reproductor.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituResolveTest {

    private val LIC = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL/CONTENT/LICENSE"

    private fun fakeListo(): FakeDituCliente {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/pelicula.mpd"}}""")
        fake.token = "tok999"
        return fake
    }

    @Test fun `una pelicula se resuelve en tres pasos y en orden`() = runTest {
        val fake = fakeListo()

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(
            listOf("CONTENT/DETAIL/VOD/42", "CONTENT/USERDATA/VOD/42", "CONTENT/VIDEOURL/VOD/42/7"),
            fake.llamadas.map { it.first },
        )
        assertEquals("https://cdn/pelicula.mpd", play.url)
        assertEquals("application/dash+xml", play.mime)
        assertEquals("ditu", play.kind)
    }

    /** La licencia sin la cookie responde 500: el token TIENE que llegar como header de licencia. */
    @Test fun `la cookie del token viaja como header de la licencia`() = runTest {
        val play = DituResolve(fakeListo()).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertEquals(mapOf("Cookie" to "playback_token=tok999"), play.drmLicenseHeaders)
    }

    /** Sin token igual se devuelve algo reproducible: el que falla después es el servidor de
     *  licencias, y su 500 se diagnostica mejor que un error nuestro inventado antes. */
    @Test fun `sin token no se manda header de cookie`() = runTest {
        val fake = fakeListo()
        fake.token = ""

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertTrue(play.drmLicenseHeaders.isEmpty())
    }

    @Test fun `un bloqueo de entitlement corta antes de pedir la URL`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/USERDATA/VOD/42", """
        {"resultObj":{"containers":[{"entitlement":{"isGeoBlocked":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()

        assertTrue(e is DituException)
        assertTrue(e!!.message!!.contains("solo disponible en Colombia"))
        assertTrue("no debió pedir la URL", fake.llamadas.none { it.first.startsWith("CONTENT/VIDEOURL") })
    }

    @Test fun `sin assetId no se puede resolver`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/DETAIL/VOD/42", """{"resultObj":{"containers":[{"assets":[]}]}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    @Test fun `sin src no se puede reproducir`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    /** Un BUNDLE no es reproducible: se resuelve su primer capítulo con assetId. */
    @Test fun `un BUNDLE resuelve su primer capitulo`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"sinasset","metadata":{}},
          {"id":"e2","metadata":{},"assets":[{"assetType":"MASTER","assetId":5}]}
        ]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/e2", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/e2/5", """{"resultObj":{"src":"https://cdn/e2.mpd"}}""")

        val play = DituResolve(fake).vod(DituRef("99", "BUNDLE"))

        assertEquals("https://cdn/e2.mpd", play.url)
    }

    // --- en vivo ---------------------------------------------------------------

    /** El vivo son DOS pasos: el assetId ya vino con el canal, así que no hay DETAIL. */
    @Test fun `un canal en vivo se resuelve en dos pasos`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/USERDATA/LIVE/7", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/LIVE/7/22", """{"resultObj":{"src":"https://cdn/vivo.mpd"}}""")
        fake.token = "tokvivo"

        val play = DituResolve(fake).vivo(DituCanal(7, "Caracol", "l.png", 22))

        assertEquals(listOf("CONTENT/USERDATA/LIVE/7", "CONTENT/VIDEOURL/LIVE/7/22"), fake.llamadas.map { it.first })
        assertEquals("https://cdn/vivo.mpd", play.url)
        assertEquals(mapOf("Cookie" to "playback_token=tokvivo"), play.drmLicenseHeaders)
    }

    @Test fun `un canal bloqueado dice por que`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/USERDATA/LIVE/7", """
        {"resultObj":{"containers":[{"entitlement":{"isChannelNotSubscribed":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).vivo(DituCanal(7, "C", "", 22)) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("requiere suscripción"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituResolveTest*'`
Expected: FAIL — `DituResolve` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import com.arkiv.player.data.gateway.GatewayPlayable
import org.json.JSONObject

/**
 * De un `ref` a algo que el reproductor pueda abrir.
 *
 * Son tres pasos y el orden no es negociable: `DETAIL` da el `assetId`, `USERDATA` dice si te
 * dejan ver, y recién entonces `VIDEOURL` entrega la URL del `.mpd` **y la cookie
 * `playback_token`**, que es lo que después autoriza la licencia Widevine. Revisar el entitlement
 * antes de pedir la URL es lo que convierte un geobloqueo en un mensaje claro en vez de un fallo
 * del reproductor diez segundos más tarde.
 *
 * El vivo son solo dos: el `assetId` ya viene con el canal (ver `DituCatalogo.canales`).
 */
internal class DituResolve(private val cliente: DituClienteLike) {

    suspend fun vod(ref: DituRef): GatewayPlayable {
        val (contentId, assetId) = if (ref.esSerie) {
            primerCapitulo(ref.contentId)
        } else {
            val detalle = cliente.get("CONTENT/DETAIL/${ref.contentType}/${ref.contentId}")
            val contenedor = DituCatalogo.contenedoresDe(detalle).firstOrNull()
                ?: throw DituException("Caracol no devolvió el detalle de ${ref.contentId}")
            val asset = DituCatalogo.assetMaster(contenedor)
                ?: throw DituException("Caracol no tiene un asset reproducible para ${ref.contentId}")
            ref.contentId to asset
        }

        revisarEntitlement("CONTENT/USERDATA/VOD/$contentId")
        return playableDe("CONTENT/VIDEOURL/VOD/$contentId/$assetId", contentId)
    }

    suspend fun vivo(canal: DituCanal): GatewayPlayable {
        revisarEntitlement("CONTENT/USERDATA/LIVE/${canal.channelId}")
        return playableDe("CONTENT/VIDEOURL/LIVE/${canal.channelId}/${canal.assetId}", canal.nombre)
    }

    /** Un BUNDLE no es reproducible en sí: lo que se abre es su primer capítulo con asset. */
    private suspend fun primerCapitulo(bundleId: String): Pair<String, Int> {
        val detalle = cliente.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val externo = DituCatalogo.contenedoresDe(detalle).firstOrNull()
            ?: throw DituException("Caracol no devolvió el detalle de $bundleId")
        val crudos = externo.optJSONArray("containers")
        for (i in 0 until (crudos?.length() ?: 0)) {
            val ep = crudos!!.optJSONObject(i) ?: continue
            val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
            val asset = DituCatalogo.assetMaster(ep) ?: continue
            return id to asset
        }
        throw DituException("Ningún capítulo de $bundleId se puede reproducir")
    }

    private suspend fun revisarEntitlement(path: String) {
        val datos: JSONObject = cliente.get(path)
        DituEntitlement.bloqueo(datos)?.let { throw DituException("Caracol: $it") }
    }

    private suspend fun playableDe(path: String, queEs: String): GatewayPlayable {
        val r = cliente.getConToken(path)
        val src = r.json.optJSONObject("resultObj")?.optString("src").orEmpty()
        if (src.isBlank()) throw DituException("Caracol no devolvió una URL de video para $queEs")
        return GatewayPlayable(
            kind = FUENTE,
            url = src,
            mime = "application/dash+xml",
            drmLicenseUrl = DituCliente.LICENCIA,
            // Sin token NO se falla acá: si la licencia después responde 500, ese error dice más
            // que uno inventado antes de intentarlo.
            drmLicenseHeaders = if (r.playbackToken.isBlank()) {
                emptyMap()
            } else {
                mapOf("Cookie" to "${DituCliente.COOKIE_TOKEN}=${r.playbackToken}")
            },
        )
    }

    internal companion object {
        const val FUENTE = "ditu"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituResolveTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituResolve.kt app/src/test/java/com/arkiv/player/data/ditu/DituResolveTest.kt
git commit -m "feat(ditu): resolver VOD y vivo, con la cookie que autoriza la licencia"
```

---

### Task 7: `DituFuente` — el contrato que la app ya conoce

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ditu/DituFuente.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/FuenteDeContenido.kt` (agregar `fun reconoce(ref: String): Boolean`)
- Modify: `app/src/main/java/com/arkiv/player/data/magis/MagisFuente.kt` (implementar `reconoce`)
- Test: `app/src/test/java/com/arkiv/player/data/ditu/DituFuenteTest.kt`

**Interfaces:**
- Consumes: `DituCatalogo`, `DituEpisodios`, `DituResolve`, `TmdbApi` (`suspend fun search(type: String, query: String, page: Int = 1): List<TmdbItem>`, y `TmdbItem` tiene `id: Int` y `title: String`).
- Produces: `internal class DituFuente(catalogo, episodios, resolucion, tmdb) : FuenteDeContenido`, y el método nuevo `reconoce(ref: String): Boolean` en la interfaz.

TMDB se usa **solo** para el `tmdbId` y el título canónico. Las imágenes las pone Caracol, y las de TMDB entran únicamente si Caracol no trajo ninguna.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ditu

import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DituFuenteTest {

    private fun fuente(fake: FakeDituCliente, tmdb: com.arkiv.player.data.catalog.TmdbApi? = null) =
        DituFuente(DituCatalogo(fake), DituEpisodios(fake), DituResolve(fake), tmdb)

    @Test fun `reconoce sus refs y no los ajenos`() {
        val f = fuente(FakeDituCliente())
        assertTrue(f.reconoce("ditu1:VOD:42"))
        assertFalse(f.reconoce("magis1:movie:0:C42"))
        assertFalse(f.reconoce(""))
    }

    @Test fun `la busqueda emite arranque, resultados y fin`() = runTest {
        val fake = FakeDituCliente()
        fake.responde(DituCatalogo.TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Rigo","contentType":"BUNDLE","pictureUrl":"p"}},
          {"id":"2","metadata":{"title":"Peli","contentType":"VOD","contentSubtype":"MOVIE"}}
        ]}}
        """)

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "rigo")).toList()

        assertTrue(eventos.first() is SearchEvent.SourceStart)
        val resultados = eventos.filterIsInstance<SearchEvent.ResultEvent>()
        assertEquals(listOf("Rigo", "Peli"), resultados.map { it.item.title })
        assertEquals(listOf("series", "movie"), resultados.map { it.item.kind })
        assertEquals(listOf("ditu1:BUNDLE:1", "ditu1:VOD:2"), resultados.map { it.item.ref })
        assertEquals("ditu", resultados.first().item.source)
        assertTrue(eventos.any { it is SearchEvent.SourceDone })
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    /** Una fuente que se cae emite su error y termina: no puede dejar el Flow colgado. */
    @Test fun `si Caracol falla la busqueda emite SourceError y Done`() = runTest {
        val fake = FakeDituCliente()
        fake.falla = DituException("Caracol no responde")

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "x")).toList()

        val error = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("ditu", error.source)
        assertTrue(error.error.contains("Caracol"))
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    @Test fun `resolve traduce el ref y devuelve el playable con DRM`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/x.mpd"}}""")
        fake.token = "t1"

        val play = fuente(fake).resolve("ditu1:VOD:42")

        assertEquals("https://cdn/x.mpd", play.url)
        assertTrue(play.drmLicenseUrl.endsWith("/CONTENT/LICENSE"))
        assertEquals("playback_token=t1", play.drmLicenseHeaders["Cookie"])
    }

    @Test fun `un ref que no es de Caracol es un GatewayException`() = runTest {
        val e = runCatching { fuente(FakeDituCliente()).resolve("magis1:movie:0:C1") }.exceptionOrNull()
        assertTrue(e is com.arkiv.player.data.gateway.GatewayException)
    }

    @Test fun `los capitulos llegan con su ref y la serie con sus imagenes`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},"containers":[
          {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno","season":1},
           "assets":[{"assetType":"MASTER","assetId":1}]}
        ]}]}}
        """)

        val (eps, serie) = fuente(fake).episodesConSerie("ditu1:BUNDLE:99")

        assertEquals(1, eps.single().number)
        assertEquals("Uno", eps.single().title)
        assertEquals("ditu1:VOD:e1", eps.single().ref)
        assertEquals("Rigo", serie!!.titulo)
        assertTrue(serie.posterUrl.endsWith("portrait-thin-promotional-tablet.jpg"))
        assertTrue(serie.backdropUrl.endsWith("landscape-regular-clean-tablet.jpg"))
        // Sin TMDB cableado no hay id: el bloque igual viaja, con lo que Caracol sí sabe.
        assertEquals(0, serie.tmdbId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DituFuenteTest*'`
Expected: FAIL — `DituFuente` no existe.

- [ ] **Step 3: Add `reconoce` to the interface**

En `FuenteDeContenido.kt`, agregar dentro de la interfaz:

```kotlin
    /**
     * Si este `ref` es de esta fuente. Existe desde que hay más de una: `FuenteCompuesta` lo usa
     * para repartir sin tener que adivinar por prefijo desde afuera —cada fuente sabe leer los
     * suyos, incluidos los viejos del gateway, que no llevan prefijo visible—.
     */
    fun reconoce(ref: String): Boolean
```

En `MagisFuente.kt`, agregar junto a los otros `override`:

```kotlin
    override fun reconoce(ref: String): Boolean = MagisRef.decodificar(ref) != null
```

- [ ] **Step 4: Write the implementation**

```kotlin
package com.arkiv.player.data.ditu

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.FuenteDeContenido
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySerie
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Caracol Streaming hablando el mismo contrato que ya habla Magis.
 *
 * Es el puerto de `DituAdapter` (`arkiv-api/src/arkiv_api/adapters/ditu/adapter.py`): lo que el
 * gateway hacía entre la API de Caracol y la app —armar los resultados, aplanar las temporadas,
 * cruzar con TMDB— vive acá.
 *
 * TMDB se usa SOLO para el `tmdbId` y el título canónico. Las imágenes las pone Caracol, que las
 * tiene siempre; las de TMDB entran únicamente si Caracol no trajo ninguna.
 */
internal class DituFuente(
    private val catalogo: DituCatalogo,
    private val episodios: DituEpisodios,
    private val resolucion: DituResolve,
    private val tmdb: TmdbApi? = null,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) : FuenteDeContenido {

    override fun reconoce(ref: String): Boolean = DituRef.decodificar(ref) != null

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = ahoraMs()
        emit(SearchEvent.SourceStart(FUENTE))
        val items = runCatching { catalogo.buscar(ctx.q) }.getOrElse { e ->
            emit(SearchEvent.SourceError(FUENTE, e.message ?: "error de Caracol", ahoraMs() - t0, 0))
            emit(SearchEvent.Done(ahoraMs() - t0))
            return@flow
        }
        for (item in items) {
            emit(SearchEvent.ResultEvent(FUENTE, resultadoDe(item)))
        }
        emit(SearchEvent.SourceDone(FUENTE, items.size, ahoraMs() - t0))
        emit(SearchEvent.Done(ahoraMs() - t0))
    }.flowOn(Dispatchers.IO)

    override suspend fun resolve(ref: String): GatewayPlayable {
        val propio = DituRef.decodificar(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        return runCatching { resolucion.vod(propio) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo reproducir en Caracol", it) }
    }

    /** Resolver un canal en vivo. No pasa por [resolve] porque un canal no tiene `ref`: lo que lo
     *  identifica es el par (channelId, assetId) que vino con la lista. */
    suspend fun resolverCanal(canal: DituCanal): GatewayPlayable =
        runCatching { resolucion.vivo(canal) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo abrir el canal", it) }

    suspend fun canales(): List<DituCanal> =
        runCatching { catalogo.canales() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron listar los canales", it) }

    suspend fun catalogoCompleto(): List<DituItem> =
        runCatching { catalogo.catalogo() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo cargar el catálogo", it) }

    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
        val propio = DituRef.decodificar(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        val temporada = runCatching { episodios.de(propio) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron leer los capítulos", it) }

        val eps = temporada.episodios.map {
            GatewayEpisode(number = it.numero, title = it.titulo, ref = it.ref())
        }
        if (temporada.tituloSerie.isBlank() && temporada.posterUrl.isBlank()) return eps to null

        var serie = GatewaySerie(
            imdbId = "",
            tmdbId = 0,
            seasonNumber = temporada.temporada,
            titulo = temporada.tituloSerie,
            posterUrl = temporada.posterUrl,
            backdropUrl = temporada.fondoUrl,
        )
        // TMDB solo aporta identidad, no imágenes: las de Caracol son las correctas para su propio
        // catálogo. Que falle no puede costar los capítulos, que ya están listos.
        if (tmdb != null && temporada.tituloSerie.isNotBlank()) {
            val hit = runCatching { tmdb.search("tv", temporada.tituloSerie).firstOrNull() }.getOrNull()
            if (hit != null) {
                serie = serie.copy(
                    tmdbId = hit.id,
                    titulo = hit.title.ifBlank { serie.titulo },
                    posterUrl = serie.posterUrl.ifBlank { hit.posterUrl },
                    backdropUrl = serie.backdropUrl.ifBlank { hit.backdropUrl },
                )
            }
        }
        return eps to serie
    }

    private fun resultadoDe(item: DituItem) = GatewayResult(
        source = FUENTE,
        title = item.titulo,
        ref = item.ref(),
        kind = if (item.esPelicula) "movie" else "series",
        year = item.anio,
        extra = mapOf("poster" to item.posterUrl, "content_type" to item.contentType),
    )

    internal companion object {
        const val FUENTE = "ditu"
    }
}
```

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — incluidos los tests de Magis, que ahora implementan `reconoce`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ditu/DituFuente.kt app/src/main/java/com/arkiv/player/data/gateway/FuenteDeContenido.kt app/src/main/java/com/arkiv/player/data/magis/MagisFuente.kt app/src/test/java/com/arkiv/player/data/ditu/DituFuenteTest.kt
git commit -m "feat(ditu): Caracol hablando el mismo contrato que Magis"
```

---

### Task 8: `FuenteCompuesta` — dos fuentes detrás de una

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/FuenteCompuesta.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/FuenteCompuestaTest.kt`

**Interfaces:**
- Consumes: `FuenteDeContenido` (con `reconoce`).
- Produces: `internal class FuenteCompuesta(private val fuentes: List<FuenteDeContenido>) : FuenteDeContenido`.

Esta es la pieza que deja que ninguna pantalla se entere de que ahora hay dos fuentes. Dos reglas que hay que probar: **una fuente caída no puede vaciar la búsqueda de las otras**, y el `Done` final tiene que ser **uno solo**, cuando todas terminaron.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuenteCompuestaTest {

    private class FuenteDeMentira(
        val nombre: String,
        val prefijo: String,
        val resultados: List<String> = emptyList(),
        val error: String? = null,
    ) : FuenteDeContenido {
        var resolvio: String? = null

        override fun reconoce(ref: String) = ref.startsWith(prefijo)

        override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
            emit(SearchEvent.SourceStart(nombre))
            if (error != null) {
                emit(SearchEvent.SourceError(nombre, error, 1, 0))
            } else {
                resultados.forEach {
                    emit(SearchEvent.ResultEvent(nombre, GatewayResult(nombre, it, "$prefijo$it")))
                }
                emit(SearchEvent.SourceDone(nombre, resultados.size, 1))
            }
            emit(SearchEvent.Done(1))
        }

        override suspend fun resolve(ref: String): GatewayPlayable {
            resolvio = ref
            return GatewayPlayable(kind = nombre, url = "http://$nombre")
        }

        override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> {
            resolvio = ref
            return listOf(GatewayEpisode(1, "Cap", ref)) to null
        }
    }

    @Test fun `los resultados de las dos fuentes llegan`() = runTest {
        val a = FuenteDeMentira("a", "a:", listOf("uno", "dos"))
        val b = FuenteDeMentira("b", "b:", listOf("tres"))

        val eventos = FuenteCompuesta(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        val titulos = eventos.filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(setOf("uno", "dos", "tres"), titulos.toSet())
    }

    /** UN solo Done, y al final: si cada fuente emitiera el suyo, la pantalla creería que terminó
     *  la búsqueda cuando apenas terminó la primera. */
    @Test fun `hay un unico Done y es el ultimo evento`() = runTest {
        val a = FuenteDeMentira("a", "a:", listOf("uno"))
        val b = FuenteDeMentira("b", "b:", listOf("dos"))

        val eventos = FuenteCompuesta(listOf(a, b)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(1, eventos.count { it is SearchEvent.Done })
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    /** LA REGLA QUE IMPORTA: una fuente caída no puede vaciar la búsqueda de la otra. */
    @Test fun `si una fuente falla la otra igual entrega`() = runTest {
        val rota = FuenteDeMentira("rota", "r:", error = "se cayó")
        val sana = FuenteDeMentira("sana", "s:", listOf("uno", "dos"))

        val eventos = FuenteCompuesta(listOf(rota, sana)).search(GatewaySearchQuery(q = "x")).toList()

        assertEquals(2, eventos.filterIsInstance<SearchEvent.ResultEvent>().size)
        val err = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertEquals("rota", err.source)
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    @Test fun `resolve va a la fuente que reconoce el ref`() = runTest {
        val a = FuenteDeMentira("a", "a:")
        val b = FuenteDeMentira("b", "b:")

        val play = FuenteCompuesta(listOf(a, b)).resolve("b:42")

        assertEquals("b", play.kind)
        assertEquals("b:42", b.resolvio)
        assertEquals(null, a.resolvio)
    }

    @Test fun `episodes va a la fuente que reconoce el ref`() = runTest {
        val a = FuenteDeMentira("a", "a:")
        val b = FuenteDeMentira("b", "b:")

        FuenteCompuesta(listOf(a, b)).episodesConSerie("a:9")

        assertEquals("a:9", a.resolvio)
        assertEquals(null, b.resolvio)
    }

    @Test fun `un ref que nadie reconoce es un GatewayException`() = runTest {
        val compuesta = FuenteCompuesta(listOf(FuenteDeMentira("a", "a:")))

        val e = runCatching { compuesta.resolve("z:1") }.exceptionOrNull()
        assertTrue(e is GatewayException)
    }

    @Test fun `reconoce si cualquiera de sus fuentes reconoce`() {
        val compuesta = FuenteCompuesta(listOf(FuenteDeMentira("a", "a:"), FuenteDeMentira("b", "b:")))

        assertTrue(compuesta.reconoce("b:1"))
        assertTrue(!compuesta.reconoce("z:1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*FuenteCompuestaTest*'`
Expected: FAIL — `FuenteCompuesta` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Varias fuentes detrás de una sola. Existe para que agregar Caracol —y después RCN— no obligue a
 * tocar ninguna pantalla: `AppGraph.fuenteDeContenido` sigue siendo UN objeto.
 *
 * Dos reglas gobiernan la búsqueda:
 *
 * - **Una fuente caída no vacía la búsqueda de las otras.** Cada fuente ya emite su propio
 *   `SourceError` y termina; acá simplemente no se deja que eso corte el flujo común.
 * - **Hay un solo `Done`, al final.** Los `Done` de cada fuente se descartan y se emite uno propio
 *   cuando todas terminaron: si pasaran los de adentro, la pantalla creería que la búsqueda
 *   terminó cuando apenas terminó la primera fuente.
 *
 * Para resolver y para listar capítulos no hay mezcla: el `ref` decide. Cada fuente sabe leer los
 * suyos (`reconoce`), incluidos los viejos del gateway, que no llevan un prefijo visible.
 */
internal class FuenteCompuesta(private val fuentes: List<FuenteDeContenido>) : FuenteDeContenido {

    override fun reconoce(ref: String): Boolean = fuentes.any { it.reconoce(ref) }

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = System.currentTimeMillis()
        // `merge` corre las fuentes en paralelo y emite lo de cada una a medida que llega, que es
        // lo que la pantalla espera: pinta resultados mientras la otra fuente sigue buscando.
        val mezclado = fuentes
            .map { it.search(ctx).filterNot { e -> e is SearchEvent.Done } }
            .merge()
        emitAll(mezclado)
        emit(SearchEvent.Done(System.currentTimeMillis() - t0))
    }

    override suspend fun resolve(ref: String): GatewayPlayable = para(ref).resolve(ref)

    override suspend fun episodesConSerie(ref: String): Pair<List<GatewayEpisode>, GatewaySerie?> =
        para(ref).episodesConSerie(ref)

    private fun para(ref: String): FuenteDeContenido =
        fuentes.firstOrNull { it.reconoce(ref) }
            ?: throw GatewayException("No hay ninguna fuente que sepa abrir esto")
}
```

Nota para quien implemente: `emitAll` necesita `import kotlinx.coroutines.flow.emitAll`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*FuenteCompuestaTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/gateway/FuenteCompuesta.kt app/src/test/java/com/arkiv/player/data/gateway/FuenteCompuestaTest.kt
git commit -m "feat(fuentes): varias fuentes detras de una, sin que las pantallas se enteren"
```

---

### Task 9: `DituExoPlayer` — DASH con Widevine

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/player/DituExoPlayer.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (cablear `FuenteCompuesta` y las piezas de Ditu)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (una rama `loadDitu`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (elegir el reproductor)

**Interfaces:**
- Consumes: `GatewayPlayable.drmLicenseUrl`, `GatewayPlayable.drmLicenseHeaders`, `EspejoDelPlayer` (el mismo que usa `MagisExoPlayer`).
- Produces: `internal fun DituExoPlayer(mediaUrl: String, drmLicenseUrl: String, drmLicenseHeaders: Map<String, String>, espejo: EspejoDelPlayer, startPositionMs: Long = 0L, onPlayerReady: (Player?) -> Unit = {}, onError: (String) -> Unit = {}, zoom: Float = 1f)`; y en `AppGraph`, `internal val dituFuente: DituFuente`.

Este es el riesgo del sub-proyecto: la rama nunca negoció una licencia Widevine. La configuración va por `MediaItem.DrmConfiguration`, que es la forma en que media3 lo hace sin montar un `DrmSessionManager` a mano.

- [ ] **Step 1: Write the composable**

```kotlin
package com.arkiv.player.ui.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.ui.PlayerView

/**
 * El reproductor de Caracol: MPEG-DASH con Widevine.
 *
 * VLC no entra acá y no es una preferencia: no soporta Widevine —no puede negociar licencias con
 * `FrameworkMediaDrm`— y cualquier intento termina en 403 en los segmentos.
 *
 * La licencia se declara en el propio `MediaItem` ([MediaItem.DrmConfiguration]) en vez de armar un
 * `DrmSessionManager` a mano: es la forma en que media3 lo resuelve solo.
 *
 * [drmLicenseHeaders] no es opcional en la práctica. Caracol exige la cookie `playback_token` que
 * devolvió `CONTENT/VIDEOURL`, y sin ella su servidor de licencias responde 500 — que en pantalla
 * se ve igual que "el video no existe".
 */
@OptIn(UnstableApi::class)
@Composable
internal fun DituExoPlayer(
    mediaUrl: String,
    drmLicenseUrl: String,
    drmLicenseHeaders: Map<String, String>,
    espejo: EspejoDelPlayer,
    startPositionMs: Long = 0L,
    onPlayerReady: (Player?) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current

    val exoPlayer = remember(mediaUrl, drmLicenseUrl) {
        Log.i(TAG, "Creando ExoPlayer DASH · url=${mediaUrl.take(80)} licencia=${drmLicenseUrl.take(60)} " +
            "headers=${drmLicenseHeaders.keys}")

        val httpFactory = DefaultHttpDataSource.Factory()
            // El MISMO User-Agent que la API: el CDN de Caracol responde 403 sin él.
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes"))
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val drm = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(drmLicenseUrl)
            .setLicenseRequestHeaders(drmLicenseHeaders)
            .build()

        val item = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(drm)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DashMediaSource.Factory(httpFactory))
            .build()
            .apply {
                setMediaItem(item)
                if (startPositionMs > 0) seekTo(startPositionMs)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val escucha = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                espejo.buffereando = state == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(estaReproduciendo: Boolean) {
                espejo.reproduciendo = estaReproduciendo
            }

            override fun onPlayerError(error: PlaybackException) {
                // Los cortes de publicidad llegan como `Source error`. NO se puede filtrar la
                // publicidad (ya se midió en la app completa y se abandonó): lo que se hace es
                // volver a preparar, que es lo que la reanuda.
                Log.w(TAG, "onPlayerError code=${error.errorCode} ${error.errorCodeName}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                ) {
                    exoPlayer.prepare()
                    return
                }
                onError(error.errorCodeName)
            }
        }
        exoPlayer.addListener(escucha)
        onPlayerReady(exoPlayer)
        onDispose {
            exoPlayer.removeListener(escucha)
            onPlayerReady(null)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = Modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = exoPlayer
            }
        },
    )
}

private const val TAG = "DituExo"
```

- [ ] **Step 2: Wire `AppGraph`**

En `AppGraph.kt`, junto al bloque de Magis, agregar y cambiar `fuenteDeContenido` para que sea la compuesta:

```kotlin
    private val dituCliente: com.arkiv.player.data.ditu.DituClienteLike by lazy {
        com.arkiv.player.data.ditu.DituCliente()
    }

    internal val dituFuente: com.arkiv.player.data.ditu.DituFuente by lazy {
        com.arkiv.player.data.ditu.DituFuente(
            catalogo = com.arkiv.player.data.ditu.DituCatalogo(dituCliente),
            episodios = com.arkiv.player.data.ditu.DituEpisodios(dituCliente),
            resolucion = com.arkiv.player.data.ditu.DituResolve(dituCliente),
            tmdb = tmdb,
        )
    }

    val fuenteDeContenido: com.arkiv.player.data.gateway.FuenteDeContenido by lazy {
        com.arkiv.player.data.gateway.FuenteCompuesta(listOf(magisFuente, dituFuente))
    }
```

Donde `magisFuente` es el `by lazy` que hoy construye `MagisFuente` (renombrar el actual `fuenteDeContenido` a `magisFuente` y dejarlo `private`).

- [ ] **Step 3: Add the `loadDitu` branch in `PlayerViewModel`**

Junto a `loadMagis`, la rama que resuelve por la fuente compuesta y expone el resultado para que la pantalla elija reproductor:

```kotlin
    /** Lo que se está reproduciendo de Caracol, o `null` si no aplica. Cuando no es null,
     *  `PlayerScreen` usa [DituExoPlayer] en vez del reproductor de Magis o de VLC. */
    private val _dituPlayable = MutableStateFlow<GatewayPlayable?>(null)
    val dituPlayable: StateFlow<GatewayPlayable?> = _dituPlayable

    private suspend fun loadDitu(episodeId: String) {
        val ref = repo.magisRefForEpisode(episodeId)
        if (ref.isNullOrBlank()) { _error.value = "No se encontró la fuente de Caracol"; return }
        _resolving.value = true
        val resuelto = withContext(Dispatchers.IO) { runCatching { fuente.resolve(ref) } }
        _resolving.value = false
        val play = resuelto.getOrNull()
        if (play == null) {
            _error.value = resuelto.exceptionOrNull()?.message ?: "No se pudo reproducir en Caracol"
            return
        }
        _dituPlayable.value = play
    }

    fun onDituExoError(mensaje: String) {
        _error.value = "Caracol: $mensaje"
    }
```

- [ ] **Step 4: Choose the player in `PlayerScreen`**

Donde hoy decide entre ExoPlayer y VLC (`val isExo = isMagis || isLiveExo`), agregar la rama de Ditu **antes**, porque su `MediaItem` es el único con DRM:

```kotlin
    val dituPlay by vm.dituPlayable.collectAsStateWithLifecycle()
    if (dituPlay != null) {
        DituExoPlayer(
            mediaUrl = dituPlay!!.url,
            drmLicenseUrl = dituPlay!!.drmLicenseUrl,
            drmLicenseHeaders = dituPlay!!.drmLicenseHeaders,
            espejo = espejo,
            startPositionMs = posicionGuardadaMs,
            onPlayerReady = { activePlayer = it },
            onError = { vm.onDituExoError(it) },
        )
    }
```

`posicionGuardadaMs` es la que la pantalla ya usa para reanudar con Magis: pasarla acá es lo que evita el hueco #2 del documento de estado de `main` (allá el reproductor de Ditu siempre recibía `0L` y nunca reanudaba).

- [ ] **Step 5: Verify it builds and the suite stays green**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL en los dos.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/DituExoPlayer.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(ditu): reproducir DASH con Widevine, y las dos fuentes cableadas"
```

- [ ] **Step 7: Verify on the KALLEY R3 before going on**

Este es el punto donde el riesgo del sub-proyecto se confirma o se cae, y por eso se prueba acá y no al final:

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.arkiv.player.light/com.arkiv.player.MainActivity
adb logcat -d | grep -iE "DituExo|Widevine|DrmSession|MediaCodec"
```

Buscar un título de Caracol, reproducirlo, y confirmar que hay imagen. Si aparece `MediaDrmCallbackException` o un 500 en la licencia, el `playback_token` no está llegando: revisar en el log de `DituExo` que `headers=[Cookie]`.

---

### Task 10: La sección propia de Caracol

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt` (agregar `PlaySource.Ditu` y su color)
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvCaracolScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` (la entrada a la sección)
- Test: `app/src/test/java/com/arkiv/player/ui/catalog/PlaySourceDituTest.kt`

**Interfaces:**
- Consumes: `AppGraph.dituFuente` (`suspend fun catalogoCompleto(): List<DituItem>`, `suspend fun canales(): List<DituCanal>`, `suspend fun resolverCanal(canal: DituCanal): GatewayPlayable`), `DituItem`, `DituCanal`.
- Produces: `PlaySource.Ditu(val result: GatewayResult)` y `ArkivCaracolVerde` en `PlaySources.kt`.

El catálogo se cachea con [CacheConVencimiento] a 6 h, con un botón para recargarlo a mano: son ~330 títulos en una sola llamada y pedirlos cada vez que se entra a la sección es tiempo regalado. Esa decisión ya está tomada y medida en la app completa.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaySourceDituTest {

    @Test fun `una fuente de Caracol tiene su propio color`() {
        val ditu = PlaySource.Ditu(GatewayResult(source = "ditu", title = "Rigo", ref = "ditu1:BUNDLE:1"))
        val magis = PlaySource.Magis(GatewayResult(source = "magis", title = "Rigo", ref = "magis1:movie:0:C1"))

        assertEquals(ArkivCaracolVerde, accentOf(ditu))
        assertNotEquals(accentOf(magis), accentOf(ditu))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*PlaySourceDituTest*'`
Expected: FAIL — `PlaySource.Ditu` no existe.

- [ ] **Step 3: Add the variant**

En `PlaySources.kt`, reemplazar el KDoc que dice que Ditu "vuelve en el sub-proyecto 3" —ya volvió— y agregar:

```kotlin
sealed interface PlaySource {
    /** Resultado del portal Magis (solo VOD). El `ref` es opaco: se manda tal cual a
     *  `MagisResolve.resolveVod` y la app nunca lo interpreta. */
    data class Magis(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource

    /** Resultado de Caracol Streaming. A diferencia de Magis, su `ref` SÍ se puede guardar en la
     *  biblioteca: codifica ids de Caracol, que son estables (ver `DituRef`). */
    data class Ditu(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource
}

/** Verde de Caracol: el color de acento de su fila, su sección y su chip de filtro. */
val ArkivCaracolVerde = Color(0xFF66BB6A)

fun accentOf(source: PlaySource): Color = when (source) {
    is PlaySource.Magis -> ArkivMagisBlue
    is PlaySource.Ditu -> ArkivCaracolVerde
}
```

Después de agregar la variante, el compilador va a marcar cada `when (source)` que quedó incompleto. **Recorrerlos todos y decidir en cada uno**: los que guardan en biblioteca tienen que aceptar Ditu (sus ids no vencen); los que arman el reproductor tienen que ir por la rama de `dituPlayable`.

- [ ] **Step 4: Write the section screen**

`TvCaracolScreen.kt` — la pantalla con dos pestañas, catálogo y canales:

```kotlin
package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkiv.player.data.ditu.DituCanal
import com.arkiv.player.data.ditu.DituItem
import com.arkiv.player.ui.rememberGraph

/**
 * La sección de Caracol en el televisor: su catálogo y sus canales en vivo.
 *
 * El catálogo se pide UNA vez y se guarda 6 h: son ~330 títulos en una sola llamada, así que
 * pedirlos cada vez que se entra es tiempo regalado. El botón de recargar existe para cuando
 * Caracol agrega algo y no se quiere esperar a que venza.
 */
@Composable
internal fun TvCaracolScreen(alAbrirTitulo: (DituItem) -> Unit, alAbrirCanal: (DituCanal) -> Unit) {
    val graph = rememberGraph()
    var titulos by remember { mutableStateOf<List<DituItem>>(emptyList()) }
    var canales by remember { mutableStateOf<List<DituCanal>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var recargas by remember { mutableStateOf(0) }

    LaunchedEffect(recargas) {
        error = null
        // Cada fuente falla sola: que no haya canales no puede dejar la pantalla sin catálogo.
        runCatching { graph.dituFuente.catalogoCompleto() }
            .onSuccess { titulos = it }
            .onFailure { error = it.message }
        runCatching { graph.dituFuente.canales() }
            .onSuccess { canales = it }
    }

    TvCaracolContenido(
        titulos = titulos,
        canales = canales,
        error = error,
        alRecargar = { recargas++ },
        alAbrirTitulo = alAbrirTitulo,
        alAbrirCanal = alAbrirCanal,
    )
}
```

`TvCaracolContenido` se arma con las mismas piezas de fila y tarjeta que ya usa `TvSeccionesDeCatalogo` — seguir ese archivo como molde, no inventar componentes nuevos.

- [ ] **Step 5: Verify it builds and the suite stays green**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL en los dos.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt app/src/main/java/com/arkiv/player/ui/tv/TvCaracolScreen.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt app/src/test/java/com/arkiv/player/ui/catalog/PlaySourceDituTest.kt
git commit -m "feat(ditu): seccion propia de Caracol, con catalogo y canales"
```

- [ ] **Step 7: Verify the whole sub-project on the KALLEY R3**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Cinco cosas, y la cuarta es la que distingue a Caracol de Magis:

1. Un canal en vivo de Caracol reproduce con imagen y sonido.
2. Una serie del catálogo abre sus capítulos, y una con varias temporadas las muestra separadas y en orden.
3. Buscar un título que exista en las dos fuentes muestra los dos resultados, cada uno con su color.
4. Guardar un capítulo de Caracol en la biblioteca y **reproducirlo al día siguiente**: sus ids no vencen, a diferencia del token de Magis.
5. Apagar el wifi y buscar: Magis falla y Caracol falla, pero la pantalla muestra los dos errores y no se queda colgada.

---

## Self-Review

**1. Cobertura del spec.** Cada sección del spec tiene su tarea: el protocolo de Ditu (Tasks 3-6), los siete flags de entitlement (Task 2), los refs locales (Task 1), el `FuenteDeContenido` con tres implementaciones (Tasks 7-8), el reproductor Widevine (Task 9), la mezcla de la búsqueda (Task 8), la sección propia (Task 10), el catálogo cacheado (Task 10) y que Ditu sí se guarda en biblioteca (Tasks 1 y 10). Lo de RCN no está acá a propósito: es 3B y tendrá su propio plan.

**2. Placeholders.** Ninguna tarea dice "manejar errores" sin decir cuál ni cómo. Las dos partes con menos código literal son el `TvCaracolContenido` de la Task 10 y los puntos de cableado de la Task 9, y en las dos se nombra el archivo que hay que seguir como molde en vez de describir en abstracto.

**3. Consistencia de tipos.** `DituRef(contentId, contentType)` se usa igual en las Tasks 1, 5, 6 y 7. `DituCatalogo.contenedoresDe/assetMaster/posterDe/fondoDe` se declaran en la Task 4 y se consumen en las 5 y 6. `DituClienteLike.get/getConToken` se declara en la Task 3 y lo implementa el fake de la Task 4. `reconoce(ref)` se agrega a la interfaz en la Task 7 y lo usa `FuenteCompuesta` en la 8 — por eso van en ese orden.

**Un hueco conocido, anotado a propósito:** la Task 9 asume que `PlayerViewModel` sabe cuándo un episodio es de Caracol para llamar a `loadDitu`. Hoy esa decisión se toma leyendo la fila de la biblioteca; quien implemente la Task 9 tiene que mirar cómo `loadMagis` se elige hoy y seguir el mismo camino, no inventar un flag nuevo.
