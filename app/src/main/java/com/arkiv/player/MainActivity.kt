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
import com.arkiv.player.seguridad.DeteccionDeRoot
import com.arkiv.player.seguridad.FirmaDelApk
import com.arkiv.player.seguridad.PantallaBloqueada
import com.arkiv.player.seguridad.RecolectorDeSenales
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.ArkivTvRoot
import kotlinx.coroutines.delay

/**
 * Ventaja que se le da a la intro antes de empezar a componer la app: sin esto el hilo principal
 * se satura y la animación no llega a dibujarse (ver comentario en setContent).
 *
 * Sale de [com.arkiv.player.ui.DURACION_DE_LA_INTRO_MS] y NO es un número suelto, a propósito.
 * Escrito a mano se desfasó: quedó en 600 ms —afinado para la intro vieja, de 750 ms— mientras la
 * intro pasaba a durar 880, así que el root se componía encima de su tramo más pesado. Atado a la
 * duración real, la composición cae siempre DESPUÉS de que la animación terminó de dibujar, y el
 * fundido de salida la tapa.
 */
private val INTRO_HEAD_START_MS = com.arkiv.player.ui.DURACION_DE_LA_INTRO_MS.toLong()

/** Margen tras arrancar la composición del root antes de destapar la app con el fundido. */
private const val CONTENT_SETTLE_MS = 400L

/**
 * Si el aparato rooteado se bloquea o no. **Apagado a propósito**: hoy queremos que un aparato con
 * root pueda usar la app igual.
 *
 * Se apaga con un interruptor en vez de borrar [com.arkiv.player.seguridad.DeteccionDeRoot] porque
 * la detección en sí quedó hecha y probada (tests incluidos); volver a prenderla es cambiar este
 * `false` por `true`, no reescribirla.
 *
 * Ojo con lo que este interruptor NO cambia: la comprobación de firma del APK sigue viva, y es la
 * que hay que dejar en pie —sin ella cualquier control futuro se quita decompilando y re-firmando.
 */
private const val BLOQUEAR_POR_ROOT = false

class MainActivity : AppCompatActivity() {

    private var pendingEpisode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: cambia del tema de arranque (logo del sistema) al tema real.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Controles de integridad ANTES de armar nada: ni servicios, ni Room, ni sync. Si el aparato
        // no pasa, lo único que se compone es el aviso. Ver `DeteccionDeRoot` para qué detecta y,
        // sobre todo, para qué NO puede detectar.
        val motivosDeBloqueo = motivosParaNoArrancar()
        if (motivosDeBloqueo.isNotEmpty()) {
            setContent { ArkivTheme { PantallaBloqueada(motivosDeBloqueo) } }
            return
        }

        val isTv = isTelevision() || intent.getBooleanExtra("force_tv", false)
        setContent {
            ArkivTheme {
                val graph = (application as ArkivApp).graph

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
     * Por qué este aparato no puede ejecutar la app. Vacío = puede.
     *
     * Dos controles, en el orden en que importan:
     *
     * 1. **Firma del APK.** Si no la comprobamos, el bloqueo por root no vale nada: se decompila,
     *    se le quita y se vuelve a firmar. Solo se exige en release (ver [FirmaDelApk]).
     * 2. **Root.** Ver [DeteccionDeRoot], que también explica sus límites. Hoy NO bloquea: está
     *    detrás de [BLOQUEAR_POR_ROOT], apagado.
     */
    private fun motivosParaNoArrancar(): List<String> {
        if (!FirmaDelApk.esNuestra(this, BuildConfig.DEBUG)) {
            return listOf("el APK no está firmado con el certificado de Kino")
        }
        if (!BLOQUEAR_POR_ROOT) return emptyList()
        return DeteccionDeRoot.motivos(RecolectorDeSenales.recoger(this))
    }

    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
}
