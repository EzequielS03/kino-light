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

/**
 * El precalentado baja las DOS puntas y de ahí sale la duración, sin pedir nada más.
 *
 * Antes eran cuatro viajes al CDN para arrancar: la sonda de duración pedía cabeza y cola por su
 * lado, y `precalentar` pedía otra vez esas mismas dos puntas — los 256 KB del final DUPLICADOS y
 * compitiendo entre sí. Medido el 2026-08-11 en el Fire TV sobre ocho arranques, `precalentado` era
 * la fase dominante en 6 de 8 (1443-6647 ms) y en dos corridas la sonda perdió su lotería y la
 * película salió sin duración.
 *
 * Los bytes que la sonda necesita son EXACTAMENTE los que el precalentado ya tiene en la mano.
 */
class PrecalentadoConDuracionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    /** Paquete TS de 188 bytes, con PCR opcional (base a 90 kHz) en el campo de adaptación. */
    private fun paquete(pid: Int, base90k: Long?): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (base90k == null) { p[3] = 0x10; return p }   // solo payload, sin PCR
        p[3] = 0x20                                       // solo campo de adaptación
        p[4] = 183.toByte()
        p[5] = 0x10                                       // flag de PCR presente
        p[6] = ((base90k shr 25) and 0xFF).toByte()
        p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte()
        p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = ((base90k and 1L) shl 7).toByte()
        p[11] = 0
        return p
    }

    /**
     * Un TS de ~3 MB con PCR SOLO en el primer paquete y en el último: así la duración esperada no
     * depende de en qué byte exacto cae el corte de la cola.
     */
    private val PAQUETES = 15_958
    private val PCR_FINAL = 900_000L                      // 10 s a 90 kHz
    private val archivo: ByteArray by lazy {
        val out = ByteArray(PAQUETES * 188)
        for (i in 0 until PAQUETES) {
            val base = when (i) {
                0 -> 0L
                PAQUETES - 1 -> PCR_FINAL
                else -> null
            }
            paquete(0x100, base).copyInto(out, i * 188)
        }
        out
    }

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        // Por Range, no por orden de llegada: cabeza y cola se piden EN PARALELO, así que con
        // `enqueue` (FIFO) cada una podría llevarse la respuesta de la otra.
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                val total = archivo.size
                val (desde, hasta) = when {
                    rango.startsWith("bytes=-") ->
                        (total - rango.removePrefix("bytes=-").toInt()) to (total - 1)
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
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        origen.shutdown()
    }

    @Test
    fun `la duracion sale del precalentado sin pedir nada extra`() = runBlocking {
        val url = origen.url("/v.ts").toString()

        assertTrue("el precalentado tenía que funcionar", proxy.precalentar(url))

        assertEquals(10_000L, proxy.duracionDelPrecalentado(url))
    }

    @Test
    fun `precalentar hace exactamente dos viajes al origen`() = runBlocking {
        // Uno por punta. Tres significaría que alguien volvió a pedir lo que ya estaba en memoria,
        // que es justo lo que esto vino a borrar.
        proxy.precalentar(origen.url("/v.ts").toString())

        assertEquals(2, origen.requestCount)
    }

    @Test
    fun `sin precalentado no hay duracion que dar`() = runBlocking {
        // No es un error: quien llama tiene que poder distinguirlo para caer a la sonda por red.
        assertEquals(0L, proxy.duracionDelPrecalentado(origen.url("/otra.ts").toString()))
    }
}
