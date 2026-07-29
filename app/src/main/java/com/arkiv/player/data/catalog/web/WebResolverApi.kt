package com.arkiv.player.data.catalog.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ResolvedSub(val lang: String, val url: String)
data class ResolvedStream(val streamUrl: String, val headers: Map<String, String>, val subtitles: List<ResolvedSub>, val proxyUrl: String? = null)

/** Cliente del resolver headless de blog: pageUrl → stream reproducible (o null). */
class WebResolverApi(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // el sniff headless tarda y el resolver SERIALIZA: un título con varios embeds (cada uno se
        // sniffea+valida) puede pasar 60s. 120s da margen; con la caché del resolver el retry es instantáneo.
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun resolve(pageUrl: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val url = baseUrl().trimEnd('/') + "?url=" + URLEncoder.encode(pageUrl, "UTF-8")
        runCatching { Log.w("ArkivWebResolve", "GET $url") }
        val body = runCatching {
            // UA de navegador: el tunnel (comparadorinternet.co) está tras el Bot Fight Mode de
            // Cloudflare, que devuelve 403 a peticiones sin UA de navegador.
            val req = Request.Builder().url(url)
                .header("User-Agent", com.arkiv.player.data.catalog.BROWSER_UA).build()
            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string()
                runCatching { Log.w("ArkivWebResolve", "code=${resp.code} ok=${resp.isSuccessful} len=${b?.length} body=${b?.take(300)}") }
                if (resp.isSuccessful) b else null
            }
        }.onFailure { runCatching { Log.w("ArkivWebResolve", "excepción HTTP: $it") } }.getOrNull() ?: return@withContext null
        val parsed = parse(body)
        runCatching { Log.w("ArkivWebResolve", "parsed streamUrl=${parsed?.streamUrl}") }
        parsed
    }

    companion object {
        fun parse(json: String): ResolvedStream? = runCatching {
            val o = JSONObject(json)
            if (!o.optBoolean("ok")) return null
            val stream = o.optString("streamUrl").ifBlank { return null }
            val proxy = o.optString("proxyUrl").ifBlank { null }
            val h = o.optJSONObject("headers")
            val headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap()
            val sj = o.optJSONArray("subtitles")
            val subs = if (sj == null) emptyList() else (0 until sj.length()).mapNotNull { i ->
                sj.optJSONObject(i)?.let { s ->
                    val u = s.optString("url").ifBlank { return@mapNotNull null }
                    ResolvedSub(lang = s.optString("lang"), url = u)
                }
            }
            ResolvedStream(stream, headers, subs, proxy)
        }.onFailure { runCatching { Log.w("ArkivWeb", "resolve parse fail: $it") } }.getOrNull()
    }
}
