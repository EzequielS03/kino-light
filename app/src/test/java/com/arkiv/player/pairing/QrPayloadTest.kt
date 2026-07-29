package com.arkiv.player.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test fun roundTrip() {
        val p = QrPayload(dbUrl = "https://db.comparadorinternet.co", code = "ABC234XYZ")
        val s = QrPayloadCodec.encode(p)
        assertEquals("arkiv://pair", s.substringBefore('?'))
        val back = QrPayloadCodec.decode(s)
        assertEquals(p, back)
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(QrPayloadCodec.decode("https://example.com"))
        assertNull(QrPayloadCodec.decode("arkiv://pair?u=x")) // falta code
    }

    @Test fun decodeReturnsNullOnMalformedEncoding() {
        // Lone '%' and bad escape must not throw — must return null.
        assertNull(QrPayloadCodec.decode("arkiv://pair?u=%&c=ABC"))
        assertNull(QrPayloadCodec.decode("arkiv://pair?u=https%3A%2F%2Fx&c=%zz"))
    }
}
