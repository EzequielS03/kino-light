package com.arkiv.player.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * El camino DIRECTO del proxy, que es el que usa magis.
 *
 * El camino de siempre (archive.org) baja el archivo entero al caché y sirve de ahí mientras crece.
 * Con magis eso es veneno: son VOD de ~1 GB detrás de un CDN con token, la descarga se corta, y al
 * cortarse el proxy BORRA el archivo y la siguiente petición vuelve a empezar en el byte 0 — mientras
 * el que le sirve a VLC sigue leyendo por el offset viejo, donde ahora hay otra parte de la película.
 * VLC lo ve como un MPEG-TS con huecos ("TS discontinuity"), el tiempo salta de a minutos y el video
 * se muere.
 *
 * El directo hace lo mismo que la réplica en Python con la que se reprodujo bien el TS: reenviar cada
 * Range al origen tal cual y devolver el cuerpo. Sin caché, sin archivo creciendo, sin nada que
 * truncar. Archive queda intacto.
 */
class ArchiveCacheProxyResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origen: MockWebServer
    private lateinit var proxy: ArchiveCacheProxy
    private lateinit var cacheDir: java.io.File

    @Before
    fun setUp() {
        origen = MockWebServer().also { it.start() }
        cacheDir = temp.newFolder("cache")
        proxy = ArchiveCacheProxy(cacheDir)
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origen.shutdown()
    }

    private fun cuerpo(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun pedir(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            if (range != null) setRequestProperty("Range", range)
        }
        val code = c.responseCode
        val datos = runCatching { c.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
        c.disconnect()
        return code to datos
    }

    @Test
    fun `directo reenvia el Range tal cual y devuelve ese tramo`() {
        val datos = cuerpo(100_000)
        val desde = 40_000
        origen.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $desde-${datos.size - 1}/${datos.size}")
                .setHeader("Content-Length", (datos.size - desde).toString())
                .setBody(okio.Buffer().write(datos.copyOfRange(desde, datos.size))),
        )

        val url = proxy.proxyUrl(origen.url("/v.ts").toString(), mapOf("A" to "b"), directo = true)
        val (code, recibido) = pedir(url, "bytes=$desde-")

        assertEquals(206, code)
        assertArrayEquals(datos.copyOfRange(desde, datos.size), recibido)
        assertEquals("bytes=$desde-", origen.takeRequest().getHeader("Range"))
    }

    @Test
    fun `directo no deja nada en el cache`() {
        val datos = cuerpo(50_000)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )

        pedir(proxy.proxyUrl(origen.url("/v.ts").toString(), directo = true))

        assertEquals(
            "el directo no debe escribir archivos de cache",
            0,
            cacheDir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `directo manda los headers del CDN`() {
        val datos = cuerpo(20_000)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )

        pedir(
            proxy.proxyUrl(
                origen.url("/v.ts").toString(),
                mapOf("Content-Auth" to "TOKEN", "Content-License" to "LIC"),
                directo = true,
            ),
        )

        val r = origen.takeRequest()
        assertEquals("TOKEN", r.getHeader("Content-Auth"))
        assertEquals("LIC", r.getHeader("Content-License"))
    }

    @Test
    fun `archive sigue cacheando como siempre`() {
        val datos = cuerpo(50_000)
        origen.enqueue(
            MockResponse().setBody(okio.Buffer().write(datos))
                .setHeader("Content-Length", datos.size.toString()),
        )

        pedir(proxy.proxyUrl(origen.url("/v.ts").toString()))

        // La descarga corre en su propio hilo; se espera a que aparezca el archivo de caché.
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 4000 && (cacheDir.listFiles()?.size ?: 0) == 0) {
            Thread.sleep(50)
        }
        assertTrue(
            "el camino de archive debe seguir dejando el archivo en cache",
            (cacheDir.listFiles()?.size ?: 0) > 0,
        )
    }

    @Test
    fun `la url directa deja el origen al final para que se siga parseando bien`() {
        val u = proxy.proxyUrl("https://ejemplo/v.ts", mapOf("A" to "b"), directo = true)
        assertEquals(
            "https://ejemplo/v.ts",
            java.net.URLDecoder.decode(u.substringAfter("u=").substringBefore('&'), "UTF-8"),
        )
        assertEquals(0f, proxy.bufferedFraction(u), 0.0001f)
    }
}
