package com.arkiv.player.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpacePolicyTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `entra si sobra espacio con margen`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 10 * gb, neededBytes = 2 * gb))
    }

    @Test
    fun `no entra si el archivo es mas grande que lo disponible`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 4 * gb))
    }

    /** Justo-justo tampoco: dejar el sistema sin un byte libre rompe otras cosas antes que a Arkiv. */
    @Test
    fun `no entra si cabe pero se come el margen`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 2 * gb - 1))
    }

    @Test
    fun `tamano desconocido siempre entra`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = 0))
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = -1))
    }
}
