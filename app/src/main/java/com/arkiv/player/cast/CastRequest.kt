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
 * Torrent: la URL es la del servidor HTTP del PROPIO celu en la LAN, porque el receptor tiene que
 * poder descargarla; y se manda el MIME real del stream, no uno inventado.
 * Archive/web: se prefiere `castUrl` (mp4 h.264, compatible con el receptor) sobre `mediaUrl`.
 */
object CastRequestBuilder {

    private const val MIME_MP4 = "video/mp4"

    @Suppress("LongParameterList")
    fun build(
        episodeId: String,
        title: String,
        subtitle: String,
        artworkUrl: String,
        mediaUrl: String,
        castUrl: String?,
        isTorrent: Boolean,
        lanUrl: String?,
        lanMime: String?,
        startPositionMs: Long,
    ): CastRequest? {
        val uri = if (isTorrent) lanUrl else castUrl?.takeIf { it.isNotBlank() } ?: mediaUrl
        if (uri.isNullOrBlank()) return null
        return CastRequest(
            uri = uri,
            mimeType = if (isTorrent) (lanMime ?: MIME_MP4) else mimeForUrl(uri),
            episodeId = episodeId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            startPositionMs = startPositionMs.coerceAtLeast(0),
        )
    }

    /** MIME por extensión. El receptor decide por esto, así que inventarlo se paga con un
     *  video que no arranca o que arranca sin sonido. */
    internal fun mimeForUrl(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
    }
}
