package com.arkiv.player.data.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveApiTest {
    private fun api(server: MockWebServer) = LiveApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        apiKey = { "k" },
        http = OkHttpClient(),
    )

    @Test
    fun `canales sin logo quedan en null`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":501,"logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(76206)
        assertEquals("ESPN", canales[0].nombre)
        assertEquals(501, canales[0].numero)
        assertNull(canales[0].logo)
        server.shutdown()
    }

    @Test
    fun `la llave del gateway viaja en la cabecera`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[]}"""))
        server.start()
        api(server).categorias()
        assertEquals("k", server.takeRequest().getHeader("X-Arkiv-Key"))
        server.shutdown()
    }

    @Test
    fun `epg separa lo que llego de lo que falta`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"epg":{"c1":[{"titulo":"Partido","inicio":100,"fin":200,"sinopsis":"s"}]},"missing":["c2"]}"""
        ))
        server.start()
        val (epg, faltan) = api(server).epg(listOf("c1", "c2"))
        assertEquals("Partido", epg["c1"]!![0].titulo)
        assertEquals(listOf("c2"), faltan)
        server.shutdown()
    }

    @Test
    fun `firmar pide el lote y devuelve los pares`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"firmas":[{"moment":1,"sign2":"aa"},{"moment":2,"sign2":"bb"}]}"""
        ))
        server.start()
        val firmas = api(server).firmar("t", 2, 0)
        assertEquals(2, firmas.size)
        assertEquals("bb", firmas[1].sign2)
        server.shutdown()
    }

    // --- JSON hostil: el portal ya nos sorprendió con campos ausentes, tipos raros y nulls.
    // El contrato de LiveApi es que ESO se degrada a valores por defecto, nunca a una excepción
    // que tumbe la pantalla. ---

    @Test
    fun `canales sin el campo canales en la respuesta no explota`() = runBlocking {
        val server = MockWebServer()
        // El gateway respondió 200 pero sin la clave "canales" (p.ej. una categoría vacía mal armada).
        server.enqueue(MockResponse().setBody("""{}"""))
        server.start()
        val canales = api(server).canales(1)
        assertTrue(canales.isEmpty())
        server.shutdown()
    }

    @Test
    fun `un canal sin la clave logo (ni siquiera null) tambien queda en null`() = runBlocking {
        val server = MockWebServer()
        // Acá "logo" ni siquiera está presente, a diferencia del test del brief donde viene null.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":501}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertNull(canales[0].logo)
        server.shutdown()
    }

    @Test
    fun `un canal con numero de tipo inesperado no explota`() = runBlocking {
        val server = MockWebServer()
        // "numero" llega como texto no numérico en vez de int.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":"desconocido","logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertEquals(0, canales[0].numero)
        server.shutdown()
    }

    @Test
    fun `un canal sin code se descarta en vez de reventar`() = runBlocking {
        val server = MockWebServer()
        // Sin "code" el canal es inservible (no se puede resolver ni pedir EPG): se descarta.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"nombre":"Fantasma","numero":1},{"code":"c2","nombre":"OK","numero":2,"logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertEquals(1, canales.size)
        assertEquals("c2", canales[0].code)
        server.shutdown()
    }

    @Test
    fun `una categoria sin nombre no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[{"id":5}]}"""))
        server.start()
        val categorias = api(server).categorias()
        assertEquals(5, categorias[0].id)
        assertEquals("", categorias[0].nombre)
        server.shutdown()
    }

    @Test
    fun `un elemento de categorias que no es un objeto se descarta`() = runBlocking {
        val server = MockWebServer()
        // Un string suelto en el array en vez de un objeto: no debe tirar JSONException.
        server.enqueue(MockResponse().setBody("""{"categorias":["basura",{"id":1,"nombre":"Deportes"}]}"""))
        server.start()
        val categorias = api(server).categorias()
        assertEquals(1, categorias.size)
        assertEquals("Deportes", categorias[0].nombre)
        server.shutdown()
    }

    @Test
    fun `epg sin el campo missing no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"epg":{}}"""))
        server.start()
        val (epg, faltan) = api(server).epg(listOf("c1"))
        assertTrue(epg.isEmpty())
        assertTrue(faltan.isEmpty())
        server.shutdown()
    }

    @Test
    fun `un programa del epg con campos ausentes no explota`() = runBlocking {
        val server = MockWebServer()
        // Sin "sinopsis" ni "fin": no debe tirar, cae a los valores por defecto.
        server.enqueue(MockResponse().setBody(
            """{"epg":{"c1":[{"titulo":"Partido","inicio":100}]},"missing":[]}"""
        ))
        server.start()
        val (epg, _) = api(server).epg(listOf("c1"))
        assertEquals("Partido", epg["c1"]!![0].titulo)
        assertEquals(0L, epg["c1"]!![0].fin)
        assertEquals("", epg["c1"]!![0].sinopsis)
        server.shutdown()
    }

    @Test
    fun `resolver con campos ausentes no explota`() = runBlocking {
        val server = MockWebServer()
        // Respuesta 200 pero incompleta: no debe tirar JSONException al armar la sesión.
        server.enqueue(MockResponse().setBody("""{"channel":"c1"}"""))
        server.start()
        val sesion = api(server).resolver("c1")
        assertEquals("c1", sesion.channel)
        assertEquals("", sesion.cflHost)
        server.shutdown()
    }

    @Test
    fun `firmar con una firma sin sign2 no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"firmas":[{"moment":1}]}"""))
        server.start()
        val firmas = api(server).firmar("t", 1, 0)
        assertEquals(1L, firmas[0].moment)
        assertEquals("", firmas[0].sign2)
        server.shutdown()
    }
}
