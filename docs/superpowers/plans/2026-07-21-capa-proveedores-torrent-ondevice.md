# Capa de proveedores de torrents on-device — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir a Arkiv una capa de proveedores de torrents *declarativa* (definiciones JSON estilo Burst) que corre on-device como fallback cuando Jackett (blog) está caído o devuelve pocos resultados.

**Architecture:** Un `ProviderRegistry` carga definiciones JSON (asset bundled + remota de blog). Cada definición se ejecuta con un `DeclarativeHtmlBackend` (QueryBuilder de plantillas → `HttpFetcher` OkHttp con solver de Cloudflare aislado → `HtmlParser` con Jsoup → `RawTorrent`). `TorrentSearchApi` recibe estos providers como *fallback* y los dispara solo según salud de Jackett + suficiencia de resultados. Todo el pipeline aguas abajo (dedupe, `classify()`, ranking, `resolveSource()`) se reutiliza sin cambios.

**Tech Stack:** Kotlin, Coroutines, OkHttp (ya presente), **Jsoup (nuevo)**, `org.json` (a mano, estilo del proyecto), Android WebView (SDK), JUnit 4 (test), DI manual vía `AppGraph`.

## Global Constraints

- Paquete raíz: `com.arkiv.player`. Código nuevo en `com.arkiv.player.data.catalog.providers`.
- DI manual en `AppGraph.kt` (sin Hilt/Koin). Singletons perezosos (`by lazy`).
- Red: OkHttp + `org.json` a mano. **No** Retrofit/Moshi/Gson/Ktor.
- Async: Coroutines + `withContext(Dispatchers.IO)`. Tests suspend con `kotlinx.coroutines.runBlocking` (no hay `kotlinx-coroutines-test` en el proyecto).
- Tests: JUnit 4 en `app/src/test/java/...` (sin mockk; usar fakes/dobles a mano).
- Reutilizar sin modificar: `RawTorrent`, `TorrentResult`, `TorrentLang`, `TorrentSearchApi.classify()`, `resolveSource()`, dedupe/ranking. Modelos en `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt`.
- UA de navegador: constante existente `BROWSER_UA` en `TorrentSearchApi.kt:68`.
- Ningún fallo de un proveedor o del solver puede tumbar el fan-out: `try/catch` + timeout en cada borde; degradar a lista vacía.
- Commits con identidad `lordmacu` (ya configurada). **Sin** pie de coautoría.
- Regla de blog (estricta): nunca compilar pesado en blog (VM 2 CPU). Servir estático + endpoint trivial.
- Spec de referencia: `docs/superpowers/specs/2026-07-21-capa-proveedores-torrent-ondevice-design.md`.

## File Structure

- `app/build.gradle.kts` — **Modify**: añadir dependencia Jsoup.
- `app/src/main/assets/providers.json` — **Create**: definiciones bundled (día-1/offline).
- `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt` — **Create**: data classes + parseo JSON + validación.
- `.../providers/SearchModels.kt` — **Create**: `SearchContext`, `ContentType`, `ProviderBackend`.
- `.../providers/QueryBuilder.kt` — **Create**: plantillas + placeholders → queries.
- `.../providers/HtmlParser.kt` — **Create**: Jsoup + `FieldRule` → `RawTorrent`; parser de tamaño.
- `.../providers/CloudflareSolver.kt` — **Create**: interfaz + impl WebView + doble noop.
- `.../providers/HttpFetcher.kt` — **Create**: `PageFetcher` + OkHttp + detección Cloudflare.
- `.../providers/DeclarativeHtmlBackend.kt` — **Create**: orquesta las piezas por definición.
- `.../providers/ProviderRegistry.kt` — **Create**: carga bundled + remota, valida.
- `.../providers/JackettHealth.kt` — **Create**: `HealthGate` + `JackettStatus`.
- `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` — **Modify**: fallback + gating en `runSearch`, `SearchContext` en `searchEpisode/searchMovie`.
- `app/src/main/java/com/arkiv/player/data/SettingsStore.kt` — **Modify**: host/apikey de Jackett, URL de providers, flag Cloudflare.
- `app/src/main/java/com/arkiv/player/AppGraph.kt` — **Modify**: wiring.
- `docs/selfhost-blog-providers.md` — **Create**: runbook blog (endpoints + indexers).

---

### Task 1: Dependencia Jsoup

**Files:**
- Modify: `app/build.gradle.kts:126` (bloque de dependencias)

**Interfaces:**
- Consumes: nada.
- Produces: `org.jsoup:jsoup` disponible para todo el paquete `providers`.

- [ ] **Step 1: Añadir la dependencia**

En `app/build.gradle.kts`, en el bloque `dependencies { ... }` (junto a las demás `implementation(...)`), añadir:

```kotlin
    // Parsing HTML declarativo para proveedores de torrents on-device (capa estilo Burst).
    implementation("org.jsoup:jsoup:1.17.2")
```

- [ ] **Step 2: Sincronizar y verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Jsoup resuelto).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(torrent): añadir Jsoup para la capa de proveedores on-device"
```

---

### Task 2: `ProviderDefinition` + parseo y validación

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionTest.kt`

**Interfaces:**
- Consumes: `org.json.JSONObject`.
- Produces:
  - `data class FieldRule(selector: String, attr: String, regex: String? = null, resolve: String? = null)`
  - `data class ParserRules(rowSelector: String, name: FieldRule, magnet: FieldRule?, torrentUrl: FieldRule?, infohash: FieldRule?, seeds: FieldRule?, size: FieldRule?)`
  - `data class DetailRules(followFrom: String, magnet: FieldRule?, infohash: FieldRule?)`
  - `data class ProviderDefinition(id, name, enabled: Boolean, priority: Int, baseUrl: String, searchPath: String, charset: String, needsCloudflare: Boolean, keywords: Map<String,String>, languageTokens: List<String>, parser: ParserRules, detail: DetailRules?)`
  - `ProviderDefinition.Companion.fromJson(o: JSONObject): ProviderDefinition?` — devuelve `null` si es inválida (validación integrada).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderDefinitionTest {
    private fun json(s: String) = JSONObject(s)

    @Test fun `parsea una definicion minima valida`() {
        val def = ProviderDefinition.fromJson(json("""
            {"id":"bt4g","baseUrl":"https://bt4g.org","searchPath":"/search/{query}/1",
             "keywords":{"movie":"{title} {year}","tv":"{title} S{season:2}E{episode:2}"},
             "parser":{"rowSelector":"tr.result",
               "name":{"selector":"a.title","attr":"text"},
               "magnet":{"selector":"a[href^=magnet]","attr":"href"},
               "seeds":{"selector":"td.se","attr":"text","regex":"\\d+"},
               "size":{"selector":"td.sz","attr":"text"}}}
        """.trimIndent()))
        assertNotNull(def)
        assertEquals("bt4g", def!!.id)
        assertEquals("BT4G".lowercase(), def.name.lowercase()) // name default = id
        assertEquals(true, def.enabled)                        // default
        assertEquals("utf-8", def.charset)                     // default
        assertEquals("tr.result", def.parser.rowSelector)
        assertEquals("{title} {year}", def.keywords["movie"])
    }

    @Test fun `rechaza definicion sin rowSelector`() {
        val def = ProviderDefinition.fromJson(json("""
            {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"",
               "name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a","attr":"href"}}}
        """.trimIndent()))
        assertNull(def)
    }

    @Test fun `rechaza definicion sin ninguna fuente (magnet infohash torrentUrl)`() {
        val def = ProviderDefinition.fromJson(json("""
            {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"}}}
        """.trimIndent()))
        assertNull(def)
    }

    @Test fun `rechaza baseUrl invalida`() {
        val def = ProviderDefinition.fromJson(json("""
            {"id":"x","baseUrl":"not-a-url","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a","attr":"href"}}}
        """.trimIndent()))
        assertNull(def)
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProviderDefinitionTest*'`
Expected: FAIL (clase `ProviderDefinition` no existe).

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject

/** Regla de extracción de un campo desde un elemento HTML (selector CSS + atributo + regex + resolve). */
data class FieldRule(
    val selector: String,
    val attr: String,
    val regex: String? = null,
    val resolve: String? = null, // "absolute" completa URLs relativas con baseUrl
) {
    companion object {
        fun fromJson(o: JSONObject?): FieldRule? {
            if (o == null) return null
            val sel = o.optString("selector")
            if (sel.isBlank()) return null
            return FieldRule(
                selector = sel,
                attr = o.optString("attr", "text"),
                regex = o.optString("regex").ifBlank { null },
                resolve = o.optString("resolve").ifBlank { null },
            )
        }
    }
}

/** Reglas de parsing del listado de resultados. */
data class ParserRules(
    val rowSelector: String,
    val name: FieldRule,
    val magnet: FieldRule?,
    val torrentUrl: FieldRule?,
    val infohash: FieldRule?,
    val seeds: FieldRule?,
    val size: FieldRule?,
)

/** Reglas opcionales de la página de detalle (2º fetch) cuando el magnet no está en el listado. */
data class DetailRules(
    val followFrom: String, // "torrentUrl"
    val magnet: FieldRule?,
    val infohash: FieldRule?,
)

/** Definición declarativa de un proveedor de torrents (estilo Burst, con selectores CSS). */
data class ProviderDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val baseUrl: String,
    val searchPath: String,
    val charset: String,
    val needsCloudflare: Boolean,
    val keywords: Map<String, String>,
    val languageTokens: List<String>,
    val parser: ParserRules,
    val detail: DetailRules?,
) {
    companion object {
        /** Parsea y VALIDA. Devuelve null (y no lanza) si la definición es inválida. */
        fun fromJson(o: JSONObject): ProviderDefinition? = runCatching {
            val id = o.optString("id").ifBlank { return null }
            val baseUrl = o.optString("baseUrl").ifBlank { return null }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null
            val searchPath = o.optString("searchPath").ifBlank { return null }

            val keywordsJson = o.optJSONObject("keywords") ?: return null
            val keywords = keywordsJson.keys().asSequence()
                .associateWith { keywordsJson.getString(it) }
            if (keywords.isEmpty()) return null

            val pj = o.optJSONObject("parser") ?: return null
            val rowSelector = pj.optString("rowSelector").ifBlank { return null }
            val name = FieldRule.fromJson(pj.optJSONObject("name")) ?: return null
            val magnet = FieldRule.fromJson(pj.optJSONObject("magnet"))
            val torrentUrl = FieldRule.fromJson(pj.optJSONObject("torrentUrl"))
            val infohash = FieldRule.fromJson(pj.optJSONObject("infohash"))
            // Debe haber al menos UNA forma de obtener una fuente reproducible.
            if (magnet == null && torrentUrl == null && infohash == null) return null

            val detailJson = o.optJSONObject("detail")
            val detail = detailJson?.let {
                val from = it.optString("followFrom").ifBlank { null } ?: return@let null
                DetailRules(
                    followFrom = from,
                    magnet = FieldRule.fromJson(it.optJSONObject("magnet")),
                    infohash = FieldRule.fromJson(it.optJSONObject("infohash")),
                )
            }

            val tokensJson = o.optJSONArray("languageTokens")
            val tokens = if (tokensJson == null) emptyList()
                else (0 until tokensJson.length()).map { tokensJson.getString(it) }

            ProviderDefinition(
                id = id,
                name = o.optString("name").ifBlank { id },
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 50),
                baseUrl = baseUrl.trimEnd('/'),
                searchPath = searchPath,
                charset = o.optString("charset", "utf-8"),
                needsCloudflare = o.optBoolean("needsCloudflare", false),
                keywords = keywords,
                languageTokens = tokens,
                parser = ParserRules(rowSelector, name, magnet, torrentUrl, infohash,
                    FieldRule.fromJson(pj.optJSONObject("seeds")),
                    FieldRule.fromJson(pj.optJSONObject("size"))),
                detail = detail,
            )
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProviderDefinitionTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionTest.kt
git commit -m "feat(torrent): ProviderDefinition declarativa + parseo/validación JSON"
```

---

### Task 3: `SearchContext`, `ProviderBackend` y `QueryBuilder`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/SearchModels.kt`
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/QueryBuilder.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/QueryBuilderTest.kt`

**Interfaces:**
- Consumes: `ProviderDefinition` (Task 2), `RawTorrent` (`TorrentSearchApi.kt:58`).
- Produces:
  - `enum class ContentType { MOVIE, TV, ANIME }`
  - `data class SearchContext(titles: List<String>, type: ContentType, season: Int = 0, episode: Int = 0, year: String = "", episodeAbs: Int = 0)`
  - `interface ProviderBackend { val id: String; suspend fun search(ctx: SearchContext): List<RawTorrent> }`
  - `object QueryBuilder { fun build(def: ProviderDefinition, ctx: SearchContext, maxQueries: Int = 6): List<String> }`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryBuilderTest {
    private fun def(keywords: String, tokens: String = "[]") =
        ProviderDefinition.fromJson(JSONObject("""
            {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}",
             "keywords":$keywords,"languageTokens":$tokens,
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a","attr":"href"}}}
        """.trimIndent()))!!

    @Test fun `tv aplica zero-pad de temporada y episodio`() {
        val d = def("""{"tv":"{title} S{season:2}E{episode:2}"}""")
        val q = QueryBuilder.build(d, SearchContext(listOf("One Piece"), ContentType.TV, season = 1, episode = 5))
        assertTrue(q.contains("One Piece S01E05"))
    }

    @Test fun `movie sustituye year`() {
        val d = def("""{"movie":"{title} {year}"}""")
        val q = QueryBuilder.build(d, SearchContext(listOf("Coco"), ContentType.MOVIE, year = "2017"))
        assertTrue(q.contains("Coco 2017"))
    }

    @Test fun `anexa un token de idioma por variante`() {
        val d = def("""{"movie":"{title}"}""", tokens = """["latino"]""")
        val q = QueryBuilder.build(d, SearchContext(listOf("Coco"), ContentType.MOVIE))
        assertTrue(q.contains("Coco"))
        assertTrue(q.contains("Coco latino"))
    }

    @Test fun `respeta el tope maxQueries`() {
        val d = def("""{"tv":"{title} S{season:2}E{episode:2}"}""", tokens = """["latino","castellano","spanish"]""")
        val q = QueryBuilder.build(d, SearchContext(listOf("A","B","C"), ContentType.TV, 1, 1), maxQueries = 4)
        assertEquals(4, q.size)
    }

    @Test fun `anime usa la plantilla anime con episode_abs`() {
        val d = def("""{"tv":"{title} S{season:2}E{episode:2}","anime":"{title} {episode_abs:2}"}""")
        val q = QueryBuilder.build(d, SearchContext(listOf("Naruto"), ContentType.ANIME, episodeAbs = 7))
        assertTrue(q.contains("Naruto 07"))
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*QueryBuilderTest*'`
Expected: FAIL (símbolos no existen).

- [ ] **Step 3: Implementar `SearchModels.kt`**

```kotlin
package com.arkiv.player.data.catalog.providers

import com.arkiv.player.data.catalog.RawTorrent

/** Tipo de contenido buscado; selecciona la plantilla de query del proveedor. */
enum class ContentType { MOVIE, TV, ANIME }

/** Contexto estructurado de una búsqueda (lo que necesita cada proveedor para armar sus queries). */
data class SearchContext(
    val titles: List<String>,
    val type: ContentType,
    val season: Int = 0,
    val episode: Int = 0,
    val year: String = "",
    val episodeAbs: Int = 0,
)

/**
 * Proveedor on-device: a diferencia de [com.arkiv.player.data.catalog.TorrentBackend] (que recibe
 * un query ya armado), recibe el [SearchContext] para poder aplicar SUS PROPIAS plantillas de query.
 */
interface ProviderBackend {
    val id: String
    suspend fun search(ctx: SearchContext): List<RawTorrent>
}
```

- [ ] **Step 4: Implementar `QueryBuilder.kt`**

```kotlin
package com.arkiv.player.data.catalog.providers

/** Arma las queries de un proveedor sustituyendo placeholders en su plantilla por tipo de contenido. */
object QueryBuilder {

    fun build(def: ProviderDefinition, ctx: SearchContext, maxQueries: Int = 6): List<String> {
        val template = pickTemplate(def, ctx.type) ?: return emptyList()
        val titles = ctx.titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (titles.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        for (title in titles) {
            val base = fill(template, ctx, title)
            if (base.isNotBlank()) out.add(base)
            // Una variante por token de idioma, solo sobre el título principal para acotar el fan-out.
            if (title == titles.first()) {
                for (token in def.languageTokens) out.add("$base $token".trim())
            }
            if (out.size >= maxQueries) break
        }
        return out.take(maxQueries)
    }

    private fun pickTemplate(def: ProviderDefinition, type: ContentType): String? = when (type) {
        ContentType.MOVIE -> def.keywords["movie"]
        ContentType.TV -> def.keywords["tv"]
        ContentType.ANIME -> def.keywords["anime"] ?: def.keywords["tv"]
    }

    private fun fill(template: String, ctx: SearchContext, title: String): String {
        var s = template
        s = s.replace("{title}", title).replace("{title_original}", ctx.titles.lastOrNull() ?: title)
        s = s.replace("{year}", ctx.year.take(4))
        s = replacePadded(s, "season", ctx.season)
        s = replacePadded(s, "episode", ctx.episode)
        s = replacePadded(s, "episode_abs", ctx.episodeAbs)
        s = s.replace("{query}", title)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Sustituye {name} y {name:2} (zero-pad a N dígitos). */
    private fun replacePadded(input: String, name: String, value: Int): String {
        var s = input.replace("{$name}", value.toString())
        s = Regex("\\{$name:(\\d)\\}").replace(s) { m ->
            value.toString().padStart(m.groupValues[1].toInt(), '0')
        }
        return s
    }
}
```

- [ ] **Step 5: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*QueryBuilderTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/SearchModels.kt app/src/main/java/com/arkiv/player/data/catalog/providers/QueryBuilder.kt app/src/test/java/com/arkiv/player/data/catalog/providers/QueryBuilderTest.kt
git commit -m "feat(torrent): SearchContext/ProviderBackend + QueryBuilder de plantillas"
```

---

### Task 4: `HtmlParser` (Jsoup + `FieldRule` → `RawTorrent`)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/HtmlParser.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HtmlParserTest.kt`

**Interfaces:**
- Consumes: `ProviderDefinition`, `FieldRule`, `RawTorrent`, Jsoup.
- Produces:
  - `object HtmlParser { fun parseList(def: ProviderDefinition, html: String, pageUrl: String, maxRows: Int = 50): List<RawTorrent>; fun parseSize(text: String): Long }`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlParserTest {
    private val def = ProviderDefinition.fromJson(JSONObject("""
        {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}","keywords":{"movie":"{title}"},
         "parser":{"rowSelector":"table tr.r",
           "name":{"selector":"a.t","attr":"text"},
           "magnet":{"selector":"a.m","attr":"href"},
           "torrentUrl":{"selector":"a.d","attr":"href","resolve":"absolute"},
           "seeds":{"selector":"td.s","attr":"text","regex":"\\d+"},
           "size":{"selector":"td.z","attr":"text"}}}
    """.trimIndent()))!!

    @Test fun `extrae filas con name magnet seeds size`() {
        val html = """
          <table>
            <tr class="r"><a class="t">Coco 2017 1080p Latino</a>
                <a class="m" href="magnet:?xt=urn:btih:ABCDEF">m</a>
                <a class="d" href="/dl/1">d</a>
                <td class="s">Seeds: 42</td><td class="z">1.4 GB</td></tr>
            <tr class="r"><a class="t">Otro</a>
                <a class="m" href="magnet:?xt=urn:btih:99">m</a>
                <td class="s">3</td><td class="z">700 MB</td></tr>
          </table>
        """.trimIndent()
        val rows = HtmlParser.parseList(def, html, "https://x.org/s/coco")
        assertEquals(2, rows.size)
        assertEquals("Coco 2017 1080p Latino", rows[0].name)
        assertEquals("magnet:?xt=urn:btih:ABCDEF", rows[0].magnetUri)
        assertEquals("https://x.org/dl/1", rows[0].downloadUrl) // resolve absolute
        assertEquals(42, rows[0].seeders)
        assertEquals((1.4 * (1L shl 30)).toLong(), rows[0].sizeBytes)
    }

    @Test fun `descarta filas sin nombre o sin fuente`() {
        val html = """<table><tr class="r"><a class="t"></a></tr></table>"""
        assertEquals(0, HtmlParser.parseList(def, html, "https://x.org/s/x").size)
    }

    @Test fun `parseSize entiende GB MB y coma decimal`() {
        assertEquals((1.4 * (1L shl 30)).toLong(), HtmlParser.parseSize("1,4 GB"))
        assertEquals(700L * (1L shl 20), HtmlParser.parseSize("700 MB"))
        assertEquals(0L, HtmlParser.parseSize("desconocido"))
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*HtmlParserTest*'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.util.Log
import com.arkiv.player.data.catalog.RawTorrent
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parsea HTML de un listado de resultados a [RawTorrent] usando las reglas de la definición. */
object HtmlParser {

    fun parseList(def: ProviderDefinition, html: String, pageUrl: String, maxRows: Int = 50): List<RawTorrent> {
        val doc = runCatching { Jsoup.parse(html, def.baseUrl) }.getOrNull() ?: return emptyList()
        val rows = doc.select(def.parser.rowSelector)
        if (rows.isEmpty()) {
            Log.w("ArkivProv", "provider=${def.id} rowSelector matcheó 0 filas (¿cambió el sitio?)")
            return emptyList()
        }
        val p = def.parser
        return rows.take(maxRows).mapNotNull { row ->
            val name = extract(row, p.name, def.baseUrl)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val magnet = p.magnet?.let { extract(row, it, def.baseUrl) }?.takeIf { it.startsWith("magnet:") }
            val infohash = p.infohash?.let { extract(row, it, def.baseUrl) }?.ifBlank { null }
            val torrentUrl = p.torrentUrl?.let { extract(row, it, def.baseUrl) }?.ifBlank { null }
            if (magnet == null && infohash == null && torrentUrl == null) return@mapNotNull null
            val seeds = p.seeds?.let { extract(row, it, def.baseUrl) }?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
            val size = p.size?.let { extract(row, it, def.baseUrl) }?.let { parseSize(it) } ?: 0L
            RawTorrent(name = name, seeders = seeds, sizeBytes = size,
                infoHash = infohash, magnetUri = magnet, downloadUrl = torrentUrl)
        }
    }

    /** Aplica una [FieldRule] a un elemento: selecciona, saca texto/atributo, aplica regex y resolve. */
    private fun extract(row: Element, rule: FieldRule, baseUrl: String): String? = runCatching {
        val el = if (rule.selector.isBlank()) row else row.selectFirst(rule.selector) ?: return null
        var value = when (rule.attr) {
            "", "text" -> el.text()
            else -> if (rule.resolve == "absolute" && (rule.attr == "href" || rule.attr == "src"))
                el.absUrl(rule.attr).ifBlank { el.attr(rule.attr) } else el.attr(rule.attr)
        }
        rule.regex?.let { rx -> value = Regex(rx).find(value)?.groupValues?.let { it.getOrNull(1) ?: it[0] } ?: "" }
        if (rule.resolve == "absolute" && value.isNotBlank() && !value.startsWith("http") && !value.startsWith("magnet:")) {
            value = baseUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
        value.trim()
    }.getOrNull()

    /** "1,4 GB" / "700 MB" / "1.2 TB" → bytes. Devuelve 0 si no reconoce. */
    fun parseSize(text: String): Long {
        val m = Regex("""([\d]+[.,]?[\d]*)\s*(TB|GB|MB|KB|B)""", RegexOption.IGNORE_CASE).find(text) ?: return 0L
        val num = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return 0L
        val mult = when (m.groupValues[2].uppercase()) {
            "TB" -> 1L shl 40; "GB" -> 1L shl 30; "MB" -> 1L shl 20; "KB" -> 1L shl 10; else -> 1L
        }
        return (num * mult).toLong()
    }
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*HtmlParserTest*'`
Expected: PASS. (Nota: `android.util.Log` en tests unitarios de Robolectric no está; como el test no cubre la rama de 0 filas con assert sobre el log, y el proyecto ya usa `android.util.Log` en clases testeadas, si `Log` lanza en JVM, envolver la llamada `Log.w` es innecesario porque no se ejecuta en los casos testeados. Si el runner falla por `Log not mocked`, añadir `testOptions { unitTests.isReturnDefaultValues = true }` en `app/build.gradle.kts` dentro de `android { }`.)

- [ ] **Step 5: Si el runner se queja de `Log not mocked`, habilitar defaults**

En `app/build.gradle.kts`, dentro del bloque `android { }`, añadir (si no existe ya):

```kotlin
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests '*HtmlParserTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/HtmlParser.kt app/src/test/java/com/arkiv/player/data/catalog/providers/HtmlParserTest.kt app/build.gradle.kts
git commit -m "feat(torrent): HtmlParser Jsoup (FieldRule → RawTorrent) + parseSize"
```

---

### Task 5: `CloudflareSolver` (interfaz + WebView + doble)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/CloudflareSolver.kt`

**Interfaces:**
- Consumes: Android `WebView`, `CookieManager`, Coroutines.
- Produces:
  - `data class CfClearance(cookies: String, userAgent: String)`
  - `interface CloudflareSolver { suspend fun solve(url: String): CfClearance? }`
  - `object NoopCloudflareSolver : CloudflareSolver` (siempre null — para tests y para el flag apagado)
  - `class WebViewCloudflareSolver(context, enabled: () -> Boolean, timeoutMs: Long = 20_000) : CloudflareSolver`

Nota: el WebView requiere hilo principal y no es unit-testeable en JVM; esta task no lleva test automático (se valida manualmente en device). El resto del sistema se testea contra `NoopCloudflareSolver`.

- [ ] **Step 1: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Cookie de Cloudflare resuelta + el UA con el que se obtuvo (debe reusarse en las peticiones OkHttp). */
data class CfClearance(val cookies: String, val userAgent: String)

/** Abre-puertas de Cloudflare: resuelve el challenge JS y entrega la cookie `cf_clearance`. */
interface CloudflareSolver {
    suspend fun solve(url: String): CfClearance?
}

/** No hace nada (flag apagado / tests): el proveedor detrás de Cloudflare devolverá vacío. */
object NoopCloudflareSolver : CloudflareSolver {
    override suspend fun solve(url: String): CfClearance? = null
}

/**
 * Resuelve el challenge JS de Cloudflare con un WebView headless. AISLADO: un único WebView
 * reutilizable, serializado con un Mutex (un challenge a la vez), con timeout duro. Si falla o
 * está deshabilitado, devuelve null y el proveedor degrada a lista vacía.
 */
class WebViewCloudflareSolver(
    context: Context,
    private val enabled: () -> Boolean,
    private val timeoutMs: Long = 20_000,
) : CloudflareSolver {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(url: String): CfClearance? {
        if (!enabled()) return null
        return mutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) { solveOnMain(url) }
            }
        }
    }

    private suspend fun solveOnMain(url: String): CfClearance? = suspendCoroutine { cont ->
        val host = runCatching { java.net.URL(url).host }.getOrNull()
        val webView = WebView(appContext)
        val ua = webView.settings.userAgentString
        var resumed = false
        fun finish(result: CfClearance?) {
            if (resumed) return
            resumed = true
            runCatching { webView.stopLoading(); webView.destroy() }
            cont.resume(result)
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    Log.i("ArkivProv", "cloudflare resuelto host=$host")
                    finish(CfClearance(cookies = cookies, userAgent = ua))
                }
                // Si aún no está, el challenge sigue; el timeout de solve() cortará si no aparece.
            }
        }
        webView.loadUrl(url)
    }
}
```

- [ ] **Step 2: Verificar compilación**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/CloudflareSolver.kt
git commit -m "feat(torrent): CloudflareSolver aislado (WebView headless) + doble noop"
```

---

### Task 6: `HttpFetcher` (`PageFetcher` + OkHttp + detección Cloudflare)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HttpFetcherTest.kt`

**Interfaces:**
- Consumes: OkHttp, `CloudflareSolver`, `BROWSER_UA` (`TorrentSearchApi.kt:68`).
- Produces:
  - `interface PageFetcher { suspend fun fetch(url: String, charset: String): String? }`
  - `fun looksLikeCloudflareChallenge(code: Int, body: String?): Boolean` (top-level, pura, testeable)
  - `class HttpFetcher(client: OkHttpClient, solver: CloudflareSolver) : PageFetcher`

- [ ] **Step 1: Escribir el test que falla (solo la lógica pura de detección)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFetcherTest {
    @Test fun `detecta challenge por codigo 403 con marcador`() {
        assertTrue(looksLikeCloudflareChallenge(403, "<title>Just a moment...</title>"))
        assertTrue(looksLikeCloudflareChallenge(503, "cf-mitigated: challenge"))
        assertTrue(looksLikeCloudflareChallenge(200, "window.__cf_chl_opt = {}"))
    }

    @Test fun `no marca como challenge una pagina normal`() {
        assertFalse(looksLikeCloudflareChallenge(200, "<html><table>resultados</table></html>"))
        assertFalse(looksLikeCloudflareChallenge(403, "Acceso denegado por el tracker"))
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*HttpFetcherTest*'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.util.Log
import com.arkiv.player.data.catalog.BROWSER_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Descarga el HTML de una URL (con manejo de Cloudflare por debajo). */
interface PageFetcher {
    suspend fun fetch(url: String, charset: String): String?
}

/** Heurística: ¿el response es un challenge JS de Cloudflare? (pura, testeable sin red) */
fun looksLikeCloudflareChallenge(code: Int, body: String?): Boolean {
    val b = body?.lowercase() ?: return code == 403 || code == 503
    val markers = listOf("just a moment", "__cf_chl", "cf-mitigated", "cf-browser-verification", "checking your browser")
    return markers.any { b.contains(it) } && (code == 403 || code == 503 || b.contains("__cf_chl"))
}

/**
 * Fetcher OkHttp con UA de navegador. Si detecta un challenge de Cloudflare, delega en el
 * [CloudflareSolver] para obtener `cf_clearance` y reintenta una vez inyectando la cookie + UA.
 */
class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val solver: CloudflareSolver = NoopCloudflareSolver,
) : PageFetcher {

    override suspend fun fetch(url: String, charset: String): String? = withContext(Dispatchers.IO) {
        val first = get(url, charset, cookie = null, ua = BROWSER_UA)
        if (first != null && !looksLikeCloudflareChallenge(first.code, first.body)) return@withContext first.body
        // Challenge (o fallo): intentar resolver Cloudflare una vez.
        val clearance = runCatching { solver.solve(url) }.getOrNull() ?: return@withContext first?.body?.takeIf { first.code in 200..299 }
        val second = get(url, charset, cookie = clearance.cookies, ua = clearance.userAgent)
        second?.body?.takeIf { second.code in 200..299 }
    }

    private data class Resp(val code: Int, val body: String?)

    private fun get(url: String, charset: String, cookie: String?, ua: String): Resp? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()
        client.newCall(req).execute().use { resp ->
            val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
            val bytes = resp.body?.bytes()
            Resp(resp.code, bytes?.toString(cs))
        }
    }.onFailure { Log.w("ArkivProv", "fetch fail $url: $it") }.getOrNull()
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*HttpFetcherTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt app/src/test/java/com/arkiv/player/data/catalog/providers/HttpFetcherTest.kt
git commit -m "feat(torrent): HttpFetcher OkHttp + detección/retry de Cloudflare"
```

---

### Task 7: `DeclarativeHtmlBackend` (orquesta una definición)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackendTest.kt`

**Interfaces:**
- Consumes: `ProviderDefinition`, `PageFetcher`, `QueryBuilder`, `HtmlParser`, `SearchContext`, `ProviderBackend`, `RawTorrent`.
- Produces:
  - `class DeclarativeHtmlBackend(def: ProviderDefinition, fetcher: PageFetcher, maxQueries: Int = 6) : ProviderBackend`

- [ ] **Step 1: Escribir el test que falla (con un `PageFetcher` fake)**

```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeHtmlBackendTest {
    private val def = ProviderDefinition.fromJson(JSONObject("""
        {"id":"fake","baseUrl":"https://fake.org","searchPath":"/s/{query}",
         "keywords":{"movie":"{title} {year}"},
         "parser":{"rowSelector":"tr.r",
           "name":{"selector":"a.t","attr":"text"},
           "magnet":{"selector":"a.m","attr":"href"}}}
    """.trimIndent()))!!

    @Test fun `pide la URL correcta y parsea resultados`() = runBlocking {
        val requested = mutableListOf<String>()
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String): String? {
                requested += url
                return """<table><tr class="r"><a class="t">Coco 2017</a>
                          <a class="m" href="magnet:?xt=urn:btih:AA">m</a></tr></table>"""
            }
        }
        val backend = DeclarativeHtmlBackend(def, fetcher)
        val out = backend.search(SearchContext(listOf("Coco"), ContentType.MOVIE, year = "2017"))
        assertTrue(requested.any { it == "https://fake.org/s/Coco%202017" })
        assertEquals(1, out.size)
        assertEquals("Coco 2017", out[0].name)
        assertEquals("magnet:?xt=urn:btih:AA", out[0].magnetUri)
    }

    @Test fun `si el fetcher devuelve null no rompe`() = runBlocking {
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String): String? = null
        }
        val out = DeclarativeHtmlBackend(def, fetcher).search(SearchContext(listOf("Coco"), ContentType.MOVIE))
        assertEquals(0, out.size)
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeclarativeHtmlBackendTest*'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.util.Log
import com.arkiv.player.data.catalog.RawTorrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Ejecuta UNA definición declarativa: arma queries → fetch → parse → RawTorrent. */
class DeclarativeHtmlBackend(
    private val def: ProviderDefinition,
    private val fetcher: PageFetcher,
    private val maxQueries: Int = 6,
) : ProviderBackend {

    override val id: String = "prov:${def.id}"

    override suspend fun search(ctx: SearchContext): List<RawTorrent> = coroutineScope {
        if (!def.enabled) return@coroutineScope emptyList()
        val queries = QueryBuilder.build(def, ctx, maxQueries)
        if (queries.isEmpty()) return@coroutineScope emptyList()
        val jobs = queries.map { q ->
            async(Dispatchers.IO) { runCatching { searchOne(q) }.getOrDefault(emptyList()) }
        }
        jobs.awaitAll().flatten().distinctBy { it.magnetUri ?: it.infoHash ?: it.downloadUrl ?: it.name }
    }

    private suspend fun searchOne(query: String): List<RawTorrent> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = def.baseUrl + def.searchPath.replace("{query}", encoded)
        val html = fetcher.fetch(url, def.charset) ?: return emptyList()
        val rows = HtmlParser.parseList(def, html, url)
        Log.i("ArkivProv", "provider=${def.id} q='$query' filas=${rows.size}")
        // Resolver magnets faltantes vía página de detalle (si la definición lo declara).
        val detail = def.detail ?: return rows
        return withContext(Dispatchers.IO) {
            rows.map { r ->
                if (r.magnetUri != null || r.infoHash != null || r.downloadUrl == null) r
                else resolveDetail(r, detail) ?: r
            }
        }
    }

    private suspend fun resolveDetail(row: RawTorrent, detail: DetailRules): RawTorrent? {
        val page = row.downloadUrl ?: return null
        val html = fetcher.fetch(page, def.charset) ?: return null
        val doc = runCatching { org.jsoup.Jsoup.parse(html, def.baseUrl) }.getOrNull() ?: return null
        val magnet = detail.magnet?.selector?.let { doc.selectFirst(it)?.attr("href") }
            ?.takeIf { it.startsWith("magnet:") }
        return if (magnet != null) row.copy(magnetUri = magnet) else null
    }
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeclarativeHtmlBackendTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt app/src/test/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackendTest.kt
git commit -m "feat(torrent): DeclarativeHtmlBackend (query→fetch→parse por definición)"
```

---

### Task 8: `ProviderRegistry` (carga bundled + remota + validación)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderRegistry.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderRegistryTest.kt`

**Interfaces:**
- Consumes: `ProviderDefinition`, `org.json.JSONArray`.
- Produces:
  - `object ProviderRegistry { fun parseDefinitions(json: String): List<ProviderDefinition>; fun merge(bundled: List<ProviderDefinition>, remote: List<ProviderDefinition>): List<ProviderDefinition> }`

  (La carga real de asset/red vive en `AppGraph` (Task 12); aquí van las funciones puras testeables.)

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    private val one = """{"id":"a","baseUrl":"https://a.org","searchPath":"/s/{query}",
        "keywords":{"movie":"{title}"},
        "parser":{"rowSelector":"tr","name":{"selector":"x","attr":"text"},
          "magnet":{"selector":"a","attr":"href"}}}"""

    @Test fun `parsea array y descarta invalidas sin romper el resto`() {
        val json = """[$one, {"id":"bad"}]"""
        val defs = ProviderRegistry.parseDefinitions(json)
        assertEquals(1, defs.size)
        assertEquals("a", defs[0].id)
    }

    @Test fun `parsea json roto a lista vacia`() {
        assertTrue(ProviderRegistry.parseDefinitions("no-json").isEmpty())
    }

    @Test fun `merge la remota gana sobre la bundled por id`() {
        val bundled = ProviderRegistry.parseDefinitions("[$one]")
        val remoteJson = one.replace("https://a.org", "https://a.NEW.org")
        val remote = ProviderRegistry.parseDefinitions("[$remoteJson]")
        val merged = ProviderRegistry.merge(bundled, remote)
        assertEquals(1, merged.size)
        assertEquals("https://a.NEW.org", merged[0].baseUrl)
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProviderRegistryTest*'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.util.Log
import org.json.JSONArray

/** Carga y mergea definiciones de proveedores (asset bundled + remota de blog). */
object ProviderRegistry {

    /** Parsea un array JSON de definiciones; descarta las inválidas sin romper el resto. */
    fun parseDefinitions(json: String): List<ProviderDefinition> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { ProviderDefinition.fromJson(it) }
        }
    }.onFailure { Log.w("ArkivProv", "providers.json ilegible: $it") }.getOrDefault(emptyList())

    /** La versión remota (de blog) gana sobre la bundled cuando comparten `id`. */
    fun merge(bundled: List<ProviderDefinition>, remote: List<ProviderDefinition>): List<ProviderDefinition> {
        val byId = LinkedHashMap<String, ProviderDefinition>()
        bundled.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        return byId.values.sortedByDescending { it.priority }
    }
}
```

- [ ] **Step 4: Ejecutar el test (pasa)**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProviderRegistryTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderRegistry.kt app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderRegistryTest.kt
git commit -m "feat(torrent): ProviderRegistry (parse + merge bundled/remota)"
```

---

### Task 9: `JackettHealth` (`HealthGate`)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt`

**Interfaces:**
- Consumes: OkHttp, `org.json`.
- Produces:
  - `enum class JackettStatus { HEALTHY, DEGRADED, DOWN }`
  - `interface HealthGate { suspend fun status(): JackettStatus }`
  - `class JackettHealth(healthUrl: String, client: OkHttpClient, cacheTtlMs: Long = 60_000) : HealthGate`

Nota: la lógica de red no se unit-testea (sin `kotlinx-coroutines-test` ni server fake). El gating que la consume sí se testea en Task 10 con un `HealthGate` fake.

- [ ] **Step 1: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Estado de salud de Jackett (blog). */
enum class JackettStatus { HEALTHY, DEGRADED, DOWN }

/** Provee el estado de salud del backend principal para decidir el fallback. */
interface HealthGate {
    suspend fun status(): JackettStatus
}

/**
 * Consulta `GET /health` de blog (timeout corto) y cachea el resultado. Si no responde → DEGRADED
 * (se intenta Jackett igual, pero on-device queda listo si falla). Si responde jackettUp=false → DOWN.
 */
class JackettHealth(
    private val healthUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build(),
    private val cacheTtlMs: Long = 60_000,
) : HealthGate {

    @Volatile private var cached: JackettStatus? = null
    @Volatile private var atMs: Long = 0

    override suspend fun status(): JackettStatus {
        val now = System.currentTimeMillis()
        cached?.let { if (now - atMs < cacheTtlMs) return it }
        val fresh = withContext(Dispatchers.IO) { probe() }
        cached = fresh; atMs = now
        return fresh
    }

    private fun probe(): JackettStatus = runCatching {
        client.newCall(Request.Builder().url(healthUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use JackettStatus.DEGRADED
            val o = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                ?: return@use JackettStatus.DEGRADED
            if (o.optBoolean("jackettUp", true)) JackettStatus.HEALTHY else JackettStatus.DOWN
        }
    }.getOrDefault(JackettStatus.DEGRADED)
}
```

- [ ] **Step 2: Verificar compilación**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt
git commit -m "feat(torrent): JackettHealth (HealthGate con cache)"
```

---

### Task 10: Integrar fallback + gating en `TorrentSearchApi`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` (constructor `:221`, `searchEpisode` `:254`, `searchMovie` `:279`, `runSearch` `:360`)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/TorrentFallbackGatingTest.kt`

**Interfaces:**
- Consumes: `ProviderBackend`, `SearchContext`, `ContentType`, `HealthGate`, `JackettStatus` (Tasks 3, 9).
- Produces (nueva firma del constructor y de `runSearch`):
  - `class TorrentSearchApi(backends: List<TorrentBackend>, fallbackBackends: List<ProviderBackend> = emptyList(), healthGate: HealthGate? = null, minResults: Int = 3)`
  - `searchEpisode(...)` y `searchMovie(...)` mantienen su firma pública actual, pero construyen un `SearchContext` y lo pasan a `runSearch`.

- [ ] **Step 1: Escribir el test que falla (gating con fakes)**

```kotlin
package com.arkiv.player.data.catalog

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.HealthGate
import com.arkiv.player.data.catalog.providers.JackettStatus
import com.arkiv.player.data.catalog.providers.ProviderBackend
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFallbackGatingTest {
    private fun rt(name: String) = RawTorrent(name = name, seeders = 10, sizeBytes = 1L shl 30,
        magnetUri = "magnet:?xt=urn:btih:${name.hashCode()}")

    private class FakeBackend(override val id: String, val out: List<RawTorrent>) : TorrentBackend {
        var called = false
        override suspend fun search(query: String): List<RawTorrent> { called = true; return out }
    }
    private class FakeProvider(override val id: String, val out: List<RawTorrent>) : ProviderBackend {
        var called = false
        override suspend fun search(ctx: SearchContext): List<RawTorrent> { called = true; return out }
    }
    private fun gate(s: JackettStatus) = object : HealthGate { override suspend fun status() = s }

    @Test fun `jackett sano y suficiente NO dispara fallback`() = runBlocking {
        val primary = FakeBackend("jackett", List(5) { rt("Coco 2017 v$it") })
        val fb = FakeProvider("prov", listOf(rt("extra")))
        val api = TorrentSearchApi(listOf(primary), listOf(fb), gate(JackettStatus.HEALTHY), minResults = 3)
        val res = api.searchMovie(listOf("Coco"), "2017", emptySet())
        assertTrue(res.isNotEmpty())
        assertTrue(primary.called)
        assertEquals(false, fb.called) // suficiente → no fallback
    }

    @Test fun `jackett DOWN salta primary y usa fallback`() = runBlocking {
        val primary = FakeBackend("jackett", List(5) { rt("Coco 2017 v$it") })
        val fb = FakeProvider("prov", listOf(rt("Coco 2017 fallback")))
        val api = TorrentSearchApi(listOf(primary), listOf(fb), gate(JackettStatus.DOWN), minResults = 3)
        val res = api.searchMovie(listOf("Coco"), "2017", emptySet())
        assertEquals(false, primary.called)
        assertTrue(fb.called)
        assertTrue(res.any { it.name.contains("fallback") })
    }

    @Test fun `pocos resultados de jackett dispara fallback y fusiona`() = runBlocking {
        val primary = FakeBackend("jackett", listOf(rt("Coco 2017 uno")))
        val fb = FakeProvider("prov", listOf(rt("Coco 2017 dos")))
        val api = TorrentSearchApi(listOf(primary), listOf(fb), gate(JackettStatus.HEALTHY), minResults = 3)
        val res = api.searchMovie(listOf("Coco"), "2017", emptySet())
        assertTrue(primary.called && fb.called)
        assertTrue(res.size >= 2)
    }
}
```

- [ ] **Step 2: Ejecutar el test (falla)**

Run: `./gradlew :app:testDebugUnitTest --tests '*TorrentFallbackGatingTest*'`
Expected: FAIL (constructor no acepta esos parámetros).

- [ ] **Step 3: Modificar imports y constructor**

En `TorrentSearchApi.kt`, añadir imports arriba (junto a los existentes, líneas 3-14):

```kotlin
import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.HealthGate
import com.arkiv.player.data.catalog.providers.JackettStatus
import com.arkiv.player.data.catalog.providers.ProviderBackend
import com.arkiv.player.data.catalog.providers.SearchContext
```

Reemplazar la declaración de clase (`:221`):

```kotlin
class TorrentSearchApi(private val backends: List<TorrentBackend>) {
```

por:

```kotlin
class TorrentSearchApi(
    private val backends: List<TorrentBackend>,
    private val fallbackBackends: List<ProviderBackend> = emptyList(),
    private val healthGate: HealthGate? = null,
    private val minResults: Int = 3,
) {
```

- [ ] **Step 4: Pasar `SearchContext` desde `searchEpisode` y `searchMovie`**

En `searchEpisode` (`:275`), reemplazar la línea final:

```kotlin
        return runSearch(withVariants(queries), langs, episodeRelevance(season, episode), maxSizeBytes)
```

por:

```kotlin
        val ctx = SearchContext(titles = ts, type = ContentType.TV, season = season, episode = episode)
        return runSearch(withVariants(queries), langs, episodeRelevance(season, episode), maxSizeBytes, ctx)
```

En `searchMovie` (`:297`), reemplazar la línea final:

```kotlin
        return runSearch(withVariants(queries), langs, movieRelevance(ts, y), maxSizeBytes)
```

por:

```kotlin
        val ctx = SearchContext(titles = ts, type = ContentType.MOVIE, year = y)
        return runSearch(withVariants(queries), langs, movieRelevance(ts, y), maxSizeBytes, ctx)
```

- [ ] **Step 5: Reescribir `runSearch` para gating**

Reemplazar la firma y el bloque de obtención de `raw` en `runSearch` (`:360-378`). La firma nueva:

```kotlin
    private suspend fun runSearch(
        queries: List<String>,
        langs: Set<TorrentLang>,
        relevance: (String) -> Boolean,
        maxSizeBytes: Long = 0,
        ctx: SearchContext? = null,
    ): List<TorrentResult> = coroutineScope {
```

Y reemplazar el bloque `val raw = cached?.raw ?: run { ... }` (`:370-378`) por:

```kotlin
        val raw = cached?.raw ?: run {
            val status = if (healthGate != null && ctx != null)
                runCatching { healthGate.status() }.getOrDefault(JackettStatus.DEGRADED)
            else JackettStatus.HEALTHY

            // Primary (Jackett/apibay/knaben): se salta solo si Jackett está caído.
            val primary = if (status == JackettStatus.DOWN) emptyList() else {
                val jobs = distinct.flatMap { q ->
                    backends.map { b -> async { runCatching { b.search(q) }.getOrDefault(emptyList()) } }
                }
                jobs.awaitAll().flatten()
            }

            // Fallback on-device: si Jackett está DOWN, o si dio pocos resultados útiles.
            val useful = primary.count { it.seeders > 0 && relevance(it.name) }
            val needFallback = ctx != null && fallbackBackends.isNotEmpty() &&
                (status == JackettStatus.DOWN || useful < minResults)
            val fallback = if (needFallback) {
                val jobs = fallbackBackends.map { p -> async { runCatching { p.search(ctx!!) }.getOrDefault(emptyList()) } }
                jobs.awaitAll().flatten()
            } else emptyList()

            (primary + fallback).also { if (it.isNotEmpty()) rawCache[cacheKey] = CacheEntry(it, now) }
        }
```

- [ ] **Step 6: Ejecutar el test (pasa) y toda la suite**

Run: `./gradlew :app:testDebugUnitTest --tests '*TorrentFallbackGatingTest*'`
Expected: PASS.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no romper tests existentes).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt app/src/test/java/com/arkiv/player/data/catalog/TorrentFallbackGatingTest.kt
git commit -m "feat(torrent): fallback on-device con gating por salud + suficiencia"
```

---

### Task 11: `SettingsStore` — config de Jackett, providers y flag Cloudflare

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`

**Interfaces:**
- Produces (nuevas propiedades y setters en `SettingsStore`):
  - `val jackettBaseUrl: StateFlow<String>` (default `"https://jackett.comparadorinternet.co"`)
  - `val jackettApiKey: StateFlow<String>` (default la key actual)
  - `val providersUrl: StateFlow<String>` (default `"https://jackett.comparadorinternet.co/providers.json"`)
  - `val jackettHealthUrl: StateFlow<String>` (default `"https://jackett.comparadorinternet.co/health"`)
  - `val cloudflareSolverEnabled: StateFlow<Boolean>` (default `true`)
  - setters `setJackettBaseUrl`, `setJackettApiKey`, `setProvidersUrl`, `setJackettHealthUrl`, `setCloudflareSolverEnabled`.

- [ ] **Step 1: Añadir las propiedades**

En `SettingsStore.kt`, tras el bloque de `maxTorrentSizeGb` (antes de los setters), añadir:

```kotlin
    private val _jackettBaseUrl = MutableStateFlow(prefs.getString(KEY_JACKETT_URL, DEFAULT_JACKETT_URL)!!)
    val jackettBaseUrl: StateFlow<String> = _jackettBaseUrl

    private val _jackettApiKey = MutableStateFlow(prefs.getString(KEY_JACKETT_KEY, DEFAULT_JACKETT_KEY)!!)
    val jackettApiKey: StateFlow<String> = _jackettApiKey

    private val _providersUrl = MutableStateFlow(prefs.getString(KEY_PROVIDERS_URL, DEFAULT_PROVIDERS_URL)!!)
    val providersUrl: StateFlow<String> = _providersUrl

    private val _jackettHealthUrl = MutableStateFlow(prefs.getString(KEY_HEALTH_URL, DEFAULT_HEALTH_URL)!!)
    val jackettHealthUrl: StateFlow<String> = _jackettHealthUrl

    private val _cloudflareSolverEnabled = MutableStateFlow(prefs.getBoolean(KEY_CF_ENABLED, true))
    val cloudflareSolverEnabled: StateFlow<Boolean> = _cloudflareSolverEnabled
```

- [ ] **Step 2: Añadir los setters**

Tras `setMaxTorrentSizeGb`, añadir:

```kotlin
    fun setJackettBaseUrl(v: String) { prefs.edit().putString(KEY_JACKETT_URL, v).apply(); _jackettBaseUrl.value = v }
    fun setJackettApiKey(v: String) { prefs.edit().putString(KEY_JACKETT_KEY, v).apply(); _jackettApiKey.value = v }
    fun setProvidersUrl(v: String) { prefs.edit().putString(KEY_PROVIDERS_URL, v).apply(); _providersUrl.value = v }
    fun setJackettHealthUrl(v: String) { prefs.edit().putString(KEY_HEALTH_URL, v).apply(); _jackettHealthUrl.value = v }
    fun setCloudflareSolverEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_CF_ENABLED, v).apply(); _cloudflareSolverEnabled.value = v }
```

- [ ] **Step 3: Añadir las claves y defaults al `companion object`**

Dentro del `companion object`, añadir:

```kotlin
        private const val KEY_JACKETT_URL = "jackett_base_url"
        private const val KEY_JACKETT_KEY = "jackett_api_key"
        private const val KEY_PROVIDERS_URL = "providers_url"
        private const val KEY_HEALTH_URL = "jackett_health_url"
        private const val KEY_CF_ENABLED = "cloudflare_solver_enabled"
        const val DEFAULT_JACKETT_URL = "https://jackett.comparadorinternet.co"
        const val DEFAULT_JACKETT_KEY = "r4xj63x2ji3d1lejeqsc0cgo710sjukw"
        const val DEFAULT_PROVIDERS_URL = "https://jackett.comparadorinternet.co/providers.json"
        const val DEFAULT_HEALTH_URL = "https://jackett.comparadorinternet.co/health"
```

- [ ] **Step 4: Verificar compilación**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SettingsStore.kt
git commit -m "feat(settings): config de Jackett/providers/health + flag Cloudflare"
```

---

### Task 12: Wiring en `AppGraph` + carga de definiciones (asset + remota)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (`:53-64` bloque `torrentSearchApi`)
- Create: `app/src/main/assets/providers.json` (placeholder; se llena en Task 13)

**Interfaces:**
- Consumes: `ProviderRegistry`, `DeclarativeHtmlBackend`, `HttpFetcher`, `WebViewCloudflareSolver`, `JackettHealth`, `SettingsStore` (Tasks 5-11).
- Produces: `torrentSearchApi` construido con fallback on-device + health gate; helper interno `loadProviderDefinitions()`.

- [ ] **Step 1: Crear el asset placeholder**

Crear `app/src/main/assets/providers.json` con contenido:

```json
[]
```

- [ ] **Step 2: Añadir imports en `AppGraph.kt`**

Junto a los imports existentes (líneas 4-23):

```kotlin
import com.arkiv.player.data.catalog.providers.DeclarativeHtmlBackend
import com.arkiv.player.data.catalog.providers.HttpFetcher
import com.arkiv.player.data.catalog.providers.JackettHealth
import com.arkiv.player.data.catalog.providers.ProviderBackend
import com.arkiv.player.data.catalog.providers.ProviderRegistry
import com.arkiv.player.data.catalog.providers.WebViewCloudflareSolver
```

- [ ] **Step 3: Reemplazar el bloque `torrentSearchApi`**

Reemplazar `torrentSearchApi` (`:53-64`) por:

```kotlin
    private val cloudflareSolver by lazy {
        WebViewCloudflareSolver(appContext, enabled = { settings.cloudflareSolverEnabled.value })
    }
    private val providerFetcher by lazy { HttpFetcher(solver = cloudflareSolver) }

    /** Carga definiciones bundled (asset) + remotas (blog); la remota gana. Nunca lanza. */
    private fun loadProviderBackends(): List<ProviderBackend> {
        val bundled = runCatching {
            appContext.assets.open("providers.json").bufferedReader().use { it.readText() }
        }.getOrDefault("[]")
        val remote = runCatching {
            val url = settings.providersUrl.value
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(okhttp3.Request.Builder().url(url).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: "[]"
        val defs = ProviderRegistry.merge(
            ProviderRegistry.parseDefinitions(bundled),
            ProviderRegistry.parseDefinitions(remote),
        )
        return defs.map { DeclarativeHtmlBackend(it, providerFetcher) }
    }

    private val jackettHealth by lazy { JackettHealth(healthUrl = settings.jackettHealthUrl.value) }

    val torrentSearchApi: TorrentSearchApi by lazy {
        TorrentSearchApi(
            backends = listOf(
                ApibayBackend(),
                KnabenBackend(),
                JackettBackend(
                    baseUrl = settings.jackettBaseUrl.value,
                    apiKey = settings.jackettApiKey.value,
                ),
            ),
            fallbackBackends = loadProviderBackends(),
            healthGate = jackettHealth,
            minResults = 3,
        )
    }
```

- [ ] **Step 4: Verificar compilación y toda la suite**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/assets/providers.json
git commit -m "feat(torrent): wiring de la capa de proveedores on-device en AppGraph"
```

---

### Task 13: `providers.json` bundled con proveedores públicos + tests de fixtures

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProvidersFixtureTest.kt`
- Test resources: `app/src/test/resources/providers/bt4g_sample.html`, `.../torrentgalaxy_sample.html`

**Interfaces:**
- Consumes: `ProviderRegistry`, `HtmlParser`.
- Produces: definiciones reales bundled; tests de parseo contra HTML real snapshot.

Nota: los selectores CSS reales de cada sitio deben verificarse contra HTML real. El implementador debe: (1) `curl` una búsqueda de cada sitio, guardar el HTML en `test/resources/providers/`, (2) ajustar los selectores de la definición hasta que el test pase. Abajo va una definición inicial para **BT4G** (público, JSON/HTML simple) como semilla; el implementador confirma selectores contra el snapshot.

- [ ] **Step 1: Escribir el test que falla (contra fixture)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersFixtureTest {
    private fun load(res: String) =
        this::class.java.classLoader!!.getResourceAsStream(res)!!.bufferedReader().use { it.readText() }
    private fun defById(id: String): ProviderDefinition {
        val json = this::class.java.classLoader!!.getResourceAsStream("providers/bundled_providers.json")!!
            .bufferedReader().use { it.readText() }
        return ProviderRegistry.parseDefinitions(json).first { it.id == id }
    }

    @Test fun `bt4g parsea el fixture y saca al menos un resultado con fuente`() {
        val def = defById("bt4g")
        val html = load("providers/bt4g_sample.html")
        val rows = HtmlParser.parseList(def, html, def.baseUrl)
        assertTrue("esperaba >=1 fila parseada", rows.isNotEmpty())
        assertTrue(rows.all { it.magnetUri != null || it.infoHash != null || it.downloadUrl != null })
    }
}
```

- [ ] **Step 2: Copiar el `providers.json` como recurso de test**

Para que el test lea las MISMAS definiciones que la app, tras editar `app/src/main/assets/providers.json` (Step 4), copiarlo a `app/src/test/resources/providers/bundled_providers.json` (o crear un symlink en el build). Comando:

```bash
mkdir -p app/src/test/resources/providers
cp app/src/main/assets/providers.json app/src/test/resources/providers/bundled_providers.json
```

- [ ] **Step 3: Obtener el HTML real de BT4G y guardarlo como fixture**

```bash
mkdir -p app/src/test/resources/providers
curl -sL -A "Mozilla/5.0 (Linux; Android 14)" "https://bt4g.org/search/Coco%202017/1" -o app/src/test/resources/providers/bt4g_sample.html
```

Inspeccionar el HTML (`app/src/test/resources/providers/bt4g_sample.html`) e identificar: selector de cada fila de resultado, del título, del enlace magnet/detalle, seeds y size.

- [ ] **Step 4: Escribir la definición en `app/src/main/assets/providers.json`**

Semilla (AJUSTAR los selectores a lo observado en el fixture del Step 3):

```json
[
  {
    "id": "bt4g",
    "name": "BT4G",
    "enabled": true,
    "priority": 60,
    "baseUrl": "https://bt4g.org",
    "searchPath": "/search/{query}/1",
    "needsCloudflare": true,
    "keywords": {
      "movie": "{title} {year}",
      "tv": "{title} S{season:2}E{episode:2}",
      "season": "{title} S{season:2}",
      "anime": "{title} {episode_abs:2}"
    },
    "languageTokens": ["latino", "castellano", "spanish"],
    "parser": {
      "rowSelector": "div.list-group-item",
      "name": { "selector": "a[href^='/magnet/']", "attr": "text" },
      "torrentUrl": { "selector": "a[href^='/magnet/']", "attr": "href", "resolve": "absolute" },
      "seeds": { "selector": "span.seeders", "attr": "text", "regex": "\\d+" },
      "size": { "selector": "span.size", "attr": "text" }
    },
    "detail": {
      "followFrom": "torrentUrl",
      "magnet": { "selector": "a[href^=magnet]", "attr": "href" }
    }
  }
]
```

- [ ] **Step 5: Ejecutar el test y ajustar selectores hasta PASS**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProvidersFixtureTest*'`
Expected: PASS. Si falla por 0 filas, ajustar `rowSelector`/campos contra el HTML real del fixture y re-copiar el asset a test/resources (Step 2). Iterar hasta PASS.

- [ ] **Step 6: (Opcional) Añadir un 2º proveedor público**

Repetir Steps 3-5 para un segundo sitio público sin Cloudflare (ej. un mirror con HTML estable), añadiendo su definición al array y su fixture. Documentar en el commit cuál se añadió.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/providers.json app/src/test/resources/providers/ app/src/test/java/com/arkiv/player/data/catalog/providers/ProvidersFixtureTest.kt
git commit -m "feat(torrent): definiciones bundled (BT4G) + tests contra fixtures HTML"
```

---

### Task 14: Blog — servir `/providers.json` y `/health`

**Files:**
- Create: `docs/selfhost-blog-providers.md` (runbook)

**Interfaces:**
- Produces: endpoints `GET /providers.json` y `GET /health` accesibles bajo `https://jackett.comparadorinternet.co` (o el host configurado en `SettingsStore`).

Nota: esta task NO es código de la app; es configuración de blog. Sin TDD. Regla estricta: no compilar pesado en blog.

- [ ] **Step 1: Escribir el runbook**

Crear `docs/selfhost-blog-providers.md` con:

```markdown
# Blog: endpoints para la capa de proveedores on-device

Host: la instancia de Jackett en `jackett.comparadorinternet.co` (reverse proxy Caddy/nginx ya presente).

## 1. `GET /providers.json` (estático)
- Curar un `providers.json` (mismo formato que `app/src/main/assets/providers.json`, pero con la
  lista extendida de proveedores latino/anime).
- Colocarlo en la raíz servida por el reverse proxy y exponerlo en `/providers.json`.
- Ejemplo Caddy (bloque del site):

      handle /providers.json {
          root * /srv/arkiv
          file_server
      }

  (con el archivo en `/srv/arkiv/providers.json`).

## 2. `GET /health` (trivial)
- Debe responder `{"status":"ok","jackettUp":true,"flaresolverrUp":true}` cuando Jackett vive.
- Opción simple (Caddy): responder estático 200 con JSON fijo:

      handle /health {
          respond `{"status":"ok","jackettUp":true}` 200 {
              header Content-Type application/json
          }
      }

- Opción con verificación real (si se quiere que refleje el estado de Jackett): un script mínimo
  (ya compilado en el Mac si es binario; NUNCA compilar en blog) que haga un ping al puerto local
  de Jackett y devuelva `jackettUp` en consecuencia, expuesto como `systemd --user` + proxy.
  Se parte de la opción estática por simplicidad.

## 3. Verificación
    curl -s https://jackett.comparadorinternet.co/health
    curl -s https://jackett.comparadorinternet.co/providers.json | head
```

- [ ] **Step 2: Aplicar la config en blog (manual, según runbook) y verificar**

Run (desde el Mac): `curl -s https://jackett.comparadorinternet.co/health`
Expected: `{"status":"ok","jackettUp":true}` (o equivalente 200 JSON).

Run: `curl -s https://jackett.comparadorinternet.co/providers.json | head`
Expected: array JSON de definiciones.

- [ ] **Step 3: Commit del runbook**

```bash
git add docs/selfhost-blog-providers.md
git commit -m "docs(blog): runbook de /providers.json y /health para la app"
```

---

### Task 15: Blog — indexers latino/anime en Jackett

**Files:**
- Modify: `docs/selfhost-blog-providers.md` (sección de indexers)

**Interfaces:**
- Produces: lista curada de indexers a añadir en la UI de Jackett; sin cambios en la app (ya consulta `indexers/all/results`).

- [ ] **Step 1: Derivar la lista de indexers latino/anime desde Burst**

Del `providers.json` de Burst (referencia: repo `elgatito/script.elementum.burst`), filtrar los que declaren `languages` con `es`/`lat` o sean de anime, y cruzarlos con los indexers disponibles en Jackett. Añadir a `docs/selfhost-blog-providers.md`:

```markdown
## 4. Indexers latino/anime a añadir en Jackett
(Configurar en la UI de Jackett → Add Indexer. Los que requieran Cloudflare usan el FlareSolverr ya presente.)

- Anime: Nyaa, AnimeTosho, (los públicos que soporte Jackett).
- Latino/castellano: los trackers ES que Jackett ya soporta (MejorTorrent, DonTorrent, Wolfmax, EliteTorrent, DivxTotal) — verificar cuáles faltan vs. los ya configurados.

Para cada uno: Add Indexer → configurar → Test. Marcar aquí los que quedaron OK.
```

- [ ] **Step 2: Configurar en Jackett (manual) y verificar contra la app**

Tras añadir los indexers, verificar que la búsqueda agregada los incluye:

Run: `curl -s "https://jackett.comparadorinternet.co/api/v2.0/indexers/all/results?apikey=<KEY>&Query=one+piece" | head -c 400`
Expected: JSON con `Results` que incluya releases de los nuevos indexers.

- [ ] **Step 3: Commit**

```bash
git add docs/selfhost-blog-providers.md
git commit -m "docs(blog): indexers latino/anime a añadir en Jackett"
```

---

## Self-Review (completado por el autor del plan)

- **Cobertura del spec:** §2 arquitectura → Tasks 2-12; §3 schema → Task 2; §4 query/parsing → Tasks 3,4; §5 Cloudflare → Tasks 5,6; §6 gating+health+registro → Tasks 8,9,10,12; §7 blog → Tasks 14,15; §8 errores → `try/catch`/timeouts en Tasks 4,6,7,9,10; §9 testing → Tasks 2,3,4,6,7,8,10,13; §10 Jsoup → Task 1; §11 fuera de alcance → respetado (sin persistencia de cookies ni pantalla de estado).
- **Consistencia de tipos:** `ProviderDefinition`/`FieldRule`/`ParserRules`/`DetailRules` (Task 2) usados igual en Tasks 3,4,7,8; `SearchContext`/`ContentType`/`ProviderBackend` (Task 3) usados en 7,10,12; `PageFetcher` (Task 6) consumido en 7,12; `HealthGate`/`JackettStatus` (Task 9) en 10,12; nueva firma de `TorrentSearchApi` (Task 10) usada en 12.
- **Sin placeholders de código:** todo el código Kotlin está completo. Las dos zonas que requieren trabajo de campo (selectores CSS reales de sitios en Task 13; config de Caddy/Jackett en Tasks 14,15) están acotadas con comandos concretos y criterio de PASS, no como "TODO".
