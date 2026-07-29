package com.arkiv.player.remote

/**
 * La barra avanza a 60fps aunque la foto del TV llegue cada 3-10s: se extrapola localmente.
 * Se usa el reloj del PROPIO celu (`receivedAtMs`), nunca el `at` del TV — así no hace falta que
 * los dos dispositivos tengan la hora sincronizada. Costo: la barra va hasta ~3s por detrás,
 * que en un capítulo de 24 min es 0,2%.
 */
object ExtrapolatedClock {

    /**
     * Sin refresco por este tiempo → mostrar el indicador de desconexión.
     *
     * **Invariante:** `WARN_MS` tiene que superar `NowPlayingPublisher.HEARTBEAT_MS` (10s, el
     * refresco más lento de un TV perfectamente sano) + `TvNowPlayingRepository.POLL_MS` (3s, la
     * fase del poll del celu) **con margen** para los viajes de red. Con 10s el umbral quedaba por
     * debajo de esa suma: en reproducción normal la antigüedad cruzaba el umbral cada ~20s y la
     * barra reemplazaba `12:34 / 24:01` por "Sin conexión" un par de segundos, mintiendo sobre un TV
     * que estaba bien. El test `ExtrapolatedClockTest` fija la relación.
     */
    const val WARN_MS = 25_000L

    /**
     * Sin refresco por este tiempo → ocultar la barra en vez de mentir sobre el TV.
     * Va bien por encima de [WARN_MS]: primero se avisa, y solo si el silencio persiste se oculta.
     */
    const val HIDE_MS = 45_000L

    fun positionAt(snapshot: TvSnapshot, nowMs: Long, scrubbing: Boolean): Long {
        val p = snapshot.nowPlaying
        if (scrubbing || p.state != TvPlaybackState.PLAYING) return p.positionMs
        val elapsed = (nowMs - snapshot.receivedAtMs).coerceAtLeast(0)
        val raw = p.positionMs + elapsed
        // durationMs == 0 significa "todavía no se sabe" (torrent recién abierto): no capar a cero.
        return if (p.durationMs > 0) raw.coerceAtMost(p.durationMs) else raw
    }

    fun isStale(snapshot: TvSnapshot, nowMs: Long, thresholdMs: Long): Boolean =
        nowMs - snapshot.receivedAtMs >= thresholdMs
}
