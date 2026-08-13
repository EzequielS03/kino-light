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
 * Task 8 (Paso 2): [SimklApi] no tenía test propio. Cubre exclusivamente lo que suma este paso --
 * Authorization + X-Arkiv-Device, sin sacar `X-Arkiv-Key` -- en las DOS llamadas que hace
 * [SimklApi.infoByAniList] (search/id y anime/{id}).
 */
class SimklApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api(personTok: String? = null, deviceTok: String? = null) = SimklApi(
        gatewayUrl = { server.url("/").toString().trimEnd('/') },
        arkivKey = { "LLAVE" },
        client = OkHttpClient(),
        personToken = { personTok },
        deviceToken = { deviceTok },
    )

    private fun encolarBusquedaYDetalle() {
        server.enqueue(MockResponse().setBody("""[{"ids":{"simkl":42}}]"""))
        server.enqueue(
            MockResponse().setBody(
                """{"ids":{"simkl":42,"imdb":"tt1","tmdb":"99","tvdb":"5"},"total_episodes":10,"alt_titles":[]}""",
            ),
        )
    }

    @Test
    fun `manda Authorization y X-Arkiv-Device en las dos llamadas, ademas de la llave`() = runBlocking {
        encolarBusquedaYDetalle()
        api(personTok = "person-tok", deviceTok = "device-tok").infoByAniList(1)
        repeat(2) {
            val req = server.takeRequest()
            assertEquals("person-tok", req.getHeader("Authorization"))
            assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
            assertEquals("LLAVE", req.getHeader("X-Arkiv-Key"))
        }
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        encolarBusquedaYDetalle()
        api().infoByAniList(1)
        repeat(2) {
            val req = server.takeRequest()
            assertNull(req.getHeader("Authorization"))
            assertNull(req.getHeader("X-Arkiv-Device"))
        }
    }
}
