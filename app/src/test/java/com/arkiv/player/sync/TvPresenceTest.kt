package com.arkiv.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPresenceTest {
    @Test
    fun foundMarksAvailableAndResetsMisses() {
        val p = reduceTvPresence(TvPresence(available = false, misses = 2), found = true)
        assertTrue(p.available)
        assertEquals(0, p.misses)
    }

    @Test
    fun singleMissDoesNotDropAvailability() {
        // Estando conectados, un descubrimiento vacío puntual NO debe apagar el flag.
        val p = reduceTvPresence(TvPresence(available = true, misses = 0), found = false, threshold = 3)
        assertTrue("un solo fallo no baja el flag", p.available)
        assertEquals(1, p.misses)
    }

    @Test
    fun availabilityDropsOnlyAfterThresholdConsecutiveMisses() {
        var p = TvPresence(available = true, misses = 0)
        p = reduceTvPresence(p, found = false, threshold = 3) // 1
        assertTrue(p.available)
        p = reduceTvPresence(p, found = false, threshold = 3) // 2
        assertTrue(p.available)
        p = reduceTvPresence(p, found = false, threshold = 3) // 3 -> baja
        assertFalse(p.available)
    }

    @Test
    fun oneSuccessResetsTheMissStreak() {
        var p = TvPresence(available = true, misses = 0)
        p = reduceTvPresence(p, found = false, threshold = 3) // 1
        p = reduceTvPresence(p, found = false, threshold = 3) // 2
        p = reduceTvPresence(p, found = true, threshold = 3)  // reset
        assertEquals(0, p.misses)
        p = reduceTvPresence(p, found = false, threshold = 3) // 1 de nuevo, sigue arriba
        assertTrue(p.available)
    }
}
