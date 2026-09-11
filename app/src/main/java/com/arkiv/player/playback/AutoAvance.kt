package com.arkiv.player.playback

/**
 * When the end of playback really is the end of the chapter.
 *
 * libVLC fires the same signal (`EndReached`) when the chapter finished as when the stream ran out
 * of data: Magis's CDN going quiet, or -- for a source removed in this branch's pruning -- a
 * torrent with no peers or a web URL that expired. The screen uses that signal to move to the next
 * chapter, so without telling the two apart a network hiccup mid-chapter turned into a skip -- and
 * the next chapter could stall the same way, cascading through the whole series.
 *
 * Vive aparte de la pantalla para poder probarse, igual que [MediaReusePolicy].
 */
object AutoAvance {

    /** Margen final que cuenta como "se acabó": los créditos y el último frame casi nunca dejan la
     * posición pegada a la duración exacta. */
    private const val MARGEN_FINAL_MS = 90_000L

    /** Sin duración conocida, lo mínimo que tuvo que sonar para no confundir un fin con un fallo. */
    private const val MINIMO_SIN_DURACION_MS = 60_000L

    /**
     * @param positionMs última posición conocida por la pantalla (la sondea cada 0,5 s). No se lee
     *   del player en el momento del aviso a propósito: al terminar, VLC puede devolver 0.
     * @param durationMs duración del capítulo, o 0 si no se pudo sondear (pasa en magis, donde la
     *   sonda a veces pierde contra el CDN — ahí se decide sólo por lo que llegó a sonar).
     */
    fun esFinDeCapitulo(positionMs: Long, durationMs: Long): Boolean =
        if (durationMs > 0) {
            positionMs >= durationMs - MARGEN_FINAL_MS
        } else {
            positionMs >= MINIMO_SIN_DURACION_MS
        }
}
