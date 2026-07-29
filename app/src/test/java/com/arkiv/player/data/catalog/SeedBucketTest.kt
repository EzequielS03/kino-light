package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedBucketTest {

    @Test
    fun `mas seeds cae en un bucket menor (mejor)`() {
        assertEquals(0, seedBucket(1000))
        assertEquals(0, seedBucket(50))   // umbral "sano"
        assertEquals(1, seedBucket(49))
        assertEquals(1, seedBucket(10))   // umbral "ok"
        assertEquals(2, seedBucket(9))
        assertEquals(2, seedBucket(3))    // umbral "marginal"
        assertEquals(3, seedBucket(2))
        assertEquals(3, seedBucket(1))    // débil (1-2 seeds)
    }

    @Test
    fun `un torrent sano gana (bucket menor) a uno con 1 seed`() {
        assertTrue(seedBucket(1000) < seedBucket(1))
    }

    @Test
    fun `el bucket es monotono no creciente al subir los seeds`() {
        val buckets = (1..200).map { seedBucket(it) }
        assertEquals(buckets.sortedDescending(), buckets)
    }
}
