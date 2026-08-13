package com.arkiv.player.playback

/**
 * Si hay que tapar la pantalla mientras se espera la PRIMERA imagen de un media.
 *
 * Existe por el "arranca negro y con sonido": libVLC llega a `Playing` y suelta el audio en cuanto
 * tiene con qué, pero la primera imagen puede tardar bastante más —el decodificador HEVC del Fire
 * Stick tiene que arrancar— y en ese hueco `playbackState` ya NO es `STATE_BUFFERING`. La pantalla
 * se quedaba entonces sin spinner Y sin imagen: negro pelado con audio, que desde el sillón se ve
 * igual que un cuelgue. Medido el 2026-08-13 en el Fire TV, entre la primera imagen y el video
 * caminando llegó a haber 8,5 s.
 *
 * Es una función pura porque acá el modo de fallar es dejar el spinner puesto ENCIMA de un video que
 * sí estaba reproduciendo, y eso es peor que el negro que vino a tapar. Los bordes se fijan por test.
 */
object EsperaDePrimeraImagen {

    /**
     * Cuánto se espera como máximo.
     *
     * Más largo que el rescate hardware→software de [VlcPlayer] a propósito: el caso que este
     * spinner tapa es justamente ese —los segundos de negro esperando al decodificador y la recarga
     * en software que sí da imagen—, así que cortar antes sería irse en el peor momento.
     */
    const val TOPE_MS = 30_000L

    /**
     * @param cargadoHaceMs desde que se cargó el media (negativo = todavía no hay ninguno).
     * @param huboImagen si este media ya dio alguna imagen.
     * @param hayVideoAhora si libVLC está pintando en este instante.
     * @param pistasDeVideo cuántas pistas de video declara el media (0 = todavía no sabe, o no hay).
     * @param pistasDeAudio ídem para audio.
     */
    fun hayQueEsperar(
        cargadoHaceMs: Long,
        huboImagen: Boolean,
        hayVideoAhora: Boolean,
        pistasDeVideo: Int,
        pistasDeAudio: Int,
    ): Boolean {
        if (cargadoHaceMs < 0L) return false          // no hay media cargado
        if (huboImagen || hayVideoAhora) return false // ya hubo imagen: esto no es asunto suyo
        if (cargadoHaceMs > TOPE_MS) return false     // pasó el tope: mejor un negro que un spinner eterno
        // Contenido SIN VIDEO: no hay imagen que esperar.
        //
        // Se pregunta por "hay audio y no hay video" y no por "la lista está vacía", y esa
        // diferencia es todo el asunto: al abrir, TODAS las cuentas son 0 —y eso no significa que no
        // haya video, significa que libVLC todavía no sabe—. Es justo el instante en el que este
        // spinner tiene que estar puesto.
        if (pistasDeVideo <= 0 && pistasDeAudio > 0) return false
        return true
    }
}
