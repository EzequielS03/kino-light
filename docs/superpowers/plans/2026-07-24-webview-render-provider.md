# Render por WebView para proveedores JS-rendered — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir un mecanismo genérico y declarativo (`renderJs` + `readySelector`) para scrapear trackers cuyos resultados carga el JS del cliente, ejecutando el propio JS del sitio en un WebView headless y pasando el DOM renderizado al parser declarativo existente. Primer consumidor: wolfmax4k.

**Architecture:** Un `JsRenderer` (interfaz) con impl `WebViewPageRenderer` (WebView serializado por mutex + timeout, misma disciplina que `WebViewCloudflareSolver`). `DeclarativeHtmlBackend` elige renderer vs `HttpFetcher` para el LISTADO según `def.renderJs`; el detalle sigue con el fetcher HTTP normal. wolfmax4k se habilita reusando `DetailRules.torrentUrl` (ya existente).

**Tech Stack:** Kotlin, Coroutines, Android WebView, Jsoup, org.json, JUnit4. Grafo manual en `AppGraph`.

## Global Constraints

- Ningún request del path de torrents va a `blog`/`jackett.comparadorinternet.co`.
- La capa web NO se toca.
- Identidad git: `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. Commits **sin** coautoría de Claude. Nunca `git add -A`.
- Tests: `./gradlew testDebugUnitTest`. Una clase: `--tests "FQN"`. TDD. Un commit por tarea.
- Branch: `feat/torrents-ondevice-sin-servidor`.
- Proveedores SIN `renderJs` deben comportarse **exactamente igual** que antes (fetcher HTTP normal).
- El WebView vivo no es unit-testeable; se valida en device. Sí son unit-testeables: parsing del DOM renderizado, parseo de schema, el seam (con renderer fake), y los helpers puros del renderer.
- Timeout/fallo del render → `null` → vacío. Nunca cuelga ni rompe a otros proveedores.

---

## Mapa de archivos

**Creados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/JsRenderer.kt` — interfaz `JsRenderer`, `NoopJsRenderer`, helpers puros (`readyCountScript`, `decodeEvalString`), y `WebViewPageRenderer`.
- `app/src/test/java/com/arkiv/player/data/catalog/providers/JsRendererHelpersTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/RenderJsSeamTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/Wolfmax4kDefinitionTest.kt`

**Modificados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt` — `renderJs`, `readySelector`.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt` — param `jsRenderer` + selección de fuente para el listado.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt` — propagar `jsRenderer`.
- `app/src/main/java/com/arkiv/player/AppGraph.kt` — construir `WebViewPageRenderer` y cablearlo.
- `app/src/main/assets/providers.json` — habilitar wolfmax4k con `renderJs`.
- `app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt` — wolfmax4k ya no está deshabilitado.
- `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionHostAltTest.kt` — (opcional) añadir aserción de los nuevos campos, o crear un test aparte.

---

## Task 1: ProviderDefinition — renderJs + readySelector

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionRenderJsTest.kt` (crear)

**Interfaces:**
- Produces: `ProviderDefinition` gana `val renderJs: Boolean = false` y `val readySelector: String? = null`, parseados en `fromJson` (`optBoolean`/`optString`).

- [ ] **Step 1: Test (falla: campos no existen)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDefinitionRenderJsTest {
    private fun def(json: String) = ProviderDefinition.fromJson(JSONObject(json))

    @Test fun `parsea renderJs y readySelector`() {
        val d = def("""
            {"id":"w","baseUrl":"https://w.org","searchPath":"/buscar/{query}",
             "renderJs":true,"readySelector":"#res a.card",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"#res a.card","name":{"selector":"h3","attr":"text"},
               "torrentUrl":{"selector":"","attr":"href","resolve":"absolute"}}}
        """.trimIndent())!!
        assertTrue(d.renderJs)
        assertEquals("#res a.card", d.readySelector)
    }

    @Test fun `defaults false y null cuando faltan`() {
        val d = def("""
            {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a.m","attr":"href"}}}
        """.trimIndent())!!
        assertFalse(d.renderJs)
        assertNull(d.readySelector)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionRenderJsTest"`
Expected: FAIL (unresolved `renderJs`).

- [ ] **Step 3: Implementar**

En la data class `ProviderDefinition`, tras `requestHeaders` (línea ~64):
```kotlin
val renderJs: Boolean = false,
val readySelector: String? = null,
```
En `fromJson`, antes de construir el objeto (junto al bloque de hostAlt/requestHeaders):
```kotlin
val renderJs = o.optBoolean("renderJs", false)
val readySelector = o.optString("readySelector").ifBlank { null }
```
Y en la llamada al constructor `ProviderDefinition(...)`, añadir: `renderJs = renderJs, readySelector = readySelector,`.

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionRenderJsTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (campos con default; no rompe defs existentes).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionRenderJsTest.kt
git commit -m "feat(torrent): ProviderDefinition con renderJs y readySelector"
```

---

## Task 2: JsRenderer (interfaz + Noop + helpers puros + WebViewPageRenderer)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/JsRenderer.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/JsRendererHelpersTest.kt`

**Interfaces:**
- Produces:
  - `interface JsRenderer { suspend fun render(url: String, readySelector: String, headers: Map<String,String>): String? }`
  - `object NoopJsRenderer : JsRenderer` (devuelve null).
  - `fun readyCountScript(selector: String): String` — script JS que cuenta `querySelectorAll(selector).length`, con el selector JSON-escapado.
  - `fun decodeEvalString(raw: String?): String?` — decodifica el string JSON que devuelve `evaluateJavascript` (null si "null"/inválido).
  - `class WebViewPageRenderer(context, enabled: () -> Boolean, timeoutMs: Long = 18_000, pollMs: Long = 300) : JsRenderer`.

- [ ] **Step 1: Test de los helpers puros (falla: no existen)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsRendererHelpersTest {
    @Test fun `readyCountScript escapa el selector y cuenta`() {
        val s = readyCountScript("#res a.card-movie")
        assertTrue(s.contains("querySelectorAll"))
        assertTrue(s.contains("\"#res a.card-movie\""))   // selector citado/escapado
        assertTrue(s.contains(".length"))
    }

    @Test fun `readyCountScript neutraliza comillas del selector`() {
        val s = readyCountScript("a[data-x=\"y\"]")
        // No debe romper el JS: la comilla interna queda escapada por JSONObject.quote.
        assertTrue(s.contains("\\\""))
    }

    @Test fun `decodeEvalString decodifica el string JSON de evaluateJavascript`() {
        assertEquals("<html>Coco</html>", decodeEvalString("\"<html>Coco</html>\""))
        assertEquals("a\"b", decodeEvalString("\"a\\\"b\""))   // comillas internas escapadas
    }

    @Test fun `decodeEvalString devuelve null para null o invalido`() {
        assertNull(decodeEvalString("null"))
        assertNull(decodeEvalString(null))
        assertNull(decodeEvalString(""))
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsRendererHelpersTest"`
Expected: FAIL (unresolved).

- [ ] **Step 3: Implementar JsRenderer.kt**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Renderiza una página ejecutando su JS y devuelve el DOM resultante (o null). Genérico y reutilizable. */
interface JsRenderer {
    suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String?
}

/** No renderiza nada (flag apagado / tests): el proveedor renderJs degrada a vacío. */
object NoopJsRenderer : JsRenderer {
    override suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String? = null
}

/** Script JS que cuenta cuántos elementos matchean el selector (con el selector JSON-escapado). Puro/testeable. */
fun readyCountScript(selector: String): String =
    "(function(){try{return document.querySelectorAll(${JSONObject.quote(selector)}).length}catch(e){return 0}})()"

/** Decodifica el string JSON que devuelve WebView.evaluateJavascript (p. ej. "\"<html>\"" -> <html>). Puro/testeable. */
fun decodeEvalString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}

/**
 * Renderiza con un WebView headless: carga la URL, deja correr el JS del sitio, hace poll de
 * [readyCountScript] hasta que aparezca ≥1 resultado (o timeout), y devuelve document.documentElement.outerHTML.
 * AISLADO: un WebView a la vez (Mutex), timeout duro, en Main. Si falla/timeout → null (degrada a vacío).
 */
class WebViewPageRenderer(
    context: Context,
    private val enabled: () -> Boolean,
    private val timeoutMs: Long = 18_000,
    private val pollMs: Long = 300,
) : JsRenderer {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String? {
        if (!enabled()) return null
        return mutex.withLock {
            withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Main) { renderOnMain(url, readySelector, headers) } }
        }
    }

    private suspend fun renderOnMain(url: String, readySelector: String, headers: Map<String, String>): String? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(appContext)
            var resumed = false
            val handler = Handler(Looper.getMainLooper())
            fun finish(result: String?) {
                if (resumed) return
                resumed = true
                runCatching { webView.stopLoading(); webView.destroy() }
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { handler.post { finish(null) } }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            fun poll() {
                if (resumed) return
                webView.evaluateJavascript(readyCountScript(readySelector)) { countStr ->
                    val count = countStr?.trim('"')?.toIntOrNull() ?: 0
                    if (count > 0) {
                        webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            finish(decodeEvalString(html))
                        }
                    } else {
                        handler.postDelayed({ poll() }, pollMs)
                    }
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    runCatching { Log.i("ArkivProv", "render onPageFinished, polling '$readySelector'") }
                    poll()
                }
            }
            webView.loadUrl(url, headers)
        }
}
```

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsRendererHelpersTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS.
(El WebView vivo no se testea aquí; se valida en device — Task 6.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JsRenderer.kt app/src/test/java/com/arkiv/player/data/catalog/providers/JsRendererHelpersTest.kt
git commit -m "feat(torrent): JsRenderer + WebViewPageRenderer (render de sitios JS-rendered)"
```

---

## Task 3: DeclarativeHtmlBackend — seam del jsRenderer

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/RenderJsSeamTest.kt`

**Interfaces:**
- Consumes: `JsRenderer` (Task 2), `ProviderDefinition.renderJs`/`readySelector` (Task 1).
- Produces: `DeclarativeHtmlBackend(def, fetcher, maxQueries = 6, jsRenderer: JsRenderer = NoopJsRenderer)`. Cuando `def.renderJs && def.readySelector != null`, el LISTADO se obtiene con `jsRenderer.render(url, readySelector, requestHeaders)`; si no, con `fetcher.fetch(...)` (idéntico a hoy).

- [ ] **Step 1: Test del seam (renderer fake vs fetcher)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderJsSeamTest {
    private val renderDef = ProviderDefinition.fromJson(JSONObject("""
        {"id":"w","baseUrl":"https://w.org","searchPath":"/buscar/{query}",
         "renderJs":true,"readySelector":"#res a.card",
         "keywords":{"movie":"{title}"},
         "parser":{"rowSelector":"#res a.card","name":{"selector":"h3","attr":"text"},
           "torrentUrl":{"selector":"","attr":"href","resolve":"absolute"}}}
    """.trimIndent()))!!

    private val renderedHtml = """
        <div id="res">
          <a class="card" href="https://w.org/pelicula/1/Coco"><h3>Coco 2017</h3></a>
        </div>
    """.trimIndent()

    @Test fun `usa el jsRenderer (no el fetcher) cuando renderJs`() = runBlocking {
        var fetcherCalled = false
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? {
                fetcherCalled = true; return "<html>NO</html>"
            }
        }
        var renderedUrl = ""
        val renderer = object : JsRenderer {
            override suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String? {
                renderedUrl = url; assertEquals("#res a.card", readySelector); return renderedHtml
            }
        }
        val out = DeclarativeHtmlBackend(renderDef, fetcher, jsRenderer = renderer)
            .search(SearchContext(listOf("Coco"), ContentType.MOVIE))
        assertEquals(false, fetcherCalled)                       // NO usó el fetcher para el listado
        assertTrue(renderedUrl.startsWith("https://w.org/buscar/"))
        assertEquals(1, out.size)
        assertEquals("Coco 2017", out[0].name)
        assertTrue(out[0].downloadUrl!!.contains("/pelicula/1/Coco"))
    }

    @Test fun `sin renderJs usa el fetcher (comportamiento intacto)`() = runBlocking {
        val plainDef = ProviderDefinition.fromJson(JSONObject("""
            {"id":"p","baseUrl":"https://p.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr.r","name":{"selector":"a.t","attr":"text"},
               "magnet":{"selector":"a.m","attr":"href"}}}
        """.trimIndent()))!!
        var rendererCalled = false
        val renderer = object : JsRenderer {
            override suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String? {
                rendererCalled = true; return null
            }
        }
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? =
                """<table><tr class="r"><td><a class="t">Coco</a></td>
                   <td><a class="m" href="magnet:?xt=urn:btih:AA">m</a></td></tr></table>"""
        }
        val out = DeclarativeHtmlBackend(plainDef, fetcher, jsRenderer = renderer)
            .search(SearchContext(listOf("Coco"), ContentType.MOVIE))
        assertEquals(false, rendererCalled)                      // NO tocó el renderer
        assertEquals(1, out.size)
        assertEquals("Coco", out[0].name)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.RenderJsSeamTest"`
Expected: FAIL (constructor sin `jsRenderer`).

- [ ] **Step 3: Implementar**

En `DeclarativeHtmlBackend`, añadir el param al constructor:
```kotlin
class DeclarativeHtmlBackend(
    private val def: ProviderDefinition,
    private val fetcher: PageFetcher,
    private val maxQueries: Int = 6,
    private val jsRenderer: JsRenderer = NoopJsRenderer,
) : ProviderBackend {
```
En `searchOne`, reemplazar la línea del fetch del listado (actual línea 37):
```kotlin
val html = if (def.renderJs && def.readySelector != null)
    jsRenderer.render(url, def.readySelector!!, def.requestHeaders)
else
    fetcher.fetch(url, def.charset, def.requestHeaders)
if (html == null) continue
```
(El resto de `searchOne` y `resolveDetail` NO cambian — el detalle sigue con `fetcher`.)

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.RenderJsSeamTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (el `DeclarativeHtmlBackendTest` existente sigue verde: default `NoopJsRenderer` + defs sin `renderJs`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt app/src/test/java/com/arkiv/player/data/catalog/providers/RenderJsSeamTest.kt
git commit -m "feat(torrent): DeclarativeHtmlBackend usa jsRenderer para listados renderJs"
```

---

## Task 4: RegistryProviderBackend + AppGraph — cablear el renderer

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `DeclarativeHtmlBackend(def, fetcher, jsRenderer=…)` (Task 3), `WebViewPageRenderer` (Task 2).
- Produces: `RegistryProviderBackend(fetcher, jsRenderer: JsRenderer = NoopJsRenderer, definitions)`; `AppGraph` construye un `WebViewPageRenderer` y lo pasa.

- [ ] **Step 1: RegistryProviderBackend propaga el renderer**

```kotlin
class RegistryProviderBackend(
    private val fetcher: PageFetcher,
    private val jsRenderer: JsRenderer = NoopJsRenderer,
    private val definitions: () -> List<ProviderDefinition>,
) : ProviderBackend {
    override val id: String = "registry"

    override suspend fun search(ctx: SearchContext): List<RawTorrent> = coroutineScope {
        definitions().filter { it.enabled }.map { def ->
            async { runCatching { DeclarativeHtmlBackend(def, fetcher, jsRenderer = jsRenderer).search(ctx) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }
}
```
Nota: el orden de params cambia (jsRenderer antes de definitions). Actualizar el call site en AppGraph en el Step 2.

- [ ] **Step 2: AppGraph construye y cablea el renderer**

En `AppGraph.kt`, junto al `cloudflareSolver`/`providerFetcher` (líneas ~84-87):
```kotlin
private val jsRenderer by lazy {
    WebViewPageRenderer(appContext, enabled = { settings.cloudflareSolverEnabled.value })
}
```
Añadir import `com.arkiv.player.data.catalog.providers.WebViewPageRenderer`.
Y cambiar la construcción de `registryProviderBackend` (línea ~99):
```kotlin
private val registryProviderBackend: ProviderBackend by lazy {
    RegistryProviderBackend(providerFetcher, jsRenderer) { providerDefinitions }
}
```

- [ ] **Step 3: Compilar + suite**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
Run: `./gradlew testDebugUnitTest` → PASS (incluido `RegistryProviderBackendTest`; si su construcción de `RegistryProviderBackend` usa args posicionales y el nuevo orden lo rompe, actualizar esa llamada del test para pasar el `jsRenderer` o usar el default con args nombrados — hacerlo mínimamente y mencionarlo).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(torrent): cablear WebViewPageRenderer en el registry provider backend"
```
(Si tocaste `RegistryProviderBackendTest.kt` para compilar, inclúyelo en el `git add`.)

---

## Task 5: Habilitar wolfmax4k + fixture del DOM renderizado

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Modify: `app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/Wolfmax4kDefinitionTest.kt` (crear)

**Interfaces:**
- Consumes: `BundledProviders.byId("wolfmax4k")`, `HtmlParser.parseList`, `DeclarativeHtmlBackend` con fake `JsRenderer`.

- [ ] **Step 1: Test del parsing del DOM renderizado (falla: wolfmax4k sigue disabled/sin renderJs)**

Fixture = `#resDetalle` con tarjetas `a.card.card-movie` (reconstruido del template del JS del sitio; refinar con un dump de device si se dispone). Crear `Wolfmax4kDefinitionTest.kt`:
```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class Wolfmax4kDefinitionTest {
    private val def = BundledProviders.byId("wolfmax4k")

    private val renderedHtml = """
        <div id="resDetalle">
          <a class="card card-movie" href="https://wolfmax4k.com/pelicula/123/Coco-2017">
            <div class="card-body"><h3>Coco 2017</h3></div>
          </a>
          <a class="card card-movie" href="https://wolfmax4k.com/pelicula/124/Coco-Chanel">
            <div class="card-body"><h3>Coco Chanel</h3></div>
          </a>
        </div>
    """.trimIndent()

    @Test fun `esta habilitado y con renderJs`() {
        assertTrue(def.enabled)
        assertTrue(def.renderJs)
        assertTrue(def.readySelector != null)
    }

    @Test fun `parsea las tarjetas del DOM renderizado`() {
        val rows = HtmlParser.parseList(def, renderedHtml, def.baseUrl)
        assertTrue(rows.size >= 2)
        val r = rows.first { it.name.contains("Coco 2017") }
        assertTrue(r.downloadUrl!!.contains("/pelicula/123/Coco-2017"))  // href de la tarjeta (ficha)
    }

    @Test fun `end-to-end con jsRenderer fake resuelve la ficha al torrent`() = runBlocking {
        val renderer = object : JsRenderer {
            override suspend fun render(url: String, readySelector: String, headers: Map<String, String>): String? = renderedHtml
        }
        // fetcher fake: la ficha /pelicula/123/... trae el enlace .torrent.
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? =
                if (url.contains("/pelicula/123/")) """<a href="https://wolfmax4k.com/t/coco.torrent">Descargar</a>"""
                else ""
        }
        val out = DeclarativeHtmlBackend(def, fetcher, jsRenderer = renderer)
            .search(SearchContext(listOf("Coco"), ContentType.MOVIE, year = "2017"))
        assertTrue(out.any { it.downloadUrl?.endsWith("coco.torrent") == true })
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.Wolfmax4kDefinitionTest"`
Expected: FAIL (`wolfmax4k` está `enabled:false` y sin `renderJs`).

- [ ] **Step 3: Habilitar wolfmax4k en providers.json**

Reemplazar la entrada `wolfmax4k` (hoy `enabled:false`) por:
```json
{
  "id": "wolfmax4k",
  "name": "Wolfmax4K",
  "enabled": true,
  "priority": 66,
  "baseUrl": "https://wolfmax4k.com",
  "searchPath": "/buscar/{query}",
  "renderJs": true,
  "readySelector": "#resDetalle a.card-movie",
  "languageTokens": ["latino", "castellano"],
  "parser": {
    "rowSelector": "#resDetalle a.card-movie",
    "name": { "selector": "h3", "attr": "text" },
    "torrentUrl": { "selector": "", "attr": "href", "resolve": "absolute" }
  },
  "detail": {
    "followFrom": "torrentUrl",
    "torrentUrl": { "selector": "a[href*='.torrent']", "attr": "href", "resolve": "absolute" }
  }
}
```

- [ ] **Step 4: Actualizar SpanishTrackersDefinitionTest**

En `SpanishTrackersDefinitionTest.kt`, el test/aserción que hoy exige `wolfmax4k` deshabilitado (y el `@Ignore` de su test de parsing) ya no aplica: cambiarlo a que `BundledProviders.byId("wolfmax4k").enabled == true` y `renderJs == true`, o eliminar ese caso y dejar la cobertura de wolfmax4k en `Wolfmax4kDefinitionTest`. No dejar un test asertando que está deshabilitado. (dontorrent/divxtotal quedan igual.)

- [ ] **Step 5: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.Wolfmax4kDefinitionTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (providers.json válido; RealProvidersJsonTest sigue verde; SpanishTrackersDefinitionTest actualizado).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/Wolfmax4kDefinitionTest.kt app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt
git commit -m "feat(torrent): habilitar wolfmax4k vía render por WebView (renderJs)"
```

---

## Task 6: Verificación

**Files:** (ninguno nuevo)

- [ ] **Step 1: Suite completa verde**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 fallos.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verificación en device (manual)**

Instalar en el S24+ / Fire Stick. Buscar una película conocida y confirmar:
- wolfmax4k **devuelve fuentes** (el render esperó al `readySelector` y parseó las tarjetas).
- Al menos una fuente de wolfmax4k **reproduce** (render → tarjeta → ficha → `.torrent` → resolveSource).
- El WebView no cuelga la UI: apibay/knaben pintan primero; wolfmax llega en la fase 2. Timeout → sin resultados de wolfmax, el resto normal.
- Logcat (`ArkivProv`): "render onPageFinished, polling '#resDetalle a.card-movie'" y filas > 0.
- Si el sitio cambió su JS/estructura y el render no da resultados: `enabled:false` + `_nota`, sin bloquear al resto.

- [ ] **Step 4: (si todo verde) cierre**

Usar `superpowers:finishing-a-development-branch`.

---

## Self-Review — cobertura del spec

- **§2.1 esquema `renderJs`/`readySelector`:** Task 1. ✓
- **§2.2 `WebViewPageRenderer` (mutex/timeout/poll/degradar):** Task 2 (helpers testeados; WebView device-verified). ✓
- **§2.3 seam en DeclarativeHtmlBackend (listado renderizado, detalle con fetcher):** Task 3. ✓
- **§3 wiring AppGraph:** Task 4. ✓
- **§2.1 wolfmax4k def + detail.torrentUrl (reusa Task 12b):** Task 5. ✓
- **§4 testing (parsing, schema, seam, helpers puros):** Tasks 1,2,3,5. ✓
- **§5 device:** Task 6. ✓
- **§6 fuera de alcance (web, POST directo, pool):** respetado. ✓
- **§7 criterios de éxito:** Task 6 los verifica; "sin renderJs igual que antes" cubierto por el 2º test de Task 3. ✓

Riesgo conocido (no bloquea): el fixture del DOM renderizado es reconstruido del template del JS; el render vivo se confirma en device (Task 6). Si el `readySelector`/estructura difieren en vivo, se ajustan con un dump del device.
