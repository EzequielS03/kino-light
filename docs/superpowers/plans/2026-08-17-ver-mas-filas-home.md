# "Ver más" en filas del home — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar una tarjeta "Ver más" al final de cada fila de descubrimiento del home (móvil y TV) que abre una pantalla nueva con paginación infinita para esa categoría, donde los títulos son reproducibles igual que desde el home.

**Architecture:** `RowBrowseViewModel` decodifica el `rowId` para saber cómo paginar (TMDB/AniList, sin re-fetchear géneros). `RowBrowseScreen` (móvil) y `TvRowBrowseScreen` (TV) muestran un grid con scroll infinito usando los mismos composables de tarjeta del home. La card "Ver más" se agrega al final de cada `RemoteRow` del móvil y cada discovery row del TV.

**Tech Stack:** Kotlin, Jetpack Compose, TV Compose Material3, `TmdbApi`, `AniListApi`, Jetpack Navigation, `AppGraph` para inyección manual de dependencias.

**Spec:** diseño aprobado en conversación del 2026-08-17.

## Global Constraints

- Kotlin + Jetpack Compose (sin Hilt — inyección manual vía `LocalAppGraph.current` + `viewModelFactory { initializer { … } }`)
- ViewModel creado con clave: `viewModel(key = rowId, factory = …)` para que cada fila tenga su instancia
- No deduplicar entre filas en `RowBrowseViewModel` (el home ya deduplicó; la pantalla de browse muestra la página tal cual viene de la API)
- No modificar `HomeViewModel` ni `HomeRows.kt`
- Las filas que **no** reciben "Ver más": Continuar viendo, Canales en vivo (ya tiene el suyo), Mi biblioteca (ya tiene "Ver todo"), Para ti (TV — gateway sin paginación)
- Commits sin `Co-Authored-By` ni coautoría de Claude
- Identidad de commits: `user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`

---

## Mapa de archivos

| Archivo | Acción |
|---------|--------|
| `ui/home/RowBrowseViewModel.kt` | **Crear** — ViewModel paginado |
| `ui/home/RowBrowseScreen.kt` | **Crear** — pantalla browse móvil |
| `ui/tv/TvRowBrowseScreen.kt` | **Crear** — pantalla browse TV |
| `ui/home/HomeScreen.kt` | **Modificar** — param `onBrowseRow`, `VerMasPosterCard`, card al final de `RemoteRow` |
| `ui/tv/TvHomeScreen.kt` | **Modificar** — param `onBrowseRow`, `TvVerMasFilaCard`, card al final de cada discovery row |
| `ui/ArkivRoot.kt` | **Modificar** — ruta `row_browse/{rowId}`, wirear `onBrowseRow` |
| `ui/tv/ArkivTvRoot.kt` | **Modificar** — ruta `row_browse/{rowId}`, wirear `onBrowseRow` |

---

## Tarea 1: RowBrowseViewModel

**Archivos:**
- Crear: `app/src/main/java/com/arkiv/player/ui/home/RowBrowseViewModel.kt`

**Interfaces:**
- Consume: `TmdbApi.curated(type, category, page)`, `TmdbApi.discover(type, genreId, page)`, `AniListApi.browse(page, sort, null, genre)` — mismas firmas que usa `HomeViewModel.loadRow()`
- Produce: `RowBrowseViewModel(rowId: String, tmdbApi: TmdbApi, aniListApi: AniListApi)` con `items: StateFlow<List<TitleCard>>`, `isLoading: StateFlow<Boolean>`, `canLoadMore: StateFlow<Boolean>`, `fun loadMore()`
- La decodificación `rowId → RowSource` es interna; no hay interfaz pública para ella

- [ ] **Paso 1: Crear el archivo**

```kotlin
package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.toTitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RowBrowseViewModel(
    private val rowId: String,
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TitleCard>>(emptyList())
    val items: StateFlow<List<TitleCard>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private var page = 1
    private var loading = false

    /** Carga la siguiente página. Idempotente mientras la anterior no termina. */
    fun loadMore() {
        if (loading || !_canLoadMore.value) return
        loading = true
        _isLoading.value = true
        viewModelScope.launch {
            val source = sourceFor(rowId)
            if (source == null) {
                _canLoadMore.value = false
                _isLoading.value = false
                loading = false
                return@launch
            }
            val result: List<TitleCard> = when (source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(source.type, source.category, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(source.type, source.genreId, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(page, source.sort, null, source.genre) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            _items.value = _items.value + result
            // Si llegaron menos de 2 ítems, la fuente se agotó (heurística: TMDB da 20/página,
            // AniList da ~50; cualquier resultado vacío o casi vacío indica fin de páginas).
            _canLoadMore.value = result.size >= 2
            if (result.isNotEmpty()) page++
            _isLoading.value = false
            loading = false
        }
    }

    companion object {
        /** Decodifica el rowId al origen de datos, sin hacer ninguna llamada de red. */
        fun sourceFor(rowId: String): RowSource? = when (rowId) {
            "cartelera"          -> RowSource.Curated("movie", TmdbCategory.NOW_PLAYING)
            "peliculas_populares" -> RowSource.Curated("movie", TmdbCategory.POPULAR)
            "tendencias"         -> RowSource.Curated("movie", TmdbCategory.TRENDING)
            "series_populares"   -> RowSource.Curated("tv", TmdbCategory.POPULAR)
            "series_top"         -> RowSource.Curated("tv", TmdbCategory.TOP_RATED)
            "anime"              -> RowSource.Anime("TRENDING_DESC")
            "anime_populares"    -> RowSource.Anime("POPULARITY_DESC")
            "anime_top"          -> RowSource.Anime("SCORE_DESC")
            else -> when {
                rowId.startsWith("g_movie_") ->
                    rowId.removePrefix("g_movie_").toIntOrNull()
                        ?.let { RowSource.Discover("movie", it) }
                rowId.startsWith("g_tv_") ->
                    rowId.removePrefix("g_tv_").toIntOrNull()
                        ?.let { RowSource.Discover("tv", it) }
                rowId.startsWith("g_anime_") -> {
                    // El slug se creó con g.lowercase().replace(" ", "_").
                    // Reconstruimos el nombre con title-case para que AniList lo reconozca.
                    val genre = rowId.removePrefix("g_anime_")
                        .replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                    RowSource.Anime(sort = "POPULARITY_DESC", genre = genre)
                }
                else -> null
            }
        }
    }
}
```

- [ ] **Paso 2: Verificar que compila**

```bash
./gradlew :app:compileDebugKotlin -x test 2>&1 | tail -15
```

Esperado: `BUILD SUCCESSFUL`

- [ ] **Paso 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/RowBrowseViewModel.kt
git commit -m "feat(home): RowBrowseViewModel pagina filas de TMDB/AniList por rowId"
```

---

## Tarea 2: RowBrowseScreen (móvil)

**Archivos:**
- Crear: `app/src/main/java/com/arkiv/player/ui/home/RowBrowseScreen.kt`
- Modificar: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`

**Interfaces:**
- Consume: `RowBrowseViewModel` (Tarea 1), `PosterCard` de `ui/components/Components.kt`, `searchShortcutRoute(card)` de `HomeRows.kt`, `LocalAppGraph` de `AppGraph.kt`
- Produce: `@Composable fun RowBrowseScreen(rowId: String, title: String, onOpenSearchRoute: (String) -> Unit, onBack: () -> Unit)`
- En `HomeScreen.kt`: nuevo param `onBrowseRow: (rowId: String, title: String) -> Unit` en `HomeScreen()`; nueva `VerMasPosterCard`; nuevo param `onVerMas: () -> Unit` en `RemoteRow()`

- [ ] **Paso 1: Crear RowBrowseScreen.kt**

```kotlin
package com.arkiv.player.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.AppGraph
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowBrowseScreen(
    rowId: String,
    title: String,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
    graph: AppGraph,
) {
    val vm: RowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { RowBrowseViewModel(rowId, graph.tmdbApi, graph.aniListApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val state = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 6 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ArkivBlack,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = ArkivBlack,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                PosterCard(
                    title = card.title,
                    imageUrl = card.posterUrl,
                    onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                )
            }
            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = ArkivRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Paso 2: Agregar `VerMasPosterCard` y modificar `RemoteRow` en HomeScreen.kt**

Localiza `private fun RemoteRow(` en `HomeScreen.kt` (~línea 467). El bloque completo actual es:
```kotlin
private fun RemoteRow(
    spec: HomeRowSpec,
    items: List<TitleCard>,
    loaded: Boolean,
    onLoad: () -> Unit,
    onOpenCard: (TitleCard) -> Unit,
) {
```

Reemplázalo agregando el nuevo param `onVerMas` y la card al final del `LazyRow`:
```kotlin
private fun RemoteRow(
    spec: HomeRowSpec,
    items: List<TitleCard>,
    loaded: Boolean,
    onLoad: () -> Unit,
    onOpenCard: (TitleCard) -> Unit,
    onVerMas: () -> Unit,
) {
    LaunchedEffect(spec.id) { onLoad() }
    if (loaded && items.isEmpty()) return
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            spec.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        if (!loaded) {
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
                item(key = "${spec.id}-ver-mas") {
                    VerMasPosterCard(onClick = onVerMas)
                }
            }
        }
    }
}
```

Agrega `VerMasPosterCard` justo debajo de `PosterCard` (~línea 511):
```kotlin
@Composable
private fun VerMasPosterCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(120.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ver más",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}
```

- [ ] **Paso 3: Agregar `onBrowseRow` a la firma de `HomeScreen` y pasarlo a `RemoteRow`**

En `HomeScreen.kt`, localiza la declaración `fun HomeScreen(` y agrega el param nuevo:
```kotlin
onBrowseRow: (rowId: String, title: String) -> Unit,
```

En el bloque donde se llama a `RemoteRow` (~línea 312), agrega:
```kotlin
RemoteRow(
    spec = spec,
    items = rowItems[spec.id].orEmpty(),
    loaded = spec.id in rowsLoaded,
    onLoad = { vm.loadRow(spec.id) },
    onOpenCard = { card -> onOpenSearchRoute(searchShortcutRoute(card)) },
    onVerMas = { onBrowseRow(spec.id, spec.title) },
)
```

- [ ] **Paso 4: Verificar compilación**

```bash
./gradlew :app:compileDebugKotlin -x test 2>&1 | tail -15
```

Esperado: error de compilación en `ArkivRoot.kt` y `LibraryScreen.kt` porque `HomeScreen()` ahora requiere `onBrowseRow` — está bien, se resuelve en Tarea 4.

- [ ] **Paso 5: Commit parcial (solo los archivos que compilan solos)**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/RowBrowseScreen.kt
git commit -m "feat(home): RowBrowseScreen — grid paginado para explorar más de una fila"
```

---

## Tarea 3: TvRowBrowseScreen

**Archivos:**
- Crear: `app/src/main/java/com/arkiv/player/ui/tv/TvRowBrowseScreen.kt`
- Modificar: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`

**Interfaces:**
- Consume: `RowBrowseViewModel` (Tarea 1), `TvLandscapeCard` de `TvComponents.kt`, `TvRowLabel` de `TvHomeScreen.kt` (es `internal`), `searchShortcutRoute(card)` de `HomeRows.kt`
- Produce: `@Composable fun TvRowBrowseScreen(rowId: String, title: String, onOpenSearchRoute: (String) -> Unit, onBack: () -> Unit, graph: AppGraph)`
- En `TvHomeScreen.kt`: nuevo param `onBrowseRow: (rowId: String, title: String) -> Unit`; nueva `TvVerMasFilaCard`; card al final de cada discovery row

- [ ] **Paso 1: Crear TvRowBrowseScreen.kt**

```kotlin
package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.*
import com.arkiv.player.AppGraph
import com.arkiv.player.ui.home.RowBrowseViewModel
import com.arkiv.player.ui.home.searchShortcutRoute
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRowBrowseScreen(
    rowId: String,
    title: String,
    onOpenSearchRoute: (String) -> Unit,
    onBack: () -> Unit,
    graph: AppGraph,
) {
    BackHandler { onBack() }

    val vm: RowBrowseViewModel = viewModel(
        key = rowId,
        factory = viewModelFactory { initializer { RowBrowseViewModel(rowId, graph.tmdbApi, graph.aniListApi) } },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val canLoadMore by vm.canLoadMore.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadMore() }

    val state = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= items.size - 10 && canLoadMore && !isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArkivBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Encabezado fijo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 32.dp, bottom = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }

            // Grid de contenido
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val cardHeight = 120.dp
                items(items, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                    val art = card.backdropUrl.ifBlank { card.posterUrl }
                    TvLandscapeCard(
                        title = card.title,
                        imageUrl = art,
                        cardHeight = cardHeight,
                        onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = ArkivRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        // Pista de navegación: "atrás para volver"
        Text(
            text = "← Atrás para volver",
            style = MaterialTheme.typography.labelSmall,
            color = ArkivTextSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = 24.dp),
        )
    }
}
```

- [ ] **Paso 2: Agregar `TvVerMasFilaCard` y el param `onBrowseRow` a `TvHomeScreen.kt`**

Localiza `TvVerMasCanalesCard` en `TvHomeScreen.kt` (~línea 902). Agrega después la nueva card para las filas de descubrimiento:

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVerMasFilaCard(
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight).onFocusChanged { if (it.isFocused) onFocus() },
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "Ver más",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
```

- [ ] **Paso 3: Agregar `onBrowseRow` a la firma de `TvHomeScreen`**

Localiza `fun TvHomeScreen(` en `TvHomeScreen.kt` (~línea 249) y agrega:
```kotlin
onBrowseRow: (rowId: String, title: String) -> Unit,
```

- [ ] **Paso 4: Agregar `TvVerMasFilaCard` al final de cada discovery row**

Localiza el bloque `items(discoveryRows, …) { spec ->` (~línea 783). Dentro del `LazyRow` de discovery rows, después del bloque `items(cards) { card -> TvLandscapeCard(…) }`, agrega:

```kotlin
item(key = "${spec.id}-ver-mas") {
    TvVerMasFilaCard(
        cardHeight = cardHeight,
        onFocus = {
            navSound()
            featured = Featured(
                spec.title,
                "Ver más de ${spec.title}",
                null,
            )
        },
        onClick = { onBrowseRow(spec.id, spec.title) },
    )
}
```

- [ ] **Paso 5: Verificar compilación**

```bash
./gradlew :app:compileDebugKotlin -x test 2>&1 | tail -15
```

Esperado: errores en `ArkivTvRoot.kt` (falta el param `onBrowseRow`) — se resuelve en Tarea 4.

- [ ] **Paso 6: Commit parcial**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvRowBrowseScreen.kt
git commit -m "feat(tv): TvRowBrowseScreen — grid paginado de descubrimiento para TV"
```

---

## Tarea 4: Rutas de navegación y cierre de compilación

**Archivos:**
- Modificar: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`
- Modificar: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`

**Interfaces:**
- Consume: `RowBrowseScreen`, `TvRowBrowseScreen` (Tareas 2 y 3); params nuevos `onBrowseRow` de `HomeScreen` y `TvHomeScreen`
- Produce: ruta `row_browse/{rowId}?title={title}` registrada en ambos NavHost

- [ ] **Paso 1: Agregar ruta en ArkivRoot.kt (móvil)**

Localiza el bloque `NavHost(navController = navController, startDestination = "home")` en `ArkivRoot.kt` (~línea 300).

Agrega el composable de la nueva ruta **antes del cierre del `NavHost`**:
```kotlin
composable(
    "row_browse/{rowId}?title={title}",
    arguments = listOf(
        navArgument("rowId") { type = NavType.StringType },
        navArgument("title") { type = NavType.StringType; defaultValue = "" },
    ),
) { entry ->
    val rowId = entry.arguments?.getString("rowId").orEmpty()
    val title = entry.arguments?.getString("title").orEmpty()
    RowBrowseScreen(
        rowId = rowId,
        title = title,
        onOpenSearchRoute = { route -> navController.navigate(route) },
        onBack = { navController.popBackStack() },
        graph = graph,
    )
}
```

- [ ] **Paso 2: Wirear `onBrowseRow` en la llamada a `HomeScreen` en ArkivRoot.kt**

Localiza la llamada a `HomeScreen(` dentro de `composable("home") {` (~línea 302) y agrega:
```kotlin
onBrowseRow = { rowId, title ->
    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
},
```

- [ ] **Paso 3: Agregar ruta en ArkivTvRoot.kt (TV)**

Localiza el bloque `NavHost(navController = navController, startDestination = "home")` en `ArkivTvRoot.kt` (~línea 183).

Agrega antes del cierre del `NavHost`:
```kotlin
composable(
    "row_browse/{rowId}?title={title}",
    arguments = listOf(
        navArgument("rowId") { type = NavType.StringType },
        navArgument("title") { type = NavType.StringType; defaultValue = "" },
    ),
) { entry ->
    val rowId = entry.arguments?.getString("rowId").orEmpty()
    val title = entry.arguments?.getString("title").orEmpty()
    TvRowBrowseScreen(
        rowId = rowId,
        title = title,
        onOpenSearchRoute = { route -> navController.navigate(route) },
        onBack = { navController.popBackStack() },
        graph = graph,
    )
}
```

- [ ] **Paso 4: Wirear `onBrowseRow` en la llamada a `TvHomeScreen` en ArkivTvRoot.kt**

Localiza la llamada a `TvHomeScreen(` dentro de `composable("home") {` (~línea 201) y agrega:
```kotlin
onBrowseRow = { rowId, title ->
    navController.navigate("row_browse/$rowId?title=${android.net.Uri.encode(title)}")
},
```

- [ ] **Paso 5: Verificar que el módulo compila limpio**

```bash
./gradlew :app:compileDebugKotlin -x test 2>&1 | tail -15
```

Esperado: `BUILD SUCCESSFUL`

- [ ] **Paso 6: Build e instalar en el Fire TV**

```bash
ANDROID_SERIAL=192.168.1.22:5555 ./gradlew :app:installDebug -x test 2>&1 | tail -10
```

- [ ] **Paso 7: Verificar en el TV (golden path)**

1. Abrir la app en el Fire TV
2. En la pantalla de inicio, navegar a cualquier fila de descubrimiento (ej. "Películas populares")
3. Ir hasta el final con el D-pad → debe aparecer una card "Ver más" con flecha
4. Seleccionarla → debe abrir `TvRowBrowseScreen` con el título de la fila
5. El grid muestra los primeros ~20 títulos; hacer scroll hacia abajo → carga más automáticamente
6. Seleccionar un título → debe ir al buscador/reproductor igual que desde el home

- [ ] **Paso 8: Verificar en el celular**

1. Abrir la app en el celular
2. En el home, hacer scroll hasta cualquier fila de descubrimiento
3. En el `LazyRow` de esa fila, scrollear al final → debe aparecer la card "Ver más" (póster gris con flecha)
4. Tocarla → abre `RowBrowseScreen` con la grilla de 3 columnas
5. Scrollear hacia abajo → carga más títulos
6. Tocar un título → va al buscador igual que desde el home

- [ ] **Paso 9: Verificar edge cases**

- Fila vacía (sin ítems cargados): la card "Ver más" no debe aparecer (`RemoteRow` ya retorna si `loaded && items.isEmpty()`)
- Última página de TMDB (resultado < 2 ítems): el spinner desaparece y no se sigue llamando a `loadMore()`

- [ ] **Paso 10: Commit final**

```bash
git add \
  app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt \
  app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt \
  app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt \
  app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
git commit -m "feat(home): card 'Ver más' en filas de descubrimiento — abre pantalla paginada"
```
