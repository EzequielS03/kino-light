package com.arkiv.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class VlcPlaybackStateTest {
    @Test fun buffering_below_100_is_buffering() {
        assertEquals(Player.STATE_BUFFERING, VlcPlaybackState.mediaPlaybackState(VlcEvent.Buffering, 40f))
    }

    @Test fun buffering_at_100_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Buffering, 100f))
    }

    @Test fun playing_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Playing, 0f))
    }

    @Test fun paused_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Paused, 0f))
    }

    @Test fun end_reached_is_ended() {
        assertEquals(Player.STATE_ENDED, VlcPlaybackState.mediaPlaybackState(VlcEvent.EndReached, 0f))
    }

    @Test fun stopped_is_idle() {
        assertEquals(Player.STATE_IDLE, VlcPlaybackState.mediaPlaybackState(VlcEvent.Stopped, 0f))
    }

    @Test fun error_is_idle() {
        assertEquals(Player.STATE_IDLE, VlcPlaybackState.mediaPlaybackState(VlcEvent.Error, 0f))
    }
}
