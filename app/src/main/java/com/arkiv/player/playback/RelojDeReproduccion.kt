package com.arkiv.player.playback

/**
 * Si entre dos sondeos del watcher el reloj del reproductor avanzó DE VERDAD.
 *
 * Existe por un bug que se metió el 2026-08-14 al sacar el aviso de "⏱ CORRIENDO" de adentro del
 * bloque de fin-de-pausa —para que también saliera en los arranques que nunca se pausan—: el
 * watcher guarda la última posición vista en `-1` mientras no observó ninguna, así que la PRIMERA
 * lectura, con el reloj todavía en 0, daba `0 != -1` y se leía como avance. Medido en el Fire TV,
 * las seis reproducciones de esa tanda reportaron el video "corriendo" ANTES de tener imagen:
 *
 * ```
 * 10:22:01.398  ⏱ CORRIENDO a los 439ms de loadMedia (primera imagen a los -1ms)
 * 10:22:03.198  ⏱ abrió en 2239ms (loadMedia → primera imagen)
 * ```
 *
 * Que es imposible, y dejaba inservible justo la métrica que se había tocado para poder comparar
 * arranques entre sí.
 */
object RelojDeReproduccion {

    /**
     * [anterior] es la última posición observada, con `-1` para "todavía ninguna".
     *
     * Se exige movimiento HACIA ADELANTE desde una observación real. Retroceder es un salto hacia
     * atrás, no el arranque: contarlo pondría el cronómetro en un momento que no tiene nada que ver.
     */
    fun avanzoDeVerdad(anterior: Long, ahora: Long): Boolean = anterior >= 0L && ahora > anterior
}
