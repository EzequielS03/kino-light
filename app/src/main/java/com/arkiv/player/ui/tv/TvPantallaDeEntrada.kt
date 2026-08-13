package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Se compone en vez de `ArkivTvRoot` cuando `EntradaViewModel` (Task 4) dice que no hay sesión de
 * persona -reemplaza al parche provisorio de la Task 4, que mostraba `PantallaDeEntrada` (el
 * formulario de login del celular) también acá-.
 *
 * En la TV **no hay login manual**: tipear email y contraseña con un control remoto es horrible
 * (decisión del spec), así que la única puerta de entrada es parear con el celular. Envuelve
 * [TvPairingScreen] -el mismo componente que usa Ajustes > "Conectar teléfono" para re-parear más
 * adelante-: es la misma mecánica de pareo (QR + escucha realtime, Task 5), solo que acá es la
 * ÚNICA pantalla, sin navegación alrededor ni forma de "saltearla".
 *
 * `onDone` no hace nada a propósito: en cuanto el pareo aplica la sesión de persona compartida
 * (`PairingManager.aplicarRespuesta` -> `SesionDePersona.aplicarSesionCompartida`), el gate de
 * `MainActivity` recompone solo hacia `ArkivTvRoot` -esta pantalla no tiene que navegar a ningún
 * lado, ni sabe que existe un gate por encima-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPantallaDeEntrada(pairing: PairingManager) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 40.dp, start = 48.dp, end = 48.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "ARKIV",
                style = MaterialTheme.typography.headlineMedium,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Este TV todavía no está emparejado. Escaneá el código con la app Arkiv de tu " +
                    "teléfono -ahí es donde iniciás sesión-.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
        }
        TvPairingScreen(pairing = pairing, deviceName = android.os.Build.MODEL, onDone = {})
    }
}
