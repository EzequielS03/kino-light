package com.arkiv.player.data.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArkivApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArkivApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            apiKey = { "LLAVE" },
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `manda la llave en el header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "dune")).toList()
        assertEquals("LLAVE", server.takeRequest().getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `emite un evento por linea`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"source_start","source":"a"}""" + "\n" +
                    """{"type":"result","source":"a","item":{"title":"T","ref":"r"}}""" + "\n" +
                    """{"type":"source_done","source":"a","count":1,"ms":10}""" + "\n" +
                    """{"type":"done","ms":11}""" + "\n",
            ),
        )
        val evs = client.search(GatewaySearchQuery(q = "dune")).toList()
        assertEquals(4, evs.size)
        assertTrue(evs[1] is SearchEvent.ResultEvent)
        assertTrue(evs.last() is SearchEvent.Done)
    }

    @Test
    fun `ignora lineas vacias`() = runBlocking {
        server.enqueue(MockResponse().setBody("\n\n" + """{"type":"done","ms":1}""" + "\n\n"))
        assertEquals(1, client.search(GatewaySearchQuery(q = "x")).toList().size)
    }

    @Test
    fun `arma la query con S y E cuando se piden`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(
            GatewaySearchQuery(q = "breaking bad", type = "tv", season = 1, episode = 2),
        ).toList()
        val url = server.takeRequest().requestUrl!!
        assertEquals("breaking bad", url.queryParameter("q"))
        assertEquals("tv", url.queryParameter("type"))
        assertEquals("1", url.queryParameter("season"))
        assertEquals("2", url.queryParameter("episode"))
    }

    @Test
    fun `no manda parametros vacios`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "x")).toList()
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("season"))
        assertNull(url.queryParameter("tmdb_id"))
    }

    @Test(expected = GatewayException::class)
    fun `un 500 lanza para que el llamador caiga al camino viejo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        client.search(GatewaySearchQuery(q = "x")).toList()
        Unit
    }

    @Test(expected = GatewayException::class)
    fun `un 401 lanza`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        client.search(GatewaySearchQuery(q = "x")).toList()
        Unit
    }

    @Test
    fun `resolve devuelve url y headers`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"magis","url":"http://cdn/v.ts",""" +
                    """"headers":{"Content-Auth":"A","Content-License":"L"},""" +
                    """"mime":"video/mp2t","expires_at":"","fallback":null}""",
            ),
        )
        val p = client.resolve("elref")
        assertEquals("http://cdn/v.ts", p.url)
        assertEquals("A", p.headers["Content-Auth"])
        assertEquals("magis", p.kind)
        assertNull(p.fallbackUrl)
    }

    @Test
    fun `resolve mapea el fallback del proxy web`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"web","url":"http://cdn/v.m3u8","headers":{"Referer":"http://s/"},""" +
                    """"mime":"","expires_at":"","fallback":{"url":"http://proxy/x"}}""",
            ),
        )
        assertEquals("http://proxy/x", client.resolve("r").fallbackUrl)
    }

    @Test(expected = GatewayException::class)
    fun `resolve con 502 lanza`() = runBlocking {
        // El gateway responde 502 cuando la fuente de arriba rechaza.
        server.enqueue(MockResponse().setResponseCode(502))
        client.resolve("r")
        Unit
    }

    @Test
    fun `resolve trae los subtitulos de la fuente`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"magis","url":"http://cdn/v.ts","headers":{},"mime":"","expires_at":"",""" +
                    """"fallback":null,"subtitles":[{"lang":"es","url":"http://s/es.srt","format":"srt"},""" +
                    """{"lang":"en","url":"http://s/en.srt","format":"srt"}]}""",
            ),
        )
        val subs = client.resolve("r").subtitles
        assertEquals(2, subs.size)
        assertEquals("es", subs[0].lang)
        assertEquals("http://s/es.srt", subs[0].url)
        assertEquals("srt", subs[0].format)
    }

    @Test
    fun `un subtitulo sin url se descarta`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"kind":"web","url":"http://cdn/v.m3u8","headers":{},""" +
                    """"subtitles":[{"lang":"es","url":"http://s/1.vtt"},{"lang":"en"}]}""",
            ),
        )
        assertEquals(1, client.resolve("r").subtitles.size)
    }

    @Test
    fun `sin subtitulos la lista va vacia`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"kind":"magis","url":"http://cdn/v.ts"}"""))
        assertEquals(emptyList<GatewaySubtitle>(), client.resolve("r").subtitles)
    }

    @Test
    fun `sources lista las fuentes activas`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"sources":[{"name":"magis","capabilities":["movie","tv"],"state":"closed"}]}""",
            ),
        )
        val s = client.sources()
        assertEquals(1, s.size)
        assertEquals("magis", s[0].name)
        assertEquals(listOf("movie", "tv"), s[0].capabilities)
        assertEquals("closed", s[0].state)
    }
}
