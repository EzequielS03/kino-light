package com.arkiv.player.data.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Task 8: [SubtitleApi] no tenía test propio. Cubre exclusivamente lo que sumó el Paso 2
 * (Authorization + X-Arkiv-Device) y lo que sacó el Paso 3 (`X-Arkiv-Key`) en las llamadas que
 * van al GATEWAY ([search], y el primer pedido de [download]). El SEGUNDO pedido de [download]
 * -bajar el .srt del `link` que devolvió OpenSubtitles- va a OTRO host (el CDN de OpenSubtitles):
 * ahí estas cabeceras no significan nada y no deben viajar, así que hay un test que lo fija.
 */
class SubtitleApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api(personTok: String? = null, deviceTok: String? = null) = SubtitleApi(
        gatewayUrl = { server.url("/").toString().trimEnd('/') },
        client = OkHttpClient(),
        personToken = { personTok },
        deviceToken = { deviceTok },
    )

    @Test
    fun `search manda Authorization y X-Arkiv-Device cuando hay sesion, nunca X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        api(personTok = "person-tok", deviceTok = "device-tok").search(imdbId = "tt1")
        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
        assertNull(req.getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `search sin sesion no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        api().search(imdbId = "tt1")
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `download manda las cabeceras de sesion al gateway pero NO al link externo del srt`() = runBlocking {
        // El primer pedido (POST a $base/download, el GATEWAY) devuelve el link de descarga --
        // apuntado al MISMO MockWebServer, para poder capturar el segundo pedido y comprobar que
        // ESE no lleva Authorization/X-Arkiv-Device/X-Arkiv-Key.
        val linkUrl = server.url("/srt-directo").toString()
        server.enqueue(MockResponse().setBody("""{"link":"$linkUrl"}"""))
        server.enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nHola\n"))

        val dir = kotlin.io.path.createTempDirectory("subtitle-api-test").toFile()
        val archivo = api(personTok = "person-tok", deviceTok = "device-tok").download(123L, dir)

        assertEquals("sub-123.srt", archivo?.name)

        val pedidoGateway = server.takeRequest()
        assertEquals("person-tok", pedidoGateway.getHeader("Authorization"))
        assertEquals("device-tok", pedidoGateway.getHeader("X-Arkiv-Device"))
        assertNull(pedidoGateway.getHeader("X-Arkiv-Key"))

        val pedidoCdn = server.takeRequest()
        assertEquals("/srt-directo", pedidoCdn.path)
        assertNull(pedidoCdn.getHeader("Authorization"))
        assertNull(pedidoCdn.getHeader("X-Arkiv-Device"))
        assertNull(pedidoCdn.getHeader("X-Arkiv-Key"))
    }
}
