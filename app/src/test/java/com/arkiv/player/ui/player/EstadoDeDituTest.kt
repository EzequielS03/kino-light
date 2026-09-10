package com.arkiv.player.ui.player

import com.arkiv.player.data.gateway.GatewayPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstadoDeDituTest {

    private fun resuelto(episodeId: String, posMs: Long = 0L) = DituReproducible(
        episodeId = episodeId,
        playable = GatewayPlayable(kind = "ditu", url = "https://cdn/$episodeId.mpd"),
        startPositionMs = posMs,
    )

    @Test
    fun `lo que se pidio se publica`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        assertTrue(estado.publicar(resuelto("ditu:A", 5_000)))
        assertEquals("ditu:A", estado.actual.value?.episodeId)
        assertEquals(5_000L, estado.actual.value?.startPositionMs)
    }

    /** La carrera: Caracol todavía resolvía A cuando la persona pidió otro episodio (acá, de Magis). */
    @Test
    fun `una resolucion de un episodio que ya no es el vigente no se publica`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.nuevoPedido("magis:B")
        assertFalse(estado.publicar(resuelto("ditu:A")))
        assertNull(estado.actual.value)
    }

    @Test
    fun `un pedido nuevo suelta lo de caracol que sonaba`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A"))
        estado.nuevoPedido("live:canal1")
        assertNull(estado.actual.value)
    }

    @Test
    fun `dos recargas y la tercera no ocurre`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A"))

        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A", 60_000))
        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A", 61_000))

        // null = no se recarga más: el ViewModel manda el error a la persona.
        assertNull(estado.pedirRecarga())
    }

    @Test
    fun `volver a reproducir repone las recargas`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A"))
        estado.pedirRecarga()
        estado.pedirRecarga()
        estado.volvioAReproducir()
        assertEquals("ditu:A", estado.pedirRecarga())
    }

    @Test
    fun `un pedido nuevo repone las recargas`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A"))
        estado.pedirRecarga()
        estado.pedirRecarga()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A"))
        assertEquals("ditu:A", estado.pedirRecarga())
    }

    @Test
    fun `sin nada de caracol sonando no hay recarga`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        assertNull(estado.pedirRecarga())
    }

    /** Si Caracol devuelve la misma URL, la pantalla igual tiene que rearmar el reproductor. */
    @Test
    fun `dos publicaciones iguales no son el mismo valor`() {
        val estado = EstadoDeDitu()
        estado.nuevoPedido("ditu:A")
        estado.publicar(resuelto("ditu:A", 1_000))
        val primera = estado.actual.value
        estado.publicar(resuelto("ditu:A", 1_000))
        assertNotEquals(primera, estado.actual.value)
    }
}
