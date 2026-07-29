# Backend de proveedores por API JSON (hacktorrent) — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir un backend declarativo para trackers cuya fuente es una API JSON de 2 pasos (search → detail), y con él habilitar hacktorrent (solo películas). Genérico: otro tracker JSON similar se agrega solo con una def.

**Architecture:** `ProviderDefinition` gana un bloque opcional `jsonApi`. `JsonProviderBackend` ejecuta ese path (fetch JSON de búsqueda → filtra películas → fetch JSON de detalle por película → cada `download_link` magnet → `RawTorrent`). `RegistryProviderBackend` enruta a `JsonProviderBackend` cuando `def.jsonApi != null`, si no a `DeclarativeHtmlBackend`. Reusa el pipeline de `TorrentSearchApi` (relevancia/idioma/ranking/caché).

**Tech Stack:** Kotlin, Coroutines, OkHttp (vía `PageFetcher`), org.json, JUnit4.

## Global Constraints

- Ningún request del path de torrents va a `blog`/`jackett.comparadorinternet.co`.
- La capa web NO se toca. Identidad git `lordmacu`, commits sin coautoría de Claude, nunca `git add -A`.
- **Sesión concurrente activa** en el branch: commitear SOLO los archivos nombrados en cada tarea; verificar el diff antes de commitear.
- Tests: `./gradlew testDebugUnitTest`. Una clase: `--tests "FQN"`. TDD. Un commit por tarea.
- Los proveedores existentes (HTML / renderJs) deben comportarse **idénticos** (`jsonApi` es aditivo/opcional).
- Solo **películas** en v1 (`type == "pelicula"`); `serie`/`anime` se ignoran.
- `RawTorrent` del backend JSON lleva `seeders = 1` (no 0) para sobrevivir el filtro `seeders > 0` de `finalize`.
- API real (hacktorrent, host `https://hacktorrent.to`): search `/wp-json/wpreact/v1/search?query={q}&posts_per_page=20&page=1` → `{"results":[{slug,title,type,year,language}]}`; detalle `/wp-json/wpreact/v1/movie/{slug}/` → `{"downloads":[{download_link(magnet),quality,size,language}]}`.

---

## Mapa de archivos

**Creados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonApiConfig.kt` — `data class JsonApiConfig` + `fromJson`.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonProviderBackend.kt` — `class JsonProviderBackend`.
- `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionJsonApiTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/JsonProviderBackendTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/HacktorrentDefinitionTest.kt`

**Modificados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt` — `parser` nullable, campo `jsonApi`, branch en `fromJson`.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/HtmlParser.kt` — guard `def.parser ?: return emptyList()`.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt` — enrutar por `jsonApi`.
- `app/src/main/assets/providers.json` — def de hacktorrent.

---

## Task 1: JsonApiConfig + ProviderDefinition (parser nullable + jsonApi + branch fromJson)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonApiConfig.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/HtmlParser.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionJsonApiTest.kt`

**Interfaces:**
- Produces:
  - `data class JsonApiConfig(searchPath, resultsPath, itemSlug, itemTitle, itemType, itemYear: String?, itemLanguage: String?, movieType, detailPath, downloadsPath, dlLink, dlQuality: String?, dlSize: String?, dlLanguage: String?)` con `fromJson(o: JSONObject): JsonApiConfig?` (null si faltan los obligatorios).
  - `ProviderDefinition.parser: ParserRules?` (ahora nullable), `ProviderDefinition.jsonApi: JsonApiConfig? = null`.
  - `fromJson`: si `jsonApi != null`, construye la def SIN exigir `keywords`/`parser`/`searchPath` (parser=null, keywords=emptyMap, searchPath=""); si no, el path HTML actual intacto.

- [ ] **Step 1: Test (falla: campos/branch no existen)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderDefinitionJsonApiTest {
    private fun def(json: String) = ProviderDefinition.fromJson(JSONObject(json))

    @Test fun `una def con jsonApi parsea sin keywords ni parser`() {
        val d = def("""
            {"id":"hack","name":"Hack","baseUrl":"https://hack.to","enabled":true,
             "jsonApi":{
               "searchPath":"/api/search?query={query}","resultsPath":"results",
               "itemSlug":"slug","itemTitle":"title","itemType":"type","movieType":"pelicula",
               "detailPath":"/api/movie/{slug}/","downloadsPath":"downloads","dlLink":"download_link"}}
        """.trimIndent())
        assertNotNull(d)
        assertNotNull(d!!.jsonApi)
        assertEquals("results", d.jsonApi!!.resultsPath)
        assertEquals("pelicula", d.jsonApi!!.movieType)
        assertNull(d.parser)                       // sin parser HTML
        assertEquals(true, d.enabled)
    }

    @Test fun `una def HTML sin jsonApi sigue exigiendo keywords y parser`() {
        // sin keywords/parser y sin jsonApi -> inválida (null), como hoy
        assertNull(def("""{"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}"}"""))
        // con keywords+parser (HTML) sigue parseando
        val html = def("""
            {"id":"y","baseUrl":"https://y.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a.m","attr":"href"}}}
        """.trimIndent())
        assertNotNull(html)
        assertNotNull(html!!.parser)
        assertNull(html.jsonApi)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionJsonApiTest"`
Expected: FAIL (no existe `jsonApi`).

- [ ] **Step 3: Crear JsonApiConfig.kt**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject

/** Config declarativa de un proveedor cuya fuente es una API JSON de 2 pasos (search -> detail). */
data class JsonApiConfig(
    val searchPath: String,       // "/wp-json/wpreact/v1/search?query={query}&posts_per_page=20&page=1"
    val resultsPath: String,      // clave del array de resultados en el JSON de búsqueda
    val itemSlug: String,
    val itemTitle: String,
    val itemType: String,
    val itemYear: String?,
    val itemLanguage: String?,
    val movieType: String,        // valor de itemType que tratamos como película (v1)
    val detailPath: String,       // "/wp-json/wpreact/v1/movie/{slug}/"
    val downloadsPath: String,    // clave del array de descargas en el detalle
    val dlLink: String,           // "download_link" (magnet)
    val dlQuality: String?,
    val dlSize: String?,
    val dlLanguage: String?,
) {
    companion object {
        fun fromJson(o: JSONObject?): JsonApiConfig? {
            if (o == null) return null
            val searchPath = o.optString("searchPath").ifBlank { return null }
            val resultsPath = o.optString("resultsPath").ifBlank { return null }
            val detailPath = o.optString("detailPath").ifBlank { return null }
            val downloadsPath = o.optString("downloadsPath").ifBlank { return null }
            val dlLink = o.optString("dlLink").ifBlank { return null }
            return JsonApiConfig(
                searchPath = searchPath,
                resultsPath = resultsPath,
                itemSlug = o.optString("itemSlug", "slug"),
                itemTitle = o.optString("itemTitle", "title"),
                itemType = o.optString("itemType", "type"),
                itemYear = o.optString("itemYear").ifBlank { null },
                itemLanguage = o.optString("itemLanguage").ifBlank { null },
                movieType = o.optString("movieType", "pelicula"),
                detailPath = detailPath,
                downloadsPath = downloadsPath,
                dlLink = dlLink,
                dlQuality = o.optString("dlQuality").ifBlank { null },
                dlSize = o.optString("dlSize").ifBlank { null },
                dlLanguage = o.optString("dlLanguage").ifBlank { null },
            )
        }
    }
}
```

- [ ] **Step 4: Editar ProviderDefinition.kt**

1. Cambiar `val parser: ParserRules,` → `val parser: ParserRules?,` (nullable). Añadir al final (tras `readySelector`): `val jsonApi: JsonApiConfig? = null,`.
2. En `fromJson`, INSERTAR el branch JSON justo después de validar `id`/`baseUrl` (antes de exigir `searchPath`/`keywords`/`parser`):
```kotlin
val id = o.optString("id").ifBlank { return null }
val baseUrl = o.optString("baseUrl").ifBlank { return null }
if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

val jsonApi = JsonApiConfig.fromJson(o.optJSONObject("jsonApi"))
if (jsonApi != null) {
    val tokensJsonJ = o.optJSONArray("languageTokens")
    val tokensJ = if (tokensJsonJ == null) emptyList()
        else (0 until tokensJsonJ.length()).map { tokensJsonJ.getString(it) }
    val hostAltJsonJ = o.optJSONArray("hostAlt")
    val hostAltJ = if (hostAltJsonJ == null) emptyList()
        else (0 until hostAltJsonJ.length()).map { hostAltJsonJ.getString(it).trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
    return@runCatching ProviderDefinition(
        id = id, name = o.optString("name").ifBlank { id },
        enabled = o.optBoolean("enabled", true), priority = o.optInt("priority", 50),
        baseUrl = baseUrl.trimEnd('/'), searchPath = "", charset = o.optString("charset", "utf-8"),
        needsCloudflare = false, keywords = emptyMap(), languageTokens = tokensJ,
        parser = null, detail = null, hostAlt = hostAltJ, jsonApi = jsonApi,
    )
}
```
3. Al final del path HTML, en la construcción existente `ProviderDefinition(...)`, no hace falta cambiar nada salvo que `parser` ahora es nullable (sigue pasando el `ParserRules` no-null). Asegurar que el constructor recibe `jsonApi = null` por default (ya está).

- [ ] **Step 5: Editar HtmlParser.kt (guard de parser nullable)**

En `HtmlParser.parseList`, al inicio, tras obtener `doc`:
```kotlin
val p = def.parser ?: return emptyList()
```
y reemplazar los usos de `def.parser` por `p` en el resto de la función. (Esto sólo afecta a defs sin parser, que nunca llegan aquí por el enrutado, pero satisface el tipo nullable.)

- [ ] **Step 6: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionJsonApiTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (defs HTML existentes y `RealProvidersJsonTest`/`DeclarativeHtmlBackendTest` siguen verdes: el branch JSON no altera el path HTML).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JsonApiConfig.kt app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt app/src/main/java/com/arkiv/player/data/catalog/providers/HtmlParser.kt app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionJsonApiTest.kt
git commit -m "feat(torrent): JsonApiConfig + ProviderDefinition acepta defs jsonApi (sin keywords/parser)"
```

---

## Task 2: JsonProviderBackend

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonProviderBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/JsonProviderBackendTest.kt`

**Interfaces:**
- Consumes: `PageFetcher.fetch(url, charset, headers)`, `JsonApiConfig`, `SearchContext`, `RawTorrent`, `HtmlParser.parseSize`.
- Produces: `class JsonProviderBackend(def: ProviderDefinition, fetcher: PageFetcher, maxMovies: Int = 8) : ProviderBackend` — `search(ctx)`: fetch search JSON → filtra `itemType == movieType` (máx `maxMovies`) → por cada uno fetch detail JSON → cada download magnet → `RawTorrent(seeders=1)`.

- [ ] **Step 1: Test con fixtures del JSON REAL (falla: clase no existe)**

```kotlin
package com.arkiv.player.data.catalog.providers

import com.arkiv.player.data.catalog.TorrentSearchApi
import com.arkiv.player.data.catalog.TorrentLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonProviderBackendTest {
    // Def jsonApi mínima estilo hacktorrent.
    private val def = ProviderDefinition.fromJson(org.json.JSONObject("""
        {"id":"hack","name":"Hack","baseUrl":"https://hack.to","enabled":true,
         "jsonApi":{"searchPath":"/api/search?query={query}","resultsPath":"results",
           "itemSlug":"slug","itemTitle":"title","itemType":"type","movieType":"pelicula",
           "detailPath":"/api/movie/{slug}/","downloadsPath":"downloads",
           "dlLink":"download_link","dlQuality":"quality","dlSize":"size","dlLanguage":"language"}}
    """.trimIndent()))!!

    private val searchJson = """
        {"results":[
          {"slug":"superman-2","title":"Superman","type":"pelicula","year":"2025","language":"Latino"},
          {"slug":"una-serie","title":"Una Serie","type":"serie","year":"2020","language":"Latino"}
        ],"total":2,"pages":1}
    """.trimIndent()

    private val detailJson = """
        {"slug":"superman-2","title":"Superman","downloads":[
          {"quality":"WEB-DL 4k HDR","size":"23.1 GB","download_link":"magnet:?xt=urn:btih:e1e0c2411efcf722978eaef0af9ab30d62457c7e&dn=x","language":"Latino/Inglés"},
          {"quality":"WEB-DL 1080p","size":"9.94 GB","download_link":"magnet:?xt=urn:btih:0761280fc0ad8139100a3e44a50af0d4d8bcdaaa&dn=y","language":"Latino/Inglés"}
        ]}
    """.trimIndent()

    private fun fetcher(vararg fail: String) = object : PageFetcher {
        override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
            url.contains("/api/search") -> searchJson
            url.contains("/api/movie/superman-2/") -> detailJson
            url.contains("/api/movie/una-serie/") -> """{"downloads":[]}"""
            else -> null
        }
    }

    @Test fun `search convierte los downloads de peliculas en RawTorrents`() = runBlocking {
        val out = JsonProviderBackend(def, fetcher()).search(SearchContext(listOf("Superman"), ContentType.MOVIE, year = "2025"))
        assertEquals(2, out.size)                             // 2 calidades de la película; la serie se ignora
        assertTrue(out.all { it.magnetUri!!.startsWith("magnet:") })
        assertTrue(out.all { it.infoHash != null })            // btih extraído
        assertTrue(out.all { it.name.contains("Superman") })
        assertTrue(out.all { it.seeders == 1 })                // no 0 -> sobrevive finalize
        val q4k = out.first { it.name.contains("4k", ignoreCase = true) }
        assertTrue(q4k.sizeBytes > 20L shl 30)                 // ~23 GB
        assertEquals(TorrentLang.LATINO, TorrentSearchApi.classify(q4k.name))  // "Latino/Inglés" -> LATINO
    }

    @Test fun `un detalle roto no rompe la busqueda`() = runBlocking {
        val badFetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
                url.contains("/api/search") -> searchJson
                url.contains("/api/movie/superman-2/") -> "no-es-json{{{"
                else -> null
            }
        }
        val out = JsonProviderBackend(def, badFetcher).search(SearchContext(listOf("Superman"), ContentType.MOVIE))
        assertEquals(0, out.size)                              // sin crash
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonProviderBackendTest"`
Expected: FAIL (unresolved `JsonProviderBackend`).

- [ ] **Step 3: Implementar JsonProviderBackend.kt**

```kotlin
package com.arkiv.player.data.catalog.providers

import android.util.Log
import com.arkiv.player.data.catalog.RawTorrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Backend para trackers con API JSON de 2 pasos (search -> detail). v1: solo películas
 * (itemType == movieType). Cada download del detalle (magnet directo) se convierte en un RawTorrent.
 * seeders=1 ("vivo, desconocido") porque la API no da conteo y finalize filtra seeders>0.
 */
class JsonProviderBackend(
    private val def: ProviderDefinition,
    private val fetcher: PageFetcher,
    private val maxMovies: Int = 8,
) : ProviderBackend {

    override val id: String = "json:${def.id}"
    private val cfg = def.jsonApi!!
    private val btih = Regex("""btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""")

    override suspend fun search(ctx: SearchContext): List<RawTorrent> = coroutineScope {
        val title = ctx.titles.firstOrNull()?.trim().orEmpty()
        if (title.isBlank()) return@coroutineScope emptyList()
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val searchUrl = def.baseUrl + cfg.searchPath.replace("{query}", q)
        val searchBody = fetcher.fetch(searchUrl, "utf-8") ?: return@coroutineScope emptyList()

        val movies = runCatching {
            val results = JSONObject(searchBody).optJSONArray(cfg.resultsPath) ?: JSONArray()
            (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { it.optString(cfg.itemType) == cfg.movieType }
        }.getOrDefault(emptyList()).take(maxMovies)

        runCatching { Log.i("ArkivProv", "provider=${def.id} json search='$title' pelis=${movies.size}") }

        val jobs = movies.map { item ->
            async(Dispatchers.IO) { runCatching { fetchDownloads(item) }.getOrDefault(emptyList()) }
        }
        jobs.awaitAll().flatten().also {
            runCatching { Log.i("ArkivProv", "provider=${def.id} json filas=${it.size}") }
        }
    }

    private suspend fun fetchDownloads(item: JSONObject): List<RawTorrent> = withContext(Dispatchers.IO) {
        val slug = item.optString(cfg.itemSlug).ifBlank { return@withContext emptyList() }
        val title = item.optString(cfg.itemTitle).ifBlank { slug }
        val detailUrl = def.baseUrl + cfg.detailPath.replace("{slug}", slug)
        val body = fetcher.fetch(detailUrl, "utf-8") ?: return@withContext emptyList()
        runCatching {
            val downloads = JSONObject(body).optJSONArray(cfg.downloadsPath) ?: JSONArray()
            (0 until downloads.length()).mapNotNull { i ->
                val dl = downloads.optJSONObject(i) ?: return@mapNotNull null
                val magnet = dl.optString(cfg.dlLink).takeIf { it.startsWith("magnet:") } ?: return@mapNotNull null
                val quality = cfg.dlQuality?.let { dl.optString(it) }.orEmpty()
                val language = cfg.dlLanguage?.let { dl.optString(it) }.orEmpty()
                val size = cfg.dlSize?.let { dl.optString(it) }.orEmpty()
                RawTorrent(
                    name = listOf(title, quality, language).filter { it.isNotBlank() }.joinToString(" "),
                    seeders = 1,   // "vivo, desconocido": la API no da seeds; evita el filtro seeders>0
                    sizeBytes = HtmlParser.parseSize(size),
                    infoHash = btih.find(magnet)?.groupValues?.get(1),
                    magnetUri = magnet,
                )
            }
        }.getOrDefault(emptyList())
    }
}
```

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonProviderBackendTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JsonProviderBackend.kt app/src/test/java/com/arkiv/player/data/catalog/providers/JsonProviderBackendTest.kt
git commit -m "feat(torrent): JsonProviderBackend (API JSON search->detail->magnet, v1 películas)"
```

---

## Task 3: Enrutar en RegistryProviderBackend

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt`

**Interfaces:**
- Consumes: `JsonProviderBackend` (Task 2), `DeclarativeHtmlBackend` (existente).

- [ ] **Step 1: Editar el fan-out para enrutar por jsonApi**

En `RegistryProviderBackend.search`, reemplazar el cuerpo del `map`:
```kotlin
override suspend fun search(ctx: SearchContext): List<RawTorrent> = coroutineScope {
    definitions().filter { it.enabled }.map { def ->
        async {
            runCatching {
                if (def.jsonApi != null) JsonProviderBackend(def, fetcher).search(ctx)
                else DeclarativeHtmlBackend(def, fetcher, jsRenderer = jsRenderer).search(ctx)
            }.getOrDefault(emptyList())
        }
    }.awaitAll().flatten()
}
```

- [ ] **Step 2: Compilar + suite**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
Run: `./gradlew testDebugUnitTest` → PASS (el `RegistryProviderBackendTest` existente sigue verde: sus defs no tienen `jsonApi`, siguen yendo por el path HTML).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/RegistryProviderBackend.kt
git commit -m "feat(torrent): RegistryProviderBackend enruta defs jsonApi a JsonProviderBackend"
```

---

## Task 4: Def de hacktorrent + fixture con JSON real

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HacktorrentDefinitionTest.kt`

**Interfaces:**
- Consumes: `BundledProviders.byId("hacktorrent")`, `JsonProviderBackend`.

- [ ] **Step 1: Test end-to-end con la def bundled (falla: no existe hacktorrent)**

```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HacktorrentDefinitionTest {
    private val def = BundledProviders.byId("hacktorrent")

    @Test fun `esta habilitado y es jsonApi`() {
        assertTrue(def.enabled)
        assertTrue(def.jsonApi != null)
    }

    // JSON real recortado (hacktorrent.to, jul 2026): search -> detail movie.
    private val searchJson = """
        {"results":[{"slug":"superman-2","title":"Superman","type":"pelicula","year":"2025","language":"Latino"}],"total":1,"pages":1}
    """.trimIndent()
    private val detailJson = """
        {"slug":"superman-2","title":"Superman","downloads":[
          {"quality":"WEB-DL 1080p","size":"9.94 GB","download_link":"magnet:?xt=urn:btih:0761280fc0ad8139100a3e44a50af0d4d8bcdaaa&dn=Superman.2025.1080p","language":"Latino/Inglés"}
        ]}
    """.trimIndent()

    @Test fun `resuelve el magnet directo desde la API`() = runBlocking {
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
                url.contains("/wp-json/wpreact/v1/search") -> searchJson
                url.contains("/wp-json/wpreact/v1/movie/superman-2/") -> detailJson
                else -> null
            }
        }
        val out = JsonProviderBackend(def, fetcher).search(SearchContext(listOf("Superman"), ContentType.MOVIE, year = "2025"))
        assertTrue(out.isNotEmpty())
        assertTrue(out[0].magnetUri!!.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(out[0].name.contains("Superman"))
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HacktorrentDefinitionTest"`
Expected: FAIL (`byId("hacktorrent")` lanza).

- [ ] **Step 3: Añadir hacktorrent a providers.json**

Insertar en el array (antes del `]` final; recordar la coma en la entrada previa):
```json
{
  "id": "hacktorrent",
  "name": "HackTorrent",
  "enabled": true,
  "priority": 67,
  "baseUrl": "https://hacktorrent.to",
  "hostAlt": [],
  "languageTokens": ["latino", "castellano"],
  "jsonApi": {
    "searchPath": "/wp-json/wpreact/v1/search?query={query}&posts_per_page=20&page=1",
    "resultsPath": "results",
    "itemSlug": "slug", "itemTitle": "title", "itemType": "type", "itemYear": "year", "itemLanguage": "language",
    "movieType": "pelicula",
    "detailPath": "/wp-json/wpreact/v1/movie/{slug}/",
    "downloadsPath": "downloads",
    "dlLink": "download_link", "dlQuality": "quality", "dlSize": "size", "dlLanguage": "language"
  }
}
```

- [ ] **Step 4: Correr (pasa) + suite + validez JSON**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HacktorrentDefinitionTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (`RealProvidersJsonTest` incluye hacktorrent sin descartarlo; validar además `python3 -c "import json; json.load(open('app/src/main/assets/providers.json'))"`).

- [ ] **Step 5: Commit (verificar diff antes: SOLO hacktorrent, por la sesión concurrente)**

```bash
git diff app/src/main/assets/providers.json   # confirmar que sólo se agregó hacktorrent
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/HacktorrentDefinitionTest.kt
git commit -m "feat(torrent): habilitar hacktorrent (API JSON) on-device"
```

---

## Task 5: Verificación

- [ ] **Step 1: Suite completa + build**

Run: `./gradlew testDebugUnitTest` → 0 fallos. Run: `./gradlew assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 2: Sincronizar el hot-update**

Copiar `app/src/main/assets/providers.json` al repo `lordmacu/arkiv-providers` y `push` (si no, el remoto pisa hacktorrent). Ver `docs/arkiv-providers-README.md`.

- [ ] **Step 3: Verificación en device (manual)**

Con el APK nuevo o vía hot-update (force-stop + relaunch para re-bajar el remoto). Buscar una película popular. En logcat (`ArkivProv`): `provider=hacktorrent json filas=N` con N>0. Confirmar que aparecen fuentes latino de hacktorrent y que una reproduce (magnet directo → player de torrent).

- [ ] **Step 4: (si verde) cierre** — `superpowers:finishing-a-development-branch`.

---

## Self-Review — cobertura del spec

- **§2.1 esquema jsonApi + validación relajada:** Task 1. ✓
- **§2.2 JsonProviderBackend (search->detail->magnet, seeders=1, aislamiento):** Task 2. ✓
- **§2.3 seam en RegistryProviderBackend:** Task 3. ✓
- **§2.4 def hacktorrent:** Task 4. ✓
- **§3 fromJson acepta jsonApi sin keywords/parser:** Task 1 (branch). ✓
- **§4 testing (config parse, backend con fixtures reales, aislamiento, seed=1, classify LATINO, serie ignorada):** Tasks 1,2,4. ✓
- **§5 device:** Task 5. ✓
- **§6 fuera de alcance (series/anime, paginación):** respetado (filtro movieType, page=1 en la def). ✓
- **§7 criterios de éxito:** Task 5 los verifica; "providers existentes idénticos" cubierto por el 2º test de Task 1 + suite. ✓

Riesgo conocido (no bloquea): el dominio de hacktorrent rota; si `hacktorrent.to` cae, actualizar `baseUrl`/`hostAlt` vía hot-update. El fixture usa JSON real, pero el render vivo se confirma en device (Task 5).
