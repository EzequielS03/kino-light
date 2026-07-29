package com.arkiv.player.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.sync.SyncStatus
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.home.searchShortcutRoute
import com.arkiv.player.ui.heroFallback
import com.arkiv.player.ui.heroSubtitle
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Featured(val title: String, val subtitle: String, val imageUrl: String?)

/** Subtítulo del hero para una card de descubrimiento: tipo y año (lo que se sabe sin abrirla). */
private fun discoveryMeta(card: com.arkiv.player.ui.search.TitleCard): String {
    val kind = when (card.kind) {
        "movie" -> "Película"
        "anime" -> "Anime"
        else -> "Serie"
    }
    return if (card.year.isBlank()) kind else "$kind  ·  ${card.year}"
}

private val SeriesBadgeColor = Color(0xE6444444)
private val TorrentBadgeColor = Color(0xE60288A7)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTorrent: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSearchRoute: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi) } },
    )
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val discoveryRows by vm.rows.collectAsStateWithLifecycle()
    val discoveryRowItems by vm.rowItems.collectAsStateWithLifecycle()
    val discoveryRowsLoaded by vm.rowsLoaded.collectAsStateWithLifecycle()

    // Backdrop estable (para la tarjeta) y uno al azar (para el hero) de un ítem, con fallback al thumb.
    fun backdropsOf(itemId: String): List<String> = artwork[itemId]?.backdrops ?: emptyList()
    fun cardArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).firstOrNull() ?: fallback
    fun heroArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).randomOrNull() ?: fallback

    // El subtítulo del hero es la sinopsis del título; cuando el ítem no la tiene guardada
    // (los que se agregaron por web o magnet suelto) cae al dato de siempre. Nunca repite el
    // título, que ya está arriba en grande.
    fun continueFeatured(row: ContinueRow): Featured {
        val thumb = row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) } ?: row.itemThumbnailUrl
        return Featured(
            row.itemTitle,
            heroSubtitle(row.itemTitle, row.itemDescription, heroFallback(row.itemTitle, row.displayName)),
            heroArt(row.itemId, thumb),
        )
    }

    fun libraryFeatured(row: LibraryRow) = Featured(
        row.title,
        heroSubtitle(
            row.title,
            row.description,
            libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount, row.isTorrent),
        ),
        heroArt(row.identifier, row.thumbnailUrl),
    )

    LaunchedEffect(Unit) { runCatching { graph.syncManager.syncNow() } }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncStatus by graph.syncManager.status.collectAsStateWithLifecycle()

    val navSound = rememberNavSound()
    var featured by remember { mutableStateOf<Featured?>(null) }
    // Destacado inicial: primer "continuar viendo" o primer ítem de la biblioteca.
    LaunchedEffect(library, continueWatching, artwork) {
        if (featured == null) {
            featured = continueWatching.firstOrNull()?.let { continueFeatured(it) }
                ?: library.firstOrNull()?.let { libraryFeatured(it) }
        }
    }

    // Al elegir un ítem: película -> reproduce directo; serie -> abre la lista de episodios.
    fun open(row: LibraryRow) {
        if (row.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(row.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(row.identifier)
            }
        } else {
            onOpenItem(row.identifier)
        }
    }

    // Ítem con el menú contextual (long-press) abierto.
    var menuRow by remember { mutableStateOf<LibraryRow?>(null) }

    val movies = library.filter { it.isMovie }
    val series = library.filter { !it.isMovie }
    // La primera tarjeta enfocable de la biblioteca (si no hay "continuar viendo").
    // El orden acá DEBE seguir al de render (series antes que películas): si apunta a una
    // tarjeta de una fila que quedó fuera de las 2 visibles, el home abre desplazado.
    val firstPosterId = if (continueWatching.isEmpty()) {
        (series.firstOrNull() ?: movies.firstOrNull())?.identifier
    } else {
        null
    }

    // La primera tarjeta recibe el foco al abrir, para que el hero/fondo reflejen algo de una.
    // La key es la IDENTIDAD de esa tarjeta, no un "¿ya hay datos?": "continuar viendo" y la
    // biblioteca llegan por flows distintos, y si la biblioteca llegaba primero el foco se clavaba
    // en su fila; cuando después aparecía "Continuar viendo" arriba, esa fila bajaba y el
    // LazyColumn quedaba desplazado, tapando justo lo que hay que ver primero. Con la identidad
    // como key el efecto se repite al cambiar la primera tarjeta y el foco (y el scroll) vuelven arriba.
    val firstCardFocus = remember { FocusRequester() }
    // Estado explícito de la zona de filas: sin él no había forma de asegurar que arranque arriba.
    // Es la pieza que faltaba — si la lista quedaba desplazada, el LazyColumn NO componía la fila
    // "Continuar viendo", el focusRequester no enganchaba y el foco no podía aterrizar ahí nunca
    // (el scroll no era consecuencia del foco perdido: era su causa).
    val rowsListState = rememberLazyListState()
    val firstFocusKey = continueWatching.firstOrNull()?.episodeId ?: firstPosterId
    LaunchedEffect(firstFocusKey) {
        if (firstFocusKey == null) return@LaunchedEffect
        delay(200)
        // Reintento en vez de un único intento: a los 200 ms la tarjeta suele no estar compuesta
        // todavía (el LazyColumn compone tras el primer layout) y requestFocus() tira
        // "FocusRequester is not initialized". El runCatching se lo tragaba en silencio, el foco
        // no aterrizaba en ningún lado y Android terminaba dándoselo a lo que se fuera componiendo
        // —las filas de descubrimiento—, que al traerse a la vista scrolleaban el home hasta
        // "En cartelera". Se espera por la condición, no por un tiempo fijo.
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            // Volver arriba ANTES de pedir foco: si la lista está desplazada, la primera fila ni
            // siquiera está compuesta y el requester no existe, así que reintentar solo no alcanza.
            runCatching { rowsListState.scrollToItem(0) }
            landed = runCatching { firstCardFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    // Tarjetas y filas de tamaño fijo: la zona de filas mide EXACTAMENTE 2 filas
    // (etiqueta + tarjeta apaisada), y el hero de arriba —inamovible— ocupa el resto con weight(1f).
    val cardHeight = 92.dp
    val labelHeight = 26.dp
    val rowGap = 14.dp
    val rowsTopPad = 6.dp
    val rowUnit = labelHeight + cardHeight + rowGap
    val rowsRegionHeight = rowUnit * 2 + rowsTopPad

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        // Fondo inmersivo fijo: backdrop del ítem enfocado + degradados.
        Crossfade(targetState = featured?.imageUrl, animationSpec = tween(450), label = "bg") { url ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight().align(Alignment.TopEnd),
                )
                // Degradado horizontal: negro a la izquierda para leer el texto.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
                    ),
                )
                // Degradado vertical: negro abajo para fundir con las filas.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            // --- HERO FIJO (no scrollea; queda inamovible arriba, ocupa el espacio sobrante) ---
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp)) {
                // Barra superior.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ARKIV",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ArkivRed,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    TvNavButton(icon = Icons.Default.Search, label = "Buscar", onClick = onOpenSearch)
                    TvNavButton(icon = Icons.Default.Download, label = "Torrent", onClick = onOpenTorrent)
                    TvNavButton(icon = Icons.Default.Settings, label = "Ajustes", onClick = onOpenSettings)
                    TvNavButton(
                        icon = Icons.Default.Sync,
                        label = if (syncStatus is SyncStatus.Syncing) "Sincronizando…" else "Sincronizar",
                        onClick = {
                            scope.launch {
                                // LAN es best-effort y silencioso: en un setup por nube no hay TV en
                                // la red WiFi, y eso no debe verse como un fallo.
                                runCatching { graph.syncManager.syncNow() }
                                graph.cloudSync.syncNow()
                                android.widget.Toast.makeText(context, "Sincronizado", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                // Título/descripción del ítem enfocado, abajo a la izquierda.
                featured?.let { f ->
                    Text(
                        f.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f),
                    )
                    if (f.subtitle.isNotBlank()) {
                        Text(
                            f.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
                }
            }

            // --- FILAS (única zona que scrollea; alto fijo = exactamente 2 filas) ---
            // LazyColumn en vez de Column+verticalScroll: con ~40 filas de descubrimiento
            // (Task 3), un Column compondría TODAS a la vez y dispararía todas sus cargas
            // de red al abrir el home. LazyColumn solo compone lo visible; cada fila de
            // descubrimiento pide sus datos recién cuando entra en pantalla (loadRow más abajo).
            LazyColumn(
                state = rowsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowsRegionHeight)
                    .padding(top = rowsTopPad),
            ) {
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue_watching") {
                        TvRowLabel("Continuar viendo", labelHeight)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(continueWatching, key = { it.episodeId }) { row ->
                                val progress = if (row.durationMs > 0) row.positionMs.toFloat() / row.durationMs else 0f
                                val thumb = row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) } ?: row.itemThumbnailUrl
                                val isFirst = row.episodeId == continueWatching.first().episodeId
                                TvWideCard(
                                    title = row.itemTitle,
                                    imageUrl = cardArt(row.itemId, thumb),
                                    progress = progress,
                                    cardHeight = cardHeight,
                                    modifier = if (isFirst) Modifier.focusRequester(firstCardFocus) else Modifier,
                                    onFocus = { navSound(); featured = continueFeatured(row) },
                                    onClick = { onPlayEpisode(row.episodeId) },
                                )
                            }
                        }
                        Spacer(Modifier.height(rowGap))
                    }
                }

                // Series ANTES que películas: en la zona de filas entran exactamente 2, así que
                // esta queda junto a "Continuar viendo" y se llega sin bajar. Desde acá se abre el
                // detalle con todos los capítulos — que es como se elige uno distinto al que
                // ofrece "Continuar viendo" (esa tarjeta reproduce directo). Películas baja a
                // tercera. Si cambia este orden, actualizar también firstPosterId.
                if (series.isNotEmpty()) {
                    item(key = "lib_series") {
                        TvLibrarySection(
                            label = "Series",
                            rows = series,
                            cardHeight = cardHeight,
                            labelHeight = labelHeight,
                            rowGap = rowGap,
                            firstFocusId = firstPosterId,
                            firstFocus = firstCardFocus,
                            imageFor = { cardArt(it.identifier, it.thumbnailUrl) },
                            onFocusRow = { navSound(); featured = libraryFeatured(it) },
                            onClickRow = { open(it) },
                            onLongClickRow = { menuRow = it },
                        )
                    }
                }
                if (movies.isNotEmpty()) {
                    item(key = "lib_movies") {
                        TvLibrarySection(
                            label = "Películas",
                            rows = movies,
                            cardHeight = cardHeight,
                            labelHeight = labelHeight,
                            rowGap = rowGap,
                            firstFocusId = firstPosterId,
                            firstFocus = firstCardFocus,
                            imageFor = { cardArt(it.identifier, it.thumbnailUrl) },
                            onFocusRow = { navSound(); featured = libraryFeatured(it) },
                            onClickRow = { open(it) },
                            onLongClickRow = { menuRow = it },
                        )
                    }
                }

                // Filas de descubrimiento (TMDB/AniList): una por género + fijas (cartelera,
                // populares, etc). loadRow() es idempotente (LoadGuard), así que el
                // LaunchedEffect solo dispara la carga real la primera vez que la fila entra
                // en pantalla; al reciclarse en el LazyColumn, vuelve a componerse pero no
                // vuelve a pedir red.
                items(discoveryRows, key = { it.id }) { spec ->
                    val cards = discoveryRowItems[spec.id].orEmpty()
                    val loaded = spec.id in discoveryRowsLoaded
                    LaunchedEffect(spec.id) { vm.loadRow(spec.id) }
                    if (loaded && cards.isEmpty()) return@items
                    Column {
                        TvRowLabel(spec.title, labelHeight)
                        if (!loaded) {
                            // Placeholder de alto fijo: mismo alto que ocupa la fila cargada
                            // (TvLandscapeCard mide exactamente cardHeight, igual que el resto
                            // de las filas) para que el scroll no salte cuando llegan los datos.
                            Spacer(Modifier.height(cardHeight))
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(cards, key = { "${spec.id}-${it.kind}-${it.tmdbId}-${it.anilistId}" }) { card ->
                                    // Misma card (16:9) que biblioteca y "continuar viendo": a igual
                                    // alto, un póster 2:3 se veía diminuto. Usamos la imagen apaisada
                                    // (backdrop de TMDB / banner de AniList) y caemos al póster si falta.
                                    val art = card.backdropUrl.ifBlank { card.posterUrl }
                                    TvLandscapeCard(
                                        title = card.title,
                                        imageUrl = art,
                                        cardHeight = cardHeight,
                                        onFocus = {
                                            navSound()
                                            featured = Featured(
                                                card.title,
                                                heroSubtitle(card.title, card.overview, discoveryMeta(card)),
                                                art,
                                            )
                                        },
                                        onClick = { onOpenSearchRoute(searchShortcutRoute(card)) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(rowGap))
                    }
                }

                item(key = "rows_bottom_pad") { Spacer(Modifier.height(rowGap)) }
            } // fin zona scrolleable de filas
        }

        menuRow?.let { row ->
            TvCategoryDialog(
                row = row,
                onOpenDetail = { onOpenItem(row.identifier); menuRow = null },
                onSetCategory = { isMovie ->
                    scope.launch { graph.repository.setCategory(row.identifier, isMovie) }
                    menuRow = null
                },
                onDismiss = { menuRow = null },
            )
        }
    }
}

/** Diálogo contextual (long-press) para ver detalle o cambiar la categoría del ítem. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCategoryDialog(
    row: LibraryRow,
    onOpenDetail: () -> Unit,
    onSetCategory: (Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            Button(
                onClick = onOpenDetail,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            ) { Text("Ver detalle / descargar", maxLines = 1) }
            if (row.isMovie) {
                Button(onClick = { onSetCategory(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Marcar como serie", maxLines = 1)
                }
            } else {
                Button(onClick = { onSetCategory(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Marcar como película", maxLines = 1)
                }
            }
            if (row.categoryOverride != null) {
                Button(onClick = { onSetCategory(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Detección automática", maxLines = 1)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar", maxLines = 1)
            }
        }
    }
}

/** Etiqueta de fila con alto fijo, para que 2 filas quepan exactas en la zona scrolleable. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRowLabel(text: String, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).padding(start = 48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = ArkivTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Una fila etiquetada de carátulas apaisadas ("Películas" / "Series") para el inicio de TV. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLibrarySection(
    label: String,
    rows: List<LibraryRow>,
    cardHeight: androidx.compose.ui.unit.Dp,
    labelHeight: androidx.compose.ui.unit.Dp,
    rowGap: androidx.compose.ui.unit.Dp,
    firstFocusId: String?,
    firstFocus: FocusRequester,
    imageFor: (LibraryRow) -> String?,
    onFocusRow: (LibraryRow) -> Unit,
    onClickRow: (LibraryRow) -> Unit,
    onLongClickRow: (LibraryRow) -> Unit,
) {
    TvRowLabel(label, labelHeight)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(rows, key = { it.identifier }) { row ->
            TvLandscapeCard(
                title = row.title,
                imageUrl = imageFor(row),
                cardHeight = cardHeight,
                badge = if (row.isTorrent) "TORRENT" else if (row.isMovie) "PELÍCULA" else "SERIE",
                badgeColor = if (row.isTorrent) TorrentBadgeColor else if (row.isMovie) ArkivRed else SeriesBadgeColor,
                episodeCountLabel = if (!row.isMovie) "${row.episodeCount} ep." else null,
                modifier = if (row.identifier == firstFocusId) Modifier.focusRequester(firstFocus) else Modifier,
                onFocus = { onFocusRow(row) },
                onLongClick = { onLongClickRow(row) },
                onClick = { onClickRow(row) },
            )
        }
    }
    Spacer(Modifier.height(rowGap))
}

/**
 * Botón de la barra superior estilo Prime Video: colapsado muestra solo el ícono; al enfocarlo
 * con el D-pad se expande mostrando también el texto.
 *
 * La transición la hace el propio texto con AnimatedVisibility, no el contenedor con
 * animateContentSize: ese recorta el contenido a los bounds mientras anima, así que el texto
 * salía cortado y la píldora se veía chata del lado derecho durante toda la transición.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        // 50 sin `.dp` es el overload de PORCENTAJE: 50% = píldora completa, el máximo redondeo
        // posible para esta altura. Si se ve chata, el problema es un recorte, no el radio.
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        // Sin foco no lleva fondo: el ícono va suelto sobre el backdrop. El rojo aparece solo al
        // enfocar, y es lo que marca dónde estás parado en la barra.
        // El contentColor va explícito en los tres estados porque el default de tv-material3 lo
        // calcula para contrastar con el container, y elegía un tono oscuro que dejaba el texto
        // en negro al lado de un ícono blanco.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (isFocused) 16.dp else 12.dp,
                end = if (isFocused) 16.dp else 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sin tint propio: hereda el contentColor del Surface, igual que el Text. Tener dos
            // fuentes de color era justamente lo que dejaba el ícono blanco y el texto negro.
            Icon(icon, contentDescription = if (isFocused) null else label)
            AnimatedVisibility(
                visible = isFocused,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                // El Row de adentro mantiene juntos el espacio y el texto: si el Spacer quedara
                // afuera, al colapsar dejaría un hueco de 8.dp al lado del ícono.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}
