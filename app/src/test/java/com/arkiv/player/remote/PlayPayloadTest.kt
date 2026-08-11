package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPayloadTest {
    @Test fun roundTrip() {
        val p = PlayPayload(PlayKind.TORRENT, id = "magnet:xyz", episodeId = "ep1", startPositionMs = 4200)
        val back = PlayPayloadCodec.decode(PlayPayloadCodec.encode(p))
        assertEquals(p, back)
    }

    @Test fun legacyRawStringIsAccepted() {
        val back = PlayPayloadCodec.decode("archive-identifier-123")
        assertEquals(PlayKind.UNKNOWN, back.kind)
        assertEquals("archive-identifier-123", back.episodeId)
    }

    @Test fun encodeIsPrefixed() {
        assertEquals(true, PlayPayloadCodec.encode(PlayPayload(PlayKind.ARCHIVE, "i", "e")).startsWith("arkivplay|"))
    }

    // Tarea 15: el envío de un canal en vivo al TV pareado usa PlayKind.LIVE con
    // episodeId = "live:<code>" (el mismo prefijo que PlayerSource.LIVE_PREFIX). El roundtrip
    // tiene que preservar el kind y el prefijo intacto -- si se perdiera, PlayerSource.kindFor()
    // del lado del TV ya no reconocería el comando como vivo.
    @Test fun roundTripLive() {
        val p = PlayPayload(PlayKind.LIVE, id = "live:canal1", episodeId = "live:canal1")
        val back = PlayPayloadCodec.decode(PlayPayloadCodec.encode(p))
        assertEquals(p, back)
        assertEquals(PlayKind.LIVE, back.kind)
    }
}
