package com.arkiv.player.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.arkiv.player.data.Quality
import com.arkiv.player.data.WebQuality
import com.arkiv.player.data.subtitles.SubtitleStyle
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.update.UpdateDialog

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val graph = rememberGraph()
    val settings = graph.settings
    val account = graph.accountManager
    val streamQuality by settings.streamQuality.collectAsStateWithLifecycle()
    val downloadQuality by settings.downloadQuality.collectAsStateWithLifecycle()
    val maxSizeGb by settings.maxTorrentSizeGb.collectAsStateWithLifecycle()
    val webQuality by settings.webQuality.collectAsStateWithLifecycle()
    val liveSignRemote by settings.liveSignRemote.collectAsStateWithLifecycle()
    val subStyle by graph.subtitlePrefs.style.collectAsStateWithLifecycle()

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

    // Cambiar estilo: persiste local + sincroniza a los otros dispositivos (TV).
    fun setStyle(s: SubtitleStyle) {
        graph.subtitlePrefs.update(s)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendSubtitlePrefs(s.toJson()) } }
    }

    // Calidad web: persiste local + sincroniza al otro dispositivo.
    fun setWebQuality(q: WebQuality) {
        settings.setWebQuality(q)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendWebQuality(q.name) } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "Ajustes",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        QualitySection(
            title = "Calidad al reproducir (streaming)",
            selected = streamQuality,
            onSelect = settings::setStreamQuality,
        )
        QualitySection(
            title = "Calidad al descargar",
            selected = downloadQuality,
            onSelect = settings::setDownloadQuality,
        )

        WebQualitySection(webQuality, ::setWebQuality)

        MaxSizeSection(maxSizeGb, settings::setMaxTorrentSizeGb)

        LiveSignSection(liveSignRemote, settings::setLiveSignRemote)

        SubtitleSection(subStyle, ::setStyle)

        UpdateSection(checking = checkingUpdate, onCheck = ::checkForUpdatesNow)

        AccountSection(account)
    }
}

@Composable
private fun UpdateSection(checking: Boolean, onCheck: () -> Unit) {
    Text(
        "Actualizaciones",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Button(onClick = onCheck, enabled = !checking) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            Text("Buscando…", modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Buscar actualizaciones")
        }
    }
    Box(Modifier.padding(bottom = 32.dp))
}

@Composable
private fun WebQualitySection(selected: WebQuality, onSelect: (WebQuality) -> Unit) {
    Text(
        "Calidad de fuentes web",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
    Text(
        "Auto: HD directo del CDN si tu conexión aguanta; 480p cuando va por el proxy (más lento). " +
            "Fijá SD/HD/Máx si preferís forzar una calidad.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            WebQuality.AUTO to "Auto",
            WebQuality.SD to "SD · 480p",
            WebQuality.HD to "HD · 720p",
            WebQuality.MAX to "Máx",
        ).forEach { (q, label) -> Chip(label, selected == q) { onSelect(q) } }
    }
}

@Composable
private fun MaxSizeSection(selectedGb: Int, onSelect: (Int) -> Unit) {
    Text(
        "Tamaño máximo del torrent",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
    Text(
        "Oculta torrents demasiado pesados para streaming. Los packs de temporada no cuentan " +
            "(de un pack solo se reproduce un episodio). Para ver bien fluido, 1080p de ~4–8 GB es lo ideal.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(8 to "8 GB", 15 to "15 GB", 21 to "21 GB", 30 to "30 GB", 0 to "Sin límite").forEach { (gb, label) ->
            Chip(label, selectedGb == gb) { onSelect(gb) }
        }
    }
}

@Composable
private fun LiveSignSection(remote: Boolean, onSelect: (Boolean) -> Unit) {
    Text(
        "TV en vivo",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
    Text(
        "Normalmente el celular firma los segmentos solo, sin ida y vuelta al servidor. " +
            "\"Forzar servidor\" fuerza el camino de respaldo aunque el CDN no esté rechazando " +
            "nada — sirve para comprobar de vez en cuando que ese camino sigue andando.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Automático", !remote) { onSelect(false) }
        Chip("Forzar servidor", remote) { onSelect(true) }
    }
}

@Composable
private fun SubtitleSection(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Text(
        "Subtítulos",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
    Text(
        "Se sincroniza con la TV.",
        style = MaterialTheme.typography.bodySmall,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    // Vista previa.
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222)).padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Ejemplo de subtítulo",
            color = Color(style.textColor),
            fontSize = (16 * style.sizePercent / 100).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(Color(style.backgroundColor)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    // Idioma preferido (auto-carga).
    Label("Idioma preferido")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Español (auto)", style.language == "es") { onChange(style.copy(language = "es")) }
        Chip("Desactivado", style.language == "off") { onChange(style.copy(language = "off")) }
    }

    // Tamaño.
    Label("Tamaño: ${style.sizePercent}%")
    Slider(
        value = style.sizePercent.toFloat(),
        onValueChange = { onChange(style.copy(sizePercent = it.toInt())) },
        valueRange = 60f..200f,
        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = ArkivRed, activeTrackColor = ArkivRed),
    )

    // Color del texto.
    Label("Color del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(0xFFFFFFFF, style.textColor) { onChange(style.copy(textColor = it)) }
        Swatch(0xFFFFEB3B, style.textColor) { onChange(style.copy(textColor = it)) } // amarillo
        Swatch(0xFF00E5FF, style.textColor) { onChange(style.copy(textColor = it)) } // cian
        Swatch(0xFF00E676, style.textColor) { onChange(style.copy(textColor = it)) } // verde
    }

    // Fondo de la caja.
    Label("Fondo")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Caja negra", style.backgroundColor == 0xCC000000L) { onChange(style.copy(backgroundColor = 0xCC000000L)) }
        Chip("Semi", style.backgroundColor == 0x80000000L) { onChange(style.copy(backgroundColor = 0x80000000L)) }
        Chip("Sin fondo", style.backgroundColor == 0x00000000L) { onChange(style.copy(backgroundColor = 0x00000000L)) }
    }

    // Borde.
    Label("Borde del texto")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Contorno", style.edge == SubtitleStyle.EDGE_OUTLINE) { onChange(style.copy(edge = SubtitleStyle.EDGE_OUTLINE)) }
        Chip("Sombra", style.edge == SubtitleStyle.EDGE_SHADOW) { onChange(style.copy(edge = SubtitleStyle.EDGE_SHADOW)) }
        Chip("Ninguno", style.edge == SubtitleStyle.EDGE_NONE) { onChange(style.copy(edge = SubtitleStyle.EDGE_NONE)) }
    }
    Box(Modifier.padding(bottom = 32.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArkivRed, selectedLabelColor = Color.White),
    )
}

@Composable
private fun Swatch(color: Long, selected: Long, onClick: (Long) -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(color))
            .border(if (color == selected) 3.dp else 1.dp, if (color == selected) ArkivRed else Color.Gray, RoundedCornerShape(6.dp))
            .clickable { onClick(color) },
    )
}

@Composable
private fun QualitySection(
    title: String,
    selected: Quality,
    onSelect: (Quality) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    QualityOption("Original (máxima calidad, mkv)", Quality.ORIGINAL, selected, onSelect)
    QualityOption("Liviano (mp4, ahorra datos)", Quality.DERIVATIVE, selected, onSelect)
}

@Composable
private fun QualityOption(
    label: String,
    value: Quality,
    selected: Quality,
    onSelect: (Quality) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected == value, onClick = { onSelect(value) })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected == value) MaterialTheme.colorScheme.onBackground else ArkivTextSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
