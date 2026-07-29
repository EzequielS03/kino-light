package com.arkiv.player.playback

/** Rango de bytes pedido por el cliente HTTP. `end` null = hasta el final. */
data class ByteRange(val start: Long, val end: Long?)

object RangeHeader {
    private val RE = Regex("""bytes=(\d+)-(\d*)""")
    fun parse(header: String?): ByteRange? {
        val m = header?.let { RE.matchEntire(it.trim()) } ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull()
        return ByteRange(start, end)
    }
}
