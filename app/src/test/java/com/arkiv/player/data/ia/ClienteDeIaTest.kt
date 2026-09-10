package com.arkiv.player.data.ia

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClienteDeIaTest {

    private lateinit var server: MockWebServer
    private var ahora = 1_000_000L
    private val almacen = object : AlmacenDeMemoria {
        var json: String? = null
        override fun leer() = json
        override fun guardar(json: String) { this.json = json }
    }

    /** Respuesta por modelo; lo que no esté acá contesta un chat válido. */
    private val porModelo = mutableMapOf<String, MockResponse>()
    private var catalogo: MockResponse = MockResponse().setBody(
        """{"data":[
          {"id":"a:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"b:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"c:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"d:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]}
        ]}""",
    )
    private val pedidosDeChat = mutableListOf<String>()
    private var pedidosDeCatalogo = 0

    @Before fun arranca() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/models")) {
                    pedidosDeCatalogo++
                    return catalogo
                }
                // .clone(): request.body es la MISMA instancia que devuelve takeRequest() más abajo
                // (verificado con javap sobre mockwebserver 4.12.0); leerla acá la deja vacía para
                // quien la vuelva a leer después (el test de "dialecto OpenAI" la vuelve a leer).
                val cuerpo = request.body.clone().readUtf8()
                val modelo = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(cuerpo)!!.groupValues[1]
                pedidosDeChat += modelo
                return porModelo[modelo] ?: MockResponse().setBody(
                    """{"model":"$modelo","choices":[{"message":{"content":"hola desde $modelo"}}]}""",
                )
            }
        }
        server.start()
    }

    @After fun apaga() = server.shutdown()

    private fun cliente() = ClienteDeIa(
        baseUrl = server.url("/api/gateway").toString().trimEnd('/'),
        memoria = MemoriaDeModelos(almacen) { ahora },
        ahoraMs = { ahora },
    )

    @Test fun `contesta con el primer modelo que sirve`() = runTest {
        val r = cliente().preguntar("di hola")
        assertEquals(RespuestaDeIa.Texto("hola desde a:free", "a:free"), r)
    }

    /** El tier anónimo de Kilo depende de que esta cabecera NO viaje. */
    @Test fun `nunca manda Authorization`() = runTest {
        cliente().preguntar("di hola")
        repeat(server.requestCount) {
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `el pedido va en dialecto OpenAI`() = runTest {
        cliente().preguntar("di hola")
        server.takeRequest() // /models
        val chat = server.takeRequest()
        assertTrue(chat.path!!.endsWith("/chat/completions"))
        val cuerpo = chat.body.readUtf8()
        assertTrue(cuerpo.contains("\"role\":\"user\""))
        assertTrue(cuerpo.contains("di hola"))
    }

    @Test fun `un 429 salta al siguiente modelo`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(429)
        val r = cliente().preguntar("di hola")
        assertEquals("b:free", (r as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `un 500 salta al siguiente modelo`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(500)
        assertEquals("b:free", (cliente().preguntar("x") as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `una respuesta sin contenido salta sin castigar`() = runTest {
        porModelo["a:free"] = MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}""")
        val c = cliente()
        assertEquals("b:free", (c.preguntar("x") as RespuestaDeIa.Texto).modelo)
        // No quedó en espera ni bajó: con b ya arriba por su éxito, a sigue estando en la lista.
        porModelo.remove("a:free")
        pedidosDeChat.clear()
        porModelo["b:free"] = MockResponse().setResponseCode(500)
        assertEquals("a:free", (c.preguntar("y") as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `prueba como maximo tres modelos`() = runTest {
        listOf("a:free", "b:free", "c:free").forEach { porModelo[it] = MockResponse().setResponseCode(500) }
        assertEquals(RespuestaDeIa.NoPude, cliente().preguntar("x"))
        assertEquals(listOf("a:free", "b:free", "c:free"), pedidosDeChat)
    }

    @Test fun `el catalogo se guarda seis horas`() = runTest {
        val c = cliente()
        c.preguntar("x")
        c.preguntar("y")
        assertEquals(1, pedidosDeCatalogo)
        ahora += 6 * 60 * 60 * 1000L + 1
        c.preguntar("z")
        assertEquals(2, pedidosDeCatalogo)
    }

    @Test fun `si el catalogo falla al renovarse sigue con el ultimo`() = runTest {
        val c = cliente()
        c.preguntar("x")
        ahora += 6 * 60 * 60 * 1000L + 1
        catalogo = MockResponse().setResponseCode(503)
        assertTrue(c.preguntar("y") is RespuestaDeIa.Texto)
    }

    @Test fun `sin catalogo no puede`() = runTest {
        catalogo = MockResponse().setResponseCode(503)
        assertEquals(RespuestaDeIa.NoPude, cliente().preguntar("x"))
    }

    @Test fun `el modelo que respondio bien se prueba primero la proxima vez`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(500)
        cliente().preguntar("x") // a falla, b responde
        ahora += 6 * 60 * 1000L // a ya salió de su espera
        porModelo.remove("a:free")
        pedidosDeChat.clear()
        cliente().preguntar("y")
        assertEquals("b:free", pedidosDeChat.first())
    }
}
