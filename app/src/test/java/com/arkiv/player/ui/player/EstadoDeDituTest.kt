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

    /** Un estado con el episodio A pedido y ya sonando. */
    private fun sonandoA(): EstadoDeDitu = EstadoDeDitu().apply {
        nuevoPedido("ditu:A")
        publicar(resuelto("ditu:A"))
    }

    /**
     * Lo que manda el reloj de [DituExoPlayer]: una lectura cada 500 ms reproduciendo, durante
     * [durMs] desde [desdeMs]. Devuelve la posición final.
     */
    private fun EstadoDeDitu.reproducir(desdeMs: Long, durMs: Long): Long {
        var pos = desdeMs
        avanzo(pos, reproduciendo = true)
        while (pos < desdeMs + durMs) {
            pos += 500
            avanzo(pos, reproduciendo = true)
        }
        return pos
    }

    // --- la guardia del pedido vigente ------------------------------------------------------

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
        val estado = sonandoA()
        estado.nuevoPedido("live:canal1")
        assertNull(estado.actual.value)
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

    // --- recargas ------------------------------------------------------------------------------

    @Test
    fun `dos recargas y la tercera no ocurre`() {
        val estado = sonandoA()
        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A", 60_000))
        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A", 61_000))

        // null = no se recarga más: el ViewModel manda el error a la persona.
        assertNull(estado.pedirRecarga())
    }

    /** El bucle que había: llegar a READY reponía el tope, y un stream que se muere solo no paraba. */
    @Test
    fun `llegar a reproducir y morirse enseguida no repone las recargas`() {
        val estado = sonandoA()
        var pos = 0L
        repeat(MAX_RECARGAS_DITU) {
            pos = estado.reproducir(pos, 2_000)
            assertEquals("ditu:A", estado.pedirRecarga())
            estado.publicar(resuelto("ditu:A", pos))
        }
        estado.reproducir(pos, 2_000)
        assertNull(estado.pedirRecarga())
    }

    @Test
    fun `treinta segundos estables reponen las recargas`() {
        val estado = sonandoA()
        estado.pedirRecarga()
        estado.pedirRecarga()
        estado.reproducir(0L, REPRODUCCION_ESTABLE_MS)
        // Un corte más adelante tiene sus dos recargas completas.
        assertEquals("ditu:A", estado.pedirRecarga())
        assertEquals("ditu:A", estado.pedirRecarga())
        assertNull(estado.pedirRecarga())
    }

    @Test
    fun `un pedido nuevo repone las recargas`() {
        val estado = sonandoA()
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

    // --- re-preparados -------------------------------------------------------------------------

    @Test
    fun `llegar a reproducir y morirse enseguida no repone los re-preparados`() {
        val estado = sonandoA()
        var pos = 0L
        repeat(MAX_REPREPARADOS_DITU) {
            pos = estado.reproducir(pos, 2_000)
            assertTrue(estado.pedirRepreparado())
        }
        estado.reproducir(pos, 2_000)
        assertFalse(estado.pedirRepreparado())
    }

    @Test
    fun `treinta segundos estables reponen los re-preparados`() {
        val estado = sonandoA()
        repeat(MAX_REPREPARADOS_DITU) { assertTrue(estado.pedirRepreparado()) }
        assertFalse(estado.pedirRepreparado())
        estado.reproducir(0L, REPRODUCCION_ESTABLE_MS)
        repeat(MAX_REPREPARADOS_DITU) { assertTrue(estado.pedirRepreparado()) }
        assertFalse(estado.pedirRepreparado())
    }

    /** Una URL nueva es un stream nuevo: sus cortes de publicidad tienen los re-preparados enteros. */
    @Test
    fun `una recarga repone los re-preparados pero no las recargas`() {
        val estado = sonandoA()
        repeat(MAX_REPREPARADOS_DITU) { estado.pedirRepreparado() }
        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A"))
        repeat(MAX_REPREPARADOS_DITU) { assertTrue(estado.pedirRepreparado()) }
        assertEquals("ditu:A", estado.pedirRecarga())
        estado.publicar(resuelto("ditu:A"))
        assertNull(estado.pedirRecarga())
    }

    // --- "de corrido" ------------------------------------------------------------------------

    @Test
    fun `un rebuffer a los veinte segundos corta la racha`() {
        val estado = sonandoA()
        repeat(MAX_REPREPARADOS_DITU) { estado.pedirRepreparado() }
        var pos = estado.reproducir(0L, 20_000)
        estado.avanzo(pos, reproduciendo = false) // se detuvo a buffear
        pos = estado.reproducir(pos, 20_000)
        // 20 + 20 no son 30 de corrido: el tope sigue gastado.
        assertFalse(estado.pedirRepreparado())
        estado.reproducir(pos, REPRODUCCION_ESTABLE_MS)
        assertTrue(estado.pedirRepreparado())
    }

    @Test
    fun `un salto no cuenta como reproducir`() {
        val estado = sonandoA()
        repeat(MAX_REPREPARADOS_DITU) { estado.pedirRepreparado() }
        estado.avanzo(0L, reproduciendo = true)
        estado.avanzo(REPRODUCCION_ESTABLE_MS * 2, reproduciendo = true) // seek hacia adelante
        assertFalse(estado.pedirRepreparado())
    }
}
