package com.arkiv.player.data.ditu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DituRefTest {

    @Test fun `ida y vuelta de una pelicula`() {
        val ref = DituRef(contentId = "12345", contentType = "VOD")
        assertEquals("ditu1:VOD:12345", ref.codificar())
        assertEquals(ref, DituRef.decodificar("ditu1:VOD:12345"))
    }

    @Test fun `ida y vuelta de una serie`() {
        val ref = DituRef(contentId = "998", contentType = "BUNDLE")
        assertEquals("ditu1:BUNDLE:998", ref.codificar())
        assertEquals(ref, DituRef.decodificar(ref.codificar()))
    }

    /** El contentId va ÚLTIMO para que no importe si algún día trae un `:` adentro. */
    @Test fun `un contentId con dos puntos sobrevive`() {
        val ref = DituRef(contentId = "a:b:c", contentType = "VOD")
        assertEquals(ref, DituRef.decodificar(ref.codificar()))
    }

    @Test fun `solo BUNDLE y GROUP_OF_BUNDLES son series`() {
        assertTrue(DituRef("1", "BUNDLE").esSerie)
        assertTrue(DituRef("1", "GROUP_OF_BUNDLES").esSerie)
        assertFalse(DituRef("1", "VOD").esSerie)
    }

    @Test fun `un ref de otra fuente no se entiende`() {
        assertNull(DituRef.decodificar("magis1:movie:0:C42"))
        assertNull(DituRef.decodificar(""))
        assertNull(DituRef.decodificar("ditu1:VOD"))
        assertNull(DituRef.decodificar("ditu1:VOD:"))
    }

    /**
     * Un ref viejo del gateway (`base64url(json).hmac`) se lee igual: es opaco por contrato, no
     * por criptografía. La firma no se valida —no hay con qué, y lo que sale de acá no autoriza
     * nada, solo dice qué pedirle a Caracol— y el vencimiento se ignora a propósito.
     */
    @Test fun `un ref viejo del gateway se entiende`() {
        val json = """{"s":"ditu","p":{"content_id":"777","content_type":"BUNDLE"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val leido = DituRef.decodificar("$datos.firmaquenadievalida")
        assertEquals(DituRef("777", "BUNDLE"), leido)
    }

    /** El ref viejo de OTRA fuente no es nuestro, aunque tenga la misma forma. */
    @Test fun `un ref viejo de magis no lo reclama ditu`() {
        val json = """{"s":"magis","p":{"content_id":"C42","program_type":"movie"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertNull(DituRef.decodificar("$datos.firma"))
    }

    /** Sin `content_type` el gateway mandaba VOD implícito. */
    @Test fun `un ref viejo sin content_type cae a VOD`() {
        val json = """{"s":"ditu","p":{"content_id":"5"}}"""
        val datos = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals(DituRef("5", "VOD"), DituRef.decodificar("$datos.firma"))
    }
}
