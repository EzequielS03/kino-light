package com.arkiv.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.local.DownloadAction
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.data.local.ChapterDownloadState
import com.arkiv.player.data.local.DownloadLabel
import com.arkiv.player.data.local.FuenteDeDescarga
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.EtiquetaDeCapitulo
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.components.BarraDeDescarga
import com.arkiv.player.ui.components.ControlDeDescarga
import com.arkiv.player.ui.components.DialogoDeDescarga
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.theme.NucDownloadedGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    identifier: String,
    onBack: () -> Unit,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    // Permiso de notificaciones (API 33+): se pide recién al disparar una descarga, que es lo único
    // que notifica desde esta pantalla. Mismo momento y mismo helper que
    // AnimeShowDetailScreen/CineDetailScreen.
    val askNotifications = rememberPostNotificationsRequest()
    // Avisa "eso ya lo tenés bajado" cuando la cola saltea una descarga duplicada (ver
    // DuplicateDownloadPolicy): si no, el botón parecería no hacer nada.
    val notifyDuplicates = rememberDuplicateDownloadNotice()
    val vm: DetailViewModel = viewModel(
        // El teléfono siempre navega con un identifier crudo (no con una llave de grupo);
        // observeGroupMembers lo resuelve igual por su fallback a `rows.filter { identifier == groupKey }`.
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, groupKey = identifier) } },
    )
    val detail by vm.detail.collectAsStateWithLifecycle()
    val skipMarker by vm.skipMarker.collectAsStateWithLifecycle()
    // Título e imagen de cada capítulo según TMDB. Es caché local: la primera apertura de la serie
    // la puebla y a partir de ahí sale de la base. Si no se sabe a qué serie pertenece el ítem,
    // los mapas quedan vacíos y cada fila cae a su nombre de archivo.
    val tmdbTitles by graph.repository.observeEpisodeTitles(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    val tmdbStills by graph.repository.observeEpisodeStills(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    // Frames capturados durante la reproducción: la escena real del capítulo, cuando existe le
    // gana al still de TMDB (ver ThumbnailChoice). Solo tiene entrada si el capítulo se
    // empezó a ver, así que "gana solo en lo empezado" sale solo de que la clave no esté.
    val tmdbFrames by graph.repository.observeEpisodeFrames(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    // Sinopsis de cada capítulo (TMDB). Mismo caché que títulos/stills, y misma regla de vacío
    // si no se sabe a qué serie pertenece el ítem.
    val tmdbOverviews by graph.repository.observeEpisodeOverviews(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    LaunchedEffect(identifier) {
        // Mismo arreglo que en el detalle del TV: los ítems de Magis guardados sin `tmdbId` no
        // tienen con qué pedir stills, así que primero se le pregunta al gateway (una sola vez).
        com.arkiv.player.data.gateway.repararIdentidadDeMagis(
            graph.repository, graph.fuenteDeContenido, identifier,
        )
        runCatching { graph.repository.ensureEpisodeStills(identifier) }
    }
    // Estado de descarga al dispositivo de cada capítulo, directo de la tabla `downloads` (la misma
    // que muestra la pantalla de Descargas). Reemplaza a la caché de "qué hay en la NUC": ese tilde
    // verde anunciaba "ya descargado" sobre contenido que, desde que se desconectó la reproducción
    // remota, nadie podía ver ni reproducir.
    val downloadRows by graph.repository.observeDownloadRows().collectAsStateWithLifecycle(emptyList())
    val savedEpisodeIds = remember(downloadRows) {
        downloadRows.filter { it.state == LocalDownloadState.COMPLETED }.map { it.episodeId }.toSet()
    }
    // Estado de descarga POR capítulo, no un simple "hace algo / no hace nada": la fila necesita
    // saber si está esperando turno, en qué porcentaje va, o por qué falló. Ver
    // [ChapterDownloadState].
    val estadosDeDescarga = remember(downloadRows) {
        downloadRows.associate { it.episodeId to ChapterDownloadState.of(it) }
    }

    // Guarda capítulos en el DISPOSITIVO. El permiso de notificaciones se pide UNA vez por acción
    // del usuario (no una por capítulo): un lote de 30 dispararía 30 `launcher.launch` seguidos
    // sobre el mismo ActivityResultLauncher antes de que el usuario conteste el primer diálogo.
    fun saveEpisodesLocally(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        askNotifications()
        scope.launch {
            // Un solo aviso para todo el lote, no uno por capítulo.
            notifyDuplicates(episodes.map { graph.localDownloads.enqueue(it.id, FuenteDeDescarga.para(it.id)) })
        }
    }

    val onDownloadEpisode: (Episode) -> Unit = { ep -> saveEpisodesLocally(listOf(ep)) }

    // Reintentar lo que falló, sin salir a la pantalla de Descargas: el fallo se ve en la misma fila
    // donde se pidió la descarga, así que la acción también vive ahí.
    val onRetryEpisode: (Episode) -> Unit = { ep -> scope.launch { graph.localDownloads.retry(ep.id) } }

    // Arrepentirse también vive en la fila, por el mismo motivo. `cancel` conserva el parcial (la
    // descarga reanuda desde ahí); `remove` borra fila y archivo, que es lo que corresponde tanto a
    // lo que nunca empezó como a lo que ya no se quiere tener guardado.
    val onDownloadAction: (Episode, DownloadAction) -> Unit = { ep, accion ->
        scope.launch {
            when (accion) {
                DownloadAction.CANCEL -> graph.localDownloads.cancel(ep.id)
                DownloadAction.REMOVE_FROM_QUEUE, DownloadAction.DELETE ->
                    graph.localDownloads.remove(ep.id)
            }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showMarkersDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // Solo se ofrecen para guardar en el dispositivo los capítulos que se pueden bajar: los que
    // tienen una estrategia en `AppGraph.downloadStrategies` (hoy, solo Magis). Un capítulo de
    // Caracol (Widevine) o una fila vieja de archive.org terminaban FAILED con "Fuente no soportada"
    // DESPUÉS de que esta pantalla dijera "Guardando": una opción que va a fallar no se muestra. Ver
    // `FuenteDeDescarga.sePuedeBajar`.
    val estrategias = remember { graph.downloadStrategies.keys }
    val sePuedeBajar: (Episode) -> Boolean = { ep -> FuenteDeDescarga.sePuedeBajar(ep.id, estrategias) }
    val savableEpisodes = detail?.episodes.orEmpty().filter(sePuedeBajar)

    // El botón de esta pantalla guarda EN EL DISPOSITIVO (worker local), no en ningún servidor
    // propio: la descarga a la NUC (`ArkivOfflineApi`/`NucDownloadCheckWorker`) se borró entera en
    // la poda de esta rama, no quedó "desconectada" — no existe una sola línea de esa maquinaria en
    // el árbol.
    fun saveSelectedLocally(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        saveEpisodesLocally(episodes)
        // El encolado es instantáneo y silencioso; sin esta confirmación el toque no deja ninguna
        // huella visible hasta que el worker arranca. Snackbar (no un Text fijo) porque el contenido
        // de esta pantalla es un LazyColumn que además auto-scrollea.
        scope.launch { snackbarHost.showSnackbar("Guardando en el dispositivo (${episodes.size})") }
    }

    if (showMarkersDialog) {
        MarkersDialog(
            current = skipMarker,
            onDismiss = { showMarkersDialog = false },
            onSave = { openStart, openEnd, endStart ->
                vm.saveSkipMarker(openStart, openEnd, endStart)
            },
        )
    }

    if (showRenameDialog) {
        var newTitle by remember(showRenameDialog) { mutableStateOf(detail?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Cambiar nombre") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    label = { Text("Nombre") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = { vm.rename(newTitle); showRenameDialog = false },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancelar") } },
        )
    }

    if (showSaveDialog && savableEpisodes.isNotEmpty()) {
        SaveEpisodesDialog(
            episodes = savableEpisodes,
            alreadySaved = savedEpisodeIds,
            onDismiss = { showSaveDialog = false },
            onConfirm = { chosen ->
                showSaveDialog = false
                saveSelectedLocally(chosen)
            },
        )
    }

    Scaffold(
        containerColor = ArkivBlack,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // Va en el "⋮" y no al lado de "Reproducir": guardar varios capítulos es
                        // una acción de toda la serie que se usa una vez, no algo que compita
                        // visualmente con el botón principal. El guardado de UN capítulo suelto
                        // sigue estando en su fila.
                        if (savableEpisodes.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Guardar en el dispositivo") },
                                onClick = {
                                    menuExpanded = false
                                    showSaveDialog = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Marcadores de intro/outro") },
                            onClick = {
                                menuExpanded = false
                                showMarkersDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Cambiar nombre") },
                            onClick = {
                                menuExpanded = false
                                showRenameDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Quitar de mi biblioteca") },
                            onClick = {
                                menuExpanded = false
                                vm.removeFromLibrary { onBack() }
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ArkivBlack),
            )
        },
    ) { padding ->
        val data = detail
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Cargando…", color = ArkivTextSecondary)
            }
            return@Scaffold
        }
        DetailContent(
            data = data,
            estadosDeDescarga = estadosDeDescarga,
            onPlayEpisode = onPlayEpisode,
            onDownloadEpisode = onDownloadEpisode,
            sePuedeBajar = sePuedeBajar,
            onRetryEpisode = onRetryEpisode,
            onDownloadAction = onDownloadAction,
            onToggleWatched = vm::toggleWatched,
            tmdbTitles = tmdbTitles,
            tmdbStills = tmdbStills,
            tmdbFrames = tmdbFrames,
            tmdbOverviews = tmdbOverviews,
            // El inferior se usa siempre. El superior casi nunca hace falta -- en un panel, lo que
            // hay debajo del TopAppBar es el póster a sangre (decorativo, se puede tapar) -- pero en
            // dos paneles el panel derecho arranca con contenido tocable (chips o el primer
            // capítulo), así que ahí sí hace falta para no dejarlo tapado e inalcanzable. Ver
            // DetailContent.
            bottomInset = padding.calculateBottomPadding(),
            topInset = padding.calculateTopPadding(),
        )
    }
}

@Composable
private fun DetailContent(
    data: ItemDetail,
    /** episodeId -> en qué va su descarga al dispositivo. Ver [DetailScreen]. */
    estadosDeDescarga: Map<String, DownloadDisplayState>,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    /** Si hay con qué bajar ese capítulo. Sin eso, su fila no ofrece guardarlo. Ver [DetailScreen]. */
    sePuedeBajar: (Episode) -> Boolean,
    onRetryEpisode: (Episode) -> Unit,
    /** Sacar de la cola / cancelar / borrar. Ya viene confirmada por el usuario. */
    onDownloadAction: (Episode, DownloadAction) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
    /** episodeId -> título / imagen / sinopsis del capítulo según TMDB. Vacíos si no se sabe la serie. Ver [DetailScreen]. */
    tmdbTitles: Map<String, String>,
    tmdbStills: Map<String, String>,
    /** episodeId -> ruta en disco del frame capturado. Le gana a [tmdbStills]; ver [DetailScreen]. */
    tmdbFrames: Map<String, String>,
    tmdbOverviews: Map<String, String>,
    bottomInset: androidx.compose.ui.unit.Dp,
    /** Alto del TopAppBar. Solo se usa en dos paneles, para que el panel derecho no arranque tapado
     *  por la barra; en un panel el contenido sigue empezando en y=0, sin padding, como siempre. */
    topInset: androidx.compose.ui.unit.Dp,
) {
    // Capítulo + acción que el usuario pidió deshacer y que todavía no confirmó. Ver
    // [DownloadConfirmation]: las tres acciones se preguntan porque el control es chiquito y todas
    // cuestan caro si se tocan sin querer.
    var porConfirmar by remember { mutableStateOf<Pair<Episode, DownloadAction>?>(null) }

    // Sitios detectados entre TODOS los episodios de la serie (no de la lista ya filtrada): el
    // set de chips no puede encogerse cuando el usuario elige un filtro, o desaparecería la forma
    // de volver a "Todos". Ver [siteLabelOf].
    val siteLabels = remember(data.episodes) {
        data.episodes.mapNotNull { siteLabelOf(it.sourceRef) }.toCollection(sortedSetOf())
    }
    // null = "Todos" (sin filtro), el default — no tocar el comportamiento existente hasta que el
    // usuario elija un chip a propósito. rememberSaveable (no remember): Navigation Compose saca
    // esta pantalla de composición al abrir el reproductor, y un remember plano se resetea al
    // volver — el filtro elegido se perdía en cada "atrás" desde el player.
    var selectedSite by rememberSaveable(data.identifier) { mutableStateOf<String?>(null) }
    // Episodes without a recognizable sourceRef -- which today is basically ALL of them: Magis and
    // Ditu both write an opaque ref into `torrentData` (`magis1:...`, `ditu1:...`), not a URL, so
    // `siteLabelOf` returns null for them. This only ever resolves to a real host for a legacy row
    // saved by the now-removed web source (a plain URL) or before sourceRef was persisted at all --
    // these episodes don't belong to ANY site in the filter, so they stay visible always instead of
    // disappearing when the user filters by one specific site.
    val filteredEpisodes = remember(data.episodes, selectedSite) {
        val site = selectedSite
        if (site == null) {
            data.episodes
        } else {
            data.episodes.filter { ep -> val label = siteLabelOf(ep.sourceRef); label == null || label == site }
        }
    }
    val bySection = filteredEpisodes.groupBy { it.section }

    // Misma condición para decidir el layout (más abajo, dónde va la ficha) y para el offset de
    // resumeIndex: si se calculan por separado, el día que uno cambie sin el otro el auto-scroll
    // se desincroniza en silencio. Ver FichaDelItem.
    val dosPaneles = esTabletHorizontal()

    // Índice (aplanado) del episodio en el que voy, para hacer scroll automático al abrir.
    // En un panel, el primer item del LazyColumn es FichaDelItem (imagen + bloque de título); en
    // dos paneles la ficha vive aparte, en el panel izquierdo, y no cuenta -- por eso itemsDeCabecera
    // sale de dosPaneles y no de un número fijo. Después, SI hay 2+ sitios, viene 1 item más con la
    // fila de chips de filtro (ver más abajo); y después cada sección con nombre añade 1 item de
    // cabecera antes de sus episodios.
    val listState = rememberLazyListState()
    val currentEpisodeId = data.inProgressEpisode?.id
    val resumeIndex = remember(filteredEpisodes, data.progress, siteLabels, dosPaneles) {
        val target = data.inProgressEpisode ?: return@remember null
        val itemsDeCabecera = if (dosPaneles) 0 else 1
        var idx = itemsDeCabecera + if (siteLabels.size >= 2) 1 else 0
        bySection.forEach { (section, episodes) ->
            if (section.isNotBlank()) idx += 1
            val pos = episodes.indexOfFirst { it.id == target.id }
            if (pos >= 0) return@remember idx + pos
            idx += episodes.size
        }
        null
    }
    // Solo auto-scrolleamos una vez por apertura de la pantalla, para no pelear con el usuario
    // si luego scrollea a mano.
    var didAutoScroll by remember(data.identifier) { mutableStateOf(false) }
    LaunchedEffect(resumeIndex) {
        val idx = resumeIndex
        if (idx != null && !didAutoScroll) {
            didAutoScroll = true
            listState.scrollToItem(idx)
        }
    }

    // El listado de capítulos es EL MISMO en un panel o en dos: acá vive una sola vez (chips +
    // secciones + filas) y ambas ramas del if de abajo lo llaman tal cual, nunca lo copian.
    val listaDeCapitulos: @Composable () -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                // En dos paneles este LazyColumn queda DEBAJO del TopAppBar (que acá cubre contenido
                // tocable, no un póster a sangre), y un LazyColumn no puede scrollear por encima del
                // offset 0 -- sin este padding la primera fila (chips o el primer capítulo) queda
                // tapada para siempre. En un panel se queda en 0.dp, igual que hoy.
                top = if (dosPaneles) topInset else 0.dp,
                bottom = 32.dp + bottomInset,
            ),
        ) {
            // En un panel la ficha va acá adentro, como siempre. En dos paneles ya se dibujó aparte
            // (más abajo, en el panel izquierdo) y no se duplica -- por eso resumeIndex también la
            // cuenta o no según este mismo `dosPaneles`.
            if (!dosPaneles) {
                item { FichaDelItem(data = data, onPlayEpisode = onPlayEpisode) }
            }

            // Solo tiene sentido filtrar si hay 2+ sitios distintos guardados para esta serie — con 0
            // o 1 sitio, la fila de chips no filtraría nada y sería puro ruido visual.
            if (siteLabels.size >= 2) {
                item {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedSite == null,
                            onClick = { selectedSite = null },
                            label = { Text("Todos") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                        )
                        siteLabels.forEach { site ->
                            FilterChip(
                                selected = selectedSite == site,
                                onClick = { selectedSite = site },
                                label = { Text(site) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
                            )
                        }
                    }
                }
            }

            bySection.forEach { (section, episodes) ->
                if (section.isNotBlank()) {
                    item {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                }
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        episode = ep,
                        progress = data.progress[ep.id],
                        isCurrent = ep.id == currentEpisodeId,

                        // Real chapter title and image (TMDB). Only show up when it could tell which
                        // series/number this row is; otherwise the row falls back to the filename and
                        // to the on-device captured frame (or the series poster, if there's no frame
                        // either) -- see ThumbnailChoice further down.
                        tmdbTitle = tmdbTitles[ep.id],
                        tmdbStill = tmdbStills[ep.id],
                        tmdbFrame = tmdbFrames[ep.id],
                        tmdbOverview = tmdbOverviews[ep.id],
                        // Thumbnail fallback: chapters never bring their own still today -- both
                        // MagisEntities and DituEntities always save thumbPath = null, the same way
                        // the removed web source's `addWebSeriesEpisode` used to; only the series
                        // poster is known, and without this the row was left with an empty box.
                        fallbackThumb = data.thumbnailUrl,
                        estado = estadosDeDescarga[ep.id] ?: DownloadDisplayState.NotDownloaded,
                        onPlay = { onPlayEpisode(ep.id) },
                        onDownload = if (sePuedeBajar(ep)) { { onDownloadEpisode(ep) } } else null,
                        onRetry = { onRetryEpisode(ep) },
                        onPedirAccion = { accion -> porConfirmar = ep to accion },
                        onToggleWatched = onToggleWatched,
                    )
                }
            }
        }
    }

    // Tablet en horizontal: ficha a la izquierda, capítulos a la derecha, los dos a la vista al
    // mismo tiempo. En celular y en vertical no cambia nada -- es la misma lista de siempre.
    if (dosPaneles) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // Mismo motivo que el bottom del LazyColumn de al lado: sin esto, si la
                    // sinopsis llena el panel, la última línea queda debajo de la barra de gestos.
                    .padding(bottom = bottomInset),
            ) {
                FichaDelItem(data = data, onPlayEpisode = onPlayEpisode)
            }
            Box(Modifier.weight(1.4f)) { listaDeCapitulos() }
        }
    } else {
        listaDeCapitulos()
    }

    DialogoDeDescarga(
        accion = porConfirmar?.second,
        nombreDelCapitulo = porConfirmar?.first?.let { tmdbTitles[it.id] ?: it.displayName },
        onConfirmar = {
            porConfirmar?.let { (episodio, accion) -> onDownloadAction(episodio, accion) }
            porConfirmar = null
        },
        onCerrar = { porConfirmar = null },
    )
}

/**
 * La ficha del ítem: imagen 16:9 con degradé, título, avance, botón de reproducir/continuar y
 * sinopsis. En celular/vertical es el primer item del [LazyColumn] de [DetailContent]; en tablet
 * horizontal se dibuja aparte, en el panel izquierdo -- mismo contenido en los dos casos, nunca
 * dos copias.
 */
@Composable
private fun FichaDelItem(data: ItemDetail, onPlayEpisode: (String) -> Unit) {
    val resume = data.resumeEpisode
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(ArkivSurfaceHigh),
    ) {
        AsyncImage(
            model = data.thumbnailUrl,
            contentDescription = data.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, ArkivBlack),
                    ),
                ),
        )
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(data.title, style = MaterialTheme.typography.headlineMedium)
        Text(
            EtiquetaDeCapitulo.avance(data, "videos"),
            color = ArkivTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (resume != null) {
            Button(
                onClick = { onPlayEpisode(resume.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("  ${EtiquetaDeCapitulo.botonReproducir(data)}", fontWeight = FontWeight.Bold)
            }
        }
        if (!data.description.isNullOrBlank()) {
            Text(
                data.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Short host ("serieskao.top") from an episode's `sourceRef`, to group the list filter by origin
 * site. Returns null for anything that isn't a real http URL -- which today is essentially
 * everything: Magis and Ditu both write an opaque ref (`magis1:...`, `ditu1:...`), not a URL, into
 * the field this reads (see `siteLabels` above). This only ever resolves for a legacy row saved by
 * the now-removed web source (a real page URL), a torrent magnet, or an episode saved before
 * `sourceRef` was persisted -- none of those has a "site" to show as a chip either. Uses plain
 * `java.net.URL(...).host` rather than inventing a second way to pull a host out of a URL.
 */
private fun siteLabelOf(sourceRef: String?): String? {
    if (sourceRef.isNullOrBlank()) return null
    val host = runCatching { java.net.URL(sourceRef).host }.getOrNull()?.lowercase()?.ifBlank { null }
        ?: return null
    return host.removePrefix("www.")
}

/**
 * Elegir qué capítulos de la serie guardar en el dispositivo (todos, algunos, o uno).
 *
 * Sobre los [Episode] ya guardados de esta pantalla: cabecera de "seleccionar todo" tri-estado,
 * cabecera por temporada con su propio marcar/desmarcar (una serie larga tiene cientos de filas y
 * marcarlas de a una es inviable) y el mismo tilde verde informativo para lo que ya está guardado.
 */
@Composable
private fun SaveEpisodesDialog(
    episodes: List<Episode>,
    alreadySaved: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Episode>) -> Unit,
) {
    // La selección por defecto es TODO lo que falta, no todo a secas: el caso normal de abrir esto
    // en una serie que ya se bajó a medias es "traeme el resto", y volver a bajar lo que ya está
    // sería gastar datos y disco al pedo. Si no falta nada se preseleccionan todos igual, para que
    // el diálogo no abra vacío y sin nada que confirmar (re-bajar es un caso válido, p.ej. si la
    // copia salió mal).
    val selected = remember(episodes, alreadySaved) {
        val pending = episodes.filterNot { it.id in alreadySaved }
        mutableStateListOf<String>().apply { addAll((pending.ifEmpty { episodes }).map { it.id }) }
    }
    val total = episodes.size
    val allSelected = selected.size == total && episodes.isNotEmpty()
    fun toggleAll(on: Boolean) {
        selected.clear()
        if (on) selected.addAll(episodes.map { it.id })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar en el dispositivo") },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(episodes.filter { it.id in selected }) },
            ) {
                Text("Guardar (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { toggleAll(!allSelected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = when {
                            allSelected -> ToggleableState.On
                            selected.isEmpty() -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = { toggleAll(!allSelected) },
                    )
                    Text(
                        if (allSelected) "Deseleccionar todo" else "Seleccionar todo",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${selected.size}/$total", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()

                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    episodes.groupBy { it.section }.forEach { (section, eps) ->
                        val ids = eps.map { it.id }
                        if (section.isNotBlank()) {
                            item(key = "sec-$section") {
                                val allOfSection = ids.all { it in selected }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            if (allOfSection) selected.removeAll(ids)
                                            else selected.addAll(ids.filterNot { it in selected })
                                        }
                                        .padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = allOfSection,
                                        onCheckedChange = { on ->
                                            if (on) selected.addAll(ids.filterNot { it in selected })
                                            else selected.removeAll(ids)
                                        },
                                    )
                                    Text(section, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.weight(1f))
                                    Text("${eps.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        items(eps, key = { it.id }) { ep ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = ep.id in selected,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(ep.id) else selected.remove(ep.id)
                                    },
                                )
                                Text(
                                    ep.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ep.id in alreadySaved) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Ya guardado en el dispositivo",
                                        tint = NucDownloadedGreen,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: com.arkiv.player.data.db.PlaybackEntity?,
    isCurrent: Boolean,
    /** Miniatura de la serie, para las filas cuyo episodio no trae una propia. */
    fallbackThumb: String?,
    tmdbTitle: String?,
    tmdbStill: String?,
    /** Ruta en disco del frame capturado. Le gana a [tmdbStill]; ver [ThumbnailChoice]. */
    tmdbFrame: String?,
    /** Sinopsis del capítulo (TMDB). Null si no se pudo resolver; la fila simplemente no la muestra. */
    tmdbOverview: String?,
    /** En qué va su descarga al dispositivo: manda el ícono de la derecha y la barra de abajo. */
    estado: DownloadDisplayState,
    onPlay: () -> Unit,
    /** Null = no hay con qué bajar este capítulo (ver `FuenteDeDescarga.sePuedeBajar`). */
    onDownload: (() -> Unit)?,
    onRetry: () -> Unit,
    /** El usuario pidió deshacer algo de la descarga; quien recibe esto se encarga de confirmarlo. */
    onPedirAccion: (DownloadAction) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
) {
    val watched = progress?.watched == true
    val cardShape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(cardShape)
            // Vistos: fondo gris sutil ("ya lo vi"). El que voy: borde blanco ("acá voy").
            .background(if (watched) ArkivSurface else Color.Transparent)
            .then(
                if (isCurrent) Modifier.border(1.5.dp, Color.White, cardShape) else Modifier,
            )
            .clickable(onClick = onPlay),
    ) {
        // El padding interno vive acá y no en la tarjeta: la barra de descarga tiene que llegar a
        // los bordes de la tarjeta, no quedar flotando a 8dp de cada lado.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 63.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                // El frame capturado primero (la escena real de donde vas), después el still de TMDB
                // (la foto del capítulo) y por último el respaldo de la serie. El fotograma de
                // archive.org que iba acá se borró en la poda de esta rama junto con esa fuente.
                // Cadena armada con ThumbnailChoice -- no a mano -- para no desalinearse del
                // resto de las pantallas.
                val thumb = ThumbnailChoice.choose(
                    tmdbFrame,
                    tmdbStill,
                    null,
                    fallbackThumb,
                )
                AsyncImage(
                    model = thumb,
                    contentDescription = tmdbTitle ?: episode.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.Center),
                )
                if (progress != null && progress.durationMs > 0 && !watched) {
                    LinearProgressIndicator(
                        progress = { progress.positionMs.toFloat() / progress.durationMs },
                        color = ArkivRed,
                        trackColor = Color(0x66000000),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    // El nombre del archivo es el respaldo, no la primera opción: para nuestras
                    // subidas es "s01e03", que no dice nada de qué capítulo es.
                    //
                    // Pero el NÚMERO manda y no puede desaparecer: con `tmdbTitle` a secas, un capítulo
                    // de Magis pasaba de "E5  Daima T1_5" (el displayName ya trae el número) a solo
                    // "Panzy", y la lista se quedaba sin forma de saber cuál era cuál. La regla vive en
                    // [EtiquetaDeCapitulo.conNombre], compartida con el detalle del TV.
                    EtiquetaDeCapitulo.conNombre(episode, tmdbTitle),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (watched) ArkivTextSecondary else MaterialTheme.colorScheme.onBackground,
                )
                // No chapter brings a real duration when it's first saved -- MagisEntities and
                // DituEntities both write durationSeconds = 0.0 on purpose, same as the removed
                // web/torrent sources used to (see ArkivRepository.kt). Showing "0:00" there looked
                // like a bug instead of a value that simply isn't known yet, so the row omits it.
                if (episode.durationSeconds > 0) {
                    Text(
                        formatDuration((episode.durationSeconds * 1000).toLong()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArkivTextSecondary,
                    )
                }
                // En qué va la descarga, EN PALABRAS. La barra y el ícono ya lo dicen en colores y
                // formas, pero eso solo se entiende sabiendo de antemano qué significan: "Bajando 42%"
                // o el motivo real del fallo se leen sin traducir nada.
                DownloadLabel.of(estado)?.let { etiqueta ->
                    Text(
                        etiqueta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (estado) {
                            is DownloadDisplayState.Failed, DownloadDisplayState.NeedsConfirmation -> ArkivRed
                            DownloadDisplayState.Done -> NucDownloadedGreen
                            else -> ArkivTextPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Sinopsis del capítulo (TMDB): solo si se pudo resolver. Recortada a 2 líneas -- la
                // fila ya compite por espacio con la miniatura y los botones, no puede crecer sin límite.
                if (!tmdbOverview.isNullOrBlank()) {
                    Text(
                        tmdbOverview,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // Un solo slot para "guardar en el dispositivo", nunca dos cosas a la vez: el tilde de que
            // ya está guardado, en qué va la descarga, o el botón para guardarlo. Ofrecer descargar lo
            // que ya está no aporta nada, y la fila tampoco tiene ancho para un ícono más (miniatura de
            // 112dp + 2 IconButton ya la dejan justa en un teléfono angosto).
            //
            // El tilde es informativo, no una acción -- por eso no es un IconButton (no se toca, no ocupa
            // un slot de 48dp) y no comparte el rojo de "visto" que tiene al lado.
            //
            // Antes había DOS slots: este (Download, al teléfono) y otro con CloudDownload que mandaba a
            // bajar a la NUC. El de la NUC se quitó porque producía algo que ya nadie puede ver ni
            // reproducir desde que se desconectó la reproducción remota.
            //
            // Sin con qué bajarlo, el botón de guardar no se muestra. Si ya hay una descarga suya en
            // la tabla (de antes), el control sigue, para poder borrarla.
            if (onDownload != null || estado != DownloadDisplayState.NotDownloaded) {
                ControlDeDescarga(
                    estado = estado,
                    onDownload = onDownload ?: {},
                    onRetry = onRetry,
                    onPedirAccion = onPedirAccion,
                )
            }
            IconButton(onClick = { onToggleWatched(episode.id, !watched) }) {
                Icon(
                    if (watched) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (watched) "Marcar no visto" else "Marcar visto",
                    tint = if (watched) ArkivRed else ArkivTextSecondary,
                )
            }
        }
        // La barra va pegada al borde inferior de la tarjeta y a todo su ancho: es el único lugar
        // donde no compite con la miniatura ni con los dos botones, y se lee de un vistazo
        // recorriendo la lista.
        BarraDeDescarga(estado)
    }
}
