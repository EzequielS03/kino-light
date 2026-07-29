# Búsqueda por Fases — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar una pantalla nueva de búsqueda guiada por fases (texto → cards → temporada/capítulo opcional → resultados multi-fuente → reproducir/guardar pack), reutilizando al máximo las APIs y flujos ya existentes.

**Architecture:** Wizard dedicado (`SearchScreen` + `SearchWizardViewModel`) con máquina de estados `QUERY → REFINE (solo serie) → RESULTS`. No depende de `CineDetailScreen`, pero comparte modelos (`PlaySource`) y APIs (`TmdbApi`, `TorrentSearchApi`, `WebSourceEngine`, `TorrentEngine`, `ArkivRepository`, `PackResolver`, `SyncManager`). La metadata de la card viaja en un `CardContext` y enriquece lo que se guarda en la biblioteca (descripción + label de capítulo `Serie · SxxExx · Título`).

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines/Flow, Room, JUnit4, Gradle.

## Global Constraints

- **Commits con identidad `lordmacu`:** `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit …`. Nunca coautoría de Claude. Nunca `git add -A` (sesiones concurrentes comparten el working tree) — agregar solo los archivos de la tarea.
- **Tests:** JUnit4 (`org.junit.Test`, `org.junit.Assert.*`). Correr con `./gradlew :app:testDebugUnitTest --tests "<FQN>"`.
- **Compilar:** `./gradlew :app:compileDebugKotlin`. Instalar en device: `./gradlew :app:installDebug`.
- **Reutilización primero:** antes de escribir lógica nueva de búsqueda/reproducción/pack, reusar lo existente (ver spec, sección "Reutilización").
- **Sin regresiones:** los llamadores actuales de `ArkivRepository` (CineDetail/ShowDetail/AnimeShowDetail) deben seguir compilando y funcionando (parámetros nuevos con valor por defecto).

---

## Estructura de archivos

- **Crear:**
  - `app/src/main/java/com/arkiv/player/ui/search/SearchWizardViewModel.kt` — estado + orquestación del wizard.
  - `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt` — UI por fase.
  - `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt` — modelos `CardContext`, `TitleCard`, `SearchPhase`, `SourceQueryMode`.
  - `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt` — `PlaySource` + `SourceSection`/`SourceRow` promovidos desde `CineDetailScreen`.
  - Tests: `TmdbSearchMultiTest.kt`, `SeriesEpisodeLabelTest.kt`, `SearchPhaseTest.kt`, `SourceQueryModeTest.kt`.
- **Modificar:**
  - `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt` — `searchMulti` + `parseMultiItem`.
  - `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` — enriquecer `add*` con `description` + label.
  - `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` — quitar `PlaySource`/`SourceRow`/`SourceSection` (ahora importados).
  - `app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt` — icono de lupa → `onOpenSearch`.
  - `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` — ruta `search` + wiring del icono.

---

## Task 1: `TmdbApi.searchMulti` (Fase 1 — TMDB mixto)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/TmdbSearchMultiTest.kt`

**Interfaces:**
- Consumes: `parseItem(o, type)`, `imgUrl`, `get`, `enc`, `auth`, `base` (privados existentes).
- Produces:
  - `suspend fun searchMulti(query: String, page: Int = 1): List<TmdbItem>`
  - `internal fun parseMultiItem(o: JSONObject): TmdbItem?`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbSearchMultiTest {
    private val api = TmdbApi(apiKey = "test")

    @Test fun `mapea movie con titulo y year`() {
        val o = JSONObject("""{"media_type":"movie","id":1,"title":"Superman","release_date":"2025-07-11","poster_path":"/p.jpg"}""")
        val item = api.parseMultiItem(o)!!
        assertEquals(1, item.id)
        assertEquals("movie", item.type)
        assertEquals("Superman", item.title)
        assertEquals("2025", item.year)
        assertEquals(false, item.isSeries)
    }

    @Test fun `mapea tv usando name y first_air_date`() {
        val o = JSONObject("""{"media_type":"tv","id":2,"name":"Superman & Lois","first_air_date":"2021-02-23"}""")
        val item = api.parseMultiItem(o)!!
        assertEquals("tv", item.type)
        assertEquals("Superman & Lois", item.title)
        assertEquals("2021", item.year)
        assertEquals(true, item.isSeries)
    }

    @Test fun `descarta person y media_type desconocido`() {
        assertNull(api.parseMultiItem(JSONObject("""{"media_type":"person","id":3,"name":"Actor X"}""")))
        assertNull(api.parseMultiItem(JSONObject("""{"media_type":"collection","id":4,"name":"Colección"}""")))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.TmdbSearchMultiTest"`
Expected: FAIL con "unresolved reference: parseMultiItem".

- [ ] **Step 3: Implementar `parseMultiItem` + `searchMulti`**

En `TmdbApi.kt`, después de `search(...)` (cerca de la línea 90), agregar:

```kotlin
/** Búsqueda mixta (pelis + series) para el wizard de búsqueda. Ignora 'person' y colecciones. */
suspend fun searchMulti(query: String, page: Int = 1): List<TmdbItem> = withContext(Dispatchers.IO) {
    val q = query.trim()
    if (q.isBlank()) return@withContext emptyList()
    val json = get("$base/search/multi?$auth&query=${enc(q)}&page=$page&include_adult=false")
        ?: return@withContext emptyList()
    runCatching {
        val results = JSONObject(json).optJSONArray("results") ?: org.json.JSONArray()
        (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { parseMultiItem(it) } }
    }.getOrDefault(emptyList())
}

/** Mapea un ítem de /search/multi según su media_type. Devuelve null para person/otros. */
internal fun parseMultiItem(o: JSONObject): TmdbItem? {
    val type = when (o.optString("media_type")) {
        "movie" -> "movie"
        "tv" -> "tv"
        else -> return null
    }
    return parseItem(o, type)
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.TmdbSearchMultiTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): TmdbApi.searchMulti (pelis + series mezcladas)" -- \
  app/src/main/java/com/arkiv/player/data/catalog/TmdbApi.kt \
  app/src/test/java/com/arkiv/player/data/catalog/TmdbSearchMultiTest.kt
```

---

## Task 2: Enriquecer el guardado en `ArkivRepository`

Objetivo: que la card enriquezca lo guardado — descripción del ítem y label de capítulo
`Serie · SxxExx · Título`. Cambios retrocompatibles (params con default).

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`addSeriesEpisode:292`, `addSeriesEpisodeMagnet:388`, `addTorrent:175`, `addTorrentMagnet:363`)
- Test: `app/src/test/java/com/arkiv/player/data/SeriesEpisodeLabelTest.kt`

**Interfaces:**
- Produces:
  - `object SeriesEpisodeLabel { fun format(showTitle: String, season: Int, episode: Int, episodeName: String): String }`
  - `addSeriesEpisode(..., description: String? = null)` (nuevo último param)
  - `addSeriesEpisodeMagnet(..., description: String? = null)`
  - `addTorrent(..., description: String? = null)`
  - `addTorrentMagnet(..., description: String? = null)`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesEpisodeLabelTest {
    @Test fun `formato con serie temporada capitulo y titulo`() {
        assertEquals("Superman & Lois · S02E05 · El regreso",
            SeriesEpisodeLabel.format("Superman & Lois", 2, 5, "El regreso"))
    }

    @Test fun `pad de dos digitos`() {
        assertEquals("Loki · S01E01 · Glorious Purpose",
            SeriesEpisodeLabel.format("Loki", 1, 1, "Glorious Purpose"))
    }

    @Test fun `sin titulo de episodio omite el ultimo segmento`() {
        assertEquals("Loki · S01E03", SeriesEpisodeLabel.format("Loki", 1, 3, ""))
    }

    @Test fun `numeros de tres digitos no se truncan`() {
        assertEquals("One Piece · S01E1024 · x",
            SeriesEpisodeLabel.format("One Piece", 1, 1024, "x"))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SeriesEpisodeLabelTest"`
Expected: FAIL con "unresolved reference: SeriesEpisodeLabel".

- [ ] **Step 3: Crear el formateador**

Crear `app/src/main/java/com/arkiv/player/data/SeriesEpisodeLabel.kt`:

```kotlin
package com.arkiv.player.data

/** Label de capítulo guardado: "Serie · S02E05 · Título" (o sin el último segmento si no hay título). */
object SeriesEpisodeLabel {
    fun format(showTitle: String, season: Int, episode: Int, episodeName: String): String {
        val se = "S%02dE%02d".format(season, episode)
        val head = "${showTitle.trim()} · $se"
        return if (episodeName.isBlank()) head else "$head · ${episodeName.trim()}"
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SeriesEpisodeLabelTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Usar el formateador y `description` en `addSeriesEpisode`**

En `ArkivRepository.addSeriesEpisode` (línea ~292): agregar `description: String? = null` como último
parámetro. Cambiar `description = null` del `ItemEntity` por `description = description`. Reemplazar:

```kotlin
val label = "T${season} · E${episode}" + if (episodeName.isNotBlank()) "  ${episodeName}" else ""
```

por:

```kotlin
val label = com.arkiv.player.data.SeriesEpisodeLabel.format(showTitle, season, episode, episodeName)
```

- [ ] **Step 6: Repetir en `addSeriesEpisodeMagnet`, `addTorrent`, `addTorrentMagnet`**

En cada método: agregar `description: String? = null` como último parámetro y setear
`description = description` en el `ItemEntity` (hoy es `null`). En `addSeriesEpisodeMagnet` usar
además `SeriesEpisodeLabel.format(...)` para el label del episodio (mismo patrón que Step 5).
`addTorrent`/`addTorrentMagnet` son ítems simples: solo enriquecer `description` (no llevan S/E).

- [ ] **Step 7: Verificar compilación (callers actuales siguen válidos)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (CineDetail/ShowDetail no pasan `description` → usan el default).

- [ ] **Step 8: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): enriquecer guardado (description + label Serie·SxxExx·Titulo)" -- \
  app/src/main/java/com/arkiv/player/data/ArkivRepository.kt \
  app/src/main/java/com/arkiv/player/data/SeriesEpisodeLabel.kt \
  app/src/test/java/com/arkiv/player/data/SeriesEpisodeLabelTest.kt
```

---

## Task 3: Promover `PlaySource` + `SourceRow`/`SourceSection` a archivo compartido

Refactor sin cambio de comportamiento: sacar de `CineDetailScreen` las piezas que el wizard también
usará, para tener una sola fuente de verdad.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` (quitar las definiciones movidas, líneas ~70-78 y `SourceSection`/`SourceRow` ~412-500)

**Interfaces:**
- Produces (ahora públicos en el nuevo archivo, mismo `package com.arkiv.player.ui.catalog`):
  - `sealed interface PlaySource { data class Torrent(val result: TorrentResult); data class Archive(val item: ArchiveSearchResult); data class Web(val result: WebResult) }`
  - `@Composable fun SourceSection(title: String, color: Color, items: List<PlaySource>, loading: Boolean, expanded: Boolean, onToggle: () -> Unit, enabled: Boolean, onPlay: (PlaySource) -> Unit)`
  - `@Composable fun SourceRow(source: PlaySource, enabled: Boolean, onClick: () -> Unit)`

- [ ] **Step 1: Crear `PlaySources.kt` con las definiciones movidas**

Copiar **verbatim** desde `CineDetailScreen.kt` el `sealed interface PlaySource` (quitando `private`)
y las funciones `SourceSection` y `SourceRow` (quitando `private`), con sus imports necesarios
(`TorrentResult`, `ArchiveSearchResult`, `WebResult`, colores de tema, íconos). Mantener el
`package com.arkiv.player.ui.catalog`. Firmas exactas según el bloque "Produces" de arriba
(revisar los parámetros actuales de `SourceSection`/`SourceRow` en `CineDetailScreen` y respetarlos).

- [ ] **Step 2: Borrar las definiciones duplicadas en `CineDetailScreen.kt`**

Eliminar de `CineDetailScreen.kt` el `sealed interface PlaySource` (~70-78) y las funciones
`SourceSection`/`SourceRow` (~412-500). Como están en el mismo package, no hace falta import nuevo.

- [ ] **Step 3: Verificar compilación (comportamiento intacto)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `CineDetailScreen` usa las mismas definiciones, ahora compartidas.

- [ ] **Step 4: Verificación en device (sin regresión visual)**

Run: `./gradlew :app:installDebug` y abrir Catálogo → una serie → seleccionar capítulo → confirmar
que la hoja de resultados TORRENT/WEB/ARCHIVE se ve y reproduce igual que antes.

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "refactor(busqueda): promover PlaySource/SourceRow/SourceSection a archivo compartido" -- \
  app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt \
  app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
```

---

## Task 4: Modelos y lógica pura del wizard (`CardContext`, `SearchPhase`)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/search/SearchPhaseTest.kt`

**Interfaces:**
- Produces:
  - `enum class SearchPhase { QUERY, REFINE, RESULTS }`
  - `fun phaseAfterPick(type: String): SearchPhase` — `"movie" → RESULTS`, `"series"/"tv" → REFINE`.
  - `data class TitleCard(val id: Int?, val type: String, val title: String, val posterUrl: String, val year: String, val overview: String?, val isAnime: Boolean = false)`
  - `data class CardContext(...)` (según spec).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPhaseTest {
    @Test fun `pelicula salta a resultados`() {
        assertEquals(SearchPhase.RESULTS, phaseAfterPick("movie"))
    }
    @Test fun `serie va a refinar temporada y capitulo`() {
        assertEquals(SearchPhase.REFINE, phaseAfterPick("series"))
        assertEquals(SearchPhase.REFINE, phaseAfterPick("tv"))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.SearchPhaseTest"`
Expected: FAIL con "unresolved reference".

- [ ] **Step 3: Crear los modelos + `phaseAfterPick`**

Crear `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt`:

```kotlin
package com.arkiv.player.ui.search

enum class SearchPhase { QUERY, REFINE, RESULTS }

/** Tras elegir una card: película → resultados directo; serie/tv → paso de temporada/capítulo. */
fun phaseAfterPick(type: String): SearchPhase =
    if (type == "movie") SearchPhase.RESULTS else SearchPhase.REFINE

/** Card de la Fase 1 (TMDB o anime). */
data class TitleCard(
    val id: Int?,            // tmdbId; null para anime
    val type: String,        // "movie" | "series"
    val title: String,
    val posterUrl: String,
    val year: String,
    val overview: String?,
    val isAnime: Boolean = false,
)

/** Metadata que viaja desde la card elegida hasta reproducir/guardar. */
data class CardContext(
    val title: String,
    val overview: String?,
    val posterUrl: String,
    val type: String,                 // "movie" | "series"
    val tmdbId: Int?,
    val tmdbType: String?,            // "movie" | "tv" | null (anime)
    val searchTitles: List<String>,
    val year: String,
    val isAnime: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.SearchPhaseTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): modelos CardContext/TitleCard y logica de fase" -- \
  app/src/main/java/com/arkiv/player/ui/search/CardContext.kt \
  app/src/test/java/com/arkiv/player/ui/search/SearchPhaseTest.kt
```

---

## Task 5: Deep-link S/E en las pantallas de detalle (handoff targets)

Hacer que `CineDetailScreen` y `AnimeShowDetailScreen` acepten un episodio/temporada **opcional** por
deep-link: si viene, auto-seleccionan y **auto-abren los resultados** de ese episodio. Sin args, se
comportan igual que hoy (sin regresión). De paso, pasar `description` a sus `add*` (enriquecimiento).

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (rutas con args opcionales)

**Interfaces:**
- `CineDetailScreen(tmdbId: Int, type: String, onPlay, onBack, onOpenItem = {}, deepLinkSeason: Int? = null, deepLinkEpisode: Int? = null)`
- `AnimeShowDetailScreen(anilistId: Long, ..., deepLinkEpisode: Int? = null)` (respetar los params actuales, agregar el nuevo al final con default)

- [ ] **Step 1: `CineDetailScreen` — params opcionales + auto-open**

Agregar `deepLinkSeason: Int? = null, deepLinkEpisode: Int? = null` al final de la firma (línea 68).
Tras cargar episodios, si hay deep-link, seleccionar la temporada y abrir los resultados de ese episodio.
Reutiliza `openSources(ep)` (existe, línea ~158) y `selectedSeason`/`episodes`. Añadir un `LaunchedEffect`
que dispare **una sola vez** cuando `episodes` ya esté cargado para la temporada objetivo:

```kotlin
var deepLinkHandled by remember { mutableStateOf(false) }
LaunchedEffect(deepLinkSeason) {
    if (deepLinkSeason != null && selectedSeason != deepLinkSeason) selectedSeason = deepLinkSeason
}
LaunchedEffect(episodes, deepLinkEpisode) {
    if (deepLinkHandled) return@LaunchedEffect
    val epNo = deepLinkEpisode ?: return@LaunchedEffect
    val ep = episodes.firstOrNull { it.episode == epNo } ?: return@LaunchedEffect
    deepLinkHandled = true
    openSources(ep)
}
```
(Ajusta los nombres reales de las variables/propiedades del episodio — `it.episode`/`ep` — según el tipo `TmdbEpisode`.)

- [ ] **Step 2: `CineDetailScreen` — pasar `description` en las add***

En su `play(...)` (líneas ~175-203), pasar `description = detail?.overview` (o `d.overview`) a las
llamadas `addSeriesEpisode(...)`, `addSeriesEpisodeMagnet(...)`, `addTorrent(...)`, `addTorrentMagnet(...)`.
Usa el nombre real de la variable de detalle en ese scope (`d`). El param es el último y con default,
así que solo agregas `, description = d.overview` donde corresponda.

- [ ] **Step 3: `AnimeShowDetailScreen` — episodio opcional + auto-open**

Agregar `deepLinkEpisode: Int? = null` al final de la firma (línea ~80). Cuando venga, tras cargar el
`show`, disparar la búsqueda de ese episodio (reutiliza la función que ya lanza `episodeSourcesFlow`
para un episodio — localízala; en el código es la que llena `sourcesByEp`/`episodeJobs`). Patrón:

```kotlin
var animeDeepLinkHandled by remember { mutableStateOf(false) }
LaunchedEffect(show, deepLinkEpisode) {
    if (animeDeepLinkHandled) return@LaunchedEffect
    val s = show ?: return@LaunchedEffect
    val ep = deepLinkEpisode ?: return@LaunchedEffect
    animeDeepLinkHandled = true
    // llamar a la función existente que abre/lanza las fuentes de ESE episodio (mode "episodes")
    loadEpisode(ep)   // <- usar el nombre real de la función existente
}
```
(No inventes una nueva ruta de búsqueda: usa la que ya existe para "un episodio" en esa pantalla.)

- [ ] **Step 4: Rutas con args opcionales en `ArkivRoot`**

Cambiar las rutas de detalle para aceptar S/E opcional como query args, sin romper las llamadas actuales
(los args ausentes → null):

```kotlin
composable(
    "cine/{type}/{tmdbId}?season={season}&episode={episode}",
    arguments = listOf(
        navArgument("season") { nullable = true; type = NavType.StringType; defaultValue = null },
        navArgument("episode") { nullable = true; type = NavType.StringType; defaultValue = null },
    ),
) { entry ->
    // ...tmdbId/type como hoy...
    val season = entry.arguments?.getString("season")?.toIntOrNull()
    val episode = entry.arguments?.getString("episode")?.toIntOrNull()
    CineDetailScreen(tmdbId = ..., type = ..., onPlay = ..., onBack = ..., onOpenItem = ...,
        deepLinkSeason = season, deepLinkEpisode = episode)
}
```
Análogo para `catalog_anime/{anilistId}?episode={episode}` → `deepLinkEpisode`. Las navegaciones
existentes (`cine/${type}/${id}` sin query) siguen funcionando: los args quedan null.

- [ ] **Step 5: Compilar**

`./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 6: Verificación en device (deep-link)**

`./gradlew :app:installDebug`. Desde el catálogo, abrir una serie por la ruta normal (sin args) → debe
verse igual que antes. (El deep-link real se prueba end-to-end en Task 7.)

- [ ] **Step 7: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): deep-link S/E opcional en detalle (auto-abre resultados) + description" -- \
  app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt \
  app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
```

---

## Task 6: `SearchViewModel` (Fase 1 unificada + REFINE + handoff)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt` (revisar `TitleCard`)
- Test: `app/src/test/java/com/arkiv/player/ui/search/HandoffRouteTest.kt`

**Interfaces:**
- `TitleCard` revisado: `data class TitleCard(val kind: String, val tmdbId: Int?, val anilistId: Long?, val title: String, val posterUrl: String, val year: String, val overview: String?)` donde `kind ∈ {"movie","series","anime"}`.
- `fun handoffRouteFor(card: TitleCard, season: Int?, episode: Int?): String` (pura, testeable).
- `class SearchViewModel(...) : ViewModel()` con StateFlows: `query`, `phase` (QUERY/REFINE), `titleResults: List<TitleCard>`, `directResults: List<PlaySource>`, flags de carga; acciones `search(q)`, `pickTitle(card)`, `setSeasonEpisode(s,e)`.

- [ ] **Step 1: Revisar `TitleCard` (soporta tmdb Int y anilist Long)**

En `CardContext.kt`, reemplazar `TitleCard` por:

```kotlin
/** Card de la Fase 1. kind: "movie" | "series" (TMDB) | "anime" (AniList). */
data class TitleCard(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val overview: String?,
)
```
(Conserva `SearchPhase`, `phaseAfterPick`. `CardContext` puede quedarse; ya no se usa en el híbrido — no lo borres para no romper imports si algo lo referencia, pero no lo extiendas.)

- [ ] **Step 2: Escribir el test puro que falla (`handoffRouteFor`)**

`app/src/test/java/com/arkiv/player/ui/search/HandoffRouteTest.kt`:

```kotlin
package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffRouteTest {
    private fun tmdb(kind: String) = TitleCard(kind, 42, null, "X", "", "2025", null)
    private fun anime() = TitleCard("anime", null, 100L, "Y", "", "2020", null)

    @Test fun `pelicula sin season episode`() {
        assertEquals("cine/movie/42", handoffRouteFor(tmdb("movie"), null, null))
    }
    @Test fun `serie sin season episode`() {
        assertEquals("cine/tv/42", handoffRouteFor(tmdb("series"), null, null))
    }
    @Test fun `serie con season y episode`() {
        assertEquals("cine/tv/42?season=2&episode=5", handoffRouteFor(tmdb("series"), 2, 5))
    }
    @Test fun `anime sin episode`() {
        assertEquals("catalog_anime/100", handoffRouteFor(anime(), null, null))
    }
    @Test fun `anime con episode`() {
        assertEquals("catalog_anime/100?episode=7", handoffRouteFor(anime(), null, 7))
    }
}
```

- [ ] **Step 3: Correr y verificar que falla**

`./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.HandoffRouteTest"` → FAIL.

- [ ] **Step 4: Implementar `handoffRouteFor`**

En `SearchViewModel.kt` (arriba del ViewModel):

```kotlin
/** Ruta de navegación a la pantalla de detalle según la card y el S/E opcional del REFINE. */
fun handoffRouteFor(card: TitleCard, season: Int?, episode: Int?): String = when (card.kind) {
    "anime" -> buildString {
        append("catalog_anime/").append(card.anilistId)
        if (episode != null) append("?episode=").append(episode)
    }
    "movie" -> "cine/movie/${card.tmdbId}"
    else -> buildString { // "series"
        append("cine/tv/").append(card.tmdbId)
        if (season != null && episode != null) append("?season=").append(season).append("&episode=").append(episode)
    }
}
```

- [ ] **Step 5: Correr y verificar que pasa**

`./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.search.HandoffRouteTest"` → PASS (5 tests).

- [ ] **Step 6: Implementar el `SearchViewModel`**

`class SearchViewModel(...) : ViewModel()` (inyecta lo que use, vía `viewModelFactory`, patrón de
`CineCatalogViewModel`). Dependencias: `tmdbApi`, `aniListApi`, `torrentSearchApi`, `api` (archive).
Estado (StateFlow): `query`, `phase` (default QUERY), `titleResults`, `directResults`,
`loadingTitles`, `loadingDirect`, `selected: TitleCard?`, `season: Int?`, `episode: Int?`.
- `search(q)`: guarda query; en `viewModelScope`, cancela job previo y lanza en paralelo:
  - `tmdbApi.searchMulti(q)` → `TitleCard(kind = if (it.type=="tv") "series" else "movie", tmdbId = it.id, anilistId = null, title, posterUrl, year, overview = null)`.
  - `aniListApi.browse(page = 1, sort = <default, p.ej. "SEARCH_MATCH" o el que use AnimeViewModel>, search = q, genre = null)` → `TitleCard(kind = "anime", tmdbId = null, anilistId = show.id, title = show.title, posterUrl = show.posterUrl, year = show.year.toString(), overview = show.description)`.
  - Directos: `torrentSearchApi.searchMovie(listOf(q), "", ALL_LANGS, 0)` → `PlaySource.Torrent`; `api.search(q)` → `PlaySource.Archive`.
  Cada fuente actualiza su StateFlow apenas responde; usa flags de carga por grupo.
- `pickTitle(card)`: `selected = card`; `phase = if (card.kind == "movie") QUERY else REFINE`
  (película no necesita REFINE — la UI navegará directo; series/anime van a REFINE).
- `setSeasonEpisode(s, e)`: guarda `season`/`episode` (pueden quedar null = "toda la serie").

> `ALL_LANGS` = `listOf(TorrentLang.LATINO, TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.ENGLISH, TorrentLang.JAP_SUB)` (igual que CineDetail). Verifica el nombre real del sort por defecto de AniList mirando `AnimeViewModel`/`buildAnimeBrowseQuery`.

- [ ] **Step 7: Compilar**

`./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): SearchViewModel (fase 1 unificada + handoffRouteFor)" -- \
  app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt \
  app/src/main/java/com/arkiv/player/ui/search/CardContext.kt \
  app/src/test/java/com/arkiv/player/ui/search/HandoffRouteTest.kt
```

---

## Task 7: `SearchScreen` (UI) + lupa + navegación/handoff + play de directos

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (ruta `search` + wiring)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt` (lupa → `onOpenSearch`)

**Interfaces:**
- `@Composable fun SearchScreen(onOpenDetail: (String) -> Unit, onPlay: (String) -> Unit, onBack: () -> Unit)`
  donde `onOpenDetail(route)` navega a la ruta de handoff, y `onPlay(episodeId)` va al player.

- [ ] **Step 1: `SearchScreen` — Fase QUERY (cards + directos)**

Obtener `graph = rememberGraph()` y el VM con `viewModel(factory = viewModelFactory { initializer { SearchViewModel(graph.tmdbApi, graph.aniListApi, graph.torrentSearchApi, graph.api) } })`.
`LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }`. Render por `phase`:
- **QUERY:** `OutlinedTextField` (lupa, placeholder "Buscar…", `ImeAction.Search` → `vm.search`). Debajo:
  - Grilla de cards desde `titleResults` (`AsyncImage(card.posterUrl)`, título, año, y un chip del `kind`),
    `onClick = { if (card.kind == "movie") onOpenDetail(handoffRouteFor(card, null, null)) else vm.pickTitle(card) }`.
  - Sección "Resultados directos": `directResults` con `SourceRow` (reutilizado de `PlaySources.kt`),
    `onClick` → reproducir directo (Step 3).

- [ ] **Step 2: `SearchScreen` — Fase REFINE (S/E opcional) + handoff**

Cuando `phase == REFINE` y hay `selected`: mostrar póster+título arriba y:
- Si `selected.kind == "series"`: dos campos numéricos opcionales (Temporada, Capítulo) + botón
  **Continuar** → `onOpenDetail(handoffRouteFor(selected, season, episode))`. Vacíos → navega sin args
  (toda la serie).
- Si `selected.kind == "anime"`: un campo numérico opcional (Episodio) + botón **Continuar** →
  `onOpenDetail(handoffRouteFor(selected, null, episode))`.
Botón atrás en REFINE → `vm.phase = QUERY` (o expón `vm.back()`).

- [ ] **Step 3: Play de resultados directos (path mínimo, reutiliza motor+repo)**

Para un `PlaySource.Torrent`/`PlaySource.Archive` directo, replica el patrón mínimo de `CineDetailScreen`:
- Torrent: `graph.torrentSearchApi.resolveSource(result)` → si `Magnet` → `graph.repository.addTorrentMagnet(result.name, uri)`; si `TorrentFile` → `resolveTorrent` → `videoFiles`/`pickVideo` → `graph.repository.addTorrent(result.name, meta.infoHashHex, meta.infoBytes, videos)` → `firstEpisodeId` → `onPlay`.
- Archive: `graph.repository.addItem(item.identifier)` → `firstEpisodeId` → `onPlay`.
(Es el único play que vive en el wizard; el resto lo hacen las pantallas de detalle vía handoff.)

- [ ] **Step 4: Ruta `search` en `ArkivRoot`**

```kotlin
composable("search") {
    SearchScreen(
        onOpenDetail = { route -> navController.navigate(route) },
        onPlay = { id -> navController.navigate("player/${Uri.encode(id)}") },
        onBack = { navController.popBackStack() },
    )
}
```
Import `com.arkiv.player.ui.search.SearchScreen`.

- [ ] **Step 5: Lupa en el Catálogo**

En `CineCatalogScreen`, agregar `onOpenSearch: () -> Unit = {}` y un `IconButton(Icons.Default.Search)`
en la barra superior que llame `onOpenSearch`. En `ArkivRoot` (ruta `catalog`), pasar
`onOpenSearch = { navController.navigate("search") }`.

- [ ] **Step 6: Compilar**

`./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 7: Verificación end-to-end en device**

`./gradlew :app:installDebug`. Probar:
1. **Serie + capítulo:** Catálogo → lupa → "Superman" → card serie → Temporada 2/Cap 5 → Continuar →
   abre `CineDetailScreen` **con los resultados de S02E05 ya abiertos** → reproducir. Verificar que el
   capítulo quedó como `Serie · S02E05 · <título>` con descripción.
2. **Serie completa:** igual, S/E vacíos → abre el detalle de la serie (packs visibles, PackDialog funciona).
3. **Anime:** "Naruto" → card anime → Episodio 20 → Continuar → abre `AnimeShowDetailScreen` con las
   fuentes del ep 20.
4. **Película:** card película → abre `CineDetailScreen` directo.
5. **Directo:** tocar una fila de "Resultados directos" → reproduce.

- [ ] **Step 8: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): SearchScreen (fase 1 + refine) + lupa + handoff a detalle" -- \
  app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt \
  app/src/main/java/com/arkiv/player/ui/catalog/CineCatalogScreen.kt
```

---

## Self-Review (cobertura vs spec — HÍBRIDO)

- **Fase 1 unificada (TMDB cards + anime cards + directos):** Task 1 (searchMulti) + Task 6 (`search()` fan-out con aniList.browse + directos) + Task 7 (UI). ✅
- **Entrada por lupa en catálogo:** Task 7 Step 5. ✅
- **REFINE S/E opcional (series) / episodio (anime):** Task 7 Step 2 + `handoffRouteFor` (Task 6). ✅
- **Handoff a pantallas existentes con deep-link:** Task 5 (deep-link S/E + auto-open) + Task 6 (`handoffRouteFor`) + Task 7 (navegación). ✅
- **Reproducir + TV + PackDialog:** reutilizados vía las pantallas de detalle (no reimplementados). ✅
- **Play de resultados directos:** Task 7 Step 3. ✅
- **Enriquecimiento (description + label `Serie·SxxExx·Título`):** label ya activo para todos los callers (Task 2); description se pasa en las pantallas de detalle (Task 5 Step 2). ✅
- **Anime cards vía AniList:** Task 6 (`aniListApi.browse`). ✅
- **Sin regresión en pantallas de detalle:** deep-link args opcionales con default null (Task 5). ✅
- **Arte/`ArtworkEntity`:** fuera de alcance (documentado). ⚠️ (no gap silencioso)
- **`CardContext` (Task 4):** parcialmente obsoleto por el híbrido; `TitleCard`/`SearchPhase`/`phaseAfterPick` se reutilizan, `CardContext` queda sin uso (no se borra para no romper imports). Nota de deuda menor.
