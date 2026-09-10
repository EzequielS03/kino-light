package com.arkiv.player.data.magis

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MagisFuenteTest {

    private lateinit var tmdbServer: MockWebServer
    private val respuestasTmdb = mutableMapOf<String, String>()
    private val pedidosTmdb = mutableListOf<String>()

    @Before
    fun setUp() {
        tmdbServer = MockWebServer()
        tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                pedidosTmdb.add(url.encodedPath + "?" + (url.query ?: ""))
                val clave = respuestasTmdb.keys.firstOrNull {
                    url.encodedPath.endsWith(it.substringBefore('|')) &&
                        (it.substringAfter('|', "").isBlank() || url.query?.contains(it.substringAfter('|')) == true)
                }
                return MockResponse().setBody(respuestasTmdb[clave] ?: "{}")
            }
        }
        tmdbServer.start()
    }

    @After
    fun tearDown() = tmdbServer.shutdown()

    private fun tmdb() = TmdbApi(
        apiKey = "llave",
        baseUrl = tmdbServer.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    private fun fuente(fake: FakePortalClient): MagisFuente {
        val session = sesionDeTest(fake)
        return MagisFuente(MagisCatalog(fake, session), MagisResolve(fake, session), tmdb())
    }

    private fun busquedaDelPortal(vararg items: String) = MagisResult.Ok(
        JSONObject("""{"searchItemList":[{"itemList":[${items.joinToString(",")}]}]}"""),
    )

    private val pelicula = """
        {"contentId":"M1","name":"Dune","programType":"movie","releaseTime":"2021-09-15 00:00:00",
         "posterList":[{"fileType":"icon","fileUrl":"https://i/dune.jpg"},
                       {"fileType":"poster","fileUrl":"https://p/dune.jpg"}]}
    """.trimIndent()

    /**
     * `reconoce` hoy no lo usa nadie (llega con la fuente compuesta de la tarea siguiente), pero un
     * `false` donde debería dar `true` deja CUALQUIER ref de Magis sin dueño y tumba toda la
     * reproducción de la app ya existente sin que ningún test lo note — de ahí la cobertura directa.
     */
    @Test
    fun `reconoce refs propios y viejos del gateway, y rechaza los de otra fuente`() {
        val f = fuente(FakePortalClient())

        assertTrue(f.reconoce(MagisRef("C1", "movie").codificar()))

        // Ref viejo del gateway (`base64url(json).hmac`), mismo formato que arma MagisRefTest.
        val json = """{"s":"magis","p":{"content_id":"C1","program_type":"movie"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertTrue(f.reconoce("$datos.firmaquenadievalida"))

        assertFalse(f.reconoce("ditu1:VOD:42"))
    }

    // --- búsqueda -------------------------------------------------------------

    @Test
    fun `la busqueda emite arranque, resultados y cierre`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(pelicula))

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "Dune", type = "movie")).toList()

        assertEquals(SearchEvent.SourceStart("magis"), eventos.first())
        assertTrue(eventos.last() is SearchEvent.Done)
        val done = eventos.filterIsInstance<SearchEvent.SourceDone>().single()
        assertEquals(1, done.count)
    }

    @Test
    fun `cada resultado trae un ref propio y los datos que la UI muestra`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(pelicula))

        val item = fuente(fake).search(GatewaySearchQuery(q = "Dune", type = "movie")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().single().item

        assertEquals("Dune", item.title)
        assertEquals("2021", item.year)
        assertEquals("magis", item.source)
        assertEquals(MagisRef("M1", "movie", 0), MagisRef.decodificar(item.ref))
        assertEquals("M1", item.extra["content_id"])
        assertEquals("movie", item.extra["program_type"])
        assertEquals("https://i/dune.jpg", item.extra["poster"])
        assertEquals("https://p/dune.jpg", item.extra["backdrop"])
    }

    @Test
    fun `al portal se le pide la cabeza del titulo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(pelicula))

        fuente(fake).search(GatewaySearchQuery(q = "Avatar: Aang, El ultimo Maestro Aire")).toList()

        assertEquals("Avatar", fake.llamadas.first { it.first == "v3/searchByName" }.second["value"])
    }

    @Test
    fun `dos titulos de la misma familia comparten una sola llamada al portal`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(pelicula))
        val f = fuente(fake)

        f.search(GatewaySearchQuery(q = "Dune: Parte dos")).toList()
        f.search(GatewaySearchQuery(q = "Dune, la profecia")).toList()

        assertEquals(1, fake.vecesLlamado("v3/searchByName"))
    }

    @Test
    fun `una serie reporta la temporada que dice su nombre`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v3/searchByName",
            busquedaDelPortal("""{"contentId":"S1","name":"Dragon Ball T3","programType":"teleplay","volumnCount":"8"}"""),
        )

        val item = fuente(fake).search(GatewaySearchQuery(q = "Dragon Ball", type = "tv")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().single().item

        assertEquals(3, item.season)
        assertEquals("8", item.extra["episode_count"])
        assertTrue(MagisRef.decodificar(item.ref)!!.esSerie)
    }

    @Test
    fun `pidiendo una temporada se filtra a esa, y si ninguna coincide se muestran todas`() = runTest {
        val tres = """{"contentId":"S3","name":"Naruto T3","programType":"teleplay"}"""
        val cuatro = """{"contentId":"S4","name":"Naruto T4","programType":"teleplay"}"""
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(tres, cuatro))
        val f = fuente(fake)

        val deLa4 = f.search(GatewaySearchQuery(q = "Naruto", type = "tv", season = 4)).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(listOf("Naruto T4"), deLa4)

        val deLa9 = f.search(GatewaySearchQuery(q = "Naruto", type = "tv", season = 9)).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(listOf("Naruto T3", "Naruto T4"), deLa9)
    }

    @Test
    fun `un item sin contentId se descarta sin tumbar la busqueda`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal("""{"name":"Sin id"}""", pelicula))

        val titulos = fuente(fake).search(GatewaySearchQuery(q = "Dune")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }

        assertEquals(listOf("Dune"), titulos)
    }

    @Test
    fun `si el portal rechaza la busqueda sale un SourceError, no una excepcion`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", MagisResult.PortalError("aaa1", "no"))
        fake.encolarRespuesta("v3/searchByName", MagisResult.PortalError("aaa1", "no"))

        val eventos = fuente(fake).search(GatewaySearchQuery(q = "Dune")).toList()

        val error = eventos.filterIsInstance<SearchEvent.SourceError>().single()
        assertTrue("el mensaje debe decir el codigo: ${error.error}", error.error.contains("aaa1"))
        assertTrue(eventos.last() is SearchEvent.Done)
    }

    @Test
    fun `el titulo original de TMDB ayuda a rankear y si TMDB falla no estorba`() = runTest {
        respuestasTmdb["/movie/99"] = """{"id":99,"title":"Spider-Man: Sin camino a casa",
            "original_title":"Spider-Man: No Way Home"}"""
        val animada = """{"contentId":"A","name":"Spider-Man: La serie animada","programType":"movie"}"""
        val laBuena = """{"contentId":"B","name":"Spider-Man: No Way Home","programType":"movie"}"""
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/searchByName", busquedaDelPortal(animada, laBuena))

        val titulos = fuente(fake).search(
            GatewaySearchQuery(q = "Spider-Man: Sin camino a casa", type = "movie", tmdbId = 99),
        ).toList().filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }

        assertEquals("Spider-Man: No Way Home", titulos.first())
    }

    // --- reproducción ---------------------------------------------------------

    private fun playDeUnaPelicula(contentId: String) = MagisResult.Ok(
        JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"$contentId","videoFormat":"mp4","encodeFormat":"h264","duration":"01:00:00",
                 "licenseList":[{"license":"LIC"}]}
            ]}]}]}""",
        ),
    )

    private fun slb() = MagisResult.Ok(
        JSONObject(
            """{"invalidTime":"14400","cdn_list":[{"tag":"vod","main_addr":"https://cdn.test",
                "url_list":[{"tag":"free","url":"sign_type=cfl&token=${"a".repeat(32)}&expired=9999999999"}]}]}""",
        ),
    )

    @Test
    fun `reproducir una pelicula usa su contentId tal cual`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", playDeUnaPelicula("M1"))
        fake.encolarRespuesta("v14/getSlbInfo", slb())

        val p = fuente(fake).resolve(MagisRef("M1", "movie", 0).codificar())

        assertEquals("magis", p.kind)
        assertEquals("https://cdn.test/vod/M1_media.mp4", p.url)
        assertEquals("LIC", p.headers["Content-License"])
        assertEquals(3_600_000L, p.durationMs)
        assertEquals("M1", fake.llamadas.first { it.first == "v10/startPlayVOD" }.second["contentId"])
        assertEquals("", fake.llamadas.first { it.first == "v10/startPlayVOD" }.second["seriesContentId"])
    }

    @Test
    fun `reproducir un capitulo busca su contentId en la lista de la serie`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/getItemData",
            MagisResult.Ok(
                JSONObject(
                    """{"assetData":{"keyWords":"","simpleProgramList":[
                        {"seriesNumber":"1","contentId":"EP1","name":"Uno","duration":"00:20:00"},
                        {"seriesNumber":"2","contentId":"EP2","name":"Dos","duration":"00:21:00"}
                    ]}}""",
                ),
            ),
        )
        fake.encolarRespuesta("v10/startPlayVOD", playDeUnaPelicula("EP2"))
        fake.encolarRespuesta("v14/getSlbInfo", slb())

        val p = fuente(fake).resolve(MagisRef("SERIE", "teleplay", 2).codificar())

        val (_, bean) = fake.llamadas.first { it.first == "v10/startPlayVOD" }
        assertEquals("EP2", bean["contentId"])
        assertEquals("SERIE", bean["seriesContentId"])
        // La duración la manda la lista de capítulos, no la pista.
        assertEquals(1_260_000L, p.durationMs)
    }

    @Test
    fun `un capitulo que la serie no tiene falla con un mensaje claro`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/getItemData",
            MagisResult.Ok(
                JSONObject("""{"assetData":{"simpleProgramList":[{"seriesNumber":"1","contentId":"EP1"}]}}"""),
            ),
        )

        val e = runCatching { fuente(fake).resolve(MagisRef("SERIE", "teleplay", 7).codificar()) }
            .exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(e!!.message!!.contains("7"))
    }

    @Test
    fun `un ref que no es de magis no se intenta reproducir`() = runTest {
        val fake = FakePortalClient()

        val e = runCatching { fuente(fake).resolve("cualquier-cosa") }.exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(fake.llamadas.isEmpty())
    }

    @Test
    fun `si el portal no da pista, el error explica por que`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.RedError(java.io.IOException("sin red")))

        val e = runCatching { fuente(fake).resolve(MagisRef("M1").codificar()) }.exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(e!!.message!!.contains("sin red"))
    }

    // --- capítulos ------------------------------------------------------------

    private fun detalleDeSerie(
        imdb: String = "tt0088509",
        temporadas: String = "[]",
        volumnCount: String = """"2"""",
        capitulos: String = """
            {"seriesNumber":"1","contentId":"EP1","name":"Uno"},
            {"seriesNumber":"2","contentId":"EP2","name":""}
        """,
    ) = MagisResult.Ok(
        JSONObject(
            """{"assetData":{"keyWords":"$imdb","volumnCount":$volumnCount,
                "sameSeasonSeriesList":$temporadas,"simpleProgramList":[$capitulos]}}""",
        ),
    )

    @Test
    fun `los capitulos salen con su numero, su nombre y su ref`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie(imdb = ""))

        val (caps, serie) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertEquals(listOf(1, 2), caps.map { it.number })
        assertEquals("Uno", caps[0].title)
        // Sin nombre en el portal se pone uno legible, nunca vacío.
        assertEquals("Capítulo 2", caps[1].title)
        assertEquals(MagisRef("SERIE", "teleplay", 2), MagisRef.decodificar(caps[1].ref))
        // Sin imdb no hay nada que identificar: el bloque de serie no viaja.
        assertNull(serie)
    }

    @Test
    fun `con imdb y TMDB resuelto los capitulos traen imagen, nombre y sinopsis`() = runTest {
        respuestasTmdb["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Dragon Ball",
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg"}]}"""
        respuestasTmdb["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"El secreto","overview":"Sinopsis 1","still_path":"/s1.jpg"},
            {"episode_number":2,"name":"La busqueda","overview":"Sinopsis 2","still_path":"/s2.jpg"}
        ]}"""
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie())

        val (caps, serie) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertEquals("El secreto", caps[0].tmdbTitle)
        assertEquals("Sinopsis 1", caps[0].overview)
        assertTrue(caps[0].still!!.endsWith("/s1.jpg"))
        assertEquals(12, serie!!.tmdbId)
        assertEquals("Dragon Ball", serie.titulo)
        assertEquals("tt0088509", serie.imdbId)
        // sameSeasonSeriesList vacía = temporada única = la 1, no "no se sabe".
        assertEquals(1, serie.seasonNumber)
    }

    @Test
    fun `si el portal partio la serie distinto que TMDB no se enriquece nada`() = runTest {
        respuestasTmdb["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"One Piece"}]}"""
        // El portal declara 2 capítulos; TMDB dice que esa temporada tiene 3.
        respuestasTmdb["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"A","still_path":"/a.jpg"},
            {"episode_number":2,"name":"B","still_path":"/b.jpg"},
            {"episode_number":3,"name":"C","still_path":"/c.jpg"}
        ]}"""
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie())

        val (caps, serie) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertNull(caps[0].still)
        assertNull(caps[0].tmdbTitle)
        // La serie SÍ viaja: con el imdb la app puede intentar identificarla por su cuenta.
        assertEquals(12, serie!!.tmdbId)
    }

    @Test
    fun `una temporada en emision se enriquece con lo que ya publico`() = runTest {
        respuestasTmdb["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"En emision"}]}"""
        respuestasTmdb["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"A","still_path":"/a.jpg"},
            {"episode_number":2,"name":"B","still_path":"/b.jpg"},
            {"episode_number":3,"name":"C","still_path":"/c.jpg"}
        ]}"""
        val fake = FakePortalClient()
        // Declara 3 (el total de la temporada) pero solo publicó 2.
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie(volumnCount = """"3""""))

        val (caps, _) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertEquals(2, caps.size)
        assertEquals("A", caps[0].tmdbTitle)
        assertEquals("B", caps[1].tmdbTitle)
    }

    @Test
    fun `la sinopsis que TMDB no tiene en espanol se completa en ingles`() = runTest {
        respuestasTmdb["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Serie"}]}"""
        respuestasTmdb["/tv/12/season/1|language=es-MX"] = """{"episodes":[
            {"episode_number":1,"name":"Uno","overview":""},
            {"episode_number":2,"name":"Dos","overview":"La que si estaba"}
        ]}"""
        respuestasTmdb["/tv/12/season/1|language=en-US"] = """{"episodes":[
            {"episode_number":1,"name":"One","overview":"The english one"},
            {"episode_number":2,"name":"Two","overview":"No deberia pisar"}
        ]}"""
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie())

        val (caps, _) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertEquals("The english one", caps[0].overview)
        // El que ya tenía sinopsis en español NO se pisa.
        assertEquals("La que si estaba", caps[1].overview)
        assertEquals("Uno", caps[0].tmdbTitle)
    }

    @Test
    fun `si no se sabe que temporada es, no se enriquece con otra`() = runTest {
        respuestasTmdb["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Serie"}]}"""
        respuestasTmdb["/tv/12/season/5"] = """{"episodes":[{"episode_number":1,"name":"No va"}]}"""
        val fake = FakePortalClient()
        // La lista trae temporadas, pero ninguna es esta: eso SÍ es no saber.
        fake.encolarRespuesta(
            "v4/getItemData",
            detalleDeSerie(temporadas = """[{"contentId":"OTRA","seasonNumber":5}]"""),
        )

        val (caps, serie) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertNull(caps[0].tmdbTitle)
        assertEquals(0, serie!!.tmdbId)
        assertEquals(0, serie.seasonNumber)
        assertTrue("no debió pedirle nada a TMDB", pedidosTmdb.isEmpty())
    }

    @Test
    fun `la lista de capitulos se pide una sola vez aunque se reproduzcan varios`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie(imdb = ""))
        fake.encolarRespuesta("v10/startPlayVOD", playDeUnaPelicula("EP1"))
        fake.encolarRespuesta("v14/getSlbInfo", slb())
        fake.encolarRespuesta("v10/startPlayVOD", playDeUnaPelicula("EP2"))
        val f = fuente(fake)

        f.episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())
        f.resolve(MagisRef("SERIE", "teleplay", 1).codificar())
        f.resolve(MagisRef("SERIE", "teleplay", 2).codificar())

        assertEquals(1, fake.vecesLlamado("v4/getItemData"))
    }

    @Test
    fun `si TMDB se cae, los capitulos salen igual`() = runTest {
        tmdbServer.shutdown()
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/getItemData", detalleDeSerie())

        val (caps, serie) = fuente(fake).episodesConSerie(MagisRef("SERIE", "teleplay", 0).codificar())

        assertEquals(2, caps.size)
        assertNull(caps[0].still)
        assertEquals(0, serie!!.tmdbId)
        assertEquals("tt0088509", serie.imdbId)
    }
}
