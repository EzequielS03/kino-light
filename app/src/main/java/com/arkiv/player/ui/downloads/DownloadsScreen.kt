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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.local.FileSizeFormat
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.anchoDeLectura
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
        factory = viewModelFactory { initializer { DownloadsViewModel(graph.localDownloads, graph.repository) } },
    )
    val groups by vm.groups.collectAsStateWithLifecycle()

    // Aviso de una sola vez del ViewModel. Es la ÚNICA salida que tiene esta pantalla cuando la cola
    // saltea una descarga por duplicado: en ese caso no se crea ninguna fila, así que el capítulo
    // sigue apareciendo como "no descargado" y el tap no dejaría ningún rastro visible.
    // Va antes del `return` de la lista vacía para que valga en los dos caminos.
    val context = androidx.compose.ui.platform.LocalContext.current
    val message by vm.message.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        vm.messageShown()
    }

    if (groups.isEmpty()) {
        EmptyState(
            title = "Descargas",
            subtitle = "Todavía no has descargado ningún episodio. Usa el ícono de descarga en un episodio.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .anchoDeLectura()
                .fillMaxSize(),
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
            items(groups, key = { it.itemId }) { group ->
                DownloadGroupSection(
                    group = group,
                    onPlay = onPlayEpisode,
                    onConfirm = vm::confirm,
                    onRetry = vm::retry,
                    onCancel = vm::cancel,
                    onRemove = vm::remove,
                    onDownload = { episodeId -> vm.download(episodeId, group.source) },
                    onCancelAll = { vm.cancelGroup(group) },
                    onRemoveAll = { vm.removeGroup(group) },
                    onRetryFailed = { vm.retryFailedGroup(group) },
                )
            }
        }
    }
}

/**
 * Una fila del listado de Descargas. Un ítem de un solo episodio (película) no tiene nada que
 * plegar y se muestra plana, como antes. Uno con varios muestra la cabecera con carátula + resumen
 * y, al desplegar, TODOS los capítulos del ítem -- no solo los que pasaron por la cola: los que
 * todavía no se descargaron llevan su propio botón de bajar.
 */
@Composable
private fun DownloadGroupSection(
    group: DownloadGroup,
    onPlay: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelAll: () -> Unit,
    onRemoveAll: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    if (group.isSingleEpisode) {
        // Ver DownloadGroupPolicy.buildGroups: un itemId solo entra a `groups` si tiene al menos una
        // fila en `downloads`, así que el único episodio de una película siempre está trackeado.
        val row = (group.episodes.firstOrNull()?.status as? EpisodeDownloadStatus.Tracked)?.row ?: return
        DownloadItem(
            row = row,
            onPlay = { if (row.state == LocalDownloadState.COMPLETED) onPlay(row.episodeId) },
            onConfirm = { onConfirm(row.episodeId) },
            onRetry = { onRetry(row.episodeId) },
            onCancel = { onCancel(row.episodeId) },
            onRemove = { onRemove(row.episodeId) },
        )
        return
    }

    // `rememberSaveable` (no `remember`): el `LazyColumn` con `key = { it.itemId }` desarma la
    // composición de los grupos que salen de la ventana visible, y sin esto un grupo perdía su
    // "desplegado" cada vez que se scrolleaba fuera de vista y volvía.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        DownloadGroupHeader(
            group = group,
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            onCancelAll = onCancelAll,
            onRemoveAll = onRemoveAll,
            onRetryFailed = onRetryFailed,
        )
        if (expanded) {
            group.episodes.forEach { grouped ->
                Box(Modifier.padding(start = 20.dp)) {
                    when (val status = grouped.status) {
                        is EpisodeDownloadStatus.Tracked -> DownloadItem(
                            row = status.row,
                            onPlay = { if (status.row.state == LocalDownloadState.COMPLETED) onPlay(status.row.episodeId) },
                            onConfirm = { onConfirm(status.row.episodeId) },
                            onRetry = { onRetry(status.row.episodeId) },
                            onCancel = { onCancel(status.row.episodeId) },
                            onRemove = { onRemove(status.row.episodeId) },
                        )
                        EpisodeDownloadStatus.NotDownloaded -> NotDownloadedRow(
                            episode = grouped.episode,
                            itemId = group.itemId,
                            onDownload = { onDownload(grouped.episode.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Cabecera plegable de un grupo: carátula del ítem, título, resumen y acciones a nivel de serie. */
@Composable
private fun DownloadGroupHeader(
    group: DownloadGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCancelAll: () -> Unit,
    onRemoveAll: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ArkivSurfaceHigh),
            ) {
                AsyncImage(
                    model = group.itemThumbnailUrl,
                    contentDescription = group.itemTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.itemTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(group.source)
                }
                Text(
                    DownloadGroupPolicy.summarize(group.episodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Contraer" else "Expandir",
                tint = ArkivTextSecondary,
            )
        }
        val activeIds = DownloadGroupPolicy.activeEpisodeIds(group)
        val failedIds = DownloadGroupPolicy.failedEpisodeIds(group)
        val trackedIds = DownloadGroupPolicy.trackedEpisodeIds(group)
        if (activeIds.isNotEmpty() || failedIds.isNotEmpty() || trackedIds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (failedIds.isNotEmpty()) TextButton(onClick = onRetryFailed) { Text("Reintentar fallidos") }
                if (activeIds.isNotEmpty()) TextButton(onClick = onCancelAll) { Text("Cancelar todos") }
                if (trackedIds.isNotEmpty()) TextButton(onClick = onRemoveAll) { Text("Quitar todos") }
            }
        }
    }
}

/** Fila de un capítulo que todavía no se bajó: sale del catálogo completo del ítem, no de `downloads`. */
@Composable
private fun NotDownloadedRow(
    episode: Episode,
    itemId: String,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ArkivSurfaceHigh),
        ) {
            // El thumb de archive.org (itemId/thumbPath) se borró en la poda de esta rama.
            val thumb: String? = null
            AsyncImage(
                model = thumb,
                contentDescription = episode.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                episode.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "No descargado",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = "Descargar", tint = ArkivTextSecondary)
        }
    }
}

@Composable
private fun DownloadItem(
    row: DownloadRow,
    onPlay: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
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
                // El thumb de archive.org (itemId/thumbPath) se borró en la poda de esta rama.
                val thumb: String? = null
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
                // STAGING renders with an INDETERMINATE bar, not `row.progress`. Nothing creates a
                // STAGING row anymore -- the NUC/arkiv-offline backend that used to own this phase
                // was removed with the rest of NUC downloads (Task 8), and today's only strategy
                // (Magis) writes DOWNLOADING directly (see `LocalDownloadWorker`). This branch is
                // only reachable for a row already sitting in the local `downloads` table from an
                // install that predates that removal. It's kept indeterminate rather than switched
                // to `row.progress` because that's what made the old NUC math (`items done / items
                // total`, one item per job) honest: with a single item the count could only ever
                // read 0/1 or 1/1, so a whole multi-minute HLS download looked stuck at 0%.
                when (row.state) {
                    LocalDownloadState.STAGING -> LinearProgressIndicator(
                        color = ArkivRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                    )
                    LocalDownloadState.DOWNLOADING -> LinearProgressIndicator(
                        progress = { row.progress },
                        color = ArkivRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                    )
                    else -> Unit
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
                // Lo que está en vuelo o en cola se puede CANCELAR (para la descarga y conserva el
                // parcial, así "Reintentar" reanuda) o QUITAR (para y borra todo).
                LocalDownloadState.QUEUED, LocalDownloadState.STAGING, LocalDownloadState.DOWNLOADING -> {
                    TextButton(onClick = onCancel) { Text("Cancelar") }
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
    // Sin porcentaje: el backend solo puede reportar 0% o 100% para esta fase (ver el comentario
    // de la barra indeterminada más arriba), así que un número acá mentiría.
    LocalDownloadState.STAGING -> "Preparando en el servidor…"
    // El error con la fila todavía en `downloading` es un fallo transitorio que WorkManager va a
    // reintentar solo (ver DownloadRetryPolicy): decirlo evita que parezca colgada.
    LocalDownloadState.DOWNLOADING ->
        row.error?.let { "Reintentando · $it" } ?: "Bajando ${(row.progress * 100).toInt()}%"
    LocalDownloadState.NEEDS_CONFIRMATION -> "Necesita confirmación · ${FileSizeFormat.formatSize(row.bytes)}"
    // El "error" de una fila completada no es un fallo: es el motivo por el que no hubo que bajar
    // nada (ver DuplicateDownloadPolicy.ADOPTED_REASON, "Ya estaba descargado"). Decirlo evita que
    // parezca que se bajaron 461 MB que en realidad ya estaban en disco bajo otro ítem.
    LocalDownloadState.COMPLETED -> row.error?.let { "Listo · $it" } ?: "Listo"
    LocalDownloadState.FAILED -> row.error ?: "Falló"
    else -> row.state
}

/**
 * File-origin badge. "torrent" and "web" are legacy `source` values from rows saved before this
 * branch's pruning. "magis" is today's real value (`FuenteDeDescarga.para`); any other value falls
 * through to "ARCHIVE".
 */
private fun sourceBadge(source: String): String = when (source) {
    "torrent" -> "TORRENT"
    "web" -> "WEB"
    "magis" -> "MAGIS"
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
