package com.arkiv.player.ui.torrent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed

@Composable
fun TorrentScreen(onAdded: (String) -> Unit, onBack: () -> Unit) {
    val graph = rememberGraph()
    val vm: TorrentViewModel = viewModel(
        factory = viewModelFactory { initializer { TorrentViewModel(graph.torrentEngine, graph.repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var magnet by remember { mutableStateOf("") }

    val pickTorrent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) vm.playTorrentBytes(bytes)
        }
    }

    LaunchedEffect(state) {
        (state as? TorrentViewModel.State.Added)?.let {
            onAdded(it.itemId)
            vm.reset()
        }
    }

    val resolving = state is TorrentViewModel.State.Resolving

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Text(
                "Agregar torrent",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "Pegá un enlace magnet o una URL .torrent, o elegí un archivo .torrent. " +
                "Se agrega a tu biblioteca y se reproduce en streaming (sin ocupar espacio).",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        OutlinedTextField(
            value = magnet,
            onValueChange = { magnet = it },
            label = { Text("Magnet o URL .torrent") },
            singleLine = false,
            enabled = !resolving,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { vm.play(magnet) },
                enabled = !resolving && magnet.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
            ) { Text("Agregar a biblioteca") }
            OutlinedButton(
                onClick = { pickTorrent.launch("*/*") },
                enabled = !resolving,
            ) { Text("Archivo .torrent") }
        }
        when (val s = state) {
            is TorrentViewModel.State.Resolving -> Row(
                modifier = Modifier.padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                Text("Leyendo el torrent… (puede tardar)")
            }
            is TorrentViewModel.State.Error -> Text(
                s.message,
                color = ArkivRed,
                modifier = Modifier.padding(top = 24.dp),
            )
            else -> {}
        }
    }
}
