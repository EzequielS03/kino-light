package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSizeGateTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `un archivo de 6 GB pide confirmacion`() {
        assertTrue(TorrentSizeGate.needsConfirmation(6 * gb, alreadyConfirmed = false))
    }

    @Test
    fun `un archivo de 1 punto 2 GB no pide confirmacion`() {
        val size = (1.2 * gb).toLong()
        assertFalse(TorrentSizeGate.needsConfirmation(size, alreadyConfirmed = false))
    }

    /**
     * El caso que motiva que la compuerta viva en el worker y no al encolar: TorrentResult.sizeBytes
     * es el peso del PACK entero. Acá se compara contra el archivo elegido, que es lo que se baja.
     */
    @Test
    fun `un pack de 30 GB cuyo capitulo pesa 1 punto 2 GB no pide confirmacion`() {
        val fileSize = (1.2 * gb).toLong()
        assertFalse(TorrentSizeGate.needsConfirmation(fileSize, alreadyConfirmed = false))
    }

    @Test
    fun `exactamente 5 GB no dispara`() {
        assertFalse(TorrentSizeGate.needsConfirmation(5 * gb, alreadyConfirmed = false))
    }

    @Test
    fun `un byte por encima de 5 GB dispara`() {
        assertTrue(TorrentSizeGate.needsConfirmation(5 * gb + 1, alreadyConfirmed = false))
    }

    @Test
    fun `una vez confirmada no vuelve a disparar`() {
        assertFalse(TorrentSizeGate.needsConfirmation(20 * gb, alreadyConfirmed = true))
    }

    @Test
    fun `tamano desconocido no dispara`() {
        assertFalse(TorrentSizeGate.needsConfirmation(0, alreadyConfirmed = false))
        assertFalse(TorrentSizeGate.needsConfirmation(-1, alreadyConfirmed = false))
    }

    @Test
    fun `formatea el tamano para mostrarlo`() {
        assertEquals("8.4 GB", TorrentSizeGate.formatSize((8.4 * gb).toLong()))
        assertEquals("700 MB", TorrentSizeGate.formatSize(700L * 1024 * 1024))
    }
}
