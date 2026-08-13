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
import java.util.concurrent.TimeUnit

/**
 * El final del archivo se pide UNA sola vez, aunque lo quieran dos a la vez.
 *
 * Desde que el arranque dejó de esperar a la cola, el precalentado y el sondeo de EOF de libVLC
 * dejaron de ir en fila y pasaron a ir a la vez — dos conexiones pidiendo los MISMOS bytes. Medido
 * en el Fire TV el 2026-08-13, con el CDN en una mala racha, se rechazaron una a la otra durante
 * 7 s y VLC tardó 7719 ms en abrir esperando su propia cola.
 *
 * Acá se comprueba lo único que impide que eso vuelva: que el segundo en llegar ESPERE al primero
 * en vez de abrir su propia conexión.
 */
class ColaEnVueloTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Lo que tarda el origen en soltar la cola: suficiente para que el sondeo llegue en el medio. */
    private val COLA_MS = 2_000L

    private val TOTAL = 3 * 1024 * 1024
    private val archivo: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    /** Peticiones de COLA que llegaron al origen (sufijo o rango cerca del final). */
    private val colasPedidas = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                val esSufijo = rango.startsWith("bytes=-")
                val (desde, hasta) = when {
                    esSufijo -> (TOTAL - rango.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    rango.startsWith("bytes=") -> {
                        val p = rango.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                if (desde > TOTAL / 2) colasPedidas.incrementAndGet()
                val trozo = archivo.copyOfRange(desde, hasta + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $desde-$hasta/$TOTAL")
                    .setHeader("Content-Length", trozo.size.toString())
                    .setBody(okio.Buffer().write(trozo))
                    .apply { if (esSufijo) setHeadersDelay(COLA_MS, TimeUnit.MILLISECONDS) }
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { proxy.stop() }
        runCatching { origen.shutdown() }
    }

    @Test
    fun `el sondeo del final espera a la cola que ya se esta bajando, no abre otra conexion`() = runBlocking {
        proxy.start()
        val url = origen.url("/v.ts").toString()
        // Como en el arranque real: se precalienta sin esperar la cola…
        proxy.precalentar(url, esperarCola = false)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), directo = true)

        // …y enseguida llega el sondeo de EOF de libVLC, con la cola todavía en vuelo.
        val pedido = TOTAL - 40_000L
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$pedido-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val cuerpo = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        assertEquals("tiene que entregar el tramo entero", (TOTAL - pedido).toInt(), cuerpo.size)
        assertEquals(
            "el final se pidió más de una vez: el sondeo abrió su propia conexión en vez de esperar",
            1, colasPedidas.get(),
        )
    }

    @Test
    fun `la cabeza no espera a la cola`() = runBlocking {
        proxy.start()
        val url = origen.url("/v.ts").toString()
        proxy.precalentar(url, esperarCola = false)
        val proxyUrl = proxy.proxyUrl(url, emptyMap(), directo = true)

        // `bytes=0-` es la PRIMERA lectura de libVLC y la contesta el arranque caliente. Si esperara
        // a la cola, el arreglo de la carrera se comería el arranque que vino a proteger.
        val t0 = System.currentTimeMillis()
        val conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=0-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.inputStream.use { it.read(ByteArray(64 * 1024)) }
        conn.disconnect()
        val ms = System.currentTimeMillis() - t0

        assertTrue("la cabeza tardó ${ms}ms: se quedó esperando la cola", ms < COLA_MS)
    }
}
