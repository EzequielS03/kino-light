package com.arkiv.player.data.catalog.web

import android.util.Log
import com.arkiv.player.data.catalog.BROWSER_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Descarga el HTML de una URL (con manejo de Cloudflare por debajo). */
interface PageFetcher {
    suspend fun fetch(url: String, charset: String, headers: Map<String, String> = emptyMap()): String?
}

/** Heurística: ¿el response es un challenge JS de Cloudflare? (pura, testeable sin red) */
fun looksLikeCloudflareChallenge(code: Int, body: String?): Boolean {
    val b = body?.lowercase() ?: return code == 403 || code == 503
    val markers = listOf("just a moment", "__cf_chl", "cf-mitigated", "cf-browser-verification", "checking your browser")
    return markers.any { b.contains(it) }
}

/**
 * Fetcher OkHttp con UA de navegador. Si detecta un challenge de Cloudflare, delega en el
 * [CloudflareSolver] para obtener `cf_clearance` y reintenta una vez inyectando la cookie + UA.
 */
class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val solver: CloudflareSolver = NoopCloudflareSolver,
    private val store: CfClearanceStore = CfClearanceStore(),
) : PageFetcher {

    override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val host = store.hostOf(url)
        val cached = host?.let { store.get(it) }
        val first = get(url, charset, cookie = cached?.cookies, ua = cached?.userAgent ?: BROWSER_UA, extra = headers)
        val isChallenge = first != null && looksLikeCloudflareChallenge(first.code, first.body)
        if (first != null && !isChallenge) return@withContext first.body?.takeIf { first.code in 200..299 }
        // Solo invocamos el solver ante un challenge REAL de Cloudflare; un fallo de red se degrada a null.
        if (!isChallenge) return@withContext null
        val clearance = runCatching { solver.solve(url) }.getOrNull() ?: return@withContext null
        if (host != null) store.put(host, clearance)
        val second = get(url, charset, cookie = clearance.cookies, ua = clearance.userAgent, extra = headers)
        second?.body?.takeIf { second.code in 200..299 }
    }

    private data class Resp(val code: Int, val body: String?)

    private fun get(url: String, charset: String, cookie: String?, ua: String, extra: Map<String, String> = emptyMap()): Resp? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply {
                // Cookie de la clearance + cookies del proveedor (ej. eztv layout=def_wlinks) se combinan.
                val provCookie = extra["Cookie"]
                val merged = listOfNotNull(cookie, provCookie).joinToString("; ").ifBlank { null }
                if (merged != null) header("Cookie", merged)
                extra.forEach { (k, v) -> if (!k.equals("Cookie", true)) header(k, v) }
            }
            .build()
        client.newCall(req).execute().use { resp ->
            val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
            val bytes = resp.body?.bytes()
            Resp(resp.code, bytes?.toString(cs))
        }
    }.onFailure { Log.w("ArkivProv", "fetch fail $url: $it") }.getOrNull()
}
