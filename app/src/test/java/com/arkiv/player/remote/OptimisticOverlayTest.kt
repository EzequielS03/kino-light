package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticOverlayTest {

    private fun foto(
        state: TvPlaybackState = TvPlaybackState.PLAYING,
        positionMs: Long = 10_000,
    ) = TvNowPlaying(
        episodeId = "ep", itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = 60_000, state = state,
        hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `sin nada pendiente devuelve la foto tal cual`() {
        val o = OptimisticOverlay()
        assertEquals(TvPlaybackState.PLAYING, o.apply(foto(), receivedAtMs = 0, nowMs = 0).state)
    }

    @Test
    fun `una foto vieja no revierte el estado que acabo de fijar`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        // La foto en vuelo todavía dice PLAYING; la barra debe seguir mostrando PAUSED.
        assertEquals(
            TvPlaybackState.PAUSED,
            o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 0, nowMs = 500).state,
        )
    }

    @Test
    fun `cuando el TV confirma se adopta la foto`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        // Llega la confirmación (foto fresca, posterior al comando): se limpia lo pendiente...
        assertEquals(
            TvPlaybackState.PAUSED,
            o.apply(foto(state = TvPlaybackState.PAUSED), receivedAtMs = 500, nowMs = 500).state,
        )
        // ...y a partir de ahí manda el TV otra vez.
        assertEquals(
            TvPlaybackState.PLAYING,
            o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 600, nowMs = 600).state,
        )
    }

    @Test
    fun `una foto que ya decia lo pedido pero es anterior al comando no confirma`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PLAYING, nowMs = 2_000)
        // La foto en vuelo es de ANTES del comando (receivedAtMs=0 < 2_000): aunque YA diga
        // PLAYING, no cuenta como confirmación. Sin este chequeo, reanudar antes de que un poll
        // confirmara la pausa previa se daría por confirmado al instante —la foto vieja sigue
        // diciendo PLAYING desde antes de la pausa— y la barra arrastraría de vuelta toda la pausa.
        // El propio `.state` no alcanza para distinguir "todavía pendiente" de "recién confirmado"
        // en este caso (ambos devuelven PLAYING): se verifica con `pinnedAtMs()`, que es la señal
        // que de verdad usa la UI para decidir si hay un pin vivo.
        o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 0, nowMs = 2_250)
        assertEquals(2_000L, o.pinnedAtMs())
        // Recién con una foto POSTERIOR al comando que coincide, se suelta.
        o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 2_100, nowMs = 2_300)
        assertEquals(null, o.pinnedAtMs())
    }

    @Test
    fun `lo pendiente expira para no quedar pegado si el comando se perdio`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        // Todavía dentro del hold (5s: cubre la latencia del comando más un ciclo de poll de 3s):
        // sigue sostenido aunque la foto cruda diga PLAYING.
        assertEquals(
            TvPlaybackState.PAUSED,
            o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 0, nowMs = 4_500).state,
        )
        // Vencido el hold: se suelta y manda la foto cruda.
        assertEquals(
            TvPlaybackState.PLAYING,
            o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 0, nowMs = 5_000).state,
        )
    }

    @Test
    fun `la posicion fijada por un seek gana hasta que el TV se acerca`() {
        val o = OptimisticOverlay()
        o.expectPosition(40_000, nowMs = 0)
        assertEquals(40_000, o.apply(foto(positionMs = 10_000), receivedAtMs = 500, nowMs = 500).positionMs)
        // El TV ya está dentro de la tolerancia (foto fresca, posterior al comando): se suelta.
        assertEquals(39_000, o.apply(foto(positionMs = 39_000), receivedAtMs = 800, nowMs = 800).positionMs)
        assertEquals(10_000, o.apply(foto(positionMs = 10_000), receivedAtMs = 900, nowMs = 900).positionMs)
    }

    @Test
    fun `clear descarta lo pendiente`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        o.clear()
        assertEquals(
            TvPlaybackState.PLAYING,
            o.apply(foto(state = TvPlaybackState.PLAYING), receivedAtMs = 100, nowMs = 100).state,
        )
    }

    @Test
    fun `pinnedAtMs es null sin nada pendiente`() {
        val o = OptimisticOverlay()
        assertEquals(null, o.pinnedAtMs())
    }

    @Test
    fun `pinnedAtMs devuelve el instante del pin activo`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 123)
        assertEquals(123L, o.pinnedAtMs())
        o.expectPosition(5_000, nowMs = 321)
        // Dos pines a la vez: el más viejo manda (es el que lleva más tiempo esperando confirmación).
        assertEquals(123L, o.pinnedAtMs())
    }

    @Test
    fun `pinnedAtMs vuelve a null cuando el pin se suelta`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        o.apply(foto(state = TvPlaybackState.PAUSED), receivedAtMs = 50, nowMs = 50)
        assertEquals(null, o.pinnedAtMs())
    }
}
