package com.arkiv.player.ui.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.arkiv.player.data.Quality
import com.arkiv.player.data.WebQuality
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.update.UpdateDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(onConnectPhone: () -> Unit = {}) {
    val graph = rememberGraph()
    val settings = graph.settings
    val streamQuality by settings.streamQuality.collectAsStateWithLifecycle()
    val webQuality by settings.webQuality.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // Chequeo manual: independiente del diálogo global de MainActivity, así funciona aunque
    // este último ya haya sido descartado por el usuario en esta sesión.
    fun checkForUpdatesNow() {
        checkingUpdate = true
        scope.launch {
            graph.checkForUpdate()
            checkingUpdate = false
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

    // Calidad web: persiste local + sincroniza al otro dispositivo (celular/TV).
    fun setWebQuality(q: WebQuality) {
        settings.setWebQuality(q)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendWebQuality(q.name) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("Calidad al reproducir", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvQualityOption("Original (máxima calidad, mkv)", Quality.ORIGINAL, streamQuality) {
            settings.setStreamQuality(Quality.ORIGINAL)
        }
        TvQualityOption("Liviano (mp4, ahorra datos)", Quality.DERIVATIVE, streamQuality) {
            settings.setStreamQuality(Quality.DERIVATIVE)
        }
        Text("Calidad de fuentes web", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvWebQualityOption("Auto (recomendado: HD si la conexión da, si no SD)", WebQuality.AUTO, webQuality) {
            setWebQuality(WebQuality.AUTO)
        }
        TvWebQualityOption("SD · 480p (máxima fluidez)", WebQuality.SD, webQuality) {
            setWebQuality(WebQuality.SD)
        }
        TvWebQualityOption("HD · hasta 720p", WebQuality.HD, webQuality) {
            setWebQuality(WebQuality.HD)
        }
        TvWebQualityOption("Máx · la más alta disponible", WebQuality.MAX, webQuality) {
            setWebQuality(WebQuality.MAX)
        }
        Text("Teléfono", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvActionOption("Conectar teléfono", onConnectPhone)
        Text("Actualizaciones", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvActionOption(
            if (checkingUpdate) "Buscando…" else "Buscar actualizaciones",
            onClick = { if (!checkingUpdate) checkForUpdatesNow() },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvWebQualityOption(label: String, value: WebQuality, selected: WebQuality, onSelect: () -> Unit) {
    val isSelected = selected == value
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.White),
            )
            Text(label, color = Color.White, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActionOption(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQualityOption(label: String, value: Quality, selected: Quality, onSelect: () -> Unit) {
    val isSelected = selected == value
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Color.White,
                    unselectedColor = Color.White,
                ),
            )
            Text(label, color = Color.White, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
