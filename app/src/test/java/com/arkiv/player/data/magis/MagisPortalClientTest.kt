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

    private fun clientPointingAt(vararg hosts: String) = MagisPortalClient(
        crypto = crypto,
        hosts = hosts.toList(),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        scheme = "http",
    )

    private fun mockHost() = server.hostName + ":" + server.port

    @Test
    fun `a returnCode other than 0 translates to PortalError`() = runTest {
        val body = """{"returnCode":"aaa100028","errorMessage":"未登录！"}"""
        server.enqueue(MockResponse().setBody(body))
        val client = clientPointingAt(mockHost())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue(r is MagisResult.PortalError)
        assertEquals("aaa100028", (r as MagisResult.PortalError).code)
        assertEquals("未登录！", r.msg)
    }

    @Test
    fun `data encrypted in the response gets decrypted before being returned`() = runTest {
        val innerJson = """{"userId":"u1","userToken":"t1"}"""
        val wire = crypto.encryptBody(innerJson)
        server.enqueue(MockResponse().setBody("""{"returnCode":"0","data":"$wire"}"""))
        val client = clientPointingAt(mockHost())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue(r is MagisResult.Ok<*>)
        assertEquals("t1", r.getOrNull()?.getString("userToken"))
    }

    @Test
    fun `the body travels encrypted, with the device fields and the fixed headers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"returnCode":"0"}"""))
        val client = clientPointingAt(mockHost())

        client.call("v8/queryInfo", mapOf("vodId" to "42"), userId = "u9", userToken = "t9")

        val request = server.takeRequest()
        assertEquals("/api/portalCore/v8/queryInfo", request.path)
        assertEquals("com.android.msandroid", request.getHeader("apk"))
        assertEquals("43404", request.getHeader("apkVer"))
        assertEquals("okhttp/3.12.12", request.getHeader("User-Agent"))
        val sent = org.json.JSONObject(crypto.decryptBlob(request.body.readUtf8()))
        assertEquals("42", sent.getString("vodId"))
        assertEquals("masnew", sent.getString("portalCode"))
        assertEquals("u9", sent.getString("userId"))
        assertEquals("t9", sent.getString("userToken"))
        assertEquals("49902", sent.getString("apkVersion"))
        assertEquals(36, sent.getInt("sdkVer"))
    }

    @Test
    fun `baseFields false doesn't send portalCode or the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"returnCode":"0"}"""))
        val client = clientPointingAt(mockHost())

        client.call("v3/snToken", mapOf("androidId" to "abc"), baseFields = false)

        val sent = org.json.JSONObject(crypto.decryptBlob(server.takeRequest().body.readUtf8()))
        assertTrue(sent.isNull("portalCode") || !sent.has("portalCode"))
        assertTrue(!sent.has("userToken"))
        assertEquals("abc", sent.getString("androidId"))
    }

    @Test
    fun `if the first host doesn't answer, it falls to the next one`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val deadHost = dead.hostName + ":" + dead.port
        dead.shutdown()   // nobody's listening on that port: connection refused
        server.enqueue(MockResponse().setBody("""{"returnCode":"0","data":""}"""))
        val client = clientPointingAt(deadHost, mockHost())

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue("esperaba Ok y fue $r", r is MagisResult.Ok<*>)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `if no host answers, it returns RedError`() = runTest {
        val dead = MockWebServer()
        dead.start()
        val deadHost = dead.hostName + ":" + dead.port
        dead.shutdown()
        val client = clientPointingAt(deadHost)

        val r = client.call("v8/active", emptyMap(), baseFields = false)

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
    }
}
