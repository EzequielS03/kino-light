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
    private val tmdbResponses = mutableMapOf<String, String>()
    private val tmdbRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        tmdbServer = MockWebServer()
        tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                tmdbRequests.add(url.encodedPath + "?" + (url.query ?: ""))
                val key = tmdbResponses.keys.firstOrNull {
                    url.encodedPath.endsWith(it.substringBefore('|')) &&
                        (it.substringAfter('|', "").isBlank() || url.query?.contains(it.substringAfter('|')) == true)
                }
                return MockResponse().setBody(tmdbResponses[key] ?: "{}")
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

    private fun magisSource(fake: FakePortalClient): MagisFuente {
        val session = testSession(fake)
        return MagisFuente(MagisCatalog(fake, session), MagisResolve(fake, session), tmdb())
    }

    private fun portalSearch(vararg items: String) = MagisResult.Ok(
        JSONObject("""{"searchItemList":[{"itemList":[${items.joinToString(",")}]}]}"""),
    )

    private val movie = """
        {"contentId":"M1","name":"Dune","programType":"movie","releaseTime":"2021-09-15 00:00:00",
         "posterList":[{"fileType":"icon","fileUrl":"https://i/dune.jpg"},
                       {"fileType":"poster","fileUrl":"https://p/dune.jpg"}]}
    """.trimIndent()

    /**
     * Nothing uses `recognizes` today (it arrives with the next task's composite source), but a
     * `false` where it should say `true` leaves ANY Magis ref orphaned and sinks all of the app's
     * existing playback with no test noticing — hence the direct coverage.
     */
    @Test
    fun `recognizes its own refs and old gateway ones, and rejects those from another source`() {
        val f = magisSource(FakePortalClient())

        assertTrue(f.recognizes(MagisRef("C1", "movie").encode()))

        // Old gateway ref (`base64url(json).hmac`), same shape MagisRefTest builds.
        val json = """{"s":"magis","p":{"content_id":"C1","program_type":"movie"}}"""
        val data = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertTrue(f.recognizes("$data.firmaquenadievalida"))

        assertFalse(f.recognizes("ditu1:VOD:42"))
    }

    // --- search -------------------------------------------------------------

    @Test
    fun `search emits start, results and close`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(movie))

        val events = magisSource(fake).search(GatewaySearchQuery(q = "Dune", type = "movie")).toList()

        assertEquals(SearchEvent.SourceStart("magis"), events.first())
        assertTrue(events.last() is SearchEvent.Done)
        val done = events.filterIsInstance<SearchEvent.SourceDone>().single()
        assertEquals(1, done.count)
    }

    @Test
    fun `each result carries its own ref and the data the UI shows`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(movie))

        val item = magisSource(fake).search(GatewaySearchQuery(q = "Dune", type = "movie")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().single().item

        assertEquals("Dune", item.title)
        assertEquals("2021", item.year)
        assertEquals("magis", item.source)
        assertEquals(MagisRef("M1", "movie", 0), MagisRef.decode(item.ref))
        assertEquals("M1", item.extra["content_id"])
        assertEquals("movie", item.extra["program_type"])
        assertEquals("https://i/dune.jpg", item.extra["poster"])
        assertEquals("https://p/dune.jpg", item.extra["backdrop"])
    }

    @Test
    fun `the portal is asked for the title's head`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(movie))

        magisSource(fake).search(GatewaySearchQuery(q = "Avatar: Aang, El ultimo Maestro Aire")).toList()

        assertEquals("Avatar", fake.calls.first { it.first == "v3/searchByName" }.second["value"])
    }

    @Test
    fun `two titles from the same family share a single portal call`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(movie))
        val f = magisSource(fake)

        f.search(GatewaySearchQuery(q = "Dune: Parte dos")).toList()
        f.search(GatewaySearchQuery(q = "Dune, la profecia")).toList()

        assertEquals(1, fake.timesCalled("v3/searchByName"))
    }

    @Test
    fun `a series reports the season its name says`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v3/searchByName",
            portalSearch("""{"contentId":"S1","name":"Dragon Ball T3","programType":"teleplay","volumnCount":"8"}"""),
        )

        val item = magisSource(fake).search(GatewaySearchQuery(q = "Dragon Ball", type = "tv")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().single().item

        assertEquals(3, item.season)
        assertEquals("8", item.extra["episode_count"])
        assertTrue(MagisRef.decode(item.ref)!!.isSeries)
    }

    @Test
    fun `asking for a season filters to it, and if none matches all are shown`() = runTest {
        val season3 = """{"contentId":"S3","name":"Naruto T3","programType":"teleplay"}"""
        val season4 = """{"contentId":"S4","name":"Naruto T4","programType":"teleplay"}"""
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(season3, season4))
        val f = magisSource(fake)

        val forSeason4 = f.search(GatewaySearchQuery(q = "Naruto", type = "tv", season = 4)).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(listOf("Naruto T4"), forSeason4)

        val forSeason9 = f.search(GatewaySearchQuery(q = "Naruto", type = "tv", season = 9)).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }
        assertEquals(listOf("Naruto T3", "Naruto T4"), forSeason9)
    }

    @Test
    fun `an item with no contentId is discarded without sinking the search`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch("""{"name":"Sin id"}""", movie))

        val titles = magisSource(fake).search(GatewaySearchQuery(q = "Dune")).toList()
            .filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }

        assertEquals(listOf("Dune"), titles)
    }

    @Test
    fun `if the portal rejects the search a SourceError comes out, not an exception`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", MagisResult.PortalError("aaa1", "no"))
        fake.queueResponse("v3/searchByName", MagisResult.PortalError("aaa1", "no"))

        val events = magisSource(fake).search(GatewaySearchQuery(q = "Dune")).toList()

        val error = events.filterIsInstance<SearchEvent.SourceError>().single()
        assertTrue("el mensaje debe decir el codigo: ${error.error}", error.error.contains("aaa1"))
        assertTrue(events.last() is SearchEvent.Done)
    }

    @Test
    fun `TMDB's original title helps rank and if TMDB fails it doesn't get in the way`() = runTest {
        tmdbResponses["/movie/99"] = """{"id":99,"title":"Spider-Man: Sin camino a casa",
            "original_title":"Spider-Man: No Way Home"}"""
        val animated = """{"contentId":"A","name":"Spider-Man: La serie animada","programType":"movie"}"""
        val theRealOne = """{"contentId":"B","name":"Spider-Man: No Way Home","programType":"movie"}"""
        val fake = FakePortalClient()
        fake.queueResponse("v3/searchByName", portalSearch(animated, theRealOne))

        val titles = magisSource(fake).search(
            GatewaySearchQuery(q = "Spider-Man: Sin camino a casa", type = "movie", tmdbId = 99),
        ).toList().filterIsInstance<SearchEvent.ResultEvent>().map { it.item.title }

        assertEquals("Spider-Man: No Way Home", titles.first())
    }

    // --- playback ---------------------------------------------------------

    private fun moviePlay(contentId: String) = MagisResult.Ok(
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
    fun `playing a movie uses its contentId as-is`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", moviePlay("M1"))
        fake.queueResponse("v14/getSlbInfo", slb())

        val p = magisSource(fake).resolve(MagisRef("M1", "movie", 0).encode())

        assertEquals("magis", p.kind)
        assertEquals("https://cdn.test/vod/M1_media.mp4", p.url)
        assertEquals("LIC", p.headers["Content-License"])
        assertEquals(3_600_000L, p.durationMs)
        assertEquals("M1", fake.calls.first { it.first == "v10/startPlayVOD" }.second["contentId"])
        assertEquals("", fake.calls.first { it.first == "v10/startPlayVOD" }.second["seriesContentId"])
    }

    @Test
    fun `playing a chapter looks up its contentId in the series' list`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
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
        fake.queueResponse("v10/startPlayVOD", moviePlay("EP2"))
        fake.queueResponse("v14/getSlbInfo", slb())

        val p = magisSource(fake).resolve(MagisRef("SERIE", "teleplay", 2).encode())

        val (_, bean) = fake.calls.first { it.first == "v10/startPlayVOD" }
        assertEquals("EP2", bean["contentId"])
        assertEquals("SERIE", bean["seriesContentId"])
        // The duration comes from the chapter list, not the track.
        assertEquals(1_260_000L, p.durationMs)
    }

    @Test
    fun `a chapter the series doesn't have fails with a clear message`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/getItemData",
            MagisResult.Ok(
                JSONObject("""{"assetData":{"simpleProgramList":[{"seriesNumber":"1","contentId":"EP1"}]}}"""),
            ),
        )

        val e = runCatching { magisSource(fake).resolve(MagisRef("SERIE", "teleplay", 7).encode()) }
            .exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(e!!.message!!.contains("7"))
    }

    @Test
    fun `a ref that isn't Magis's doesn't get an attempt at playing`() = runTest {
        val fake = FakePortalClient()

        val e = runCatching { magisSource(fake).resolve("cualquier-cosa") }.exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `if the portal gives no track, the error explains why`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.RedError(java.io.IOException("sin red")))

        val e = runCatching { magisSource(fake).resolve(MagisRef("M1").encode()) }.exceptionOrNull()

        assertTrue(e is GatewayException)
        assertTrue(e!!.message!!.contains("sin red"))
    }

    // --- chapters ------------------------------------------------------------

    private fun seriesDetail(
        imdb: String = "tt0088509",
        seasons: String = "[]",
        volumnCount: String = """"2"""",
        chapters: String = """
            {"seriesNumber":"1","contentId":"EP1","name":"Uno"},
            {"seriesNumber":"2","contentId":"EP2","name":""}
        """,
    ) = MagisResult.Ok(
        JSONObject(
            """{"assetData":{"keyWords":"$imdb","volumnCount":$volumnCount,
                "sameSeasonSeriesList":$seasons,"simpleProgramList":[$chapters]}}""",
        ),
    )

    @Test
    fun `chapters come out with their number, their name and their ref`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail(imdb = ""))

        val (chapters, series) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertEquals(listOf(1, 2), chapters.map { it.number })
        assertEquals("Uno", chapters[0].title)
        // With no name from the portal a readable one is used, never blank.
        assertEquals("Capítulo 2", chapters[1].title)
        assertEquals(MagisRef("SERIE", "teleplay", 2), MagisRef.decode(chapters[1].ref))
        // With no imdb there's nothing to identify: the series block doesn't travel.
        assertNull(series)
    }

    @Test
    fun `with imdb and TMDB resolved, chapters carry image, name and synopsis`() = runTest {
        tmdbResponses["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Dragon Ball",
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg"}]}"""
        tmdbResponses["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"El secreto","overview":"Sinopsis 1","still_path":"/s1.jpg"},
            {"episode_number":2,"name":"La busqueda","overview":"Sinopsis 2","still_path":"/s2.jpg"}
        ]}"""
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail())

        val (chapters, series) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertEquals("El secreto", chapters[0].tmdbTitle)
        assertEquals("Sinopsis 1", chapters[0].overview)
        assertTrue(chapters[0].still!!.endsWith("/s1.jpg"))
        assertEquals(12, series!!.tmdbId)
        assertEquals("Dragon Ball", series.title)
        assertEquals("tt0088509", series.imdbId)
        // An empty sameSeasonSeriesList = single season = the 1st, not "unknown".
        assertEquals(1, series.seasonNumber)
    }

    @Test
    fun `if the portal split the series differently from TMDB, nothing gets enriched`() = runTest {
        tmdbResponses["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"One Piece"}]}"""
        // The portal declares 2 chapters; TMDB says that season has 3.
        tmdbResponses["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"A","still_path":"/a.jpg"},
            {"episode_number":2,"name":"B","still_path":"/b.jpg"},
            {"episode_number":3,"name":"C","still_path":"/c.jpg"}
        ]}"""
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail())

        val (chapters, series) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertNull(chapters[0].still)
        assertNull(chapters[0].tmdbTitle)
        // The series DOES travel: with the imdb the app can try to identify it on its own.
        assertEquals(12, series!!.tmdbId)
    }

    @Test
    fun `a season that's airing gets enriched with what it already published`() = runTest {
        tmdbResponses["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"En emision"}]}"""
        tmdbResponses["/tv/12/season/1"] = """{"episodes":[
            {"episode_number":1,"name":"A","still_path":"/a.jpg"},
            {"episode_number":2,"name":"B","still_path":"/b.jpg"},
            {"episode_number":3,"name":"C","still_path":"/c.jpg"}
        ]}"""
        val fake = FakePortalClient()
        // Declares 3 (the season's total) but only published 2.
        fake.queueResponse("v4/getItemData", seriesDetail(volumnCount = """"3""""))

        val (chapters, _) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertEquals(2, chapters.size)
        assertEquals("A", chapters[0].tmdbTitle)
        assertEquals("B", chapters[1].tmdbTitle)
    }

    @Test
    fun `the synopsis TMDB doesn't have in Spanish gets filled in in English`() = runTest {
        tmdbResponses["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Serie"}]}"""
        tmdbResponses["/tv/12/season/1|language=es-MX"] = """{"episodes":[
            {"episode_number":1,"name":"Uno","overview":""},
            {"episode_number":2,"name":"Dos","overview":"La que si estaba"}
        ]}"""
        tmdbResponses["/tv/12/season/1|language=en-US"] = """{"episodes":[
            {"episode_number":1,"name":"One","overview":"The english one"},
            {"episode_number":2,"name":"Two","overview":"No deberia pisar"}
        ]}"""
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail())

        val (chapters, _) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertEquals("The english one", chapters[0].overview)
        // The one that already had a Spanish synopsis does NOT get overwritten.
        assertEquals("La que si estaba", chapters[1].overview)
        assertEquals("Uno", chapters[0].tmdbTitle)
    }

    @Test
    fun `if it's unknown which season it is, it doesn't get enriched with another`() = runTest {
        tmdbResponses["/find/tt0088509"] = """{"tv_results":[{"id":12,"name":"Serie"}]}"""
        tmdbResponses["/tv/12/season/5"] = """{"episodes":[{"episode_number":1,"name":"No va"}]}"""
        val fake = FakePortalClient()
        // The list carries seasons, but none is this one: THAT is really unknown.
        fake.queueResponse(
            "v4/getItemData",
            seriesDetail(seasons = """[{"contentId":"OTRA","seasonNumber":5}]"""),
        )

        val (chapters, series) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertNull(chapters[0].tmdbTitle)
        assertEquals(0, series!!.tmdbId)
        assertEquals(0, series.seasonNumber)
        assertTrue("no debió pedirle nada a TMDB", tmdbRequests.isEmpty())
    }

    @Test
    fun `the chapter list is requested only once even if several get played`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail(imdb = ""))
        fake.queueResponse("v10/startPlayVOD", moviePlay("EP1"))
        fake.queueResponse("v14/getSlbInfo", slb())
        fake.queueResponse("v10/startPlayVOD", moviePlay("EP2"))
        val f = magisSource(fake)

        f.episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())
        f.resolve(MagisRef("SERIE", "teleplay", 1).encode())
        f.resolve(MagisRef("SERIE", "teleplay", 2).encode())

        assertEquals(1, fake.timesCalled("v4/getItemData"))
    }

    @Test
    fun `if TMDB fails, chapters still come out`() = runTest {
        tmdbServer.shutdown()
        val fake = FakePortalClient()
        fake.queueResponse("v4/getItemData", seriesDetail())

        val (chapters, series) = magisSource(fake).episodesWithSeries(MagisRef("SERIE", "teleplay", 0).encode())

        assertEquals(2, chapters.size)
        assertNull(chapters[0].still)
        assertEquals(0, series!!.tmdbId)
        assertEquals("tt0088509", series.imdbId)
    }
}
