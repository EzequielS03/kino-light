package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuenteDeDescargaTest {

    @Test
    fun `un capitulo de magis se baja con la estrategia de magis`() {
        assertEquals("magis", FuenteDeDescarga.para("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }

    @Test
    fun `un capitulo de archive se baja con la estrategia de archive`() {
        assertEquals("archive", FuenteDeDescarga.para("dragon-ball-gt_s01e01"))
    }

    @Test fun `an unknown source keeps the persisted download source value`() {
        assertEquals("archive", FuenteDeDescarga.para("some-old-archive-identifier"))
    }

    @Test
    fun `un capitulo de caracol no cae en la estrategia de archive`() {
        assertEquals("ditu", FuenteDeDescarga.para("ditu:12345::e1"))
    }

    /** Lo que trae hoy `AppGraph.downloadStrategies`: solo Magis. */
    private val estrategias = setOf("magis")

    @Test
    fun `magis se ofrece para bajar`() {
        assertTrue(FuenteDeDescarga.sePuedeBajar("magis:2AD2591D4242471D96B68FF04FFD2784::e6", estrategias))
        assertTrue(FuenteDeDescarga.hayEstrategia("magis", estrategias))
    }

    /** Widevine: no hay con qué bajarlo, así que la opción no se muestra en vez de fallar después. */
    @Test
    fun `caracol no se ofrece para bajar`() {
        assertFalse(FuenteDeDescarga.sePuedeBajar("ditu:12345::e1", estrategias))
        assertFalse(FuenteDeDescarga.sePuedeBajar("ditu:P1::0", estrategias))
        assertFalse(FuenteDeDescarga.hayEstrategia("ditu", estrategias))
    }

    /** La regla es "hay estrategia", no una lista de nombres: cubre sola a una fuente futura. */
    @Test
    fun `una fuente sin estrategia no se ofrece, se llame como se llame`() {
        assertFalse(FuenteDeDescarga.sePuedeBajar("dragon-ball-gt_s01e01", estrategias))
        assertFalse(FuenteDeDescarga.hayEstrategia("fuente_nueva", estrategias))
        assertTrue(FuenteDeDescarga.hayEstrategia("fuente_nueva", estrategias + "fuente_nueva"))
    }
}
