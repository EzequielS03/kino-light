package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DituClienteTest {

    private lateinit var server: MockWebServer

    @Before fun arranca() {
        server = MockWebServer()
        server.start()
    }

    @After fun apaga() {
        server.shutdown()
    }

    private fun cliente() = DituCliente(baseUrl = server.url("/AGL").toString().trimEnd('/'))

    /** Sin estos tres headers el CDN responde 403, tanto en el manifiesto como en los segmentos. */
    @Test fun `manda los headers que la API exige`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        cliente().get("TRAY/SEARCH/VOD")

        val pedido = server.takeRequest()
        assertEquals("yes", pedido.getHeader("restful"))
        assertEquals("okhttp/4.12.0", pedido.getHeader("User-Agent"))
        assertEquals("application/json, text/plain, */*", pedido.getHeader("Accept"))
    }

    @Test fun `arma la ruta bajo la base y agrega los parametros`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":1}"""))

        cliente().get("TRAY/SEARCH/VOD", mapOf("query" to "rigo", "filter_contentType" to "BUNDLE"))

        val pedido = server.takeRequest()
        assertTrue(pedido.path!!.startsWith("/AGL/TRAY/SEARCH/VOD?"))
        assertTrue(pedido.path!!.contains("query=rigo"))
        assertTrue(pedido.path!!.contains("filter_contentType=BUNDLE"))
    }

    /** LA COOKIE. No viene en el cuerpo: viene en el Set-Cookie de la respuesta. */
    @Test fun `getConToken saca el playback_token del Set-Cookie`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}""")
                .addHeader("Set-Cookie", "playback_token=abc123; Path=/; HttpOnly"),
        )

        val r = cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("abc123", r.playbackToken)
        assertEquals("https://cdn/x.mpd", r.json.getJSONObject("resultObj").getString("src"))
    }

    /**
     * Sin cookie NO se falla: el token vacío tiene que llegar hasta el reproductor, porque el
     * síntoma real (licencia en 500) se diagnostica mucho más rápido si se ve "sin playback_token"
     * en el log que si la resolución explota antes con otro mensaje.
     */
    @Test fun `sin Set-Cookie el token queda vacio y no falla`() = runTest {
        server.enqueue(MockResponse().setBody("""{"resultObj":{"src":"https://cdn/x.mpd"}}"""))

        val r = cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2")

        assertEquals("", r.playbackToken)
    }

    @Test fun `otras cookies no se confunden con el token`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"ok":1}""")
                .addHeader("Set-Cookie", "session=zzz; Path=/")
                .addHeader("Set-Cookie", "playback_token=elbueno; Path=/"),
        )

        assertEquals("elbueno", cliente().getConToken("CONTENT/VIDEOURL/VOD/1/2").playbackToken)
    }

    @Test fun `un status de error se convierte en DituException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))

        val e = runCatching { cliente().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("esperaba DituException y vino $e", e is DituException)
        assertTrue(e!!.message!!.contains("500"))
    }

    @Test fun `un cuerpo que no es JSON se convierte en DituException`() = runTest {
        server.enqueue(MockResponse().setBody("<html>error</html>"))

        val e = runCatching { cliente().get("TRAY/SEARCH/VOD") }.exceptionOrNull()

        assertTrue("esperaba DituException y vino $e", e is DituException)
    }
}
