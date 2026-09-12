package com.arkiv.player.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

/**
 * Tells the receiver how long the title actually is.
 *
 * media3's [DefaultMediaItemConverter] never sets a stream duration -- it builds the `MediaInfo`
 * with `STREAM_TYPE_BUFFERED` and leaves the length for the receiver to work out from the media.
 * For a normal file that is fine. For a fragmented MP4 that is still being WRITTEN it is not: the
 * receiver reads the fragments that exist and concludes the title is five seconds long. Measured
 * 2026-09-12 -- `dur=5166ms` while the position was already past 24 s -- and it costs twice over.
 * The progress bar lies and there is nothing to seek along, and every few seconds playback reaches
 * that imaginary end, stalls into buffering, gets handed more data and resumes. That was the
 * "loading" appearing every six seconds, with a remux running 2.5 MB/s ahead of playback and not a
 * byte missing.
 *
 * The duration travels in the MediaItem's request metadata because that is the one bag that
 * survives being turned into a `MediaQueueItem`: [CastRequest] carries it, `CastSessionManager`
 * puts it there, and this reads it back out.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ConversorConDuracion : MediaItemConverter {

    private val base = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val item = base.toMediaQueueItem(mediaItem)
        val ms = mediaItem.requestMetadata.extras?.getLong(CLAVE_DURACION, C.TIME_UNSET)
            ?: C.TIME_UNSET
        if (ms <= 0L) return item

        val info = item.media ?: return item
        val conDuracion = MediaInfo.Builder(info.contentId)
            .setStreamType(info.streamType)
            .setContentType(info.contentType)
            .setContentUrl(info.contentUrl ?: info.contentId)
            .setMetadata(info.metadata)
            .setStreamDuration(ms)
            .setCustomData(info.customData)
            .build()
        android.util.Log.i(TAG, "telling the receiver the title runs ${ms}ms")
        return MediaQueueItem.Builder(conDuracion).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        base.toMediaItem(mediaQueueItem)

    companion object {
        /** Key under which the duration rides in `MediaItem.requestMetadata.extras`. */
        const val CLAVE_DURACION = "arkiv.durationMs"

        private const val TAG = "ArkivCast"
    }
}
