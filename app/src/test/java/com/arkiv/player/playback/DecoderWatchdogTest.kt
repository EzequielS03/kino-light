package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local-playback decoder watchdog: a downloaded HEVC file whose hardware decoder accepts the
 * stream but never paints gets ONE reload that prefers a software decoder. These tests pin when it
 * fires and, above all, when it must not.
 */
class DecoderWatchdogTest {

    private fun decide(
        waitingMs: Long = DecoderWatchdog.NO_FRAME_MS,
        renderedFirstFrame: Boolean = false,
        videoTracks: Int = 1,
        wantsToPlay: Boolean = true,
        hasSurface: Boolean = true,
        alreadySoftware: Boolean = false,
    ) = DecoderWatchdog.shouldReloadInSoftware(
        waitingMs = waitingMs,
        renderedFirstFrame = renderedFirstFrame,
        videoTracks = videoTracks,
        wantsToPlay = wantsToPlay,
        hasSurface = hasSurface,
        alreadySoftware = alreadySoftware,
    )

    @Test fun `no frame after the bound with a video track and a surface reloads in software`() {
        assertTrue(decide())
    }

    @Test fun `before the bound it waits`() {
        assertFalse(decide(waitingMs = DecoderWatchdog.NO_FRAME_MS - 1))
    }

    @Test fun `nothing loaded never fires`() {
        assertFalse(decide(waitingMs = -1L))
    }

    @Test fun `a frame already rendered disarms it`() {
        assertFalse(decide(waitingMs = 60_000L, renderedFirstFrame = true))
    }

    /** Audio-only content has no picture to wait for. */
    @Test fun `without a video track it never fires`() {
        assertFalse(decide(videoTracks = 0))
    }

    /** In the background there is no surface, so no frame can render: that is not a decoder fault. */
    @Test fun `without a surface it never fires`() {
        assertFalse(decide(hasSurface = false))
    }

    /** Paused before the first frame: nothing is supposed to render. */
    @Test fun `paused it never fires`() {
        assertFalse(decide(wantsToPlay = false))
    }

    /** Once: a software load that still paints nothing is not reloaded again. */
    @Test fun `it fires only once per load`() {
        assertFalse(decide(waitingMs = 60_000L, alreadySoftware = true))
    }

    /**
     * The reload must land while the first-frame spinner still covers the screen, or the person sees
     * the black-with-audio gap this whole thing exists to hide.
     */
    @Test fun `the bound is shorter than the first-frame spinner cap`() {
        assertTrue(DecoderWatchdog.NO_FRAME_MS < EsperaDePrimeraImagen.TOPE_MS)
    }
}
