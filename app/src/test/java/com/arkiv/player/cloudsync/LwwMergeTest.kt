package com.arkiv.player.cloudsync

import org.junit.Assert.*
import org.junit.Test

class LwwMergeTest {
    @Test fun remoteWinsWhenNewer() {
        assertTrue(LwwMerge.pickWinner(localUpdatedAt = 10, remoteUpdatedAt = 20))
        assertFalse(LwwMerge.pickWinner(localUpdatedAt = 20, remoteUpdatedAt = 10))
        assertFalse(LwwMerge.pickWinner(localUpdatedAt = 20, remoteUpdatedAt = 20)) // empate: local se queda
    }

    @Test fun clampNeverGoesBackwards() {
        assertEquals(101, clampUpdatedAt(candidate = 50, lastKnown = 100))
        assertEquals(200, clampUpdatedAt(candidate = 200, lastKnown = 100))
    }
}
