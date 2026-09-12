package com.arkiv.player.playback

/**
 * Whether the screen needs to stay covered while waiting for a media's FIRST frame.
 *
 * Exists because of "starts black with sound": libVLC used to reach `Playing` and let the audio
 * go as soon as it had something to play, but the first frame can take a lot longer -- the Fire
 * Stick's HEVC decoder has to spin up -- and in that gap `playbackState` is already NOT
 * `STATE_BUFFERING`. The screen would then sit with no spinner AND no image: plain black with
 * audio, which from the couch looks exactly like a freeze. Measured on 2026-08-13 on the Fire TV,
 * the gap between the first frame and the video actually moving reached 8.5s.
 *
 * It's a pure function because here the failure mode is leaving the spinner sitting ON TOP of a
 * video that was already playing, and that's worse than the black screen it came to cover. The
 * edges are pinned by test.
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
     * @param cargadoHaceMs since the media was loaded (negative = none loaded yet).
     * @param huboImagen whether this media has already produced any frame.
     * @param pistasDeVideo how many video tracks the media declares (0 = doesn't know yet, or none).
     * @param pistasDeAudio same, for audio.
     * @param pedidoMs the point resume was requested at (0 = none was requested).
     * @param posicionMs where the player's clock is right now.
     */
    @Suppress("LongParameterList")
    fun hayQueEsperar(
        cargadoHaceMs: Long,
        huboImagen: Boolean,
        pistasDeVideo: Int,
        pistasDeAudio: Int,
        pedidoMs: Long = 0L,
        posicionMs: Long = 0L,
    ): Boolean {
        if (cargadoHaceMs < 0L) return false          // no hay media cargado
        if (cargadoHaceMs > TOPE_MS) return false     // pasó el tope: mejor un negro que un spinner eterno
        // RESUME STILL LANDING: there IS a frame, but it's NOT the one for the requested point.
        //
        // Measured on the Fire TV on 2026-08-14: `:start-time` didn't open at the saved minute.
        // VLC opened at byte 0, pulled a frame from there (`⏱ abrió en 1025ms`) and only THEN
        // jumped -- the `PAUSA (buffering) en pos=0ms` that follows lasts 1.5s. That first frame
        // turned on `huboImagen` and switched off the spinner, so the user was left staring at a
        // frozen frame FROM THE BEGINNING, with audio already playing, until the seek landed. It
        // looks exactly like a freeze and shows the wrong content on top of it.
        //
        // It comes BEFORE the "there was already a frame" cutoff precisely because the case is
        // "there was a frame, but not the right one". The original player covers this same gap:
        // its seek turns the spinner on the instant it's requested.
        if (pedidoMs > 0L && posicionMs < pedidoMs - MARGEN_DE_ATERRIZAJE_MS) return true
        if (huboImagen) return false                  // ya hubo imagen: esto no es asunto suyo
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
