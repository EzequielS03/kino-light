package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingCodecTest {

    private val sample = TvNowPlaying(
        episodeId = "ep-1",
        itemId = "item-1",
        kind = "TORRENT",
        title = "Dandadan",
        subtitle = "T1E5 · El abuelo turbo",
        posterUrl = "https://ejemplo/p.jpg",
        positionMs = 754_000,
        durationMs = 1_440_000,
        state = TvPlaybackState.PLAYING,
        hasNext = true,
        hasPrev = false,
        at = "2026-07-27T18:04:12Z",
    )

    @Test
    fun `ida y vuelta preserva todos los campos`() {
        assertEquals(sample, NowPlayingCodec.decode(NowPlayingCodec.encode(sample)))
    }

    @Test
    fun `null o vacio significa que no hay nada reproduciendose`() {
        assertNull(NowPlayingCodec.decode(null))
        assertNull(NowPlayingCodec.decode(""))
        assertNull(NowPlayingCodec.decode("null"))
    }

    @Test
    fun `json invalido no lanza`() {
        assertNull(NowPlayingCodec.decode("{esto no es json"))
    }

    @Test
    fun `payload de una version futura se ignora`() {
        val futuro = NowPlayingCodec.encode(sample).replace("\"v\":1", "\"v\":2")
        assertNull(NowPlayingCodec.decode(futuro))
    }

    @Test
    fun `campos ausentes caen a valores por defecto`() {
        val minimo = """{"v":1,"episodeId":"ep-9"}"""
        val d = NowPlayingCodec.decode(minimo)!!
        assertEquals("ep-9", d.episodeId)
        assertEquals("", d.title)
        assertEquals(0L, d.positionMs)
        assertEquals(TvPlaybackState.PAUSED, d.state)
        assertEquals(false, d.hasNext)
        // Un TV con el build viejo no manda "startedAt": tiene que decodificar a 0, no fallar (ver
        // el comentario junto a `put("startedAt", ...)` en encode() sobre por qué VERSION no sube).
        assertEquals(0L, d.startedAtMs)
    }

    @Test
    fun `startedAtMs viaja en la ida y vuelta`() {
        val conArranque = sample.copy(startedAtMs = 123_456_789L)
        assertEquals(conArranque, NowPlayingCodec.decode(NowPlayingCodec.encode(conArranque)))
    }

    @Test
    fun `sin episodeId no hay foto valida`() {
        assertNull(NowPlayingCodec.decode("""{"v":1,"title":"x"}"""))
    }

    @Test
    fun `eventKey ignora la posicion y el timestamp`() {
        val avanzado = sample.copy(positionMs = 999_000, at = "2026-07-27T18:05:00Z")
        assertEquals(NowPlayingCodec.eventKey(sample), NowPlayingCodec.eventKey(avanzado))
    }

    @Test
    fun `eventKey cambia con el estado el episodio y la duracion`() {
        val base = NowPlayingCodec.eventKey(sample)
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(state = TvPlaybackState.PAUSED)))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(episodeId = "otro")))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(durationMs = 1L)))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(hasNext = false)))
        // Reiniciar el MISMO episodio cambia startedAtMs: tiene que ser un evento (Fix 2), no algo
        // que espere el latido de 10s.
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(startedAtMs = 42)))
    }
}
