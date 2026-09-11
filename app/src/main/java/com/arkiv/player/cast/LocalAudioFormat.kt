package com.arkiv.player.cast

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.arkiv.player.playback.AudioTrackFormat

/**
 * The audio the LOCAL player is using, for the cast audio gate ([CastAudioSupport.receiverDecodes]).
 *
 * The local player is now the ExoPlayer hosted by `PlaybackService`, so this reads the `Tracks` its
 * `MediaController` reports: the selected audio track, or the first one when none is selected yet.
 * [AudioTrackFormat.index] is the position among the audio tracks, which is what the transcoder's
 * `--audio-track` expects.
 *
 * Temporary bridge: the gate still speaks libVLC fourccs ([fourccForAudioMime]). The cast task that
 * removes the transcoder replaces the gate with one based on `Format.sampleMimeType` directly.
 */
internal fun localAudioTrackFormat(tracks: Tracks): AudioTrackFormat? {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.isEmpty()) return null
    val index = audio.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0
    val group = audio[index]
    val track = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
    val format = group.getTrackFormat(track)
    return AudioTrackFormat(
        fourcc = fourccForAudioMime(format.sampleMimeType),
        channels = format.channelCount.coerceAtLeast(0),
        index = index,
    )
}

/**
 * ExoPlayer's audio mime type as the libVLC fourcc the gate knows.
 *
 * `null` (track not parsed yet) maps to the gate's "no info", which keeps its conservative "don't
 * know → send it directly". Any other mime the table doesn't know maps to a fourcc outside the
 * gate's whitelist, so it is transcoded rather than assumed decodable — the gate's own rule.
 */
internal fun fourccForAudioMime(mime: String?): Int = when (mime) {
    null -> 0
    MimeTypes.AUDIO_AAC -> CastAudioSupport.fourccOf("mp4a")
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> CastAudioSupport.fourccOf("mpga")
    MimeTypes.AUDIO_OPUS -> CastAudioSupport.fourccOf("Opus")
    MimeTypes.AUDIO_VORBIS -> CastAudioSupport.fourccOf("vorb")
    MimeTypes.AUDIO_FLAC -> CastAudioSupport.fourccOf("flac")
    MimeTypes.AUDIO_RAW -> CastAudioSupport.fourccOf("araw")
    MimeTypes.AUDIO_AC3 -> CastAudioSupport.fourccOf("a52 ")
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> CastAudioSupport.fourccOf("eac3")
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> CastAudioSupport.fourccOf("dts ")
    MimeTypes.AUDIO_TRUEHD -> CastAudioSupport.fourccOf("mlp ")
    else -> CastAudioSupport.fourccOf("unkn")
}
