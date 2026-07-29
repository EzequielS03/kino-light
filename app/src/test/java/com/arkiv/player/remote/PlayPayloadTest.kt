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
}
