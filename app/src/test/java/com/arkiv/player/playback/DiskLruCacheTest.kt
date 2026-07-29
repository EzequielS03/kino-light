package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskLruCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun key_is_stable_and_filesystem_safe() {
        val c = DiskLruCache(tmp.newFolder(), 1024)
        val k1 = c.keyFor("https://archive.org/a/b c.mkv")
        val k2 = c.keyFor("https://archive.org/a/b c.mkv")
        assertEquals(k1, k2)
        assertTrue(k1.all { it.isLetterOrDigit() })
    }

    @Test fun evicts_oldest_when_over_limit() {
        val c = DiskLruCache(tmp.newFolder(), 100)
        val a = c.file(c.keyFor("u/a")).apply { writeBytes(ByteArray(60)) }
        c.touch(c.keyFor("u/a"))
        Thread.sleep(5)
        val b = c.file(c.keyFor("u/b")).apply { writeBytes(ByteArray(60)) }
        c.touch(c.keyFor("u/b"))
        c.evictIfNeeded()               // 120 > 100 → borra el más viejo (a)
        assertTrue(!a.exists())
        assertTrue(b.exists())
    }
}
