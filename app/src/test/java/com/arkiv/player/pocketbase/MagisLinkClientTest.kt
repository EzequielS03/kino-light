package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `status nunca manda X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":false}"""))
        client.status()
        val req = server.takeRequest()
        assertNull(req.getHeader("X-Arkiv-Key"))
    }

    // --- Task 8 (Paso 3): Authorization + X-Arkiv-Device, sin ninguna llave ---

    @Test
    fun `status manda Authorization y X-Arkiv-Device cuando hay sesion`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":false}"""))
        val conSesion = MagisLinkClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            client = OkHttpClient(),
            personToken = { "person-tok" },
            deviceToken = { "device-tok" },
        )
        conSesion.status()
        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        // `client` del setUp no pasa personToken/deviceToken -- quedan en null por default.
        server.enqueue(MockResponse().setBody("""{"linked":false}"""))
        client.status()
        val req = server.takeRequest()
        assertEquals(null, req.getHeader("Authorization"))
        assertEquals(null, req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `link manda POST con headers y body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.link("user1", "pass1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/magis/link", req.path)
        assertNull(req.getHeader("X-Arkiv-Key"))
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

    @Test(expected = MagisLinkException::class)
    fun `una falla de red al llamar status envuelve en MagisLinkException`() = runBlocking {
        server.shutdown()
        client.status()
        Unit
    }

    @Test
    fun `registerSendCode manda POST con email`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.registerSendCode("a@b.co")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/magis/register/send-code", req.path)
        assertNull(req.getHeader("X-Arkiv-Key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("a@b.co", body.getString("email"))
    }

    @Test(expected = MagisLinkException::class)
    fun `registerSendCode con 503 lanza MagisLinkException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"caido"}"""))
        client.registerSendCode("a@b.co")
        Unit
    }

    @Test
    fun `registerConfirm manda POST con email password y code`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.registerConfirm("a@b.co", "secret12", "123456")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/magis/register/confirm", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("a@b.co", body.getString("email"))
        assertEquals("secret12", body.getString("password"))
        assertEquals("123456", body.getString("code"))
    }

    @Test
    fun `registerConfirm con 422 trae el detail en el mensaje`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"codigo invalido"}"""))
        try {
            client.registerConfirm("a@b.co", "secret12", "000000")
            org.junit.Assert.fail("esperaba MagisLinkException")
        } catch (e: MagisLinkException) {
            assertEquals(422, e.code)
            assertEquals("codigo invalido", e.message)
        }
    }
}
