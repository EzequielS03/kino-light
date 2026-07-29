package com.arkiv.player.remote

import java.util.concurrent.atomic.AtomicLong

/** Genera seqs monótonos (lado emisor) y descarta repetidos/viejos (lado receptor). */
class SeqTracker {
    private val counter = AtomicLong(0)
    @Volatile private var highestSeen = 0L

    fun next(): Long = counter.incrementAndGet()

    @Synchronized
    fun isFresh(seq: Long): Boolean {
        if (seq <= highestSeen) return false
        highestSeen = seq
        return true
    }
}
