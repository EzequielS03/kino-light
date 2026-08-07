package com.arkiv.player.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.local.TorrentSizeGate
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory { initializer { DownloadsViewModel(graph.localDownloads) } },
    )
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    if (downloads.isEmpty()) {
        EmptyState(
            title = "Descargas",
            subtitle = "Todavía no descargaste ningún episodio. Usá el ícono de descarga en un episodio.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item {
            Text(
                "Descargas",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(downloads, key = { it.episodeId }) { row ->
            DownloadItem(
                row = row,
                onPlay = { if (row.state == LocalDownloadState.COMPLETED) onPlayEpisode(row.episodeId) },
                onConfirm = { vm.confirm(row.episodeId) },
                onRetry = { vm.retry(row.episodeId) },
                onRemove = { vm.remove(row.episodeId) },
            )
        }
    }
}

@Composable
private fun DownloadItem(
    row: DownloadRow,
    onPlay: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                val thumb = row.thumbPath?.let { ArchiveUrls.download(row.itemId, it) }
                AsyncImage(
                    model = thumb,
                    contentDescription = row.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(row.source)
                }
                Text(
                    row.itemTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stateLabel(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.state == LocalDownloadState.FAILED || row.state == LocalDownloadState.NEEDS_CONFIRMATION) {
                        ArkivRed
                    } else {
                        ArkivTextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.state == LocalDownloadState.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { row.progress },
                        color = ArkivRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                    )
                }
            }
            if (row.state == LocalDownloadState.COMPLETED) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = ArkivRed)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (row.state) {
                LocalDownloadState.NEEDS_CONFIRMATION -> {
                    TextButton(onClick = onConfirm) { Text("Descargar igual") }
                    TextButton(onClick = onRemove) { Text("Descartar") }
                }
                LocalDownloadState.FAILED -> {
                    TextButton(onClick = onRetry) { Text("Reintentar") }
                    TextButton(onClick = onRemove) { Text("Quitar") }
                }
                else -> TextButton(onClick = onRemove) { Text("Quitar") }
            }
        }
    }
}

/** Texto que se le muestra al usuario para cada estado de la cola. */
private fun stateLabel(row: DownloadRow): String = when (row.state) {
    LocalDownloadState.QUEUED -> "En cola"
    LocalDownloadState.STAGING -> "Preparando en el servidor"
    LocalDownloadState.DOWNLOADING -> "Bajando ${(row.progress * 100).toInt()}%"
    LocalDownloadState.NEEDS_CONFIRMATION -> "Necesita confirmación · ${TorrentSizeGate.formatSize(row.bytes)}"
    LocalDownloadState.COMPLETED -> "Listo"
    LocalDownloadState.FAILED -> row.error ?: "Falló"
    else -> row.state
}

/** Origen del archivo, para distinguir de un vistazo torrent de web de archive. */
private fun sourceBadge(source: String): String = when (source) {
    "torrent" -> "TORRENT"
    "web" -> "WEB"
    else -> "ARCHIVE"
}

@Composable
private fun SourceBadge(source: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ArkivSurfaceHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            sourceBadge(source),
            style = MaterialTheme.typography.labelSmall,
            color = ArkivTextSecondary,
        )
    }
}
