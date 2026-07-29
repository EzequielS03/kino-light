package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrapolatedClockTest {

    private fun foto(
        state: TvPlaybackState = TvPlaybackState.PLAYING,
        positionMs: Long = 10_000,
        durationMs: Long = 60_000,
    ) = TvNowPlaying(
        episodeId = "ep", itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = durationMs, state = state,
        hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `avanza con el reloj local mientras reproduce`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 1_000)
        assertEquals(13_000, ExtrapolatedClock.positionAt(snap, nowMs = 4_000, scrubbing = false))
    }

    @Test
    fun `no avanza en pausa`() {
        val snap = TvSnapshot(foto(state = TvPlaybackState.PAUSED), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = false))
    }

    @Test
    fun `no avanza mientras bufferea`() {
        val snap = TvSnapshot(foto(state = TvPlaybackState.BUFFERING), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = false))
    }

    @Test
    fun `se congela mientras se arrastra el slider`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = true))
    }

    @Test
    fun `se capa en la duracion`() {
        val snap = TvSnapshot(foto(positionMs = 59_000), receivedAtMs = 1_000)
        assertEquals(60_000, ExtrapolatedClock.positionAt(snap, nowMs = 100_000, scrubbing = false))
    }

    @Test
    fun `duracion desconocida no capa a cero`() {
        val snap = TvSnapshot(foto(durationMs = 0), receivedAtMs = 1_000)
        assertEquals(13_000, ExtrapolatedClock.positionAt(snap, nowMs = 4_000, scrubbing = false))
    }

    @Test
    fun `un reloj que retrocede no resta posicion`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 5_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 1_000, scrubbing = false))
    }

    @Test
    fun `rancia segun el umbral`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 0)
        assertFalse(ExtrapolatedClock.isStale(snap, nowMs = 24_999, thresholdMs = ExtrapolatedClock.WARN_MS))
        assertTrue(ExtrapolatedClock.isStale(snap, nowMs = 25_000, thresholdMs = ExtrapolatedClock.WARN_MS))
        assertFalse(ExtrapolatedClock.isStale(snap, nowMs = 44_999, thresholdMs = ExtrapolatedClock.HIDE_MS))
        assertTrue(ExtrapolatedClock.isStale(snap, nowMs = 45_000, thresholdMs = ExtrapolatedClock.HIDE_MS))
    }

    @Test
    fun `un TV sano nunca cruza el umbral de aviso`() {
        // La antigüedad de la foto en reproducción normal llega, como mucho, al latido del publisher
        // más la fase del poll del celu, más los viajes de red. Si WARN_MS no supera esa suma con
        // margen, la barra grita "Sin conexión" con el TV perfectamente vivo.
        val refrescoMasLento = NowPlayingPublisher.HEARTBEAT_MS + TvNowPlayingRepository.POLL_MS
        assertTrue(ExtrapolatedClock.WARN_MS > refrescoMasLento + MARGEN_RED_MS)
        assertTrue(ExtrapolatedClock.HIDE_MS > ExtrapolatedClock.WARN_MS)
    }

    private companion object {
        /** Margen para los viajes de red (celu ↔ Cloudflare ↔ PocketBase) en los dos sentidos. */
        const val MARGEN_RED_MS = 10_000L
    }
}
