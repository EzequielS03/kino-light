package com.arkiv.player.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.components.ContinueCard
import com.arkiv.player.ui.components.SectionHeader
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.canalesDelPaisParaHome
import com.arkiv.player.ui.live.canalesRecientesParaHome
import com.arkiv.player.ui.live.filaDeCanalesDelHome
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Medidas del home según la forma de la pantalla. Ver [esTabletHorizontal]. */
private data class MedidasDelHome(
    val altoDelHero: Dp,
    val anchoDePoster: Dp,
    val anchoDeContinuar: Dp,
    val anchoDeCanal: Dp,
)

@Composable
private fun medidasDelHome(): MedidasDelHome =
    if (esTabletHorizontal()) {
        MedidasDelHome(
            altoDelHero = 420.dp,
            anchoDePoster = 180.dp,
            anchoDeContinuar = 320.dp,
            anchoDeCanal = 200.dp,
        )
    } else {
        MedidasDelHome(
            altoDelHero = 220.dp,
            anchoDePoster = 120.dp,
            anchoDeContinuar = 220.dp,
            anchoDeCanal = 140.dp,
        )
    }

/**
 * Home de descubrimiento (estilo Amazon/Netflix): hero de lo último visto, biblioteca y
 * muchas filas horizontales que se cargan perezosamente al entrar en pantalla.
 */
@Composable
fun HomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    /** Reproduce un canal en vivo directo (código de canal), sin pasar por la pestaña "En vivo". */
    onPlayLive: (String) -> Unit,
    /** Abre la pestaña "En vivo" con la parrilla completa (última tarjeta de la fila de canales). */
    onOpenLive: () -> Unit,
    onOpenSearchRoute: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onBrowseRow: (rowId: String, title: String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val medidas = medidasDelHome()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi, graph.settings) } },
    )
    // Esta pantalla no colecciona `vm.library` (orden por addedAt): esa suscripción vive solo en
    // el `init` del VM, para el `ensureArtwork`/hero del TV. La fila "Mi biblioteca" usa
    // `bibliotecaOrdenada` para coincidir con el orden de la grilla (misma regla, ver
    // OrdenDeBiblioteca).
    val bibliotecaOrdenada by vm.bibliotecaOrdenada.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val rowItems by vm.rowItems.collectAsStateWithLifecycle()
    val rowsLoaded by vm.rowsLoaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Sin nada en curso, adelantamos "tendencias" para tener un destacado apenas esté lista.
    LaunchedEffect(continueWatching.isEmpty()) {
        if (continueWatching.isEmpty()) vm.loadRow("tendencias")
    }

    // Al tocar un ítem de la biblioteca: si es película, reproduce directo; si es serie, abre el detalle.
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

    // Canales en vivo recientes, para la fila que evita entrar a "En vivo" (ver
    // canalesRecientesParaHome). Se lee directo de Room, igual que hace LiveScreen con su propia
    // pestaña "Recientes" -- no hace falta levantar LiveViewModel (que habla con el gateway) solo
    // para esto. Tope de 10: es una fila de acceso rápido a mano, no el historial completo (para
    // eso está la pestaña "Recientes" de "En vivo", sin tope).
    val liveRecentDao = remember { graph.database.liveRecentDao() }
    val liveCacheDao = remember { graph.database.liveChannelCacheDao() }
    val recientesCrudo by liveRecentDao.flowUltimos(10).collectAsStateWithLifecycle(initialValue = emptyList())
    var cachePorCodigo by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(recientesCrudo) {
        if (recientesCrudo.isNotEmpty()) {
            cachePorCodigo = liveCacheDao.deCodigos(recientesCrudo.map { it.code }).associateBy { it.code }
        }
    }
    val canalesRecientes = remember(recientesCrudo, cachePorCodigo) {
        canalesRecientesParaHome(recientesCrudo, cachePorCodigo)
    }

    // Canales del país del aparato, para que la fila sirva desde la primera apertura (sin nada
    // visto todavía) -- ver canalesDelPaisParaHome: detecta el país, sale de la caché de Room si
    // está fresca y no rompe nada si no hay red ni país detectable.
    val context = LocalContext.current
    var canalesDelPais by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        canalesDelPais = canalesDelPaisParaHome(
            context = context,
            api = graph.catalogoDeVivo,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    val canalesFila = remember(canalesRecientes, canalesDelPais) {
        filaDeCanalesDelHome(canalesRecientes, canalesDelPais)
    }

    fun reproducirCanal(canal: LiveChannel) {
        // Deja fijada la lista con la que se "entró", mismo mecanismo que LiveScreen.abrirAca --
        // así arriba/abajo en el reproductor recorre los mismos canales que muestra la fila.
        LiveZappingSource.lista = canalesFila
        onPlayLive(canal.code)
    }

    val hayInternet by graph.hayInternet.collectAsStateWithLifecycle()

    if (!hayInternet) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFFB00020))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.SignalWifiOff,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
                androidx.compose.material3.Text(
                    text = "Sin conexión — revisa tu red",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    val listState = rememberLazyListState()

    // Whether the person has scrolled the list by hand. rememberSaveable so it survives coming
    // back from a detail screen with the same value -- once true, the auto-snap below never fires
    // again for this screen instance. Only a real drag/fling sets it: `isScrollInProgress` is also
    // true during the auto-snap's own `scrollToItem`, so it can't be used to tell the two apart.
    var userScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrolled = true
        }
    }

    // Shape of the top sections right now (see TopSectionsSignature/shouldSnapHomeToTop): those
    // sections are always-present, stably-keyed items below, but they still grow from zero height
    // to their real content as their data loads. While the person hasn't scrolled, snap back to
    // the top whenever that shape changes -- the safety net for staying at the top of the list.
    val topSectionsSignature = TopSectionsSignature(
        heroVisible = continueWatching.firstOrNull() != null || rowItems["tendencias"]?.firstOrNull() != null,
        continuarCount = (continueWatching.size - 1).coerceAtLeast(0),
        canalesCount = canalesFila.size,
        bibliotecaCount = bibliotecaOrdenada.size,
    )
    var lastTopSectionsSignature by remember { mutableStateOf<TopSectionsSignature?>(null) }
    LaunchedEffect(topSectionsSignature, userScrolled) {
        val signatureChanged = topSectionsSignature != lastTopSectionsSignature
        lastTopSectionsSignature = topSectionsSignature
        if (
            shouldSnapHomeToTop(
                userScrolled = userScrolled,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                signatureChanged = signatureChanged,
            )
        ) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 1. Hero: lo último visto, o si no hay nada en curso, la tendencia #1 (si ya cargó).
        // Always-present, keyed item (renders nothing until it has data) -- see TopSectionsSignature:
        // an unkeyed, conditionally-emitted item here is what let this section get inserted ABOVE the
        // already-visible, keyed remote rows and land the person mid-list.
        item(key = "hero") {
            val heroContinue = continueWatching.firstOrNull()
            if (heroContinue != null) {
                val backdrop = ThumbnailChoice.choose(
                    heroContinue.framePath,
                    artwork[heroContinue.itemId]?.backdrops?.firstOrNull(),
                    heroContinue.itemThumbnailUrl,
                )
                Hero(
                    medidas = medidas,
                    backdropUrl = backdrop,
                    title = heroContinue.itemTitle,
                    // Los datos del capítulo, la MISMA línea que arma el héroe del TV: número,
                    // nombre y cuánto falta, omitiendo lo que no se sepa. Antes acá solo estaba el
                    // nombre del capítulo, sin número ni tiempo. Si no queda ningún tramo (una
                    // película sin duración conocida) se cae al nombre de siempre, para no dejar el
                    // héroe con una línea vacía.
                    subtitle = com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe(
                        esPelicula = heroContinue.isMovie,
                        season = heroContinue.season,
                        episode = heroContinue.episode,
                        orderIndex = heroContinue.orderIndex,
                        itemId = heroContinue.itemId,
                        section = heroContinue.section,
                        nombre = heroContinue.episodeTitle,
                        positionMs = heroContinue.positionMs,
                        durationMs = heroContinue.durationMs,
                    ).ifBlank { heroContinue.episodeTitle ?: heroContinue.displayName },
                    actionLabel = "Reanudar",
                    onAction = { onPlayEpisode(heroContinue.episodeId) },
                    onClick = { onPlayEpisode(heroContinue.episodeId) },
                )
            } else {
                val trending = rowItems["tendencias"]?.firstOrNull()
                if (trending != null) {
                    // En ancho preferimos el backdrop apaisado (16:9) del TitleCard: un póster 2:3
                    // estirado a 1280dp se ve mal. Si la fuente no trajo backdrop, seguimos con el
                    // póster -- el hero nunca puede quedar vacío.
                    val heroImage = if (esTabletHorizontal() && trending.backdropUrl.isNotBlank()) {
                        trending.backdropUrl
                    } else {
                        trending.posterUrl
                    }
                    Hero(
                        medidas = medidas,
                        backdropUrl = heroImage,
                        title = trending.title,
                        subtitle = "Tendencia de la semana",
                        actionLabel = null,
                        onAction = null,
                        onClick = { onOpenSearchRoute(searchShortcutRoute(trending)) },
                    )
                }
            }
        }

        // 2. Continuar viendo (el resto, sin repetir el hero). Always-present, keyed item -- see
        // the hero comment above.
        item(key = "continuar") {
            if (continueWatching.size > 1) {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionHeader("Continuar viendo", modifier = Modifier.padding(start = 16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(continueWatching.drop(1), key = { it.episodeId }) { row ->
                            val progress = if (row.durationMs > 0) row.positionMs.toFloat() / row.durationMs else 0f
                            // El frame capturado manda si existe; si no, el still de TMDB y por
                            // último la carátula del ítem. El thumb de archive.org que iba en medio
                            // se borró en la poda de esta rama junto con esa fuente.
                            val thumb = ThumbnailChoice.choose(
                                row.framePath,
                                row.stillUrl,
                                null,
                                row.itemThumbnailUrl,
                            )
                            ContinueCard(
                                title = row.itemTitle,
                                subtitle = row.episodeTitle ?: row.displayName,
                                imageUrl = thumb,
                                progress = progress,
                                modifier = Modifier.width(medidas.anchoDeContinuar),
                                onClick = { onPlayEpisode(row.episodeId) },
                            )
                        }
                    }
                }
            }
        }

        // 3. Canales en vivo -- acceso directo sin pasar por "En vivo": lo último visto a la
        // izquierda, después los canales del país sin repetir los ya vistos, y al final la salida
        // a la parrilla completa (ver `filaDeCanalesDelHome`). Sin nada que mostrar, la fila no se
        // dibuja: nada de un hueco vacío. Always-present, keyed item -- see the hero comment
        // above.
        item(key = "canales") {
            if (canalesFila.isNotEmpty()) {
                Column(Modifier.padding(top = 16.dp)) {
                    SectionHeader("Canales en vivo", modifier = Modifier.padding(start = 16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(canalesFila, key = { it.code }) { canal ->
                            LiveChannelCard(canal = canal, ancho = medidas.anchoDeCanal, onClick = { reproducirCanal(canal) })
                        }
                        // Al final de la fila, la salida hacia la parrilla completa: los recientes
                        // son un atajo, no el catálogo.
                        item(key = "live_ver_mas") {
                            VerMasCanalesCard(ancho = medidas.anchoDeCanal, onClick = onOpenLive)
                        }
                    }
                }
            }
        }

        // 4. Mi biblioteca (con "Ver todo" hacia la grilla completa). Always-present, keyed item --
        // see the hero comment above.
        item(key = "biblioteca") {
            if (bibliotecaOrdenada.isNotEmpty()) {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        SectionHeader("Mi biblioteca", modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenLibrary) { Text("Ver todo", color = ArkivRed) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(bibliotecaOrdenada, key = { it.identifier }) { row ->
                            com.arkiv.player.ui.components.PosterCard(
                                title = row.title,
                                imageUrl = row.thumbnailUrl,
                                modifier = Modifier.width(medidas.anchoDePoster),
                                onClick = { open(row) },
                            )
                        }
                    }
                }
            }
        }

        // 5. Filas remotas: cada una carga sola al entrar en pantalla (ver RemoteRow).
        rows.forEach { spec ->
            item(key = spec.id) {
                RemoteRow(
                    spec = spec,
                    items = rowItems[spec.id].orEmpty(),
                    loaded = spec.id in rowsLoaded,
                    medidas = medidas,
                    onLoad = { vm.loadRow(spec.id) },
                    onOpenCard = { card -> onOpenSearchRoute(searchShortcutRoute(card)) },
                    onVerMas = { onBrowseRow(spec.id, spec.title) },
                )
            }
        }
    }
}

/** Destacado a ancho completo: backdrop, degradado inferior, título/subtítulo y acción opcional. */
@Composable
private fun Hero(
    medidas: MedidasDelHome,
    backdropUrl: String?,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(medidas.altoDelHero)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, ArkivBlack))),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                // 2 líneas: con 1 sola, la línea de datos del capítulo (~48 caracteres) se elipsaba
                // justo donde importa — "te faltan N min" es lo primero que se pierde.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Última tarjeta de la fila "Canales en vivo": abre la pestaña "En vivo" con la parrilla completa.
 * Mismo molde que [LiveChannelCard] (140.dp, 16:9 y texto debajo) para que la fila no cambie de
 * altura al llegar al final.
 */
@Composable
private fun VerMasCanalesCard(ancho: Dp = 140.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(ancho).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LiveTv,
                contentDescription = null,
                tint = ArkivRed,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = "Ver más canales",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Tarjeta de un canal reciente para la fila "Canales en vivo" del home: logo si la caché lo tiene
 * (ver [canalesRecientesParaHome]); si no, el mismo tratamiento que `ChannelCard` en
 * `LiveScreen.kt` -- degradado + el número del canal, para que se vea deliberada y no como un logo
 * roto. Si ni siquiera el número se conoce (canal recién visto, caché sin ese `code`), cae más
 * abajo todavía: las iniciales del nombre, para no mostrar un "0" que no significa nada.
 */
@Composable
private fun LiveChannelCard(canal: LiveChannel, ancho: Dp = 140.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(ancho).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
        ) {
            if (canal.logo != null) {
                AsyncImage(
                    model = canal.logo,
                    contentDescription = canal.nombre,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (canal.numero > 0) canal.numero.toString() else canal.nombre.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
        Text(
            text = canal.nombre,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Fila remota: se carga sola al entrar en pantalla; se oculta sin dejar hueco si vino vacía. */
@Composable
private fun RemoteRow(
    spec: HomeRowSpec,
    items: List<TitleCard>,
    loaded: Boolean,
    medidas: MedidasDelHome,
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
                    PosterCard(card, ancho = medidas.anchoDePoster) { onOpenCard(card) }
                }
                item(key = "${spec.id}-ver-mas") {
                    VerMasPosterCard(ancho = medidas.anchoDePoster, onClick = onVerMas)
                }
            }
        }
    }
}

/** Carátula 2:3 de una fila remota (título del buscador/catálogo). */
@Composable
private fun PosterCard(card: TitleCard, ancho: Dp = 120.dp, onClick: () -> Unit) {
    com.arkiv.player.ui.components.PosterCard(
        title = card.title,
        imageUrl = card.posterUrl,
        modifier = Modifier.width(ancho),
        onClick = onClick,
    )
}

@Composable
private fun VerMasPosterCard(ancho: Dp = 120.dp, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(ancho).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
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
