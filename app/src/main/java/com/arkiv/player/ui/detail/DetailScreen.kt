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
import com.arkiv.player.data.local.DownloadQueuePolicy
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.formatDuration
import com.arkiv.player.ui.EtiquetaDeCapitulo
import com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice
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
    // Sinopsis de cada capítulo (TMDB). Mismo caché que títulos/stills, y misma regla de vacío
    // si no se sabe a qué serie pertenece el ítem.
    val tmdbOverviews by graph.repository.observeEpisodeOverviews(identifier)
        .collectAsStateWithLifecycle(emptyMap())
    LaunchedEffect(identifier) {
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
    val savingEpisodeIds = remember(downloadRows) {
        downloadRows.filterNot { DownloadQueuePolicy.isTerminal(it.state) }.map { it.episodeId }.toSet()
    }

    // Guarda capítulos en el DISPOSITIVO. El permiso de notificaciones se pide UNA vez por acción
    // del usuario (no una por capítulo): un lote de 30 dispararía 30 `launcher.launch` seguidos
    // sobre el mismo ActivityResultLauncher antes de que el usuario conteste el primer diálogo.
    fun saveEpisodesLocally(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        askNotifications()
        scope.launch {
            // Un solo aviso para todo el lote, no uno por capítulo.
            notifyDuplicates(episodes.map { graph.localDownloads.enqueue(it.id, localSourceFor(it.id)) })
        }
    }

    val onDownloadEpisode: (Episode) -> Unit = { ep -> saveEpisodesLocally(listOf(ep)) }

    var menuExpanded by remember { mutableStateOf(false) }
    var showMarkersDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // Todos los capítulos se pueden guardar en el dispositivo: hay una estrategia por cada fuente
    // (archive, torrent y web), así que ya no hay filtro de elegibilidad. Antes esta lista era la de
    // los capítulos mandables a la NUC y exigía sourceRef + temporada/capítulo parseables, porque
    // arkiv-offline solo entiende (seriesId, season, episode).
    val savableEpisodes = detail?.episodes.orEmpty()

    // El botón de esta pantalla dejó de mandar a la NUC y ahora guarda EN EL DISPOSITIVO, igual que
    // el resto de la app. Antes disparaba un job a blog cuyo progreso no se veía en ninguna pantalla
    // (la Task 20 quitó la ruta a NucDownloadsScreen) y cuyo resultado no se podía reproducir (la
    // misma tarea desconectó la reproducción remota). La maquinaria de la NUC no se borra: queda
    // desconectada, como el resto.
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
            savedEpisodeIds = savedEpisodeIds,
            savingEpisodeIds = savingEpisodeIds,
            onPlayEpisode = onPlayEpisode,
            onDownloadEpisode = onDownloadEpisode,
            onToggleWatched = vm::toggleWatched,
            tmdbTitles = tmdbTitles,
            tmdbStills = tmdbStills,
            tmdbOverviews = tmdbOverviews,
            // Solo el inferior: el superior ya lo cubre el TopAppBar (agregarlo acá lo duplicaría).
            bottomInset = padding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun DetailContent(
    data: ItemDetail,
    /** Episodios ya guardados en el dispositivo (fila `completed`). Ver [DetailScreen]. */
    savedEpisodeIds: Set<String>,
    /** Episodios en cola o bajando -- la fila muestra spinner. Ver [DetailScreen]. */
    savingEpisodeIds: Set<String>,
    onPlayEpisode: (String) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onToggleWatched: (String, Boolean) -> Unit,
    /** episodeId -> título / imagen / sinopsis del capítulo según TMDB. Vacíos si no se sabe la serie. Ver [DetailScreen]. */
    tmdbTitles: Map<String, String>,
    tmdbStills: Map<String, String>,
    tmdbOverviews: Map<String, String>,
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

                    // Título e imagen reales del capítulo (TMDB). Solo aparecen si se pudo saber a
                    // qué serie y a qué número corresponde la fila; si no, la fila cae al nombre
                    // del archivo y al fotograma que genera archive.org, como antes.
                    tmdbTitle = tmdbTitles[ep.id],
                    tmdbStill = tmdbStills[ep.id],
                    tmdbOverview = tmdbOverviews[ep.id],
                    // Fallback de miniatura: los capítulos web nunca traen un still propio
                    // (addWebSeriesEpisode guarda thumbPath = null a propósito, el pack solo da un
                    // póster de la serie), y sin esto la fila quedaba con un recuadro vacío.
                    fallbackThumb = data.thumbnailUrl,
                    isSaved = ep.id in savedEpisodeIds,
                    isSaving = ep.id in savingEpisodeIds,
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
 * Elegir qué capítulos de la serie guardar en el dispositivo (todos, algunos, o uno).
 *
 * Gemelo del selector de [com.arkiv.player.ui.catalog.WebPackDialog] pero sobre los [Episode] ya
 * guardados de esta pantalla en vez de un `MirrorWebPack` (otra forma de datos, misma interacción):
 * cabecera de "seleccionar todo" tri-estado, cabecera por temporada con su propio marcar/desmarcar
 * (una serie larga tiene cientos de filas y marcarlas de a una es inviable) y el mismo tilde verde
 * informativo para lo que ya está guardado.
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

/**
 * La `source` de la tabla `downloads` que le corresponde a un episodio, o sea qué estrategia lo sabe
 * bajar. Se deriva del prefijo del id con el MISMO [PlayerSource.kindFor] que usa el reproductor,
 * para no inventar una segunda forma de decidir de dónde vino un episodio.
 *
 * Antes esta pantalla encolaba todo como "archive" fijo, así que un capítulo web o de torrent
 * guardado desde acá caía en la estrategia equivocada y fallaba con "no tiene un archivo
 * descargable".
 */
private fun localSourceFor(episodeId: String): String = when (PlayerSource.kindFor(episodeId)) {
    SourceKind.TORRENT -> "torrent"
    SourceKind.WEB -> "web"
    else -> "archive"
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
    /** Sinopsis del capítulo (TMDB). Null si no se pudo resolver; la fila simplemente no la muestra. */
    tmdbOverview: String?,
    /** Ya guardado en el dispositivo. */
    isSaved: Boolean,
    /** En cola o bajando: muestra spinner en vez del botón. */
    isSaving: Boolean,
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
            // El still de TMDB primero: es la foto del capítulo, mientras que el de archive.org es
            // un fotograma cualquiera del video (suele salir negro o a mitad de una transición).
            val thumb = tmdbStill
                ?: episode.thumbPath?.let { ArchiveUrls.download(episode.itemId, it) }
            AsyncImage(
                model = thumb ?: fallbackThumb,
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
                tmdbTitle ?: episode.displayName,
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
        // ya está guardado, el spinner de que está en cola/bajando, o el botón para guardarlo.
        // Ofrecer descargar lo que ya está no aporta nada, y la fila tampoco tiene ancho para un
        // ícono más (miniatura de 112dp + 2 IconButton ya la dejan justa en un teléfono angosto).
        //
        // El tilde es informativo, no una acción -- por eso no es un IconButton (no se toca, no ocupa
        // un slot de 48dp) y no comparte el rojo de "visto" que tiene al lado. Mismo ícono, color y
        // tamaño que en WebPackDialog: es el mismo indicador y tiene que reconocerse igual.
        //
        // Antes había DOS slots: este (Download, al teléfono) y otro con CloudDownload que mandaba a
        // bajar a la NUC. El de la NUC se quitó porque producía algo que ya nadie puede ver ni
        // reproducir desde que se desconectó la reproducción remota.
        if (isSaved) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Guardado en el dispositivo",
                tint = NucDownloadedGreen,
                modifier = Modifier.size(18.dp),
            )
        } else if (isSaving) {
            // Mismo slot de 48dp que el IconButton, para que la fila no salte de tamaño al pasar de
            // ícono a spinner y viceversa.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ArkivTextSecondary)
            }
        } else {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Guardar en el dispositivo",
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
    }
}
