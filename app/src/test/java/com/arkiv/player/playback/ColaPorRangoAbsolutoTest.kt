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
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * El final del archivo se pide por rango ABSOLUTO, nunca por sufijo.
 *
 * Es lo más caro que se encontró midiendo. En el Fire TV, el 2026-08-13, sobre 31 peticiones al CDN
 * de magis:
 *
 * ```
 *   forma del rango          rechazos   respuestas OK
 *   bytes=-262144 (sufijo)      19            0
 *   bytes=N-      (absoluto)     0           12
 * ```
 *
 * Los diecinueve rechazos fueron del sufijo y ninguno del absoluto. Y no es que el tramo no esté:
 * en el mismo arranque, tras seis rechazos seguidos de `bytes=-262144` (dos conexiones en paralelo,
 * tres intentos cada una, 8,8 s tirados), el reproductor pidió ese mismo final por
 * `bytes=322515168-` y el CDN lo sirvió en 267 ms.
 *
 * Como libVLC no abre el video hasta tener el final del archivo, esos segundos se pagaban enteros
 * en spinner.
 */
class ColaPorRangoAbsolutoTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy

    private val TOTAL = 3 * 1024 * 1024
    private val archivo: ByteArray by lazy {
        ByteArray(TOTAL).also { out ->
            val p = ByteArray(188) { 0xFF.toByte() }.also { it[0] = 0x47; it[3] = 0x10 }
            for (i in 0 until out.size / 188) p.copyInto(out, i * 188)
        }
    }

    private val rangosPedidos = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        origen.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rango = request.getHeader("Range").orEmpty()
                rangosPedidos.add(rango)
                // Como el CDN real: el sufijo NO se contesta nunca. Si el proxy lo pide, se cuelga
                // hasta el plazo, que es exactamente lo que costaba los segundos.
                if (rango.startsWith("bytes=-")) {
                    return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
                val p = rango.removePrefix("bytes=").split("-")
                val desde = p[0].toIntOrNull() ?: 0
                val hasta = p.getOrNull(1)?.toIntOrNull() ?: (TOTAL - 1)
                val trozo = archivo.copyOfRange(desde, hasta + 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $desde-$hasta/$TOTAL")
                    .setHeader("Content-Length", trozo.size.toString())
                    .setBody(okio.Buffer().write(trozo))
                    .setBodyDelay(0, TimeUnit.MILLISECONDS)
            }
        }
        proxy = ArchiveCacheProxy(temp.newFolder("cache"))
    }

    @After
    fun tearDown() {
        runCatching { origen.shutdown() }
    }

    @Test
    fun `la cola no se pide por sufijo`() = runBlocking {
        proxy.precalentar(origen.url("/v.ts").toString(), esperarCola = true)

        val sufijos = rangosPedidos.filter { it.startsWith("bytes=-") }
        assertTrue(
            "se pidió el final por sufijo, que este CDN no contesta: $sufijos",
            sufijos.isEmpty(),
        )
    }

    @Test
    fun `la cola pide exactamente el ultimo tramo, por rango absoluto`() = runBlocking {
        val url = origen.url("/v.ts").toString()
        proxy.precalentar(url, esperarCola = true)

        val esperado = "bytes=${TOTAL - TsDurationProbe.PROBE_BYTES}-${TOTAL - 1}"
        assertTrue(
            "no se pidió el final por rango absoluto. Pedidos: $rangosPedidos",
            rangosPedidos.any { it == esperado },
        )
        // Y con eso la cola queda en memoria, que es para lo que se la bajaba.
        assertEquals(
            "el final no quedó guardado pese a que el origen lo sirvió",
            true, proxy.duracionDelPrecalentado(url) >= 0L,
        )
    }
}
