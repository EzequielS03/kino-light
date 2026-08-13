package com.arkiv.player.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.pairing.PairingState
import com.arkiv.player.pairing.qrImageBitmap

/** Pantalla de TV que muestra el QR para parear con el teléfono (rol TV del pareo). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPairingScreen(pairing: PairingManager, deviceName: String, onDone: () -> Unit) {
    val state by pairing.state.collectAsStateWithLifecycle()
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        val qrContent = pairing.startTvPairing(deviceName)
        if (qrContent != null) {
            qr = runCatching { qrImageBitmap(qrContent) }.getOrNull()
        }
        // Si es null, PairingState.Error ya quedó publicado y se muestra abajo.
    }
    LaunchedEffect(state) { if (state is PairingState.Paired) onDone() }
    DisposableEffect(Unit) { onDispose { pairing.stopTvPairing() } }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Escanea este código con la app Kino de tu teléfono",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(24.dp))
        qr?.let { Image(bitmap = it, contentDescription = "QR de pareo", modifier = Modifier.size(320.dp)) }
        Spacer(Modifier.height(24.dp))
        Text(
            when (val s = state) {
                is PairingState.WaitingScan -> "Esperando escaneo…"
                is PairingState.Claiming -> "Pareando…"
                is PairingState.Paired -> "Pareado"
                is PairingState.Error -> "Error: ${s.msg} — vuelve a intentar"
                else -> "Generando código…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
    }
}
