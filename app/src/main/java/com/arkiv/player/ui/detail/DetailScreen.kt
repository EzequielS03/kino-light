package com.arkiv.player.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.arkiv.player.ui.formatDuration
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

    var menuExpanded by remember { mutableStateOf(false) }
    var showMarkersDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        containerColor = ArkivBlack,
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
            Text(
                formatDuration((episode.durationSeconds * 1000).toLong()),
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
        }
        // Informativo, no una acción -- por eso no es un IconButton (no se toca, no ocupa un slot
        // de 48dp) y no comparte el rojo de "visto" que tiene al lado: solo avisa que ESTA fila,
        // bajada de ESTE sitio, ya está en la NUC. Mismo ícono, color y tamaño que en WebPackDialog:
        // es el mismo indicador y tiene que reconocerse igual en las dos pantallas.
        if (isInNuc) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Ya descargado en la NUC",
                tint = NucDownloadedGreen,
                modifier = Modifier.size(18.dp),
            )
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
