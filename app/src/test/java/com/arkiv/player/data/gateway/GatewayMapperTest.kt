package com.arkiv.player.data.gateway

import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayMapperTest {

    @Test
    fun `magis se mapea a su propio tipo`() {
        // Sin esta rama el mapper devolvia null y los resultados de Magis nunca llegaban a la
        // pantalla, aunque el gateway los estuviera entregando.
        val ps = GatewayResult(
            source = "magis", title = "Duna", ref = "r", year = "2021",
            extra = mapOf("content_id" to "abc", "program_type" to "movie"),
        ).toPlaySource()
        assertTrue(ps is PlaySource.Magis)
        assertEquals("Duna", (ps as PlaySource.Magis).result.title)
        assertEquals("abc", ps.result.extra["content_id"])
        assertEquals("r", ps.result.ref)
    }

    @Test
    fun `ditu se mapea a su propio tipo`() {
        val ps = GatewayResult(source = "ditu", title = "Loki", ref = "r").toPlaySource()
        assertTrue(ps is PlaySource.Ditu)
        assertEquals("Loki", (ps as PlaySource.Ditu).result.title)
    }

    @Test
    fun `las fuentes conocidas se mapean- ninguna cae en null`() {
        // Guarda contra el bug real: el gateway sirve fuentes y el mapper debe conocerlas todas.
        // "archive" NO está en esta lista a propósito: se borró en la poda de light-magis (ver el
        // test de abajo).
        for (fuente in listOf("magis", "ditu")) {
            val r = GatewayResult(source = fuente, title = "x", ref = "r")
            assertTrue("la fuente '$fuente' no se mapea", r.toPlaySource() != null)
        }
    }

    @Test
    fun `archive torrent y web se ignoran a proposito- se borraron de esta rama`() {
        // El gateway (server viejo) todavia puede mandarlas; este APK ya no sabe que hacer con
        // ellas y las descarta igual que cualquier fuente futura desconocida. archive.org se borró
        // en esta tarea (poda de light-magis); torrent/web ya se habían borrado en la Tarea 2 (ver
        // TODO en GatewayMapper.toPlaySource, task 6 hace la limpieza completa del lado del fan-out).
        assertNull(GatewayResult(source = "archive", title = "x", ref = "r").toPlaySource())
        assertNull(GatewayResult(source = "torrent", title = "x", ref = "r").toPlaySource())
        assertNull(GatewayResult(source = "web", title = "x", ref = "r").toPlaySource())
    }

    @Test
    fun `una fuente desconocida se descarta sin romper`() {
        // Si el servidor agrega una fuente que este APK no conoce, se ignora en vez de fallar.
        assertNull(GatewayResult(source = "fuente_nueva", title = "x", ref = "r").toPlaySource())
    }

    @Test
    fun `el ref sobrevive al mapeo`() {
        // Sin esto no se puede resolver despues: /v1/resolve solo entiende el ref. Magis mapea el
        // GatewayResult entero, así que esto es sobre todo una guarda de regresión.
        assertEquals(
            "r",
            (GatewayResult(source = "magis", title = "x", ref = "r").toPlaySource()
                as PlaySource.Magis).result.ref,
        )
    }
}
