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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.arkiv.player.data.biblioteca.DiskSpace
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.ui.downloads.DownloadsViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.tv.arkivTvButtonBorder
import com.arkiv.player.ui.tv.arkivTvButtonColors
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
fun TvDownloadsSection(onPlayEpisode: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(graph.localDownloads, graph.repository) }
        },
    )
    val grupos by vm.groups.collectAsStateWithLifecycle()
    // Se guarda el itemId y no el `DownloadGroup` entero: si mientras el diálogo está abierto una
    // descarga termina o se encola otra, un grupo capturado en el momento del click quedaría con
    // `hayActivas`/`hayFallidas` y episodios viejos. Buscando de nuevo en `grupos` en cada
    // recomposición, el diálogo siempre lee el estado actual.
    var accionesId by remember { mutableStateOf<String?>(null) }
    val grupoAcciones = accionesId?.let { id -> grupos.firstOrNull { it.itemId == id } }

    val ocupado = DiskSpace.usedByDownloads(grupos)
    // Se remide cada vez que cambia lo ocupado (una descarga terminó, se borró algo). `StatFs` toca
    // el filesystem, así que va fuera del hilo principal.
    // Nullable a propósito: antes de la primera medición arrancaba en 0L y ese 0L se pintaba como
    // "0 MB libres" -- en un aparato casi lleno eso se lee como alarma falsa. Con null no se dibuja
    // nada hasta tener el dato real.
    val libres by produceState<Long?>(initialValue = null, ocupado) {
        value = withContext(Dispatchers.IO) { graph.localDownloads.espacioLibreBytes() }
    }

    // Mismo aire contra los bordes que el resto de la biblioteca (ver SAFE_H/SAFE_V): una sección
    // con distinto margen se nota apenas cambiás de sección con el control.
    Column(modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V)) {
        Text("Descargas", style = MaterialTheme.typography.headlineSmall, color = ArkivTextPrimary)
        libres?.let { bytesLibres ->
            Text(
                DiskSpace.summary(bytesLibres, ocupado),
                style = MaterialTheme.typography.labelLarge,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }

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
                    onClick = { accionesId = grupo.itemId },
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

    // Si el grupo desapareció de `grupos` (se quitó la última descarga mientras el diálogo estaba
    // abierto), `grupoAcciones` da null y el diálogo simplemente no se dibuja más -- no hace falta
    // un efecto aparte para "cerrarlo".
    grupoAcciones?.let { grupo ->
        TvDownloadActionsDialog(
            grupo = grupo,
            onCancelar = { vm.cancelGroup(grupo); accionesId = null },
            onReintentar = { vm.retryFailedGroup(grupo); accionesId = null },
            onQuitar = { vm.removeGroup(grupo); accionesId = null },
            onPlayEpisode = { episodeId -> onPlayEpisode(episodeId); accionesId = null },
            onDismiss = { accionesId = null },
        )
    }
}

/** Qué paso de confirmación está abierto dentro de [TvDownloadActionsDialog], si alguno. */
private enum class ConfirmacionDeDescarga { DETENER, QUITAR }

/**
 * Acciones de un grupo de descargas, en un diálogo y no como botones dentro de la fila: con el
 * D-pad, varios botones por fila multiplican los saltos de foco y hacen fácil apretar el equivocado
 * —y acá el equivocado borra gigabytes—.
 *
 * Solo se ofrece lo que el estado del grupo permite: "Detener"/"Reintentar" si hay algo
 * activo/fallido, "Reproducir" si hay al menos un episodio ya descargado. "Quitar del dispositivo"
 * está siempre: es lo que libera disco.
 *
 * Tanto "Detener lo que está bajando" como "Quitar del dispositivo" piden confirmación: el spec
 * original ya pedía confirmar para cancelar-y-borrar, y antes solo "Quitar" la tenía. Un enum
 * ([ConfirmacionDeDescarga]) y no dos booleanos separados, porque los dos pasos de confirmación
 * son mutuamente excluyentes -- con dos booleanos habría que cuidar a mano que nunca estuvieran
 * los dos en `true` a la vez.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDownloadActionsDialog(
    grupo: DownloadGroup,
    onCancelar: () -> Unit,
    onReintentar: () -> Unit,
    onQuitar: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hayActivas = DownloadGroupPolicy.activeEpisodeIds(grupo).isNotEmpty()
    val hayFallidas = DownloadGroupPolicy.failedEpisodeIds(grupo).isNotEmpty()
    val episodioAReproducir = DownloadGroupPolicy.firstPlayableEpisodeId(grupo)
    var confirmando by remember { mutableStateOf<ConfirmacionDeDescarga?>(null) }
    val focus = remember { FocusRequester() }
    // Mismo patrón de reintento que `TvLibraryItemDialog`: es el único diálogo del feature que
    // borraba gigabytes sin manejar el foco, así que el D-pad podía quedar sin dueño en el paso
    // de confirmación. La key en `confirmando` hace que el foco salte de nuevo cuando cambia
    // el paso (de la lista de acciones a cualquiera de las confirmaciones, o viceversa).
    LaunchedEffect(confirmando) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { focus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

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
            when (confirmando) {
                ConfirmacionDeDescarga.QUITAR -> {
                    Text(
                        "Se borran del disco los archivos ya bajados de esta serie. La serie sigue en tu biblioteca.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    Button(onClick = onQuitar, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sí, borrar del dispositivo", maxLines = 1)
                    }
                    // El foco va al botón seguro, no al destructivo: con el control remoto un doble
                    // OK (normal cuando la UI tarda un frame en componerse) puede llegar antes de
                    // que el usuario alcance a leer la advertencia, y acá lo que se borra son
                    // gigabytes.
                    Button(onClick = { confirmando = null }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Cancelar", maxLines = 1)
                    }
                }
                ConfirmacionDeDescarga.DETENER -> {
                    Text(
                        "Se corta lo que está bajando ahora de esta serie. Los capítulos ya descargados no se tocan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    Button(onClick = onCancelar, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Sí, detener", maxLines = 1)
                    }
                    Button(onClick = { confirmando = null }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Cancelar", maxLines = 1)
                    }
                }
                null -> {
                    if (hayActivas) {
                        Button(onClick = { confirmando = ConfirmacionDeDescarga.DETENER }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Detener lo que está bajando", maxLines = 1)
                        }
                    }
                    if (hayFallidas) {
                        Button(onClick = onReintentar, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reintentar lo que falló", maxLines = 1)
                        }
                    }
                    if (episodioAReproducir != null) {
                        Button(onClick = { onPlayEpisode(episodioAReproducir) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                            Text("Reproducir", maxLines = 1)
                        }
                    }
                    Button(onClick = { confirmando = ConfirmacionDeDescarga.QUITAR }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Quitar del dispositivo", maxLines = 1)
                    }
                    // Mismo criterio acá: por defecto el foco cae en la opción segura, no en la que
                    // arranca el camino hacia borrar.
                    Button(onClick = onDismiss, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                        Text("Volver", maxLines = 1)
                    }
                }
            }
        }
    }
}
