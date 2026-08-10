package com.arkiv.player.pocketbase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MagisLinkException(val code: Int, message: String) : Exception(message)

/** Cliente de los endpoints /v1/magis/link del gateway. Manda X-Arkiv-Key + X-Arkiv-Account. */
class MagisLinkClient(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val accountId: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val jsonType = "application/json".toMediaType()

    private fun req(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("X-Arkiv-Key", apiKey())
        accountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        return b
    }

    private fun exec(request: Request): JSONObject =
        client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                throw MagisLinkException(r.code, msg?.ifBlank { raw } ?: raw)
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }

    suspend fun status(): Boolean = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").get().build()).optBoolean("linked", false)
    }

    suspend fun link(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("username" to username, "password" to password))
            .toString().toRequestBody(jsonType)
        exec(req("${baseUrl()}/v1/magis/link").post(body).build())   // 422 -> MagisLinkException
    }

    suspend fun unlink(): Unit = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").delete().build())
    }
}
