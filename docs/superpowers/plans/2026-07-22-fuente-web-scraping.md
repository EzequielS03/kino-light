# Fuente web (scraping estilo Alfa) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar una fuente de contenido "web" (scraping declarativo de sitios de streaming en español, estilo addon Alfa) que se pinta en la hoja de fuentes por título/capítulo y en el grid del catálogo, junto a torrent y archive.org, configurable por JSON — SIN reproducir todavía (el click queda como stub).

**Architecture:** Módulo nuevo `data/catalog/web/` calcado sobre el existente `data/catalog/providers/` (mismo patrón definición-JSON → fetch → parse → fan-out → hot-update remoto). La web aporta enlaces/catálogo; TMDB es la capa de identidad que enriquece y fusiona. Reusa `FieldRule`, `SearchContext`, `ContentType`, `PageFetcher`/`HttpFetcher`, `CloudflareSolver` del paquete providers.

**Tech Stack:** Kotlin, Coroutines, Jsoup, OkHttp, org.json, JUnit4 (tests JVM puros sin Robolectric), Jetpack Compose.

## Global Constraints

- Commits SIN coautoría de Claude. Identidad git: `user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com` (usar `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit`).
- Tests unitarios JVM puros: `android.util.Log` lanza "not mocked" — envolver cualquier `Log.x` en `runCatching { }` (patrón ya usado en `DeclarativeHtmlBackend`).
- Idioma del código/comentarios: español, siguiendo el estilo del código existente.
- Reusar tipos existentes del paquete `providers` (`FieldRule`, `SearchContext`, `ContentType`, `PageFetcher`, `CloudflareSolver`) — NO duplicarlos.
- Degradación limpia: cualquier fallo (web caída, JSON inválido, Cloudflare, sin match TMDB) → lista vacía / fallback, nunca crash. El grid y la hoja quedan idénticos a hoy si la feature falla.
- El click a reproducir una fuente/tarjeta web NO reproduce en esta fase: muestra aviso "Reproducción web: próximamente".
- Correr tests con: `./gradlew :app:testDebugUnitTest`.

**Spec de referencia:** `docs/superpowers/specs/2026-07-22-fuente-web-scraping-design.md`. Ante dudas de scraping, consultar el código real de Alfa en el clon (`channels/<id>.py`, `lib/AlfaChannelHelper.py`, `core/tmdb.py`) y portarlo.

---

## File Structure

**Nuevos (`app/src/main/java/com/arkiv/player/data/catalog/web/`):**
- `WebModels.kt` — `WebResult` (resultado crudo de una web).
- `WebSourceDefinition.kt` — `WebParserRules`, `WebSourceDefinition` + `fromJson` (validado).
- `WebHtmlParser.kt` — `WebHtmlParser.parse(def, html)` → `List<WebResult>` (soporta `rowSelector` CSS y `rowRegex`).
- `WebSourceBackend.kt` — `WebSourceBackend(def, fetcher)`: `browse(kind, page)` + `search(ctx)`.
- `WebSourceRegistry.kt` — `parseDefinitions(json)` + `merge(bundled, remote)`.
- `WebSourceEngine.kt` — fan-out sobre definiciones activas: `browse(kind, page)` + `search(ctx)`.
- `WebTmdbMatcher.kt` — enriquece `WebResult` con `tmdbId`/póster vía `TmdbApi`.

**Nuevo asset:** `app/src/main/assets/web_sources.json`.

**Tests nuevos (`app/src/test/java/com/arkiv/player/data/catalog/web/`):** uno por componente + fixtures en `app/src/test/resources/web/`.

**Modificados:**
- `AppGraph.kt` — wiring del engine + matcher + bundled load + remote refresh.
- `ui/catalog/CineDetailScreen.kt` — `PlaySource.Web` + job web en `runSearch` + `SourceRow` badge + stub click.
- `ui/catalog/CineCatalogScreen.kt` (`CineCatalogViewModel`) — browse web + merge + badge.

---

### Task 1: Modelo `WebResult` y definición `WebSourceDefinition`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebModels.kt`
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceDefinition.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceDefinitionTest.kt`

**Interfaces:**
- Consumes: `FieldRule` (de `providers`, con `FieldRule.fromJson(JSONObject?): FieldRule?`).
- Produces:
  - `data class WebResult(siteId: String, siteName: String, title: String, year: String, pageUrl: String, posterUrl: String, language: String, quality: String = "", kind: String, tmdbId: Int? = null, subtitleUrl: String? = null, audioLanguages: List<String> = emptyList())`
  - `data class WebParserRules(rowSelector: String?, rowRegex: String?, rowRegexFields: List<String>, title: FieldRule?, pageUrl: FieldRule?, poster: FieldRule?, year: FieldRule?, quality: FieldRule?, kindHint: FieldRule?)`
  - `data class WebSourceDefinition(id, name, enabled, priority, baseUrl, hostAlt: List<String>, charset, needsCloudflare, languageTokens: List<String>, browse: Map<String,String>, search: String?, keywords: Map<String,String>, parser: WebParserRules)` con `companion object { fun fromJson(o: JSONObject): WebSourceDefinition? }`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceDefinitionTest {
    private fun def(json: String) = WebSourceDefinition.fromJson(JSONObject(json))

    @Test fun `parsea definicion valida con rowSelector`() {
        val d = def("""
          {"id":"cine","name":"Cine","priority":60,"baseUrl":"https://cine.tld/",
           "hostAlt":["https://cine2.tld"],"needsCloudflare":true,
           "languageTokens":["latino","castellano"],
           "browse":{"movie":"/peliculas?page={page}","tv":"/series?page={page}"},
           "search":"/?s={query}","keywords":{"movie":"{title} {year}","tv":"{title}"},
           "parser":{"rowSelector":"article.item",
             "title":{"selector":"h2","attr":"text"},
             "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"},
             "poster":{"selector":"img","attr":"src"}}}
        """.trimIndent())!!
        assertEquals("cine", d.id)
        assertEquals("https://cine.tld", d.baseUrl) // trailing slash recortado
        assertTrue(d.enabled) // default true
        assertEquals(listOf("https://cine2.tld"), d.hostAlt)
        assertEquals("/peliculas?page={page}", d.browse["movie"])
        assertEquals("article.item", d.parser.rowSelector)
        assertEquals("h2", d.parser.title!!.selector)
    }

    @Test fun `parsea definicion con rowRegex y rowRegexFields`() {
        val d = def("""
          {"id":"rx","baseUrl":"https://rx.tld","browse":{"movie":"/p/{page}"},
           "keywords":{"movie":"{title}"},
           "parser":{"rowRegex":"<a href=\"([^\"]+)\".*?<h3>([^<]+)",
             "rowRegexFields":["pageUrl","title"]}}
        """.trimIndent())!!
        assertEquals("<a href=\"([^\"]+)\".*?<h3>([^<]+)", d.parser.rowRegex)
        assertEquals(listOf("pageUrl", "title"), d.parser.rowRegexFields)
    }

    @Test fun `rechaza sin id o baseUrl o parser`() {
        assertNull(def("""{"baseUrl":"https://x.tld","parser":{"rowSelector":"a"}}"""))
        assertNull(def("""{"id":"x","parser":{"rowSelector":"a"}}"""))
        assertNull(def("""{"id":"x","baseUrl":"https://x.tld"}"""))
    }

    @Test fun `rechaza baseUrl no http`() {
        assertNull(def("""{"id":"x","baseUrl":"ftp://x.tld","parser":{"rowSelector":"a"}}"""))
    }

    @Test fun `rechaza parser sin rowSelector ni rowRegex`() {
        assertNull(def("""{"id":"x","baseUrl":"https://x.tld","parser":{"title":{"selector":"h2","attr":"text"}}}"""))
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceDefinitionTest*"`
Expected: FAIL con "unresolved reference: WebSourceDefinition".

- [ ] **Step 3: Implementar `WebModels.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

/**
 * Un resultado crudo de una web de streaming (una tarjeta del listado o de la búsqueda).
 * [pageUrl] es la carga útil para la fase de reproducción (aún no implementada). [tmdbId] lo
 * rellena WebTmdbMatcher; [subtitleUrl]/[audioLanguages]/[quality] fina se pueblan al resolver.
 */
data class WebResult(
    val siteId: String,
    val siteName: String,
    val title: String,
    val year: String,
    val pageUrl: String,
    val posterUrl: String,
    val language: String,
    val quality: String = "",
    val kind: String,              // "movie" | "tv"
    val tmdbId: Int? = null,
    val subtitleUrl: String? = null,
    val audioLanguages: List<String> = emptyList(),
)
```

- [ ] **Step 4: Implementar `WebSourceDefinition.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.FieldRule
import org.json.JSONObject

/** Reglas de parsing del listado de una web. Extracción de filas por rowSelector (CSS) O rowRegex. */
data class WebParserRules(
    val rowSelector: String?,
    val rowRegex: String?,
    val rowRegexFields: List<String>,   // nombres de campo por grupo capturado, en orden
    val title: FieldRule?,
    val pageUrl: FieldRule?,
    val poster: FieldRule?,
    val year: FieldRule?,
    val quality: FieldRule?,
    val kindHint: FieldRule?,
)

/** Definición declarativa de una web de streaming (espejo de ProviderDefinition, salida = páginas). */
data class WebSourceDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val baseUrl: String,
    val hostAlt: List<String>,
    val charset: String,
    val needsCloudflare: Boolean,
    val languageTokens: List<String>,
    val browse: Map<String, String>,   // kind -> pathTemplate con {page}
    val search: String?,               // pathTemplate con {query}
    val keywords: Map<String, String>, // kind -> plantilla de query
    val parser: WebParserRules,
) {
    companion object {
        /** Parsea y VALIDA. Devuelve null (sin lanzar) si la definición es inválida. */
        fun fromJson(o: JSONObject): WebSourceDefinition? = runCatching {
            val id = o.optString("id").ifBlank { return null }
            val baseUrl = o.optString("baseUrl").ifBlank { return null }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

            val pj = o.optJSONObject("parser") ?: return null
            val rowSelector = pj.optString("rowSelector").ifBlank { null }
            val rowRegex = pj.optString("rowRegex").ifBlank { null }
            if (rowSelector == null && rowRegex == null) return null
            val fieldsJson = pj.optJSONArray("rowRegexFields")
            val rowRegexFields = if (fieldsJson == null) emptyList()
                else (0 until fieldsJson.length()).map { fieldsJson.getString(it) }

            val browseJson = o.optJSONObject("browse")
            val browse = browseJson?.keys()?.asSequence()?.associateWith { browseJson.getString(it) } ?: emptyMap()
            val keywordsJson = o.optJSONObject("keywords")
            val keywords = keywordsJson?.keys()?.asSequence()?.associateWith { keywordsJson.getString(it) } ?: emptyMap()

            val altJson = o.optJSONArray("hostAlt")
            val hostAlt = if (altJson == null) emptyList()
                else (0 until altJson.length()).map { altJson.getString(it).trimEnd('/') }
            val tokensJson = o.optJSONArray("languageTokens")
            val tokens = if (tokensJson == null) emptyList()
                else (0 until tokensJson.length()).map { tokensJson.getString(it) }

            WebSourceDefinition(
                id = id,
                name = o.optString("name").ifBlank { id },
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 50),
                baseUrl = baseUrl.trimEnd('/'),
                hostAlt = hostAlt,
                charset = o.optString("charset", "utf-8"),
                needsCloudflare = o.optBoolean("needsCloudflare", false),
                languageTokens = tokens,
                browse = browse,
                search = o.optString("search").ifBlank { null },
                keywords = keywords,
                parser = WebParserRules(
                    rowSelector = rowSelector,
                    rowRegex = rowRegex,
                    rowRegexFields = rowRegexFields,
                    title = FieldRule.fromJson(pj.optJSONObject("title")),
                    pageUrl = FieldRule.fromJson(pj.optJSONObject("pageUrl")),
                    poster = FieldRule.fromJson(pj.optJSONObject("poster")),
                    year = FieldRule.fromJson(pj.optJSONObject("year")),
                    quality = FieldRule.fromJson(pj.optJSONObject("quality")),
                    kindHint = FieldRule.fromJson(pj.optJSONObject("kindHint")),
                ),
            )
        }.getOrNull()
    }
}
```

- [ ] **Step 5: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceDefinitionTest*"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebModels.kt \
        app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceDefinition.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceDefinitionTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): modelo WebResult y definicion declarativa WebSourceDefinition"
```

---

### Task 2: `WebHtmlParser` (rowSelector CSS + rowRegex)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebHtmlParser.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebHtmlParserTest.kt`

**Interfaces:**
- Consumes: `WebSourceDefinition`, `WebResult`, `HtmlParser.applyRule(Element, FieldRule, String)` (de `providers`).
- Produces: `object WebHtmlParser { fun parse(def: WebSourceDefinition, html: String, kind: String, maxRows: Int = 60): List<WebResult> }`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHtmlParserTest {
    private fun def(json: String) = WebSourceDefinition.fromJson(JSONObject(json))!!

    private val cssDef = def("""
      {"id":"cine","baseUrl":"https://cine.tld","browse":{"movie":"/p/{page}"},
       "keywords":{"movie":"{title}"},
       "parser":{"rowSelector":"article.item",
         "title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"},
         "poster":{"selector":"img","attr":"src"},
         "year":{"selector":".year","attr":"text","regex":"\\d{4}"},
         "quality":{"selector":".cal","attr":"text"}}}
    """.trimIndent())

    @Test fun `extrae filas por rowSelector con campos`() {
        val html = """
          <div>
            <article class="item"><a href="/pelicula/coco"><img src="/c.jpg"></a>
               <h2>Coco</h2><span class="year">Estreno 2017</span><span class="cal">HD</span></article>
            <article class="item"><a href="/pelicula/up"><img src="/u.jpg"></a>
               <h2>Up</h2><span class="year">2009</span></article>
          </div>
        """.trimIndent()
        val rows = WebHtmlParser.parse(cssDef, html, "movie")
        assertEquals(2, rows.size)
        assertEquals("Coco", rows[0].title)
        assertEquals("https://cine.tld/pelicula/coco", rows[0].pageUrl)
        assertEquals("https://cine.tld/c.jpg", rows[0].posterUrl)
        assertEquals("2017", rows[0].year)
        assertEquals("HD", rows[0].quality)
        assertEquals("movie", rows[0].kind)
        assertEquals("cine", rows[0].siteId)
        assertEquals("", rows[1].quality) // sin nodo .cal
    }

    @Test fun `descarta filas sin title o sin pageUrl`() {
        val html = """<article class="item"><h2>SinLink</h2></article>"""
        assertEquals(0, WebHtmlParser.parse(cssDef, html, "movie").size)
    }

    @Test fun `extrae filas por rowRegex mapeando grupos a campos`() {
        val rxDef = def("""
          {"id":"rx","baseUrl":"https://rx.tld","browse":{"movie":"/p/{page}"},
           "keywords":{"movie":"{title}"},
           "parser":{"rowRegex":"<a href=\"([^\"]+)\"><h3>([^<]+)</h3><i>(\\d{4})",
             "rowRegexFields":["pageUrl","title","year"]}}
        """.trimIndent())
        val html = """<a href="/pelicula/x"><h3>Peli X</h3><i>2020</i>
                      <a href="/pelicula/y"><h3>Peli Y</h3><i>2021</i>"""
        val rows = WebHtmlParser.parse(rxDef, html, "movie")
        assertEquals(2, rows.size)
        assertEquals("Peli X", rows[0].title)
        assertEquals("https://rx.tld/pelicula/x", rows[0].pageUrl) // resuelto absoluto
        assertEquals("2020", rows[0].year)
        assertEquals("Peli Y", rows[1].title)
    }

    @Test fun `rowSelector que no matchea devuelve vacio`() {
        assertTrue(WebHtmlParser.parse(cssDef, "<div>nada</div>", "movie").isEmpty())
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebHtmlParserTest*"`
Expected: FAIL con "unresolved reference: WebHtmlParser".

- [ ] **Step 3: Implementar `WebHtmlParser.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import android.util.Log
import com.arkiv.player.data.catalog.providers.FieldRule
import com.arkiv.player.data.catalog.providers.HtmlParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parsea el HTML de un listado de una web a [WebResult]. Soporta rowSelector (CSS) y rowRegex. */
object WebHtmlParser {

    fun parse(def: WebSourceDefinition, html: String, kind: String, maxRows: Int = 60): List<WebResult> {
        val p = def.parser
        return when {
            p.rowSelector != null -> parseCss(def, html, kind, maxRows)
            p.rowRegex != null -> parseRegex(def, html, kind, maxRows)
            else -> emptyList()
        }
    }

    private fun parseCss(def: WebSourceDefinition, html: String, kind: String, maxRows: Int): List<WebResult> {
        val doc = runCatching { Jsoup.parse(html, def.baseUrl) }.getOrNull() ?: return emptyList()
        val rows = doc.select(def.parser.rowSelector!!)
        if (rows.isEmpty()) {
            runCatching { Log.w("ArkivWeb", "web=${def.id} rowSelector matcheó 0 filas (¿cambió el sitio?)") }
            return emptyList()
        }
        val p = def.parser
        return rows.take(maxRows).mapNotNull { row ->
            val title = field(row, p.title, def.baseUrl)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pageUrl = field(row, p.pageUrl, def.baseUrl)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            toResult(def, kind, title, pageUrl,
                poster = field(row, p.poster, def.baseUrl).orEmpty(),
                year = field(row, p.year, def.baseUrl).orEmpty(),
                quality = field(row, p.quality, def.baseUrl).orEmpty(),
                kindHint = field(row, p.kindHint, def.baseUrl))
        }
    }

    private fun parseRegex(def: WebSourceDefinition, html: String, kind: String, maxRows: Int): List<WebResult> {
        val fields = def.parser.rowRegexFields
        val rx = runCatching { Regex(def.parser.rowRegex!!, RegexOption.DOT_MATCHES_ALL) }.getOrNull() ?: return emptyList()
        val out = ArrayList<WebResult>()
        for (m in rx.findAll(html)) {
            if (out.size >= maxRows) break
            val g = m.groupValues // g[0] = match completo; g[1..] = grupos
            fun byName(name: String): String {
                val idx = fields.indexOf(name)
                return if (idx >= 0 && idx + 1 < g.size) g[idx + 1].trim() else ""
            }
            val title = byName("title").ifBlank { continue }
            val pageUrl = resolveAbs(byName("pageUrl"), def.baseUrl).ifBlank { continue }
            out.add(toResult(def, kind, title, pageUrl,
                poster = resolveAbs(byName("poster"), def.baseUrl),
                year = byName("year"),
                quality = byName("quality"),
                kindHint = byName("kindHint").ifBlank { null }))
        }
        return out
    }

    private fun toResult(
        def: WebSourceDefinition, kind: String, title: String, pageUrl: String,
        poster: String, year: String, quality: String, kindHint: String?,
    ): WebResult {
        // kindHint (regex /(pelicula|serie)/) refina el kind; si no, usa el kind del contexto.
        val resolvedKind = when {
            kindHint == null -> kind
            kindHint.contains("serie", true) || kindHint.contains("tv", true) -> "tv"
            kindHint.contains("pelicula", true) || kindHint.contains("movie", true) -> "movie"
            else -> kind
        }
        val lang = def.languageTokens.firstOrNull()?.let { normalizeLang(it) } ?: ""
        return WebResult(
            siteId = def.id, siteName = def.name, title = title, year = year,
            pageUrl = pageUrl, posterUrl = poster, language = lang, quality = quality, kind = resolvedKind,
        )
    }

    private fun normalizeLang(token: String): String = when {
        token.contains("latino", true) -> "LAT"
        token.contains("castellano", true) -> "CAST"
        token.contains("spanish", true) -> "ES"
        else -> token.uppercase()
    }

    private fun field(row: Element, rule: FieldRule?, baseUrl: String): String? =
        rule?.let { HtmlParser.applyRule(row, it, baseUrl) }

    private fun resolveAbs(value: String, baseUrl: String): String = when {
        value.isBlank() || value.startsWith("http") -> value
        else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
    }
}
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebHtmlParserTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebHtmlParser.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebHtmlParserTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebHtmlParser con rowSelector CSS y rowRegex"
```

---

### Task 3: `WebSourceBackend` (browse + search)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceBackendTest.kt`

**Interfaces:**
- Consumes: `PageFetcher` (de `providers`, `suspend fun fetch(url, charset): String?`), `SearchContext`, `ContentType`, `WebSourceDefinition`, `WebHtmlParser`.
- Produces: `class WebSourceBackend(def: WebSourceDefinition, fetcher: PageFetcher)` con `suspend fun browse(kind: String, page: Int): List<WebResult>` y `suspend fun search(ctx: SearchContext): List<WebResult>`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.PageFetcher
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceBackendTest {
    // Fetcher fake: devuelve HTML por URL, o null (fallo) para simular dominio caído.
    private class FakeFetcher(val pages: Map<String, String>) : PageFetcher {
        val hits = mutableListOf<String>()
        override suspend fun fetch(url: String, charset: String): String? { hits += url; return pages[url] }
    }

    private val def = WebSourceDefinition.fromJson(JSONObject("""
      {"id":"cine","baseUrl":"https://cine.tld","hostAlt":["https://cine2.tld"],
       "browse":{"movie":"/peliculas?page={page}"},"search":"/?s={query}",
       "keywords":{"movie":"{title} {year}"},
       "parser":{"rowSelector":"article.item",
         "title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"}}}
    """.trimIndent()))!!

    private val gridHtml = """<article class="item"><a href="/pelicula/coco"></a><h2>Coco</h2></article>"""

    @Test fun `browse arma la URL de browse y parsea`() = runBlocking {
        val f = FakeFetcher(mapOf("https://cine.tld/peliculas?page=1" to gridHtml))
        val rows = WebSourceBackend(def, f).browse("movie", 1)
        assertEquals(1, rows.size)
        assertEquals("Coco", rows[0].title)
    }

    @Test fun `search sustituye query y parsea`() = runBlocking {
        val url = "https://cine.tld/?s=Coco%202017"
        val f = FakeFetcher(mapOf(url to gridHtml))
        val ctx = SearchContext(titles = listOf("Coco"), type = ContentType.MOVIE, year = "2017")
        val rows = WebSourceBackend(def, f).search(ctx)
        assertTrue(rows.any { it.title == "Coco" })
    }

    @Test fun `hostAlt como fallback cuando el dominio principal falla`() = runBlocking {
        val altUrl = "https://cine2.tld/peliculas?page=1"
        val f = FakeFetcher(mapOf(altUrl to gridHtml)) // el host principal NO responde
        val rows = WebSourceBackend(def, f).browse("movie", 1)
        assertEquals(1, rows.size)
        assertTrue(f.hits.contains("https://cine.tld/peliculas?page=1")) // intentó el principal primero
        assertTrue(f.hits.contains(altUrl))                              // y cayó al alt
    }

    @Test fun `browse de un kind sin plantilla devuelve vacio`() = runBlocking {
        val f = FakeFetcher(emptyMap())
        assertTrue(WebSourceBackend(def, f).browse("tv", 1).isEmpty())
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceBackendTest*"`
Expected: FAIL con "unresolved reference: WebSourceBackend".

- [ ] **Step 3: Implementar `WebSourceBackend.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import android.util.Log
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.PageFetcher
import com.arkiv.player.data.catalog.providers.SearchContext
import java.net.URLEncoder

/** Ejecuta UNA definición web: arma URL (browse o search) → fetch (con hostAlt) → parse → WebResult. */
class WebSourceBackend(
    private val def: WebSourceDefinition,
    private val fetcher: PageFetcher,
) {
    /** Lista el catálogo del sitio para el grid. kind: "movie" | "tv". */
    suspend fun browse(kind: String, page: Int): List<WebResult> {
        if (!def.enabled) return emptyList()
        val template = def.browse[kind] ?: return emptyList()
        val path = template.replace("{page}", page.toString())
        val html = fetchWithFallback(path) ?: return emptyList()
        return WebHtmlParser.parse(def, html, kind)
    }

    /** Busca por título para la hoja de fuentes. */
    suspend fun search(ctx: SearchContext): List<WebResult> {
        if (!def.enabled) return emptyList()
        val searchPath = def.search ?: return emptyList()
        val query = buildQuery(ctx) ?: return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val path = searchPath.replace("{query}", encoded)
        val kind = if (ctx.type == ContentType.MOVIE) "movie" else "tv"
        val html = fetchWithFallback(path) ?: return emptyList()
        return WebHtmlParser.parse(def, html, kind)
    }

    /** Arma el query desde la plantilla keywords del sitio. */
    private fun buildQuery(ctx: SearchContext): String? {
        val template = when (ctx.type) {
            ContentType.MOVIE -> def.keywords["movie"]
            ContentType.TV, ContentType.ANIME -> def.keywords["tv"] ?: def.keywords["movie"]
        } ?: return null
        val title = ctx.titles.firstOrNull { it.isNotBlank() } ?: return null
        return template.replace("{title}", title).replace("{year}", ctx.year.take(4))
            .replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }

    /** Prueba baseUrl y, si falla, cada hostAlt en orden. Devuelve el primer HTML no nulo. */
    private suspend fun fetchWithFallback(path: String): String? {
        for (base in listOf(def.baseUrl) + def.hostAlt) {
            val url = base.trimEnd('/') + path
            val html = runCatching { fetcher.fetch(url, def.charset) }.getOrNull()
            if (!html.isNullOrBlank()) return html
        }
        runCatching { Log.w("ArkivWeb", "web=${def.id}: sin respuesta en baseUrl ni hostAlt para $path") }
        return null
    }
}
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceBackendTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceBackend.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceBackendTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebSourceBackend con browse, search y fallback hostAlt"
```

---

### Task 4: `WebSourceRegistry` (parse + merge)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceRegistry.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceRegistryTest.kt`

**Interfaces:**
- Produces: `object WebSourceRegistry { fun parseDefinitions(json: String): List<WebSourceDefinition>; fun merge(bundled: List<WebSourceDefinition>, remote: List<WebSourceDefinition>): List<WebSourceDefinition> }`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceRegistryTest {
    private val a = """{"id":"a","baseUrl":"https://a.tld","priority":10,"browse":{"movie":"/p/{page}"},
        "keywords":{"movie":"{title}"},"parser":{"rowSelector":"i","title":{"selector":"h2","attr":"text"},
        "pageUrl":{"selector":"a","attr":"href"}}}"""
    private val b = """{"id":"b","baseUrl":"https://b.tld","priority":90,"browse":{"movie":"/p/{page}"},
        "keywords":{"movie":"{title}"},"parser":{"rowSelector":"i","title":{"selector":"h2","attr":"text"},
        "pageUrl":{"selector":"a","attr":"href"}}}"""

    @Test fun `parsea array descartando invalidas`() {
        val list = WebSourceRegistry.parseDefinitions("[$a, {\"id\":\"malo\"}, $b]")
        assertEquals(2, list.size)
    }

    @Test fun `merge la remota gana por id y ordena por prioridad desc`() {
        val bundled = WebSourceRegistry.parseDefinitions("[$a, $b]")
        val remoteA = a.replace("\"priority\":10", "\"priority\":99")
        val remote = WebSourceRegistry.parseDefinitions("[$remoteA]")
        val merged = WebSourceRegistry.merge(bundled, remote)
        assertEquals(2, merged.size)
        assertEquals("a", merged[0].id)      // ahora priority 99 → primero
        assertEquals(99, merged[0].priority) // la remota ganó
    }

    @Test fun `json ilegible devuelve lista vacia`() {
        assertEquals(0, WebSourceRegistry.parseDefinitions("no soy json").size)
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceRegistryTest*"`
Expected: FAIL con "unresolved reference: WebSourceRegistry".

- [ ] **Step 3: Implementar `WebSourceRegistry.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import android.util.Log
import org.json.JSONArray

/** Carga y mergea definiciones web (asset bundled + remota de blog). Espejo de ProviderRegistry. */
object WebSourceRegistry {

    fun parseDefinitions(json: String): List<WebSourceDefinition> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { WebSourceDefinition.fromJson(it) }
        }
    }.onFailure { runCatching { Log.w("ArkivWeb", "web_sources.json ilegible: $it") } }.getOrDefault(emptyList())

    /** La versión remota (de blog) gana sobre la bundled cuando comparten `id`. */
    fun merge(bundled: List<WebSourceDefinition>, remote: List<WebSourceDefinition>): List<WebSourceDefinition> {
        val byId = LinkedHashMap<String, WebSourceDefinition>()
        bundled.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        return byId.values.sortedByDescending { it.priority }
    }
}
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceRegistryTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceRegistry.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceRegistryTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebSourceRegistry (parse + merge remoto por id)"
```

---

### Task 5: `WebSourceEngine` (fan-out sobre webs activas)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceEngine.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceEngineTest.kt`

**Interfaces:**
- Consumes: `PageFetcher`, `SearchContext`, `WebSourceDefinition`, `WebSourceBackend`.
- Produces: `class WebSourceEngine(fetcher: PageFetcher, definitions: () -> List<WebSourceDefinition>)` con `suspend fun browse(kind: String, page: Int): List<WebResult>` y `suspend fun search(ctx: SearchContext): List<WebResult>`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.PageFetcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceEngineTest {
    private class MapFetcher(val pages: Map<String, String>) : PageFetcher {
        override suspend fun fetch(url: String, charset: String): String? = pages[url]
    }
    private fun def(id: String, host: String) = WebSourceDefinition.fromJson(JSONObject("""
      {"id":"$id","baseUrl":"$host","browse":{"movie":"/p?page={page}"},"keywords":{"movie":"{title}"},
       "parser":{"rowSelector":"article","title":{"selector":"h2","attr":"text"},
         "pageUrl":{"selector":"a","attr":"href","resolve":"absolute"}}}
    """.trimIndent()))!!

    private fun html(t: String) = """<article><a href="/x"></a><h2>$t</h2></article>"""

    @Test fun `browse agrega resultados de todas las webs activas`() = runBlocking {
        val defs = listOf(def("a", "https://a.tld"), def("b", "https://b.tld"))
        val f = MapFetcher(mapOf(
            "https://a.tld/p?page=1" to html("DesdeA"),
            "https://b.tld/p?page=1" to html("DesdeB"),
        ))
        val rows = WebSourceEngine(f) { defs }.browse("movie", 1)
        assertEquals(setOf("DesdeA", "DesdeB"), rows.map { it.title }.toSet())
    }

    @Test fun `una web caida no rompe a las demas`() = runBlocking {
        val defs = listOf(def("a", "https://a.tld"), def("b", "https://b.tld"))
        val f = MapFetcher(mapOf("https://b.tld/p?page=1" to html("DesdeB"))) // 'a' no responde
        val rows = WebSourceEngine(f) { defs }.browse("movie", 1)
        assertEquals(listOf("DesdeB"), rows.map { it.title })
    }

    @Test fun `ignora definiciones deshabilitadas`() = runBlocking {
        val disabled = WebSourceDefinition.fromJson(JSONObject("""
          {"id":"off","baseUrl":"https://off.tld","enabled":false,"browse":{"movie":"/p?page={page}"},
           "keywords":{"movie":"{title}"},"parser":{"rowSelector":"article",
             "title":{"selector":"h2","attr":"text"},"pageUrl":{"selector":"a","attr":"href"}}}
        """.trimIndent()))!!
        val f = MapFetcher(mapOf("https://off.tld/p?page=1" to html("NoDebeSalir")))
        assertEquals(0, WebSourceEngine(f) { listOf(disabled) }.browse("movie", 1).size)
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceEngineTest*"`
Expected: FAIL con "unresolved reference: WebSourceEngine".

- [ ] **Step 3: Implementar `WebSourceEngine.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import com.arkiv.player.data.catalog.providers.PageFetcher
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fan-out sobre TODAS las webs activas del registro. Lee las definiciones FRESCAS en cada llamada
 * (vía [definitions]) para reflejar el hot-update remoto sin reconstruir. Cada web corre en su
 * corrutina con runCatching: el fallo de una no rompe a las demás.
 */
class WebSourceEngine(
    private val fetcher: PageFetcher,
    private val definitions: () -> List<WebSourceDefinition>,
) {
    suspend fun browse(kind: String, page: Int): List<WebResult> = coroutineScope {
        definitions().filter { it.enabled }.map { def ->
            async { runCatching { WebSourceBackend(def, fetcher).browse(kind, page) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun search(ctx: SearchContext): List<WebResult> = coroutineScope {
        definitions().filter { it.enabled }.map { def ->
            async { runCatching { WebSourceBackend(def, fetcher).search(ctx) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }
}
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebSourceEngineTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebSourceEngine.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebSourceEngineTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebSourceEngine (fan-out con aislamiento de fallos)"
```

---

### Task 6: `WebTmdbMatcher` (capa de identidad)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebTmdbMatcher.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebTmdbMatcherTest.kt`

**Interfaces:**
- Consumes: `WebResult`. Se inyecta un buscador desacoplado (para testear sin `TmdbApi`): `fun interface TmdbLookup { suspend fun search(type: String, query: String): List<TmdbHit> }` con `data class TmdbHit(id, title, year, posterUrl)`.
- Produces: `class WebTmdbMatcher(lookup: TmdbLookup)` con `suspend fun enrich(result: WebResult): WebResult`, `suspend fun enrichAll(results: List<WebResult>): List<WebResult>`, y `companion object { fun normalize(title: String): String }`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTmdbMatcherTest {
    private fun web(title: String, year: String, kind: String = "movie") = WebResult(
        siteId = "s", siteName = "S", title = title, year = year, pageUrl = "https://s/x",
        posterUrl = "https://s/p.jpg", language = "LAT", kind = kind,
    )
    private val hits = listOf(
        TmdbHit(id = 354912, title = "Coco", year = "2017", posterUrl = "https://tmdb/coco.jpg"),
    )
    private fun matcher() = WebTmdbMatcher(lookup = { _, _ -> hits })

    @Test fun `normalize quita tildes puntuacion y parentesis de anio`() {
        assertEquals("coco", WebTmdbMatcher.normalize("¡Coco! (2017)"))
        assertEquals("el laberinto del fauno", WebTmdbMatcher.normalize("El Laberinto del Fauno"))
    }

    @Test fun `match confiable setea tmdbId y hereda poster`() = runBlocking {
        val out = matcher().enrich(web("Coco", "2017"))
        assertEquals(354912, out.tmdbId)
        assertEquals("https://tmdb/coco.jpg", out.posterUrl)
    }

    @Test fun `sin match conserva datos de la web y tmdbId null`() = runBlocking {
        val out = WebTmdbMatcher(lookup = { _, _ -> emptyList() }).enrich(web("Peli Rara", "1999"))
        assertNull(out.tmdbId)
        assertEquals("https://s/p.jpg", out.posterUrl) // póster original
    }

    @Test fun `no matchea si el anio difiere mas de uno`() = runBlocking {
        val out = matcher().enrich(web("Coco", "2005"))
        assertNull(out.tmdbId)
    }

    @Test fun `matchea con anio faltante en la web`() = runBlocking {
        val out = matcher().enrich(web("Coco", ""))
        assertEquals(354912, out.tmdbId) // sin año, basta el título normalizado
    }
}
```

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebTmdbMatcherTest*"`
Expected: FAIL con "unresolved reference: WebTmdbMatcher".

- [ ] **Step 3: Implementar `WebTmdbMatcher.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

/** Un hit de TMDB reducido a lo que el matcher necesita. */
data class TmdbHit(val id: Int, val title: String, val year: String, val posterUrl: String)

/** Búsqueda TMDB desacoplada (para testear sin la API real). type: "movie" | "tv". */
fun interface TmdbLookup {
    suspend fun search(type: String, query: String): List<TmdbHit>
}

/**
 * Capa de identidad: enriquece un [WebResult] con TMDB (equivalente a set_infoLabels_itemlist de Alfa).
 * Match confiable = título normalizado igual + año ±1 (o año faltante en la web) → hereda tmdbId + póster.
 */
class WebTmdbMatcher(private val lookup: TmdbLookup) {

    private val cache = HashMap<String, List<TmdbHit>>()

    suspend fun enrichAll(results: List<WebResult>): List<WebResult> = coroutineScope {
        results.map { async { enrich(it) } }.map { it.await() }
    }

    suspend fun enrich(result: WebResult): WebResult {
        val type = if (result.kind == "tv") "tv" else "movie"
        val key = "$type:${normalize(result.title)}"
        val hits = cache.getOrPut(key) {
            runCatching { lookup.search(type, result.title) }.getOrDefault(emptyList())
        }
        val match = hits.firstOrNull { hit -> isConfident(result, hit) } ?: return result
        return result.copy(
            tmdbId = match.id,
            posterUrl = match.posterUrl.ifBlank { result.posterUrl },
        )
    }

    private fun isConfident(web: WebResult, hit: TmdbHit): Boolean {
        if (normalize(web.title) != normalize(hit.title)) return false
        val wy = web.year.take(4).toIntOrNull()
        val hy = hit.year.take(4).toIntOrNull()
        if (wy == null || hy == null) return true // sin año en alguno → basta el título
        return kotlin.math.abs(wy - hy) <= 1
    }

    companion object {
        /** minúsculas, sin tildes, sin puntuación, sin "(2024)", espacios colapsados. */
        fun normalize(title: String): String {
            var s = title.lowercase().replace(Regex("\\(\\d{4}\\)"), " ")
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            s = s.replace(Regex("[^a-z0-9 ]"), " ")
            return s.replace(Regex("\\s+"), " ").trim()
        }
    }
}
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebTmdbMatcherTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebTmdbMatcher.kt \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebTmdbMatcherTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebTmdbMatcher (identidad/enriquecimiento TMDB)"
```

---

### Task 7: Seed `web_sources.json` + primer canal (`pelisplus`) validado por fixture

**Files:**
- Create: `app/src/main/assets/web_sources.json`
- Create: `app/src/test/resources/web/pelisplus_movies.html` (HTML real recortado del listado de películas de pelisplus)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebFixtureTest.kt`

**Interfaces:**
- Consumes: `WebSourceRegistry.parseDefinitions`, `WebHtmlParser.parse`.
- Produces: `web_sources.json` con al menos la entrada `pelisplus`; test de fixture que valida su parser.

**Nota de portado:** consultar `channels/pelisplus.py` en el clon de Alfa. Su `list_all` usa `soup.find_all("article", class_="item")` con `elem.a['href']`, `elem.img['alt']`, `elem.h2.text`. Traducir esos a selectores CSS.

- [ ] **Step 1: Escribir el test que falla (con fixture)**

Primero guardar `app/src/test/resources/web/pelisplus_movies.html` con HTML real del listado (bajar la página de películas del sitio y recortar ~3 tarjetas `<article class="item">…</article>`; si el selector real difiere del ejemplo, ajustar el JSON del Step 3 para que matcheen).

```kotlin
package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertTrue
import org.junit.Test

class WebFixtureTest {
    private fun readResource(path: String): String =
        this::class.java.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    private fun defById(id: String): WebSourceDefinition {
        val json = readResource("web_sources_test.json").ifBlank { "" }
            .ifEmpty { readResource("../../../main/assets/web_sources.json") } // fallback
        return WebSourceRegistry.parseDefinitions(json).first { it.id == id }
    }

    @Test fun `pelisplus parsea el fixture de peliculas con title y pageUrl`() {
        val def = WebSourceRegistry.parseDefinitions(readResource("web/bundled_web_sources.json"))
            .first { it.id == "pelisplus" }
        val html = readResource("web/pelisplus_movies.html")
        val rows = WebHtmlParser.parse(def, html, "movie")
        assertTrue("esperaba >=1 fila parseada", rows.isNotEmpty())
        assertTrue("todas con title", rows.all { it.title.isNotBlank() })
        assertTrue("todas con pageUrl http", rows.all { it.pageUrl.startsWith("http") })
    }
}
```

Nota: copiar el `web_sources.json` real a `app/src/test/resources/web/bundled_web_sources.json` (o crear un symlink en el build) para que el test lea la MISMA definición que se envía en el APK. Alternativa simple: en este test, pegar el JSON de la definición `pelisplus` inline como en los otros tests — pero preferimos el fixture compartido para validar el asset real.

- [ ] **Step 2: Correr el test para ver que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebFixtureTest*"`
Expected: FAIL (falta el asset / fixture).

- [ ] **Step 3: Crear `app/src/main/assets/web_sources.json`**

```json
[
  {
    "id": "pelisplus",
    "name": "PelisPlus",
    "enabled": true,
    "priority": 60,
    "baseUrl": "https://pelisplushd.bz",
    "hostAlt": [],
    "needsCloudflare": false,
    "languageTokens": ["latino", "castellano"],
    "browse": { "movie": "/peliculas?page={page}", "tv": "/series?page={page}" },
    "search": "/search?s={query}",
    "keywords": { "movie": "{title}", "tv": "{title}" },
    "parser": {
      "rowSelector": "article.item",
      "title":   { "selector": "h2", "attr": "text" },
      "pageUrl": { "selector": "a", "attr": "href", "resolve": "absolute" },
      "poster":  { "selector": "img", "attr": "src" },
      "year":    { "selector": ".year", "attr": "text", "regex": "\\d{4}" },
      "kindHint":{ "selector": "a", "attr": "href", "regex": "/(pelicula|serie)/" }
    }
  }
]
```

(Ajustar `baseUrl` y selectores a lo que muestre el fixture real. El dominio del ejemplo puede haber cambiado — verificar contra Alfa `channels/pelisplus.json` campo `host`/`host_alt`.)

Copiar el asset al recurso de test para el fixture compartido:
```bash
mkdir -p app/src/test/resources/web
cp app/src/main/assets/web_sources.json app/src/test/resources/web/bundled_web_sources.json
```

- [ ] **Step 4: Correr el test para ver que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebFixtureTest*"`
Expected: PASS. Si falla por 0 filas, corregir los selectores en el JSON hasta que matcheen el fixture (esto ES la validación del portado).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/web_sources.json app/src/test/resources/web/ \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebFixtureTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): seed web_sources.json con pelisplus + validacion por fixture"
```

---

### Task 8: Wiring en `AppGraph`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (añadir junto al bloque de providers, ~línea 80-123)

**Interfaces:**
- Consumes: `HttpFetcher` (ya instanciado como `providerFetcher`), `WebSourceRegistry`, `WebSourceEngine`, `WebTmdbMatcher`, `TmdbLookup`, `tmdbApi`.
- Produces: `AppGraph.webSourceEngine: WebSourceEngine`, `AppGraph.webTmdbMatcher: WebTmdbMatcher`, y `refreshRemoteWebSources()`. `settings.webSourcesUrl` (URL remota; añadir con default `https://<blog>/web_sources.json`).

- [ ] **Step 1: Añadir imports** (junto a los de providers, ~línea 18-24)

```kotlin
import com.arkiv.player.data.catalog.web.WebSourceDefinition
import com.arkiv.player.data.catalog.web.WebSourceEngine
import com.arkiv.player.data.catalog.web.WebSourceRegistry
import com.arkiv.player.data.catalog.web.WebTmdbMatcher
import com.arkiv.player.data.catalog.web.TmdbHit
```

- [ ] **Step 2: Añadir el bloque de wiring** (después del bloque de providers, tras la línea ~110)

```kotlin
    // --- Capa de fuentes web on-device (scraping estilo Alfa) ---
    @Volatile private var webSourceDefinitions: List<WebSourceDefinition> = loadBundledWebSources()

    private fun loadBundledWebSources(): List<WebSourceDefinition> = runCatching {
        val json = appContext.assets.open("web_sources.json").bufferedReader().use { it.readText() }
        WebSourceRegistry.parseDefinitions(json)
    }.getOrDefault(emptyList())

    val webSourceEngine: WebSourceEngine by lazy {
        WebSourceEngine(providerFetcher) { webSourceDefinitions }
    }

    val webTmdbMatcher: WebTmdbMatcher by lazy {
        WebTmdbMatcher(lookup = { type, query ->
            runCatching {
                tmdbApi.search(type, query).map { TmdbHit(it.id, it.title, it.year, it.posterUrl) }
            }.getOrDefault(emptyList())
        })
    }

    /** Baja web_sources.json de blog (async) y mergea sobre las bundled. Si falla, quedan las bundled. */
    private suspend fun refreshRemoteWebSources() {
        runCatching {
            val remote = okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(okhttp3.Request.Builder().url(settings.webSourcesUrl.value).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching
            val merged = WebSourceRegistry.merge(webSourceDefinitions, WebSourceRegistry.parseDefinitions(remote))
            if (merged.isNotEmpty()) webSourceDefinitions = merged
        }
    }
```

- [ ] **Step 3: Añadir el setting `webSourcesUrl`**

Buscar en `Settings` (el `settings` que expone `providersUrl`) y añadir un valor análogo `webSourcesUrl` con default `settings.providersUrl` cambiando el filename a `web_sources.json`. Ubicar la definición de `providersUrl`:

Run: `grep -rn "providersUrl" app/src/main/java/com/arkiv/player`

Añadir junto a él un `webSourcesUrl` con el mismo patrón (misma base de blog, filename `web_sources.json`).

- [ ] **Step 4: Enganchar el refresh remoto** donde ya se llama `refreshRemoteProviders()`

Run: `grep -rn "refreshRemoteProviders" app/src/main/java/com/arkiv/player`
Añadir una llamada a `refreshRemoteWebSources()` en el mismo lugar (mismo `applicationScope.launch { }`).

- [ ] **Step 5: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/*Settings*.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): wiring de WebSourceEngine + WebTmdbMatcher en AppGraph"
```

---

### Task 9: Integración en la hoja de fuentes (`CineDetailScreen`)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` (PlaySource ~66-69, runSearch ~114-136, playSource ~188-191, SourceRow ~316-343)

**Interfaces:**
- Consumes: `graph.webSourceEngine.search(ctx)`, `graph.webTmdbMatcher.enrichAll(...)`, `WebResult`, `SearchContext`, `ContentType`.
- Produces: `PlaySource.Web(result: WebResult)` visible en la hoja con badge; click = stub.

- [ ] **Step 1: Añadir la variante `Web` al sealed interface** (~línea 66)

```kotlin
private sealed interface PlaySource {
    data class Torrent(val result: TorrentResult) : PlaySource
    data class Archive(val item: ArchiveSearchResult) : PlaySource
    data class Web(val result: com.arkiv.player.data.catalog.web.WebResult) : PlaySource
}
```

- [ ] **Step 2: Añadir el job web en `runSearch`** (dentro del `searchJob = scope.launch { ... }`, junto a torrentsJob/archiveJob, ~línea 120-134)

```kotlin
            val webJob = async {
                val ctx = com.arkiv.player.data.catalog.providers.SearchContext(
                    titles = d.searchTitles,
                    type = if (ep != null) com.arkiv.player.data.catalog.providers.ContentType.TV
                           else com.arkiv.player.data.catalog.providers.ContentType.MOVIE,
                    season = ep?.season ?: 0,
                    episode = ep?.episode ?: 0,
                    year = d.year,
                )
                runCatching {
                    val raw = graph.webSourceEngine.search(ctx)
                    graph.webTmdbMatcher.enrichAll(raw)
                }.getOrDefault(emptyList())
            }
```

Y cambiar la línea de composición de fuentes (~134):

```kotlin
            val torrents = torrentsJob.await().map { PlaySource.Torrent(it) }
            val archive = archiveJob.await().map { PlaySource.Archive(it) }
            val web = webJob.await().map { PlaySource.Web(it) }
            if (sheetEpisode == ep) { sources = torrents + archive + web; searching = false }
```

- [ ] **Step 3: Añadir el stub de reproducción** en `playSource` (~línea 188)

```kotlin
    fun playSource(s: PlaySource) = when (s) {
        is PlaySource.Torrent -> play(s.result)
        is PlaySource.Archive -> playArchive(s.item)
        is PlaySource.Web -> { error = "Reproducción web: próximamente" }
    }
```

- [ ] **Step 4: Añadir el badge web en `SourceRow`** (dentro del `when (source)`, ~línea 333)

```kotlin
                is PlaySource.Web -> {
                    val r = source.result
                    Text(r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val extra = buildString {
                        append(r.siteName)
                        if (r.language.isNotBlank()) append("  ·  ").append(r.language)
                        if (r.quality.isNotBlank()) append("  ·  ").append(r.quality)
                    }
                    Text(extra, color = Color(0xFFB39DDB), style = MaterialTheme.typography.labelSmall) // violeta = web
                }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (el `when` sobre PlaySource ahora es exhaustivo con las 3 variantes).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): fuente web en la hoja de fuentes (badge violeta, click stub)"
```

---

### Task 10: Integración en el grid (`CineCatalogViewModel` / `CineCatalogScreen`)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt` (`CineCatalogViewModel` ~70+, y el composable de la tarjeta del grid)

**Interfaces:**
- Consumes: `graph.webSourceEngine.browse(kind, page)`, `graph.webTmdbMatcher.enrichAll(...)`, `TmdbItem`.
- Produces: en el grid, `List<GridCard>` donde `data class GridCard(val item: TmdbItem, val hasWeb: Boolean)`; badge "Web" en tarjetas con `hasWeb=true` o creadas desde web.

**Nota:** primero leer el archivo completo para ubicar dónde el VM emite `_items` y cómo el composable pinta cada `TmdbItem`. `grep -n "fun load\|_items.value\|itemsFlow\|LazyVerticalGrid\|TmdbItem" app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt`.

- [ ] **Step 1: Añadir el modelo de tarjeta y el merge en el VM**

En `CineCatalogViewModel`, tras obtener `List<TmdbItem>` de TMDB en su `load`, lanzar el browse web en paralelo y fusionar por `tmdbId`. Añadir:

```kotlin
    data class GridCard(val item: TmdbItem, val hasWeb: Boolean)

    // dentro de load(), después de obtener `tmdb: List<TmdbItem>`:
    private suspend fun mergeWeb(tmdb: List<TmdbItem>, kind: String, page: Int): List<GridCard> {
        val webRaw = runCatching { graph.webSourceEngine.browse(kind, page) }.getOrDefault(emptyList())
        if (webRaw.isEmpty()) return tmdb.map { GridCard(it, hasWeb = false) }
        val web = runCatching { graph.webTmdbMatcher.enrichAll(webRaw) }.getOrDefault(webRaw)
        val webTmdbIds = web.mapNotNull { it.tmdbId }.toSet()
        val cards = tmdb.map { GridCard(it, hasWeb = it.id in webTmdbIds) }.toMutableList()
        val presentIds = tmdb.map { it.id }.toSet()
        // Web con tmdbId nuevo → tarjeta propia (usa metadata TMDB heredada si la hubo).
        web.filter { it.tmdbId != null && it.tmdbId !in presentIds }
            .distinctBy { it.tmdbId }
            .forEach { w ->
                cards += GridCard(
                    TmdbItem(id = w.tmdbId!!, type = w.kind, title = w.title,
                             originalTitle = w.title, posterUrl = w.posterUrl, year = w.year),
                    hasWeb = true,
                )
            }
        return cards
    }
```

Cambiar el tipo del StateFlow de items de `List<TmdbItem>` a `List<GridCard>` y adaptar `_items.value = ...` para usar `mergeWeb(...)`. (El VM necesita acceso a `graph`; si hoy recibe `TmdbApi` en el constructor, añadir `webSourceEngine`/`webTmdbMatcher` como parámetros del constructor y pasarlos desde donde se instancia el VM — buscar `CineCatalogViewModel(` para el call-site.)

- [ ] **Step 2: Pintar el badge "Web" en la tarjeta del grid**

En el composable que renderiza cada tarjeta (hoy recibe `TmdbItem`; ahora recibe `GridCard`), tras la imagen del póster añadir un badge cuando `card.hasWeb`:

```kotlin
        if (card.hasWeb) {
            Box(
                Modifier.padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFB39DDB)).padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Text("WEB", color = Color.Black, style = MaterialTheme.typography.labelSmall) }
        }
```

Adaptar el `onClick` de la tarjeta para usar `card.item` (misma navegación al detalle que hoy — `CineDetailScreen` seguirá buscando fuentes por tmdbId).

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificar que el grid degrada limpio**

Con `web_sources.json` conteniendo solo `pelisplus`, correr los tests existentes del catálogo (si los hay) y compilar. El merge devuelve `tmdb.map { GridCard(it, false) }` cuando el web viene vacío → grid idéntico a hoy.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (toda la suite, incluidos los tests web nuevos).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): fuente web en el grid del catalogo (merge por tmdbId + badge WEB)"
```

---

### Task 11: Portado masivo + validación de los 45 canales (Tier 1 → Tier 2)

**Files (por cada canal `<id>`):**
- Modify: `app/src/main/assets/web_sources.json` (añadir la entrada del canal)
- Create: `app/src/test/resources/web/<id>_movies.html` (fixture real del listado)
- Modify: `app/src/test/java/com/arkiv/player/data/catalog/web/WebFixtureTest.kt` (añadir un test por canal)
- Modify: `app/src/test/resources/web/bundled_web_sources.json` (copia del asset, mantenida en sync)

**Este es un procedimiento REPETIBLE por canal.** Orden: los 24 de Tier 1, luego los 21 de Tier 2 (listas en el spec). Es volumen mecánico → **candidato a paralelizar con subagentes** (uno por canal); requiere opt-in del usuario para orquestación multi-agente.

**Procedimiento por canal (repetir para cada `<id>`):**

- [ ] **Step A: Leer el canal en Alfa.** Abrir `channels/<id>.py` y `channels/<id>.json` del clon de Alfa. Identificar:
  - `host` / `host_alt` → `baseUrl` / `hostAlt`.
  - En `list_all` (o `list_all_matches` para Tier 2): el selector de fila (`find_all("article", class_="item")` → `rowSelector: "article.item"`) y los campos (`elem.a['href']` → `pageUrl {selector:"a", attr:"href"}`, etc.). Si el canal usa regex crudo (`scrapertools.find_multiple_matches(data, patron)`), usar `rowRegex` + `rowRegexFields`.
  - La URL de búsqueda (`search`) y de listado (`browse`).
  - `language` del `.json` → `languageTokens`.

- [ ] **Step B: Guardar el fixture.** Bajar la página de listado real del sitio (o del `host_alt` si el principal cambió) y recortar ~3-5 tarjetas a `app/src/test/resources/web/<id>_movies.html`.

- [ ] **Step C: Añadir la entrada al JSON.** Agregar el objeto del canal a `web_sources.json` (con su `id` de Alfa). Re-copiar a `bundled_web_sources.json`.

- [ ] **Step D: Escribir el test de fixture del canal** en `WebFixtureTest.kt`:

```kotlin
    @Test fun `<id> parsea su fixture`() {
        val def = WebSourceRegistry.parseDefinitions(readResource("web/bundled_web_sources.json"))
            .first { it.id == "<id>" }
        val rows = WebHtmlParser.parse(def, readResource("web/<id>_movies.html"), "movie")
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.title.isNotBlank() && it.pageUrl.startsWith("http") })
    }
```

- [ ] **Step E: Correr el test.** `./gradlew :app:testDebugUnitTest --tests "*WebFixtureTest*"`.
  - PASS → portado correcto.
  - FAIL (0 filas / campos vacíos) → ajustar selectores/regex hasta que pase.
  - Si el sitio está caído / Cloudflare irresoluble / HTML irreconocible tras esfuerzo razonable → dejar la entrada con `"enabled": false` y un comentario del motivo (`"_nota": "roto: dominio caído 2026-07"`), y marcar el canal como "roto" en el reporte. NO bloquear el resto.

- [ ] **Step F: Commit del canal.**

```bash
git add app/src/main/assets/web_sources.json app/src/test/resources/web/<id>_movies.html \
        app/src/test/resources/web/bundled_web_sources.json \
        app/src/test/java/com/arkiv/player/data/catalog/web/WebFixtureTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): portar canal <id> desde Alfa (validado por fixture)"
```

- [ ] **Step FINAL: Reporte de portado.** Crear `docs/superpowers/plans/2026-07-22-web-porting-report.md` con una tabla `id | tier | estado (ok/roto) | motivo`. Correr la suite completa: `./gradlew :app:testDebugUnitTest` → todos verde (los canales rotos quedan `enabled:false`, sin test que falle). Commit del reporte.

---

## Self-Review

**Spec coverage:**
- Motor declarativo (`web/` package) → Tasks 1-5. ✔
- Enriquecimiento TMDB → Task 6. ✔
- `web_sources.json` + hot-update remoto → Tasks 4, 7, 8. ✔
- Integración hoja de fuentes (`PlaySource.Web`, badge, click stub) → Task 9. ✔
- Integración grid (merge por tmdbId, badge) → Task 10. ✔
- Idiomas/multi-audio (`WebResult.language`) → Tasks 1, 9. ✔
- Calidades (etiqueta cruda, `FieldRule` quality, badge) → Tasks 1, 2, 9. ✔
- Subtítulos/audio reservados (`subtitleUrl`, `audioLanguages`) → Task 1 (campos), resolución diferida (fuera de alcance). ✔
- rowRegex (compatibilidad Alfa) → Tasks 1, 2. ✔
- hostAlt fallback → Tasks 1, 3. ✔
- Cloudflare → reusado vía `HttpFetcher`/`WebViewCloudflareSolver` (Task 8 wiring). ✔
- Validación por canal (fixture + reporte) → Tasks 7, 11. ✔
- Siembra Tier 1 + Tier 2 (45 canales) → Task 11. ✔
- Degradación limpia → Tasks 3, 5, 10 (tests explícitos de fallo). ✔
- Fuera de alcance: reproducción/resolución embed → no hay tarea (correcto). ✔

**Type consistency:** `WebResult`, `WebSourceDefinition`, `WebParserRules`, `WebSourceBackend.browse/search`, `WebSourceEngine.browse/search`, `WebTmdbMatcher.enrich/enrichAll/normalize`, `TmdbHit`, `TmdbLookup`, `GridCard` — nombres y firmas usados consistentemente entre tareas. `PlaySource.Web` alineado en Task 9 (declaración, runSearch, playSource, SourceRow).

**Placeholder scan:** sin TBD/TODO; todo el código está completo. Los únicos "ajustar a lo real" son inherentes al scraping (selectores/dominios que dependen del HTML vivo del sitio) y están acompañados del mecanismo de validación (fixture test) que confirma la corrección — no son placeholders de lógica.
