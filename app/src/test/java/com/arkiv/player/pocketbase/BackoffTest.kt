package com.arkiv.player.pocketbase

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BackoffTest {
    @Test
    fun growsExponentiallyAndIsCapped() {
        val b = Backoff(baseMs = 1000, maxMs = 30000, random = Random(42))
        val d0 = b.nextDelayMs(0)
        val d3 = b.nextDelayMs(3)
        val dBig = b.nextDelayMs(20)
        assertTrue("d0 en [500,1000]", d0 in 500..1000)
        assertTrue("d3 mayor que d0", d3 >= d0)
        assertTrue("acotado a maxMs", dBig <= 30000)
        assertTrue("jitter no baja de 50% del cap", dBig >= 15000)
    }
}
