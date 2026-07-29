package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqTrackerTest {
    @Test fun nextIsMonotonic() {
        val t = SeqTracker()
        assertEquals(1L, t.next()); assertEquals(2L, t.next()); assertEquals(3L, t.next())
    }

    @Test fun isFreshRejectsRepeatsAndOld() {
        val t = SeqTracker()
        assertTrue(t.isFresh(5)); assertFalse(t.isFresh(5)); assertFalse(t.isFresh(3)); assertTrue(t.isFresh(6))
    }
}
