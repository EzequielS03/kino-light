package com.arkiv.player.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cuando la primera conexión a la cola se cuelga, la segunda salva el arranque.
 *
 * Medido en el Fire TV el 2026-08-13 con un capítulo nuevo: el CDN no contestó la cola dos veces
 * seguidas —sin decir nada, que es como falla este CDN— y cada silencio cuesta los 3 s de plazo del
 * perfil MAGIS. Como libVLC necesita el final del archivo para abrir, se quedó esperando 7,3 s antes
 * de dar la primera imagen.
 *
 * Reintentar EN SERIE no arregla eso: hay que esperar a que el intento anterior se dé por vencido
 * para recién ahí volver a tirar los dados. Por eso el segundo pedido sale en PARALELO.
 */
class ColaDuplicadaTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Lo que tarda el PRIMER pedido de cola. Más que el plazo del duplicado, a propósito. */
    private val PRIMERA_COLA_MS = 5_000L

    private val TOTAL = 3 * 1024 * 1024
    private val archivo: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    private val colasPedidas = AtomicInteger(0)

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                val esCola = rango.startsWith("bytes=-")
                val (desde, hasta) = when {
                    esCola -> (TOTAL - rango.removePrefix("bytes=-").toInt()) to (TOTAL - 1)
                    rango.startsWith("bytes=") -> {
                        val p = rango.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1))
                    }
                    else -> 0 to (TOTAL - 1)
                }
                val trozo = archivo.copyOfRange(desde, hasta + 1)
                val r = MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $desde-$hasta/$TOTAL")
                    .setHeader("Content-Length", trozo.size.toString())
                    .setBody(okio.Buffer().write(trozo))
                // SOLO la primera cola se cuelga; la segunda contesta al toque. Es el caso medido:
                // el mismo rango que no contestaba por una conexión, contestó por otra.
                if (esCola && colasPedidas.incrementAndGet() == 1) {
                    r.setHeadersDelay(PRIMERA_COLA_MS, TimeUnit.MILLISECONDS)
                }
                return r
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { origen.shutdown() }
    }

    @Test
    fun `si la primera cola se cuelga, el duplicado la trae sin esperar al plazo entero`() = runBlocking {
        val url = origen.url("/v.ts").toString()
        val t0 = System.currentTimeMillis()
        // `esperarCola=true` para poder medir acá cuándo estuvo la cola. En producción el arranque no
        // la espera (ver PrecalentadoNoBloqueaTest); quien la espera es libVLC, y es a él a quien
        // este duplicado le ahorra los segundos.
        proxy.precalentar(url, esperarCola = true)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "se esperó a la primera conexión colgada: ${ms}ms de ${PRIMERA_COLA_MS}ms",
            ms < PRIMERA_COLA_MS - 1_500,
        )
        assertTrue("el duplicado tenía que haber salido", colasPedidas.get() >= 2)
        assertTrue("la cola tenía que quedar en memoria", proxy.duracionDelPrecalentado(url) >= 0L)
    }

    @Test
    fun `cuando la primera contesta a tiempo, no se pide dos veces`() = runBlocking {
        // El duplicado no puede ser gratis para el CDN en el caso normal: sale tarde justamente para
        // que, cuando la primera anda bien, este hilo se despierte y no toque la red.
        colasPedidas.set(1) // el dispatcher solo demora la nº1: así la próxima ya contesta rápido
        val url = origen.url("/rapida.ts").toString()
        proxy.precalentar(url, esperarCola = true)

        assertTrue(
            "se pidió la cola de más: ${colasPedidas.get() - 1} pedidos",
            colasPedidas.get() - 1 == 1,
        )
    }
}
