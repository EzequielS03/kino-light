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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.seguridad.DeteccionDeRoot
import com.arkiv.player.seguridad.FirmaDelApk
import com.arkiv.player.seguridad.PantallaBloqueada
import com.arkiv.player.seguridad.RecolectorDeSenales
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.entrada.EntradaViewModel
import com.arkiv.player.ui.entrada.EstadoDeEntrada
import com.arkiv.player.ui.entrada.PantallaDeEntrada
import com.arkiv.player.ui.entrada.estadoDeEntrada
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
        if (isTv) com.arkiv.player.tvservice.TvConnectionService.start(this)
        if (isTv) com.arkiv.player.tvservice.TvKeepAliveWorker.schedule(this)
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
                        // Gate de sesión (Task 4), al lado del de integridad que ya filtró antes de
                        // llegar acá: sin sesión no se arma ni Room, ni el sync, ni las filas del
                        // home. `sesionEstado` es una lectura en memoria (SesionDePersona.estado),
                        // nunca un pedido de red -ver el KDoc de EntradaViewModel-, así que con
                        // sesión guardada este `when` no agrega ninguna espera al arranque.
                        val entradaVm: EntradaViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { EntradaViewModel(graph.sesionDePersona, graph.accountManager) }
                            },
                        )
                        val sesionEstado by entradaVm.sesionEstado.collectAsState()
                        val aviso by entradaVm.aviso.collectAsState()
                        when (estadoDeEntrada(sesionEstado, aviso)) {
                            is EstadoDeEntrada.Adentro -> {
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
                            is EstadoDeEntrada.Entrada -> {
                                // En la TV la puerta principal sigue siendo parear con el celular
                                // (QR, Task 5) -más rápido y no expone la contraseña en el living-,
                                // pero desde la Task 9 hay una tercera pestaña para entrar/registrarse
                                // con el teclado en pantalla, para quien no tiene un Android a mano.
                                // El celular sigue usando el formulario de siempre.
                                if (isTv) {
                                    com.arkiv.player.ui.tv.TvPantallaDeEntrada(graph.pairing, entradaVm.account)
                                } else {
                                    PantallaDeEntrada(entradaVm)
                                }
                            }
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
            pendingEpisode = NowPlaying.episodeId
        }
    }

    /**
     * Por qué este aparato no puede ejecutar la app. Vacío = puede.
     *
     * Dos controles, en el orden en que importan:
     *
     * 1. **Firma del APK.** Si no la comprobamos, el bloqueo por root no vale nada: se decompila,
     *    se le quita y se vuelve a firmar. Solo se exige en release (ver [FirmaDelApk]).
     * 2. **Root.** Ver [DeteccionDeRoot], que también explica sus límites.
     */
    private fun motivosParaNoArrancar(): List<String> {
        if (!FirmaDelApk.esNuestra(this, BuildConfig.DEBUG)) {
            return listOf("el APK no está firmado con el certificado de Kino")
        }
        return DeteccionDeRoot.motivos(RecolectorDeSenales.recoger(this))
    }

    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
}
