# Backend JSON: extensión a anime (hacktorrent por episodio) — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extender `JsonProviderBackend` (hoy solo películas) para cubrir anime de hacktorrent: por cada episodio del detalle anime, emitir un `RawTorrent` nombrado `SxxEyy` y dejar que el pipeline de anime filtre el episodio.

**Architecture:** `JsonApiConfig` gana campos anime opcionales. `JsonProviderBackend.search` ramifica por `ctx.type`: ANIME usa el endpoint/tipo anime y nombra con `S{season}E{episode}`; MOVIE queda v1. El matching de episodio lo hace el pipeline (`SourceQuerySpec.matches`), no el backend.

**Tech Stack:** Kotlin, Coroutines, org.json, JUnit4.

## Global Constraints

- Ningún request del path de torrents va a `blog`. La capa web NO se toca. Identidad git `lordmacu`, sin coautoría, nunca `git add -A`.
- **Sesión concurrente activa** en el branch: commitear SOLO los archivos de cada tarea; verificar el diff antes de commitear.
- Tests: `./gradlew testDebugUnitTest`. TDD. Un commit por tarea. Si la suite completa falla en archivos ajenos (subtítulos/player/quality), es la sesión concurrente — reportar, no arreglar; correr las clases propias.
- Los `RawTorrent` llevan `seeders = 1`. El comportamiento de PELÍCULA (v1) debe quedar **idéntico**.
- hacktorrent anime (verificado): items `type:"anime"`, detalle `/wp-json/wpreact/v1/anime/{slug}/` → `downloads:[{season, episode, quality, size, download_link(magnet), language}]`, numeración por temporada.

---

## Task 1: JsonApiConfig — campos de anime

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonApiConfig.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/JsonApiConfigAnimeTest.kt` (crear)

**Interfaces:**
- Produces: `JsonApiConfig` gana `animeType: String?`, `animeDetailPath: String?`, `dlSeason: String?`, `dlEpisode: String?` (todos default null, parseados en `fromJson`).

- [ ] **Step 1: Test (falla)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonApiConfigAnimeTest {
    @Test fun `parsea los campos de anime`() {
        val c = JsonApiConfig.fromJson(JSONObject("""
            {"searchPath":"/s?q={query}","resultsPath":"results","detailPath":"/m/{slug}/",
             "downloadsPath":"downloads","dlLink":"download_link",
             "animeType":"anime","animeDetailPath":"/a/{slug}/","dlSeason":"season","dlEpisode":"episode"}
        """.trimIndent()))!!
        assertEquals("anime", c.animeType)
        assertEquals("/a/{slug}/", c.animeDetailPath)
        assertEquals("season", c.dlSeason)
        assertEquals("episode", c.dlEpisode)
    }

    @Test fun `los campos de anime son opcionales (null si faltan)`() {
        val c = JsonApiConfig.fromJson(JSONObject("""
            {"searchPath":"/s?q={query}","resultsPath":"results","detailPath":"/m/{slug}/",
             "downloadsPath":"downloads","dlLink":"download_link"}
        """.trimIndent()))!!
        assertNull(c.animeType); assertNull(c.animeDetailPath); assertNull(c.dlSeason); assertNull(c.dlEpisode)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonApiConfigAnimeTest"`
Expected: FAIL (campos no existen).

- [ ] **Step 3: Editar JsonApiConfig.kt**

Añadir a la data class (tras `dlLanguage`):
```kotlin
val animeType: String? = null,
val animeDetailPath: String? = null,
val dlSeason: String? = null,
val dlEpisode: String? = null,
```
Y en `fromJson`, en la construcción del objeto, añadir:
```kotlin
animeType = o.optString("animeType").ifBlank { null },
animeDetailPath = o.optString("animeDetailPath").ifBlank { null },
dlSeason = o.optString("dlSeason").ifBlank { null },
dlEpisode = o.optString("dlEpisode").ifBlank { null },
```

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonApiConfigAnimeTest"` → PASS.
Luego `./gradlew testDebugUnitTest` → PASS (los tests existentes de JSON siguen verdes; campos opcionales).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JsonApiConfig.kt app/src/test/java/com/arkiv/player/data/catalog/providers/JsonApiConfigAnimeTest.kt
git commit -m "feat(torrent): JsonApiConfig con campos de anime (animeType/animeDetailPath/dlSeason/dlEpisode)"
```

---

## Task 2: JsonProviderBackend — ramificar por ctx.type (anime por episodio)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/JsonProviderBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/JsonProviderBackendAnimeTest.kt` (crear)

**Interfaces:**
- Consumes: `JsonApiConfig` anime fields (Task 1), `ContentType` (mismo paquete), `SourceQuerySpec`/`TorrentSearchApi` (en el test).
- Produces: `JsonProviderBackend.search(ctx)` — cuando `ctx.type == ANIME` y hay config anime, filtra `itemType==animeType`, usa `animeDetailPath`, y nombra cada download `"{title} S{season:02}E{episode:02} {quality} {language}"`. MOVIE queda v1. Sin config anime en una búsqueda anime → vacío.

- [ ] **Step 1: Test (falla)**

```kotlin
package com.arkiv.player.data.catalog.providers

import com.arkiv.player.data.catalog.TorrentSearchApi
import com.arkiv.player.data.catalog.TorrentLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonProviderBackendAnimeTest {
    private val def = ProviderDefinition.fromJson(org.json.JSONObject("""
        {"id":"hack","name":"Hack","baseUrl":"https://hack.to","enabled":true,
         "jsonApi":{"searchPath":"/api/search?query={query}","resultsPath":"results",
           "itemSlug":"slug","itemTitle":"title","itemType":"type","itemLanguage":"language","movieType":"pelicula",
           "detailPath":"/api/movie/{slug}/","animeType":"anime","animeDetailPath":"/api/anime/{slug}/",
           "downloadsPath":"downloads","dlLink":"download_link","dlQuality":"quality","dlSize":"size",
           "dlLanguage":"language","dlSeason":"season","dlEpisode":"episode"}}
    """.trimIndent()))!!

    private val searchJson = """
        {"results":[
          {"slug":"slime","title":"Slime","type":"anime","language":"Latino"},
          {"slug":"una-peli","title":"Una Peli","type":"pelicula","language":"Latino"}
        ]}
    """.trimIndent()
    private val animeDetailJson = """
        {"slug":"slime","title":"Slime","downloads":[
          {"season":3,"episode":1,"quality":"WEB-DL 1080p","size":"350 MB","download_link":"magnet:?xt=urn:btih:AAAABBBBCCCCDDDDEEEEFFFF0000111122223333","language":"Latino/Japones"},
          {"season":3,"episode":2,"quality":"WEB-DL 1080p","size":"350 MB","download_link":"magnet:?xt=urn:btih:BBBBCCCCDDDDEEEEFFFF00001111222233334444","language":"Latino/Japones"}
        ]}
    """.trimIndent()

    private val fetcher = object : PageFetcher {
        override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
            url.contains("/api/search") -> searchJson
            url.contains("/api/anime/slime/") -> animeDetailJson
            else -> null
        }
    }

    @Test fun `busqueda anime emite un RawTorrent por episodio con SxxEyy`() = runBlocking {
        val out = JsonProviderBackend(def, fetcher).search(
            SearchContext(listOf("Slime"), ContentType.ANIME, episode = 1, episodeAbs = 1))
        assertEquals(2, out.size)                       // 2 episodios; la peli se ignora en modo anime
        assertTrue(out.all { it.magnetUri!!.startsWith("magnet:") })
        assertTrue(out.all { it.seeders == 1 })
        val e1 = out.first { it.name.contains("S03E01") }
        assertTrue(e1.name.contains("Slime"))
        assertEquals(TorrentLang.LATINO, TorrentSearchApi.classify(e1.name))   // "Latino/Japones" -> LATINO
    }

    @Test fun `busqueda de pelicula ignora los items anime (regresion v1)`() = runBlocking {
        val movieDetail = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
                url.contains("/api/search") -> searchJson
                url.contains("/api/movie/una-peli/") -> """{"downloads":[{"quality":"1080p","size":"2 GB","download_link":"magnet:?xt=urn:btih:CCCCDDDDEEEEFFFF000011112222333344445555","language":"Latino"}]}"""
                else -> null
            }
        }
        val out = JsonProviderBackend(def, movieDetail).search(
            SearchContext(listOf("Una Peli"), ContentType.MOVIE, year = "2025"))
        assertEquals(1, out.size)                       // solo la peli; el anime se ignora en modo movie
        assertTrue(out[0].name.contains("Una Peli"))
    }

    @Test fun `anime sin config anime devuelve vacio`() = runBlocking {
        val movieOnlyDef = ProviderDefinition.fromJson(org.json.JSONObject("""
            {"id":"m","name":"M","baseUrl":"https://m.to","enabled":true,
             "jsonApi":{"searchPath":"/api/search?query={query}","resultsPath":"results",
               "itemSlug":"slug","itemTitle":"title","itemType":"type","movieType":"pelicula",
               "detailPath":"/api/movie/{slug}/","downloadsPath":"downloads","dlLink":"download_link"}}
        """.trimIndent()))!!
        val out = JsonProviderBackend(movieOnlyDef, fetcher).search(
            SearchContext(listOf("Slime"), ContentType.ANIME, episode = 1))
        assertEquals(0, out.size)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonProviderBackendAnimeTest"`
Expected: FAIL (aún no ramifica por tipo; el `busqueda anime` da 0).

- [ ] **Step 3: Editar JsonProviderBackend.kt**

Reemplazar `search` y `fetchDownloads` por la versión que ramifica:
```kotlin
override suspend fun search(ctx: SearchContext): List<RawTorrent> = coroutineScope {
    val title = ctx.titles.firstOrNull()?.trim().orEmpty()
    if (title.isBlank()) return@coroutineScope emptyList()

    // Modalidad según el tipo de búsqueda.
    val anime = ctx.type == ContentType.ANIME
    val typeValue: String
    val detailPath: String
    if (anime) {
        val at = cfg.animeType; val adp = cfg.animeDetailPath
        if (at == null || adp == null) return@coroutineScope emptyList()   // no atiende anime
        typeValue = at; detailPath = adp
    } else {
        typeValue = cfg.movieType; detailPath = cfg.detailPath
    }

    val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
    val searchUrl = def.baseUrl + cfg.searchPath.replace("{query}", q)
    val searchBody = fetcher.fetch(searchUrl, "utf-8") ?: return@coroutineScope emptyList()

    val items = runCatching {
        val results = JSONObject(searchBody).optJSONArray(cfg.resultsPath) ?: JSONArray()
        (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString(cfg.itemType) == typeValue }
    }.getOrDefault(emptyList()).take(maxMovies)

    runCatching { Log.i("ArkivProv", "provider=${def.id} json type=${ctx.type} search='$title' items=${items.size}") }

    val jobs = items.map { item ->
        val itemLanguage = cfg.itemLanguage?.let { item.optString(it) }.orEmpty()
        async(Dispatchers.IO) { runCatching { fetchDownloads(item, detailPath, anime, itemLanguage) }.getOrDefault(emptyList()) }
    }
    jobs.awaitAll().flatten().also {
        runCatching { Log.i("ArkivProv", "provider=${def.id} json filas=${it.size}") }
    }
}

private suspend fun fetchDownloads(item: JSONObject, detailPath: String, episodic: Boolean, itemLanguage: String): List<RawTorrent> = withContext(Dispatchers.IO) {
    val slug = item.optString(cfg.itemSlug).ifBlank { return@withContext emptyList() }
    val title = item.optString(cfg.itemTitle).ifBlank { slug }
    val detailUrl = def.baseUrl + detailPath.replace("{slug}", slug)
    val body = fetcher.fetch(detailUrl, "utf-8") ?: return@withContext emptyList()
    runCatching {
        val downloads = JSONObject(body).optJSONArray(cfg.downloadsPath) ?: JSONArray()
        (0 until downloads.length()).mapNotNull { i ->
            val dl = downloads.optJSONObject(i) ?: return@mapNotNull null
            val magnet = dl.optString(cfg.dlLink).takeIf { it.startsWith("magnet:") } ?: return@mapNotNull null
            val quality = cfg.dlQuality?.let { dl.optString(it) }.orEmpty()
            val language = cfg.dlLanguage?.let { dl.optString(it) }.orEmpty().ifBlank { itemLanguage }
            val size = cfg.dlSize?.let { dl.optString(it) }.orEmpty()
            val sxe = if (episodic) buildSxe(dl) else ""
            RawTorrent(
                name = listOf(title, sxe, quality, language).filter { it.isNotBlank() }.joinToString(" "),
                seeders = 1,
                sizeBytes = HtmlParser.parseSize(size),
                infoHash = btih.find(magnet)?.groupValues?.get(1),
                magnetUri = magnet,
            )
        }
    }.getOrDefault(emptyList())
}

/** "S03E01" a partir del download anime; "" si falta season/episode. */
private fun buildSxe(dl: JSONObject): String {
    val s = cfg.dlSeason?.let { dl.optInt(it, -1) } ?: -1
    val e = cfg.dlEpisode?.let { dl.optInt(it, -1) } ?: -1
    return if (s >= 0 && e >= 0) "S%02dE%02d".format(s, e) else ""
}
```
Actualizar el KDoc de la clase para reflejar que ahora atiende películas Y anime (por episodio).

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.JsonProviderBackendAnimeTest"` → PASS.
Correr también el test v1 existente: `--tests "com.arkiv.player.data.catalog.providers.JsonProviderBackendTest"` → PASS (regresión de películas).
Luego `./gradlew testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/JsonProviderBackend.kt app/src/test/java/com/arkiv/player/data/catalog/providers/JsonProviderBackendAnimeTest.kt
git commit -m "feat(torrent): JsonProviderBackend atiende anime por episodio (SxxEyy, delega match al pipeline)"
```

---

## Task 3: Def de hacktorrent (campos anime) + fixture + sync + verificación

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HacktorrentAnimeTest.kt` (crear)

**Interfaces:**
- Consumes: `BundledProviders.byId("hacktorrent")`, `JsonProviderBackend`.

- [ ] **Step 1: Test end-to-end con la def bundled (falla: la def aún no tiene animeType)**

```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HacktorrentAnimeTest {
    private val def = BundledProviders.byId("hacktorrent")

    @Test fun `la def tiene config de anime`() {
        assertTrue(def.jsonApi?.animeType == "anime")
        assertTrue(def.jsonApi?.animeDetailPath?.contains("/anime/") == true)
    }

    private val searchJson = """
        {"results":[{"slug":"slime","title":"Slime","type":"anime","language":"Latino"}]}
    """.trimIndent()
    private val animeDetailJson = """
        {"slug":"slime","title":"Slime","downloads":[
          {"season":3,"episode":1,"quality":"WEB-DL 1080p","size":"350 MB","download_link":"magnet:?xt=urn:btih:AAAABBBBCCCCDDDDEEEEFFFF0000111122223333","language":"Latino/Japones"}
        ]}
    """.trimIndent()

    @Test fun `resuelve un episodio de anime desde la API`() = runBlocking {
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = when {
                url.contains("/wp-json/wpreact/v1/search") -> searchJson
                url.contains("/wp-json/wpreact/v1/anime/slime/") -> animeDetailJson
                else -> null
            }
        }
        val out = JsonProviderBackend(def, fetcher).search(
            SearchContext(listOf("Slime"), ContentType.ANIME, episode = 1, episodeAbs = 1))
        assertTrue(out.isNotEmpty())
        assertTrue(out[0].name.contains("S03E01"))
        assertTrue(out[0].magnetUri!!.startsWith("magnet:?xt=urn:btih:"))
    }
}
```

- [ ] **Step 2: Correr (falla) → Step 3: añadir los campos anime al jsonApi de hacktorrent**

En `app/src/main/assets/providers.json`, dentro del bloque `"jsonApi"` de la entrada `hacktorrent`, añadir (junto a los campos existentes):
```json
"animeType": "anime",
"animeDetailPath": "/wp-json/wpreact/v1/anime/{slug}/",
"dlSeason": "season",
"dlEpisode": "episode"
```

- [ ] **Step 4: Correr (pasa) + suite + validez JSON**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HacktorrentAnimeTest"` → PASS.
`python3 -c "import json; json.load(open('app/src/main/assets/providers.json'))"` → válido.
`./gradlew testDebugUnitTest` → PASS (`RealProvidersJsonTest` verde).

- [ ] **Step 5: Commit (verificar diff: SOLO hacktorrent, por la sesión concurrente)**

```bash
git diff app/src/main/assets/providers.json   # confirmar que sólo cambió el jsonApi de hacktorrent
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/HacktorrentAnimeTest.kt
git commit -m "feat(torrent): hacktorrent anime (config anime en su jsonApi)"
```

- [ ] **Step 6: Sync hot-update + verificación**

- Sincronizar `providers.json` al repo `lordmacu/arkiv-providers` (`cp` + commit + push) — si no, el remoto pisa el cambio.
- Suite completa verde + `./gradlew assembleDebug` BUILD SUCCESSFUL.
- Device (manual, vía hot-update): buscar un anime con temporada mapeada; en logcat `provider=hacktorrent json type=ANIME filas=N`; confirmar que aparece el episodio latino y reproduce.

---

## Self-Review — cobertura del spec

- **§2.1 campos anime en JsonApiConfig:** Task 1. ✓
- **§2.2 ramificar por ctx.type + nombre SxxEyy:** Task 2. ✓
- **§2.3 matching delegado al pipeline:** el backend solo produce SxxEyy; el test de Task 2 verifica el nombre, y el pipeline (`spec.matches`) filtra en producción (probado indirectamente; un test de integración con `searchAnime` es opcional). ✓
- **§3 def hacktorrent anime:** Task 3. ✓
- **§4 testing (config, anime flow, regresión película, sin-config):** Tasks 1,2,3. ✓
- **§5 device + §6 fuera de alcance:** Task 3 Step 6 / respetado. ✓
- **§7 criterios:** Task 3 los verifica.

Riesgo conocido (declarado en el spec §2.4): la numeración por-temporada de hacktorrent puede no cuadrar con la de TVDB de la app → algunos episodios se descartan (limitación de todo anime, delegada al pipeline). No bloquea.
