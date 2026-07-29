package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerListParseTest {
    @Test fun `parsea udp http y descarta wss y vacios`() {
        val txt = "udp://a.org:1337/announce\n\nhttp://b.org:80/announce\n\nwss://c.org/ws\n  \nudp://a.org:1337/announce\n"
        val t = parseTrackers(txt)
        assertEquals(2, t.size) // dedup + sin wss/vacíos
        assertTrue(t.contains("udp://a.org:1337/announce"))
        assertTrue(t.contains("http://b.org:80/announce"))
        assertFalse(t.any { it.startsWith("wss://") })
    }
    @Test fun `texto vacio da lista vacia`() { assertTrue(parseTrackers("").isEmpty()) }
}
