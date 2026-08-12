package com.arkiv.player.data

/**
 * Cuándo un capítulo cuenta como visto.
 *
 * Vive acá y no inline en `savePlayback` porque no es un detalle de persistencia: es la regla que
 * decide DOS cosas visibles a la vez —si el capítulo sigue en "Continuar viendo" y si el detalle de
 * la serie te ofrece este capítulo o el siguiente ([ItemDetail.resumeEpisode])—, y porque una regla
 * de producto sin test es la que se rompe sin que nadie se entere.
 *
 * ### Por qué "cuánto falta" y no "qué fracción llevo"
 *
 * La regla anterior era `positionMs >= duracion * 0.6`. Reportado en device (2026-08-11) y medido
 * ahí mismo: un capítulo de 24 minutos quedaba marcado visto en el minuto 15 y **desaparecía del
 * home mientras se lo estaba mirando**, con nueve minutos por delante; y el detalle pasaba a
 * ofrecer el capítulo siguiente, que se siente como haber perdido el avance.
 *
 * Un porcentaje no significa lo mismo en contenidos de distinta duración: al 90% de una película de
 * dos horas todavía faltan doce minutos. Tres minutos, en cambio, son tres minutos en los dos
 * casos, y es más o menos lo que dura un ending o unos créditos.
 */
object UmbralDeVisto {

    /** Cuánto puede faltar para el final y ya contar como visto. */
    const val RESTANTE_MS = 3 * 60 * 1000L

    /**
     * Piso porcentual, que NO es redundante: sin él, cualquier cosa que dure menos de
     * [RESTANTE_MS] nacería vista (la resta da negativo y la posición 0 ya lo supera). Además
     * evita que en contenidos cortos "tres minutos" sea casi todo el contenido.
     */
    const val FRACCION_MINIMA = 0.9

    /**
     * Manda el umbral MÁS TARDÍO de los dos, que es el conservador: marcar visto de más es
     * exactamente el bug que esto arregla.
     *
     * En la práctica: por debajo de ~30 min de duración manda la fracción (un capítulo de 24 min se
     * marca a los 21:36); por encima manda el resto (una película de 2 h, a las 1:57).
     */
    fun yaLoViste(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val porLoQueFalta = durationMs - RESTANTE_MS
        val porFraccion = (durationMs * FRACCION_MINIMA).toLong()
        return positionMs >= maxOf(porLoQueFalta, porFraccion)
    }
}
