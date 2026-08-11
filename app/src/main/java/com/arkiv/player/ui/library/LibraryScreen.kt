package com.arkiv.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.miniaturas.EleccionDeMiniatura
import com.arkiv.player.ui.components.ContinueCard
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.components.PosterCard
import com.arkiv.player.ui.components.SectionHeader
import com.arkiv.player.ui.home.HomeViewModel
import com.arkiv.player.ui.libraryMeta
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class LibFilter(val label: String) { ALL("Todas"), MOVIES("Películas"), SERIES("Series") }

private val SeriesBadgeColor = Color(0xE6444444)
private val TorrentBadgeColor = Color(0xE60288A7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenConnect: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi, graph.settings) } },
    )
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Avisa "eso ya lo tenés bajado" cuando la cola saltea una descarga duplicada (ver
    // DuplicateDownloadPolicy): si no, el menú parecería no hacer nada.
    val notifyDuplicates = com.arkiv.player.ui.offline.rememberDuplicateDownloadNotice()

    // Ítems con (al menos) un episodio ya guardado en el dispositivo, para el tilde en la tarjeta.
    val savedIds by graph.localDownloads.observeRows()
        .map { rows -> rows.filter { it.state == LocalDownloadState.COMPLETED }.map { it.itemId }.toSet() }
        .collectAsStateWithLifecycle(initialValue = emptySet())

    // Ítem con el menú contextual (long-press) abierto.
    var menuRow by remember { mutableStateOf<LibraryRow?>(null) }
    // Ítem pendiente de confirmar borrado.
    var confirmDeleteRow by remember { mutableStateOf<LibraryRow?>(null) }

    // Al tocar un ítem: si es película, reproduce directo; si es serie, abre la lista.
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

    // Sincronización LAN automática al abrir el inicio (además del botón manual).
    LaunchedEffect(Unit) { runCatching { graph.syncManager.syncNow() } }

    if (library.isEmpty() && continueWatching.isEmpty()) {
        EmptyState(
            title = "Tu biblioteca está vacía",
            subtitle = "Tocá + para agregar un ítem de archive.org pegando su URL.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    var filter by remember { mutableStateOf(LibFilter.ALL) }
    val hasMovies = library.any { it.isMovie }
    val hasSeries = library.any { !it.isMovie }
    val filtered = when (filter) {
        LibFilter.ALL -> library
        LibFilter.MOVIES -> library.filter { it.isMovie }
        LibFilter.SERIES -> library.filter { !it.isMovie }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (continueWatching.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Continuar viendo")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueWatching, key = { it.episodeId }) { row ->
                        val progress = if (row.durationMs > 0) {
                            row.positionMs.toFloat() / row.durationMs
                        } else 0f
                        val thumb = EleccionDeMiniatura.elegir(
                            row.framePath,
                            row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) },
                            row.itemThumbnailUrl,
                        )
                        ContinueCard(
                            title = row.itemTitle,
                            subtitle = row.displayName,
                            imageUrl = thumb,
                            progress = progress,
                            modifier = Modifier.width(240.dp),
                            onClick = { onPlayEpisode(row.episodeId) },
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SectionHeader("Mi biblioteca", modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenConnect) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Conexión")
                }
            }
        }
        // Chips de filtro (solo si hay de ambos tipos, para no estorbar).
        if (hasMovies && hasSeries) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibFilter.values().forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArkivRed,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
        items(filtered, key = { it.identifier }) { row ->
            PosterCard(
                title = row.title,
                imageUrl = row.thumbnailUrl,
                badge = if (row.isTorrent) "TORRENT" else if (row.isMovie) "PELÍCULA" else "SERIE",
                badgeColor = if (row.isTorrent) TorrentBadgeColor else if (row.isMovie) ArkivRed else SeriesBadgeColor,
                meta = libraryMeta(row.isMovie, row.durationSeconds, row.episodeCount, row.isTorrent),
                saved = row.identifier in savedIds,
                // Mantener pulsado abre el menú (detalle/descargar + cambiar categoría).
                onLongClick = { menuRow = row },
                onClick = { open(row) },
            )
        }
    }

    menuRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { menuRow = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction("Ver detalle / descargar") { onOpenItem(row.identifier); menuRow = null }
                SheetAction("Guardar en el dispositivo") {
                    scope.launch {
                        // Una película es un ítem de un solo episodio; una serie se guarda desde su
                        // detalle, capítulo por capítulo (no tiene sentido encolar 200 capítulos
                        // desde un menú contextual sin decir cuáles).
                        val episodes = graph.repository.episodesOf(row.identifier)
                        val single = episodes.singleOrNull()
                        if (single != null) {
                            // `row.source` viene directo de `items.source` ("archive" | "torrent" |
                            // "web"): es el dato real, no una heurística a partir de `isTorrent`.
                            notifyDuplicates(listOf(graph.localDownloads.enqueue(single.id, row.source)))
                        } else {
                            onOpenItem(row.identifier)
                        }
                    }
                    menuRow = null
                }
                if (row.isMovie) {
                    SheetAction("Marcar como serie") {
                        scope.launch { graph.repository.setCategory(row.identifier, false) }
                        menuRow = null
                    }
                } else {
                    SheetAction("Marcar como película") {
                        scope.launch { graph.repository.setCategory(row.identifier, true) }
                        menuRow = null
                    }
                }
                if (row.categoryOverride != null) {
                    SheetAction("Volver a detección automática") {
                        scope.launch { graph.repository.setCategory(row.identifier, null) }
                        menuRow = null
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction("Quitar de mi biblioteca", color = ArkivRed) {
                    confirmDeleteRow = row
                    menuRow = null
                }
            }
        }
    }

    confirmDeleteRow?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDeleteRow = null },
            title = { Text("¿Quitar de mi biblioteca?") },
            text = { Text(row.title) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.repository.removeItem(row.identifier) }
                    confirmDeleteRow = null
                }) {
                    Text("Quitar", color = ArkivRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRow = null }) { Text("Cancelar") }
            },
        )
    }
}

/** Fila de acción dentro del bottom sheet contextual. */
@Composable
private fun SheetAction(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
