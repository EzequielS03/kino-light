package com.arkiv.player.data.gateway

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
 * Lo que le queda a este cliente después del sub-proyecto 2A: la trivia (excepción permanente, ver
 * el CLAUDE.md de la rama). La búsqueda, la reproducción y los capítulos se fueron al portal
 * directo — sus tests viven en `MagisFuenteTest`.
 */
class ArkivApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArkivApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ArkivApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun conSesion(persona: String?, aparato: String?) = ArkivApiClient(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        http = OkHttpClient(),
        personToken = { persona },
        deviceToken = { aparato },
    )

    // --- credenciales: Authorization + X-Arkiv-Device son la ÚNICA que manda ------------------

    @Test
    fun `manda Authorization y X-Arkiv-Device cuando hay sesion`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"textos":[]}"""))

        conSesion("person-tok", "device-tok").trivia(1, "movie", null, null)

        val pedido = server.takeRequest()
        assertEquals("person-tok", pedido.getHeader("Authorization"))
        assertEquals("device-tok", pedido.getHeader("X-Arkiv-Device"))
    }

    /** `X-Arkiv-Key` salió del todo: el corte fue real, no solo dejar de mandar un valor. */
    @Test
    fun `nunca manda X-Arkiv-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"textos":[]}"""))

        conSesion("person-tok", "device-tok").trivia(1, "movie", null, null)

        assertNull(server.takeRequest().getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device (nunca cabeceras vacias)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"textos":[]}"""))

        client.trivia(1, "movie", null, null)

        val pedido = server.takeRequest()
        assertNull(pedido.getHeader("Authorization"))
        assertNull(pedido.getHeader("X-Arkiv-Device"))
    }

    @Test
    fun `un token en blanco tambien se omite, no se manda vacio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"textos":[]}"""))

        conSesion("   ", "").trivia(1, "movie", null, null)

        val pedido = server.takeRequest()
        assertNull(pedido.getHeader("Authorization"))
        assertNull(pedido.getHeader("X-Arkiv-Device"))
    }

    // --- trivia -------------------------------------------------------------------------------

    @Test
    fun `la trivia devuelve los textos y descarta los vacios`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"textos":["uno","","dos"]}"""))

        val textos = client.trivia(42, "tv", 1, 3)

        assertEquals(listOf("uno", "dos"), textos)
        val url = server.takeRequest().requestUrl!!
        assertEquals("42", url.queryParameter("tmdbId"))
        assertEquals("tv", url.queryParameter("tipo"))
        assertEquals("1", url.queryParameter("temporada"))
        assertEquals("3", url.queryParameter("episodio"))
    }

    @Test
    fun `sin bloque de textos la trivia va vacia en vez de lanzar`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(emptyList<String>(), client.trivia(42, "movie", null, null))
    }

}
