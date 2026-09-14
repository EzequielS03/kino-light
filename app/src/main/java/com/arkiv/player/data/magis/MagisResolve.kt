package com.arkiv.player.data.magis

import com.arkiv.player.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class MagisSubtitle(val lang: String, val url: String, val format: String)

/**
 * What's needed to play a Magis title. It's deliberately a mirror of `GatewayPlayable`: whoever
 * wires this in only has to copy fields, without deciding anything.
 */
internal data class MagisPlayable(
    val url: String,
    val headers: Map<String, String>,
    val mime: String,
    /** The container as the portal NAMES it ("ts", "mp4"). Not inferred from [url]'s extension:
     *  this code builds that by collapsing to `.mp4` everything the portal doesn't call `ts`. */
    val container: String,
    val videoCodec: String,
    val durationMs: Long,
    val subtitles: List<MagisSubtitle>,
)

/**
 * VOD resolution: from the `contentId` to the CDN's URL with its headers. Port of
 * `MagisAdapter.resolve` (`adapters/magis/adapter.py`).
 *
 * It's two portal calls: `v10/startPlayVOD` (the track and its license, by title) and
 * `v14/getSlbInfo` (the CDN and the `Content-Auth`, by SESSION — the same for every title, which
 * is why it's cached here).
 */
internal class MagisResolve(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val appId: String = BuildConfig.IPTV_APP_ID,
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val slbLock = Mutex()
    private var slb: JSONObject? = null
    private var slbExpiresMs = 0L
    private var slbTokenOwner = ""

    /**
     * [contentId] has to be the PLAYABLE TRACK's: a series' `contentId` doesn't play
     * (`play_vod` on it returns `节目不存在`), the chapter's and the series' have to be passed in
     * [seriesContentId].
     */
    suspend fun resolveVod(
        contentId: String,
        seriesContentId: String? = null,
    ): MagisResult<MagisPlayable> {
        val sessionResult = session.ensureSession()
        if (sessionResult !is MagisResult.Ok) return sessionResult.asError()

        val play = session.withValidSession {
            portal.call(
                path = "v10/startPlayVOD",
                bean = mapOf(
                    "contentId" to contentId,
                    "seriesContentId" to seriesContentId.orEmpty(),
                    "startTime" to 0,
                    "type" to "1",
                    "columnId" to 0,
                    "authType" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.getOrNull() ?: return play.asError()

        val media = bestMedia(playJson)
            ?: return MagisResult.PortalError("sin_media", "magis devolvió sin media reproducible")
        val license = media.optJSONArray("licenseList")?.optJSONObject(0)?.optString("license")
            ?.takeIf { it.isNotBlank() }
            ?: return MagisResult.PortalError("sin_license", "magis devolvió sin licenseList")

        val currentSlb = sessionSlb()
        val slbJson = currentSlb.getOrNull() ?: return currentSlb.asError()
        val cdn = vodCdn(slbJson)
            ?: return MagisResult.PortalError("sin_cdn_vod", "magis no expuso CDN de vod con token libre")

        // `container` is what the portal SAYS and travels raw to the demuxer; `ext` is the CDN
        // object's key, which only exists in two flavors. Asking for `.mp4` when the portal said
        // something else is the only thing that can be done, but it doesn't turn the file into an mp4.
        val container = media.optString("videoFormat").lowercase()
        val ext = if (container == "ts") "ts" else "mp4"

        return MagisResult.Ok(
            MagisPlayable(
                url = "${cdn.base}/vod/${media.optString("contentId")}_media.$ext",
                headers = mapOf(
                    "Content-Auth" to cdn.auth,
                    "Content-License" to license,
                    "User-Agent" to UA_CDN,
                    "App" to appId,
                    "App-Version" to apkVersion,
                ),
                mime = if (ext == "mp4") "video/mp4" else "video/mp2t",
                container = container,
                videoCodec = media.optString("encodeFormat").lowercase(),
                durationMs = portalDurationMs(media.opt("duration")),
                subtitles = readSubtitles(playJson),
            ),
        )
    }

    /**
     * The track most likely to play on any device: **h264 first and only then mp4**, in that
     * precedence order and not both at once. Requiring both and falling to the first candidate
     * delivered h265 on titles served only in TS (which is almost all of them), and an HEVC the
     * hardware decoder won't start leaves the screen black.
     *
     * Stable: between two equally good ones, whichever the portal offered first wins.
     */
    private fun bestMedia(play: JSONObject): JSONObject? {
        val episode = play.optJSONArray("episodeList")?.optJSONObject(0) ?: return null
        val candidates = mutableListOf<JSONObject>()
        episode.optJSONArray("totalMovieList")?.forEachObject { tm ->
            tm.optJSONArray("movieList")?.forEachObject { candidates.add(it) }
        }
        return candidates.minByOrNull { m ->
            val codec = if (m.optString("encodeFormat").lowercase() == "h264") 0 else 2
            val container = if (m.optString("videoFormat").lowercase() == "mp4") 0 else 1
            codec + container
        }
    }

    /** `subtitleList[].file[0].url`. The portal sometimes lists a language that never got uploaded: discarded. */
    private fun readSubtitles(play: JSONObject): List<MagisSubtitle> {
        val episode = play.optJSONArray("episodeList")?.optJSONObject(0) ?: return emptyList()
        val output = mutableListOf<MagisSubtitle>()
        episode.optJSONArray("subtitleList")?.forEachObject { sub ->
            val file = sub.optJSONArray("file")?.optJSONObject(0) ?: return@forEachObject
            val url = file.optString("url").takeIf { it.isNotBlank() } ?: return@forEachObject
            output.add(
                MagisSubtitle(
                    lang = sub.optString("language"),
                    url = url,
                    format = file.optString("fileType").ifBlank { "srt" },
                ),
            )
        }
        return output
    }

    private data class CdnVod(val base: String, val auth: String)

    /** The VOD CDN and the free tier's `Content-Auth`. */
    private fun vodCdn(slb: JSONObject): CdnVod? {
        slb.optJSONArray("cdn_list")?.forEachObject { cdn ->
            if (cdn.optString("tag") != "vod") return@forEachObject
            cdn.optJSONArray("url_list")?.forEachObject { u ->
                val url = u.optString("url")
                val matches = isCfl(url) || u.optString("sign_type") == "cfl"
                if (matches && u.optString("tag") == "free") {
                    return CdnVod(base = withScheme(cdn.optString("main_addr")), auth = url)
                }
            }
        }
        return null
    }

    /**
     * This session's `getSlbInfo`, requested ONCE while it keeps working: it doesn't receive the
     * `contentId` because the CDN and the `Content-Auth` are the same for every title. Requesting
     * it on every resolution is a trip to the portal (with its rate limit) the user pays for by
     * watching the spinner.
     */
    private suspend fun sessionSlb(): MagisResult<JSONObject> = slbLock.withLock {
        val current = slb
        if (current != null && nowMs() < slbExpiresMs && slbTokenOwner == session.userToken) {
            return@withLock MagisResult.Ok(current)
        }
        val r = session.withValidSession {
            portal.call(
                path = "v14/getSlbInfo",
                bean = slbRequestParams(apkVersion),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val fresh = r.getOrNull() ?: return@withLock r
        val ttl = slbLifetime(fresh)
        if (ttl > 0) {
            slb = fresh
            slbExpiresMs = nowMs() + ttl * 1000L
            slbTokenOwner = session.userToken
        } else {
            // An slb that's no longer good does NOT get saved: saving it would leave the session
            // broken and silent until it expired. It's returned anyway, since it's all there is.
            slb = null
        }
        MagisResult.Ok(fresh)
    }

    /**
     * Seconds this `getSlbInfo` can be kept for. Whichever expires first wins: the `invalidTime`
     * the portal declares (measured: 14400 = 4h) or the `expired=<unix>` the `Content-Auth` itself
     * carries in its querystring. A cache that outlives the token hands out a dead auth and the
     * failure happens inside the player, with no visible error anywhere.
     */
    private fun slbLifetime(slb: JSONObject): Long {
        val declared = slb.optString("invalidTime").toLongOrNull()?.takeIf { it > 0 } ?: TTL_SLB_S
        val auth = vodCdn(slb)?.auth ?: return 0
        val expiresAt = EXPIRED.find(auth)?.groupValues?.get(1)?.toLongOrNull()
            ?: return declared
        return minOf(declared, expiresAt - nowMs() / 1000 - AUTH_MARGIN_S)
    }

    private companion object {
        const val UA_CDN = "Ranger/4.9.4-17294ac0"

        /** Conservative, for when the portal doesn't declare `invalidTime`. */
        const val TTL_SLB_S = 300L
        const val AUTH_MARGIN_S = 300L
        val EXPIRED = Regex("""expired=(\d+)""")

    }
}

/**
 * Duration in ms of what the portal sends: it arrives as "HH:MM:SS", "MM:SS" or bare seconds
 * (number or text). Anything else is worth 0 — for drawing the bar, no duration (the app polls the
 * PCRs) beats a made-up one, which on top of that sends seeking anywhere.
 */
internal fun portalDurationMs(raw: Any?): Long {
    if (raw == null || raw == JSONObject.NULL) return 0
    if (raw is Number) return (raw.toDouble() * 1000).toLong()
    val text = raw.toString().trim()
    if (text.isEmpty()) return 0
    val parts = text.split(":")
    if (parts.size > 3 || parts.any { it.isBlank() || !it.all(Char::isDigit) }) return 0
    val seconds = parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    return seconds * 1000
}
