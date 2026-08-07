package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateChecker(
    private val client: OkHttpClient,
    private val url: String = "https://apk.comparadorinternet.co/latest.json",
) {
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).build()).execute()
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
}
