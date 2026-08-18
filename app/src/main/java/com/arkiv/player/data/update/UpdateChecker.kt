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
     * La URL del APK, haya o no una versión más nueva que la instalada.
     *
     * [check] devuelve `null` cuando ya estás al día, que es lo correcto para el aviso de
     * actualización pero inservible para el QR de descarga de la pantalla de entrada del TV: ahí
     * hace falta la URL siempre, y sale del mismo `latest.json` para que no envejezca cuando se
     * publique una versión nueva (la URL lleva el número adentro).
     */
    suspend fun urlDeDescarga(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val body = raw.substringAfter("{", "").let { "{$it" }
            JSONObject(body).getString("url")
        }.getOrNull()
    }
}
