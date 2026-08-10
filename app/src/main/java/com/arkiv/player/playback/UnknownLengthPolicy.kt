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
     * Cuánto dura la PELÍCULA, que no es lo mismo que lo que reporta el reproductor.
     *
     * Desde que magis se demuxea con avformat, libVLC por fin informa una duración — pero estando
     * dentro de una ventana informa solo lo que queda desde el corte. Reanudando en 1h14 de una
     * película de 2h06 dice 51 minutos, y la barra pasaría a medir el pedazo en vez de la obra
     * (medido en device: `dur=3101234ms` sobre una de `7560000ms`). Con ventana manda la duración
     * conocida; si no hubiera ninguna, se reconstruye sumándole el desfase al tramo.
     */
    fun duracionAbsolutaMs(lengthMs: Long, knownDurationMs: Long, baseOffsetMs: Long): Long = when {
        baseOffsetMs <= 0L -> effectiveDurationMs(lengthMs, knownDurationMs)
        knownDurationMs > 0L -> knownDurationMs
        lengthMs > 0L -> baseOffsetMs + lengthMs
        else -> 0L
    }

    /**
     * Si hace falta bajar las dos puntas del archivo para deducir la duración.
     *
     * Solo para TS, y solo si la fuente no la dijo ya. La sonda son dos viajes al CDN ANTES de que
     * arranque el video, y ese CDN tarda entre 0,2 s y 20 s en contestar un rango (medido): cuando
     * el gateway ya mandó la duración, sondear es riesgo puro a cambio de nada.
     */
    fun hayQueSondear(esTs: Boolean, duracionDeLaFuente: Long): Boolean =
        esTs && duracionDeLaFuente <= 0L

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
