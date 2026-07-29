package com.arkiv.player.data.catalog.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Cookie de Cloudflare resuelta + el UA con el que se obtuvo (debe reusarse en las peticiones OkHttp). */
data class CfClearance(val cookies: String, val userAgent: String)

/** Abre-puertas de Cloudflare: resuelve el challenge JS y entrega la cookie `cf_clearance`. */
interface CloudflareSolver {
    suspend fun solve(url: String): CfClearance?
}

/** No hace nada (flag apagado / tests): el proveedor detrás de Cloudflare devolverá vacío. */
object NoopCloudflareSolver : CloudflareSolver {
    override suspend fun solve(url: String): CfClearance? = null
}

/**
 * Resuelve el challenge JS de Cloudflare con un WebView headless. AISLADO: un único WebView
 * reutilizable, serializado con un Mutex (un challenge a la vez), con timeout duro. Si falla o
 * está deshabilitado, devuelve null y el proveedor degrada a lista vacía.
 */
class WebViewCloudflareSolver(
    context: Context,
    private val enabled: () -> Boolean,
    private val timeoutMs: Long = 20_000,
    private val store: CfClearanceStore = CfClearanceStore(),
) : CloudflareSolver {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(url: String): CfClearance? {
        if (!enabled()) return null
        val host = store.hostOf(url)
        host?.let { store.get(it) }?.let { return it }        // short-circuit: ya resuelto
        return mutex.withLock {
            host?.let { store.get(it) }?.let { return@withLock it }  // pudo resolverlo otro mientras esperaba el lock
            val result = withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Main) { solveOnMain(url) } }
            if (result != null && host != null) store.put(host, result)
            result
        }
    }

    private suspend fun solveOnMain(url: String): CfClearance? = suspendCoroutine { cont ->
        val host = runCatching { java.net.URL(url).host }.getOrNull()
        val webView = WebView(appContext)
        val ua = webView.settings.userAgentString
        var resumed = false
        fun finish(result: CfClearance?) {
            if (resumed) return
            resumed = true
            runCatching { webView.stopLoading(); webView.destroy() }
            cont.resume(result)
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    Log.i("ArkivProv", "cloudflare resuelto host=$host")
                    finish(CfClearance(cookies = cookies, userAgent = ua))
                }
                // Si aún no está, el challenge sigue; el timeout de solve() cortará si no aparece.
            }
        }
        webView.loadUrl(url)
    }
}
