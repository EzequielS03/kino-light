package com.arkiv.player.playback

/**
 * Cuándo el fin de la reproducción es de verdad el fin del capítulo.
 *
 * libVLC avisa lo mismo (`EndReached`) cuando el capítulo se acabó que cuando el stream se quedó
 * sin datos: el CDN de magis que deja de responder, un torrent sin peers, una URL web que caducó.
 * La pantalla usa ese aviso para pasar al capítulo siguiente, así que sin distinguirlos un tirón de
 * red a mitad del capítulo se convertía en un salto —y el siguiente podía cortarse igual, en
 * cascada por toda la serie—.
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
