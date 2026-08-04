package com.arkiv.player.data.offline

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArkivOfflineApiTest {
    private lateinit var lanServer: MockWebServer
    private lateinit var tunnelServer: MockWebServer

    @Before fun setUp() {
        lanServer = MockWebServer(); lanServer.start()
        tunnelServer = MockWebServer(); tunnelServer.start()
    }

    @After fun tearDown() {
        lanServer.shutdown()
        tunnelServer.shutdown()
    }

    private fun api(lanUrl: String, tunnelUrl: String, apiKey: String = "secret123") =
        ArkivOfflineApi(
            lanBaseUrl = { lanUrl },
            tunnelBaseUrl = { tunnelUrl },
            apiKey = { apiKey },
        )

    @Test fun `resolves LAN when it responds`() = runBlocking {
        lanServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val lanUrl = lanServer.url("/").toString().trimEnd('/')
        val tunnelUrl = tunnelServer.url("/").toString().trimEnd('/')
        val resolved = api(lanUrl, tunnelUrl).baseUrlResolved()
        assertEquals(lanUrl, resolved)
        val probe = lanServer.takeRequest()
        assertTrue(probe.path!!.contains("/library"))
        assertTrue(probe.path!!.contains("series_id=__probe__"))
        assertEquals("secret123", probe.getHeader("X-Api-Key"))
    }

    @Test fun `falls back to tunnel when LAN times out`() = runBlocking {
        tunnelServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        // TEST-NET-1 (RFC 5737): dirección no ruteable, nadie responde ni rechaza la conexión ->
        // fuerza el connectTimeout corto del probe en vez de un connection-refused instantáneo.
        val deadLanUrl = "http://192.0.2.1:1"
        val tunnelUrl = tunnelServer.url("/").toString().trimEnd('/')
        val resolved = api(deadLanUrl, tunnelUrl).baseUrlResolved()
        assertEquals(tunnelUrl, resolved)
    }

    @Test fun `createJob posts items array matching arkiv-offline contract`() = runBlocking {
        lanServer.enqueue(MockResponse().setResponseCode(200).setBody("[]")) // probe LAN
        lanServer.enqueue(MockResponse().setResponseCode(201).setBody("""{"job_id": 42}"""))
        val lanUrl = lanServer.url("/").toString().trimEnd('/')
        val tunnelUrl = tunnelServer.url("/").toString().trimEnd('/')

        val jobId = api(lanUrl, tunnelUrl).createJob(
            seriesId = "tt123",
            showTitle = "Breaking Bad",
            posterUrl = "http://x/poster.jpg",
            items = listOf(NucDownloadItem(season = 1, episode = 1, pageUrl = "http://web/ep1")),
        )
        assertEquals(42L, jobId)

        lanServer.takeRequest() // el probe LAN
        val req = lanServer.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/jobs", req.path)
        assertEquals("secret123", req.getHeader("X-Api-Key"))

        val body = org.json.JSONObject(req.body.readUtf8())
        assertEquals("web", body.getString("kind"))
        assertEquals("tt123", body.getString("series_id"))
        assertEquals("Breaking Bad", body.getString("show_title"))
        val items = body.getJSONArray("items")
        assertEquals(1, items.length())
        val item0 = items.getJSONObject(0)
        assertEquals(1, item0.getInt("season"))
        assertEquals(1, item0.getInt("episode"))
        assertEquals("http://web/ep1", item0.getString("source_ref"))
    }

    @Test fun `streamUrl appends api_key query param`() {
        val api = ArkivOfflineApi(lanBaseUrl = { "" }, tunnelBaseUrl = { "" }, apiKey = { "secret123" })
        assertEquals("http://x/stream/42?api_key=secret123", api.streamUrl(42, "http://x"))
    }

    @Test fun `getJob devuelve null si la respuesta no es exitosa`() = runBlocking {
        lanServer.enqueue(MockResponse().setResponseCode(200).setBody("[]")) // probe LAN
        lanServer.enqueue(MockResponse().setResponseCode(404))
        val lanUrl = lanServer.url("/").toString().trimEnd('/')
        val tunnelUrl = tunnelServer.url("/").toString().trimEnd('/')
        assertNull(api(lanUrl, tunnelUrl).getJob(999))
    }
}
