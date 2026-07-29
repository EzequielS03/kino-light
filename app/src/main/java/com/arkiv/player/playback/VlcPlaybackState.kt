package com.arkiv.player.playback

import androidx.media3.common.Player

/** Evento de reproducción de libVLC normalizado (independiente del tipo nativo de VLC). */
enum class VlcEvent { Buffering, Playing, Paused, Stopped, EndReached, Error }

/** Traduce eventos de VLC al modelo de estado de media3. Pura: testeable sin Android/VLC. */
object VlcPlaybackState {
    fun mediaPlaybackState(event: VlcEvent, bufferingPercent: Float): Int = when (event) {
        VlcEvent.Buffering -> if (bufferingPercent >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
        VlcEvent.Playing, VlcEvent.Paused -> Player.STATE_READY
        VlcEvent.EndReached -> Player.STATE_ENDED
        VlcEvent.Stopped, VlcEvent.Error -> Player.STATE_IDLE
    }
}
