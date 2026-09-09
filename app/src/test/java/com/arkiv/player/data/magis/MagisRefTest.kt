package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisRefTest {

    /** Ref REAL del gateway (`base64url(json).hmac`), con un `exp` ya vencido y firmado con una
     *  llave que la app no tiene: es exactamente lo que hay guardado en las bases de hoy. */
    private val refViejo =
        "eyJzIjoibWFnaXMiLCJwIjp7ImNvbnRlbnRfaWQiOiIxNDcwOTc0IiwicHJvZ3JhbV90eXBlIjoidGVsZXBsYXki" +
            "LCJlcGlzb2RlIjozfSwiZXhwIjoxNzU3MDAwMDAwfQ.lkewA6xe7e098nDA5WYNWA"

    @Test
    fun `ida y vuelta del formato propio`() {
        val ref = MagisRef(contentId = "1470974", tipoPrograma = "teleplay", episodio = 3)

        assertEquals(ref, MagisRef.decodificar(ref.codificar()))
    }

    @Test
    fun `un contentId con dos puntos adentro sobrevive`() {
        val ref = MagisRef(contentId = "cyx:raro:99", tipoPrograma = "movie", episodio = 0)

        assertEquals(ref, MagisRef.decodificar(ref.codificar()))
    }

    @Test
    fun `un ref viejo del gateway se entiende aunque este vencido y firmado con otra llave`() {
        val ref = MagisRef.decodificar(refViejo)!!

        assertEquals("1470974", ref.contentId)
        assertEquals("teleplay", ref.tipoPrograma)
        assertEquals(3, ref.episodio)
        assertTrue(ref.esSerie)
    }

    @Test
    fun `un ref viejo de otra fuente no es de Magis`() {
        val deWeb = "eyJzIjoid2ViIiwicCI6eyJ1cmwiOiJ4In0sImV4cCI6MTc1NzAwMDAwMH0.hAsrHhTwBhEMO5hw9hZ_bA"

        assertNull(MagisRef.decodificar(deWeb))
    }

    @Test
    fun `basura, vacio y cosas a medio armar dan null en vez de reventar`() {
        listOf(
            "",
            "   ",
            "magis1",
            "magis1:movie",
            "magis1:movie:0:",
            "no-es-un-ref",
            "sinpunto",
            "...",
            "@@@.@@@",
            "eyJzIjoibWFnaXMi.x", // json cortado
        ).forEach { assertNull("ref $it", MagisRef.decodificar(it)) }
    }

    @Test
    fun `sin program_type se asume pelicula`() {
        assertEquals("movie", MagisRef.decodificar("magis1::0:C1")?.tipoPrograma)
        assertTrue(MagisRef.decodificar("magis1::0:C1")?.esSerie == false)
    }

    @Test
    fun `el episodio que no es un numero no tumba el ref`() {
        val ref = MagisRef.decodificar("magis1:teleplay:tres:C1")!!

        assertEquals(0, ref.episodio)
        assertEquals("C1", ref.contentId)
    }

    @Test
    fun `los tres tipos de serie del portal cuentan como serie`() {
        listOf("teleplay", "series", "variety").forEach {
            assertTrue(it, MagisRef(contentId = "x", tipoPrograma = it).esSerie)
        }
        assertTrue(!MagisRef(contentId = "x", tipoPrograma = "movie").esSerie)
    }
}
