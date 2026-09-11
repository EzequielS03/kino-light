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
    /** Punto del video donde arranca el stream cuando va transcodificado. El receptor cuenta desde
     *  cero a partir de acá, así que sin esto la posición que se guarda queda corrida. */
    val baseOffsetMs: Long = 0,
    /** Duración real del contenido. El stream transcodificado sale "en vivo" y el receptor no la
     *  sabe, pero el celu sí porque la leyó del archivo. */
    val knownDurationMs: Long = 0,
)

/**
 * Deriva la petición de cast según la fuente. Pura: testeable sin Android.
 *
 * Archive/web: se prefiere `castUrl` (mp4 h.264, compatible con el receptor) sobre `mediaUrl`.
 * Live (Task 18): the URL is that of the LOCAL HTTP server (the `LiveHlsProxy` proxy) reachable
 * over the LAN -- `mediaUrl` is always the loopback that VLC consumes on this same device, and
 * `castUrl` doesn't exist for live channels (there's never a fallback mp4 h.264, it's a live
 * feed). A live feed also has no "where you were": `startPositionMs` is forced to 0 no matter
 * what's requested, and the MIME is always that of an HLS playlist, not what the file extension
 * would guess (`mimeForUrl` doesn't know `.m3u8`).
 */
object CastRequestBuilder {

    private const val MIME_MP4 = "video/mp4"

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
        lanMime: String?,
        startPositionMs: Long,
        isLive: Boolean = false,
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
     * MIME por extensión. El receptor decide por esto, así que inventarlo se paga con un video que
     * no arranca o que arranca sin sonido.
     *
     * Acá NO se pueden mirar los bytes (la URL es remota y no hay archivo que abrir), así que la
     * extensión es todo lo que hay; lo que sí se comparte con el resto de la app es la TABLA, para
     * que no vuelva a haber tres versiones distintas de "qué MIME tiene un .ts". On-disk files
     * —local downloads— do resolve it by signature, and those are the ones that come in through
     * `lanMime`. Ver [com.arkiv.player.playback.ContenedorDeVideo].
     */
    internal fun mimeForUrl(url: String): String =
        com.arkiv.player.playback.ContenedorDeVideo.mimePorNombre(url)
}
