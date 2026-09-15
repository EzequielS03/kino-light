package com.arkiv.player.data.magis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transport for the Magis portal. The layers above (session, catalog, resolution) depend on this
 * interface and not on [MagisPortalClient], so a double can be injected into their tests.
 */
internal interface MagisPortalClientLike {
    suspend fun call(
        path: String,
        bean: Map<String, Any?> = emptyMap(),
        baseFields: Boolean = true,
        userId: String = "",
        userToken: String = "",
        /** Overrides the device dict's `sn` for this one call. `null` (the default) keeps using
         *  whatever [MagisPortalClient]'s own `snProvider` returns (this device's stored session).
         *  Needed for account registration: that flow mints a SEPARATE, temporary device that must
         *  never overwrite the stored one's `sn` until registration actually succeeds. */
        sn: String? = null,
    ): MagisResult<JSONObject>
}

/**
 * Talks directly to the Magis portal (without going through the `arkiv-api` gateway). Port of
 * `IPTVClient.call` — `/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/vendor/iptv_client.py`,
 * which itself came from decompiling the original app.
 *
 * Three things aren't negotiable (if missing, the portal answers "版本已停止使用" or 未登录):
 *  - the body goes encrypted with [MagisCrypto] (hex(base64(3DES))), never bare JSON;
 *  - EVERY body gets [deviceDict]'s ~15 fields glued on (the app's `C6357b` interceptor);
 *  - the `apk` / `apkVer` / `spkgVer` headers always go.
 *
 * [hosts] is received through the constructor (and not read from `BuildConfig` in here) so tests
 * can point it at a `MockWebServer`; the real wiring passes it the hosts read from
 * `RemoteCredentialsStore` (see [com.arkiv.player.AppGraph.magisPortal]).
 */
internal class MagisPortalClient(
    private val crypto: MagisCrypto,
    private val hosts: List<String>,
    private val appId: String,
    private val apkVersion: String,
    private val scheme: String = "https",
    /** The minted device's `sn`, read on every call: `MagisSession` mints it and changes it live
     *  (in the Python port this was `self.device["sn"]`, the client's mutable state). Empty while
     *  there's no device — which is exactly what `v3/snToken` expects. */
    private val snProvider: () -> String = { "" },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MagisPortalClientLike {

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    /** Minimum pace between calls: the portal is sensitive to bursts (in the gateway this was a
     *  global 1.5s TokenBucket, shared across every user; here the client is a single one, so
     *  spacing them out is enough). */
    private val rateLimit = Mutex()
    private var lastCallMs = 0L

    /** Host that worked last time: tried first so as not to pay a dead host's timeout on every
     *  call (same as `self.host` in the Python port). */
    @Volatile
    private var preferredHost: String? = null

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
        sn: String?,
    ): MagisResult<JSONObject> {
        val body = buildMap<String, Any?> {
            if (baseFields) {
                put("portalCode", PORTAL_CODE)
                put("userId", userId)
                put("userToken", userToken)
            }
            putAll(bean)
            putAll(deviceDict(sn))   // the device enriches (and overwrites) whatever comes in the bean
        }
        val wire = crypto.encryptBody(JSONObject(body).toString())

        waitTurn()

        var lastError: Throwable? = null
        for (host in hostOrder()) {
            val request = Request.Builder()
                .url("$scheme://$host/api/portalCore/$path")
                .post(wire.toRequestBody(jsonType))
                .apply { headers().forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                val raw = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.body?.string().orEmpty() }
                }
                val j = JSONObject(raw)
                preferredHost = host
                val rc = j.optString("returnCode").takeIf { it.isNotEmpty() }
                if (rc != null && rc != "0") {
                    return MagisResult.PortalError(rc, j.optString("errorMessage").ifBlank { null })
                }
                val data = j.optString("data")
                return if (data.isNotEmpty()) {
                    MagisResult.Ok(JSONObject(crypto.decryptBlob(data)))
                } else {
                    MagisResult.Ok(j)
                }
            } catch (e: Throwable) {
                // Network down, TLS, or a response that isn't JSON: this host is no good, try the next one.
                lastError = e
            }
        }
        return MagisResult.RedError(lastError ?: IllegalStateException("sin hosts configurados"))
    }

    private fun hostOrder(): List<String> {
        val preferred = preferredHost ?: return hosts
        return listOf(preferred) + hosts.filter { it != preferred }
    }

    private suspend fun waitTurn() = rateLimit.withLock {
        val elapsed = System.currentTimeMillis() - lastCallMs
        if (lastCallMs != 0L && elapsed < RATE_LIMIT_MS) delay(RATE_LIMIT_MS - elapsed)
        lastCallMs = System.currentTimeMillis()
    }

    /**
     * The ~15 fields the original app glues to every body. The fixed values are the emulator's,
     * the one the protocol was captured with: changing them is untested and the portal validates
     * some of them against the minted device.
     *
     * `reserve1`/`deviceToken`/`drmId` go EMPTY on purpose — that's how production sends them and
     * how `new_anonymous_device` clears them before minting (`iptv_client.py:187-188`).
     */
    private fun deviceDict(snOverride: String? = null): Map<String, Any?> = mapOf(
        "loginType" to "2",
        "appLanguage" to "en",
        "apkVersion" to apkVersion,
        "sysVersion" to SPKG_VER,
        "appId" to appId,
        "hardwareInfo" to "ranchu",
        "model" to "sdk_gphone64_arm64",
        "product" to "sdk_gphone64_arm64",
        "cpu" to "arm64-v8a",
        "B29" to "",
        "reserve1" to "",
        "deviceToken" to "",
        "sn" to (snOverride ?: snProvider()),
        "drmId" to "",
        "sdkVer" to 36,
    )

    /** `apkVer` is a fixed literal `43404`, different from the device dict's `apkVersion`: they're
     *  two different fields of the original app, not a copy-paste error. */
    private fun headers(): Map<String, String> = mapOf(
        "apk" to appId,
        "apkVer" to "43404",
        "spkgVer" to SPKG_VER,
        "User-Agent" to "okhttp/3.12.12",
    )

    private companion object {
        const val PORTAL_CODE = "masnew"
        const val SPKG_VER = "2025-08-07 05:40:11_36_16_"
        const val RATE_LIMIT_MS = 400L
    }
}
