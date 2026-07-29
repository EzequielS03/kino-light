package com.arkiv.player.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val sync = rememberGraph().syncManager
    val remote = rememberGraph().remoteController
    val scope = rememberCoroutineScope()
    val tvAvailable by sync.tvAvailable.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf(true) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        connecting = true
        sync.connectRemote()
        connecting = false
    }

    fun key(k: String) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch { remote.sendKey(k) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Encabezado.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Text(
                "Control remoto",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // Estado de conexión.
        val status = when {
            connecting -> "Buscando la TV…"
            tvAvailable -> "Conectado a la TV"
            else -> "No se encontró la TV. Abrí Arkiv en el Fire Stick."
        }
        Text(
            status,
            color = if (tvAvailable) ArkivRed else ArkivTextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!tvAvailable && !connecting) {
            Button(
                onClick = { scope.launch { connecting = true; sync.connectRemote(); connecting = false } },
                colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Reintentar") }
        }

        Spacer(Modifier.weight(1f))

        // Cruceta D-pad.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PadButton(Icons.Filled.KeyboardArrowUp, "Arriba") { key("UP") }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadButton(Icons.Filled.KeyboardArrowLeft, "Izquierda") { key("LEFT") }
                OkButton { key("OK") }
                PadButton(Icons.Filled.KeyboardArrowRight, "Derecha") { key("RIGHT") }
            }
            PadButton(Icons.Filled.KeyboardArrowDown, "Abajo") { key("DOWN") }
        }

        Spacer(Modifier.height(48.dp))

        // Atrás + Play/Pausa.
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LabeledPad("Atrás") {
                PadButton(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") { key("BACK") }
            }
            LabeledPad("Play / Pausa") {
                PadButton(Icons.Filled.PlayArrow, "Play/Pausa") { key("PLAYPAUSE") }
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PadButton(icon: ImageVector, desc: String, size: Dp = 76.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ArkivSurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun OkButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(ArkivRed)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LabeledPad(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, color = ArkivTextSecondary, modifier = Modifier.padding(top = 6.dp))
    }
}
