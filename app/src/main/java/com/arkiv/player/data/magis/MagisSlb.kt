package com.arkiv.player.data.magis

import org.json.JSONArray
import org.json.JSONObject

/**
 * `v14/getSlbInfo` is the same call for VOD and for live: it returns the CDNs and the free tier's
 * token. The only thing that changes is [liveCodes] — for live, the code of the channel being
 * resolved goes here, because the portal returns THAT signal's hosts.
 */
internal fun slbRequestParams(
    apkVersion: String,
    liveCodes: List<String> = listOf("masnew_live"),
): Map<String, Any?> = mapOf(
    "hasPay" to "0",
    "userIdentity" to "1",
    "type" to "merge",
    "appVer" to apkVersion,
    "lang" to "es",
    "encMediaSupported" to 1,
    // Explicit JSONArray: Android's `JSONObject(Map)` does NOT convert a Kotlin `List`, it
    // serializes it as the text "[masnew_live]" and the portal gets garbage.
    "liveCodeList" to JSONArray(liveCodes),
    "appParams" to "",
    "reserve1" to "02:00:00:00:00:00",
    "pipFlag" to "0",
)

/**
 * Exact `sign_type=cfl`, not a similar-looking prefix like `cflx`. `getSlbInfo`'s `url` field ISN'T
 * a URL: it's a loose querystring, with no scheme or `?` (e.g. `cdn_type=1&sign_type=cfl&token=ABC`),
 * so parsing it as a URL never finds the parameter and no entry ever matches.
 */
internal fun isCfl(url: String): Boolean = url.substringAfterLast('?')
    .split('&')
    .any { it.trim() == "sign_type=cfl" }

/**
 * `main_addr` with a scheme, to build a complete URL (VOD). It arrives with a scheme in
 * production —and sometimes with a path, which is kept—, but without this a bare host would build
 * a URL the player can't open, and the failure would show up far from where it originated.
 */
internal fun withScheme(mainAddr: String): String {
    val clean = mainAddr.trimEnd('/')
    return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
    else "https://$clean"
}

/**
 * Just `main_addr`'s host, with no scheme or path: it's what the HLS proxy expects, because it
 * builds `http://<host>/live/<playCode>.m3u8` on its own.
 */
internal fun bareHost(mainAddr: String): String = mainAddr
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

/** Walks a `JSONArray` of objects without writing the index by hand every place. */
internal inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}
