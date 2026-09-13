package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { UNKNOWN, MAGIS, LOCAL, LIVE, DITU }

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
     * Extra headers the origin requires, beyond Referer and User-Agent.
     *
     * Exists because magis serves its VOD behind `Content-Auth` and `Content-License`, and libVLC
     * only exposed `:http-referrer` and `:http-user-agent` -- there was no way to send it an
     * arbitrary header. These still travel through the local proxy, which can put them on the
     * request to the origin.
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Start by SOFTWARE decoding instead of hardware.
     *
     * Exists for magis's HEVC titles: on device, the hardware decoder often failed to initialise
     * with this content and libVLC used to respond by dropping every track (`pistas=v0/a0`) -- no
     * picture, no sound -- while the demuxer kept draining the file. The same titles play fine by
     * software. Knowing this ahead of time skips the failed attempt and the ~10s of black screen
     * the automatic rescue used to take to kick in.
     */
    val preferirSoftware: Boolean = false,
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
    /**
     * Prefijo de un canal en vivo (Tarea 14): `episodeId = "live:<code>"`, el mismo `code` que
     * [com.arkiv.player.ui.live.LiveController.abrir] recibe. Vive acá (y no repetido como string
     * literal en cada callsite) porque tanto quien arma la ruta de navegación
     * (ArkivRoot/ArkivTvRoot) como quien la interpreta (PlayerViewModel) tienen que coincidir.
     */
    const val LIVE_PREFIX = "live:"

    /**
     * ¿[episodeId] es un canal en vivo, de cualquier fuente? El de Magis (`live:`, ver [LIVE_PREFIX])
     * o el de Caracol ([DituLive]).
     *
     * `PlayerScreen` cuelga de acá lo que es de cualquier directo: sin barra de avance ni seek, sin
     * posición que guardar, sin "siguiente capítulo" al terminar. Lo que es solo del vivo de Magis
     * (zapeo, cajón y ficha de canales, reapertura por cortes) sigue preguntando por [SourceKind.LIVE].
     */
    fun esCanalEnVivo(episodeId: String): Boolean =
        kindFor(episodeId) == SourceKind.LIVE || DituLive.isLive(episodeId)

    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        // Caracol (Ditu). Los ids con este prefijo los arman `DituEntities`, al guardar un título de
        // Caracol en la biblioteca, y `DituLive`, para un canal en vivo: sus `PREFIX` tienen que
        // empezar con este.
        episodeId.startsWith("ditu:") -> SourceKind.DITU
        episodeId.startsWith(LIVE_PREFIX) -> SourceKind.LIVE
        // UNKNOWN covers ids from sources removed from this branch (torrent, archive.org, web): the
        // player answers them with a "no longer available" error, see PlayerViewModel.loadUnknownSource.
        else -> SourceKind.UNKNOWN
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)

/**
 * Encodes/decodes the [PlayerSourceTag] fields that cross the controller→session IPC boundary as a
 * plain map, free of `android.os.Bundle` so the round trip is testable without Robolectric (this
 * project has none -- same convention as `LocalFilePaths`/`FreeSpacePolicy` and friends). The
 * `android.os.Bundle` is only touched at the very edges, by `PlayerScreen.localMediaItems` (writing,
 * via [PlayerSourceTag.toIpcBundle]) and `PlaybackService.MediaItemResolverCallback` (reading).
 */
internal object PlayerSourceTagIpc {

    fun encode(tag: PlayerSourceTag): Map<String, Any> = buildMap {
        put("kind", tag.kind.name)
        tag.referer?.let { put("referer", it) }
        tag.userAgent?.let { put("userAgent", it) }
        tag.castUrl?.let { put("castUrl", it) }
        tag.proxyUrl?.let { put("proxyUrl", it) }
        tag.openingStartMs?.let { put("openingStartMs", it) }
        tag.openingEndMs?.let { put("openingEndMs", it) }
        tag.endingStartMs?.let { put("endingStartMs", it) }
        if (tag.preferirSoftware) put("preferirSoftware", true)
    }

    /**
     * Null when [extras] never went through [encode] (no `"kind"` key) -- same guard
     * `MediaItemResolverCallback` used to run directly against the `Bundle`.
     */
    fun decode(extras: Map<String, Any?>): PlayerSourceTag? {
        if ("kind" !in extras) return null
        return PlayerSourceTag(
            kind = runCatching { SourceKind.valueOf(extras["kind"] as String) }.getOrDefault(SourceKind.UNKNOWN),
            openingStartMs = extras["openingStartMs"] as? Long,
            openingEndMs = extras["openingEndMs"] as? Long,
            endingStartMs = extras["endingStartMs"] as? Long,
            castUrl = extras["castUrl"] as? String,
            referer = extras["referer"] as? String,
            userAgent = extras["userAgent"] as? String,
            proxyUrl = extras["proxyUrl"] as? String,
            // If a field gets added to the tag, it has to be wired HERE and in [encode]: the tag
            // doesn't cross the IPC boundary itself, and whatever is missing arrives at its default,
            // in silence. This happened once: preferirSoftware stayed false and magis's HEVC kept
            // opening in hardware.
            preferirSoftware = extras["preferirSoftware"] as? Boolean ?: false,
        )
    }
}

/** The thin `Bundle` adapter around [PlayerSourceTagIpc.encode], for [MediaItem.RequestMetadata]. */
fun PlayerSourceTag.toIpcBundle(): android.os.Bundle = android.os.Bundle().apply {
    PlayerSourceTagIpc.encode(this@toIpcBundle).forEach { (key, value) ->
        when (value) {
            is String -> putString(key, value)
            is Long -> putLong(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }
}

/** The thin `Bundle` adapter around [PlayerSourceTagIpc.decode], for `MediaSession.Callback`. */
internal fun PlayerSourceTagIpc.decodeFromBundle(extras: android.os.Bundle?): PlayerSourceTag? {
    if (extras == null) return null
    return decode(extras.keySet().associateWith { extras.get(it) })
}
