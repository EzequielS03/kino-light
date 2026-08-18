package com.arkiv.player.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.update.UpdateDialog

/** Lo que es de la app y no del contenido: actualizaciones y acceso a descargas offline. */
@Composable
internal fun AppTab(onOpenDownloads: () -> Unit = {}) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // Chequeo manual: independiente del diálogo global de MainActivity, así funciona aunque
    // este último ya haya sido descartado por el usuario en esta sesión.
    fun checkForUpdatesNow() {
        checking = true
        scope.launch {
            graph.checkForUpdate()
            checking = false
            val info = graph.updateInfo.value
            if (info != null) {
                manualUpdate = info
            } else {
                Toast.makeText(context, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    manualUpdate?.let { info ->
        UpdateDialog(info = info, graph = graph, onDismiss = { manualUpdate = null })
    }

    Text(
        "Descargas",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Button(onClick = onOpenDownloads) {
        Text("Ver descargas")
    }

    Text(
        "Actualizaciones",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Button(onClick = ::checkForUpdatesNow, enabled = !checking) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            Text("Buscando…", modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Buscar actualizaciones")
        }
    }
}
