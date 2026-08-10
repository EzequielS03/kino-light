package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MagisLinkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MagisLinkClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = MagisLinkClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            apiKey = { "LLAVE" },
            accountId = { "acc-9" },
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `status parsea linked true`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":true}"""))
        assertTrue(client.status())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/magis/link", req.path)
    }

    @Test
    fun `status parsea linked false`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":false}"""))
        assertFalse(client.status())
    }

    @Test
    fun `status manda X-Arkiv-Key y X-Arkiv-Account`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":false}"""))
        client.status()
        val req = server.takeRequest()
        assertEquals("LLAVE", req.getHeader("X-Arkiv-Key"))
        assertEquals("acc-9", req.getHeader("X-Arkiv-Account"))
    }

    @Test
    fun `link manda POST con headers y body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.link("user1", "pass1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/magis/link", req.path)
        assertEquals("LLAVE", req.getHeader("X-Arkiv-Key"))
        assertEquals("acc-9", req.getHeader("X-Arkiv-Account"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("user1", body.getString("username"))
        assertEquals("pass1", body.getString("password"))
    }

    @Test
    fun `unlink manda DELETE`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.unlink()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/magis/link", req.path)
    }

    @Test(expected = MagisLinkException::class)
    fun `un 422 lanza MagisLinkException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"credenciales invalidas"}"""))
        client.link("user1", "pass1")
        Unit
    }

    @Test
    fun `el 422 trae el detail en el mensaje`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"credenciales invalidas"}"""))
        try {
            client.link("user1", "pass1")
            org.junit.Assert.fail("esperaba MagisLinkException")
        } catch (e: MagisLinkException) {
            assertEquals(422, e.code)
            assertEquals("credenciales invalidas", e.message)
        }
    }
}
