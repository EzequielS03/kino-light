package com.arkiv.player.remote

enum class PlayKind { ARCHIVE, TORRENT, LIVE, UNKNOWN }

data class PlayPayload(
    val kind: PlayKind,
    val id: String,
    val episodeId: String,
    val startPositionMs: Long = 0,
)

data class RemoteCommand(val type: String, val play: PlayPayload?, val key: String?, val seq: Long)

/**
 * Serializa PlayPayload a un string delimitado (sin depender de org.json → testeable en JVM).
 * Formato: `arkivplay|<kind>|<id>|<episodeId>|<startMs>` con los campos URL-encoded.
 * `decode` acepta además un String legacy suelto (un episodeId crudo del LAN antiguo).
 */
object PlayPayloadCodec {
    private const val PREFIX = "arkivplay|"

    fun encode(p: PlayPayload): String {
        fun e(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return PREFIX + listOf(p.kind.name, e(p.id), e(p.episodeId), p.startPositionMs.toString()).joinToString("|")
    }

    fun decode(s: String): PlayPayload {
        if (!s.startsWith(PREFIX)) {
            // Legacy: un episodeId crudo del LAN antiguo.
            return PlayPayload(PlayKind.UNKNOWN, id = s, episodeId = s)
        }
        val parts = s.removePrefix(PREFIX).split("|")
        fun d(i: Int) = parts.getOrNull(i)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        val kind = runCatching { PlayKind.valueOf(parts.getOrNull(0) ?: "") }.getOrDefault(PlayKind.UNKNOWN)
        return PlayPayload(kind, id = d(1), episodeId = d(2), startPositionMs = parts.getOrNull(3)?.toLongOrNull() ?: 0)
    }
}
