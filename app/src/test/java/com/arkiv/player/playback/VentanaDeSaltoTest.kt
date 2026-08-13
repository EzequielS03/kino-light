package com.arkiv.player.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reanudar no puede costar diez conexiones al CDN.
 *
 * Medido en el Fire TV el 2026-08-13 reanudando una película en 13:26: libVLC no salta de una,
 * **bisecta**. Pidió diez rangos seguidos —`bytes=62148288-`, `63899508-`, `63533848-`,
 * `63443420-`…— leyendo unos cientos de KB de cada uno y cortando la conexión enseguida, cada uno
 * con su propia conexión a ~300 ms. Y los diez caían adentro de 1,8 MB del archivo.
 *
 * Acá se reproduce esa forma de leer —pedir, leer poco, cortar, volver a pedir cerca— y se comprueba
 * que la segunda vuelta ya no toque la red.
 */
class VentanaDeSaltoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    private val TOTAL = 32 * 1024 * 1024
    private val archivo: ByteArray by lazy {
        // Contenido no uniforme: si el proxy sirviera bytes de OTRO lado del archivo, un relleno
        // constante lo dejaría pasar. Cada byte depende de su posición.
        ByteArray(TOTAL) { (it % 251).toByte() }
    }

    private val pedidosAlOrigen = AtomicInteger(0)

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                val esSufijo = rango.startsWith("bytes=-")
                if (!esSufijo) pedidosAlOrigen.incrementAndGet()
                val (desde, hasta) = when {
                    esSufijo -> (TOTAL - rango.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    rango.startsWith("bytes=") -> {
                        val p = rango.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                val trozo = archivo.copyOfRange(desde, hasta + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $desde-$hasta/$TOTAL")
                    .setHeader("Content-Length", trozo.size.toString())
                    .setBody(okio.Buffer().write(trozo))
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { origen.shutdown() }
    }

    /** Pide un rango, lee [leer] bytes y CORTA — igual que libVLC bisecando. */
    private fun sondear(proxyUrl: String, desde: Long, leer: Int): ByteArray {
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$desde-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val buf = ByteArray(leer)
        var n = 0
        runCatching {
            conn.inputStream.use { ins ->
                while (n < leer) {
                    val l = ins.read(buf, n, leer - n)
                    if (l < 0) break
                    n += l
                }
            }
        }
        conn.disconnect()
        return buf.copyOf(n)
    }

    @Test
    fun `los saltos cercanos al primero se contestan de memoria`() = runBlocking {
        proxy.start()
        val url = origen.url("/v.ts").toString()
        // El total lo aprende el proxy del precalentado de la cola, y sin él no puede armar el
        // Content-Range de una respuesta desde memoria.
        proxy.precalentar(url, esperarCola = true)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), directo = true)
        pedidosAlOrigen.set(0)

        val base = 20L * 1024 * 1024
        // Primer sondeo: este SÍ va al origen, y de paso deja la ventana.
        sondear(proxyUrl, base, 64 * 1024)
        val trasElPrimero = pedidosAlOrigen.get()

        // La ventana se llena en el mismo hilo que atendió el sondeo; hay que darle su momento.
        Thread.sleep(2_000)

        // Los siguientes, cerca del primero, como en la bisección medida.
        val leidos = listOf(1_500_000L, 900_000L, 400_000L, 120_000L).map { d ->
            d to sondear(proxyUrl, base + d, 32 * 1024)
        }

        assertEquals(
            "el primer sondeo tenía que ir al origen una sola vez", 1, trasElPrimero,
        )
        assertEquals(
            "los sondeos cercanos volvieron a la red en vez de salir de la ventana",
            trasElPrimero, pedidosAlOrigen.get(),
        )
        // Y lo que entregó tiene que ser el tramo CORRECTO, no bytes de cualquier parte.
        for ((d, bytes) in leidos) {
            assertTrue("el sondeo en +$d vino vacío", bytes.isNotEmpty())
            val esperado = ((base + d) % 251).toByte()
            assertEquals("el sondeo en +$d entregó bytes de otro lado", esperado, bytes[0])
        }
    }

    @Test
    fun `la lectura principal no gasta ventana`() = runBlocking {
        // `bytes=0-` es la lectura secuencial y esa NUNCA la corta el reproductor: guardarle una
        // ventana serían 4 MB de RAM por reproducción a cambio de nada.
        proxy.start()
        val url = origen.url("/w.ts").toString()
        proxy.precalentar(url, esperarCola = true)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), directo = true)

        sondear(proxyUrl, 0L, 64 * 1024)
        Thread.sleep(500)

        // Si hubiera guardado ventana desde 0, este pedido saldría de memoria y no tocaría el origen.
        pedidosAlOrigen.set(0)
        sondear(proxyUrl, 100_000L, 16 * 1024)
        assertEquals("la lectura principal dejó una ventana que no le sirve a nadie", 1, pedidosAlOrigen.get())
    }
}
