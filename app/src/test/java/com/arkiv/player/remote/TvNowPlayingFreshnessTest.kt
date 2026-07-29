package com.arkiv.player.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cota de vida por el reloj del TV: un `devices.nowPlaying` que quedó publicado por un TV apagado
 * no debe pintar el miniplayer como si el capítulo siguiera corriendo.
 */
class TvNowPlayingFreshnessTest {

    private val ahora = java.time.Instant.parse("2026-07-27T18:00:00Z").toEpochMilli()

    private fun at(offsetMs: Long): String =
        java.time.Instant.ofEpochMilli(ahora + offsetMs).toString()

    @Test
    fun `una foto recien publicada esta viva`() {
        assertFalse(TvNowPlayingRepository.isDeadByTvClock(at(-2_000), ahora))
    }

    @Test
    fun `una foto justo en el limite sigue viva`() {
        assertFalse(TvNowPlayingRepository.isDeadByTvClock(at(-TvNowPlayingRepository.MAX_AGE_MS), ahora))
    }

    @Test
    fun `una foto mas vieja que la cota esta muerta`() {
        assertTrue(TvNowPlayingRepository.isDeadByTvClock(at(-TvNowPlayingRepository.MAX_AGE_MS - 1), ahora))
        assertTrue(TvNowPlayingRepository.isDeadByTvClock(at(-30 * 24 * 3_600_000L), ahora))
    }

    @Test
    fun `la cota deja pasar de sobra el umbral de ocultado`() {
        // La cota es la red de seguridad del arranque en frío, no el detector de "TV que se cayó
        // recién": de eso se encarga HIDE_MS sobre receivedAtMs. Si la cota cayera por debajo de
        // HIDE_MS, mataría fotos de un TV sano antes de que la barra pudiera envejecer.
        assertTrue(TvNowPlayingRepository.MAX_AGE_MS > ExtrapolatedClock.HIDE_MS)
    }

    @Test
    fun `un at ilegible no descarta la foto`() {
        // Defensivo: si el formato cambiara, preferimos un miniplayer que funciona a uno que nunca
        // aparece.
        assertFalse(TvNowPlayingRepository.isDeadByTvClock("", ahora))
        assertFalse(TvNowPlayingRepository.isDeadByTvClock("ayer por la tarde", ahora))
        assertFalse(TvNowPlayingRepository.isDeadByTvClock("2026-07-27 18:00:00", ahora))
    }

    @Test
    fun `un reloj del TV adelantado no mata la foto`() {
        assertFalse(TvNowPlayingRepository.isDeadByTvClock(at(60_000), ahora))
    }
}
