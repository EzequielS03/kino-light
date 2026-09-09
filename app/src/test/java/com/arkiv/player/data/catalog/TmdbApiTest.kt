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
 * Cubre cómo [TmdbApi] autentica ahora que habla DIRECTO con TMDB (sub-proyecto 2A): la llave va
 * como parámetro de query y ya no viaja ninguna cabecera de sesión, porque no hay gateway del otro
 * lado al que autenticarse. No pretende ser una suite completa de [TmdbApi].
 */
class TmdbApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api() = TmdbApi(
        apiKey = "llave-de-test",
        baseUrl = server.url("/3").toString().trimEnd('/'),
        client = OkHttpClient(),
    )

    @Test
    fun `la llave y el idioma van en la query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val pedido = server.takeRequest()
        assertEquals("llave-de-test", pedido.requestUrl?.queryParameter("api_key"))
        assertEquals("es-MX", pedido.requestUrl?.queryParameter("language"))
        assertEquals("/3/movie/popular", pedido.requestUrl?.encodedPath)
    }

    @Test
    fun `no viaja ninguna cabecera de sesion del gateway`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().browse("movie", 1)

        val pedido = server.takeRequest()
        assertNull(pedido.getHeader("Authorization"))
        assertNull(pedido.getHeader("X-Arkiv-Device"))
        assertNull(pedido.getHeader("X-Arkiv-Key"))
    }

    @Test
    fun `la busqueda manda la llave y no pide contenido adulto`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        api().search("movie", "batman")

        val url = server.takeRequest().requestUrl!!
        assertEquals("llave-de-test", url.queryParameter("api_key"))
        assertEquals("batman", url.queryParameter("query"))
        assertEquals("false", url.queryParameter("include_adult"))
    }

    @Test
    fun `por defecto apunta a TMDB, no a ningun servidor propio`() {
        // La base se fija en el build y no es configurable desde Ajustes, como la llave.
        assertEquals("https://api.themoviedb.org/3", TmdbApi.BASE_TMDB)
    }
}
