package com.arkiv.player.miniaturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las dos guardas que evitan guardar un frame inservible. Importan porque la captura SOBRESCRIBE:
 * un frame malo no se suma al bueno, lo reemplaza.
 */
class GuardasDeFrameTest {

    private fun lleno(color: Int, cuantos: Int = 100) = IntArray(cuantos) { color }

    /** Los primeros 60 s son logos de distribuidora y pantallas negras. */
    @Test
    fun `no se captura antes del piso de posicion`() {
        assertFalse(GuardasDeFrame.posicionSirve(0))
        assertFalse(GuardasDeFrame.posicionSirve(59_999))
        assertTrue(GuardasDeFrame.posicionSirve(60_000))
        assertTrue(GuardasDeFrame.posicionSirve(15 * 60_000))
    }

    @Test
    fun `la luminancia de un negro puro es cero y la de un blanco puro es 255`() {
        assertEquals(0, GuardasDeFrame.luminanciaMedia(lleno(0xFF000000.toInt())))
        assertEquals(255, GuardasDeFrame.luminanciaMedia(lleno(0xFFFFFFFF.toInt())))
    }

    /** Pausar en un fundido dejaría la tarjeta en negro, pisando el frame bueno anterior. */
    @Test
    fun `un frame negro se descarta`() {
        assertFalse(GuardasDeFrame.noEsCasiNegro(lleno(0xFF000000.toInt())))
    }

    /** Casi negro pero no del todo: una escena nocturna real tiene que pasar. */
    @Test
    fun `justo en el umbral se acepta y justo debajo se descarta`() {
        assertTrue(GuardasDeFrame.noEsCasiNegro(lleno(0xFF0A0A0A.toInt())))   // luminancia 10
        assertFalse(GuardasDeFrame.noEsCasiNegro(lleno(0xFF080808.toInt())))  // luminancia 8
    }

    /** Una escena oscura con una zona clara (una lámpara, un subtítulo) promedia por encima. */
    @Test
    fun `una escena oscura con algo de luz se acepta`() {
        val pixeles = IntArray(100) { i -> if (i < 90) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertTrue(GuardasDeFrame.noEsCasiNegro(pixeles))
    }

    /** Sin píxeles no hay nada que juzgar: se descarta en vez de dividir por cero. */
    @Test
    fun `un arreglo vacio se descarta`() {
        assertFalse(GuardasDeFrame.noEsCasiNegro(IntArray(0)))
    }
}
