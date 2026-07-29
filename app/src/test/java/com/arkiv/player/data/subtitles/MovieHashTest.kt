package com.arkiv.player.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieHashTest {

    private fun zeros() = ByteArray(MovieHash.CHUNK)

    @Test fun allZeroBlocksHashEqualsFileSize() {
        // head y cola en cero → hash = tamaño del archivo (0xBC614E = 12345678).
        val h = MovieHash.compute(12_345_678L) { _, _ -> zeros() }
        assertEquals("0000000000bc614e", h)
    }

    @Test fun headContributionIsLittleEndian() {
        // Primer long del head = 1 (little-endian), resto cero; cola cero. fileSize=0x20000.
        val head = zeros().apply { this[0] = 1 }
        val h = MovieHash.compute(131_072L) { offset, _ -> if (offset == 0L) head else zeros() }
        assertEquals("0000000000020001", h) // 0x20000 + 1
    }

    @Test fun tailAlsoContributes() {
        val tail = zeros().apply { this[0] = 2 } // long = 2
        val h = MovieHash.compute(131_072L) { offset, _ -> if (offset == 0L) zeros() else tail }
        assertEquals("0000000000020002", h) // 0x20000 + 2
    }

    @Test fun overflowWrapsAround() {
        // Primer long de head y de cola = 0xFF..FF = -1 con signo; el resto cero → cada bloque suma -1.
        // fileSize=0x10002; 0x10002 + (-1) + (-1) = 0x10000. Verifica el envolvimiento de 64 bits.
        val minusOne = zeros().apply { for (i in 0 until 8) this[i] = 0xFF.toByte() }
        val h = MovieHash.compute(0x10002L) { _, _ -> minusOne }
        assertEquals("0000000000010000", h)
    }

    @Test fun tooSmallReturnsNull() {
        assertNull(MovieHash.compute(100L) { _, _ -> zeros() })
    }

    @Test fun shortReadReturnsNull() {
        assertNull(MovieHash.compute(200_000L) { _, _ -> ByteArray(10) })
    }
}
