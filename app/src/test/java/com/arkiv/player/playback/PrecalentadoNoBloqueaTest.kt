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

/**
 * El plazo para esperar la cola tiene que ser un plazo de verdad.
 *
 * Medido en el Fire TV el 2026-08-13, reproduciendo un capítulo de serie de magis: el log decía
 * `la cola no llegó en 2000ms → se reproduce sin duración` y aun así la fase de precalentado tardó
 * **9218 ms**. El plazo se cumplía y nadie seguía adelante.
 *
 * La razón es de corrutinas, no de red: la cola se lanzaba con `async` DENTRO del
 * `withContext(Dispatchers.IO)` de `precalentar`. `withTimeoutOrNull { cola.await() }` cancela la
 * ESPERA, no el trabajo — y un `withContext` no vuelve hasta que todos sus hijos terminan. O sea que
 * al vencer el plazo se imprimía el aviso, se salía del bloque… y ahí el `withContext` se quedaba
 * quieto esperando a la misma cola de la que acababa de desentenderse.
 *
 * El síntoma sale caro dos veces: primero porque son segundos de spinner, y segundo porque son
 * segundos gastados en un dato que libVLC calcula solo (medido en el mismo aparato: `dur=3831168ms`
 * contra los 3831000 ms de la sonda).
 */
class PrecalentadoNoBloqueaTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Cuánto tarda el origen en soltar la COLA. Más que el plazo de espera, a propósito. */
    private val COLA_LENTA_MS = 6_000L

    private fun paqueteTs(): ByteArray = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }

    private val archivo: ByteArray by lazy {
        ByteArray(3 * 1024 * 1024).also { out ->
            val p = paqueteTs()
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                val total = archivo.size
                val esCola = rango.startsWith("bytes=-")
                val (desde, hasta) = when {
                    esCola -> (total - rango.removePrefix("bytes=-").toInt()) to (total - 1)
                    rango.startsWith("bytes=") -> {
                        val p = rango.removePrefix("bytes=").split("-")
                        p[0].toInt() to (p.getOrNull(1)?.toIntOrNull() ?: (total - 1))
                    }
                    else -> 0 to (total - 1)
                }
                val trozo = archivo.copyOfRange(desde, hasta + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $desde-$hasta/$total")
                    .setHeader("Content-Length", trozo.size.toString())
                    .setBody(okio.Buffer().write(trozo))
                    // La CABEZA llega al toque; la COLA es la que se hace esperar.
                    .apply { if (esCola) setHeadersDelay(COLA_LENTA_MS, TimeUnit.MILLISECONDS) }
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        // `runCatching` y no un shutdown a secas: cuando el test termina, la cola TODAVÍA está
        // bajando —es exactamente lo que estos dos casos vinieron a comprobar— y MockWebServer se
        // queja de no poder cerrar la cola de peticiones. Que ese hilo sobreviva al test es la
        // conducta buscada, no una fuga: es lo que deja la cola en memoria para los sondeos de EOF
        // de libVLC sin frenar el arranque. Es un servidor por test, así que no se pisan.
        runCatching { origen.shutdown() }
    }

    @Test
    fun `con una cola lenta el precalentado vuelve igual, en cuanto la cabeza sirve`() = runBlocking {
        val t0 = System.currentTimeMillis()
        proxy.precalentar(origen.url("/v.ts").toString(), esperarCola = false)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "esperarCola=false no puede tardar lo que tarda la cola; tardó ${ms}ms de ${COLA_LENTA_MS}ms",
            ms < COLA_LENTA_MS / 2,
        )
    }

    @Test
    fun `esperar la cola tiene un plazo y el plazo se cumple`() = runBlocking {
        // Con `esperarCola=true` se le da a la cola su oportunidad, pero ACOTADA: pasado el plazo se
        // reproduce sin duración. Que el aviso salga y la función siga esperando es lo mismo que no
        // tener plazo.
        val t0 = System.currentTimeMillis()
        proxy.precalentar(origen.url("/v.ts").toString(), esperarCola = true)
        val ms = System.currentTimeMillis() - t0

        assertTrue(
            "el plazo de espera de la cola no se respetó: tardó ${ms}ms, la cola tardaba ${COLA_LENTA_MS}ms",
            ms < COLA_LENTA_MS - 1_000,
        )
    }
}
