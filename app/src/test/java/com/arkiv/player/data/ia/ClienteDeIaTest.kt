package com.arkiv.player.data.ia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** Se cuenta hasta cero apenas el servidor recibe un pedido de chat (no de catálogo): sirve
     *  para esperar, con tiempo real, a que el pedido ya haya salido antes de cancelar. */
    private var latchPedidoDeChat = CountDownLatch(1)

    @Before fun arranca() {
        latchPedidoDeChat = CountDownLatch(1)
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
                latchPedidoDeChat.countDown()
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

    /**
     * Una conexión que se cae a mitad del cuerpo, después de un 200, es "error de red": 5 min de
     * espera ([Falla.Servidor]), no [Falla.Ilegible] (que no castiga). El cuerpo tiene que ser largo
     * para que `DISCONNECT_DURING_RESPONSE_BODY` (corta a la mitad de los bytes) deje al lector a
     * medio camino de un `Content-Length` que nunca se completa.
     */
    @Test fun `una conexion caida a mitad de la respuesta cuenta como falla de red`() = runTest {
        val cuerpoLargo = """{"choices":[{"message":{"content":"${"x".repeat(5000)}"}}]}"""
        porModelo["a:free"] = MockResponse().setBody(cuerpoLargo)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        val c = cliente()
        assertEquals("b:free", (c.preguntar("x") as RespuestaDeIa.Texto).modelo) // a falla, b responde
        // b ya quedó arriba por su éxito: lo hacemos fallar también para que el orden siga bajando.
        // Si a:free quedó castigado (5 min de espera), con el mismo `ahora` sigue afuera y el próximo
        // candidato es c:free; si no lo castigaron (el bug de Ilegible), a:free se reintenta y, como
        // ya no tiene el corte, responde bien.
        porModelo.remove("a:free")
        porModelo["b:free"] = MockResponse().setResponseCode(500)
        pedidosDeChat.clear()
        c.preguntar("y")
        assertFalse(pedidosDeChat.contains("a:free"))
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

    /**
     * Si se cancela mientras el primer modelo todavía no contestó (saltar de capítulo, salir del
     * reproductor), el pedido no puede seguir probando modelos, y el que estaba respondiendo no
     * puede quedar castigado por una cancelación que no dice nada de si el modelo sirve.
     */
    @Test fun `cancelar mientras el primer modelo tarda no prueba el segundo ni lo deja castigado`() = runTest {
        // `setHeadersDelay` (no `setBodyDelay`): demora ANTES de contestar, como un modelo que
        // tarda en generar la respuesta -el escenario real que preocupa a A3-, no una respuesta ya
        // lista cuyo cuerpo tarda en llegar. 2 s alcanza de sobra para cancelar mucho antes: cancelar
        // corta la llamada en milisegundos (ver el `time` de este test), no espera el resto del
        // delay -solo el `@After` sigue el resto de esos 2 s porque el hilo del delay del servidor
        // simulado no se entera de la cancelación del socket hasta que intenta escribir.
        porModelo["a:free"] = MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setResponseCode(500)
        val c = cliente()
        val job = launch(Dispatchers.Default) { c.preguntar("x") }
        withContext(Dispatchers.Default) { latchPedidoDeChat.await(2, TimeUnit.SECONDS) }
        job.cancelAndJoin()
        assertEquals(listOf("a:free"), pedidosDeChat) // nunca llegó a probar b:free

        // Si a:free hubiera quedado castigado (5 min de espera por Falla.Servidor), el siguiente
        // intento saltaría directo a b:free aunque a:free ya conteste bien y rápido.
        porModelo.remove("a:free") // ahora responde el default: JSON válido y sin demora
        pedidosDeChat.clear()
        assertEquals("a:free", (c.preguntar("y") as RespuestaDeIa.Texto).modelo)
    }
}
