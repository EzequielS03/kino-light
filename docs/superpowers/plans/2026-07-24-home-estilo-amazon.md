# Home estilo Amazon — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convertir el Home en una pantalla de descubrimiento estilo Prime Video (hero + filas horizontales por categoría con carga perezosa), donde tocar un póster es un atajo directo a las fuentes de ese título en el buscador ya existente.

**Architecture:** `HomeViewModel` construye una lista de *specs* de fila (curated / discover por género / anime) y las carga **bajo demanda** (`loadRow(id)` idempotente, cacheado en memoria). `HomeScreen` es un `LazyColumn`: solo compone lo visible, y cada fila dispara su carga al aparecer. El click mapea la card a una ruta del buscador (`search?kind=…&tmdbId=…`) que salta la fase de escribir. La grilla de biblioteca actual se **mueve** a su propia pantalla/ruta (no se borra) y el Home la enlaza con "Ver todo".

**Tech Stack:** Kotlin, Jetpack Compose (LazyColumn/LazyRow), Coroutines/Flow, Room, Coil (AsyncImage), TMDB + AniList, JUnit4, Gradle.

## Global Constraints

- **Commits con identidad `lordmacu`:** `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit …`. Nunca coautoría de Claude. **Nunca `git add -A`** (varias sesiones comparten el working tree) — agregar solo los archivos de la tarea.
- **Tests:** JUnit4 (`org.junit.Test`, `org.junit.Assert.*`). Correr con `./gradlew :app:testDebugUnitTest --tests "<FQN>"`.
- **Compilar:** `./gradlew :app:compileDebugKotlin`. Instalar: `./gradlew :app:installDebug` (device WiFi en `192.168.3.20:40381`).
- **Reutilización primero:** usar `TitleCard` (`ui/search/CardContext.kt`), `tmdbApi.curated/discover/genres`, `aniListApi.browse`, `repo.observeLibrary/observeContinueWatching/observeArtwork`, `AsyncImage`. No duplicar el buscador ni el catálogo.
- **No borrar código del catálogo:** la pestaña se oculta quitando su entrada de `TABS`; ruta y pantallas quedan.
- **Sin regresiones:** la biblioteca (grilla, filtros, long-press, borrar) debe seguir funcionando igual tras moverse de pantalla.

## Estructura de archivos

- **Crear:**
  - `app/src/main/java/com/arkiv/player/ui/home/HomeRows.kt` — modelos y lógica pura de filas + ruta de atajo.
  - `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt` — la grilla de biblioteca movida desde `HomeScreen`.
  - Tests: `HomeRowsTest.kt`, `LoadGuardTest.kt`.
- **Modificar:**
  - `ui/home/HomeViewModel.kt` — filas remotas + carga perezosa.
  - `ui/home/HomeScreen.kt` — pantalla nueva (hero + filas).
  - `ui/search/CardContext.kt` — mappers compartidos a `TitleCard`.
  - `ui/search/SearchViewModel.kt` + `SearchScreen.kt` — entrada por atajo.
  - `ui/ArkivRoot.kt` — rutas `library` y `search` con args, ocultar pestaña Catálogo.

---

## Task 1: Lógica pura de filas y ruta de atajo

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/home/HomeRows.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/home/HomeRowsTest.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/home/LoadGuardTest.kt`

**Interfaces:**
- Consumes: `TmdbCategory`, `TmdbGenre` (`data/catalog/TmdbApi.kt`), `TitleCard` (`ui/search/CardContext.kt`).
- Produces:
  - `sealed interface RowSource { Curated(type, category) | Discover(type, genreId) | Anime }`
  - `data class HomeRowSpec(val id: String, val title: String, val source: RowSource)`
  - `fun buildRowSpecs(movieGenres: List<TmdbGenre>, tvGenres: List<TmdbGenre>): List<HomeRowSpec>`
  - `fun searchShortcutRoute(card: TitleCard): String`
  - `class LoadGuard { fun shouldLoad(id: String): Boolean }`

- [ ] **Step 1: Escribir los tests que fallan**

`app/src/test/java/com/arkiv/player/ui/home/HomeRowsTest.kt`:

```kotlin
package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private val movieGenres = listOf(TmdbGenre(28, "Acción"), TmdbGenre(35, "Comedia"))
    private val tvGenres = listOf(TmdbGenre(16, "Animación"))

    @Test fun `las filas fijas van en orden y antes de los generos`() {
        val ids = buildRowSpecs(movieGenres, tvGenres).map { it.id }
        assertEquals(
            listOf("cartelera", "proximamente", "tendencias", "series_populares", "series_top", "anime"),
            ids.take(6),
        )
    }

    @Test fun `agrega una fila por genero de pelicula y de serie`() {
        val specs = buildRowSpecs(movieGenres, tvGenres)
        assertTrue(specs.any { it.id == "g_movie_28" && it.source == RowSource.Discover("movie", 28) })
        assertTrue(specs.any { it.id == "g_movie_35" })
        assertTrue(specs.any { it.id == "g_tv_16" && it.source == RowSource.Discover("tv", 16) })
        assertEquals(6 + 3, specs.size)
    }

    @Test fun `sin generos quedan solo las fijas`() {
        assertEquals(6, buildRowSpecs(emptyList(), emptyList()).size)
    }

    @Test fun `los ids son unicos`() {
        val specs = buildRowSpecs(movieGenres, tvGenres)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `la fila de cartelera apunta a NOW_PLAYING de peliculas`() {
        val spec = buildRowSpecs(emptyList(), emptyList()).first { it.id == "cartelera" }
        assertEquals(RowSource.Curated("movie", TmdbCategory.NOW_PLAYING), spec.source)
    }

    @Test fun `ruta de atajo por tipo de card`() {
        val movie = TitleCard("movie", 42, null, "X", "", "2025", null)
        val series = TitleCard("series", 1399, null, "Y", "", "2011", null)
        val anime = TitleCard("anime", null, 20L, "Z", "", "1999", null)
        assertEquals("search?kind=movie&tmdbId=42", searchShortcutRoute(movie))
        assertEquals("search?kind=series&tmdbId=1399", searchShortcutRoute(series))
        assertEquals("search?kind=anime&anilistId=20", searchShortcutRoute(anime))
    }
}
```

`app/src/test/java/com/arkiv/player/ui/home/LoadGuardTest.kt`:

```kotlin
package com.arkiv.player.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadGuardTest {
    @Test fun `solo deja cargar una vez por id`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("cartelera"))
        assertFalse(guard.shouldLoad("cartelera"))
    }

    @Test fun `ids distintos son independientes`() {
        val guard = LoadGuard()
        assertTrue(guard.shouldLoad("a"))
        assertTrue(guard.shouldLoad("b"))
        assertFalse(guard.shouldLoad("a"))
    }
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.home.*"`
Expected: FAIL con "unresolved reference: buildRowSpecs / LoadGuard".

- [ ] **Step 3: Implementar `HomeRows.kt`**

```kotlin
package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard

/** De dónde saca sus títulos una fila del home. */
sealed interface RowSource {
    data class Curated(val type: String, val category: TmdbCategory) : RowSource
    data class Discover(val type: String, val genreId: Int) : RowSource
    data object Anime : RowSource
}

/** Una fila horizontal del home. El `id` es la clave de caché y de carga perezosa. */
data class HomeRowSpec(val id: String, val title: String, val source: RowSource)

/**
 * Filas fijas primero (las más útiles) y luego una por género — películas y después series.
 * Las de género son muchas a propósito: se cargan solo cuando entran en pantalla.
 */
fun buildRowSpecs(movieGenres: List<TmdbGenre>, tvGenres: List<TmdbGenre>): List<HomeRowSpec> = buildList {
    add(HomeRowSpec("cartelera", "En cartelera", RowSource.Curated("movie", TmdbCategory.NOW_PLAYING)))
    add(HomeRowSpec("proximamente", "Próximamente", RowSource.Curated("movie", TmdbCategory.UPCOMING)))
    add(HomeRowSpec("tendencias", "Tendencias de la semana", RowSource.Curated("movie", TmdbCategory.TRENDING)))
    add(HomeRowSpec("series_populares", "Series populares", RowSource.Curated("tv", TmdbCategory.POPULAR)))
    add(HomeRowSpec("series_top", "Series mejor valoradas", RowSource.Curated("tv", TmdbCategory.TOP_RATED)))
    add(HomeRowSpec("anime", "Anime del momento", RowSource.Anime))
    movieGenres.forEach { g -> add(HomeRowSpec("g_movie_${g.id}", "${g.name} · Películas", RowSource.Discover("movie", g.id))) }
    tvGenres.forEach { g -> add(HomeRowSpec("g_tv_${g.id}", "${g.name} · Series", RowSource.Discover("tv", g.id))) }
}

/** Ruta del buscador que salta la fase de escribir y arranca ya en ese título. */
fun searchShortcutRoute(card: TitleCard): String = when (card.kind) {
    "anime" -> "search?kind=anime&anilistId=${card.anilistId}"
    "movie" -> "search?kind=movie&tmdbId=${card.tmdbId}"
    else -> "search?kind=series&tmdbId=${card.tmdbId}"
}

/** Evita que una fila vuelva a pedir red al recomponerse o al volver a entrar en pantalla. */
class LoadGuard {
    private val started = mutableSetOf<String>()
    fun shouldLoad(id: String): Boolean = started.add(id)
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.home.*"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(home): specs de filas, ruta de atajo al buscador y guard de carga" -- \
  app/src/main/java/com/arkiv/player/ui/home/HomeRows.kt \
  app/src/test/java/com/arkiv/player/ui/home/HomeRowsTest.kt \
  app/src/test/java/com/arkiv/player/ui/home/LoadGuardTest.kt
```

---

## Task 2: Mover la grilla de biblioteca a `LibraryScreen` (sin cambio visible)

Refactor puro: sacar de `HomeScreen` la grilla + filtros + menú long-press + confirmación de borrado a
su propia pantalla y ruta. El Home **delega** en ella, así que la app se ve exactamente igual.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`

**Interfaces:**
- Produces: `@Composable fun LibraryScreen(onOpenItem: (String) -> Unit, onPlayEpisode: (String) -> Unit, onOpenConnect: () -> Unit = {}, contentPadding: PaddingValues)` — misma firma que `HomeScreen` hoy.

- [ ] **Step 1: Crear `LibraryScreen.kt` moviendo el contenido actual**

Mueve **verbatim** desde `HomeScreen.kt` a `ui/library/LibraryScreen.kt` (package `com.arkiv.player.ui.library`): el cuerpo del composable (estado `menuRow`/`confirmDeleteRow`/`filter`, `open(row)`, el `EmptyState`, el `LazyVerticalGrid` con "Continuar viendo" + "Mi biblioteca", el `AlertDialog` de borrado y los helpers privados que solo use esa pantalla — p.ej. `SheetAction`, `SectionHeader`, las cards). Renombra el composable a `LibraryScreen`. Ajusta imports (los helpers que queden en `ui/home` y se sigan usando hay que importarlos, o muévelos también si son exclusivos de esta pantalla).

- [ ] **Step 2: `HomeScreen` delega en `LibraryScreen`**

Deja `HomeScreen` con la misma firma pública, delegando:

```kotlin
@Composable
fun HomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenConnect: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    // Provisional: en la Task 5 este cuerpo se reemplaza por el home de descubrimiento.
    LibraryScreen(onOpenItem, onPlayEpisode, onOpenConnect, contentPadding)
}
```

- [ ] **Step 3: Registrar la ruta `library` en `ArkivRoot`**

Junto a las demás rutas del `NavHost`:

```kotlin
composable("library") {
    LibraryScreen(
        onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
        onPlayEpisode = { playEpisode(it) },
        onOpenConnect = { showConnection = true },
        contentPadding = padding,
    )
}
```
Copia los argumentos exactamente como se los pasa hoy `composable("home")` a `HomeScreen` (revisa ese bloque). Import: `com.arkiv.player.ui.library.LibraryScreen`.

- [ ] **Step 4: Compilar y verificar que no cambió nada**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:installDebug` y confirma que el Inicio se ve **igual que antes** (continuar viendo + grilla + filtros + long-press + borrar).

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "refactor(home): mover la grilla de biblioteca a LibraryScreen con su ruta" -- \
  app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt \
  app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
```

---

## Task 3: Mappers compartidos a `TitleCard`

Hoy `SearchViewModel` mapea `TmdbItem`/`AnimeShow` → `TitleCard` en línea. El home necesita lo mismo:
extraer los mappers para no duplicarlos.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`

**Interfaces:**
- Produces (en `CardContext.kt`, package `com.arkiv.player.ui.search`):
  - `fun TmdbItem.toTitleCard(): TitleCard`
  - `fun AnimeShow.toTitleCard(): TitleCard`

- [ ] **Step 1: Agregar los mappers en `CardContext.kt`**

```kotlin
/** TMDB → card del home/buscador. `type` de TMDB es "movie"|"tv"; en la UI usamos "movie"|"series". */
fun TmdbItem.toTitleCard(): TitleCard = TitleCard(
    kind = if (type == "tv") "series" else "movie",
    tmdbId = id,
    anilistId = null,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = null,
)

/** AniList → card. `year` puede venir 0 cuando no se conoce: mejor vacío que "0". */
fun AnimeShow.toTitleCard(): TitleCard = TitleCard(
    kind = "anime",
    tmdbId = null,
    anilistId = id,
    title = title,
    posterUrl = posterUrl,
    year = if (year > 0) year.toString() else "",
    overview = description,
)
```
Imports necesarios: `com.arkiv.player.data.catalog.TmdbItem`, `com.arkiv.player.data.catalog.AnimeShow`.

- [ ] **Step 2: Usarlos en `SearchViewModel`**

En `search(q)`, reemplaza los dos mapeos en línea por `.map { it.toTitleCard() }`. **No cambies el
comportamiento**: verifica que el mapeo inline actual produce exactamente lo mismo (incluido el guard
del año en anime); si difiere en algo, el mapper compartido manda y explícalo en el reporte.

- [ ] **Step 3: Compilar y correr los tests existentes**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (nada debe romperse).

- [ ] **Step 4: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "refactor(busqueda): extraer mappers TmdbItem/AnimeShow -> TitleCard" -- \
  app/src/main/java/com/arkiv/player/ui/search/CardContext.kt \
  app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt
```

---

## Task 4: Entrada por atajo en el buscador (`search?kind=…`)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`

**Interfaces:**
- Produces:
  - `fun SearchViewModel.startFromShortcut(kind: String, tmdbId: Int?, anilistId: Long?)`
  - `SearchScreen(..., shortcutKind: String? = null, shortcutTmdbId: Int? = null, shortcutAnilistId: Long? = null)`

- [ ] **Step 1: `startFromShortcut` en el ViewModel**

Resuelve la metadata mínima para armar el `TitleCard` y entra al flujo normal:

```kotlin
/** Entrada desde el home: arranca ya en un título, saltándose la fase de escribir. */
fun startFromShortcut(kind: String, tmdbId: Int?, anilistId: Long?) {
    if (selected.value != null) return   // ya arrancado (no repetir en recomposición)
    viewModelScope.launch {
        val card = when {
            kind == "anime" && anilistId != null ->
                runCatching { aniListApi.details(anilistId) }.getOrNull()?.toTitleCard()
            tmdbId != null -> {
                val type = if (kind == "movie") "movie" else "tv"
                runCatching { tmdbApi.detail(type, tmdbId) }.getOrNull()?.let { d ->
                    TitleCard(
                        kind = if (kind == "movie") "movie" else "series",
                        tmdbId = d.id, anilistId = null, title = d.title,
                        posterUrl = d.posterUrl, year = d.year, overview = d.overview,
                    )
                }
            }
            else -> null
        } ?: return@launch
        pickTitle(card)   // película -> RESULTS; serie/anime -> REFINE
    }
}
```
> Verifica los nombres reales de los campos de `TmdbDetail` (`id`, `title`, `posterUrl`, `year`, `overview`) y de `aniListApi.details`. Si `pickTitle` no dispara la búsqueda para película en tu implementación actual, ajusta para que sí llegue a RESULTS.

- [ ] **Step 2: Parámetros de atajo en `SearchScreen`**

Agrega al final de la firma: `shortcutKind: String? = null, shortcutTmdbId: Int? = null, shortcutAnilistId: Long? = null`, y dispara una sola vez:

```kotlin
LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) {
    val k = shortcutKind ?: return@LaunchedEffect
    vm.startFromShortcut(k, shortcutTmdbId, shortcutAnilistId)
}
```

- [ ] **Step 3: Ruta con args opcionales en `ArkivRoot`**

Reemplaza `composable("search") { … }` por:

```kotlin
composable(
    "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}",
    arguments = listOf(
        navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
        navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
        navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
    ),
) { entry ->
    SearchScreen(
        // …los argumentos que ya recibe hoy…
        shortcutKind = entry.arguments?.getString("kind"),
        shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
        shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
    )
}
```
La lupa del home sigue navegando a `"search"` sin args → los tres llegan null → comportamiento actual intacto.

- [ ] **Step 4: Compilar y probar en device**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:installDebug`. Verifica que la **lupa sigue abriendo el buscador vacío**. (El atajo real se prueba en la Task 5, cuando el home lo dispare.)

- [ ] **Step 5: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(busqueda): entrada por atajo (search?kind=&tmdbId=&anilistId=)" -- \
  app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt \
  app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
```

---

## Task 5: `HomeViewModel` — filas remotas con carga perezosa

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt`

**Interfaces:**
- Consumes: `buildRowSpecs`, `HomeRowSpec`, `RowSource`, `LoadGuard` (Task 1); `toTitleCard()` (Task 3); `tmdbApi.genres/curated/discover`, `aniListApi.browse`.
- Produces:
  - constructor `HomeViewModel(repo: ArkivRepository, tmdbApi: TmdbApi, aniListApi: AniListApi)`
  - `val rows: StateFlow<List<HomeRowSpec>>`
  - `val rowItems: StateFlow<Map<String, List<TitleCard>>>`
  - `val rowsLoaded: StateFlow<Set<String>>` — filas que ya terminaron (para poder ocultar las vacías)
  - `fun loadRow(id: String)`

- [ ] **Step 1: Ampliar el ViewModel**

Mantén intactos `library`, `continueWatching`, `artwork` y el `init` de `ensureArtwork`. Agrega:

```kotlin
class HomeViewModel(
    repo: ArkivRepository,
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    // …library / continueWatching / artwork como están…

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    private val _rowItems = MutableStateFlow<Map<String, List<TitleCard>>>(emptyMap())
    val rowItems: StateFlow<Map<String, List<TitleCard>>> = _rowItems.asStateFlow()

    private val _rowsLoaded = MutableStateFlow<Set<String>>(emptySet())
    val rowsLoaded: StateFlow<Set<String>> = _rowsLoaded.asStateFlow()

    private val guard = LoadGuard()

    init {
        // Los géneros se piden una sola vez para construir las filas; si falla, quedan las fijas.
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            if (movie.isNotEmpty() || tv.isNotEmpty()) _rows.value = buildRowSpecs(movie, tv)
        }
    }

    /** Carga los títulos de una fila la primera vez que se pide (idempotente). */
    fun loadRow(id: String) {
        if (!guard.shouldLoad(id)) return
        val spec = _rows.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val cards: List<TitleCard> = when (val s = spec.source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(s.type, s.category, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(s.type, s.genreId, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                RowSource.Anime ->
                    runCatching { aniListApi.browse(1, "TRENDING_DESC", null, null) }.getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            _rowItems.value = _rowItems.value + (id to cards)
            _rowsLoaded.value = _rowsLoaded.value + id
        }
    }
}
```
> Ojo: las specs de género llegan **después** de resolver `genres()`. Como `loadRow` busca la spec en
> `_rows.value`, una fila de género solo se puede cargar cuando ya existe — y solo existe cuando la UI
> la pinta, así que el orden es correcto. Verifica que `guard` no marque como "cargada" una fila que
> se descartó por no encontrar spec (si `spec == null`, ya consumiste el guard: en ese caso **no**
> consumas el guard antes de encontrar la spec, o resetéalo).

- [ ] **Step 2: Actualizar la construcción del ViewModel**

En `HomeScreen` (y en cualquier otro sitio que lo cree), pasa las nuevas dependencias:
`HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi)`.

- [ ] **Step 3: Compilar y correr tests**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(home): filas remotas del home con carga perezosa por fila" -- \
  app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt \
  app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt
```

---

## Task 6: `HomeScreen` nuevo (hero + filas + atajo) y ocultar la pestaña Catálogo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`

**Interfaces:**
- `HomeScreen(onOpenItem, onPlayEpisode, onOpenConnect = {}, onOpenSearchRoute: (String) -> Unit, onOpenLibrary: () -> Unit, contentPadding: PaddingValues)`

- [ ] **Step 1: Estructura del Home**

Reemplaza la delegación de la Task 2 por un `LazyColumn` con este orden:

1. **Hero** — `continueWatching.firstOrNull()`: backdrop (de `artwork[itemId]?.backdrops?.firstOrNull()`, fallback `itemThumbnailUrl`) a ancho completo (~220dp), degradado inferior, título (`itemTitle`), subtítulo (`displayName`) y botón **Reanudar** → `onPlayEpisode(episodeId)`. Si no hay nada en curso, usa el primer título de la fila `tendencias` (si ya cargó) como destacado con click al atajo; si tampoco, omite el hero.
2. **Continuar viendo** — `LazyRow` con el resto (`drop(1)`), click → `onPlayEpisode`.
3. **Mi biblioteca** — `LazyRow` de `library` + un **Ver todo** en el encabezado → `onOpenLibrary()`. Click en ítem → misma lógica `open(row)` que hoy (película reproduce, serie abre detalle).
4. **Filas remotas** — `rows.forEach { spec -> item(key = spec.id) { … } }`.

Cada fila remota:

```kotlin
@Composable
private fun RemoteRow(
    spec: HomeRowSpec,
    items: List<TitleCard>,
    loaded: Boolean,
    onLoad: () -> Unit,
    onOpenCard: (TitleCard) -> Unit,
) {
    LaunchedEffect(spec.id) { onLoad() }
    // Fila que ya cargó y vino vacía (o falló) -> se oculta, sin dejar hueco ni error.
    if (loaded && items.isEmpty()) return
    Column(Modifier.padding(top = 16.dp)) {
        Text(spec.title, style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        if (!loaded) {
            // Placeholder de carga (alto fijo para que el scroll no salte).
            Box(Modifier.height(180.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { "${spec.id}-${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                    PosterCard(card) { onOpenCard(card) }
                }
            }
        }
    }
}
```

`PosterCard`: `AsyncImage` del `posterUrl` con relación 2:3 (~120dp de ancho), esquinas redondeadas y el título en 2 líneas debajo. Sigue el estilo de las cards del catálogo (`CineCatalogScreen`) — no inventes uno nuevo.

Click de card: `onOpenSearchRoute(searchShortcutRoute(card))`.

- [ ] **Step 2: Cablear en `ArkivRoot`**

En `composable("home")`, agrega:
```kotlin
onOpenSearchRoute = { route -> navController.navigate(route) },
onOpenLibrary = { navController.navigate("library") },
```

- [ ] **Step 3: Ocultar la pestaña Catálogo**

En `ArkivRoot.kt` (~línea 97), quita la entrada de `TABS` dejando constancia:

```kotlin
private val TABS = listOf(
    Tab("home", "Inicio") { Icon(Icons.Default.Home, contentDescription = "Inicio") },
    // Catálogo oculto: el home de descubrimiento lo reemplaza. La ruta y CineCatalogScreen siguen
    // vivas — para volver a mostrarlo basta devolver esta línea.
    // Tab("catalog", "Catálogo") { Icon(Icons.Default.Movie, contentDescription = "Catálogo") },
    Tab("downloads", "Descargas") { Icon(Icons.Default.Download, contentDescription = "Descargas") },
    Tab("settings", "Ajustes") { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
)
```
**No borres** `composable("catalog")` ni nada de `CineCatalogScreen`. Si el import de `Icons.Default.Movie` queda sin uso, déjalo comentado junto a la línea o quítalo — lo que compile limpio.

- [ ] **Step 4: Compilar, tests e instalar**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL
Run: `./gradlew :app:installDebug`

- [ ] **Step 5: Verificación (el controlador la hará con capturas)**

En el reporte, lista lo que hay que mirar en el device:
1. Home abre rápido, con hero de lo último visto y botón Reanudar.
2. Al bajar, las filas se van cargando (spinner → pósters) y las vacías desaparecen.
3. Tocar una **película** → resultados de torrents de ese título.
4. Tocar una **serie** → paso de temporada/capítulo → resultados.
5. Tocar **anime** → paso de episodio → resultados.
6. "Ver todo" de Mi biblioteca → grilla con filtros, igual que antes.
7. El menú inferior ya **no** muestra Catálogo (Inicio · Descargas · Ajustes).

- [ ] **Step 6: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(home): home de descubrimiento (hero + filas horizontales) y ocultar pestana catalogo" -- \
  app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
```

---

## Self-Review (cobertura vs spec)

- **Hero = continuar viendo + Reanudar (fallback Tendencias):** Task 6 Step 1. ✅
- **Orden de secciones (hero → continuar → biblioteca → fijas → géneros):** Task 1 (`buildRowSpecs`) + Task 6. ✅
- **Filas elegidas (cartelera, próximamente, tendencias, series populares/top, anime):** Task 1. ✅
- **Todos los géneros con carga perezosa:** Task 1 (specs) + Task 5 (`loadRow` + guard) + Task 6 (`LaunchedEffect` por fila). ✅
- **Click = atajo al buscador (peli→resultados, serie/anime→refine):** Task 1 (`searchShortcutRoute`) + Task 4 (`startFromShortcut`) + Task 6. ✅
- **Ruta lleva solo kind+id:** Task 4. ✅
- **Mi biblioteca como fila + Ver todo:** Task 2 (`LibraryScreen` + ruta) + Task 6. ✅
- **Filas fallidas/vacías se ocultan:** Task 6 Step 1. ✅
- **Ocultar Catálogo sin borrar código:** Task 6 Step 3. ✅
- **Reutilizar TitleCard y no duplicar mapeos:** Task 3. ✅
- **Testing unitario de lógica pura:** Task 1 (8 tests). El resto se verifica compilando + capturas. ✅
- **Atrás desde el buscador vuelve al Home:** es el comportamiento por defecto de `popBackStack` al navegar desde el home; no requiere código extra. Verificar en Task 6 Step 5. ⚠️ (a confirmar en device)
