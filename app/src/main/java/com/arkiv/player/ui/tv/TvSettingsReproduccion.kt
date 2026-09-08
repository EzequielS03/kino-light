package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.Quality
import com.arkiv.player.data.WebQuality
import com.arkiv.player.ui.rememberGraph

/** Con qué calidad reproduce este televisor. Ver [TvSettingsScreen]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsReproduccion() {
    val graph = rememberGraph()
    val settings = graph.settings
    val streamQuality by settings.streamQuality.collectAsStateWithLifecycle()
    val webQuality by settings.webQuality.collectAsStateWithLifecycle()

    // Calidad web: persiste local.
    fun setWebQuality(q: WebQuality) {
        settings.setWebQuality(q)
    }

    Text("Calidad al reproducir", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvRadioOption("Original (máxima calidad, mkv)", streamQuality == Quality.ORIGINAL) {
        settings.setStreamQuality(Quality.ORIGINAL)
    }
    TvRadioOption("Liviano (mp4, ahorra datos)", streamQuality == Quality.DERIVATIVE) {
        settings.setStreamQuality(Quality.DERIVATIVE)
    }

    Text("Calidad de fuentes web", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvRadioOption("Auto (recomendado: HD si la conexión da, si no SD)", webQuality == WebQuality.AUTO) {
        setWebQuality(WebQuality.AUTO)
    }
    TvRadioOption("SD · 480p (máxima fluidez)", webQuality == WebQuality.SD) {
        setWebQuality(WebQuality.SD)
    }
    TvRadioOption("HD · hasta 720p", webQuality == WebQuality.HD) {
        setWebQuality(WebQuality.HD)
    }
    TvRadioOption("Máx · la más alta disponible", webQuality == WebQuality.MAX) {
        setWebQuality(WebQuality.MAX)
    }
}
