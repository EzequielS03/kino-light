# Agrupar series duplicadas en la biblioteca — Plan de implementación

> **Para trabajadores agénticos:** SUB-SKILL REQUERIDA: usar `superpowers:subagent-driven-development` (recomendado) o `superpowers:executing-plans` para implementar tarea por tarea. Los pasos usan checkbox (`- [ ]`) para seguimiento.

**Goal:** Que una serie que entró a la biblioteca desde varias fuentes (web, torrent, alfa, archive) se vea como **una sola tarjeta** en el home de TV, y que su detalle deje elegir de qué fuente reproducir.

**Architecture:** Agrupación **en tiempo de lectura**, sin migración de datos y sin tocar el esquema. Una función pura deriva una "llave de grupo" por ítem a partir de su `identifier` y del `tmdbId` que la tabla `artwork` ya resolvió; el repositorio combina `observeLibrary()` con `observeArtwork()` y emite grupos; el home pinta un grupo por tarjeta y el detalle recibe la llave del grupo en vez de un `identifier`. Nada se borra ni se fusiona en la DB, así que **el sync a la nube y al celular no se ve afectado** y el cambio es 100% reversible.

**Tech Stack:** Kotlin, Compose (TV Material3), Room, Flows de kotlinx.coroutines, JUnit4 para tests JVM.

## Global Constraints

- **Identidad de git:** todo commit va con `user.name = lordmacu` y `user.email = 10134930+lordmacu@users.noreply.github.com`. Verificar con `git config user.email` antes del primer commit.
- **Sin coautoría:** ningún mensaje de commit lleva `Co-Authored-By`.
- **Sin migración de Room:** este plan NO sube `ArkivDatabase.version` (hoy 18) ni agrega columnas. Si una tarea parece necesitarlo, parar y replantear.
- **No agrupar películas.** Solo se agrupan ítems de serie (`!row.isMovie`). Razón medida abajo, en Evidencia.
- **Comentarios en español**, explicando el *porqué* (convención del repo).
- Tests JVM: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.<Clase>"`.

---

## Evidencia que fundamenta el diseño

Medido el 2026-08-10 sobre la DB real del Fire TV (90 ítems vivos):

- `items.tmdbId` está **NULL en los 90 ítems**: no sirve como llave hoy.
- `artwork` tiene fila para **90/90** ítems y `tmdbId` resuelto en **78/90**.
- **Con `tmdbType='tv'` la llave es fiable:** los 4 Naruto de serie caen en `tv:46260`; DAN DA DAN (`web:series:tt30217403` + `web:series:anilist171018`) en `tv:240411`; Get Backers (archive + web) en `tv:62883`. Las dos Ranma (1989 `tt0096686` y remake 2024 `tt32766897`) caen en ids **distintos**, o sea que el criterio no las fusiona mal.
- **Con `tmdbType='movie'` NO es fiable:** `movie:324849` junta "Lego Batman: la película" con "Batman: La película (1966)", y `movie:123025` junta "El caballero oscuro" con "Batman: El regreso del Caballero Oscuro, Parte 1". Son obras distintas. Por eso las películas quedan fuera.
- Los dos "Naruto — Pack" tienen `tmdbId` NULL porque `cleanTitleForSearch` normaliza el guion `-` pero **no la raya larga `—`**, así que busca literalmente "Naruto — Pack" en TMDB y no matchea. Lo arregla la Tarea 5.
- El duplicado de DAN DA DAN es **dato legacy**: `SeriesItemIds.canonicalSeriesId` ya unificó el seriesId en el commit `0081dd75` (2026-08-07), pero solo para altas nuevas. Este plan lo resuelve mostrándolo agrupado, sin tocar las filas.

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt` | **Crear.** Lógica pura: llave de grupo por ítem, y armado/orden de los grupos. Sin Android, sin Room. |
| `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt` | **Crear.** Tests de la lógica pura, con los casos reales medidos arriba. |
| `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` | **Modificar.** `observeLibraryGroups()` (combina library+artwork) y `observeGroupDetail()` (une capítulos de todos los miembros). |
| `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt` | **Modificar.** Exponer `libraryGroups` además de `library`. |
| `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` | **Modificar.** La sección "Series" pinta grupos; badge con nº de fuentes. |
| `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` | **Modificar.** Ruta `detail/{id}` acepta también una llave de grupo. |
| `app/src/main/java/com/arkiv/player/ui/detail/DetailViewModel.kt` | **Modificar.** Cargar por llave de grupo y exponer las fuentes. |
| `app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt` | **Modificar.** Selector de fuente arriba de la lista de capítulos. |

---

### Task 1: Llave de grupo y armado de grupos (lógica pura)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt`
- Test: `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt`

**Interfaces:**
- Consumes: `LibraryRow` (`com.arkiv.player.data.db.LibraryRow`), `ArtworkEntity` (`com.arkiv.player.data.db.ArtworkEntity`), `SeriesItemIds.seriesIdOrNull`.
- Produces: `LibraryGroup(key: String, primary: LibraryRow, members: List<LibraryRow>)` con `sourceCount: Int` y `episodeCount: Int`; `LibraryGrouping.groupKeyOf(row, artwork): String`; `LibraryGrouping.group(rows, artwork): List<LibraryGroup>`.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Agrupación de la biblioteca: la MISMA serie entrada por varias fuentes tiene que dar UNA tarjeta.
 * Los casos son los medidos en la DB real del Fire TV el 2026-08-10 (ver el plan).
 */
class LibraryGroupingTest {

    private fun row(
        id: String,
        title: String,
        eps: Int,
        source: String = "web",
        category: String? = "series",
        addedAt: Long = 0L,
    ) = LibraryRow(
        identifier = id,
        title = title,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = category,
        source = source,
    )

    private fun art(id: String, tmdbId: Int?, type: String?) =
        ArtworkEntity(itemId = id, tmdbId = tmdbId, tmdbType = type, backdropsJson = "[]", fetchedAt = 0L)

    @Test
    fun `una serie con tmdbId de tv resuelto agrupa por ese id`() {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(a, artwork[a.identifier]))
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(b, artwork[b.identifier]))
    }

    /** El resolver de artwork confunde películas distintas (Lego Batman vs Batman 1966): nunca agrupar. */
    @Test
    fun `las peliculas nunca se agrupan aunque compartan tmdbId`() {
        val a = row("torrent:aaa", "Lego Batman: la película", 1, source = "torrent", category = null)
        val b = row("torrent:bbb", "Batman: La película (1966)", 1, source = "torrent", category = null)
        val artA = art(a.identifier, 324849, "movie")
        val artB = art(b.identifier, 324849, "movie")
        assertEquals("item:torrent:aaa", LibraryGrouping.groupKeyOf(a, artA))
        assertEquals("item:torrent:bbb", LibraryGrouping.groupKeyOf(b, artB))
    }

    /** Sin artwork resuelto se cae al seriesId del identifier, que es exacto. */
    @Test
    fun `sin tmdbId cae al seriesId del identifier`() {
        val r = row("web:series:tt0409591", "Naruto", 300)
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, art(r.identifier, null, null)))
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, null))
    }

    /** Sin nada de lo anterior, el ítem es su propio grupo (comportamiento de hoy). */
    @Test
    fun `sin tmdbId ni seriesId el item queda solo`() {
        val r = row("alfa:series:jkanime:3f7d7ee2", "Naruto", 1, source = "alfa")
        assertEquals("item:alfa:series:jkanime:3f7d7ee2", LibraryGrouping.groupKeyOf(r, null))
    }

    /** Series distintas con el mismo título no se fusionan: Ranma 1989 y el remake de 2024. */
    @Test
    fun `dos series homonimas con tmdbId distinto quedan separadas`() {
        val vieja = row("web:series:tt0096686", "Ranma ½", 161)
        val nueva = row("web:series:tt32766897", "Ranma1/2", 24)
        val grupos = LibraryGrouping.group(
            listOf(vieja, nueva),
            mapOf(
                vieja.identifier to art(vieja.identifier, 33840, "tv"),
                nueva.identifier to art(nueva.identifier, 240909, "tv"),
            ),
        )
        assertEquals(2, grupos.size)
    }

    @Test
    fun `el representante del grupo es el de mas capitulos`() {
        val pocos = row("web:series:anilist171018", "DAN DA DAN", 1, addedAt = 200)
        val muchos = row("web:series:tt30217403", "DAN DA DAN", 24, addedAt = 100)
        val grupos = LibraryGrouping.group(
            listOf(pocos, muchos),
            mapOf(
                pocos.identifier to art(pocos.identifier, 240411, "tv"),
                muchos.identifier to art(muchos.identifier, 240411, "tv"),
            ),
        )
        assertEquals(1, grupos.size)
        assertEquals("web:series:tt30217403", grupos[0].primary.identifier)
        assertEquals(2, grupos[0].sourceCount)
        assertEquals(25, grupos[0].episodeCount)
    }

    /** El orden del home es por lo más reciente del grupo, para que agrupar no reordene la fila. */
    @Test
    fun `los grupos salen ordenados por el miembro mas reciente`() {
        val viejo = row("web:series:tt1", "Vieja", 10, addedAt = 100)
        val nuevo = row("web:series:tt2", "Nueva", 10, addedAt = 300)
        val grupos = LibraryGrouping.group(listOf(viejo, nuevo), emptyMap())
        assertEquals(listOf("Nueva", "Vieja"), grupos.map { it.primary.title })
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: FALLA con "Unresolved reference: LibraryGrouping".

- [ ] **Step 3: Implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow

/**
 * Una serie de la biblioteca vista como UNA sola cosa, con todas las adquisiciones que la
 * componen. [primary] es la que se muestra y se abre por defecto; [members] son todas (incluida
 * [primary]), que es lo que alimenta el selector de fuente del detalle.
 */
data class LibraryGroup(
    val key: String,
    val primary: LibraryRow,
    val members: List<LibraryRow>,
) {
    /** Cuántas adquisiciones distintas hay detrás de la tarjeta (1 = no está agrupada). */
    val sourceCount: Int get() = members.size

    /** Suma de capítulos de todas las fuentes: es lo que el usuario puede ver en total. */
    val episodeCount: Int get() = members.sumOf { it.episodeCount }
}

/**
 * Agrupa la biblioteca para que una misma serie entrada desde varias fuentes (web, torrent, alfa,
 * archive) sea una sola tarjeta.
 *
 * Los `identifier` se prefijan A PROPÓSITO por fuente para que no colisionen en `items` (ver
 * [SeriesItemIds]); eso está bien para guardar, pero el home no debería mostrarlos por separado.
 * Acá se los junta SOLO para mostrar: no se toca ni se borra ninguna fila, así que el sync no se
 * entera y esto es reversible.
 */
object LibraryGrouping {

    /**
     * Llave por la que se juntan dos ítems.
     *
     * Orden de preferencia, medido contra la biblioteca real (2026-08-10):
     *  1. **Películas: nunca.** El `tmdbId` de `artwork` se resuelve buscando por título y en
     *     películas se equivoca — junta "Lego Batman" con "Batman (1966)" bajo `movie:324849`.
     *     Agruparlas sería peor que el duplicado, así que cada película es su propio grupo.
     *  2. **`tmdbId` de `artwork` con `tmdbType == "tv"`.** En series sí acierta (los 4 Naruto en
     *     `tv:46260`, DAN DA DAN en `tv:240411`) y es lo ÚNICO que cruza fuentes distintas, porque
     *     no depende del prefijo del identifier. Ranma 1989 y el remake 2024 caen en ids distintos,
     *     así que no las fusiona.
     *  3. **seriesId del identifier**, que es exacto pero solo existe en `web:series:` y
     *     `torrent:series:`.
     *  4. El propio identifier: grupo de uno, o sea lo que hace la app hoy.
     */
    fun groupKeyOf(row: LibraryRow, artwork: ArtworkEntity?): String {
        if (row.isMovie) return "item:${row.identifier}"
        val tvId = artwork?.tmdbId?.takeIf { artwork.tmdbType == "tv" }
        if (tvId != null) return "tv:$tvId"
        SeriesItemIds.seriesIdOrNull(row.identifier)?.let { return "series:$it" }
        return "item:${row.identifier}"
    }

    /**
     * Arma los grupos preservando el orden de entrada por lo más reciente de cada grupo: agrupar no
     * debe reordenar la fila del home, que ya viene ordenada por `addedAt DESC`.
     *
     * El representante es el de MÁS capítulos (con más capítulos = la adquisición más completa; es
     * la que conviene abrir), y a igualdad de capítulos, el más reciente.
     */
    fun group(rows: List<LibraryRow>, artwork: Map<String, ArtworkEntity>): List<LibraryGroup> =
        rows.groupBy { groupKeyOf(it, artwork[it.identifier]) }
            .map { (key, members) ->
                LibraryGroup(
                    key = key,
                    primary = members.maxWith(compareBy({ it.episodeCount }, { it.addedAt })),
                    members = members,
                )
            }
            .sortedByDescending { g -> g.members.maxOf { it.addedAt } }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt
git commit -m "feat(biblioteca): llave de agrupacion de series por tmdbId de tv"
```

---

### Task 2: `observeLibraryGroups()` en el repositorio

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (al lado de `observeLibrary()`, línea ~71)
- Test: `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt` (agregar al final de la clase)

**Interfaces:**
- Consumes: `LibraryGrouping.group` (Tarea 1), `ArkivRepository.observeLibrary()`, `ArkivRepository.observeArtwork()`.
- Produces: `ArkivRepository.observeLibraryGroups(): Flow<List<LibraryGroup>>`.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `LibraryGroupingTest.kt` (dentro de la clase, antes del `}` final):

```kotlin
    /**
     * El combine de los dos flows: si el arte llega DESPUÉS que la biblioteca (que es lo normal —
     * `ensureArtwork` sale a la red), el grupo tiene que recalcularse solo. Si no, el home se
     * queda con las tarjetas separadas hasta reabrir la app.
     */
    @Test
    fun `los grupos se recalculan cuando llega el artwork`() = kotlinx.coroutines.runBlocking {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ArtworkEntity>>(emptyMap())
        val flow = LibraryGrouping.groupsFlow(
            kotlinx.coroutines.flow.flowOf(listOf(a, b)),
            artwork,
        )
        val emissions = mutableListOf<List<LibraryGroup>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            flow.collect { emissions += it }
        }
        assertEquals(2, emissions.last().size)
        artwork.value = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals(1, emissions.last().size)
        job.cancel()
    }
```

Agregar los imports que faltan arriba del archivo de test:

```kotlin
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: FALLA con "Unresolved reference: groupsFlow".

- [ ] **Step 3: Implementación mínima**

Agregar a `LibraryGrouping` (en `LibraryGrouping.kt`), después de `group`:

```kotlin
    /**
     * Los dos flows combinados. Vive acá (y no en el repositorio) para poder testearlo sin Room:
     * el repositorio solo lo cablea con sus DAOs.
     */
    fun groupsFlow(
        rows: kotlinx.coroutines.flow.Flow<List<LibraryRow>>,
        artwork: kotlinx.coroutines.flow.Flow<Map<String, ArtworkEntity>>,
    ): kotlinx.coroutines.flow.Flow<List<LibraryGroup>> =
        kotlinx.coroutines.flow.combine(rows, artwork) { r, a -> group(r, a) }
```

Agregar a `ArkivRepository`, justo debajo de `observeLibrary()`:

```kotlin
    /**
     * La biblioteca ya agrupada: una entrada por serie, no por adquisición. Ver [LibraryGrouping].
     * `observeLibrary()` sigue existiendo para quien necesite las filas crudas (la pantalla de
     * biblioteca del teléfono, el sync).
     */
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork())
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt
git commit -m "feat(biblioteca): observeLibraryGroups combina biblioteca y artwork"
```

---

### Task 3: El home de TV pinta grupos en la fila "Series"

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt:28`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` (`series`/`movies`, `TvLibrarySection` en :540, `open()` en :161)

**Interfaces:**
- Consumes: `ArkivRepository.observeLibraryGroups()` (Tarea 2), `LibraryGroup` (Tarea 1).
- Produces: `HomeViewModel.libraryGroups: StateFlow<List<LibraryGroup>>`. La fila "Series" del home pasa a renderizar `LibraryGroup`; "Películas" sigue con `LibraryRow`.

- [ ] **Step 1: Exponer los grupos en el ViewModel**

En `HomeViewModel.kt`, debajo de `library` (línea 28):

```kotlin
    /**
     * La biblioteca agrupada por serie (una entrada por show, no por adquisición). El home de TV
     * usa esto para la fila de Series; `library` se conserva porque las películas y el destacado
     * siguen razonando por ítem.
     */
    val libraryGroups: StateFlow<List<LibraryGroup>> = repo.observeLibraryGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Agregar el import `com.arkiv.player.data.LibraryGroup`.

- [ ] **Step 2: Renderizar los grupos en la fila de Series**

En `TvHomeScreen.kt`, donde hoy se calculan `series` y `movies` a partir de `library`, dejar `movies` como está y derivar `series` de los grupos:

```kotlin
    val libraryGroups by vm.libraryGroups.collectAsStateWithLifecycle()
    // Películas por ítem (no se agrupan: ver LibraryGrouping.groupKeyOf), series por grupo.
    val seriesGroups = libraryGroups.filter { !it.primary.isMovie }
```

`val series = library.filter { !it.isMovie }` (línea 176) queda sin uso: se borra. Sus tres usos (181, 358, 362) los reemplazan los pasos de esta tarea. `val movies` (línea 175) se conserva tal cual.

Reemplazar la llamada a `TvLibrarySection` de Series (línea ~360) por una variante que toma grupos:

```kotlin
                if (seriesGroups.isNotEmpty()) {
                    item(key = "lib_series") {
                        TvSeriesSection(
                            rows = seriesGroups,
                            cardHeight = cardHeight,
                            labelHeight = labelHeight,
                            rowGap = rowGap,
                            firstFocusId = firstPosterId,
                            firstFocus = firstCardFocus,
                            imageFor = { cardArt(it.primary.identifier, it.primary.thumbnailUrl) },
                            onFocusRow = { navSound(); featured = libraryFeatured(it.primary) },
                            onClickRow = { onOpenItem(it.key) },
                            onLongClickRow = { menuRow = it.primary },
                        )
                    }
                }
```

Y agregar la sección, al lado de `TvLibrarySection`:

```kotlin
/**
 * La fila de Series del home. Igual que [TvLibrarySection] pero por grupo: cuando una serie tiene
 * más de una fuente, el badge lo dice ("3 FUENTES") y el contador suma los capítulos de todas.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeriesSection(
    rows: List<LibraryGroup>,
    cardHeight: androidx.compose.ui.unit.Dp,
    labelHeight: androidx.compose.ui.unit.Dp,
    rowGap: androidx.compose.ui.unit.Dp,
    firstFocusId: String?,
    firstFocus: FocusRequester,
    imageFor: (LibraryGroup) -> String?,
    onFocusRow: (LibraryGroup) -> Unit,
    onClickRow: (LibraryGroup) -> Unit,
    onLongClickRow: (LibraryGroup) -> Unit,
) {
    TvRowLabel("Series", labelHeight)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(rows, key = { it.key }) { group ->
            val multi = group.sourceCount > 1
            TvLandscapeCard(
                title = group.primary.title,
                imageUrl = imageFor(group),
                cardHeight = cardHeight,
                badge = if (multi) "${group.sourceCount} FUENTES" else if (group.primary.isTorrent) "TORRENT" else "SERIE",
                badgeColor = if (group.primary.isTorrent && !multi) TorrentBadgeColor else SeriesBadgeColor,
                episodeCountLabel = "${group.episodeCount} ep.",
                modifier = if (group.key == firstFocusId) Modifier.focusRequester(firstFocus) else Modifier,
                onFocus = { onFocusRow(group) },
                onLongClick = { onLongClickRow(group) },
                onClick = { onClickRow(group) },
            )
        }
    }
    Spacer(Modifier.height(rowGap))
}
```

- [ ] **Step 3: Ajustar `firstPosterId`**

`firstPosterId` (TvHomeScreen.kt:180) hoy sale de `series`/`movies` como `LibraryRow` y se compara contra `row.identifier`. La fila de Series ahora compara contra `group.key`, así que hay que cambiar de qué se saca. Reemplazar:

```kotlin
    val firstPosterId = if (continueWatching.isEmpty()) {
        (series.firstOrNull() ?: movies.firstOrNull())?.identifier
    } else {
        null
    }
```

por:

```kotlin
    // El orden acá DEBE seguir al de render (series antes que películas). La fila de Series se
    // identifica por la llave del grupo y la de Películas por el identifier del ítem: son espacios
    // distintos, pero nunca se comparan entre sí porque cada sección solo mira sus propias tarjetas.
    val firstPosterId = if (continueWatching.isEmpty()) {
        seriesGroups.firstOrNull()?.key ?: movies.firstOrNull()?.identifier
    } else {
        null
    }
```

- [ ] **Step 4: Compilar y verificar en la TV**

```bash
./gradlew :app:assembleDebug
```

Instalar en el Fire TV (IP dinámica; redescubrir si falla). El APK es grande, así que se sube y se verifica antes de instalar:

```bash
adb -s 192.168.1.22:5555 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/arkiv.apk
```

```bash
adb -s 192.168.1.22:5555 shell pm install -r /data/local/tmp/arkiv.apk
```

```bash
adb -s 192.168.1.22:5555 shell monkey -p com.arkiv.player -c android.intent.category.LEANBACK_LAUNCHER 1
```

Verificar con `adb -s 192.168.1.22:5555 exec-out screencap -p > /tmp/home.png` que en la fila Series:
- Hay **una sola** tarjeta DAN DA DAN, con "2 FUENTES" y 25 ep.
- Hay **una sola** de Naruto con "4 FUENTES" (los dos packs sin `tmdbId` siguen aparte hasta la Tarea 5).
- Las dos Ranma siguen siendo dos tarjetas.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt
git commit -m "feat(home tv): una tarjeta por serie aunque tenga varias fuentes"
```

---

### Task 4: Detalle por grupo, con selector de fuente

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (junto a `observeItemDetail`, :714)
- Modify: `app/src/main/java/com/arkiv/player/ui/detail/DetailViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` (ruta `detail/{id}`)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt`

**Interfaces:**
- Consumes: `LibraryGroup` (Tarea 1), `observeLibraryGroups()` (Tarea 2), `observeItemDetail(identifier)`.
- Produces: `ArkivRepository.observeGroupMembers(groupKey: String): Flow<List<LibraryRow>>`; `DetailViewModel.sources: StateFlow<List<LibraryRow>>` y `DetailViewModel.selectSource(identifier: String)`.

- [ ] **Step 1: Resolver la llave de grupo a sus miembros**

La ruta `detail/{id}` ahora recibe una llave de grupo (`tv:46260`, `series:tt…` o `item:<identifier>`). Agregar en `ArkivRepository`, debajo de `observeLibraryGroups()`:

```kotlin
    /**
     * Los ítems detrás de una llave de grupo, del más completo al menos.
     *
     * Acepta TAMBIÉN un identifier crudo: "Continuar viendo", el menú de mantener presionado y el
     * detalle del teléfono navegan con el identifier del ítem, no con una llave de grupo. Si no
     * matchea ninguna de las dos cosas devuelve vacío (por ejemplo si se borró la única fuente
     * mientras el detalle estaba abierto).
     */
    fun observeGroupMembers(groupKey: String): Flow<List<LibraryRow>> =
        combine(observeLibrary(), observeLibraryGroups()) { rows, groups ->
            groups.firstOrNull { it.key == groupKey }?.members?.sortedByDescending { it.episodeCount }
                ?: rows.filter { it.identifier == groupKey }
        }
```

- [ ] **Step 2: El ViewModel elige una fuente dentro del grupo**

En `DetailViewModel.kt`, cambiar el constructor para aceptar una llave de grupo además del identifier, manteniendo el camino viejo para quien navegue con un identifier crudo (el teléfono):

```kotlin
class DetailViewModel(
    private val repo: ArkivRepository,
    /** Llave de grupo (`tv:46260`) o identifier crudo: `item:<id>` y los identifiers sueltos resuelven a un solo miembro. */
    private val groupKey: String,
) : ViewModel() {

    /** Todas las adquisiciones de esta serie; alimenta el selector de fuente. */
    val sources: StateFlow<List<LibraryRow>> = repo.observeGroupMembers(groupKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** La fuente elegida a mano; si es null se usa la primera de [sources] (la más completa). */
    private val _selected = MutableStateFlow<String?>(null)

    /** Identifier del ítem que se está mostrando. */
    val selectedId: StateFlow<String?> = combine(sources, _selected) { list, manual ->
        manual?.takeIf { id -> list.any { it.identifier == id } } ?: list.firstOrNull()?.identifier
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val detail: StateFlow<ItemDetail?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeItemDetail(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val skipMarker: StateFlow<SkipMarkerEntity?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeSkipMarker(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectSource(identifier: String) { _selected.value = identifier }
```

Imports nuevos en `DetailViewModel.kt`: `com.arkiv.player.data.db.LibraryRow`, `kotlinx.coroutines.flow.MutableStateFlow`, `combine`, `flatMapLatest`, `flowOf`, `filterNotNull`, `first`. `flatMapLatest` sigue siendo experimental, así que la clase lleva `@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)`.

El resto de los métodos pasan a operar sobre `selectedId.value`, saliendo temprano si es null (`toggleWatched` no, porque ya recibe el `episodeId` y no depende del ítem):

```kotlin
    fun saveSkipMarker(openingStartMs: Long?, openingEndMs: Long?, endingStartMs: Long?) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.saveSkipMarker(id, openingStartMs, openingEndMs, endingStartMs) }
    }

    fun toggleWatched(episodeId: String, watched: Boolean) {
        viewModelScope.launch { repo.setWatched(episodeId, watched) }
    }

    /** Borra SOLO la fuente que se está viendo. Si era la última del grupo, la tarjeta desaparece. */
    fun removeFromLibrary(onDone: () -> Unit) {
        val id = selectedId.value ?: return onDone()
        viewModelScope.launch {
            repo.removeItem(id)
            onDone()
        }
    }

    fun rename(title: String) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.renameItem(id, title) }
    }

    fun refresh() {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.refreshItem(id) }
    }
```

`refresh()` en el `init` tiene que esperar a que `selectedId` tenga valor:

```kotlin
    init {
        // El refresh necesita saber QUÉ ítem refrescar, y eso llega con el primer valor de
        // selectedId (viene de la DB, no es inmediato). filterNotNull().first() evita refrescar
        // null y evita que el detalle quede sin recargar si el grupo tarda en resolverse.
        viewModelScope.launch {
            val id = selectedId.filterNotNull().first()
            repo.refreshItem(id)
        }
    }
```

- [ ] **Step 3: El selector en la pantalla de TV**

En `TvDetailScreen.kt`, arriba de la lista de capítulos, mostrar una fila de chips solo cuando haya más de una fuente:

```kotlin
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    if (sources.size > 1) {
        TvRowLabel("Fuentes", labelHeight)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sources, key = { it.identifier }) { src ->
                // El nombre de la fuente + cuántos capítulos aporta: es lo que deja decidir
                // (ej. "web · 300 ep." vs "torrent · 267 ep.").
                TvSourceChip(
                    label = "${src.source} · ${src.episodeCount} ep.",
                    selected = src.identifier == selectedId,
                    onClick = { vm.selectSource(src.identifier) },
                )
            }
        }
    }
```

`TvEpisodeChip` es específico de episodios (miniatura, progreso, número), así que no sirve tal cual. Crear `TvSourceChip` en el mismo archivo `TvEpisodeChip.kt`, copiando su manejo de foco y sus colores:

```kotlin
/**
 * Chip de una fuente de la serie ("web · 300 ep."), para elegir de cuál ver los capítulos cuando
 * la misma serie entró a la biblioteca desde varias. Mismo tratamiento de foco que
 * [TvEpisodeChip]: solo avisa al GANAR el foco, porque el "perdí" del chip viejo llega después
 * del "gané" del nuevo y dejaría la pantalla mostrando la fuente equivocada.
 */
@Composable
fun TvSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ArkivRed.copy(alpha = 0.25f) else ArkivSurfaceHigh)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}
```

Verificar los imports que ya están en `TvEpisodeChip.kt` (`Box`, `clickable`, `focusable`, `border`, `Color`, `MaterialTheme`) y agregar los que falten.

- [ ] **Step 4: Pasar la llave de grupo por la ruta**

La ruta `detail/{id}` de `ArkivTvRoot.kt` ya recibe un string arbitrario y el home ya manda `group.key` (Tarea 3), así que **no hay que cambiar la ruta**: solo el nombre del parámetro con el que se construye el `DetailViewModel`, de `identifier` a `groupKey`. Buscar la construcción con:

```bash
grep -rn "DetailViewModel(" app/src/main/java/com/arkiv/player/ui/
```

y renombrar el argumento en cada sitio. Los que navegan con un identifier crudo ("Continuar viendo", el menú de mantener presionado, y la pantalla de detalle del teléfono) **no se tocan**: `observeGroupMembers` ya resuelve ese caso por el `rows.filter { it.identifier == groupKey }` del Step 1.

Ojo con la llave `item:<identifier>` que produce `groupKeyOf` para los grupos de uno: `observeGroupMembers` la matchea por la rama de grupos (la llave existe en `observeLibraryGroups()`), así que no hace falta pelarle el prefijo `item:` en ningún lado.

- [ ] **Step 5: Compilar, instalar y verificar**

```bash
./gradlew :app:assembleDebug
```

Instalar como en la Tarea 3 y verificar con el D-pad (`input keyevent 20` para bajar, `23` para OK; `input tap` NO funciona en Fire TV): abrir la tarjeta de Naruto, ver la fila "Fuentes" con 4 chips, cambiar de chip y confirmar que la lista de capítulos cambia.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/ui/detail/DetailViewModel.kt app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
git commit -m "feat(detalle tv): elegir de que fuente ver una serie agrupada"
```

---

### Task 5: Que los packs de torrent también resuelvan su TMDB id

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt:210-220` (`cleanTitleForSearch`)
- Test: `app/src/test/java/com/arkiv/player/data/CleanTitleForSearchTest.kt` (crear)

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `cleanTitleForSearch` deja de ser `private` (pasa a `internal`) para poder testearla.

**Contexto:** "Naruto — Pack" no resuelve `tmdbId` porque la función normaliza el guion `-` pero no la raya larga `—`, y además el sufijo "Pack" no es parte del título. Por eso esos dos ítems no se agrupan con los otros 4 Naruto.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/CleanTitleForSearchTest.kt`:

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El título que se le manda a TMDB para resolver el arte. El bug real: "Naruto — Pack" no matcheaba
 * nada (la raya larga `—` no se normalizaba y "Pack" no es parte del título), así que esos ítems
 * quedaban sin `tmdbId` y no se agrupaban con el resto de la serie.
 */
class CleanTitleForSearchTest {

    @Test
    fun `quita el sufijo de pack con raya larga`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto — Pack"))
        assertEquals("Los Simpson", cleanTitleForSearch("Los Simpson — Pack"))
    }

    @Test
    fun `no toca un titulo que ya esta limpio`() {
        assertEquals("Naruto", cleanTitleForSearch("Naruto"))
        assertEquals("DAN DA DAN", cleanTitleForSearch("DAN DA DAN"))
    }

    /** La raya larga en medio del título no es un sufijo de pack: no se corta la parte de atrás. */
    @Test
    fun `una raya larga que no es sufijo de pack se conserva como separador`() {
        assertEquals("Naruto Shippuden", cleanTitleForSearch("Naruto — Shippuden"))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.CleanTitleForSearchTest"
```

Esperado: FALLA — `cleanTitleForSearch` es privada ("Cannot access 'cleanTitleForSearch'").

- [ ] **Step 3: Implementación mínima**

En `ArkivRepository.kt`, sacar `cleanTitleForSearch` de la clase, dejarla como función `internal` de fichero, y agregar la limpieza del pack **antes** del resto:

```kotlin
/**
 * Título "desnudo" para buscar en TMDB.
 *
 * El sufijo " — Pack" lo pone la app al guardar un torrent que trae la serie entera; no es parte
 * del nombre y sin quitarlo TMDB no devuelve nada (verificado: los dos "Naruto — Pack" de la
 * biblioteca quedaron sin tmdbId y por eso no se agrupaban con el resto de los Naruto).
 */
internal fun cleanTitleForSearch(raw: String): String {
    // Solo el SUFIJO: una raya larga en medio del título es un separador legítimo.
    var s = raw.replace(Regex("""\s*[—–-]\s*Pack\s*$""", RegexOption.IGNORE_CASE), "")
    s = s.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace('—', ' ').replace('–', ' ')
    Regex("""\b(19|20)\d{2}\b""").find(s)?.let { s = s.substring(0, it.range.first) }
    val noise = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|bluray|blu ray|brrip|bdrip|webrip|web dl|web|hdrip|dvdrip|hdtv|latino|castellano|espanol|español|dual|multi|subs?|ac3|aac|dts|yify|rarbg|proper|remux)\b""",
    )
    s = s.replace(noise, " ")
    return s.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.CleanTitleForSearchTest"
```

Esperado: PASS, 3 tests. Después correr toda la suite para no romper nada que dependa del arte:

```bash
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 5: Forzar el re-resolve del arte de los ítems que quedaron sin match**

`ensureArtwork` se salta los ítems que ya tienen fila, incluso si quedó vacía (`tmdbId` NULL). Para que los packs se resuelvan con el título ya limpio, hay que reintentarlos. Agregar en `ensureArtwork`, en el `continue` de la línea 98:

```kotlin
            // Un arte YA resuelto no se vuelve a pedir. Uno que quedó sin match (tmdbId null) sí se
            // reintenta, pero solo si la fila es vieja: así un título que TMDB no conoce no se
            // consulta en cada arranque, y a la vez los ítems que fallaron por un título sucio
            // (ver cleanTitleForSearch) se recuperan solos tras una actualización.
            val existing = artworkDao.get(row.identifier)
            if (existing != null && (existing.tmdbId != null || clock() - existing.fetchedAt < 7 * 24 * 60 * 60 * 1000L)) continue
```

- [ ] **Step 6: Verificar en la TV**

Instalar como en la Tarea 3, abrir el home, esperar a que resuelva el arte (unos segundos) y confirmar que la tarjeta de Naruto pasa a "6 FUENTES".

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/CleanTitleForSearchTest.kt
git commit -m "fix(artwork): limpiar el sufijo Pack para que TMDB resuelva los packs de torrent"
```

---

## Fuera de alcance (anotado a propósito)

- **Películas duplicadas** (Supergirl ×7, El señor de los anillos ×4). El `tmdbId` de `artwork` no es confiable para películas — junta obras distintas —, así que agruparlas necesita otra llave (por ejemplo el `imdbId` exacto del torrent) y es un plan aparte.
- **Fusionar filas en la DB.** Este plan agrupa solo para mostrar. Borrar o fusionar `items` propagaría tombstones por el sync al celular, y no hace falta para el problema reportado.
- **Poblar `items.tmdbId`.** Está NULL en los 90 ítems, pero el id canónico ya vive en el identifier de los `*:series:*` y `artwork` cubre el resto, así que poblarlo sería redundante hoy.
- **Home del teléfono.** Solo se toca el de TV, que es donde se reportó. `observeLibrary()` sigue intacto para el teléfono.
