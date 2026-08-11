package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LiveHlsProxyTest {
    /** Firma predecible, para poder afirmar qué `Content-Auth` salió en cada petición. */
    private class FirmasFalsas : FirmaDeSegmentos {
        var entregadas = 0
        var rechazos = 0
        override suspend fun firmar(token: String): LiveSignature {
            entregadas++
            return LiveSignature(1000L, "firma%02d".format(entregadas))
        }
        override fun rechazada() { rechazos++ }
    }

    private fun leer(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val cuerpo = runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
        return c.responseCode to cuerpo
    }

    @Test
    fun `reescribe los segmentos absolutos hacia el propio proxy`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n#EXTINF:6,\nhttp://seg2.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val puerto = proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (codigo, cuerpo) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertTrue(cuerpo.contains("http://127.0.0.1:$puerto/seg?u="))
        assertTrue("no debe quedar ninguna URL del CDN sin reescribir", !cuerpo.contains("seg1.cdn/live"))
        assertEquals(2, cuerpo.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `pone las tres cabeceras al pedir el playlist`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        leer(proxy.urlPara(sesion))

        val req = upstream.takeRequest()
        assertEquals("LIC", req.getHeader("Content-License"))
        assertEquals("Ranger/4.9.4-17294ac0", req.getHeader("User-Agent"))
        val auth = req.getHeader("Content-Auth")!!
        assertTrue(auth.contains("sign2_method=sign_o3"))
        assertTrue(auth.contains("start_moment=1000"))
        assertTrue(auth.contains("sign2=firma01"))
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `ante un 403 pide firma fresca y reintenta exactamente una vez`() = runBlocking {
        val upstream = MockWebServer()
        var pedidos = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos++
                return if (pedidos == 1) MockResponse().setResponseCode(403)
                else MockResponse().setBody("#EXTM3U\n")
            }
        }
        upstream.start()

        val firmas = FirmasFalsas()
        val proxy = LiveHlsProxy(firmas)
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertEquals("un 403 y su reintento, nada mas", 2, pedidos)
        assertEquals("el 403 se le avisa a la fuente de firmas", 1, firmas.rechazos)
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `dos 403 seguidos se rinden en vez de reintentar para siempre`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))
        assertEquals(502, codigo)
        proxy.stop(); upstream.shutdown()
    }
}
