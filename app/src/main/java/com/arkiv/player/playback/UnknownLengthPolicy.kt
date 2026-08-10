package com.arkiv.player.playback

/**
 * Qué hacer cuando libVLC no sabe cuánto dura lo que reproduce.
 *
 * Pasa con MPEG-TS servido por HTTP (magis): VLC solo deduce la duración de un TS sondeando el
 * final del archivo, y eso solo lo hace con acceso de lectura rápida (archivo local). Ver
 * [TsDurationProbe]. Sin duración:
 *  - la barra se pinta llena y la derecha marca 00:00,
 *  - `mediaPlayer.time = ms` se ignora (no hay contra qué mapear el tiempo),
 *  - pero `mediaPlayer.position = fracción` SÍ funciona: VLC salta por BYTE y el PCR del sitio le
 *    devuelve el tiempo correcto (verificado: pedir 0,5 cayó en 5058 s de 10 144 reales).
 *
 * Así que con la duración sondeada aparte, se puede pintar la barra y buscar por fracción.
 */
object UnknownLengthPolicy {

    /** La duración que hay que reportar: la de VLC si la sabe, si no la sondeada (0 = nadie sabe). */
    fun effectiveDurationMs(lengthMs: Long, knownDurationMs: Long): Long =
        if (lengthMs > 0) lengthMs else knownDurationMs.coerceAtLeast(0L)

    /**
     * Fracción [0..1] a la que hay que mover a VLC para llegar a [targetMs], o null si hay que
     * buscar por tiempo (lo normal: VLC conoce la duración, o no la conoce nadie y no hay nada
     * mejor que intentarlo por tiempo).
     */
    fun seekFraction(targetMs: Long, lengthMs: Long, knownDurationMs: Long): Float? {
        if (lengthMs > 0 || knownDurationMs <= 0) return null
        return (targetMs.toFloat() / knownDurationMs).coerceIn(0f, 1f)
    }
}
