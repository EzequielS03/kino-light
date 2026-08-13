package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.pairing.qrImageBitmap
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Se compone en vez de `ArkivTvRoot` cuando `EntradaViewModel` (Task 4) dice que no hay sesión de
 * persona.
 *
 * En la TV **no hay login manual**: tipear email y contraseña con un control remoto es horrible
 * (decisión del spec), así que la única puerta de entrada es parear con el celular.
 *
 * ### Por qué dos pestañas y no un solo QR
 *
 * El pareo asume un teléfono que ya tiene Arkiv instalado. Quien estrena el TV sin eso quedaba en
 * un callejón: la única pantalla que ve le pide escanear con una app que no tiene, y desde el TV
 * no hay salida. La pestaña "Descargar" resuelve eso.
 *
 * Son **pestañas y no pasos** para que se pueda ir y volver: con un asistente de dos pasos, quien
 * pasa al pareo y después se da cuenta de que el teléfono no tenía la app queda otra vez sin
 * camino de vuelta. Las dos están siempre a la vista y a un movimiento del control remoto.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPantallaDeEntrada(pairing: PairingManager) {
    var enDescarga by remember { mutableStateOf(true) }
    val focoDescarga = remember { FocusRequester() }

    // El foco arranca en las pestañas, con reintento: pedirlo en la primera composición falla en
    // silencio porque el nodo todavía no está colocado, y queda una pantalla que se ve pero no
    // responde al control remoto hasta que alguien mueve el DPAD por su cuenta. Pasó exactamente
    // eso en el Fire TV.
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoDescarga.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 36.dp, start = 48.dp, end = 48.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "KINO",
                style = MaterialTheme.typography.headlineMedium,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Este TV todavía no está emparejado -es en el teléfono donde iniciás sesión-.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PestanaDeEntrada(
                    texto = "1 · Descargar la app",
                    seleccionada = enDescarga,
                    onSelect = { enDescarga = true },
                    modifier = Modifier.focusRequester(focoDescarga),
                )
                PestanaDeEntrada(
                    texto = "2 · Parear este TV",
                    seleccionada = !enDescarga,
                    onSelect = { enDescarga = false },
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (enDescarga) {
                PanelDeDescarga()
            } else {
                // Column y no Box superpuesto: `TvPairingScreen` es un `fillMaxSize()` centrado que
                // dibuja su propio título, y encimarlo al encabezado los hacía caer en la misma
                // línea, ilegibles uno sobre el otro.
                TvPairingScreen(pairing = pairing, deviceName = android.os.Build.MODEL, onDone = {})
            }
        }
    }
}

/** Mismo lenguaje visual que los chips de fuente de `TvSearchScreen`: el foco gana en contraste,
 *  que es lo que se necesita ver desde el sofá. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PestanaDeEntrada(
    texto: String,
    seleccionada: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSelect,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (seleccionada) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed.copy(alpha = 0.55f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * QR para bajar el APK al teléfono. La URL sale de `latest.json` -la misma que usa el aviso de
 * actualización- y no de una constante: lleva el número de versión adentro, así que hardcodearla
 * la dejaría apuntando a un archivo viejo en la próxima publicación.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelDeDescarga() {
    val graph = rememberGraph()
    var url by remember { mutableStateOf<String?>(null) }
    var fallo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        url = graph.updateChecker.urlDeDescarga()
        fallo = url == null
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Escaneá este código para instalar Kino en tu teléfono",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(20.dp))
        val u = url
        when {
            u != null -> {
                val qr = remember(u) { runCatching { qrImageBitmap(u) }.getOrNull() }
                qr?.let {
                    Image(bitmap = it, contentDescription = "QR de descarga", modifier = Modifier.size(280.dp))
                }
            }
            // Sin red no se puede armar el QR, pero el pareo sí funciona en la LAN: no hay que
            // dejar trabado acá a quien ya tiene la app.
            fallo -> Text(
                "No se pudo obtener el enlace de descarga. Si ya tenés la app, pasá a \"Parear este TV\".",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            else -> Text("Generando código…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}
