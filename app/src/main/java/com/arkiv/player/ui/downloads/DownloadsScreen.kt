package com.arkiv.player.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.formatBytes
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
        factory = viewModelFactory { initializer { DownloadsViewModel(graph.repository, graph.downloader) } },
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
                onPlay = { if (row.state == "completed") onPlayEpisode(row.episodeId) },
                onDelete = { vm.remove(row.episodeId) },
            )
        }
    }
}

@Composable
private fun DownloadItem(
    row: DownloadRow,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(
                row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.itemTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (row.state) {
                "completed" -> Text(
                    "Descargado · ${formatBytes(row.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                )
                "failed" -> Text(
                    "Falló la descarga",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivRed,
                )
                else -> LinearProgressIndicator(
                    progress = { row.progress },
                    color = ArkivRed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(top = 6.dp),
                )
            }
        }
        if (row.state == "completed") {
            Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = ArkivRed)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = ArkivTextSecondary)
        }
    }
}
