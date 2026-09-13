package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateChecker(
    private val client: OkHttpClient,
    private val url: String = "https://apk.comparadorinternet.co/latest.json",
) {
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            // Cloudflare transforms JSON bodies; the server prepends )]}'\n to bypass it.
            val body = raw.substringAfter("{", "").let { "{$it" }
            val json = JSONObject(body)
            val remote = UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                url = json.getString("url"),
                notes = json.optString("notes", ""),
            )
            if (remote.versionCode > currentVersionCode) remote else null
        }.getOrNull()
    }

    /**
     * The APK's URL, whether or not there's a newer version than the installed one.
     *
     * [check] returns `null` when you're already up to date, which is right for the update notice
     * but useless for the TV entry screen's download QR: there the URL is needed always, and it
     * comes from the same `latest.json` so it doesn't go stale once a new version is published
     * (the URL carries the number inside).
     */
    suspend fun downloadUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val body = raw.substringAfter("{", "").let { "{$it" }
            JSONObject(body).getString("url")
        }.getOrNull()
    }
}
