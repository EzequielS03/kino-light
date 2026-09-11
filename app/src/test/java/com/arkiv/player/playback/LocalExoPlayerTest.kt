package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the ExoPlayer that `PlaybackService` hosts for downloaded files. */
class LocalExoPlayerTest {

    private data class Decoder(val name: String, val hardware: Boolean)

    private val hw1 = Decoder("c2.qti.hevc.decoder", hardware = true)
    private val hw2 = Decoder("c2.exynos.hevc.decoder", hardware = true)
    private val sw1 = Decoder("c2.android.hevc.decoder", hardware = false)
    private val sw2 = Decoder("OMX.google.hevc.decoder", hardware = false)

    @Test fun `software first puts every software decoder ahead, keeping the platform order`() {
        assertEquals(
            listOf(sw1, sw2, hw1, hw2),
            LocalExoPlayer.softwareFirst(listOf(hw1, sw1, hw2, sw2)) { it.hardware },
        )
    }

    @Test fun `software first without any software decoder leaves the list alone`() {
        assertEquals(listOf(hw1, hw2), LocalExoPlayer.softwareFirst(listOf(hw1, hw2)) { it.hardware })
    }

    @Test fun `an item tagged preferirSoftware prefers software`() {
        assertTrue(LocalExoPlayer.prefersSoftware(tag(preferirSoftware = true)))
    }

    @Test fun `an item without the flag keeps the platform order`() {
        assertFalse(LocalExoPlayer.prefersSoftware(tag(preferirSoftware = false)))
    }

    @Test fun `an item with no tag, or a foreign tag, keeps the platform order`() {
        assertFalse(LocalExoPlayer.prefersSoftware(null))
        assertFalse(LocalExoPlayer.prefersSoftware("not a PlayerSourceTag"))
    }

    private fun tag(preferirSoftware: Boolean) = PlayerSourceTag(
        kind = SourceKind.LOCAL,
        openingStartMs = null, openingEndMs = null, endingStartMs = null, castUrl = null,
        preferirSoftware = preferirSoftware,
    )
}
