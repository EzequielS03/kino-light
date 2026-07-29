package com.arkiv.player.pocketbase

import java.util.Random
import kotlin.math.min

/** Backoff exponencial con jitter en [50%, 100%] del valor calculado. */
class Backoff(
    private val baseMs: Long = 1000,
    private val maxMs: Long = 30000,
    private val random: Random = Random(),
) {
    fun nextDelayMs(attempt: Int): Long {
        val exp = min(maxMs, baseMs shl attempt.coerceIn(0, 30))
        val half = exp / 2
        return half + (random.nextDouble() * half).toLong()
    }
}
