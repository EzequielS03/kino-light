package com.arkiv.player.ui.tv

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.R

/**
 * Devuelve una función que reproduce un "tick" corto de navegación (estilo Netflix)
 * al cambiar de ítem enfocado en la TV. Libera el SoundPool al salir.
 */
@Composable
fun rememberNavSound(): () -> Unit {
    val context = LocalContext.current
    val pool = remember {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }
    val soundId = remember { pool.load(context, R.raw.nav_click, 1) }
    DisposableEffect(Unit) {
        onDispose { pool.release() }
    }
    return remember(soundId) {
        { pool.play(soundId, 0.5f, 0.5f, 1, 0, 1f) }
    }
}
