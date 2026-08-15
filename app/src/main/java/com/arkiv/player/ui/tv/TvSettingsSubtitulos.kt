package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.IDIOMAS_AUDIO
import com.arkiv.player.ui.settings.IDIOMAS_SUBTITULO

/**
 * Idioma de audio y subtítulos en la TV. El estilo (tamaño, colores, borde) no está acá a
 * propósito: se edita en el celular y viaja para acá, que es mucho más cómodo que elegir colores
 * con el control remoto.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsSubtitulos() {
    val graph = rememberGraph()
    val prefs by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

    // Persiste local + sincroniza al celular, igual que hace la pantalla de Ajustes del teléfono.
    fun setPrefs(p: PlaybackPrefs) {
        graph.subtitlePrefs.update(p)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendSubtitlePrefs(p.toJson()) } }
    }

    Text("Audio y subtítulos", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvLanguageOrderEditor(
        title = "Idioma del audio (en orden de preferencia)",
        options = IDIOMAS_AUDIO,
        order = prefs.audioLangs,
        onChange = { setPrefs(prefs.copy(audioLangs = it)) },
    )
    TvLanguageChecklist(
        title = "Idiomas que entiendo",
        subtitle = "Los subtítulos se prenden solos únicamente cuando el audio queda en un " +
            "idioma que no está en esta lista.",
        options = IDIOMAS_AUDIO,
        selected = prefs.understoodLangs,
        onChange = { setPrefs(prefs.copy(understoodLangs = it)) },
    )
    TvLanguageOrderEditor(
        title = "Idioma de los subtítulos (en orden de preferencia)",
        options = IDIOMAS_SUBTITULO,
        order = prefs.subtitleLangs,
        onChange = { setPrefs(prefs.copy(subtitleLangs = it)) },
    )
    TvActionOption(
        if (prefs.subtitleMode == SubtitleMode.AUTO) {
            "Subtítulos: automáticos (tocá para desactivar)"
        } else {
            "Subtítulos: desactivados (tocá para automáticos)"
        },
    ) {
        val nuevo = if (prefs.subtitleMode == SubtitleMode.AUTO) SubtitleMode.OFF else SubtitleMode.AUTO
        setPrefs(prefs.copy(subtitleMode = nuevo))
    }
}
