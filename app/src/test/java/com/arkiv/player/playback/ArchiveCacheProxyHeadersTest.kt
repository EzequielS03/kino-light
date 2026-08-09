package com.arkiv.player.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * El proxy local es la única vía para mandarle headers arbitrarios al origen: libVLC solo expone
 * `:http-referrer` y `:http-user-agent`, y magis sirve el VOD detrás de `Content-Auth` y
 * `Content-License`.
 */
class ArchiveCacheProxyHeadersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origen.shutdown()
    }

    private fun cuerpo(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun pedir(url: String): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
        }
        val code = c.responseCode
        val datos = runCatching { c.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
        c.disconnect()
        return code to datos
    }

    @Test
    fun `sin headers la url del proxy no cambia de forma`() {
        val u = proxy.proxyUrl("https://ejemplo/video.mp4")
        assertTrue(u.startsWith("http://127.0.0.1:${proxy.port}/s?u="))
        assertTrue("no deberia agregar el parametro h", !u.contains("h="))
    }

    @Test
    fun `los headers extra llegan al origen`() {
        val datos = cuerpo(4096)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )
        val url = proxy.proxyUrl(
            origen.url("/v.ts").toString(),
            mapOf("Content-Auth" to "TOKEN-AUTH", "Content-License" to "LIC"),
        )
        val (code, _) = pedir(url)
        assertEquals(200, code)

        val recibido = origen.takeRequest()
        assertEquals("TOKEN-AUTH", recibido.getHeader("Content-Auth"))
        assertEquals("LIC", recibido.getHeader("Content-License"))
    }

    @Test
    fun `el origen se decodifica bien aunque haya headers en la url`() {
        val datos = cuerpo(2048)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )
        val url = proxy.proxyUrl(origen.url("/con%20espacio.ts").toString(), mapOf("X-Uno" to "1"))
        val (code, _) = pedir(url)
        assertEquals(200, code)
        // Lo que se verifica es que el origen sobreviva al parametro extra en la URL. NO se afirma
        // el tamaño del cuerpo: el proxy sirve el archivo MIENTRAS lo descarga, asi que cuanto
        // alcanzo a escribir depende del timing y haria el test intermitente.
        assertEquals("/con%20espacio.ts", origen.takeRequest().path)
    }

    @Test
    fun `bufferedFraction sigue leyendo el origen con headers en la url`() {
        // Parseaba con substringAfter("u="), que se tragaba cualquier parametro posterior.
        val url = proxy.proxyUrl("https://ejemplo/video.mp4", mapOf("A" to "b"))
        assertEquals(0f, proxy.bufferedFraction(url), 0.0001f)
    }

    @Test
    fun `un header con caracteres raros sobrevive el viaje`() {
        val datos = cuerpo(1024)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )
        val valor = "sign=abc/def+ghi=&t=123"
        val url = proxy.proxyUrl(origen.url("/v.ts").toString(), mapOf("Content-Auth" to valor))
        pedir(url)
        assertEquals(valor, origen.takeRequest().getHeader("Content-Auth"))
    }

    @Test
    fun `sin headers no se manda ninguno extra`() {
        val datos = cuerpo(512)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )
        pedir(proxy.proxyUrl(origen.url("/v.ts").toString()))
        assertNull(origen.takeRequest().getHeader("Content-Auth"))
    }
}
