package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairCodeTest {
    @Test fun generatesHighEntropyDistinctCodes() {
        val a = PairCode.generate()
        val b = PairCode.generate()
        assertNotEquals(a, b)
        assertEquals(26, a.length)
        assertTrue("solo base32 A-Z2-7", a.all { it in 'A'..'Z' || it in '2'..'7' })
    }
}
