package com.arkiv.player.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.data.offline.NucJob
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Progreso en vivo de los jobs de descarga a la NUC (arkiv-offline) disparados desde este
 * dispositivo (Task 9). No confundir con la pantalla "Descargas" de la pestaña inferior: esa
 * lista episodios ya bajados AL TELÉFONO (DownloadManager); esta lista trabajos EN CURSO en la
 * NUC de casa -- de ahí el nombre `NucDownloadsScreen` para no chocar con `DownloadsScreen` ya
 * existente. Se llega acá desde el ícono "Descargas en la NUC" del home (ver ArkivRoot.kt).
 *
 * Mismo molde que [com.arkiv.player.ui.torrent.TorrentScreen]/[com.arkiv.player.ui.remote.RemoteScreen]:
 * Column + Row con flecha de "atrás", sin Scaffold propio -- ya viene envuelta en el Scaffold de
 * ArkivRoot, y anidar otro con su propio TopAppBar duplicaría el padding de status bar.
 */
@Composable
fun NucDownloadsScreen(onBack: () -> Unit) {
    val graph = rememberGraph()
    val vm: NucDownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NucDownloadsViewModel(
                    graph.arkivOfflineApi,
                    graph.nucJobEvents,
                    graph.database.localActiveJobDao(),
                    graph.database.nucLibraryItemDao(),
                )
            }
        },
    )
    val active by vm.activeJobs.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Text(
                "Descargas en la NUC",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (active.isEmpty() && finished.isEmpty()) {
            EmptyState(
                title = "Sin descargas activas",
                subtitle = "Los trabajos que dispares desde una serie o película van a aparecer acá con su progreso.",
            )
            return@Column
        }

        // Una sola LazyColumn para toda la pantalla: dos LazyColumn anidadas dentro de este
        // Column (sin peso/altura fija) hacen que Compose mida la segunda con altura infinita
        // y tire IllegalStateException en tiempo de ejecución. Activos y terminados van acá
        // como secciones (item/items) en vez de listas scrolleables separadas.
        LazyColumn(Modifier.fillMaxSize()) {
            if (active.isNotEmpty()) {
                items(active, key = { "active-${it.jobId}" }) { job ->
                    NucJobRow(job = job, onCancel = { vm.cancel(job.jobId) })
                }
            }

            if (finished.isNotEmpty()) {
                item(key = "finished-header") {
                    Text(
                        "Terminados",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                finished.groupBy { it.seriesId }.forEach { (seriesId, items) ->
                    item(key = "series-header-$seriesId") {
                        Text(
                            seriesId,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(16.dp, 8.dp),
                        )
                    }
                    items(items, key = { "finished-${it.itemId}" }) { item ->
                        ListItem(
                            headlineContent = { Text("T${item.season} · E${item.episode}") },
                            supportingContent = {
                                Text(
                                    "${item.sizeBytes / 1_000_000} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = ArkivSurface),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NucJobRow(job: NucJob, onCancel: () -> Unit) {
    ListItem(
        headlineContent = {
            Text("Job #${job.jobId} — ${estadoLegible(job.status)}")
        },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${job.items.size} episodio(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                job.progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p },
                        color = ArkivRed,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = ArkivTextSecondary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = ArkivSurface),
    )
}

private fun estadoLegible(status: String): String = when (status) {
    "queued" -> "en cola"
    "downloading" -> "descargando"
    "done" -> "listo"
    "failed" -> "falló"
    else -> status
}
