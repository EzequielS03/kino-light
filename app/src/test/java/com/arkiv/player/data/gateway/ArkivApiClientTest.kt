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
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- este test confirma que el corte fue real,
    // no solo que se dejó de mandar un valor no vacío.
    @Test
    fun `nunca manda X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "dune")).toList()
        assertNull(server.takeRequest().getHeader("X-Arkiv-Key"))
    }

    // --- Task 8 (Paso 3): Authorization + X-Arkiv-Device son la ÚNICA credencial --------------

    @Test
    fun `manda Authorization y X-Arkiv-Device cuando hay sesion`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        val conSesion = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
            personToken = { "person-tok" },
            deviceToken = { "device-tok" },
        )
        conSesion.search(GatewaySearchQuery(q = "dune")).toList()
        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device (nunca cabeceras vacias)`() = runBlocking {
        // `client` del setUp no pasa personToken/deviceToken -- quedan en null por default.
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        client.search(GatewaySearchQuery(q = "dune")).toList()
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `un token en blanco tambien se omite, no se manda vacio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"done","ms":1}""" + "\n"))
        val conBlancos = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
            personToken = { "" },
            deviceToken = { "  " },
        )
        conBlancos.search(GatewaySearchQuery(q = "dune")).toList()
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
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
    fun `episodes lista los capitulos con su ref`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"episodes":[{"number":1,"title":"01","ref":"r1"},""" +
                    """{"number":2,"title":"02","ref":"r2"}]}""",
            ),
        )
        val caps = client.episodes("refSerie")
        assertEquals(2, caps.size)
        assertEquals(1, caps[0].number)
        assertEquals("01", caps[0].title)
        assertEquals("r1", caps[0].ref)
    }

    @Test
    fun `un capitulo sin ref se descarta`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"episodes":[{"number":1,"title":"01"},{"number":2,"title":"02","ref":"r"}]}"""),
        )
        assertEquals(1, client.episodes("r").size)
    }

    @Test(expected = GatewayException::class)
    fun `episodes de una fuente que no los soporta lanza`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422))
        client.episodes("r")
        Unit
    }

    @Test
    fun `refrescarRecomendaciones pega al POST correcto`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        client.refrescarRecomendaciones()
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/recomendaciones/refrescar", req.path)
    }

    @Test(expected = GatewayException::class)
    fun `refrescarRecomendaciones lanza si el gateway falla`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        client.refrescarRecomendaciones()
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

    // --- Búsqueda por frase ("una de miedo de los 80 en español") ------------------------------

    @Test
    fun `buscarPorFrase parsea la interpretacion y las obras`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"interpretado":{"tipo":"movie","generos":["terror"],"anio_desde":1980,"anio_hasta":1989,"idioma":"es","nombre":"Batman"},""" +
                    """"items":[{"tmdb_id":103,"titulo":"Angustia","anio":"1987","poster_url":"https://img/a.jpg","tipo":"movie"}]}""",
            ),
        )
        val r = client.buscarPorFrase("una de miedo de los 80 en español")

        assertEquals("movie", r.interpretado?.tipo)
        assertEquals(listOf("terror"), r.interpretado?.generos)
        assertEquals(1980, r.interpretado?.anioDesde)
        assertEquals(1989, r.interpretado?.anioHasta)
        assertEquals("es", r.interpretado?.idioma)
        assertEquals("Batman", r.interpretado?.nombre)
        assertEquals(1, r.items.size)
        assertEquals(GatewayObraDeFrase(103, "Angustia", "1987", "https://img/a.jpg", "movie"), r.items[0])
        // La frase viaja URL-encodeada: espacios y tildes no pueden romper la URL.
        assertTrue(server.takeRequest().path!!.startsWith("/v1/search/frase?q=una%20de%20miedo"))
    }

    @Test
    fun `buscarPorFrase con el interprete apagado devuelve vacio sin interpretacion`() = runBlocking {
        // El gateway responde así sin llave de modelo o con el gateway de LLMs caído:
        // no es un error, es "no hay nada que dibujar".
        server.enqueue(MockResponse().setBody("""{"interpretado":null,"items":[]}"""))
        val r = client.buscarPorFrase("una de risa")

        assertNull(r.interpretado)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `buscarPorFrase ignora obras sin tmdb_id o sin titulo`() = runBlocking {
        // Una fila coja del gateway no puede pintar una card que no se puede abrir.
        server.enqueue(
            MockResponse().setBody(
                """{"interpretado":{"tipo":"movie","generos":[]},"items":[""" +
                    """{"tmdb_id":0,"titulo":"Sin id","anio":"","poster_url":"","tipo":"movie"},""" +
                    """{"tmdb_id":9,"titulo":"","anio":"","poster_url":"","tipo":"movie"},""" +
                    """{"tmdb_id":103,"titulo":"Angustia","anio":"1987","poster_url":"","tipo":"movie"}]}""",
            ),
        )
        val r = client.buscarPorFrase("terror")

        assertEquals(listOf(103), r.items.map { it.tmdbId })
    }
}
