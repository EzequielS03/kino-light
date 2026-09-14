package com.arkiv.player.data.magis

import com.arkiv.player.BuildConfig
import com.arkiv.player.data.gateway.CdnDeCanal
import com.arkiv.player.data.gateway.LiveSession
import org.json.JSONObject

/**
 * Live channel straight from the portal. Returns the same [LiveSession] `LiveApi.resolver` used to
 * build against the gateway, so `LiveHlsProxy` doesn't change: it keeps asking the CDN for
 * `http://<cflHost>/live/<playCode>.m3u8` and signing each segment on the device
 * ([com.arkiv.player.playback.LocalSignature]).
 *
 * It's two portal calls and they're NOT cached: `main_addr` rotates on every response, and an old
 * host answers 403.
 */
internal class MagisLive(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun resolveChannel(channelCode: String): MagisResult<LiveSession> {
        // Live requires a real account: with an anonymous session the portal answers `aaa100028`
        // ("未登录！"). Cut here to avoid spending two calls and to be able to say why.
        if (!session.hasAccountLinked) {
            return MagisResult.PortalError(
                NO_ACCOUNT,
                "el canal en vivo exige una cuenta de Magis vinculada: la sesión anónima no alcanza",
            )
        }
        val sessionResult = session.ensureSession()
        if (sessionResult !is MagisResult.Ok) return sessionResult.asError()

        val play = session.withValidSession {
            portal.call(
                path = "v4/startPlayLive",
                bean = mapOf("channelCode" to channelCode, "columnId" to 0, "type" to "1"),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.getOrNull() ?: return play.asError()
        val signal = signalFrom(playJson)
            ?: return MagisResult.PortalError(
                NO_ADDRESSES,
                "el portal no dio direcciones para $channelCode",
            )

        val slb = session.withValidSession {
            portal.call(
                path = "v14/getSlbInfo",
                // The CHANNEL's code, not the playCode: the portal returns that signal's hosts.
                bean = slbRequestParams(apkVersion, liveCodes = listOf(channelCode)),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val slbJson = slb.getOrNull() ?: return slb.asError()

        val cdns = liveCdns(slbJson)
        if (cdns.isEmpty()) {
            return MagisResult.PortalError(
                NO_CDN,
                "no hay entrada CDN cfl de vivo para $channelCode",
            )
        }

        val channelSession = LiveSession(
            cflHost = cdns.first().cflHost,
            authBase = cdns.first().authBase,
            license = signal.license,
            channel = channelCode,
            expiresAt = nowMs() / 1000 + ttlOf(slbJson),
            // What the signal is called ON THE CDN, which isn't always the channel's code
            // (measured: `cyx-RCNHD` is served as `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`). Asking the
            // CDN for the channel's code is asking for a different signal from the one the license
            // authorizes: 401.
            playCode = signal.playCode.ifBlank { channelCode },
            cdns = cdns,
        )

        // What follows is the same validation `LiveApi.resolver` used to do on the gateway's
        // response, and for the same reason: `LiveHlsProxy` uses these fields as-is against the
        // real CDN, so an empty one here shows back up as an opaque 401/403, far from where it
        // originated.
        if (channelSession.license.isBlank()) {
            return MagisResult.PortalError(NO_LICENSE, "el portal dio $channelCode sin licencia")
        }
        if (channelSession.token.isBlank()) {
            return MagisResult.PortalError(
                NO_TOKEN,
                "el authBase de $channelCode no trae token=<32 hex>",
            )
        }
        return MagisResult.Ok(channelSession)
    }

    /**
     * Like [resolveChannel] but throwing, which is what whoever opens a channel expects (it used to
     * be thrown by `LiveApi.resolver`). The message carries the portal's reason: it's what shows up
     * when a channel doesn't open, and a bare "couldn't open it" leaves nothing to diagnose.
     */
    suspend fun resolveOrThrow(channelCode: String): LiveSession {
        val r = resolveChannel(channelCode)
        return r.getOrNull() ?: throw com.arkiv.player.data.gateway.GatewayException(
            when (r) {
                is MagisResult.PortalError -> "vivo: ${r.code}${r.msg?.let { " ($it)" }.orEmpty()}"
                is MagisResult.RedError -> "vivo: no se pudo hablar con el portal (${r.cause.message})"
                is MagisResult.Ok -> "vivo: el portal no dio sesión"
            },
        )
    }

    private data class Signal(val playCode: String, val license: String)

    /**
     * `playCode` and the license come from THE SAME entry, never crossed: the license authorizes
     * ONE signal, and pairing it with another's playCode is exactly the pair the CDN rejects. It's
     * what the original app does (decompiled, `sources/h8/v3.java`): discards addresses with no
     * playCode or no license and uses the pair from whichever was left.
     *
     * The first COMPLETE one is preferred; if none is, the first with a license and no playCode is
     * good enough — there are channels whose playCode the portal doesn't send and that work with
     * the channel's code, so fixing some can't break those.
     */
    private fun signalFrom(play: JSONObject): Signal? {
        var firstLicense: String? = null
        play.optJSONArray("liveAddressList")?.forEachObject { a ->
            val license = a.optString("license")
            val playCode = a.optString("playCode")
            if (firstLicense == null) firstLicense = license
            if (playCode.isNotBlank() && license.isNotBlank()) {
                return Signal(playCode = playCode, license = license)
            }
        }
        return firstLicense?.let { Signal(playCode = "", license = it) }
    }

    /**
     * ALL servable live CDNs, not the first one: measured on 2026-08-14 the portal returns three
     * and only one was used; that day the CDN answered 401 and the channel died while having
     * another host in the SAME response. Each with ITS OWN `authBase`, because the token travels
     * inside that url and signing with one against another's host is the same failure mode.
     */
    private fun liveCdns(slb: JSONObject): List<CdnDeCanal> {
        val output = mutableListOf<CdnDeCanal>()
        slb.optJSONArray("cdn_list")?.forEachObject { cdn ->
            if (cdn.optString("tag") != "live") return@forEachObject
            cdn.optJSONArray("url_list")?.forEachObject { u ->
                val url = u.optString("url")
                if (!isCfl(url) && u.optString("sign_type") != "cfl") return@forEachObject
                val host = bareHost(cdn.optString("main_addr"))
                if (host.isNotBlank()) output.add(CdnDeCanal(cflHost = host, authBase = url))
            }
        }
        return output
    }

    /**
     * How long this CDN info is good for, ACCORDING TO THE PORTAL: `invalidTime` (measured: 14400 =
     * 4h). Without the field it falls back to the conservative value — re-resolving extra costs a
     * few seconds, serving an expired host cuts playback off.
     */
    private fun ttlOf(slb: JSONObject): Long =
        slb.optString("invalidTime").toLongOrNull()?.takeIf { it > 0 } ?: CHANNEL_TTL_S

    internal companion object {
        const val CHANNEL_TTL_S = 300L

        const val NO_ACCOUNT = "live_no_account"
        const val NO_ADDRESSES = "live_no_addresses"
        const val NO_CDN = "live_no_cfl_cdn"
        const val NO_LICENSE = "live_no_license"
        const val NO_TOKEN = "live_no_cfl_token"
    }
}
