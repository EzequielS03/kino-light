package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MagisPortalClientTest {
    private lateinit var server: MockWebServer
    private val crypto = MagisCrypto("e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8")

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() { server.shutdown() }

    private fun clienteApuntandoA(vararg hosts: String) = MagisPortalClient(
        crypto = crypto,
        hosts = hosts.toList(),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        scheme = "http",
    )

    private fun hostDelMock() = server.hostName + ":" + server.port

    @Test
    fun `returnCode distinto de 0 se traduce a PortalError`() = runTest {
        val body = """{"returnCode":"aaa100028","errorMessage":"未登录！"}"""
        server.enqueue(MockResponse().setBody(body))
        val client = clienteApuntandoA(hostDelMock())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue(r is MagisResult.PortalError)
        assertEquals("aaa100028", (r as MagisResult.PortalError).codigo)
        assertEquals("未登录！", r.msg)
    }

    @Test
    fun `data cifrado en la respuesta se descifra antes de devolverlo`() = runTest {
        val innerJson = """{"userId":"u1","userToken":"t1"}"""
        val wire = crypto.encryptBody(innerJson)
        server.enqueue(MockResponse().setBody("""{"returnCode":"0","data":"$wire"}"""))
        val client = clienteApuntandoA(hostDelMock())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue(r is MagisResult.Ok<*>)
        assertEquals("t1", r.dato()?.getString("userToken"))
    }

    @Test
    fun `el body viaja cifrado, con los campos de device y los headers fijos`() = runTest {
        server.enqueue(MockResponse().setBody("""{"returnCode":"0"}"""))
        val client = clienteApuntandoA(hostDelMock())

        client.call("v8/queryInfo", mapOf("vodId" to "42"), userId = "u9", userToken = "t9")

        val pedido = server.takeRequest()
        assertEquals("/api/portalCore/v8/queryInfo", pedido.path)
        assertEquals("com.android.msandroid", pedido.getHeader("apk"))
        assertEquals("43404", pedido.getHeader("apkVer"))
        assertEquals("okhttp/3.12.12", pedido.getHeader("User-Agent"))
        val enviado = org.json.JSONObject(crypto.decryptBlob(pedido.body.readUtf8()))
        assertEquals("42", enviado.getString("vodId"))
        assertEquals("masnew", enviado.getString("portalCode"))
        assertEquals("u9", enviado.getString("userId"))
        assertEquals("t9", enviado.getString("userToken"))
        assertEquals("49902", enviado.getString("apkVersion"))
        assertEquals(36, enviado.getInt("sdkVer"))
    }

    @Test
    fun `baseFields en false no manda portalCode ni la sesion`() = runTest {
        server.enqueue(MockResponse().setBody("""{"returnCode":"0"}"""))
        val client = clienteApuntandoA(hostDelMock())

        client.call("v3/snToken", mapOf("androidId" to "abc"), baseFields = false)

        val enviado = org.json.JSONObject(crypto.decryptBlob(server.takeRequest().body.readUtf8()))
        assertTrue(enviado.isNull("portalCode") || !enviado.has("portalCode"))
        assertTrue(!enviado.has("userToken"))
        assertEquals("abc", enviado.getString("androidId"))
    }

    @Test
    fun `si el primer host no contesta, cae al siguiente`() = runTest {
        val muerto = MockWebServer()
        muerto.start()
        val hostMuerto = muerto.hostName + ":" + muerto.port
        muerto.shutdown()   // nadie escucha en ese puerto: conexión rechazada
        server.enqueue(MockResponse().setBody("""{"returnCode":"0","data":""}"""))
        val client = clienteApuntandoA(hostMuerto, hostDelMock())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue("esperaba Ok y fue $r", r is MagisResult.Ok<*>)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `si ningun host contesta, devuelve RedError`() = runTest {
        val muerto = MockWebServer()
        muerto.start()
        val hostMuerto = muerto.hostName + ":" + muerto.port
        muerto.shutdown()
        val client = clienteApuntandoA(hostMuerto)

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
    }
}
