package com.arkiv.player.playback

import java.io.File
import java.security.MessageDigest

/** Caché LRU en disco por clave de URL. La "recencia" es el lastModified del archivo. */
class DiskLruCache(private val dir: File, private val maxBytes: Long) {
    init { dir.mkdirs() }

    fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun file(key: String): File = File(dir, key)

    fun touch(key: String) { file(key).setLastModified(System.currentTimeMillis()) }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun evictIfNeeded() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        val byOldest = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in byOldest) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
