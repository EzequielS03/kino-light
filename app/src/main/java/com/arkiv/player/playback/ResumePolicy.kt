package com.arkiv.player.playback

/**
 * Desde dónde reanudar un episodio con lo que quedó guardado.
 *
 * Vive aparte del ViewModel para poder probarse: es la regla que decide si al abrir algo aparecés
 * donde lo dejaste o de vuelta en cero, y eso no debería depender de nada de Android.
 */
object ResumePolicy {

    /** Debajo de esto no se considera que hayas empezado a ver: abrir y arrepentirse no deja marca. */
    private const val MINIMO_MS = 10_000L

    /** A partir de acá se considera visto; reanudar ahí te dejaría en los créditos. */
    private const val CASI_TERMINADO = 0.9

    /**
     * @param savedPositionMs posición guardada.
     * @param savedDurationMs duración guardada, o 0 si no se conoce.
     *
     * Deliberately does NOT check the download's state. With torrent (source removed in this
     * branch's pruning) it was needed: without it the position was discarded if that zone hadn't
     * downloaded yet, and since it never had in a freshly-opened magnet, playback ALWAYS started
     * from the beginning until the streaming server learned to anchor the sequential download at
     * the requested point. Magis and Ditu don't have that problem -they're pure streaming, there's
     * no "zone not downloaded yet" to check-, so today the parameter simply isn't needed.
     */
    fun startPosition(savedPositionMs: Long, savedDurationMs: Long): Long {
        if (savedPositionMs <= MINIMO_MS) return 0L
        if (savedDurationMs > 0L && savedPositionMs >= savedDurationMs * CASI_TERMINADO) return 0L
        return savedPositionMs
    }
}
