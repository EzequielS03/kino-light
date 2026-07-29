package com.arkiv.player.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class TrackerScraperTest {
    private val s = TrackerScraper(txId = { 0x11223344 })

    @Test fun `connect request es 16 bytes con protocol_id, action 0 y txid`() {
        val req = s.buildConnectRequest(0x11223344)
        assertEquals(16, req.size)
        val b = ByteBuffer.wrap(req)
        assertEquals(TrackerScraper.PROTOCOL_ID, b.long)
        assertEquals(TrackerScraper.ACTION_CONNECT, b.int)
        assertEquals(0x11223344, b.int)
    }

    @Test fun `parseConnectResponse devuelve connection_id y valida action y txid`() {
        val ok = ByteBuffer.allocate(16).putInt(0).putInt(0x11223344).putLong(0xABCDEF12L).array()
        assertEquals(0xABCDEF12L, s.parseConnectResponse(ok, 0x11223344))
        // txid distinto -> null
        assertNull(s.parseConnectResponse(ok, 0x55555555.toInt()))
        // action != connect -> null
        val badAction = ByteBuffer.allocate(16).putInt(2).putInt(0x11223344).putLong(1L).array()
        assertNull(s.parseConnectResponse(badAction, 0x11223344))
        // corto -> null
        assertNull(s.parseConnectResponse(ByteArray(8), 0x11223344))
    }

    @Test fun `scrape request es 36 bytes (16 + 20 infohash)`() {
        val ih = ByteArray(20) { it.toByte() }
        val req = s.buildScrapeRequest(0xABCDEF12L, 0x11223344, ih)
        assertEquals(36, req.size)
        val b = ByteBuffer.wrap(req)
        assertEquals(0xABCDEF12L, b.long)
        assertEquals(TrackerScraper.ACTION_SCRAPE, b.int)
        assertEquals(0x11223344, b.int)
        val tail = ByteArray(20); b.get(tail)
        assertArrayEquals(ih, tail)
    }

    @Test fun `parseScrapeResponse devuelve seeders completed leechers`() {
        val resp = ByteBuffer.allocate(8 + 12).putInt(2).putInt(0x11223344)
            .putInt(345).putInt(1000).putInt(20).array()
        assertEquals(Triple(345, 1000, 20), s.parseScrapeResponse(resp, 0x11223344))
        // txid distinto -> null
        assertNull(s.parseScrapeResponse(resp, 1))
        // corto -> null
        assertNull(s.parseScrapeResponse(ByteArray(10), 0x11223344))
    }

    @Test fun `trackersFromMagnet extrae solo udp decodificados`() {
        val magnet = "magnet:?xt=urn:btih:AAAA&dn=x" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=http%3A%2F%2Fnoudp.com%2Fannounce"
        val trs = TrackerScraper.trackersFromMagnet(magnet)
        assertEquals(listOf("udp://tracker.opentrackr.org:1337/announce"), trs)
        assertEquals(emptyList<String>(), TrackerScraper.trackersFromMagnet(null))
    }

    @Test fun `hexToInfoHash 40 hex a 20 bytes e invalido null`() {
        val ih = TrackerScraper.hexToInfoHash("0123456789abcdef0123456789abcdef01234567")
        assertEquals(20, ih!!.size)
        assertEquals(0x01.toByte(), ih[0])
        assertEquals(0x67.toByte(), ih[19])
        assertNull(TrackerScraper.hexToInfoHash("xyz"))
        assertNull(TrackerScraper.hexToInfoHash("0123"))       // corto
        assertNull(TrackerScraper.hexToInfoHash(null))
    }
}
