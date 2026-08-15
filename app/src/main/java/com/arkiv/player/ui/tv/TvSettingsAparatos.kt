package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.AparatoUi
import com.arkiv.player.ui.settings.EstadoMisAparatos
import com.arkiv.player.ui.settings.MisAparatosViewModel
import com.arkiv.player.ui.settings.etiquetaDeTipo
import com.arkiv.player.ui.settings.mensajeDeConfirmacion
import com.arkiv.player.ui.settings.nombreParaMostrar
import com.arkiv.player.ui.settings.ultimoUsoParaMostrar
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Los aparatos de la cuenta, y el emparejado con el celular. Ver [TvSettingsScreen]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsAparatos(onConnectPhone: () -> Unit) {
    val graph = rememberGraph()

    Text("Teléfono", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvActionOption("Conectar teléfono", onConnectPhone)

    Text(
        "Mis aparatos",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = Modifier.padding(top = 24.dp),
    )
    TvMisAparatosSection(graph.misAparatosViewModel)
}

/**
 * "Mis aparatos" en la TV (Task 6): misma instancia de [MisAparatosViewModel] que usa
 * `ui/settings/MisAparatos.kt` en el celular (`AppGraph.misAparatosViewModel`) -solo cambia la UI-,
 * cada fila es un [TvActionOption] (navegable con D-pad, igual que el resto de esta pantalla) que
 * ABRE la confirmación; el DELETE en sí solo sale de [TvSacarAparatoDialog.onConfirmar].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMisAparatosSection(vm: MisAparatosViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val pendiente by vm.pendienteDeSacar.collectAsStateWithLifecycle()
    val sacandoId by vm.sacandoId.collectAsStateWithLifecycle()
    val avisoError by vm.avisoError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.cargar() }

    when (val e = estado) {
        EstadoMisAparatos.Cargando -> Text("Cargando…", color = ArkivTextSecondary)
        is EstadoMisAparatos.Error -> {
            Text(e.mensaje, color = ArkivRed)
            TvActionOption("Reintentar") { scope.launch { vm.cargar() } }
        }
        is EstadoMisAparatos.Cargado -> {
            e.aparatos.forEach { aparato ->
                val visto = ultimoUsoParaMostrar(aparato.ultimoUso)
                val label = nombreParaMostrar(aparato) +
                    (if (aparato.esEsteAparato) " · este aparato" else "") +
                    " · " + etiquetaDeTipo(aparato.kind) +
                    (if (visto.isNotBlank()) " · visto $visto" else "")
                TvActionOption(if (sacandoId == aparato.id) "$label · sacando…" else label) {
                    vm.pedirSacar(aparato)
                }
            }
            avisoError?.let { Text(it, color = ArkivRed, modifier = Modifier.padding(top = 4.dp)) }

            pendiente?.let { aparato ->
                TvSacarAparatoDialog(
                    aparato = aparato,
                    esElUltimo = e.aparatos.size <= 1,
                    ocupado = sacandoId != null,
                    onConfirmar = { scope.launch { vm.confirmarSacar() } },
                    onCancelar = vm::cancelarSacar,
                )
            }
        }
    }
}

/**
 * Confirmación de "sacar" en la TV. Mismo patrón que `TvDownloadActionsDialog`
 * (`ui/tv/library/TvDownloadsSection.kt`): `Dialog` + foco que salta al botón SEGURO ("Cancelar"),
 * nunca al destructivo -acá "sacar" puede cerrar la sesión de este mismo aparato, así que el
 * criterio pesa todavía más que borrar unos GB-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSacarAparatoDialog(
    aparato: AparatoUi,
    esElUltimo: Boolean,
    ocupado: Boolean,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { focus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    Dialog(onDismissRequest = { if (!ocupado) onCancelar() }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Sacar \"${nombreParaMostrar(aparato)}\"",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                mensajeDeConfirmacion(aparato, esElUltimo),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            Button(
                onClick = onConfirmar,
                enabled = !ocupado,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ocupado) "Sacando…" else "Sí, sacar", maxLines = 1) }
            // El foco por defecto va acá, no al botón de arriba -mismo criterio que
            // TvDownloadActionsDialog-.
            Button(
                onClick = onCancelar,
                enabled = !ocupado,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            ) { Text("Cancelar", maxLines = 1) }
        }
    }
}
