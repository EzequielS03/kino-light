package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
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
}
