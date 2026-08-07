package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StagingProgressTest {

    @Test
    fun `el staging ocupa la primera mitad`() {
        assertEquals(0f, StagingProgress.fromStaging(0f), 0.001f)
        assertEquals(0.25f, StagingProgress.fromStaging(0.5f), 0.001f)
        assertEquals(0.5f, StagingProgress.fromStaging(1f), 0.001f)
    }

    @Test
    fun `la transferencia ocupa la segunda mitad`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(0, 1000), 0.001f)
        assertEquals(0.75f, StagingProgress.fromTransfer(500, 1000), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(1000, 1000), 0.001f)
    }

    @Test
    fun `sin total conocido la transferencia se queda en la mitad`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(400, 0), 0.001f)
    }

    @Test
    fun `nunca se pasa de los limites`() {
        assertEquals(0.5f, StagingProgress.fromStaging(2f), 0.001f)
        assertEquals(0f, StagingProgress.fromStaging(-1f), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(2000, 1000), 0.001f)
    }
}
