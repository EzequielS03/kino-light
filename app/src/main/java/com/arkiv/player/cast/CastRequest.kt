package com.arkiv.player.cast

/** Lo que se le manda al receptor de Chromecast. */
data class CastRequest(
    val uri: String,
    val mimeType: String,
    val episodeId: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val startPositionMs: Long,
)

/**
 * Deriva la petición de cast según la fuente. Pura: testeable sin Android.
 *
 * Local and magis: `castUrl` (mp4 h.264, compatible with the receiver) is preferred over
 * `mediaUrl` when it's set -- archive.org and web, which were the origin of this case, were
 * removed in this branch's pruning.
 * Live (Task 18): the URL is that of the LOCAL HTTP server (the `LiveHlsProxy` proxy) reachable
 * over the LAN -- `mediaUrl` is always the loopback the local player consumes on this same device, and
 * `castUrl` doesn't exist for live channels (there's never a fallback mp4 h.264, it's a live
 * feed). A live feed also has no "where you were": `startPositionMs` is forced to 0 no matter
 * what's requested, and the MIME is always that of an HLS playlist, not what the file extension
 * would guess (`mimeForUrl` doesn't know `.m3u8`).
 */
object CastRequestBuilder {

    /** El Default Media Receiver de Chromecast decide por esto si abrir el stream como HLS. */
    private const val MIME_HLS = "application/vnd.apple.mpegurl"

    @Suppress("LongParameterList")
    fun build(
        episodeId: String,
        title: String,
        subtitle: String,
        artworkUrl: String,
        mediaUrl: String,
        castUrl: String?,
        lanUrl: String?,
        startPositionMs: Long,
        isLive: Boolean = false,
        mimeOverride: String? = null,
    ): CastRequest? {
        val uri = when {
            isLive -> lanUrl
            else -> castUrl?.takeIf { it.isNotBlank() } ?: mediaUrl
        }
        if (uri.isNullOrBlank()) return null
        return CastRequest(
            uri = uri,
            mimeType = when {
                isLive -> MIME_HLS
                // What the bytes say, when the caller could read them. [mimeForUrl] guesses from the
                // extension, and the local file server's URL has none ("…/file"), so it always fell
                // back to mp4 while the server served the real thing -- we announced one container
                // and delivered another, which is exactly the mistake this file's KDoc warns about.
                mimeOverride != null -> mimeOverride
                else -> mimeForUrl(uri)
            },
            episodeId = episodeId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            startPositionMs = if (isLive) 0L else startPositionMs.coerceAtLeast(0),
        )
    }

    /**
     * MIME by extension. The receiver decides based on this, so getting it wrong is paid for with
     * a video that doesn't start, or starts with no sound.
     *
     * Here the bytes can't be inspected (the URL is remote, there's no file to open), so the
     * extension is all there is; what IS shared with the rest of the app is the TABLE, so there
     * aren't three different versions of "what MIME does a .ts have" floating around. See
     * [com.arkiv.player.playback.ContenedorDeVideo].
     */
    internal fun mimeForUrl(url: String): String =
        com.arkiv.player.playback.ContenedorDeVideo.mimePorNombre(url)
}
