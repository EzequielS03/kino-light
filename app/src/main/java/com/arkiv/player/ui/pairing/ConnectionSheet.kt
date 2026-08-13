package com.arkiv.player.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.pairing.PairingManager
import com.arkiv.player.pairing.PairingState

/** Hoja modal que muestra el estado de conexión con el TV y permite re-parear o desvincular. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
    pairing: PairingManager,
    onRepair: () -> Unit,
    onUnlink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by pairing.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conexión con el TV", style = MaterialTheme.typography.titleLarge)
            Text(
                when (val s = state) {
                    is PairingState.Paired -> "✅ Pareado"
                    is PairingState.WaitingScan -> "Esperando escaneo…"
                    is PairingState.Claiming -> "Pareando…"
                    is PairingState.TopeAlcanzado -> s.msg
                    is PairingState.Error -> "Error: ${s.msg}"
                    else -> "Sin parear"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRepair, modifier = Modifier.fillMaxWidth()) { Text("Re-parear (escanear QR)") }
            OutlinedButton(onClick = onUnlink, modifier = Modifier.fillMaxWidth()) { Text("Desvincular") }
        }
    }
}
