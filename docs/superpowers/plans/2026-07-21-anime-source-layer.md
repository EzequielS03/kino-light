# Anime Source Layer — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unificar la búsqueda de fuentes de anime en el pipeline multi-backend (Jackett + agregadores) con ranking de idioma y numeración robusta (offset absoluto), manteniendo el browse actual y añadiendo búsqueda por episodio.

**Architecture:** Cuatro capas de mapeo (dataset Fribb + Simkl + traversal AniList + títulos español de TMDB) alimentan un resolver puro que produce queries + predicado de match. Un coordinador (`AnimeSourceProvider`) orquesta esas capas y delega el fan-out a `TorrentSearchApi.searchAnime()`. La UI (`AnimeShowDetailScreen`) consume el coordinador: browse fetch-all robusto + búsqueda dirigida por episodio.

**Tech Stack:** Kotlin, OkHttp 4.12 + `org.json`, coroutines, Room (sin tocar esquema), DI manual en `AppGraph`, JUnit 4.13.2.

## Global Constraints

- Convenciones del repo: OkHttp + `org.json` (sin Retrofit/Moshi/serialization), coroutines con `withContext(Dispatchers.IO)`, DI manual por `by lazy` en `AppGraph`. Un archivo = una responsabilidad.
- Claves desde `.env` vía `readEnv()` en `app/build.gradle.kts` → `BuildConfig`. Nunca hardcodear secretos ni commitear `.env` (ya está en `.gitignore`).
- Git: identidad `lordmacu` (ya configurada). **NUNCA** añadir `Co-Authored-By` ni coautoría.
- Tests: JUnit 4 puro bajo `app/src/test/java/com/arkiv/player/...`, nombres de test en backticks en español (patrón existente). No añadir dependencias de test nuevas.
- Prioridad de idioma (ranking anime): **Latino > Castellano > Dual > Japonés > Otros > Inglés**. No se reordena el enum `TorrentLang` (lo usa la ruta de Cine): se aplica una prioridad explícita solo en la ruta de anime.
- No se toca la ruta de Cine/TMDB.
- Compilar: `./gradlew :app:compileDebugKotlin` ; tests: `./gradlew :app:testDebugUnitTest`.

---

## File Structure

**Nuevos:**
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeMapping.kt` — modelo `AnimeMapping` + parser puro del dataset Fribb.
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeMappingRepository.kt` — descarga + cache en disco (TTL) del dataset; `mappingFor(anilistId)`.
- `app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt` — cliente Simkl (cross-IDs + episodios + `alt_titles`).
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt` — lógica pura: input → `SourceQuerySpec` (queries + `matches` + `canonicalEpisode`).
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt` — coordinador que ensambla capas y delega a `TorrentSearchApi`.
- Tests: `FribbAnimeListParserTest.kt`, `AnimeEpisodeResolverTest.kt`, `SimklParseTest.kt`, `AnimeLangPriorityTest.kt`, `AnimeMappingCacheTest.kt` bajo `app/src/test/java/com/arkiv/player/data/catalog/`.

**Modificados:**
- `app/build.gradle.kts` — `buildConfigField` `SIMKL_CLIENT_ID`.
- `app/src/main/java/com/arkiv/player/data/catalog/AniListApi.kt` — `absoluteOffset()` (traversal precuelas).
- `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` — `searchAnime()` + `animeLangPriority()` + param `langPriority` en `runSearch`.
- `app/src/main/java/com/arkiv/player/AppGraph.kt` — cablear `simklApi`, `animeMappingRepository`, `animeSourceProvider`.
- `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt` — consumir el coordinador (browse + por episodio); `play()` sobre `TorrentResult`.

---

## Task 1: Modelo + parser del dataset Fribb (puro)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/AnimeMapping.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/FribbAnimeListParserTest.kt`

**Interfaces:**
- Produces: `data class AnimeMapping(anilistId: Long, malId: Long?, tvdbId: Long?, imdbId: String?, tmdbId: Int?, simklId: Long?, tvdbSeason: Int?, episodeOffset: Int?)` ; `object FribbAnimeListParser { fun parse(json: String): Map<Long, AnimeMapping> }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/arkiv/player/data/catalog/FribbAnimeListParserTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FribbAnimeListParserTest {

    // Formas reales del dataset: imdb_id como array, themoviedb_id como objeto {tv:..},
    // season/episode_offset como objeto {tvdb:..}. Algunas entradas no traen season/offset.
    private val json = """
        [
          {"anilist_id":21,"mal_id":21,"tvdb_id":81797,"imdb_id":["tt0388629"],
           "themoviedb_id":{"tv":37854},"simkl_id":38636,"type":"tv"},
          {"anilist_id":110277,"tvdb_id":267440,"imdb_id":["tt2560140"],
           "themoviedb_id":{"tv":1429},"season":{"tvdb":4,"tmdb":4}},
          {"anilist_id":821,"tvdb_id":70900,"season":{"tvdb":0},"episode_offset":{"tvdb":2},"type":"OVA"},
          {"mal_id":999,"tvdb_id":123}
        ]
    """.trimIndent()

    @Test
    fun `indexa por anilist_id y omite entradas sin anilist`() {
        val map = FribbAnimeListParser.parse(json)
        assertEquals(3, map.size)          // la 4ª no tiene anilist_id
        assertNull(map[999L])
    }

    @Test
    fun `parsea cross-ids con imdb array y tmdb objeto tv`() {
        val m = FribbAnimeListParser.parse(json)[21L]!!
        assertEquals(81797L, m.tvdbId)
        assertEquals("tt0388629", m.imdbId)
        assertEquals(37854, m.tmdbId)
        assertEquals(38636L, m.simklId)
        assertNull(m.tvdbSeason)           // sin season
    }

    @Test
    fun `parsea season tvdb y offset cuando existen`() {
        val aot = FribbAnimeListParser.parse(json)[110277L]!!
        assertEquals(4, aot.tvdbSeason)
        assertNull(aot.episodeOffset)
        val ova = FribbAnimeListParser.parse(json)[821L]!!
        assertEquals(2, ova.episodeOffset)
        assertEquals(0, ova.tvdbSeason)
    }

    @Test
    fun `json invalido devuelve mapa vacio`() {
        assertTrue(FribbAnimeListParser.parse("no soy json").isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.FribbAnimeListParserTest"`
Expected: FAIL de compilación ("unresolved reference: FribbAnimeListParser").

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/arkiv/player/data/catalog/AnimeMapping.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mapeo cruzado de un anime (dataset Fribb/anime-lists). Da los IDs en otras bases + la
 * temporada TVDB. El `episodeOffset` casi nunca viene para TV (solo OVAs/especiales); el offset
 * absoluto real lo calcula el traversal de AniList (ver [AniListApi.absoluteOffset]).
 */
data class AnimeMapping(
    val anilistId: Long,
    val malId: Long? = null,
    val tvdbId: Long? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val simklId: Long? = null,
    val tvdbSeason: Int? = null,
    val episodeOffset: Int? = null,
)

/** Parser del `anime-list-full.json` de Fribb → mapa indexado por `anilist_id`. */
object FribbAnimeListParser {
    fun parse(json: String): Map<Long, AnimeMapping> = runCatching {
        val arr = JSONArray(json)
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val anilist = o.optLong("anilist_id", 0L)
                if (anilist <= 0L) continue          // solo entradas con AniList id
                put(
                    anilist,
                    AnimeMapping(
                        anilistId = anilist,
                        malId = o.optLong("mal_id", 0L).takeIf { it > 0 },
                        tvdbId = o.optLong("tvdb_id", 0L).takeIf { it > 0 },
                        imdbId = imdbOf(o),
                        tmdbId = tmdbOf(o),
                        simklId = o.optLong("simkl_id", 0L).takeIf { it > 0 },
                        tvdbSeason = intInObj(o.opt("season")),
                        episodeOffset = intInObj(o.opt("episode_offset")),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    // imdb_id puede ser un array (["tt..."]) o un string suelto.
    private fun imdbOf(o: JSONObject): String? = when (val v = o.opt("imdb_id")) {
        is JSONArray -> v.optString(0).ifBlank { null }
        is String -> v.ifBlank { null }
        else -> null
    }

    // themoviedb_id puede ser {"tv":id} / {"movie":id} o un int suelto.
    private fun tmdbOf(o: JSONObject): Int? = when (val v = o.opt("themoviedb_id")) {
        is JSONObject -> (v.optInt("tv", 0).takeIf { it > 0 } ?: v.optInt("movie", 0)).takeIf { it > 0 }
        is Number -> v.toInt().takeIf { it > 0 }
        else -> null
    }

    // season / episode_offset vienen como {"tvdb":n,"tmdb":n}; tomamos el de tvdb.
    private fun intInObj(v: Any?): Int? = when (v) {
        is JSONObject -> if (v.has("tvdb")) v.optInt("tvdb") else null
        is Number -> v.toInt()
        else -> null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.FribbAnimeListParserTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeMapping.kt \
        app/src/test/java/com/arkiv/player/data/catalog/FribbAnimeListParserTest.kt
git commit -m "feat(anime): modelo AnimeMapping + parser del dataset Fribb"
```

---

## Task 2: AnimeMappingRepository (cache en disco + TTL)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/AnimeMappingRepository.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/AnimeMappingCacheTest.kt`

**Interfaces:**
- Consumes: `FribbAnimeListParser.parse(json)`, `AnimeMapping` (Task 1).
- Produces: `class AnimeMappingRepository(cacheDir: File, client: OkHttpClient)` con `suspend fun mappingFor(anilistId: Long): AnimeMapping?`. Helper puro: `fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean`.

- [ ] **Step 1: Write the failing test** (solo el helper puro de frescura; la red/IO la validaron los spikes)

`app/src/test/java/com/arkiv/player/data/catalog/AnimeMappingCacheTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeMappingCacheTest {
    private val week = 7L * 24 * 60 * 60 * 1000

    @Test
    fun `cache reciente es fresco`() {
        assertTrue(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week - 1))
    }

    @Test
    fun `cache de mas de una semana esta vencido`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week + 1))
    }

    @Test
    fun `sin fetch previo (0) no es fresco`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 0, nowMs = 5_000))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeMappingCacheTest"`
Expected: FAIL de compilación ("unresolved reference: AnimeMappingRepository").

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/arkiv/player/data/catalog/AnimeMappingRepository.kt`:
```kotlin
package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dataset de mapeo de anime (Fribb/anime-lists). Se descarga a disco (TTL semanal) y se parsea a
 * un mapa en memoria bajo demanda. Si el refresh falla, se sirve lo cacheado; si no hay nada,
 * devuelve null (el resolver degrada a heurístico).
 */
class AnimeMappingRepository(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val file get() = File(cacheDir, "anime-list-full.json")
    private val mutex = Mutex()
    @Volatile private var cache: Map<Long, AnimeMapping>? = null

    suspend fun mappingFor(anilistId: Long): AnimeMapping? = ensureLoaded()[anilistId]

    private suspend fun ensureLoaded(): Map<Long, AnimeMapping> = mutex.withLock {
        cache?.let { return it }
        withContext(Dispatchers.IO) {
            val fresh = file.exists() && isFresh(file.lastModified(), System.currentTimeMillis())
            if (!fresh) runCatching { download() }   // best-effort; si falla, usamos lo que haya
            val json = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            val parsed = json?.let { FribbAnimeListParser.parse(it) }.orEmpty()
            parsed.also { cache = it }
        }
    }

    private fun download() {
        val body = client.newCall(Request.Builder().url(DATASET_URL).build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return
        cacheDir.mkdirs()
        file.writeText(body)
    }

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** ¿El cache descargado en [fetchedAtMs] sigue vigente en [nowMs]? (0 = sin descarga). */
        fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean =
            fetchedAtMs > 0 && nowMs - fetchedAtMs < TTL_MS
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeMappingCacheTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeMappingRepository.kt \
        app/src/test/java/com/arkiv/player/data/catalog/AnimeMappingCacheTest.kt
git commit -m "feat(anime): AnimeMappingRepository con cache en disco y TTL semanal"
```

---

## Task 3: SimklApi + BuildConfig SIMKL_CLIENT_ID

**Files:**
- Modify: `app/build.gradle.kts:29` (tras la línea de `OPENSUBTITLES_API_KEY`)
- Create: `app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/SimklParseTest.kt`

**Interfaces:**
- Produces: `data class SimklAnimeInfo(simklId: Long, totalEpisodes: Int, imdbId: String?, tmdbId: Int?, tvdbId: Long?, altTitles: List<String>)` ; `object SimklParser { fun parseSearch(json): Long? ; fun parseDetail(json): SimklAnimeInfo? }` ; `class SimklApi(clientId, client)` con `suspend fun infoByAniList(anilistId: Long): SimklAnimeInfo?`.

- [ ] **Step 1: Write the failing test** (parsers puros; la red la validó el spike A)

`app/src/test/java/com/arkiv/player/data/catalog/SimklParseTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimklParseTest {
    @Test
    fun `parseSearch toma el simkl id del primer match`() {
        val json = """[{"type":"anime","ids":{"simkl":38636,"slug":"one-piece"},"total_episodes":1176}]"""
        assertEquals(38636L, SimklParser.parseSearch(json))
    }

    @Test
    fun `parseSearch vacio devuelve null`() {
        assertNull(SimklParser.parseSearch("[]"))
    }

    @Test
    fun `parseDetail extrae episodios, cross-ids y alt_titles`() {
        val json = """
            {"ids":{"simkl":38636,"imdb":"tt0388629","tvdb":"81797","tmdb":"37854","anilist":"21"},
             "en_title":"One Piece","total_episodes":1176,
             "alt_titles":[{"name":"Wan Piisu"},{"name":"ワンピース"}]}
        """.trimIndent()
        val info = SimklParser.parseDetail(json)!!
        assertEquals(38636L, info.simklId)
        assertEquals(1176, info.totalEpisodes)
        assertEquals("tt0388629", info.imdbId)
        assertEquals(37854, info.tmdbId)
        assertEquals(81797L, info.tvdbId)
        assertEquals(listOf("Wan Piisu", "ワンピース"), info.altTitles)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.SimklParseTest"`
Expected: FAIL de compilación ("unresolved reference: SimklParser").

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt`:
```kotlin
package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Info autoritativa de un anime en Simkl (episodios + cross-ids + títulos alternativos). */
data class SimklAnimeInfo(
    val simklId: Long,
    val totalEpisodes: Int,
    val imdbId: String?,
    val tmdbId: Int?,
    val tvdbId: Long?,
    val altTitles: List<String>,
)

/** Parsers puros de las respuestas de Simkl (testeables sin red). */
object SimklParser {
    fun parseSearch(json: String): Long? = runCatching {
        val arr = JSONArray(json)
        arr.optJSONObject(0)?.optJSONObject("ids")?.optLong("simkl", 0L)?.takeIf { it > 0 }
    }.getOrNull()

    fun parseDetail(json: String): SimklAnimeInfo? = runCatching {
        val o = JSONObject(json)
        val ids = o.optJSONObject("ids") ?: return@runCatching null
        val simkl = ids.optLong("simkl", 0L).takeIf { it > 0 } ?: return@runCatching null
        val alts = o.optJSONArray("alt_titles")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        }.orEmpty()
        SimklAnimeInfo(
            simklId = simkl,
            totalEpisodes = o.optInt("total_episodes", 0),
            imdbId = ids.optString("imdb").ifBlank { null },
            tmdbId = ids.optString("tmdb").toIntOrNull(),
            tvdbId = ids.optString("tvdb").toLongOrNull(),
            altTitles = alts,
        )
    }.getOrNull()
}

/**
 * Cliente Simkl (público, sin OAuth). Autentica con el header `simkl-api-key = client_id`.
 * `search/id?anilist=` da el id de Simkl; `anime/{id}?extended=full` da episodios + cross-ids.
 */
class SimklApi(
    private val clientId: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    val configured: Boolean get() = clientId.isNotBlank()

    suspend fun infoByAniList(anilistId: Long): SimklAnimeInfo? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val sid = SimklParser.parseSearch(
            get("https://api.simkl.com/search/id?anilist=$anilistId") ?: return@withContext null,
        ) ?: return@withContext null
        SimklParser.parseDetail(
            get("https://api.simkl.com/anime/$sid?extended=full") ?: return@withContext null,
        )
    }

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url).header("simkl-api-key", clientId).build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()
}
```

- [ ] **Step 4: Add BuildConfig field**

En `app/build.gradle.kts`, tras la línea 29 (`buildConfigField("String", "OPENSUBTITLES_API_KEY", ...)`), añadir:
```kotlin
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${readEnv("SIMKL_CLIENT_ID")}\"")
```

- [ ] **Step 5: Run test + compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.SimklParseTest"`
Expected: PASS (3 tests).
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (BuildConfig.SIMKL_CLIENT_ID generado).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt \
        app/src/test/java/com/arkiv/player/data/catalog/SimklParseTest.kt
git commit -m "feat(anime): SimklApi (cross-ids + episodios + alt_titles) y key en BuildConfig"
```

---

## Task 4: AnimeEpisodeResolver (núcleo puro — queries, matches, numeración)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/AnimeEpisodeResolverTest.kt`

**Interfaces:**
- Produces:
  - `data class AnimeQueryInput(titles: List<String>, episode: Int, absoluteEpisode: Int? = null, tvdbSeason: Int? = null)`
  - `class SourceQuerySpec(val queries: List<String>)` con `fun matches(releaseName: String): Boolean` y `fun canonicalEpisode(releaseName: String): Int?`
  - `object AnimeEpisodeResolver { fun spec(input: AnimeQueryInput): SourceQuerySpec }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/arkiv/player/data/catalog/AnimeEpisodeResolverTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeEpisodeResolverTest {

    // AoT Final Season: usuario pide el ep 5 de la entrada; su absoluto es 64 (offset 59).
    private val aot = AnimeQueryInput(
        titles = listOf("Shingeki no Kyojin: The Final Season", "Attack on Titan Final Season"),
        episode = 5, absoluteEpisode = 64, tvdbSeason = 4,
    )

    @Test
    fun `genera queries con numero absoluto y con SxxEyy`() {
        val q = AnimeEpisodeResolver.spec(aot).queries
        assertTrue(q.any { it.contains("Shingeki no Kyojin") && it.contains("64") })
        assertTrue(q.any { it.contains("S04E05") })
    }

    @Test
    fun `matches acepta el release absoluto y el relativo del episodio correcto`() {
        val s = AnimeEpisodeResolver.spec(aot)
        assertTrue(s.matches("[SubsPlease] Shingeki no Kyojin (The Final Season) - 64 (1080p).mkv"))
        assertTrue(s.matches("[AnimeRG] Shingeki no Kyojin (The Final Season) 05 [1080p Dual Audio]"))
        assertTrue(s.matches("Attack on Titan S04E05 1080p Dual"))
    }

    @Test
    fun `matches rechaza otro episodio y otro anime`() {
        val s = AnimeEpisodeResolver.spec(aot)
        assertFalse(s.matches("[SubsPlease] Shingeki no Kyojin (The Final Season) - 63 (1080p).mkv"))
        assertFalse(s.matches("[SubsPlease] Frieren - 05 (1080p).mkv"))
    }

    @Test
    fun `long-runner sin offset matchea por numero absoluto de 4 digitos`() {
        val op = AnimeQueryInput(titles = listOf("One Piece"), episode = 1085, absoluteEpisode = null)
        val s = AnimeEpisodeResolver.spec(op)
        assertTrue(s.matches("[SubsPlease] One Piece - 1085 (1080p).mkv"))
        assertFalse(s.matches("[SubsPlease] One Piece - 1084 (1080p).mkv"))
    }

    @Test
    fun `canonicalEpisode normaliza absoluto y relativo al mismo numero de la entrada`() {
        val s = AnimeEpisodeResolver.spec(aot)
        // Ambos releases son el ep 5 de la entrada: uno viene como 64 (abs), otro como 05.
        assertEquals(5, s.canonicalEpisode("Shingeki no Kyojin (The Final Season) - 64"))
        assertEquals(5, s.canonicalEpisode("Shingeki no Kyojin (The Final Season) 05"))
    }

    @Test
    fun `primaryEpisodeNumber agrupa por el primer patron fuerte, null en packs`() {
        assertEquals(64, AnimeText.primaryEpisodeNumber("Shingeki no Kyojin (The Final Season) - 64"))
        assertNull(AnimeText.primaryEpisodeNumber("One Piece Complete Series [BD 1080p]"))
    }

    // helper local (evita depender de import estático en cada assert)
    private fun assertEquals(expected: Int, actual: Int?) =
        org.junit.Assert.assertEquals(expected as Int?, actual)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeEpisodeResolverTest"`
Expected: FAIL de compilación ("unresolved reference: AnimeEpisodeResolver").

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt`:
```kotlin
package com.arkiv.player.data.catalog

import java.text.Normalizer

/** Entrada para resolver las fuentes de UN episodio concreto de anime. */
data class AnimeQueryInput(
    val titles: List<String>,
    val episode: Int,
    /** Nº absoluto de serie (cuando difiere del de la entrada AniList). Null si no se pudo calcular. */
    val absoluteEpisode: Int? = null,
    /** Temporada TVDB (para armar SxxEyy). Null → se asume 1. */
    val tvdbSeason: Int? = null,
)

/**
 * Especificación de búsqueda de un episodio: las queries a lanzar y el predicado que decide si un
 * release corresponde (aceptando numeración absoluta o relativa) + normalización del nº de episodio.
 */
class SourceQuerySpec internal constructor(
    val queries: List<String>,
    private val episode: Int,
    private val absoluteEpisode: Int?,
    private val season: Int,
    private val anchors: List<List<String>>,
) {
    // Números que identifican ESTE episodio en cualquier release (el de la entrada y el absoluto).
    private val epNumbers: Set<Int> = setOfNotNull(episode, absoluteEpisode)

    /** ¿El release corresponde al título pedido Y a este episodio (abs o relativo)? */
    fun matches(releaseName: String): Boolean {
        val words = AnimeText.significantWords(releaseName).toSet()
        if (anchors.none { a -> words.containsAll(a) }) return false
        val found = AnimeText.episodeNumbersIn(releaseName)
        // SxxEyy explícito debe ser de nuestra temporada+episodio; los demás nº valen si coinciden.
        return found.any { it in epNumbers }
    }

    /** Nº de episodio de la entrada AniList al que pertenece el release (o null si es pack). */
    fun canonicalEpisode(releaseName: String): Int? {
        val found = AnimeText.episodeNumbersIn(releaseName)
        if (found.isEmpty()) return null
        // Si aparece el absoluto, mapearlo al nº de la entrada; si aparece el relativo, tal cual.
        if (absoluteEpisode != null && absoluteEpisode in found) return episode
        return found.firstOrNull { it == episode } ?: found.min()
    }
}

/** Construye la spec (queries + matcher) a partir de la entrada. Puro, sin red. */
object AnimeEpisodeResolver {
    fun spec(input: AnimeQueryInput): SourceQuerySpec {
        val titles = input.titles
            .map { it.trim() }
            .filter { it.isNotBlank() && !AnimeText.isCjkOnly(it) }
            .flatMap { listOf(it, AnimeText.stripAccents(it)) }
            .distinct()
        val season = (input.tvdbSeason ?: 1).coerceAtLeast(1)
        val ss = season.toString().padStart(2, '0')
        val ee = input.episode.toString().padStart(2, '0')
        val numbers = listOfNotNull(input.absoluteEpisode, input.episode).distinct()

        val queries = buildList {
            titles.take(4).forEach { t ->
                add(t)                                   // título pelado (trackers ES devuelven su catálogo)
                add("$t S${ss}E$ee")                     // relativo SxxEyy
                numbers.forEach { n ->
                    add("$t ${n.toString().padStart(2, '0')}")   // "One Piece 05"
                    if (n >= 100) add("$t $n")                   // "One Piece 1085"
                    add("$t - $n")                               // "One Piece - 1085"
                }
            }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val anchors = titles.map { AnimeText.significantWords(it).take(2) }.filter { it.isNotEmpty() }.distinct()
        return SourceQuerySpec(queries, input.episode, input.absoluteEpisode, season, anchors)
    }
}

/** Utilidades de texto/numeración compartidas por el resolver (puras). */
internal object AnimeText {
    private val STOPWORDS = setOf("the", "and", "los", "las", "del", "for", "una", "que", "season", "final", "part")

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun isCjkOnly(s: String): Boolean =
        s.isNotBlank() && s.none { it.code in 0x20..0x7F }

    fun significantWords(s: String): List<String> =
        stripAccents(s.lowercase()).split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }

    // Patrones "fuertes" de nº de episodio (orden = prioridad). Para agrupar (primaryEpisodeNumber).
    private val STRONG_PATTERNS = listOf(
        Regex("""\(e(\d{3,4})\)"""),                 // "(E1157)" absoluto
        Regex("""\bs\d{1,2}e(\d{1,3})\b"""),         // S04E05 (relativo)
        Regex("""\s-\s(\d{1,4})(?=\s|v\d|\[|\(|$)"""),// " - 1158 " / " - 05 ["
        Regex("""\bep?(?:isode)?\s?(\d{1,4})\b"""),  // EP1158 / Episode 5 / E05
    )
    // Nº "suelto" de 2-4 dígitos: SOLO para match (se cruza contra los nº conocidos), no para agrupar
    // (evita tomar una resolución/año como episodio). Los lookarounds ya descartan "1080p"/"x264".
    private val LOOSE_NUMBER = Regex("""(?<![a-z0-9])(\d{2,4})(?![a-z0-9])""")

    /** Todos los nº de episodio plausibles en el nombre (para matchear abs y relativo). */
    fun episodeNumbersIn(name: String): Set<Int> {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        val out = linkedSetOf<Int>()
        (STRONG_PATTERNS + LOOSE_NUMBER).forEach { p ->
            p.findAll(n).forEach { m ->
                m.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..9999 }?.let { out += it }
            }
        }
        return out
    }

    /** Nº de episodio "principal" (primer patrón fuerte), o null si es pack/batch. Para agrupar el browse. */
    fun primaryEpisodeNumber(name: String): Int? {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        for (p in STRONG_PATTERNS) {
            val v = p.find(n)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 1..9999) return v
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeEpisodeResolverTest"`
Expected: PASS (5 tests). Si algún caso de numeración falla, ajustar `PATTERNS`/`epNumbers` hasta que pasen — NO relajar los asserts.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeEpisodeResolver.kt \
        app/src/test/java/com/arkiv/player/data/catalog/AnimeEpisodeResolverTest.kt
git commit -m "feat(anime): AnimeEpisodeResolver (queries + matches + numeracion abs/relativa)"
```

---

## Task 5: TorrentSearchApi.searchAnime + prioridad de idioma explícita

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` (añadir `animeLangPriority`, `searchAnime`, y param `langPriority` en `runSearch`)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/AnimeLangPriorityTest.kt`

**Interfaces:**
- Consumes: `SourceQuerySpec` (Task 4), `TorrentLang`, `TorrentResult` (existentes).
- Produces: `fun animeLangPriority(lang: TorrentLang): Int` (top-level en el archivo) ; `TorrentSearchApi.searchAnime(spec: SourceQuerySpec, langs: Set<TorrentLang>, maxSizeBytes: Long = 0): List<TorrentResult>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/arkiv/player/data/catalog/AnimeLangPriorityTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeLangPriorityTest {
    @Test
    fun `orden de prioridad es Latino Castellano Dual Jap Otros Ingles`() {
        val ordered = listOf(
            TorrentLang.ENGLISH, TorrentLang.OTHER, TorrentLang.JAP_SUB,
            TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.LATINO,
        ).sortedBy { animeLangPriority(it) }
        assertEquals(
            listOf(
                TorrentLang.LATINO, TorrentLang.CASTELLANO, TorrentLang.DUAL,
                TorrentLang.JAP_SUB, TorrentLang.OTHER, TorrentLang.ENGLISH,
            ),
            ordered,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeLangPriorityTest"`
Expected: FAIL de compilación ("unresolved reference: animeLangPriority").

- [ ] **Step 3: Add the priority function** (top-level, tras la declaración del enum `TorrentLang`, ~línea 35 de `TorrentSearchApi.kt`)

```kotlin
/**
 * Prioridad de idioma para la ruta de ANIME (Latino > Castellano > Dual > Jap > Otros > Inglés).
 * Explícita a propósito: el `ordinal` del enum sirve a la ruta de Cine (Dual arriba de Castellano)
 * y no se reordena para no cambiarla.
 */
fun animeLangPriority(lang: TorrentLang): Int = when (lang) {
    TorrentLang.LATINO -> 0
    TorrentLang.CASTELLANO -> 1
    TorrentLang.DUAL -> 2
    TorrentLang.JAP_SUB -> 3
    TorrentLang.OTHER -> 4
    TorrentLang.ENGLISH -> 5
}
```

- [ ] **Step 4: Parametrizar `runSearch` con `langPriority`**

En `TorrentSearchApi.runSearch` (línea ~360), cambiar la firma para aceptar el comparador de idioma y usarlo en el `sortedWith`:

De:
```kotlin
    private suspend fun runSearch(
        queries: List<String>,
        langs: Set<TorrentLang>,
        relevance: (String) -> Boolean,
        maxSizeBytes: Long = 0,
    ): List<TorrentResult> = coroutineScope {
```
a:
```kotlin
    private suspend fun runSearch(
        queries: List<String>,
        langs: Set<TorrentLang>,
        relevance: (String) -> Boolean,
        maxSizeBytes: Long = 0,
        langPriority: (TorrentLang) -> Int = { it.ordinal },
    ): List<TorrentResult> = coroutineScope {
```
Y en el `sortedWith` (línea ~397), cambiar `compareBy<TorrentResult> { it.lang.ordinal }` por `compareBy<TorrentResult> { langPriority(it.lang) }`.

- [ ] **Step 5: Add `searchAnime`** (método público de `TorrentSearchApi`, junto a `searchEpisode`/`searchMovie`, ~línea 298)

```kotlin
    /**
     * Busca las fuentes de un episodio de anime usando la spec del resolver (queries multi-título
     * con numeración absoluta/relativa) y el ranking de idioma propio del anime.
     */
    suspend fun searchAnime(
        spec: SourceQuerySpec,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
    ): List<TorrentResult> =
        runSearch(withVariants(spec.queries), langs, spec::matches, maxSizeBytes, ::animeLangPriority)

    /**
     * Browse: TODAS las fuentes de un show (sin filtrar por episodio), para navegar shows en emisión
     * donde no se conoce el nº de episodios. Relevancia por ancla de título; ranking de idioma anime.
     */
    suspend fun searchAnimeBrowse(
        titles: List<String>,
        langs: Set<TorrentLang>,
        maxSizeBytes: Long = 0,
    ): List<TorrentResult> {
        val ts = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct().ifEmpty { return emptyList() }
        val queries = buildList {
            ts.forEach { add(it) }
            LANG_HINT_TOKENS.forEach { add("${ts.first()} $it") }
        }
        return runSearch(withVariants(queries), langs, animeTitleRelevance(ts), maxSizeBytes, ::animeLangPriority)
    }

    // Relevancia de browse: el release contiene las 2 primeras palabras clave de ALGÚN título.
    private fun animeTitleRelevance(titles: List<String>): (String) -> Boolean {
        val anchors = titles.map { significantWords(it).take(2) }.filter { it.isNotEmpty() }
        if (anchors.isEmpty()) return { true }
        return { name ->
            val words = significantWords(name).toSet()
            anchors.any { a -> words.containsAll(a) }
        }
    }
```

- [ ] **Step 6: Run test + compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.AnimeLangPriorityTest"`
Expected: PASS (1 test).
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt \
        app/src/test/java/com/arkiv/player/data/catalog/AnimeLangPriorityTest.kt
git commit -m "feat(anime): searchAnime + prioridad de idioma explicita (lat>cast>dual>jp>en)"
```

---

## Task 6: AniListApi.absoluteOffset (traversal de precuelas)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AniListApi.kt` (añadir método `absoluteOffset`)

**Interfaces:**
- Produces: `suspend fun AniListApi.absoluteOffset(anilistId: Long): Int` — suma de episodios de la cadena de precuelas TV (0 si no hay precuelas o falla).

- [ ] **Step 1: Add the traversal method** (dentro de `class AniListApi`, tras `details(...)`)

```kotlin
    /**
     * Nº de episodios ACUMULADOS antes de este anime en su cadena de precuelas TV. Sumado al nº de
     * episodio de la entrada da el nº ABSOLUTO de serie (ej. Shingeki Final Season ep 1 → abs 60).
     * Camina una sola cadena PREQUEL de formato TV; corta en ciclos o profundidad 12. 0 si falla.
     */
    suspend fun absoluteOffset(anilistId: Long): Int = withContext(Dispatchers.IO) {
        val seen = HashSet<Long>()
        var current = anilistId
        var total = 0
        var depth = 0
        while (depth++ < 12 && seen.add(current)) {
            val prequel = prequelOf(current) ?: break
            total += prequel.second
            current = prequel.first
        }
        total
    }

    // (idPrecuela, episodiosDePrecuela) del PREQUEL TV directo, o null.
    private fun prequelOf(id: Long): Pair<Long, Int>? {
        val query = """
            query(${'$'}id:Int){
              Media(id:${'$'}id,type:ANIME){
                relations{ edges{ relationType node{ id episodes format } } }
              }
            }
        """.trimIndent()
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("id", id)).toString()
        val json = runCatching {
            client.newCall(
                Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        return runCatching {
            val edges = JSONObject(json).optJSONObject("data")?.optJSONObject("Media")
                ?.optJSONObject("relations")?.optJSONArray("edges") ?: return null
            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                if (e.optString("relationType") != "PREQUEL") continue
                val node = e.optJSONObject("node") ?: continue
                if (node.optString("format") != "TV") continue
                return node.optLong("id").takeIf { it > 0 }?.let { it to node.optInt("episodes", 0) }
            }
            null
        }.getOrNull()
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AniListApi.kt
git commit -m "feat(anime): AniListApi.absoluteOffset (traversal de precuelas TV)"
```

---

## Task 7: AnimeSourceProvider (coordinador)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`

**Interfaces:**
- Consumes: `AniListApi` (+`absoluteOffset`), `SimklApi`, `AnimeMappingRepository`, `TmdbApi`, `TorrentSearchApi.searchAnime`, `AnimeEpisodeResolver`, `AnimeShow`, `TorrentResult`.
- Produces:
  - `data class AnimeSourceResult(val result: TorrentResult, val episode: Int?)`
  - `class AnimeSourceProvider(...)` con:
    - `suspend fun episodeSources(show: AnimeShow, episode: Int, langs: Set<TorrentLang>): List<AnimeSourceResult>` (ruta B)
    - `suspend fun browseSources(show: AnimeShow, langs: Set<TorrentLang>): List<AnimeSourceResult>` (ruta A)
    - `suspend fun browseTitles(show: AnimeShow): List<String>` (ayuda pública para el browse)

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`:
```kotlin
package com.arkiv.player.data.catalog

/** Un release de anime resuelto, con el episodio de la entrada AniList al que pertenece. */
data class AnimeSourceResult(val result: TorrentResult, val episode: Int?)

/**
 * Coordina las capas de metadata (Fribb + Simkl + TMDB + traversal AniList) para producir la spec
 * del resolver y delegar el fan-out multi-backend a [TorrentSearchApi.searchAnime]. Toda capa es
 * best-effort: si falla, se degrada a lo que haya (títulos de AniList + numeración de la entrada).
 */
class AnimeSourceProvider(
    private val aniListApi: AniListApi,
    private val simklApi: SimklApi,
    private val mappingRepo: AnimeMappingRepository,
    private val tmdbApi: TmdbApi,
    private val torrentSearchApi: TorrentSearchApi,
) {
    /** Fuentes de UN episodio concreto, con numeración absoluta resuelta y ranking de idioma. */
    suspend fun episodeSources(
        show: AnimeShow,
        episode: Int,
        langs: Set<TorrentLang>,
    ): List<AnimeSourceResult> {
        val mapping = runCatching { mappingRepo.mappingFor(show.id) }.getOrNull()
        val simkl = runCatching { simklApi.infoByAniList(show.id) }.getOrNull()
        val titles = buildTitles(show, mapping, simkl)
        val absolute = resolveAbsolute(show.id, episode)
        val spec = AnimeEpisodeResolver.spec(
            AnimeQueryInput(
                titles = titles,
                episode = episode,
                absoluteEpisode = absolute,
                tvdbSeason = mapping?.tvdbSeason,
            ),
        )
        return torrentSearchApi.searchAnime(spec, langs)
            .map { AnimeSourceResult(it, spec.canonicalEpisode(it.name)) }
    }

    /** Browse robusto: TODAS las fuentes del show agrupadas por episodio (para shows en emisión). */
    suspend fun browseSources(show: AnimeShow, langs: Set<TorrentLang>): List<AnimeSourceResult> =
        torrentSearchApi.searchAnimeBrowse(browseTitles(show), langs)
            .map { AnimeSourceResult(it, AnimeText.primaryEpisodeNumber(it.name)) }

    /** Conjunto de títulos para buscar: AniList (display + romaji) + español (TMDB) + alt (Simkl). */
    suspend fun browseTitles(show: AnimeShow): List<String> {
        val mapping = runCatching { mappingRepo.mappingFor(show.id) }.getOrNull()
        val simkl = runCatching { simklApi.infoByAniList(show.id) }.getOrNull()
        return buildTitles(show, mapping, simkl)
    }

    private suspend fun buildTitles(
        show: AnimeShow,
        mapping: AnimeMapping?,
        simkl: SimklAnimeInfo?,
    ): List<String> = buildList {
        add(show.title)
        add(show.searchTitle)
        // Título en español desde TMDB (aflora latino/castellano en Jackett). tmdbId de Fribb o Simkl.
        val tmdbId = mapping?.tmdbId ?: simkl?.tmdbId
        if (tmdbId != null) {
            runCatching { tmdbApi.detail("tv", tmdbId) }.getOrNull()?.let { addAll(it.searchTitles) }
        }
        simkl?.altTitles?.let { addAll(it) }
    }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    // Absoluto: preferir offset del dataset; si no, traversal AniList; si da 0 (sin precuelas), null.
    private suspend fun resolveAbsolute(anilistId: Long, episode: Int): Int? {
        val offset = runCatching { aniListApi.absoluteOffset(anilistId) }.getOrDefault(0)
        return if (offset > 0) offset + episode else null
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt
git commit -m "feat(anime): AnimeSourceProvider (coordina Fribb+Simkl+TMDB+AniList -> searchAnime)"
```

---

## Task 8: Cablear en AppGraph

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `SimklApi`, `AnimeMappingRepository`, `AnimeSourceProvider` (Tasks 2,3,7), `BuildConfig.SIMKL_CLIENT_ID` (Task 3).
- Produces: `AppGraph.simklApi`, `AppGraph.animeMappingRepository`, `AppGraph.animeSourceProvider`.

- [ ] **Step 1: Add imports** (junto a los otros imports de `data.catalog`, ~línea 6-14)

```kotlin
import com.arkiv.player.data.catalog.AnimeMappingRepository
import com.arkiv.player.data.catalog.AnimeSourceProvider
import com.arkiv.player.data.catalog.SimklApi
```

- [ ] **Step 2: Add singletons** (tras `val aniListApi ...`, línea 44)

```kotlin
    val simklApi: SimklApi by lazy { SimklApi(clientId = BuildConfig.SIMKL_CLIENT_ID) }
    val animeMappingRepository: AnimeMappingRepository by lazy {
        AnimeMappingRepository(cacheDir = appContext.filesDir)
    }
    val animeSourceProvider: AnimeSourceProvider by lazy {
        AnimeSourceProvider(aniListApi, simklApi, animeMappingRepository, tmdbApi, torrentSearchApi)
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(anime): cablear SimklApi + AnimeMappingRepository + AnimeSourceProvider"
```

---

## Task 9: Integrar en AnimeShowDetailScreen (browse + por episodio)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`

**Interfaces:**
- Consumes: `graph.animeSourceProvider.episodeSources(...)`, `AnimeSourceResult`, `TorrentResult`, `graph.torrentSearchApi.resolveSource(...)`, `TorrentSource` (existente), `graph.torrentEngine.resolveMagnet/resolveTorrent`, `graph.repository.addAnimeEpisode`.

Cambio de modelo de datos: la pantalla deja de usar `AnimeApi`/`AnimeRelease`; ahora arma la lista de episodios desde `show.episodes` (AniList) y por cada episodio abierto consulta `episodeSources`. Los langs por defecto: todos (la prioridad los ordena, no los excluye).

- [ ] **Step 1: Reemplazar el estado y la carga de releases**

Sustituir el bloque de estado + `LaunchedEffect` (líneas 56-75) por:
```kotlin
    var show by remember { mutableStateOf<AnimeShow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // Fuentes cargadas por episodio (clave = nº de episodio de la entrada AniList).
    val sourcesByEp = remember { mutableStateMapOf<Int, List<AnimeSourceResult>>() }
    val loadingEp = remember { mutableStateMapOf<Int, Boolean>() }

    LaunchedEffect(anilistId) {
        loading = true
        show = runCatching { graph.aniListApi.details(anilistId) }.getOrNull()
        loading = false
    }

    fun loadEpisode(ep: Int) {
        if (sourcesByEp.containsKey(ep) || loadingEp[ep] == true) return
        val s = show ?: return
        loadingEp[ep] = true
        scope.launch {
            val res = runCatching {
                graph.animeSourceProvider.episodeSources(s, ep, langs = emptySet())
            }.getOrDefault(emptyList())
            sourcesByEp[ep] = res
            loadingEp[ep] = false
        }
    }
```

- [ ] **Step 2: Reemplazar `play(r: AnimeRelease)` por `play(r: TorrentResult)`**

Sustituir la función `play` (líneas 77-110) por:
```kotlin
    fun play(r: TorrentResult) {
        val s = show ?: return
        preparing = true
        error = null
        scope.launch {
            val source = graph.torrentSearchApi.resolveSource(r)
            val meta = when (source) {
                is com.arkiv.player.data.catalog.TorrentSource.Magnet ->
                    graph.torrentEngine.resolveMagnet(source.uri)
                is com.arkiv.player.data.catalog.TorrentSource.TorrentFile ->
                    graph.torrentEngine.resolveTorrent(source.bytes)
                null -> null
            }
            if (meta == null) {
                preparing = false
                error = "No se pudo abrir el torrent (puede no tener seeds ahora)"
                return@launch
            }
            val videos = graph.torrentEngine.videoFiles(meta)
                .ifEmpty { graph.torrentEngine.pickVideo(meta)?.let { listOf(it) } ?: emptyList() }
            val video = videos.firstOrNull()
            if (video == null) {
                preparing = false
                error = "El torrent no tiene video reproducible"
                return@launch
            }
            val epId = graph.repository.addAnimeEpisode(
                anilistId = anilistId,
                showTitle = s.title,
                posterUrl = s.posterUrl,
                episodeName = r.name,
                infoHashHex = meta.infoHashHex,
                infoBytes = meta.infoBytes,
                fileIndex = video.index,
                fileSizeBytes = video.sizeBytes,
            )
            preparing = false
            onPlay(epId)
        }
    }
```

- [ ] **Step 3: Reemplazar la lista de episodios (bloque `else ->` de releases)**

Sustituir el bloque que hoy hace `loadingReleases`/`releases.isEmpty()`/`groupReleasesByEpisode` (líneas 168-219) por una lista de episodios `1..show.episodes` (o hasta 24 si `episodes<=0`, para shows en emisión) que cargan sus fuentes al expandirse:
```kotlin
                    val total = if (s.episodes > 0) s.episodes else 24
                    (1..total).forEach { ep ->
                        val open = expanded[ep] ?: false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val now = !open
                                    expanded[ep] = now
                                    if (now) loadEpisode(ep)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null, tint = Color.White,
                            )
                            Text(
                                "Episodio $ep",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            val count = sourcesByEp[ep]?.size
                            if (count != null) Text(
                                "$count",
                                color = ArkivTextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (open) {
                            when {
                                loadingEp[ep] == true -> Row(
                                    modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed)
                                    Text("Buscando fuentes…", color = ArkivTextSecondary)
                                }
                                sourcesByEp[ep].isNullOrEmpty() -> Text(
                                    "Sin fuentes para este episodio.",
                                    color = ArkivTextSecondary,
                                    modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
                                )
                                else -> sourcesByEp[ep]!!.forEach { src ->
                                    ReleaseRow(result = src.result, enabled = !preparing) { play(src.result) }
                                }
                            }
                        }
                    }
```

- [ ] **Step 4: Actualizar `ReleaseRow` para `TorrentResult`**

Reemplazar el composable `ReleaseRow` (líneas 274-300) por una versión que muestre nombre + idioma + seeds/size desde `TorrentResult`:
```kotlin
@Composable
private fun ReleaseRow(result: TorrentResult, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 32.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ArkivRed)
        Column(Modifier.weight(1f)) {
            Text(
                result.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${result.lang.label}  ·  ${result.seeders} seeds" +
                    if (result.sizeLabel.isNotBlank()) "  ·  ${result.sizeLabel}" else "",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
```

- [ ] **Step 5: Borrar el código muerto**

Eliminar de `AnimeShowDetailScreen.kt`: el `import ...AnimeRelease`, `EpisodeGroup`, `EPISODE_PATTERNS`, `episodeNumberOf`, `groupReleasesByEpisode` (líneas 43, 245-272) — su lógica de numeración vive ahora en `AnimeEpisodeResolver`/`AnimeText`. Añadir `import com.arkiv.player.data.catalog.AnimeSourceResult` y `import com.arkiv.player.data.catalog.TorrentResult`.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (sin referencias a `AnimeRelease`/`animeApi` en esta pantalla).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt
git commit -m "feat(anime): pantalla usa AnimeSourceProvider (por episodio, multi-backend, ranking idioma)"
```

---

## Task 10: Modo browse "Todos los releases" (ruta A) en la pantalla

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`

**Interfaces:**
- Consumes: `graph.animeSourceProvider.browseSources(show, langs)` (Task 7), `AnimeSourceResult`, `play(TorrentResult)` (Task 9).

Añade un selector de modo **[Por episodio] [Todos]** encima de la lista. "Por episodio" = ruta B (Task 9). "Todos" = ruta A: un solo fetch multi-backend del show, agrupado por episodio (`primaryEpisodeNumber`), robusto para shows en emisión.

- [ ] **Step 1: Añadir estado de browse** (junto al estado de la Task 9, tras `val loadingEp = ...`)

```kotlin
    var mode by remember { mutableStateOf("episodes") } // "episodes" (B) | "all" (A)
    var browse by remember { mutableStateOf<List<AnimeSourceResult>?>(null) }
    var loadingBrowse by remember { mutableStateOf(false) }

    fun loadBrowse() {
        if (browse != null || loadingBrowse) return
        val s = show ?: return
        loadingBrowse = true
        scope.launch {
            browse = runCatching { graph.animeSourceProvider.browseSources(s, langs = emptySet()) }
                .getOrDefault(emptyList())
            loadingBrowse = false
        }
    }
```

- [ ] **Step 2: Añadir el selector de modo** (dentro de `Column(Modifier.padding(16.dp))`, justo ANTES del `Text("Episodios / releases", ...)`)

```kotlin
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("episodes" to "Por episodio", "all" to "Todos").forEach { (m, label) ->
                            Text(
                                label,
                                color = if (mode == m) Color.White else ArkivTextSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (mode == m) ArkivRed else ArkivSurfaceHigh)
                                    .clickable { mode = m; if (m == "all") loadBrowse() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
```

- [ ] **Step 3: Envolver la lista por-episodio y añadir la lista "Todos"**

En el bloque que renderiza la lista `(1..total).forEach { ep -> ... }` (Task 9 Step 3), envolverlo en `if (mode == "episodes") { ... }` y añadir a continuación el modo "all":
```kotlin
                    if (mode == "all") {
                        when {
                            loadingBrowse -> Row(
                                modifier = Modifier.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = ArkivRed)
                                Text("Buscando releases…", color = ArkivTextSecondary)
                            }
                            browse.isNullOrEmpty() -> Text(
                                "No se encontraron torrents para este anime.",
                                color = ArkivTextSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            else -> {
                                val groups = remember(browse) {
                                    browse!!.groupBy { it.episode }
                                        .toSortedMap(compareBy { it ?: Int.MAX_VALUE })
                                }
                                groups.forEach { (ep, items) ->
                                    val key = ep ?: -1
                                    val open = expanded[key] ?: (ep == groups.keys.firstOrNull())
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { expanded[key] = !open }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null, tint = Color.White,
                                        )
                                        Text(
                                            if (ep != null) "Episodio $ep" else "Packs / otros",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${items.size}",
                                            color = ArkivTextSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    if (open) items.forEach { src ->
                                        ReleaseRow(result = src.result, enabled = !preparing) { play(src.result) }
                                    }
                                }
                            }
                        }
                    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt
git commit -m "feat(anime): modo browse 'Todos los releases' (ruta A) con selector de modo"
```

---

## Task 11: Verificación end-to-end en dispositivo

**Files:** (ninguno — verificación)

- [ ] **Step 1: Suite de tests completa**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (incluye los 4 nuevos test classes, sin romper los existentes).

- [ ] **Step 2: Instalar en el celular (ADB WiFi) y probar 3 casos**

Run: `./gradlew :app:installDebug` (ver memoria de ADB WiFi para conectar el S24+).
Verificar manualmente en la app (modos **Por episodio** y **Todos**):
- Frieren (temporada única): episodios abren fuentes; aparece **Dual** arriba de Jap-sub.
- Attack on Titan Final Season: el episodio muestra releases tanto `- 64` como `05` bajo el mismo episodio.
- One Piece (en emisión, `episodes=null`): en modo **Todos** aparece el catálogo agrupado por episodio (ruta A robusta); en **Por episodio** la lista base 1..24.
Confirmar que al tocar una fuente reproduce (pasa por `resolveSource` → engine → player).

- [ ] **Step 3: Commit final (si hubo ajustes)**

```bash
git add -A && git commit -m "fix(anime): ajustes tras verificacion end-to-end"
```

---

## Notas de implementación

- **Degradación:** cada capa de red va en `runCatching`; sin Fribb/Simkl/TMDB el resolver usa títulos de AniList + numeración de la entrada. Nunca bloquea el play.
- **Sin migración de DB:** el dataset Fribb se cachea en `filesDir` (no en Room), así que no hay bump de versión ni `Migration` nuevo.
- **`langs = emptySet()`** en la UI = mostrar todos los idiomas ordenados por prioridad (la exclusión por idioma queda para un filtro futuro; hoy la prioridad basta).
