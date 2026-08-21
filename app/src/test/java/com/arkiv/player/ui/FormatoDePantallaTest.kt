package com.arkiv.player.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatoDePantallaTest {

    @Test
    fun `una tablet acostada es tablet horizontal`() {
        assertTrue(esTabletHorizontal(800, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `una tablet parada no lo es`() {
        assertFalse(esTabletHorizontal(800, Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `un celular grande acostado NO es tablet`() {
        // Un Galaxy S24+ acostado mide 1040dp de ANCHO, pero su lado más chico son 480dp.
        // Por eso la regla mira el lado más chico: si mirara el ancho actual, el celular
        // se llevaría el layout de tablet cada vez que el usuario lo gira.
        assertFalse(esTabletHorizontal(480, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `el umbral es 600dp`() {
        assertFalse(esTabletHorizontal(599, Configuration.ORIENTATION_LANDSCAPE))
        assertTrue(esTabletHorizontal(600, Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `la grilla duplica columnas en ancho`() {
        assertEquals(6, columnasDeGrilla(base = 3, esAncho = true))
        assertEquals(4, columnasDeGrilla(base = 2, esAncho = true))
    }

    @Test
    fun `la grilla del celular no cambia`() {
        assertEquals(3, columnasDeGrilla(base = 3, esAncho = false))
        assertEquals(2, columnasDeGrilla(base = 2, esAncho = false))
    }
}
