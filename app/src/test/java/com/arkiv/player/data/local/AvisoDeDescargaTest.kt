package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AvisoDeDescargaTest {

    @Test
    fun `muestra el porcentaje cuando se sabe`() {
        assertEquals("42%", AvisoDeDescarga.subtitulo(0.42f, enCola = 0))
    }

    @Test
    fun `sin tamano conocido dice que esta preparando`() {
        assertEquals("Preparando…", AvisoDeDescarga.subtitulo(null, enCola = 0))
    }

    @Test
    fun `avisa cuantos esperan turno`() {
        assertEquals("42% · 3 más en cola", AvisoDeDescarga.subtitulo(0.42f, enCola = 3))
    }

    @Test
    fun `un solo pendiente va en singular`() {
        assertEquals("42% · 1 más en cola", AvisoDeDescarga.subtitulo(0.42f, enCola = 1))
    }

    @Test
    fun `el titulo junta serie y capitulo`() {
        assertEquals("Bajando Daima · E1", AvisoDeDescarga.titulo("Daima", "E1"))
    }

    @Test
    fun `con solo uno de los dos no deja el separador suelto`() {
        assertEquals("Bajando E1", AvisoDeDescarga.titulo(null, "E1"))
        assertEquals("Bajando Daima", AvisoDeDescarga.titulo("Daima", "  "))
    }

    @Test
    fun `sin nombre no muestra el id crudo`() {
        assertEquals("Bajando un capítulo", AvisoDeDescarga.titulo(null, null))
    }

    @Test
    fun `el aviso de terminado dice que capitulo fue`() {
        assertEquals("Daima · E1", AvisoDeDescarga.listo("Daima", "E1"))
    }

    @Test
    fun `si no se sabe el capitulo el aviso de terminado sigue diciendo algo util`() {
        assertEquals("Ya lo puedes ver sin conexión", AvisoDeDescarga.listo(null, null))
    }
}
