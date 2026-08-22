package com.arkiv.player.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.ui.player.DituExoPlayer
import com.arkiv.player.ui.player.EspejoDelPlayer
import com.arkiv.player.ui.rememberGraph

/**
 * Pantalla de reproductor de canal en vivo de Ditu/Caracol.
 *
 * Ruta: `ditu_live/{channelId}/{assetId}/{channelName}`
 *
 * Funciona en celu y Fire TV: el [BackHandler] intercepta el botón de retroceso
 * del control remoto antes de que el nav graph lo maneje, y el [IconButton] de
 * cerrar recibe foco automático al entrar (necesario en TV para que el D-pad
 * lo encuentre sin tener que buscar).
 *
 * Reutilizable para otras fuentes de TV en vivo (Magis, etc.): basta con pasar
 * una [GatewayPlayable] con la URL DASH/HLS y las credenciales DRM.
 */
@Composable
fun DituLivePlayerScreen(
    channelId: Int,
    assetId: Int,
    channelName: String,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val espejo = remember { EspejoDelPlayer() }
    val closeFocus = remember { FocusRequester() }

    var playable by remember { mutableStateOf<GatewayPlayable?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(channelId, assetId) {
        cargando = true
        errorMsg = null
        try {
            playable = graph.arkivApiClient.dituResolveLive(channelId, assetId)
        } catch (e: Throwable) {
            errorMsg = e.message ?: "Error al cargar el canal"
        } finally {
            cargando = false
        }
    }

    // Pide foco al botón de cerrar para que en TV el D-pad lo encuentre al entrar.
    LaunchedEffect(Unit) {
        runCatching { closeFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable(), // captura eventos del D-pad en TV
    ) {
        playable?.let { p ->
            DituExoPlayer(
                mediaUrl = p.url,
                licenseUrl = p.drmLicenseUrl,
                licenseHeaders = p.drmLicenseHeaders,
                espejo = espejo,
                onError = { msg -> errorMsg = msg },
            )
        }

        // Spinner mientras resuelve
        if (cargando) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Error
        errorMsg?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Nombre del canal (esquina superior izquierda)
        if (channelName.isNotBlank()) {
            Text(
                text = channelName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Botón de cerrar (esquina superior derecha) — recibe foco en TV
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .focusRequester(closeFocus),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
        }
    }
}
