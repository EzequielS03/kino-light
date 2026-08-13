package com.arkiv.player.data.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Task 8 (Paso 2): [TmdbApi] no tenía test propio (solo [TmdbSearchMultiTest], que ejercita el
 * parseo de `/search/multi` sin mirar cabeceras). Este archivo cubre exclusivamente lo que suma
 * este paso -- Authorization + X-Arkiv-Device, sin sacar `X-Arkiv-Key` -- no una suite completa
 * de [TmdbApi], que está fuera de alcance.
 */
class TmdbApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api(personTok: String? = null, deviceTok: String? = null) = TmdbApi(
        gatewayUrl = { server.url("/").toString().trimEnd('/') },
        arkivKey = { "LLAVE" },
        client = OkHttpClient(),
        personToken = { personTok },
        deviceToken = { deviceTok },
    )

    @Test
    fun `manda Authorization y X-Arkiv-Device cuando hay sesion, ademas de la llave`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        api(personTok = "person-tok", deviceTok = "device-tok").browse("movie", 1)
        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
        assertEquals("LLAVE", req.getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device (nunca cabeceras vacias)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        api().browse("movie", 1)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `un token en blanco tambien se omite, no se manda vacio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        api(personTok = "", deviceTok = "   ").browse("movie", 1)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
    }
}
