package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtiquetaDeDescargaTest {

    @Test
    fun `bajando dice el porcentaje`() {
        assertEquals("Bajando 42%", EtiquetaDeDescarga.para(EstadoDeDescarga.Bajando(0.42f)))
    }

    @Test
    fun `bajando sin saber cuanto falta no inventa un numero`() {
        assertEquals("Bajando…", EtiquetaDeDescarga.para(EstadoDeDescarga.Bajando(null)))
    }

    @Test
    fun `en cola lo dice`() {
        assertEquals("En cola", EtiquetaDeDescarga.para(EstadoDeDescarga.EnCola))
    }

    @Test
    fun `descargado lo dice`() {
        assertEquals("Descargado", EtiquetaDeDescarga.para(EstadoDeDescarga.Lista))
    }

    @Test
    fun `el fallo muestra su motivo, no un mensaje generico`() {
        assertEquals(
            "Este episodio no tiene un archivo descargable",
            EtiquetaDeDescarga.para(EstadoDeDescarga.Fallida("Este episodio no tiene un archivo descargable")),
        )
    }

    @Test
    fun `un fallo sin motivo igual se anuncia`() {
        assertEquals("Falló la descarga", EtiquetaDeDescarga.para(EstadoDeDescarga.Fallida(null)))
    }

    @Test
    fun `el torrent pesado explica que espera`() {
        assertEquals(
            "Pesa mucho: confírmala en Descargas",
            EtiquetaDeDescarga.para(EstadoDeDescarga.PideConfirmacion),
        )
    }

    @Test
    fun `lo que nadie encolo no agrega ninguna linea`() {
        assertNull(EtiquetaDeDescarga.para(EstadoDeDescarga.SinDescargar))
    }
}
