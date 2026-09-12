package com.arkiv.player.playback

/**
 * Si hay que tapar la pantalla mientras se espera la PRIMERA imagen de un media.
 *
 * Existe por el "arranca negro y con sonido": libVLC used to reach `Playing` and let the audio go
 * as soon as it had something to play, but la primera imagen puede tardar bastante más —el
 * decodificador HEVC del Fire Stick tiene que arrancar— y en ese hueco `playbackState` ya NO es
 * `STATE_BUFFERING`. La pantalla
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
     * Más largo que el rescate hardware→software de [DecoderWatchdog] a propósito: el caso que este
     * spinner tapa es justamente ese —los segundos de negro esperando al decodificador y la recarga
     * en software que sí da imagen—, así que cortar antes sería irse en el peor momento.
     */
    const val TOPE_MS = 30_000L

    /**
     * Cuánto puede aterrizar ANTES del punto pedido y seguir contando como "llegó".
     *
     * El salto cae en el keyframe anterior al punto pedido, así que exigir `posición >= pedido`
     * dejaría el spinner puesto sobre un video que ya arrancó bien. Medido en el Fire TV el
     * 2026-08-14: se pidió 1327653 ms y aterrizó en 1327116, o sea 537 ms antes. 10 s cubre con
     * holgura cualquier tamaño de GOP razonable sin llegar a tapar un salto que salió mal.
     */
    const val MARGEN_DE_ATERRIZAJE_MS = 10_000L

    /**
     * @param cargadoHaceMs desde que se cargó el media (negativo = todavía no hay ninguno).
     * @param huboImagen si este media ya dio alguna imagen.
     * @param hayVideoAhora si el player está pintando en este instante (libVLC-era parameter; the
     *   local ExoPlayer path always passes `false` here and answers through [huboImagen]/
     *   [pistasDeVideo] instead).
     * @param pistasDeVideo cuántas pistas de video declara el media (0 = todavía no sabe, o no hay).
     * @param pistasDeAudio ídem para audio.
     * @param pedidoMs a qué punto se pidió reanudar (0 = no se pidió ninguno).
     * @param posicionMs dónde va el reloj del reproductor ahora.
     */
    @Suppress("LongParameterList")
    fun hayQueEsperar(
        cargadoHaceMs: Long,
        huboImagen: Boolean,
        hayVideoAhora: Boolean,
        pistasDeVideo: Int,
        pistasDeAudio: Int,
        pedidoMs: Long = 0L,
        posicionMs: Long = 0L,
    ): Boolean {
        if (cargadoHaceMs < 0L) return false          // no hay media cargado
        if (cargadoHaceMs > TOPE_MS) return false     // pasó el tope: mejor un negro que un spinner eterno
        // REANUDACIÓN TODAVÍA EN CAMINO: hay imagen, pero NO es la del punto que se pidió.
        //
        // Medido en el Fire TV el 2026-08-14: `:start-time` no abría en el minuto guardado. VLC
        // opened at byte 0, pulled a frame from there (`⏱ abrió en 1025ms`) and only THEN jumped —el
        // `PAUSA (buffering) en pos=0ms` que sigue dura 1,5 s—. Esa primera imagen prendía
        // `huboImagen` y apagaba el spinner, así que el usuario se quedaba mirando un fotograma
        // congelado DEL PRINCIPIO, con el audio ya sonando, hasta que el salto aterrizaba. Se ve
        // igual que un cuelgue y encima muestra contenido equivocado.
        //
        // Va ANTES del corte por "ya hubo imagen" justamente porque el caso es "hubo imagen, pero
        // no la que corresponde". El reproductor original tapa este mismo hueco: su salto prende el
        // spinner en el instante en que lo pide.
        if (pedidoMs > 0L && posicionMs < pedidoMs - MARGEN_DE_ATERRIZAJE_MS) return true
        if (huboImagen || hayVideoAhora) return false // ya hubo imagen: esto no es asunto suyo
        // Contenido SIN VIDEO: no hay imagen que esperar.
        //
        // Se pregunta por "hay audio y no hay video" y no por "la lista está vacía", y esa
        // diferencia es todo el asunto: al abrir, TODAS las cuentas son 0 —y eso no significa que no
        // haya video, significa que el player todavía no sabe—. Es justo el instante en el que este
        // spinner tiene que estar puesto.
        if (pistasDeVideo <= 0 && pistasDeAudio > 0) return false
        return true
    }
}
