package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los dos pasos para leer un archivo `protected` de PocketBase (el campo `img` de
 * `episode_frames`): pedir un file-token con la sesión, y usarlo como QUERY PARAM de la URL del
 * archivo.
 *
 * Es la lógica de la que dependen todas las miniaturas que llegan de otro dispositivo, y la más
 * fácil de romper sin darse cuenta: mandar el file-token como header `Authorization` "parece"
 * razonable y devuelve 403 siempre. De ahí que el test mire dónde viaja el token, no solo que la
 * llamada no explote.
 */
class PocketBaseClientArchivosTest {

    private lateinit var server: MockWebServer

    @Before fun abrir() {
        server = MockWebServer()
        server.start()
    }

    @After fun cerrar() = server.shutdown()

    private fun cliente() = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    @Test fun `fileToken pide el token con la sesion del dispositivo`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"ftok-1"}"""))

        val token = cliente().fileToken("dtok")

        assertEquals("ftok-1", token)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/files/token", req.path)
        assertEquals("la sesión SÍ va como header en este paso", "dtok", req.getHeader("Authorization"))
    }

    @Test fun `downloadFile manda el file-token como query param y devuelve los bytes`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)).setHeader("Content-Type", "image/jpeg"))
        val url = "${server.url("/")}api/files/col1/rec1/frame.jpg"

        val bytes = cliente().downloadFile(url, "ftok-1")

        assertTrue("el cuerpo tiene que volver como bytes crudos", jpeg.contentEquals(bytes))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/files/col1/rec1/frame.jpg?token=ftok-1", req.path)
        assertNull(
            "el file-token NO va como header: PocketBase no sirve un archivo protegido así",
            req.getHeader("Authorization"),
        )
    }

    /** Una URL que ya trae query no pierde lo suyo al sumarle el token. */
    @Test fun `downloadFile conserva los parametros que ya trae la url`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1))))

        cliente().downloadFile("${server.url("/")}api/files/col1/rec1/frame.jpg?thumb=100x100", "ftok-1")

        assertEquals("/api/files/col1/rec1/frame.jpg?thumb=100x100&token=ftok-1", server.takeRequest().path)
    }

    @Test fun `un archivo que ya no esta lanza en vez de devolver bytes vacios`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val e = runCatching {
            cliente().downloadFile("${server.url("/")}api/files/col1/rec1/frame.jpg", "ftok-1")
        }.exceptionOrNull()

        assertTrue("tiene que ser un error de PocketBase, no bytes vacíos", e is PocketBaseException)
        assertEquals(404, (e as PocketBaseException).code)
    }
}
