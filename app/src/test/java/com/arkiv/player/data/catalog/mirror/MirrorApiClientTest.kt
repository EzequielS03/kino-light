package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MirrorApiClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(now: Long = 0L) =
        MirrorApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, nowMs = { now })

    @Test fun `resolveSlug por tmdbId devuelve el slug del primer resultado`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad","tmdb_id":"1396"}]}"""))
        val slug = client().resolveSlug(1396, ContentType.TV, titleFallback = null)
        assertEquals("breaking-bad", slug)
        val req = server.takeRequest()
        assert(req.path!!.contains("tmdb_id=1396")) { "path=${req.path}" }
        assert(req.path!!.contains("kind=serie")) { "path=${req.path}" }
    }

    @Test fun `resolveSlug cae a q cuando tmdbId no matchea`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))            // tmdb_id + kind: vacío
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))            // tmdb_id sin kind: vacío
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"matrix"}]}""")) // q: match
        val slug = client().resolveSlug(999999, ContentType.MOVIE, titleFallback = "Matrix")
        assertEquals("matrix", slug)
        server.takeRequest() // tmdb_id + kind
        server.takeRequest() // tmdb_id sin kind
        val q = server.takeRequest()
        assert(q.path!!.contains("q=Matrix")) { "path=${q.path}" }
    }

    @Test fun `resolveSlug reintenta sin kind cuando el kind pedido no matchea (anime pedido como serie)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))                          // tmdb_id + kind=serie: vacío
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"naruto-shippuden"}]}""")) // tmdb_id sin kind: match
        val slug = client().resolveSlug(31910, ContentType.TV, titleFallback = null)
        assertEquals("naruto-shippuden", slug)
        val r1 = server.takeRequest(); assert(r1.path!!.contains("kind=serie")) { "path=${r1.path}" }
        val r2 = server.takeRequest()
        assert(r2.path!!.contains("tmdb_id=31910") && !r2.path!!.contains("kind=")) { "path=${r2.path}" }
    }

    @Test fun `resolveSlug devuelve null si no hay match ni fallback`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))  // tmdb_id + kind
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))  // tmdb_id sin kind
        assertNull(client().resolveSlug(1, ContentType.MOVIE, titleFallback = null))
    }

    @Test fun `titleTorrents parsea campos y tolera seeders null`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"slug":"breaking-bad","torrents":[
                {"magnet":"magnet:?xt=urn:btih:AAA","infohash":"aaa","season":1,"episode":1,
                 "episode_end":null,"is_pack":false,"lang_norm":"LATINO","lang_raw":"Latino/Inglés",
                 "quality":"WEB-DL 1080p","seeders":null,"size_bytes":2877628088,"size_label":"2.68 GB",
                 "source":"pelispanda","name":null}
            ]}""",
        ))
        val list = client().titleTorrents("breaking-bad")
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals(1, t.season); assertEquals(1, t.episode)
        assertEquals("LATINO", t.langNorm); assertEquals(0, t.seeders ?: 0)
        assertEquals(2877628088L, t.sizeBytes); assertEquals("magnet:?xt=urn:btih:AAA", t.magnet)
        assertNull(t.name) // "name":null → null (en Android optString devolvería "null"; guardamos con isNull)
    }

    @Test fun `titleTorrents cachea por slug dentro del TTL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"torrents":[{"size_bytes":1,"is_pack":false}]}"""))
        val c = client(now = 0L)
        c.titleTorrents("x"); c.titleTorrents("x")
        assertEquals(1, server.requestCount) // segundo llamado vino de caché
    }

    @Test fun `titleTorrents junta anime (packs + seasons_episodes) ademas de torrents plano`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"slug":"naruto","kind":"anime",
                "packs":[{"magnet":"magnet:PACK","infohash":"p","is_pack":true,"episode":1,"episode_end":220,"lang_norm":"CASTELLANO","size_bytes":10}],
                "seasons":[
                  {"season":1,"episodes":[
                    {"magnet":"magnet:E1","infohash":"e1","season":1,"episode":1,"is_pack":false,"lang_norm":"CASTELLANO","size_bytes":1},
                    {"magnet":"magnet:E2","infohash":"e2","season":1,"episode":2,"is_pack":false,"lang_norm":"OTHER","size_bytes":1}
                  ]},
                  {"season":2,"episodes":[
                    {"magnet":"magnet:S2E1","infohash":"s2e1","season":2,"episode":1,"is_pack":false,"lang_norm":"OTHER","size_bytes":1}
                  ]}
                ]}"""
        ))
        val list = client().titleTorrents("naruto")
        assertEquals(setOf("magnet:PACK","magnet:E1","magnet:E2","magnet:S2E1"), list.mapNotNull { it.magnet }.toSet())
        assertEquals(true, list.first { it.magnet == "magnet:PACK" }.isPack)
    }

    // --- refresh(): la UNICA llamada de esta clase que habla con el GATEWAY (no con el mirror) --
    // --- Task 8 (Paso 3): Authorization + X-Arkiv-Device son la ÚNICA credencial, X-Arkiv-Key ---
    // --- salió del todo ------------------------------------------------------------------------

    private fun clientConGateway(personTok: String? = null, deviceTok: String? = null) = MirrorApiClient(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        gatewayUrl = { server.url("/").toString().trimEnd('/') },
        personToken = { personTok },
        deviceToken = { deviceTok },
    )

    @Test fun `refresh manda Authorization y X-Arkiv-Device cuando hay sesion, nunca X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"created":false,"web_sources_added":0,"torrents_added":0}"""))
        clientConGateway(personTok = "person-tok", deviceTok = "device-tok")
            .refresh(1396, ContentType.TV, "Breaking Bad", "2008")
        val req = server.takeRequest()
        assertEquals("/v1/catalog/refresh", req.path)
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
        assertNull(req.getHeader("X-Arkiv-Key"))
    }

    @Test fun `refresh sin sesion no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"created":false,"web_sources_added":0,"torrents_added":0}"""))
        clientConGateway().refresh(1396, ContentType.TV, "Breaking Bad", "2008")
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
        assertNull(req.getHeader("X-Arkiv-Key"))
    }

    @Test fun `resolveSlug (habla con el MIRROR, no el gateway) no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        // Aunque el cliente tenga sesion configurada, resolveSlug/titleTorrents/etc. van al mirror
        // -otro host-, donde estas cabeceras no significan nada.
        server.enqueue(MockResponse().setBody("""{"results":[{"slug":"breaking-bad"}]}"""))
        clientConGateway(personTok = "person-tok", deviceTok = "device-tok")
            .resolveSlug(1396, ContentType.TV, titleFallback = null)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
        assertTrue(req.path!!.contains("tmdb_id=1396"))
    }
}
