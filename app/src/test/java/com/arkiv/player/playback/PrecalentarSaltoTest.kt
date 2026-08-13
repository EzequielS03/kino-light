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
 * Reanudar no puede empezar bajando el principio de la película.
 *
 * Medido en el Fire TV el 2026-08-13 reanudando una película en 13:29: libVLC abre SIEMPRE en el
 * byte 0 y recién después busca el minuto guardado. En el medio se bajaron 2,5 MB del principio que
 * después se tiraron, con la imagen congelada en `pos=0` durante 3,4 s.
 *
 * Precalentar la zona de destino es la pieza que le faltaba a nuestra copia del diseño de la app
 * original, que le avisa a su motor de descarga a dónde va ANTES de saltar.
 *
 * Los offsets de estos tests son los medidos de verdad, no inventados: es lo único que respalda que
 * estimar el byte por tasa constante sirva.
 */
class PrecalentarSaltoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Como la película medida: ~1,3 GB. Se usa un archivo chico y se escala con la fracción. */
    private val TOTAL = 40 * 1024 * 1024
    private val archivo: ByteArray by lazy { ByteArray(TOTAL) { (it % 251).toByte() } }

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
                    val l = ins.read(buf, n, leer - n); if (l < 0) break; n += l
                }
            }
        }
        conn.disconnect()
        return buf.copyOf(n)
    }

    /** Espera a que el precalentado del salto deje su ventana lista. */
    private fun esperarVentana() {
        Thread.sleep(2_500)
    }

    @Test
    fun `el salto precalentado contesta el destino sin tocar la red`() = runBlocking {
        proxy.start()
        val url = origen.url("/v.ts").toString()
        proxy.precalentar(url, esperarCola = true)   // deja el tamaño del archivo a mano
        val fraccion = 0.5f
        proxy.precalentarSalto(url, emptyMap(), fraccion)
        esperarVentana()

        val proxyUrl = proxy.proxyUrl(url, emptyMap(), directo = true)
        pedidosAlOrigen.set(0)

        // El destino exacto y los desvíos medidos de verdad: -2,7 MB y +3,7 MB.
        val destino = (TOTAL * fraccion).toLong()
        for (desvio in listOf(0L, -2_700_000L, 3_700_000L)) {
            val bytes = sondear(proxyUrl, destino + desvio, 16 * 1024)
            assertTrue("el sondeo en $desvio vino vacío", bytes.isNotEmpty())
            assertEquals(
                "entregó bytes de otro lado del archivo",
                ((destino + desvio) % 251).toByte(), bytes[0],
            )
        }
        assertEquals(
            "el destino del salto todavía se pide por red: el precalentado no sirvió",
            0, pedidosAlOrigen.get(),
        )
    }

    @Test
    fun `sin fraccion valida no precalienta nada`() = runBlocking {
        // Reanudar en 0 (o una fracción absurda) no es reanudar: pedirle bytes al CDN ahí sería
        // gastar una conexión por nada.
        proxy.start()
        val url = origen.url("/w.ts").toString()
        proxy.precalentar(url, esperarCola = true)
        pedidosAlOrigen.set(0)

        proxy.precalentarSalto(url, emptyMap(), 0f)
        proxy.precalentarSalto(url, emptyMap(), 1f)
        proxy.precalentarSalto(url, emptyMap(), -0.3f)
        esperarVentana()

        assertEquals("se pidió algo al origen sin tener a dónde saltar", 0, pedidosAlOrigen.get())
    }
}
