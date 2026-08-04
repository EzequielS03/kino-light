package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { ARCHIVE, TORRENT, WEB, NUC }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
    val referer: String? = null,      // headers para streams web (algunos hosts exigen Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,     // web: URL proxeada de respaldo si la directa falla (403/geo/anti-leech)
)

object PlayerSource {
    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("torrent:") -> SourceKind.TORRENT
        episodeId.startsWith("web:") -> SourceKind.WEB
        else -> SourceKind.ARCHIVE
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)
