package com.arkiv.player.pocketbase

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El cuerpo multipart con el que se sube el JPEG de un frame. Se testea la CONSTRUCCIÓN del
 * cuerpo, no la llamada de red: es la parte donde se cometen los errores (un campo que no viaja,
 * un tipo de contenido mal puesto) y la única que se puede probar sin un servidor.
 */
class PocketBaseMultipartTest {

    private fun cuerpoComoTexto(b: okhttp3.MultipartBody): String {
        val buf = Buffer()
        b.writeTo(buf)
        return buf.readUtf8()
    }

    @Test
    fun `viajan todos los campos y el archivo`() {
        val body = PocketBaseMultipart.build(
            fields = mapOf("episodeId" to "ep-1", "updatedAt" to 123L),
            campoArchivo = "img",
            nombre = "frame.jpg",
            bytes = byteArrayOf(1, 2, 3),
        )
        val texto = cuerpoComoTexto(body)
        assertTrue(texto.contains("name=\"episodeId\""))
        assertTrue(texto.contains("ep-1"))
        assertTrue(texto.contains("name=\"updatedAt\""))
        assertTrue(texto.contains("123"))
        assertTrue(texto.contains("name=\"img\""))
        assertTrue(texto.contains("filename=\"frame.jpg\""))
    }

    @Test
    fun `el archivo va como image jpeg`() {
        val body = PocketBaseMultipart.build(
            fields = emptyMap(), campoArchivo = "img", nombre = "f.jpg", bytes = byteArrayOf(9),
        )
        assertTrue(cuerpoComoTexto(body).contains("image/jpeg"))
    }

    /** Un campo null se manda como cadena vacía: PocketBase no acepta la ausencia como "borrar". */
    @Test
    fun `un campo null viaja como cadena vacia`() {
        val body = PocketBaseMultipart.build(
            fields = mapOf("x" to null), campoArchivo = "img", nombre = "f.jpg", bytes = byteArrayOf(1),
        )
        assertTrue(cuerpoComoTexto(body).contains("name=\"x\""))
    }

    @Test
    fun `es multipart form-data`() {
        val body = PocketBaseMultipart.build(emptyMap(), "img", "f.jpg", byteArrayOf(1))
        assertEquals("multipart", body.contentType().type)
        assertEquals("form-data", body.contentType().subtype)
    }
}
