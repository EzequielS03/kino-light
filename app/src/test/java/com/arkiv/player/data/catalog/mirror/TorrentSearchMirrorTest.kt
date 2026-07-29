package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentSearchApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TorrentSearchMirrorTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = TorrentSearchApi(
        MirrorApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, nowMs = { 0L }),
    )

    @Test fun `searchMovie resuelve slug por tmdbId y devuelve torrents mapeados`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"matrix"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[{"magnet":"magnet:?xt=urn:btih:AAA","infohash":"aaa","is_pack":false,
                "lang_norm":"CASTELLANO","quality":"1080p","seeders":null,"size_bytes":1073741824}]}""",
        ))
        val r = api().searchMovie(listOf("Matrix"), "1999", emptySet(), tmdbId = 603)
        assertEquals(1, r.size)
        assertEquals(TorrentLang.CASTELLANO, r[0].lang)
        assertTrue(r[0].magnetUri!!.startsWith("magnet:"))
    }

    @Test fun `searchEpisode filtra al capítulo pedido`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[
                {"magnet":"magnet:S1E1","infohash":"a","season":1,"episode":1,"is_pack":false,
                 "lang_norm":"LATINO","size_bytes":1000},
                {"magnet":"magnet:S1E2","infohash":"b","season":1,"episode":2,"is_pack":false,
                 "lang_norm":"LATINO","size_bytes":1000}
            ]}""",
        ))
        val r = api().searchEpisode(listOf("Breaking Bad"), 1, 1, emptySet(), tmdbId = 1396)
        assertEquals(listOf("magnet:S1E1"), r.map { it.magnetUri })
    }

    @Test fun `título inexistente en el backend devuelve vacío`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertEquals(emptyList<Any>(), api().searchMovie(listOf("noexiste"), "", emptySet()))
    }

    @Test fun `searchAnime filtra por episodio absoluto o relativo sin exigir temporada`() = runBlocking {
        // spec sin tvdbSeason => seasonKnown=false => seasonForMirror=0 (no exige temporada)
        val spec = com.arkiv.player.data.catalog.AnimeEpisodeResolver.spec(
            com.arkiv.player.data.catalog.AnimeQueryInput(
                titles = listOf("One Piece"),
                episode = 5,
                absoluteEpisode = 1085,
                tvdbSeason = null,
            ),
        )
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"one-piece"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[
                {"magnet":"magnet:ABS","infohash":"a","season":1,"episode":1085,"is_pack":false,"lang_norm":"LATINO","size_bytes":1000},
                {"magnet":"magnet:REL","infohash":"b","season":21,"episode":5,"is_pack":false,"lang_norm":"LATINO","size_bytes":1000},
                {"magnet":"magnet:NO","infohash":"c","season":1,"episode":3,"is_pack":false,"lang_norm":"LATINO","size_bytes":1000}
            ]}""",
        ))
        val r = api().searchAnime(spec, emptySet(), tmdbId = 37854)
        assertEquals(setOf("magnet:ABS", "magnet:REL"), r.mapNotNull { it.magnetUri }.toSet())
    }

    @Test fun `searchSeriesBrowse devuelve todas las fuentes de la serie sin filtrar por episodio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[
                {"magnet":"magnet:S1E1","infohash":"a","season":1,"episode":1,"is_pack":false,"lang_norm":"LATINO","size_bytes":1000},
                {"magnet":"magnet:PACK","infohash":"b","season":1,"episode":1,"episode_end":8,"is_pack":true,"lang_norm":"LATINO","size_bytes":1000}
            ]}""",
        ))
        val r = api().searchSeriesBrowse(listOf("Breaking Bad"), emptySet(), tmdbId = 1396)
        assertEquals(setOf("magnet:S1E1", "magnet:PACK"), r.mapNotNull { it.magnetUri }.toSet())
    }

    @Test fun `searchByText resuelve por q sin kind y trae fuentes de cualquier tipo`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad"}]}"""))
        server.enqueue(MockResponse().setBody(
            """{"torrents":[{"magnet":"magnet:X","infohash":"x","is_pack":false,"lang_norm":"LATINO","size_bytes":1000}]}""",
        ))
        val r = api().searchByText("breaking bad", emptySet())
        assertEquals(listOf("magnet:X"), r.mapNotNull { it.magnetUri })
        val req = server.takeRequest()
        assert(req.path!!.contains("q=breaking") && !req.path!!.contains("kind=")) { "path=${req.path}" }
    }
}
