package com.arkiv.player.data.local

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpRangeDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpRangeDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = HttpRangeDownloader(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    /**
     * Deja un parcial "legítimo": los bytes + la marca de origen que escribe el propio descargador.
     * Sin la marca el parcial se descarta (ver `un parcial sin marca de origen no se reanuda`), que
     * es justamente lo que protege contra reanudar un archivo contra otro.
     */
    private fun writePart(target: File, bytes: String, origin: String) {
        LocalFilePaths.partOf(target).writeText(bytes)
        LocalFilePaths.originOf(target).writeText(origin)
    }

    @Test
    fun `sin parcial previo no manda Range`() {
        assertNull(RangeMath.rangeHeaderFor(0))
    }

    @Test
    fun `con parcial previo pide desde donde quedo`() {
        assertEquals("bytes=1024-", RangeMath.rangeHeaderFor(1024))
    }

    @Test
    fun `el total es lo que falta mas lo ya escrito`() {
        assertEquals(5000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 1000))
        assertEquals(4000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 0))
    }

    @Test
    fun `descarga completa y renombra el parcial`() = runBlocking {
        val body = "0123456789".repeat(100)   // 1000 bytes
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8(body)))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals(1000, target.length())
        assertEquals(body, target.readText())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `reanuda desde el parcial existente`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val url = server.url("/f").toString()
        writePart(target, "AAAA", origin = url)                   // 4 bytes ya bajados, mismo origen
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(url, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        // Ya no hay parcial que identificar: la marca se limpia con el renombre.
        assertFalse(LocalFilePaths.originOf(target).exists())
    }

    /**
     * El escenario del bug: se baja la variante `derivative` de un episodio de archive y falla al
     * 40%; el usuario cambia la calidad a ORIGINAL y reencola. `variantFor()` elige otra URL y otro
     * tamaño, se pedía `Range: bytes=<40% del derivative>-` sobre el original, llegaba un 206 y se
     * appendeaba la cola de un archivo al prefijo del otro. La verificación `written < total` no lo
     * detectaba (las cuentas cerraban), se renombraba y quedaba marcado "Listo" siendo basura.
     */
    @Test
    fun `no reanuda un parcial de otro origen y baja el archivo entero`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val derivative = server.url("/peli_derivative.mp4").toString()
        val original = server.url("/peli_original.mp4").toString()
        writePart(target, "DERIVATIVE-40%", origin = derivative)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ORIGINAL-ENTERO"))

        val result = downloader.download(original, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        // Ni rastro del prefijo de la otra variante.
        assertEquals("ORIGINAL-ENTERO", target.readText())
        // Y ni siquiera se pidió reanudar: el parcial se descartó ANTES de armar el request.
        assertNull(server.takeRequest().getHeader("Range"))
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `un parcial sin marca de origen no se reanuda`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        // Parcial que dejó una versión anterior de la app: no se puede afirmar de qué URL vino.
        LocalFilePaths.partOf(target).writeText("VIEJO")
        server.enqueue(MockResponse().setResponseCode(200).setBody("NUEVO-COMPLETO"))

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("NUEVO-COMPLETO", target.readText())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `el mismo resumeKey reanuda aunque cambie la URL`() = runBlocking {
        // La NUC se sirve por LAN o por túnel según dónde esté el celular: la URL cambia pero el
        // contenido es el mismo, así que con la URL como clave se tiraría un parcial válido.
        val target = File(tmp.root, "peli.mp4")
        writePart(target, "AAAA", origin = "nuc:item:42")
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(
            server.url("/otra-base/stream/42").toString(), target, emptyMap(), resumeKey = "nuc:item:42",
        ) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `si el server no soporta Range y responde 200 descarta el parcial y no duplica`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        val url = server.url("/f").toString()
        writePart(target, "AAAA", origin = url)                    // 4 bytes ya bajados, mismo origen
        server.enqueue(MockResponse().setResponseCode(200).setBody("XXXXYYYY"))   // archivo completo, ignora el Range

        val result = downloader.download(url, target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("XXXXYYYY", target.readText())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `manda los headers que le pasan`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        val target = File(tmp.root, "peli.mp4")

        downloader.download(
            server.url("/f").toString(), target,
            mapOf("Referer" to "https://origen.example/"),
        ) { _, _ -> }

        assertEquals("https://origen.example/", server.takeRequest().getHeader("Referer"))
    }

    @Test
    fun `un 403 falla y no deja archivo final`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(target.exists())
        // Tipada, para que la política de reintentos pueda distinguir un 403 (definitivo) de un 503.
        val error = result.exceptionOrNull()
        assertTrue(error is HttpStatusException)
        assertEquals(403, (error as HttpStatusException).code)
    }

    @Test
    fun `informa progreso creciente`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8("x".repeat(200_000))))
        val target = File(tmp.root, "peli.mp4")
        val seen = mutableListOf<Long>()

        downloader.download(server.url("/f").toString(), target, emptyMap()) { done, _ -> seen.add(done) }

        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen)
        assertEquals(200_000L, seen.last())
    }
}
