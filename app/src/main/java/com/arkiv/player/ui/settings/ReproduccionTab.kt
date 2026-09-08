package com.arkiv.player.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.Quality
import com.arkiv.player.data.WebQuality
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Todo lo que decide **con qué calidad y de qué fuente** se reproduce. Es el tab que más se toca,
 * así que es el que abre la pantalla.
 */
@Composable
internal fun ReproduccionTab() {
    val graph = rememberGraph()
    val settings = graph.settings
    val streamQuality by settings.streamQuality.collectAsStateWithLifecycle()
    val downloadQuality by settings.downloadQuality.collectAsStateWithLifecycle()
    val maxSizeGb by settings.maxTorrentSizeGb.collectAsStateWithLifecycle()
    val webQuality by settings.webQuality.collectAsStateWithLifecycle()
    val liveSignRemote by settings.liveSignRemote.collectAsStateWithLifecycle()

    // Calidad web: persiste local.
    fun setWebQuality(q: WebQuality) {
        settings.setWebQuality(q)
    }

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
