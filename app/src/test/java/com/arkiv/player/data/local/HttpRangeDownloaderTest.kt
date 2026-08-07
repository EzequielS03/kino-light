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
        LocalFilePaths.partOf(target).writeText("AAAA")            // 4 bytes ya bajados
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `si el server no soporta Range y responde 200 descarta el parcial y no duplica`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        LocalFilePaths.partOf(target).writeText("AAAA")            // 4 bytes ya bajados
        server.enqueue(MockResponse().setResponseCode(200).setBody("XXXXYYYY"))   // archivo completo, ignora el Range

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

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
