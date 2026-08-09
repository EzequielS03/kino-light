package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { ARCHIVE, TORRENT, WEB, MAGIS, NUC, LOCAL }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
    val referer: String? = null,      // headers para streams web (algunos hosts exigen Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,     // web: URL proxeada de respaldo si la directa falla (403/geo/anti-leech)
    /**
     * Headers extra que exige el origen, más allá de Referer y User-Agent.
     *
     * Existe porque magis sirve el VOD detrás de `Content-Auth` y `Content-License`, y libVLC solo
     * expone `:http-referrer` y `:http-user-agent`: no hay forma de mandarle un header cualquiera.
     * Estos viajan por el proxy local, que sí puede ponerlos en la petición al origen.
     */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    /** Todos los headers del origen en un solo mapa, para quien pueda mandarlos completos. */
    val allHeaders: Map<String, String>
        get() = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            putAll(extraHeaders)
        }
}

object PlayerSource {
    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("torrent:") -> SourceKind.TORRENT
        episodeId.startsWith("web:") -> SourceKind.WEB
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        else -> SourceKind.ARCHIVE
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)
