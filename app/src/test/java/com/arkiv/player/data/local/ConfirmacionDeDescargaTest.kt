package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmacionDeDescargaTest {

    @Test
    fun `lo que espera turno se saca de la cola`() {
        assertEquals(
            AccionDeDescarga.SACAR_DE_LA_COLA,
            ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.EnCola),
        )
    }

    @Test
    fun `lo que esta bajando se cancela`() {
        assertEquals(
            AccionDeDescarga.CANCELAR,
            ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.Bajando(0.3f)),
        )
        assertEquals(
            AccionDeDescarga.CANCELAR,
            ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.Bajando(null)),
        )
    }

    @Test
    fun `lo ya descargado se borra`() {
        assertEquals(
            AccionDeDescarga.BORRAR,
            ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.Lista),
        )
    }

    @Test
    fun `lo que fallo no ofrece nada que confirmar`() {
        // Su acción es reintentar, que no destruye nada y por eso no pregunta.
        assertNull(ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.Fallida("lo que sea")))
    }

    @Test
    fun `lo que nadie encolo no ofrece nada`() {
        assertNull(ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.SinDescargar))
        assertNull(ConfirmacionDeDescarga.accionPara(EstadoDeDescarga.PideConfirmacion))
    }

    @Test
    fun `cancelar avisa que lo bajado no se pierde`() {
        val texto = ConfirmacionDeDescarga.texto(AccionDeDescarga.CANCELAR, "E1")
        assertTrue(texto.cuerpo.contains("E1"))
        assertTrue(texto.cuerpo.contains("reintentar"))
        // El botón de descartar NO puede llamarse "Cancelar" en este diálogo: al lado de
        // "Cancelar la descarga" no se entiende cuál es cuál.
        assertEquals("Seguir bajando", texto.descartar)
    }

    @Test
    fun `borrar aclara que el capitulo sigue en la biblioteca`() {
        val texto = ConfirmacionDeDescarga.texto(AccionDeDescarga.BORRAR, "E1")
        assertTrue(texto.cuerpo.contains("biblioteca"))
        assertEquals("Borrar", texto.confirmar)
    }

    @Test
    fun `sin nombre de capitulo el texto sigue teniendo sentido`() {
        val texto = ConfirmacionDeDescarga.texto(AccionDeDescarga.SACAR_DE_LA_COLA, null)
        assertTrue(texto.cuerpo.isNotBlank())
        assertTrue(texto.cuerpo.contains("«").not())
    }
}
