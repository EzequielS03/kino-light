package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.arkiv.player.data.SettingsStore
import androidx.media3.common.Player
import com.arkiv.player.playback.VlcPlayer
import kotlinx.coroutines.delay

/** Pasos de velocidad y sus etiquetas, en el mismo orden: el índice vale para las dos listas. */
private val PASOS_DE_VELOCIDAD = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val ETIQUETAS_DE_VELOCIDAD = listOf("0.75×", "1×", "1.25×", "1.5×", "2×")

/** Ídem para el zoom nativo de VLC. 0f = "ajustar a la pantalla". */
private val PASOS_DE_ZOOM = listOf(0f, 1.15f, 1.35f)
private val ETIQUETAS_DE_ZOOM = listOf("Ajustar", "Zoom", "Zoom+")

/**
 * Tope del modo noche: el velo negro va ENCIMA del video, con opacidad nivel/[DIM_MAX_LEVEL] —
 * 0 = brillo normal (sin velo), [DIM_MAX_LEVEL] = negro total. No se usa el brillo real de la
 * pantalla porque eso también apagaría los controles, que es justo cuando hacen falta.
 */
internal const val DIM_MAX_LEVEL = 10

/** Cuánto queda el HUD del nivel de brillo tras una pulsación de botón. */
private const val HUD_DE_BRILLO_MS = 1200L

/** La velocidad temporal del long-press. */
private const val VELOCIDAD_ACELERADA = 2f

/**
 * Velocidad, zoom, modo noche y el HUD central de los gestos: todo lo que el usuario ajusta
 * ENCIMA de la reproducción sin cambiar qué se reproduce.
 *
 * Los tres ajustes comparten [hud] —el cartelito del centro— y por eso viajan juntos; cada uno por
 * su lado no tendría dónde poner ese estado. Velocidad y zoom van contra el reproductor local y no
 * se persisten (son de esta sesión); el modo noche sí, en [SettingsStore], porque sobrevive a
 * cerrar la app.
 *
 * [alInteractuar] es el `bump()` de la pantalla: cualquiera de estos ajustes cuenta como actividad
 * y reinicia el auto-ocultado de los controles.
 */
@Stable
internal class EstadoDeGestos(
    private val vlc: VlcPlayer,
    private val settings: SettingsStore,
    private val alInteractuar: () -> Unit,
) {
    private var indiceVelocidad by mutableIntStateOf(1) // arranca en 1×
    private var indiceZoom by mutableIntStateOf(0) // arranca en "Ajustar"

    /**
     * El ExoPlayer al mando, o null cuando reproduce VLC. Mismo trato que en [EstadoDePistas]: la
     * pantalla lo enchufa al crear el player y la velocidad va a uno o a otro según quién esté
     * sonando. Sin esto los gestos le hablaban siempre a VLC y en magis no hacían nada.
     */
    private var exoRef: Player? = null

    fun setExoPlayer(player: Player?) {
        exoRef = player
    }

    /** La velocidad va al que esté reproduciendo. */
    private fun aplicarVelocidad(rate: Float) {
        val exo = exoRef
        if (exo != null) exo.setPlaybackSpeed(rate) else vlc.setRate(rate)
    }

    private fun velocidadActual(): Float = exoRef?.playbackParameters?.speed ?: vlc.currentRate()

    /**
     * Volumen 0..100 del que esté sonando. En ExoPlayer es un factor 0..1, así que se convierte —y
     * se redondea, para que subir y bajar un paso vuelva al mismo número en vez de derivar.
     *
     * Sin esto el gesto le movía el volumen a un VlcPlayer que en magis y ditu está callado: el HUD
     * se movía en pantalla y no cambiaba nada.
     */
    fun volumenActual(): Int {
        val exo = exoRef ?: return vlc.vlcVolume()
        return Math.round(exo.volume * 100f).coerceIn(0, 100)
    }

    fun ponerVolumen(v: Int) {
        val exo = exoRef
        if (exo != null) exo.volume = (v / 100f).coerceIn(0f, 1f) else vlc.setVlcVolume(v)
    }

    /** Cartel central del gesto en curso (velocidad, seek, volumen, brillo), o null. */
    var hud by mutableStateOf<String?>(null)
        private set

    /**
     * Sube con cada pulsación de los botones de brillo, para que su HUD se borre solo. Va por tick
     * y no por el valor: en los topes el nivel no cambia, pero la pulsación igual muestra el HUD y
     * tiene que desvanecerse. Los gestos limpian el suyo a mano al soltar; un botón no tiene
     * "soltar", así que necesita temporizador propio.
     */
    var tickDelHudDeBrillo by mutableIntStateOf(0)
        private set

    /** Long-press sobre el video: 2× mientras se mantenga. */
    var acelerando by mutableStateOf(false)
        private set

    private var velocidadAntesDeAcelerar by mutableFloatStateOf(1f)

    val etiquetaDeVelocidad: String get() = ETIQUETAS_DE_VELOCIDAD[indiceVelocidad]
    val velocidadEsNormal: Boolean get() = indiceVelocidad == 1
    val etiquetaDeZoom: String get() = ETIQUETAS_DE_ZOOM[indiceZoom]
    val zoomEsAjustar: Boolean get() = indiceZoom == 0

    /**
     * El zoom que tiene que aplicar quien dibuja el video, o 1 si no hay que tocar nada.
     *
     * Solo lo mira ExoPlayer: libVLC escala por su cuenta con `setScale` —es una operación del
     * propio motor de video— y ahí esto no se usa. ExoPlayer no tiene equivalente, así que su
     * superficie se agranda con un `scale` de Compose, que da el mismo recorte: el contenedor no
     * cambia de tamaño y lo que se sale queda fuera.
     */
    val zoomParaExo: Float get() = if (exoRef != null) PASOS_DE_ZOOM[indiceZoom].let { if (it <= 0f) 1f else it } else 1f

    /** Velocidad y zoom son cíclicos: cada toque pasa al siguiente paso y vuelve al principio. */
    fun siguienteVelocidad() {
        indiceVelocidad = (indiceVelocidad + 1) % PASOS_DE_VELOCIDAD.size
        aplicarVelocidad(PASOS_DE_VELOCIDAD[indiceVelocidad])
        alInteractuar()
    }

    fun siguienteZoom() {
        indiceZoom = (indiceZoom + 1) % PASOS_DE_ZOOM.size
        // Con ExoPlayer no hay a quién decírselo: lo lee [zoomParaExo] quien dibuja el video.
        if (exoRef == null) vlc.setScale(PASOS_DE_ZOOM[indiceZoom])
        alInteractuar()
    }

    /**
     * Sube (delta<0) o baja (delta>0) un paso el velo del modo noche, acotado a [0, DIM_MAX_LEVEL].
     * En los extremos la pulsación no cambia nada, pero igual muestra el HUD y reinicia el
     * auto-ocultado: los botones NO se deshabilitan a propósito (en TV un botón deshabilitado no
     * recibe foco y rompería la cadena del D-pad justo al llegar al tope).
     */
    fun pasoDeBrillo(delta: Int, nivelActual: Int) {
        val nuevo = (nivelActual + delta).coerceIn(0, DIM_MAX_LEVEL)
        if (nuevo != nivelActual) settings.setDimLevel(nuevo)
        hud = if (nuevo == 0) "☀ Normal" else "🌙 ${nuevo * (100 / DIM_MAX_LEVEL)}%"
        tickDelHudDeBrillo++
        alInteractuar()
    }

    fun mostrarHud(texto: String) {
        hud = texto
    }

    fun limpiarHud() {
        hud = null
    }

    /** Long-press: guarda la velocidad de antes para poder devolverla al soltar. */
    fun empezarAAcelerar() {
        velocidadAntesDeAcelerar = velocidadActual()
        aplicarVelocidad(VELOCIDAD_ACELERADA)
        acelerando = true
        hud = "⏩ ${VELOCIDAD_ACELERADA.toInt()}×"
    }

    /** Al soltar. Devuelve si de verdad estábamos acelerando, para que quien llama sepa si actuó. */
    fun terminarDeAcelerar(): Boolean {
        if (!acelerando) return false
        acelerando = false
        aplicarVelocidad(velocidadAntesDeAcelerar)
        hud = null
        return true
    }
}

@Composable
internal fun rememberEstadoDeGestos(
    vlc: VlcPlayer,
    settings: SettingsStore,
    alInteractuar: () -> Unit,
): EstadoDeGestos {
    // rememberUpdatedState para que el holder no se quede con la primera versión del callback: se
    // crea una sola vez, pero `bump()` se recompone con el resto de la pantalla.
    val ultimo by rememberUpdatedState(alInteractuar)
    return remember(vlc, settings) { EstadoDeGestos(vlc, settings) { ultimo() } }
}

/** Borra solo el HUD del nivel de brillo, un rato después de la última pulsación. */
@Composable
internal fun EfectoDelHudDeBrillo(estado: EstadoDeGestos) {
    LaunchedEffect(estado.tickDelHudDeBrillo) {
        if (estado.tickDelHudDeBrillo > 0) {
            delay(HUD_DE_BRILLO_MS)
            estado.limpiarHud()
        }
    }
}
