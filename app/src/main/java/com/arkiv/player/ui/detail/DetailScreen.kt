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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.EpisodeNumbering
import com.arkiv.player.data.offline.NucDownloadItem
import com.arkiv.player.data.offline.NucDownloads
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.offline.rememberPostNotificationsRequest
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
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
    val context = LocalContext.current
    // Permiso de notificaciones (API 33+): se pide recién al disparar una descarga a la NUC, que es
    // lo único que notifica desde esta pantalla. Mismo momento y mismo helper que
    // AnimeShowDetailScreen/CineDetailScreen.
    val askNotifications = rememberPostNotificationsRequest()
    val vm: DetailViewModel = viewModel(
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, identifier) } },
    )
    val detail by vm.detail.collectAsStateWithLifecycle()
    val skipMarker by vm.skipMarker.collectAsStateWithLifecycle()
    val onDownloadEpisode: (Episode) -> Unit = { ep ->
        scope.launch { graph.downloader.enqueue(ep) }
    }

    // Refresca la caché local de "qué episodios ya están en la NUC" al abrir el detalle: así
    // PlaybackPreferenceStore (Task 10) tiene datos frescos aunque la descarga se haya disparado
    // desde otro dispositivo, o el usuario haya llegado por "Mi biblioteca" en vez de por la
    // búsqueda/catálogo (AnimeShowDetailScreen/CineDetailScreen), que son las otras 2 puertas de
    // entrada que ya hacían este refresh.
    //
    // OJO: el `identifier` de esta pantalla NO es el seriesId — es el identifier del ítem LOCAL,
    // que para una serie viene prefijado ("web:series:anilist$123"), mientras que la NUC la conoce
    // por el seriesId desnudo ("anilist$123"). Pasarlo crudo no fallaba con ruido: simplemente
    // preguntaba por una serie inexistente y nunca traía nada. Ver [SeriesItemIds] y el mismo
    // pelado de prefijo en PlayerViewModel.loadWebRespectingPreference.
    // Si no es una serie guardada (un ítem de archive.org suelto) no hay nada que preguntar.
    //
    // `replace = true`: la respuesta es la verdad completa de la serie (refleja también borrados).
    // Si la consulta falla, NucDownloads.refreshLibraryCache no toca nada (ver ahí el porqué), así
    // que leer la caché después siempre es seguro, haya habido red o no.
    //
    // La clave del set incluye la fuente (sourceRef), no solo (temporada, capítulo): esta lista
    // puede tener VARIAS filas del mismo capítulo guardadas desde sitios distintos, y matchear solo
    // por número las marcaría todas como descargadas aunque la copia en la NUC venga de una sola.
    // Mismo criterio (y mismo porqué en detalle) que WebPackDialog.
    val nucSeriesId = com.arkiv.player.data.SeriesItemIds.seriesIdOrNull(identifier)
    var nucDownloaded by remember(identifier) { mutableStateOf<Set<Triple<Int, Int, String>>>(emptySet()) }
    LaunchedEffect(identifier) {
        if (nucSeriesId == null) return@LaunchedEffect
        val dao = graph.database.nucLibraryItemDao()
        com.arkiv.player.data.offline.NucDownloads.refreshLibraryCache(
            graph.arkivOfflineApi, dao, seriesId = nucSeriesId, replace = true,
        )
        nucDownloaded = dao.forSeries(nucSeriesId)
            .mapNotNull { item -> item.sourceRef?.let { Triple(item.season, item.episode, it) } }
            .toSet()
    }
    // IDs con un pedido de descarga a la NUC en curso: sin esto el botón por fila no daba ningún
    // feedback entre el toque y la Snackbar final, así que un toque doble (o varios de impaciencia)
    // mandaba el mismo capítulo dos o tres veces. Mientras un id está acá, la fila muestra un
    // spinner en vez del ícono y no vuelve a aceptar el toque.
    var pendingNucIds by remember(identifier) { mutableStateOf<Set<String>>(emptySet()) }

    var menuExpanded by remember { mutableStateOf(false) }
    var showMarkersDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showNucDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // Episodios de ESTA pantalla que se pueden mandar a bajar a la NUC. Tres condiciones, y las
    // tres son necesarias:
    //
    //  1. `!isTorrent`: pedido explícito -- los ítems de torrent no llevan este botón. arkiv-offline
    //     no baja torrents desde la app y ofrecerlo sería mentir.
    //  2. `seriesIdOrNull(identifier) != null`: la NUC indexa por seriesId desnudo, y si el ítem no
    //     es una serie guardada no hay a qué asociar el job. OJO que este helper también devuelve
    //     algo para las series de torrent (`torrent:series:`), así que NO reemplaza a (1).
    //  3. `nucDownloadItemOrNull() != null`: exige sourceRef (la pageUrl, que es lo que la NUC baja)
    //     + temporada y capítulo parseables. Deja afuera archive.org (siempre sourceRef == null) y
    //     cualquier fila vieja guardada sin fuente. Ver el porqué de "el ítem completo o nada" ahí.
    //
    // Si queda vacío no se agrega NADA a la pantalla (ni botón por fila ni entrada de menú): un
    // ítem de archive.org o de torrent se ve exactamente igual que antes.
    val nucEpisodes = remember(detail, nucSeriesId) {
        val d = detail
        if (d == null || d.isTorrent || nucSeriesId == null) emptyList()
        else d.episodes.filter { it.nucDownloadItemOrNull() != null }
    }

    // Único punto de disparo (fila suelta y selección del diálogo pasan por acá): arma los items y
    // delega en NucDownloads.start, que es el que sabe crear el job + registrarlo en
    // local_active_jobs + programar el worker que avisa al terminar. Ver NucDownloads: esta
    // secuencia estaba copiada 5 veces y por eso vive en un solo lugar.
    fun downloadToNuc(episodes: List<Episode>) {
        val d = detail ?: return
        val seriesId = nucSeriesId ?: return
        // Filtra también los que ya tienen un pedido en curso -- guarda extra contra el doble toque
        // más allá de que el ícono ya esté deshabilitado (la recomposición no es instantánea).
        val pending = episodes.filterNot { it.id in pendingNucIds }
        val items = pending.mapNotNull { it.nucDownloadItemOrNull() }
        if (items.isEmpty()) return
        val ids = pending.map { it.id }.toSet()
        pendingNucIds = pendingNucIds + ids
        askNotifications()
        scope.launch {
            try {
                val err = NucDownloads.start(
                    context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                    seriesId = seriesId, showTitle = d.title, posterUrl = d.thumbnailUrl, items = items,
                )
                // La descarga ocurre en la NUC, no acá: sin confirmación explícita el toque del botón no
                // deja ninguna huella visible y parece que no hizo nada. Snackbar (no un Text rojo fijo
                // como en AnimeShowDetailScreen) porque el contenido de esta pantalla es un LazyColumn
                // que además auto-scrollea: un cartel dentro de la lista podría quedar fuera de vista.
                snackbarHost.showSnackbar(err ?: "Descarga enviada a la NUC (${items.size})")
            } finally {
                // finally, no solo en el camino feliz: si NucDownloads.start lanzara (no debería,
                // pero es una llamada de red) la fila tiene que volver a ofrecer el botón en vez de
                // quedarse con el spinner puesto para siempre.
                pendingNucIds = pendingNucIds - ids
            }
        }
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

    if (showNucDialog && nucEpisodes.isNotEmpty()) {
        NucDownloadDialog(
            episodes = nucEpisodes,
            alreadyInNuc = nucDownloaded,
            onDismiss = { showNucDialog = false },
            onConfirm = { chosen ->
                showNucDialog = false
                downloadToNuc(chosen)
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
                        // Va en el "⋮" y no al lado de "Reproducir": bajar a la NUC es una acción de
                        // toda la serie que se usa una vez, no algo que compita visualmente con el
                        // botón principal. Solo aparece si hay episodios elegibles (ver nucEpisodes).
                        if (nucEpisodes.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Descargar a la NUC") },
                                onClick = {
                                    menuExpanded = false
                                    showNucDialog = true
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
            nucDownloaded = nucDownloaded,
            pendingNucIds = pendingNucIds,
            // null = este ítem no admite descargas a la NUC (torrent/archive.org): la fila no dibuja
            // el botón. Con lambda, cada fila decide igual si ella misma es elegible.
            onDownloadToNuc = if (nucEpisodes.isNotEmpty()) ({ ep -> downloadToNuc(listOf(ep)) }) else null,
            onPlayEpisode = onPlayEpisode,
            onDownloadEpisode = onDownloadEpisode,
            onToggleWatched = vm::toggleWatched,
            // Solo el inferior: el superior ya lo cubre el TopAppBar (agregarlo acá lo duplicaría).
            bottomInset = padding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun DetailContent(
    data: ItemDetail,
    /** (temporada, capítulo, fuente) de lo que ya está bajado en la NUC. Ver [DetailScreen]. */
    nucDownloaded: Set<Triple<Int, Int, String>>,
    /** IDs con un pedido de descarga a la NUC en curso -- la fila muestra spinner. Ver [DetailScreen]. */
    pendingNucIds: Set<String>,
    /** Manda UN episodio a bajar a la NUC, o null si este ítem no lo admite. Ver [DetailScreen]. */
    onDownloadToNuc: ((Episode) -> Unit)?,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
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
    // Los episodios sin sourceRef reconocible (archive.org, magnets de torrent, o guardados antes
    // de que se persistiera sourceRef) no pertenecen a NINGÚN sitio del filtro, así que se quedan
    // siempre visibles en vez de desaparecer cuando el usuario filtra por un sitio puntual.
    val filteredEpisodes = remember(data.episodes, selectedSite) {
        val site = selectedSite
        if (site == null) {
            data.episodes
        } else {
            data.episodes.filter { ep -> val label = siteLabelOf(ep.sourceRef); label == null || label == site }
        }
    }
    val bySection = filteredEpisodes.groupBy { it.section }
    val resume = data.resumeEpisode

    // Índice (aplanado) del episodio en el que voy, para hacer scroll automático al abrir.
    // Los 2 primeros items del LazyColumn son la imagen y el bloque de título; después, SI hay
    // 2+ sitios, viene 1 item más con la fila de chips de filtro (ver más abajo); y después cada
    // sección con nombre añade 1 item de cabecera antes de sus episodios.
    val listState = rememberLazyListState()
    val currentEpisodeId = data.inProgressEpisode?.id
    val resumeIndex = remember(filteredEpisodes, data.progress, siteLabels) {
        val target = data.inProgressEpisode ?: return@remember null
        var idx = if (siteLabels.size >= 2) 3 else 2
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp + bottomInset),
    ) {
        item {
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
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(data.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${data.episodes.size} videos",
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
                        Text("  Reproducir", fontWeight = FontWeight.Bold)
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
                    showDownload = !data.isTorrent,
                    // Fallback de miniatura: los capítulos web nunca traen un still propio
                    // (addWebSeriesEpisode guarda thumbPath = null a propósito, el pack solo da un
                    // póster de la serie), y sin esto la fila quedaba con un recuadro vacío.
                    fallbackThumb = data.thumbnailUrl,
                    isInNuc = remember(ep.id, nucDownloaded) { nucDownloaded.hasCopyOf(ep) },
                    isPendingNuc = ep.id in pendingNucIds,
                    // Doble filtro a propósito: el ítem tiene que admitir NUC (lambda != null) Y
                    // esta fila puntual tiene que ser mandable (fuente + numeración parseables).
                    // Una serie web puede tener capítulos sueltos sin sourceRef guardados de antes.
                    onDownloadToNuc = onDownloadToNuc
                        ?.takeIf { ep.nucDownloadItemOrNull() != null }
                        ?.let { send -> { send(ep) } },
                    onPlay = { onPlayEpisode(ep.id) },
                    onDownload = { onDownloadEpisode(ep) },
                    onToggleWatched = onToggleWatched,
                )
            }
        }
    }
}

/**
 * ¿Este episodio puntual, con la fuente de la que se guardó, ya está bajado en la NUC?
 *
 * Exige las 3 partes (temporada, capítulo y fuente): si falta cualquiera devuelve false. Es a
 * propósito — un episodio sin numeración parseable, o guardado antes de que se persistiera la
 * fuente, no se puede cruzar con la NUC sin adivinar, y un tilde de más (decir "ya lo tenés"
 * cuando no) es peor que uno de menos. Los que no tienen fuente los rellena el primer refresh
 * contra la NUC, que trae el `source_ref` real.
 */
/**
 * Host corto ("serieskao.top") a partir del `sourceRef` (pageUrl) de un episodio web, para
 * agrupar el filtro de la lista por sitio de origen. Null para archive.org, magnets de torrent
 * (no son una URL http válida), o episodios guardados antes de que se persistiera `sourceRef` —
 * ninguno de esos tiene un "sitio" que mostrar como chip. Mismo patrón que
 * `CfClearanceStore.hostOf` / `CloudflareSolver` (`java.net.URL(...).host`), para no inventar una
 * segunda forma de sacarle el host a una URL en el código.
 */
private fun siteLabelOf(sourceRef: String?): String? {
    if (sourceRef.isNullOrBlank()) return null
    val host = runCatching { java.net.URL(sourceRef).host }.getOrNull()?.lowercase()?.ifBlank { null }
        ?: return null
    return host.removePrefix("www.")
}

/**
 * Elegir qué capítulos de la serie mandar a bajar a la NUC (todos, algunos, o uno).
 *
 * Gemelo del selector de [com.arkiv.player.ui.catalog.WebPackDialog] pero sobre los [Episode] ya
 * guardados de esta pantalla en vez de un `MirrorWebPack` (otra forma de datos, misma interacción):
 * cabecera de "seleccionar todo" tri-estado, cabecera por temporada con su propio marcar/desmarcar
 * (una serie larga tiene cientos de filas y marcarlas de a una es inviable) y el mismo tilde verde
 * informativo para lo que ya está bajado.
 *
 * [episodes] llega ya filtrado a lo elegible, así que acá no se vuelve a decidir quién puede o no.
 */
@Composable
private fun NucDownloadDialog(
    episodes: List<Episode>,
    alreadyInNuc: Set<Triple<Int, Int, String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<Episode>) -> Unit,
) {
    // La selección por defecto es TODO lo que falta, no todo a secas: el caso normal de abrir esto
    // en una serie que ya se bajó a medias es "traeme el resto", y volver a mandar lo que ya está
    // sería trabajo al pedo para la NUC. Si no falta nada se preseleccionan todos igual, para que
    // el diálogo no abra vacío y sin nada que confirmar (re-bajar es un caso válido, p.ej. si la
    // copia salió mal).
    val selected = remember(episodes, alreadyInNuc) {
        val pending = episodes.filterNot { alreadyInNuc.hasCopyOf(it) }
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
        title = { Text("Descargar a la NUC") },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(episodes.filter { it.id in selected }) },
            ) {
                Text("Descargar (${selected.size})")
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
                                if (alreadyInNuc.hasCopyOf(ep)) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Ya descargado en la NUC",
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

/**
 * El episodio traducido a lo que arkiv-offline entiende, o `null` si no se puede mandar a bajar.
 *
 * Devuelve null (y por eso la fila no ofrece el botón) en tres casos, todos legítimos:
 *  - `sourceRef == null`: archive.org nunca lo guarda, y la NUC baja la pageUrl -- sin ella no hay
 *    nada que pedirle. También cubre las filas viejas guardadas antes de que se persistiera.
 *  - temporada o capítulo no parseables: `NucDownloadItem` los exige y adivinar un número
 *    equivocado hace que la NUC baje otro capítulo. No debería pasar en un capítulo web real (se
 *    guardan como "Temporada N" / "T1 · E7"), pero un ítem raro no puede romper el flujo.
 *
 * NO filtra torrent: un magnet tampoco es una pageUrl válida, pero eso se decide arriba con
 * `ItemDetail.isTorrent`, que es explícito y no depende de cómo se vea el string. Usa los mismos
 * [EpisodeNumbering] que el resto de la pantalla, para no tener una tercera forma de sacar el
 * número de temporada/capítulo.
 */
private fun Episode.nucDownloadItemOrNull(): NucDownloadItem? {
    val season = EpisodeNumbering.seasonOf(section) ?: return null
    val number = EpisodeNumbering.episodeOf(displayName) ?: return null
    val pageUrl = sourceRef ?: return null
    return NucDownloadItem(season, number, pageUrl)
}

private fun Set<Triple<Int, Int, String>>.hasCopyOf(episode: Episode): Boolean {
    val season = EpisodeNumbering.seasonOf(episode.section) ?: return false
    val number = EpisodeNumbering.episodeOf(episode.displayName) ?: return false
    val source = episode.sourceRef ?: return false
    return Triple(season, number, source) in this
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: com.arkiv.player.data.db.PlaybackEntity?,
    isCurrent: Boolean,
    showDownload: Boolean,
    /** Miniatura de la serie, para las filas cuyo episodio no trae una propia. */
    fallbackThumb: String?,
    /** Ya descargado en la NUC (esta fila puntual, con su fuente). */
    isInNuc: Boolean,
    /** Pedido de descarga a la NUC en curso para esta fila: muestra spinner, no reacciona al toque. */
    isPendingNuc: Boolean,
    /** Manda ESTE episodio a bajar a la NUC, o null si no es elegible. Ver [DetailContent]. */
    onDownloadToNuc: (() -> Unit)?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
) {
    val watched = progress?.watched == true
    val cardShape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(cardShape)
            // Vistos: fondo gris sutil ("ya lo vi"). El que voy: borde blanco ("acá voy").
            .background(if (watched) ArkivSurface else Color.Transparent)
            .then(
                if (isCurrent) Modifier.border(1.5.dp, Color.White, cardShape) else Modifier,
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ArkivSurfaceHigh),
        ) {
            val thumb = episode.thumbPath?.let { ArchiveUrls.download(episode.itemId, it) }
            AsyncImage(
                model = thumb ?: fallbackThumb,
                contentDescription = episode.displayName,
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
                episode.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (watched) ArkivTextSecondary else MaterialTheme.colorScheme.onBackground,
            )
            // Los capítulos guardados desde web/torrent nunca traen duración real (queda en 0.0 a
            // propósito al guardar, ver ArkivRepository.kt) -- mostrar "0:00" ahí parecía un error
            // en vez de un dato que simplemente no se conoce, así que la fila la omite.
            if (episode.durationSeconds > 0) {
                Text(
                    formatDuration((episode.durationSeconds * 1000).toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                )
            }
        }
        // Un solo slot para "la NUC", nunca los dos a la vez: o el tilde de que ya está bajado, o el
        // botón para mandarlo a bajar. Ofrecer descargar lo que ya está no aporta nada, y la fila
        // tampoco tiene ancho para un ícono más (miniatura de 112dp + 2 IconButton ya la dejan justa
        // en un teléfono angosto).
        //
        // El tilde es informativo, no una acción -- por eso no es un IconButton (no se toca, no ocupa
        // un slot de 48dp) y no comparte el rojo de "visto" que tiene al lado: solo avisa que ESTA
        // fila, bajada de ESTE sitio, ya está en la NUC. Mismo ícono, color y tamaño que en
        // WebPackDialog: es el mismo indicador y tiene que reconocerse igual en las dos pantallas.
        //
        // El botón usa CloudDownload y NO el Download de al lado a propósito: ese otro baja al
        // teléfono (DownloadManager) y este manda a bajar a la NUC. Son destinos distintos y con el
        // mismo ícono serían indistinguibles.
        if (isInNuc) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Ya descargado en la NUC",
                tint = NucDownloadedGreen,
                modifier = Modifier.size(18.dp),
            )
        } else if (isPendingNuc) {
            // Mismo slot de 48dp que el IconButton de abajo, para que la fila no salte de tamaño
            // al pasar de ícono a spinner y viceversa.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ArkivTextSecondary)
            }
        } else if (onDownloadToNuc != null) {
            IconButton(onClick = onDownloadToNuc) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "Descargar a la NUC",
                    tint = ArkivTextSecondary,
                )
            }
        }
        IconButton(onClick = { onToggleWatched(episode.id, !watched) }) {
            Icon(
                if (watched) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (watched) "Marcar no visto" else "Marcar visto",
                tint = if (watched) ArkivRed else ArkivTextSecondary,
            )
        }
        if (showDownload) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Descargar", tint = ArkivTextSecondary)
            }
        }
    }
}
