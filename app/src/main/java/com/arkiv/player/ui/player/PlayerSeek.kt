package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A dónde cae un salto incremental de [deltaMs] partiendo de [base], acotado al contenido.
 *
 * [base] es el destino anterior sin confirmar, NO la posición del player: así la cuenta no depende
 * de si el salto anterior ya aterrizó. Con doce pulsaciones seguidas del D-pad, tomar la posición
 * real cada vez daría un resultado distinto según cuántos seeks alcanzaron a completarse.
 *
 * Una [duracionMs] de 0 o menos significa "todavía no se sabe" (arranque, o un directo): ahí solo
 * se corta por abajo, porque no hay tope que respetar.
 */
internal fun destinoDeSeek(base: Long, deltaMs: Long, duracionMs: Long): Long =
    (base + deltaMs).coerceIn(0L, if (duracionMs > 0) duracionMs else Long.MAX_VALUE)

/**
 * El estado de "moverse por la barra": el arrastre del slider y los saltos incrementales del D-pad,
 * los botones de ±10 s y el doble-tap.
 *
 * Los dos caminos comparten [arrastrando] y [posicionDeArrastre] a propósito: mientras cualquiera
 * de los dos está en curso, la barra y el reloj se pintan con el DESTINO y no con la posición real,
 * así se ve a dónde vas aunque el video siga en el fotograma viejo. Es el mismo comportamiento que
 * Netflix o Prime en TV.
 *
 * Quién dispara el seek de verdad se queda en la pantalla: depende del player activo (local o
 * Chromecast) y de si el cast está transcodificando, que no es asunto de este estado.
 */
@Stable
internal class EstadoDeSeek {
    /** Hay un movimiento en curso: arrastre del slider o ráfaga de saltos sin confirmar. */
    var arrastrando by mutableStateOf(false)
        private set

    /** Posición que se pinta mientras [arrastrando], en ms como float (lo que pide el Slider). */
    var posicionDeArrastre by mutableFloatStateOf(0f)
        private set

    /**
     * Destino acumulado de los saltos incrementales que todavía no se confirmó, o null si no hay
     * ninguno en curso. Cada pulsación nueva lo pisa y reinicia el debounce, así que sale un solo
     * seek real cuando dejaste de moverte.
     *
     * Cada pulsación era un `seekTo` real, o sea un Range request y su rebuffer: moverse dos
     * minutos con el D-pad de la TV son doce. En Magis cada rango puede tardar de 0,2 a 20 s, así
     * que la ráfaga competía contra sí misma.
     */
    var pendienteMs by mutableStateOf<Long?>(null)
        private set

    /**
     * Foco en la barra de progreso (TV): engrosa el track para que se note que está seleccionada.
     * Sin señal visual no se distinguía de estar en los botones, y como acá izquierda/derecha hacen
     * seek en vez de cambiar de botón, la navegación parecía errática.
     */
    var barraEnfocada by mutableStateOf(false)
        private set

    /** Qué posición mostrar: el destino mientras hay movimiento, la real cuando no. */
    fun posicionAMostrar(posicionRealMs: Long): Long =
        if (arrastrando) posicionDeArrastre.toLong() else posicionRealMs

    /** Ídem para el Slider, que trabaja en float. */
    fun valorDeLaBarra(posicionRealMs: Long): Float =
        if (arrastrando) posicionDeArrastre else posicionRealMs.toFloat()

    /**
     * Un salto incremental. Devuelve el destino acumulado; NO toca el player — de confirmarlo se
     * encarga el debounce de la pantalla.
     */
    fun saltar(deltaMs: Long, posicionActualMs: Long, duracionMs: Long): Long {
        val destino = destinoDeSeek(pendienteMs ?: posicionActualMs, deltaMs, duracionMs)
        pendienteMs = destino
        posicionDeArrastre = destino.toFloat()
        arrastrando = true
        return destino
    }

    /**
     * Agarrar la barra descarta cualquier salto incremental pendiente: si no, el debounce
     * dispararía DESPUÉS de soltar y te devolvería al destino de las flechas, pisando el arrastre.
     */
    fun arrastrarA(posicion: Float) {
        pendienteMs = null
        arrastrando = true
        posicionDeArrastre = posicion
    }

    /** Al soltar la barra. Devuelve a dónde hay que mandar el player. */
    fun soltar(): Long {
        arrastrando = false
        return posicionDeArrastre.toLong()
    }

    /**
     * Tras confirmar la ráfaga. El orden importa: quien llama ya dejó la posición real en el
     * destino, así que apagar [arrastrando] recién acá evita que la barra parpadee a la posición
     * vieja durante un frame.
     */
    fun confirmado() {
        pendienteMs = null
        arrastrando = false
    }

    fun cambioElFoco(enfocada: Boolean) {
        barraEnfocada = enfocada
    }
}

@Composable
internal fun rememberEstadoDeSeek(): EstadoDeSeek = remember { EstadoDeSeek() }
