# Home y Buscador en el TV — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Llevar al TV (Fire Stick) el Home de descubrimiento con filas y un buscador de dos columnas estilo Amazon (teclado + resultados), navegable por control remoto, donde la fuente elegida se reproduce de inmediato — reutilizando toda la lógica ya construida para el celular.

**Architecture:** El TV comparte `HomeViewModel` y `SearchViewModel` con el teléfono; lo nuevo es **solo UI de TV** (Compose for TV, `androidx.tv:tv-material`). La lógica de reproducir/guardar que hoy vive dentro de `SearchScreen` se extrae a un helper compartido para que el TV la use sin duplicarla. El teclado en pantalla se modela como lógica pura (distribución de teclas + reductor de texto) para poder testearlo sin UI.

**Tech Stack:** Kotlin, Compose for TV (`androidx.tv:tv-material:1.0.0`) + Compose Foundation, Coroutines/Flow, Coil, JUnit4, Gradle, ADB contra el Fire Stick.

## Global Constraints

- **Commits con identidad `lordmacu`:** `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit …`. Nunca coautoría de Claude. **Nunca `git add -A`** — solo los archivos de la tarea.
- **Tests:** JUnit4. `./gradlew :app:testDebugUnitTest`. **Compilar:** `./gradlew :app:compileDebugKotlin`.
- **NO ejecutar adb/installDebug sin que el controlador lo indique** — el device puede estar en uso; la verificación en el Fire Stick la coordina el controlador.
- **Reutilización obligatoria:** `SearchViewModel`, `HomeViewModel`, `buildRowSpecs`/`LoadGuard`/`searchShortcutRoute`, `PackDetector`/`PackResolver`/`savePackAsSeries`, `TorrentEngine`, `EpisodeFilePicker`, `ArkivRepository`. **No** reimplementar búsqueda, motor, packs ni guardado.
- **No rediseñar el Home del TV:** se conserva el hero, los cards y el foco actuales; solo se agregan filas.
- **Compose for TV:** usar los componentes de `androidx.tv.material3` (`Card`, `Button`, `Text`, `MaterialTheme`) como el resto de `ui/tv/`, no los de `material3` normal.

## Contexto verificado (no re-investigar)

- `TvHomeScreen(onOpenItem, onPlayEpisode, onOpenSettings, onOpenTorrent)` **ya construye** `HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi)` → `rows`, `rowItems`, `rowsLoaded`, `loadRow()` ya disponibles.
- La zona de filas de `TvHomeScreen` es hoy un `Column(Modifier.height(rowsRegionHeight).verticalScroll(scroll))` — **compone todas las filas a la vez**. Con ~40 filas eso dispararía todas las cargas al abrir: hay que pasarla a `LazyColumn` (ver Task 3).
- Barra superior del TV: `Text("ARKIV")` + `Button("Torrent")` + `Button("Ajustes")` + botón de sincronizar.
- `ArkivTvRoot`: rutas `home`, `detail/{itemId}`, `settings`, `pairing`, `torrent`, `player/{episodeId}`; helper local `goToPlayer(id)`.
- Componentes TV: `TvWideCard(title, imageUrl, progress, cardHeight, modifier, onFocus, onClick)`, `TvLandscapeCard(title, imageUrl, cardHeight, modifier, badge, badgeColor, onFocus, onLongClick, onClick)`.
- `SearchViewModel` expone: `search(q)`, `pickTitle(card)`, `runSourceSearch(season, episode)`, `back()`, `phase`, `titleResults`, `directResults`, `sources`, `loadingTorrent/Web/Archive`, `selected`, `refineSeason/refineEpisode`, `detail`, `animeShow`, `startFromShortcut(kind, tmdbId, anilistId)`.

## Estructura de archivos

- **Crear:**
  - `ui/tv/TvKeyboard.kt` — distribución de teclas + reductor de texto (lógica pura) y el composable de la grilla.
  - `ui/tv/TvPosterCard.kt` — card de póster 2:3 para TV.
  - `ui/tv/TvSearchScreen.kt` — buscador de dos columnas (fases: títulos → temporada/capítulo → fuentes → capítulos de pack).
  - `ui/search/SearchPlayback.kt` — helper compartido de reproducir/guardar extraído de `SearchScreen`.
  - Tests: `TvKeyboardTest.kt`.
- **Modificar:**
  - `ui/search/SearchScreen.kt` — usar el helper extraído (sin cambio de comportamiento).
  - `ui/tv/TvHomeScreen.kt` — filas de descubrimiento + botón Buscar.
  - `ui/tv/ArkivTvRoot.kt` — ruta `search` (con args de atajo) y wiring.

---

## Task 1: Teclado del TV (lógica pura) + `TvPosterCard`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvKeyboard.kt`
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvPosterCard.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/tv/TvKeyboardTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface TvKey { data class Char(val c: Char); data object Space; data object Backspace }`
  - `val TV_KEYBOARD_ROWS: List<List<TvKey>>` — A-Z y 0-9 en grilla de 6 columnas, más espacio y borrar en la última fila.
  - `fun applyKey(text: String, key: TvKey): String`
  - `@Composable fun TvKeyboard(text: String, onTextChange: (String) -> Unit, modifier: Modifier)`
  - `@Composable fun TvPosterCard(title: String, posterUrl: String?, cardHeight: Dp, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit)`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvKeyboardTest {
    @Test fun `la grilla trae A-Z y 0-9 una sola vez`() {
        val chars = TV_KEYBOARD_ROWS.flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('A'..'Z').toList() + ('0'..'9').toList(), chars)
        assertEquals(chars.size, chars.toSet().size)
    }

    @Test fun `la grilla tiene espacio y borrar`() {
        val keys = TV_KEYBOARD_ROWS.flatten()
        assertTrue(keys.contains(TvKey.Space))
        assertTrue(keys.contains(TvKey.Backspace))
    }

    @Test fun `las filas tienen a lo sumo 6 columnas`() {
        assertTrue(TV_KEYBOARD_ROWS.all { it.size <= 6 })
    }

    @Test fun `escribir agrega la letra`() {
        assertEquals("SUP", applyKey("SU", TvKey.Char('P')))
    }

    @Test fun `espacio agrega un espacio`() {
        assertEquals("LA CASA", applyKey("LA", TvKey.Char('C')).let { applyKey("LA ", TvKey.Char('C')) }.let { "LA CASA" })
        assertEquals("LA ", applyKey("LA", TvKey.Space))
    }

    @Test fun `borrar quita el ultimo caracter`() {
        assertEquals("SU", applyKey("SUP", TvKey.Backspace))
    }

    @Test fun `borrar en vacio no rompe`() {
        assertEquals("", applyKey("", TvKey.Backspace))
    }
}
```
> Limpia el test de `espacio` si te queda enrevesado: lo que importa es `applyKey("LA", TvKey.Space) == "LA "`.

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.tv.TvKeyboardTest"` → FAIL (unresolved reference).

- [ ] **Step 3: Implementar la lógica pura en `TvKeyboard.kt`**

```kotlin
package com.arkiv.player.ui.tv

/** Una tecla del teclado en pantalla del TV. */
sealed interface TvKey {
    data class Char(val c: kotlin.Char) : TvKey
    data object Space : TvKey
    data object Backspace : TvKey
}

/** Grilla alfabética estilo Amazon: A-Z y 0-9 en 6 columnas, con espacio/borrar al final. */
val TV_KEYBOARD_ROWS: List<List<TvKey>> = buildList {
    val chars = (('A'..'Z') + ('0'..'9')).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/** Reductor puro del texto escrito con el control. */
fun applyKey(text: String, key: TvKey): String = when (key) {
    is TvKey.Char -> text + key.c
    TvKey.Space -> "$text "
    TvKey.Backspace -> text.dropLast(1)
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.ui.tv.TvKeyboardTest"` → PASS.

- [ ] **Step 5: El composable `TvKeyboard`**

En el mismo archivo, debajo de la lógica pura:
- Una `Column` con una `Row` por cada fila de `TV_KEYBOARD_ROWS`.
- Cada tecla es un `androidx.tv.material3.Button` (o `Card`) cuadrado con la letra centrada; `onClick = { onTextChange(applyKey(text, key)) }`.
- Las teclas Espacio/Borrar muestran `"␣"` y `"⌫"` (usa `contentDescription` accesible: "Espacio", "Borrar").
- Tamaño de tecla ~48-56dp, separación 8dp; usa `MaterialTheme` de `androidx.tv.material3`.
- Devuelve el `FocusRequester` de la primera tecla vía parámetro opcional si te resulta necesario para el foco inicial (o expón `firstKeyFocus: FocusRequester? = null`).

- [ ] **Step 6: `TvPosterCard`**

`TvPosterCard.kt`, copiando el patrón de `TvLandscapeCard` (mismo `Card` de tv-material, `CardDefaults.scale(focusedScale = 1.08f)`, `onFocusChanged`), pero con proporción **2:3** (ancho = alto × 2/3) y `AsyncImage` del póster; título debajo en 2 líneas máximo. Lee `TvComponents.kt` y respeta sus colores/estilos.

- [ ] **Step 7: Compilar y commitear**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(tv): teclado en pantalla (logica pura + grilla) y TvPosterCard" -- \
  app/src/main/java/com/arkiv/player/ui/tv/TvKeyboard.kt \
  app/src/main/java/com/arkiv/player/ui/tv/TvPosterCard.kt \
  app/src/test/java/com/arkiv/player/ui/tv/TvKeyboardTest.kt
```

---

## Task 2: Extraer la reproducción del buscador a un helper compartido

Hoy la lógica de "resolver la fuente → guardar → obtener episodeId" vive dentro de `SearchScreen`
(funciones locales `playTorrent`, `playArchive`/`playDirect`, `playWebResult`, `episodeNameFor`,
`seriesIdFor`). El TV necesita exactamente eso. **Extraerlo sin cambiar comportamiento.**

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`

**Interfaces:**
- Produces: `class SearchPlayback(private val graph: AppGraph)` con métodos **suspend** que devuelven
  `String?` (el episodeId listo para reproducir) o `null` si falló:
  - `suspend fun playTorrent(result: TorrentResult, card: TitleCard?, detail: TmdbDetail?, animeShow: AnimeShow?, season: Int?, episode: Int?): String?`
  - `suspend fun playArchive(item: ArchiveSearchResult): String?`
  - `suspend fun playWeb(result: WebResult, card: TitleCard?, season: Int?, episode: Int?): String?`
  - `suspend fun savePack(title: String, posterUrl: String, description: String?, contents: PackResolver.PackContents, rows: List<PackFileRow>): String`

> Ajusta los parámetros a lo que el código real necesita (mira las funciones actuales en `SearchScreen`);
> lo importante es que **no quede lógica de guardado/resolución dentro del composable**.

- [ ] **Step 1: Crear `SearchPlayback.kt` moviendo el cuerpo de esas funciones**

Copia la lógica **verbatim** (magnet → `addTorrentMagnet` sin envolver en `firstEpisodeId`; `.torrent`
→ `resolveTorrent`/`videoFiles`/`EpisodeFilePicker` → `addSeriesEpisode`/`addAnimeEpisode`/`addTorrent`
envuelto en `firstEpisodeId`; archive → `addItem` → `firstEpisodeId`; web → `addWebSeriesEpisode`…).
No cambies ninguna condición ni el orden de los casos.

- [ ] **Step 2: `SearchScreen` usa el helper**

Sustituye las funciones locales por llamadas al helper (`val playback = remember { SearchPlayback(graph) }`),
manteniendo el manejo de `preparing`/`error` y el `onPlay(epId)` donde están hoy.

- [ ] **Step 3: Verificar equivalencia**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL
Revisa el diff: para cada rama del `when` original debe existir la misma rama en el helper. Documenta
en el reporte cualquier diferencia (no debería haber ninguna).

- [ ] **Step 4: Commit**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "refactor(busqueda): extraer la reproduccion/guardado del buscador a SearchPlayback" -- \
  app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt \
  app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt
```

---

## Task 3: Filas de descubrimiento en el Home del TV + botón Buscar

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`

**Interfaces:**
- `TvHomeScreen(onOpenItem, onPlayEpisode, onOpenSettings, onOpenTorrent, onOpenSearch: () -> Unit, onOpenSearchRoute: (String) -> Unit)`

- [ ] **Step 1: La zona de filas pasa a `LazyColumn`**

Hoy es `Column(Modifier.height(rowsRegionHeight).verticalScroll(scroll))`, que **compone todas las
filas** — con ~40 filas dispararía todas las cargas al abrir y mataría el arranque. Cámbialo por un
`LazyColumn` con el **mismo alto fijo** (`Modifier.height(rowsRegionHeight)`) y el mismo padding.
Las secciones actuales (Continuar viendo, biblioteca) pasan a ser `item { … }` y **conservan su
contenido y su foco tal cual** (incluido el `focusRequester` de la primera card).

> Riesgo conocido: en TV, `LazyColumn` recicla y puede perder el foco al scrollear. Si al probar en el
> Fire Stick el foco se comporta mal, repórtalo — hay `Modifier.focusRestorer()` y `pivotOffsets` como
> herramientas, pero **no** cambies el diseño por tu cuenta.

- [ ] **Step 2: Agregar las filas de descubrimiento**

Después de las secciones actuales, dentro del `LazyColumn`:

```kotlin
items(rows, key = { it.id }) { spec ->
    val items = rowItems[spec.id].orEmpty()
    val loaded = spec.id in rowsLoaded
    LaunchedEffect(spec.id) { vm.loadRow(spec.id) }
    if (loaded && items.isEmpty()) return@items      // fila vacía/fallida -> se oculta
    TvRowLabel(spec.title, labelHeight)
    if (!loaded) {
        // placeholder de alto fijo para que el scroll no salte
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { "${spec.id}-${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                TvPosterCard(
                    title = card.title,
                    posterUrl = card.posterUrl,
                    cardHeight = cardHeight,
                    onFocus = { navSound() /* + actualizar el hero si aplica */ },
                    onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                )
            }
        }
    }
    Spacer(Modifier.height(rowGap))
}
```
Toma `rows`, `rowItems` y `rowsLoaded` del `vm` que la pantalla ya tiene (`collectAsStateWithLifecycle`).
Respeta los nombres reales de `labelHeight`, `cardHeight`, `rowGap`, `navSound()` que ya existen.

- [ ] **Step 3: Botón Buscar en la barra superior**

Junto a `Button("Torrent")` y `Button("Ajustes")`, agrega **como primero** (es la acción principal):
`Button(onClick = onOpenSearch) { Text("Buscar", maxLines = 1) }`.

- [ ] **Step 4: Wiring en `ArkivTvRoot`**

En `composable("home")`, pasar:
```kotlin
onOpenSearch = { navController.navigate("search") },
onOpenSearchRoute = { route -> navController.navigate(route) },
```
(La ruta `search` del TV se crea en la Task 4; si aún no existe, crea un placeholder mínimo para que
compile y complétalo allí, o haz esta tarea **después** de la 4 — decide y dilo en el reporte.)

- [ ] **Step 5: Compilar y commitear**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(tv): filas de descubrimiento en el home del TV + boton Buscar" -- \
  app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt \
  app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
```

---

## Task 4: `TvSearchScreen` — dos columnas (teclado + títulos)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`

**Interfaces:**
- `@Composable fun TvSearchScreen(onPlay: (String) -> Unit, onBack: () -> Unit, shortcutKind: String? = null, shortcutTmdbId: Int? = null, shortcutAnilistId: Long? = null)`

- [ ] **Step 1: Esqueleto y estado**

- `val graph = rememberGraph()`; VM: `viewModel(factory = viewModelFactory { initializer { SearchViewModel(graph.tmdbApi, graph.aniListApi, graph.torrentSearchApi, graph.api, graph.animeSourceProvider, graph.webSourceEngine, graph.settings) } })` — **usa la firma real** del constructor actual.
- Estado local: `var text by remember { mutableStateOf("") }`.
- Debounce: `LaunchedEffect(text) { delay(300); vm.search(text) }` (si `text` está en blanco, limpia).
- Atajo: `LaunchedEffect(shortcutKind, shortcutTmdbId, shortcutAnilistId) { shortcutKind?.let { vm.startFromShortcut(it, shortcutTmdbId, shortcutAnilistId) } }`.
- `LaunchedEffect(Unit) { graph.torrentEngine.warmUp() }`.

- [ ] **Step 2: Layout de dos columnas (fase títulos)**

`Row(Modifier.fillMaxSize())`:
- **Izquierda (~30% ancho):** el texto escrito arriba (o "Buscar…" si vacío) + `TvKeyboard(text, onTextChange = { text = it })`.
- **Derecha (resto):** grilla/fila(s) de `TvPosterCard` con `vm.titleResults`; al hacer click: `vm.pickTitle(card)`.
  Usa `LazyVerticalGrid` (foundation) o varias `LazyRow`; prioriza que el foco se mueva natural con el D-pad.
- Indicador de carga discreto mientras `loadingTitles`.
- Estado vacío: "Sin resultados" cuando ya cargó y no hay nada.

- [ ] **Step 3: Foco entre columnas**

- Foco inicial en la primera tecla (`FocusRequester` + `LaunchedEffect(Unit) { requestFocus() }`).
- El movimiento derecha/izquierda entre columnas lo resuelve el sistema de foco de Compose si ambos
  lados son focusables y están en un `Row`. Si en el device no salta bien, usa
  `Modifier.focusProperties { right = resultsFocus; left = keyboardFocus }` — **repórtalo** si hizo falta.
- `BackHandler`: si `vm.phase` no es la de títulos → `vm.back()`; si ya está en títulos → `onBack()`.

- [ ] **Step 4: Ruta `search` en `ArkivTvRoot` (con args de atajo)**

```kotlin
composable(
    "search?kind={kind}&tmdbId={tmdbId}&anilistId={anilistId}",
    arguments = listOf(
        navArgument("kind") { nullable = true; type = NavType.StringType; defaultValue = null },
        navArgument("tmdbId") { nullable = true; type = NavType.StringType; defaultValue = null },
        navArgument("anilistId") { nullable = true; type = NavType.StringType; defaultValue = null },
    ),
) { entry ->
    TvSearchScreen(
        onPlay = { goToPlayer(it) },
        onBack = { navController.popBackStack() },
        shortcutKind = entry.arguments?.getString("kind"),
        shortcutTmdbId = entry.arguments?.getString("tmdbId")?.toIntOrNull(),
        shortcutAnilistId = entry.arguments?.getString("anilistId")?.toLongOrNull(),
    )
}
```
Mismo patrón que la ruta `search` del teléfono (ya existe en `ui/ArkivRoot.kt`) — cópiala.

- [ ] **Step 5: Compilar y commitear**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(tv): buscador de dos columnas (teclado + titulos) con ruta y atajo" -- \
  app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt \
  app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
```

---

## Task 5: Selector visual de temporada/capítulo (serie y anime)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt`

- [ ] **Step 1: Fase REFINE del TV**

Cuando `vm.phase == SearchPhase.REFINE` y hay `selected`:
- Encabezado con póster + título de la card.
- **Serie (TMDB):** `LazyRow` de temporadas (`T1`, `T2`, …) construida desde `vm.detail?.seasons`
  (o `tmdbApi.detail` si el VM aún no lo tiene); al enfocar/elegir una temporada, cargar sus capítulos
  con `graph.tmdbApi.seasonEpisodes(tmdbId, season)` y mostrarlos en una lista vertical navegable
  (`"E{n} · {nombre}"`).
- **Anime:** lista de episodios `1..(animeShow?.episodes ?: 0)`; si no se conoce el total, muestra solo
  el botón de "Toda la serie".
- Botón **"Toda la serie"** arriba de la lista → `vm.runSourceSearch(null, null)`.
- Elegir un capítulo → `vm.runSourceSearch(season, episode)` (para anime: `runSourceSearch(null, episodio)`).

- [ ] **Step 2: Foco y atrás**

Foco inicial en "Toda la serie". `BackHandler` → `vm.back()` (vuelve a títulos).

- [ ] **Step 3: Compilar y commitear**

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(tv): selector visual de temporada y capitulo con el control" -- \
  app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt
```

---

## Task 6: Fuentes en TV, reproducción automática y packs

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt`

- [ ] **Step 1: Lista de fuentes**

Cuando `vm.phase == SearchPhase.RESULTS`:
- Encabezado: título de la card + `T{s} · E{e}` si aplica.
- Lista vertical navegable de `vm.sources`, con los **packs primero** (usa `packsFirst(...)` del celu si
  aplica al tipo, o `PackDetector.isPack` para ordenar: packs arriba, resto en el orden recibido).
- Cada fila muestra: etiqueta de origen (TORRENT/WEB/ARCHIVE), nombre, idioma, calidad, seeds, tamaño y
  badge **PACK** cuando corresponda (mismo contenido que el celu, con estilos de TV).
- Indicadores de carga por fuente mientras `loadingTorrent/Web/Archive`.

- [ ] **Step 2: Reproducción automática**

Al elegir una fuente **que no es pack**: `preparing = true` → usar `SearchPlayback` (Task 2) para
resolver/guardar → `onPlay(epId)` (que en el TV va directo al player). **Sin diálogo de dónde ver.**
Si falla, mostrar el mensaje y dejar el foco en la lista.

- [ ] **Step 3: Packs → lista de capítulos**

Al elegir una fuente **pack** (`PackDetector.isPack(result.name)`):
- Resolver con `graph.packResolver.resolve(result)` (mostrar "Leyendo el pack…" mientras).
- Mostrar la lista de `contents.rows` (`row.label`, calidad y tamaño) navegable con el D-pad.
- **Elegir un capítulo** → `savePackAsSeries(título, póster, sinopsis, infoHashHex, infoBytes, contents.rows)`
  y luego `onPlay("$itemId::${row.index}")` (mismo patrón que el celu en `onPlayOne`).
- Botón **"Guardar toda la serie"** → `savePackAsSeries(...)` con todas las filas y `onPlay(firstEpisodeId(itemId))`.
- Si el pack no se puede leer: mensaje y volver a la lista de fuentes.

- [ ] **Step 4: Compilar, tests y commit**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

```bash
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com \
  commit -m "feat(tv): fuentes con packs primero, reproduccion automatica y capitulos de pack" -- \
  app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt
```

- [ ] **Step 5: Lista de verificación en el Fire Stick (la ejecuta el controlador)**

En el reporte, deja la lista de lo que hay que probar con el control:
1. Home del TV: se ven las filas nuevas y **cargan al bajar** (no todas de golpe).
2. Botón **Buscar** abre el buscador con foco en el teclado.
3. Escribir con el D-pad filtra los títulos a la derecha; derecha/izquierda salta entre columnas.
4. Elegir una **película** → fuentes → elegir una → **reproduce sola**.
5. Elegir una **serie** → temporadas/capítulos → fuentes; y "Toda la serie" → packs.
6. Elegir un **pack** → lista de capítulos → uno reproduce; "Guardar toda la serie" lo deja en biblioteca.
7. Tocar una card del Home lleva al buscador ya posicionado en ese título.
8. **Atrás** retrocede paso a paso y sale del buscador al Home.

---

## Self-Review (cobertura vs spec)

- **Home del TV con las filas del celu, sin rediseñar:** Task 3. ✅
- **Carga perezosa real en TV (no todas de golpe):** Task 3 Step 1 (LazyColumn) + Step 2 (`loadRow` por fila). ✅
- **`TvPosterCard` (pósters 2:3):** Task 1 Step 6. ✅
- **Click en card del Home → buscador posicionado:** Task 3 Step 2 + Task 4 Step 4 (args de atajo). ✅
- **Buscador dos columnas con teclado alfabético estilo Amazon:** Task 1 (lógica + grilla) + Task 4. ✅
- **Búsqueda automática al escribir (debounce):** Task 4 Step 1. ✅
- **Selector visual de temporada/capítulo + "Toda la serie":** Task 5. ✅
- **Fuentes con packs primero:** Task 6 Step 1. ✅
- **Reproducción automática sin diálogo:** Task 6 Step 2. ✅
- **Packs: lista de capítulos + guardar toda la serie:** Task 6 Step 3. ✅
- **Reutilización sin duplicar lógica de reproducción:** Task 2 (`SearchPlayback` compartido). ✅
- **Botón Buscar en la barra superior:** Task 3 Step 3. ✅
- **Testing unitario del teclado:** Task 1. El resto se verifica compilando + en el Fire Stick. ✅
- **Foco/D-pad:** cubierto por los componentes de tv-material; los casos límite (LazyColumn perdiendo
  foco, salto entre columnas) están marcados como riesgos a reportar en Task 3 Step 1 y Task 4 Step 3. ⚠️
