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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
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
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.miniaturas.EleccionDeMiniatura
import com.arkiv.player.sync.SyncStatus
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.home.searchShortcutRoute
import com.arkiv.player.ui.heroFallback
import com.arkiv.player.ui.heroSubtitle
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.canalesDelPaisParaHome
import com.arkiv.player.ui.live.canalesRecientesParaHome
import com.arkiv.player.ui.live.filaDeCanalesDelHome
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lo que muestra el héroe del fondo. [meta] es la línea de datos del capítulo ("T1 · E5  ·  La
 * conspiración  ·  te faltan 12 min") y solo la llenan las tarjetas de "Continuar viendo": las
 * filas de descubrimiento muestran títulos de TMDB, que no son capítulos.
 */
private data class Featured(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val meta: String = "",
)

/** Subtítulo del hero para una card de descubrimiento: tipo y año (lo que se sabe sin abrirla). */
private fun discoveryMeta(card: com.arkiv.player.ui.search.TitleCard): String {
    val kind = when (card.kind) {
        "movie" -> "Película"
        "anime" -> "Anime"
        else -> "Serie"
    }
    return if (card.year.isBlank()) kind else "$kind  ·  ${card.year}"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    /** Reproduce un canal en vivo directo (código de canal), sin pasar por "En vivo". */
    onPlayLive: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSearchRoute: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi, graph.settings) } },
    )
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val artwork by vm.artwork.collectAsStateWithLifecycle()
    val discoveryRows by vm.rows.collectAsStateWithLifecycle()
    val discoveryRowItems by vm.rowItems.collectAsStateWithLifecycle()
    val discoveryRowsLoaded by vm.rowsLoaded.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Canales en vivo recientes -- mismo criterio que el home del celular (ver su KDoc en
    // HomeScreen.kt): se lee directo de Room, sin levantar LiveViewModel (que habla con el
    // gateway) solo para esta fila. Tope de 10: acceso rápido, no el historial completo.
    val liveRecentDao = remember { graph.database.liveRecentDao() }
    val liveCacheDao = remember { graph.database.liveChannelCacheDao() }
    val liveRecientesCrudo by liveRecentDao.flowUltimos(10).collectAsStateWithLifecycle(initialValue = emptyList())
    var liveCachePorCodigo by remember { mutableStateOf<Map<String, LiveChannelCacheEntity>>(emptyMap()) }
    LaunchedEffect(liveRecientesCrudo) {
        if (liveRecientesCrudo.isNotEmpty()) {
            liveCachePorCodigo = liveCacheDao.deCodigos(liveRecientesCrudo.map { it.code }).associateBy { it.code }
        }
    }
    val canalesRecientes = remember(liveRecientesCrudo, liveCachePorCodigo) {
        canalesRecientesParaHome(liveRecientesCrudo, liveCachePorCodigo)
    }

    // Canales del país del aparato, igual que en el home del celular (ver canalesDelPaisParaHome):
    // así la fila sirve desde la primera apertura, sin nada visto todavía. Acá pesa más que en el
    // celular -- este TV puede no tener SIM, y por eso la detección mira la zona horaria antes que
    // el idioma.
    var canalesDelPais by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        canalesDelPais = canalesDelPaisParaHome(
            context = context,
            api = graph.liveApi,
            cacheDao = liveCacheDao,
            prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
    }
    val canalesFila = remember(canalesRecientes, canalesDelPais) {
        filaDeCanalesDelHome(canalesRecientes, canalesDelPais)
    }

    fun reproducirCanal(canal: LiveChannel) {
        // Mismo mecanismo que TvLiveGuideScreen.verCanal: fija la lista con la que se "entró" para
        // que arriba/abajo en el reproductor recorra los mismos canales que muestra la fila.
        LiveZappingSource.lista = canalesFila
        onPlayLive(canal.code)
    }

    // Backdrop estable (para la tarjeta) y uno al azar (para el hero) de un ítem, con fallback al thumb.
    fun backdropsOf(itemId: String): List<String> = artwork[itemId]?.backdrops ?: emptyList()
    fun cardArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).firstOrNull() ?: fallback
    fun heroArt(itemId: String, fallback: String?): String? = backdropsOf(itemId).randomOrNull() ?: fallback

    // El subtítulo del hero es la sinopsis del título; cuando el ítem no la tiene guardada
    // (los que se agregaron por web o magnet suelto) cae al dato de siempre. Nunca repite el
    // título, que ya está arriba en grande.
    fun continueFeatured(row: ContinueRow): Featured {
        // El still de TMDB manda si `episode_still` lo tiene; si no, el thumb de siempre.
        val thumb = row.stillUrl
            ?: row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) }
            ?: row.itemThumbnailUrl
        // Los datos del capítulo enfocado, que es lo que cambia al moverse entre tarjetas (la
        // sinopsis de arriba es de la SERIE y no cambia). La regla de qué se muestra y qué se
        // omite vive en EtiquetaDeCapitulo, compartida con los dos detalles.
        val meta = com.arkiv.player.ui.EtiquetaDeCapitulo.lineaDeHeroe(
            esPelicula = row.isMovie,
            season = row.season,
            episode = row.episode,
            orderIndex = row.orderIndex,
            itemId = row.itemId,
            section = row.section,
            nombre = row.episodeTitle,
            positionMs = row.positionMs,
            durationMs = row.durationMs,
        )
        // Si la línea del capítulo ya quedó con algo, el fallback de la sinopsis se apaga: sin
        // esto, ítems sin descripción (web, anime, Magis) repetían el mismo dato dos veces
        // seguidas, porque su `displayName` ya trae el número y el nombre adentro.
        val fallback = if (meta.isNotBlank()) "" else heroFallback(row.itemTitle, row.episodeTitle ?: row.displayName)
        // El frame capturado le gana a todo lo demás (incluido el backdrop de `heroArt`), igual
        // que en el hero del Home del celular: es la escena real de donde vas, no la carátula.
        return Featured(
            row.itemTitle,
            heroSubtitle(row.itemTitle, row.itemDescription, fallback),
            EleccionDeMiniatura.elegir(row.framePath, heroArt(row.itemId, thumb)),
            meta = meta,
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
    // Foco inicial. Antes caía en la primera tarjeta de biblioteca; esas filas ya no están, y dejar
    // el foco suelto es exactamente el bug que costó el comentario largo de más abajo: Android se lo
    // daba a lo que se fuera componiendo —las filas de descubrimiento—, que al traerse a la vista
    // scrolleaban el home hasta "En cartelera".
    //
    // Sin "Continuar viendo" el foco va a la BARRA SUPERIOR, que es la única zona determinista: no
    // vive dentro del LazyColumn, así que siempre está compuesta y enfocarla no puede scrollear nada.
    // Y deja al usuario a un clic de su biblioteca, que es lo que va a querer si no hay nada empezado.
    val barraFocus = remember { FocusRequester() }
    val firstFocusKey = continueWatching.firstOrNull()?.episodeId
    LaunchedEffect(firstFocusKey) {
        delay(200)
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            if (firstFocusKey != null) {
                // Volver arriba ANTES de pedir foco: si la lista está desplazada, la primera fila ni
                // siquiera está compuesta y el requester no existe, así que reintentar solo no alcanza.
                runCatching { rowsListState.scrollToItem(0) }
                landed = runCatching { firstCardFocus.requestFocus() }.isSuccess
            } else {
                landed = runCatching { barraFocus.requestFocus() }.isSuccess
            }
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
                    TvNavButton(
                        icon = Icons.Default.VideoLibrary,
                        label = "Mi biblioteca",
                        onClick = onOpenLibrary,
                        modifier = Modifier.focusRequester(barraFocus),
                    )
                    TvNavButton(icon = Icons.Default.LiveTv, label = "En vivo", onClick = onOpenLive)
                    // El botón "Torrent" (pegar un magnet a mano) se quitó de la barra: ya no se usa,
                    // los torrents entran por el buscador. La ruta "torrent" sigue registrada en
                    // ArkivTvRoot y la pantalla funciona; solo perdió su entrada desde el home.
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
                    if (f.meta.isNotBlank()) {
                        Text(
                            f.meta,
                            style = MaterialTheme.typography.titleSmall,
                            color = ArkivRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.55f),
                        )
                    }
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
                                // El respaldo de siempre, para cuando no hay ni still ni backdrop.
                                val thumb = row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) }
                                    ?: row.itemThumbnailUrl
                                val isFirst = row.episodeId == continueWatching.first().episodeId
                                TvWideCard(
                                    title = row.itemTitle,
                                    // El frame capturado manda primero (es la escena real del capítulo).
                                    // ACÁ, y solo acá, el still del CAPÍTULO le gana al backdrop de la
                                    // serie: esta fila muestra un capítulo, no la serie. En el resto del
                                    // home (y en el hero de fondo) sigue mandando el backdrop, que es la
                                    // imagen del título. Sin esta inversión el still no se veía nunca:
                                    // `cardArt` prueba primero `backdropsOf(itemId)`, y backdrop tienen
                                    // todos —los de Magis del portal, los demás de TMDB—, así que el
                                    // still solo entraba como respaldo de algo que jamás faltaba.
                                    imageUrl = EleccionDeMiniatura.elegir(row.framePath, row.stillUrl, cardArt(row.itemId, thumb)),
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

                // Canales en vivo -- acceso directo sin pasar por "En vivo": lo último visto a la
                // izquierda, después los canales del país sin repetir los ya vistos, y al final la
                // salida a la parrilla completa (ver `filaDeCanalesDelHome`). Sin nada que mostrar,
                // la fila no se dibuja: nada de un hueco vacío en medio del home.
                if (canalesFila.isNotEmpty()) {
                    item(key = "live_recientes") {
                        TvRowLabel("Canales en vivo", labelHeight)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(canalesFila, key = { it.code }) { canal ->
                                TvLiveChannelCard(
                                    canal = canal,
                                    cardHeight = cardHeight,
                                    onFocus = {
                                        navSound()
                                        featured = Featured(canal.nombre, "Canal en vivo", canal.logo)
                                    },
                                    onClick = { reproducirCanal(canal) },
                                )
                            }
                            // Al final de la fila, la salida hacia la parrilla completa: los
                            // recientes son un atajo, no el catálogo.
                            item(key = "live_ver_mas") {
                                TvVerMasCanalesCard(
                                    cardHeight = cardHeight,
                                    onFocus = {
                                        navSound()
                                        featured = Featured("Ver más canales", "Canal en vivo", null)
                                    },
                                    onClick = onOpenLive,
                                )
                            }
                        }
                        Spacer(Modifier.height(rowGap))
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
    }
}

/**
 * Tarjeta (16:9, mismo molde que [TvLandscapeCard]/[TvWideCard]) de un canal reciente para la fila
 * "Canales en vivo". Sin título superpuesto -- el nombre se lee arriba, en el hero, al enfocar
 * (mismo criterio que el resto de las filas del TV).
 *
 * Logo si la caché lo tiene (ver [canalesRecientesParaHome]); si no, el mismo tratamiento que
 * `ChannelCard` en `LiveScreen.kt` (celular): degradado + el número del canal, deliberado en vez
 * de un logo roto. Si ni el número se conoce todavía (canal recién visto, caché sin ese `code`),
 * cae a las iniciales del nombre -- un "0" no significaría nada acá.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLiveChannelCard(
    canal: LiveChannel,
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
                .background(ArkivSurfaceHigh),
        ) {
            if (canal.logo != null) {
                AsyncImage(
                    model = canal.logo,
                    contentDescription = canal.nombre,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * Última tarjeta de la fila "Canales en vivo": abre la sección "En vivo" con la parrilla completa.
 * Mismo molde que [TvLiveChannelCard] (alto de fila, 16:9, mismo foco y borde) para que la fila no
 * cambie de altura ni de ritmo al llegar al final.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVerMasCanalesCard(
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
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = ArkivRed,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "Ver más canales",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
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
