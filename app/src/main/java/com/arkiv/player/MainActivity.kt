package com.arkiv.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.ArkivTvRoot
import kotlinx.coroutines.delay

/**
 * Ventaja que se le da a la intro antes de empezar a componer la app: sin esto el hilo principal
 * se satura y la animación no llega a dibujarse (ver comentario en setContent).
 */
private const val INTRO_HEAD_START_MS = 600L

/** Margen tras arrancar la composición del root antes de destapar la app con el fundido. */
private const val CONTENT_SETTLE_MS = 400L

class MainActivity : AppCompatActivity() {

    private var pendingEpisode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: cambia del tema de arranque (logo del sistema) al tema real.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val isTv = isTelevision() || intent.getBooleanExtra("force_tv", false)
        if (isTv) com.arkiv.player.tvservice.TvConnectionService.start(this)
        if (isTv) com.arkiv.player.tvservice.TvKeepAliveWorker.schedule(this)
        setContent {
            ArkivTheme {
                // La intro se dibuja ENCIMA de la app para tapar el arranque en frío.
                //
                // El contenido NO se compone de entrada: armar el root (Room, sync, filas del
                // home) satura el hilo principal y el reloj de la animación salta hasta el final
                // sin llegar a dibujarse. Dándole ~1s de hilo libre, la intro se reproduce de
                // verdad y recién ahí empieza a componerse la app, por detrás del fundido.
                var splashDone by remember { mutableStateOf(false) }
                var loadContent by remember { mutableStateOf(false) }
                var contentSettled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(INTRO_HEAD_START_MS)
                    loadContent = true
                    // La composición del root bloquea el hilo principal; esta espera se reanuda
                    // recién cuando termina, así el fundido destapa algo ya dibujado.
                    delay(CONTENT_SETTLE_MS)
                    contentSettled = true
                }
                Box(Modifier.fillMaxSize()) {
                    if (loadContent) {
                        if (isTv) {
                            ArkivTvRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        } else {
                            ArkivRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        }
                    }
                    if (!splashDone) {
                        ArkivSplash(
                            isTv = isTv,
                            canExit = contentSettled,
                            onFinished = { splashDone = true },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) {
            pendingEpisode = NowPlaying.episodeId
        }
    }

    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
}
