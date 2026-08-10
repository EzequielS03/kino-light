package com.arkiv.player.ui.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.biblioteca.EspacioEnDisco
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.ui.downloads.DownloadsViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Las descargas al dispositivo, vistas desde el TV.
 *
 * Hasta ahora el TV no tenía NINGUNA pantalla de descargas, aunque "Guardar toda la temporada" del
 * buscador encola N descargas al disco del aparato. En un Fire TV Stick eso se llena sin avisar.
 *
 * Una fila por SERIE, no por capítulo: `DownloadsViewModel` ya expone las acciones de grupo y el
 * caso real es una temporada entera encolada de una. Una lista por capítulo sería una pantalla
 * anidada más para navegar con el D-pad, sin ganar nada.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDownloadsSection(modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(graph.localDownloads, graph.repository) }
        },
    )
    val grupos by vm.groups.collectAsStateWithLifecycle()
    var acciones by remember { mutableStateOf<DownloadGroup?>(null) }

    val ocupado = EspacioEnDisco.ocupadoPorDescargas(grupos)
    // Se remide cada vez que cambia lo ocupado (una descarga terminó, se borró algo). `StatFs` toca
    // el filesystem, así que va fuera del hilo principal.
    val libres by produceState(initialValue = 0L, ocupado) {
        value = withContext(Dispatchers.IO) { graph.localDownloads.espacioLibreBytes() }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("Descargas", style = MaterialTheme.typography.headlineSmall, color = ArkivTextPrimary)
        Text(
            EspacioEnDisco.resumen(libres, ocupado),
            style = MaterialTheme.typography.labelLarge,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        if (grupos.isEmpty()) {
            Text(
                "No hay nada descargado en este aparato.\nGuardá una serie desde el buscador y va a aparecer acá.",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(grupos, key = { it.itemId }) { grupo ->
                Card(
                    onClick = { acciones = grupo },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            grupo.itemTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            DownloadGroupPolicy.summarize(grupo.episodes),
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    acciones?.let { grupo ->
        TvDownloadActionsDialog(
            grupo = grupo,
            onCancelar = { vm.cancelGroup(grupo); acciones = null },
            onReintentar = { vm.retryFailedGroup(grupo); acciones = null },
            onQuitar = { vm.removeGroup(grupo); acciones = null },
            onDismiss = { acciones = null },
        )
    }
}

/**
 * Acciones de un grupo de descargas, en un diálogo y no como botones dentro de la fila: con el
 * D-pad, varios botones por fila multiplican los saltos de foco y hacen fácil apretar el equivocado
 * —y acá el equivocado borra gigabytes—.
 *
 * Solo se ofrece lo que el estado del grupo permite: "Cancelar" si hay algo activo, "Reintentar" si
 * hay algo fallido. "Quitar del dispositivo" está siempre: es lo que libera disco.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDownloadActionsDialog(
    grupo: DownloadGroup,
    onCancelar: () -> Unit,
    onReintentar: () -> Unit,
    onQuitar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hayActivas = DownloadGroupPolicy.activeEpisodeIds(grupo).isNotEmpty()
    val hayFallidas = DownloadGroupPolicy.failedEpisodeIds(grupo).isNotEmpty()
    var confirmarQuitar by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                grupo.itemTitle,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DownloadGroupPolicy.summarize(grupo.episodes),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            if (confirmarQuitar) {
                Text(
                    "Se borran del disco los archivos ya bajados de esta serie. La serie sigue en tu biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onQuitar, modifier = Modifier.fillMaxWidth()) {
                    Text("Sí, borrar del dispositivo", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = { confirmarQuitar = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar", maxLines = 1)
                }
            } else {
                if (hayActivas) {
                    Button(onClick = onCancelar, modifier = Modifier.fillMaxWidth()) {
                        Text("Detener lo que está bajando", maxLines = 1)
                    }
                }
                if (hayFallidas) {
                    Button(onClick = onReintentar, modifier = Modifier.fillMaxWidth()) {
                        Text("Reintentar lo que falló", maxLines = 1)
                    }
                }
                Button(onClick = { confirmarQuitar = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Quitar del dispositivo", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Volver", maxLines = 1)
                }
            }
        }
    }
}
