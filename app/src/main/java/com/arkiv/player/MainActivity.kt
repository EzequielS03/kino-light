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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.security.RootDetection
import com.arkiv.player.security.ApkSignature
import com.arkiv.player.security.LockedScreen
import com.arkiv.player.security.RootSignalCollector
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.ArkivTvRoot
import kotlinx.coroutines.delay

/**
 * Head start given to the intro before starting to compose the app: without this the main thread
 * gets saturated and the animation doesn't get to draw (see the comment on setContent).
 *
 * Comes from [com.arkiv.player.ui.INTRO_DURATION_MS] and is NOT a loose number, on purpose.
 * Written by hand it drifted: it stayed at 600 ms --tuned for the old, 750 ms intro-- while the
 * intro grew to 880, so the root composed on top of its heaviest stretch. Tied to the real
 * duration, composition always lands AFTER the animation finished drawing, and the exit fade
 * covers it.
 */
private val INTRO_HEAD_START_MS = com.arkiv.player.ui.INTRO_DURATION_MS.toLong()

/** Margin after starting the root's composition before uncovering the app with the fade. */
private const val CONTENT_SETTLE_MS = 400L

/**
 * Whether a rooted device gets blocked. **Off on purpose**: today we want a device with root to
 * still be able to use the app.
 *
 * It's turned off with a switch instead of deleting [com.arkiv.player.security.RootDetection]
 * because the detection itself is already built and tested (tests included); turning it back on
 * is flipping this `false` to `true`, not rewriting it.
 *
 * Note what this switch does NOT change: the APK signature check stays live, and it's the one
 * that must be kept -- without it any future check gets removed by decompiling and re-signing.
 */
private const val BLOCK_ON_ROOT = false

class MainActivity : AppCompatActivity() {

    private var pendingEpisode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: cambia del tema de arranque (logo del sistema) al tema real.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Integrity checks BEFORE building anything: no services, no Room. If the device fails
        // them, the only thing composed is the warning. See `RootDetection` for what it detects
        // and, above all, for what it CANNOT detect.
        val blockingReasons = reasonsNotToStart()
        if (blockingReasons.isNotEmpty()) {
            setContent { ArkivTheme { LockedScreen(blockingReasons) } }
            return
        }

        val isTv = isTelevision() || intent.getBooleanExtra("force_tv", false)
        setContent {
            ArkivTheme {
                val graph = (application as ArkivApp).graph

                // La intro se dibuja ENCIMA de la app para tapar el arranque en frío.
                //
                // El contenido NO se compone de entrada: armar el root (Room, filas del
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
                        // Sin gate de sesión: Kino L entra directo al home, sin preguntarle a
                        // PocketBase ni al gateway si hay sesión. El subsistema de cuentas
                        // (ui/entrada/, EntradaViewModel) se borró entero en la Task 9
                        // (sub-proyecto 2B): no queda nada que llamar desde acá.
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

                    // OTA: se muestra sola cuando AppGraph detecta una versión nueva (chequeo al
                    // arrancar o el UpdateWorker periódico). "dismissed" solo tapa esta instancia
                    // del diálogo global; el chequeo manual desde Ajustes usa su propia instancia.
                    val updateAvailable by graph.updateInfo.collectAsState()
                    var dismissed by remember { mutableStateOf(false) }
                    updateAvailable?.let { info ->
                        if (!dismissed) {
                            com.arkiv.player.ui.update.UpdateDialog(
                                info = info,
                                graph = graph,
                                onDismiss = { dismissed = true },
                            )
                        }
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
            // El extra manda cuando viene (aviso de "descarga completa", que apunta a un capítulo
            // concreto); sin él se abre el que está sonando, que es lo que pide la notificación del
            // reproductor.
            pendingEpisode = intent.getStringExtra(EXTRA_EPISODE_ID) ?: NowPlaying.episodeId
        }
    }

    /**
     * Why this device cannot run the app. Empty = it can.
     *
     * Two checks, in the order they matter:
     *
     * 1. **APK signature.** If we don't check it, the root block is worthless: decompile, strip
     *    it, re-sign. Only enforced in release (see [ApkSignature]).
     * 2. **Root.** See [RootDetection], which also explains its limits. Today it does NOT block:
     *    it's behind [BLOCK_ON_ROOT], turned off.
     */
    private fun reasonsNotToStart(): List<String> {
        if (!ApkSignature.isOurs(this, BuildConfig.DEBUG)) {
            return listOf("el APK no está firmado con el certificado de Kino")
        }
        if (!BLOCK_ON_ROOT) return emptyList()
        return RootDetection.reasons(RootSignalCollector.collect(this))
    }

    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
}
