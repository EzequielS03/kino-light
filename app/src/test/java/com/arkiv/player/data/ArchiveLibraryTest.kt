package com.arkiv.player.data

import com.arkiv.player.data.catalog.mirror.MirrorApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Biblioteca propia: los capítulos que subimos quedan en archive.org con identificador y título
 * hasheados (`f75163f026d99259e37c_12697`, título `..._s01e01`), así que `title:(Dragon Ball GT)`
 * no los encuentra por más que estén públicos. El mirror los indexa por `tmdb_id` y devuelve el
 * identificador y los nombres REALES de archive.org: de ahí en adelante se reproducen y descargan
 * por el mismo camino que cualquier ítem público, sin que el mirror sirva un solo byte.
 */
class ArchiveLibraryTest {

    /** Payload real de `GET /library/metadata/tmdb-12697` (recortado a 3 de los 27 capítulos). */
    private fun mirrorPayload(): String = """
        {"metadata":{"identifier":"f75163f026d99259e37c_12697","title":"Dragon Ball GT",
                     "description":"Goku vuelve a ser niño."},
         "files":[
           {"format":"MPEG4","length":"1420.5","name":"f75163f026d99259e37c_12697_s01e01.mp4","size":"350000000","source":"original"},
           {"format":"MPEG4","length":"1418.0","name":"f75163f026d99259e37c_12697_s01e02.mp4","size":"351000000","source":"original"},
           {"format":"MPEG4","length":"1419.0","name":"f75163f026d99259e37c_12697_s01e03.mp4","size":"352000000","source":"original"}
         ]}
    """.trimIndent()

    private fun clientFor(server: MockWebServer) =
        MirrorApiClient(baseUrl = { server.url("/").toString() })

    @Test
    fun `devuelve el identificador de archive punto org, no el nuestro`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(mirrorPayload()))
        server.start()
        try {
            val result = runBlocking { clientFor(server).libraryItem(12697) }

            // Lo decisivo: con "tmdb-12697" la reproducción pediría bytes que archive.org no
            // tiene. Con el identificador real anda por el camino de cualquier ítem público.
            assertEquals("f75163f026d99259e37c_12697", result?.identifier)
            assertEquals("/library/metadata/tmdb-12697", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `el titulo sale del mirror, no el hash con el que se subio`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(mirrorPayload()))
        server.start()
        try {
            val result = runBlocking { clientFor(server).libraryItem(12697) }
            assertEquals("Dragon Ball GT", result?.title)
            assertEquals(3, result?.episodeCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `queda marcado como de la biblioteca para poder distinguirlo en pantalla`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(mirrorPayload()))
        server.start()
        try {
            assertTrue(runBlocking { clientFor(server).libraryItem(12697) }!!.fromLibrary)
            // El identificador es indistinguible de uno público: sin el flag no habría cómo saberlo.
            assertFalse(ArchiveSearchResult("f75163f026d99259e37c_12697", "x", "").fromLibrary)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sin nada subido para ese titulo devuelve null en vez de romper la busqueda`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            assertNull(runBlocking { clientFor(server).libraryItem(999999) })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `un item sin archivos no se ofrece como fuente`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"metadata":{"identifier":"abc","title":"X"},"files":[]}"""),
        )
        server.start()
        try {
            assertNull(runBlocking { clientFor(server).libraryItem(12697) })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `si el mirror no responde devuelve null y no propaga la excepcion`() {
        val server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        server.shutdown()   // nadie escuchando: la llamada falla por red
        assertNull(runBlocking { MirrorApiClient(baseUrl = { base }).libraryItem(12697) })
    }
}
