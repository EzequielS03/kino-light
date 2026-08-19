package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkiv.player.torrent.TorrentProgress

/**
 * El espejo del reproductor: lo que la pantalla sabe de lo que está sonando —posición, duración, si
 * reproduce, si bufferea— más el progreso de descarga que se pinta encima.
 *
 * Nada de esto se decide acá: son valores que van DETRÁS del player activo (el local o el
 * Chromecast), escritos por su listener y por el sondeo de la pantalla, que son los dos que saben
 * cuándo mirar. Este objeto solo los guarda juntos, porque juntos es como los lee toda la interfaz.
 *
 * Están agrupados a propósito y no sueltos en el scope: son las cuatro variables que casi cualquier
 * pieza del overlay necesita, así que cada composable que se quiera extraer las pedía de a una.
 */
@Stable
internal class EspejoDelPlayer {
    /** Posición del CONTENIDO (no la del receptor, que con ventana lleva otro origen). */
    var posicionMs by mutableLongStateOf(0L)
        private set

    /** Duración del contenido, o 0 mientras no se sepa (arranque, o un directo). */
    var duracionMs by mutableLongStateOf(0L)
        private set

    var reproduciendo by mutableStateOf(false)
        private set

    /** Arranca en true: al abrir la pantalla todavía no hay nada listo. */
    var buffereando by mutableStateOf(true)
        private set

    /**
     * La INTENCIÓN de reproducir (`playWhenReady`), que no es lo mismo que [reproduciendo]: esa
     * también se cae en cada rebuffer. Se sigue aparte porque es lo que distingue "el usuario
     * pausó" de "el torrent se quedó sin datos un segundo" (ver el efecto de captura al pausar).
     */
    var quiereReproducir by mutableStateOf(false)
        private set

    /**
     * Fracción [0..1] ya descargada/buffereada por delante, para el tramo gris claro de la barra.
     * Torrent: % de descarga del engine; archive: % del archivo cacheado por el proxy.
     */
    var fraccionBuffereada by mutableFloatStateOf(0f)
        private set

    /** Estado de descarga del torrent, para el overlay de progreso. Null en el resto de fuentes. */
    var descarga by mutableStateOf<TorrentProgress?>(null)
        private set

    /** Los tres valores que el listener del player publica de una. */
    fun sincronizarTransporte(buffereando: Boolean, reproduciendo: Boolean, quiereReproducir: Boolean) {
        this.buffereando = buffereando
        this.reproduciendo = reproduciendo
        this.quiereReproducir = quiereReproducir
    }

    fun cambioElBuffering(valor: Boolean) {
        buffereando = valor
    }

    fun cambioElPlaying(valor: Boolean) {
        reproduciendo = valor
    }

    fun cambioLaIntencion(valor: Boolean) {
        quiereReproducir = valor
    }

    /**
     * Nueva lectura del reloj. La duración solo se pisa cuando se conoce: un 0 pasajero del player
     * borraría el total de la barra a mitad de la reproducción.
     */
    fun leyoElReloj(posicionMs: Long, duracionMs: Long) {
        this.posicionMs = posicionMs
        if (duracionMs > 0) this.duracionMs = duracionMs
    }

    /** Tras pedir un seek: adelanta la posición sin esperar al player, para que la barra no salte. */
    fun saltoA(posicionMs: Long) {
        this.posicionMs = posicionMs
    }

    /** Al cargar un ítem nuevo: el reloj del anterior no describe nada. */
    fun reiniciarElReloj() {
        posicionMs = 0L
        duracionMs = 0L
    }

    fun leyoElBuffer(fraccion: Float) {
        fraccionBuffereada = fraccion
    }

    fun leyoLaDescarga(progreso: TorrentProgress?) {
        descarga = progreso
    }
}

@Composable
internal fun rememberEspejoDelPlayer(): EspejoDelPlayer = remember { EspejoDelPlayer() }
