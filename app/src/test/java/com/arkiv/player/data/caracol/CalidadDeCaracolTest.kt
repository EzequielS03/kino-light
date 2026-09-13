package com.arkiv.player.data.caracol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las seis calidades reales del capítulo medido el 2026-09-13 ("Dulce Amor" E1). */
private fun manifiestoReal() = listOf(
    PistaDeCaracol(grupo = 0, pista = 0, esVideo = true, alto = 144, bitsPorSegundo = 164_344),
    PistaDeCaracol(grupo = 0, pista = 1, esVideo = true, alto = 240, bitsPorSegundo = 330_000),
    PistaDeCaracol(grupo = 0, pista = 2, esVideo = true, alto = 360, bitsPorSegundo = 700_000),
    PistaDeCaracol(grupo = 0, pista = 3, esVideo = true, alto = 480, bitsPorSegundo = 1_200_000),
    PistaDeCaracol(grupo = 0, pista = 4, esVideo = true, alto = 720, bitsPorSegundo = 2_400_000),
    PistaDeCaracol(grupo = 0, pista = 5, esVideo = true, alto = 1080, bitsPorSegundo = 4_452_000),
    PistaDeCaracol(grupo = 1, pista = 0, esVideo = false, alto = 0, bitsPorSegundo = 64_000),
    PistaDeCaracol(grupo = 1, pista = 1, esVideo = false, alto = 0, bitsPorSegundo = 97_768),
)

class CalidadDeCaracolTest {

    @Test
    fun `toma el mejor video que no pase del techo`() {
        val elegidas = CalidadDeCaracol.elegir(manifiestoReal(), altoObjetivo = 480)
        assertEquals(480, elegidas.first { it.esVideo }.alto)
    }

    @Test
    fun `el techo por defecto deja 720 y no 1080`() {
        val elegidas = CalidadDeCaracol.elegir(manifiestoReal())
        assertEquals(720, elegidas.first { it.esVideo }.alto)
    }

    @Test
    fun `el audio es el de mas bits porque al lado del video no pesa`() {
        val elegidas = CalidadDeCaracol.elegir(manifiestoReal())
        assertEquals(97_768, elegidas.first { !it.esVideo }.bitsPorSegundo)
    }

    @Test
    fun `una sola de video y una sola de audio`() {
        val elegidas = CalidadDeCaracol.elegir(manifiestoReal())
        assertEquals(1, elegidas.count { it.esVideo })
        assertEquals(1, elegidas.count { !it.esVideo })
    }

    @Test
    fun `si todas superan el techo baja la mas chica en vez de rendirse`() {
        val soloGrandes = manifiestoReal().filter { !it.esVideo || it.alto >= 720 }
        val elegidas = CalidadDeCaracol.elegir(soloGrandes, altoObjetivo = 360)
        assertEquals(720, elegidas.first { it.esVideo }.alto)
    }

    @Test
    fun `sin video no hay nada que ofrecer`() {
        val soloAudio = manifiestoReal().filter { !it.esVideo }
        assertTrue(CalidadDeCaracol.elegir(soloAudio).isEmpty())
    }

    @Test
    fun `sin audio igual se baja el video`() {
        val soloVideo = manifiestoReal().filter { it.esVideo }
        val elegidas = CalidadDeCaracol.elegir(soloVideo)
        assertEquals(1, elegidas.size)
        assertTrue(elegidas.single().esVideo)
    }

    @Test
    fun `la estimacion reproduce los 97 MB que se midieron en el celular`() {
        // 144p + el audio de 97 kbps, 2_814_505 ms: el spike dejó 97 MB en disco.
        val pistas = manifiestoReal().filter { (it.esVideo && it.alto == 144) || it.bitsPorSegundo == 97_768 }
        val bytes = CalidadDeCaracol.bytesEstimados(CalidadDeCaracol.elegir(pistas), 2_814_505)
        assertEquals(92L, bytes / 1_000_000)
    }

    @Test
    fun `sin duracion no inventa un peso`() {
        assertEquals(0L, CalidadDeCaracol.bytesEstimados(CalidadDeCaracol.elegir(manifiestoReal()), 0))
    }
}

class DescargaDeCaracolTest {

    private val muestra = DescargaDeCaracol(
        mpd = "https://mdstrm.com/video/693351c8fdae1b250c0d85e3.mpd",
        claves = listOf(ClaveDePista(0, 0, 4), ClaveDePista(0, 1, 1)),
        alto = 720,
    )

    @Test
    fun `sobrevive la ida y vuelta a json`() {
        assertEquals(muestra, DescargaDeCaracol.deJson(muestra.aJson()))
    }

    @Test
    fun `un registro sin mpd no sirve para abrir nada`() {
        assertNull(DescargaDeCaracol.deJson("""{"claves":["0.0.4"]}"""))
    }

    @Test
    fun `un registro sin claves tampoco`() {
        assertNull(DescargaDeCaracol.deJson("""{"mpd":"https://x/y.mpd","claves":[]}"""))
    }

    @Test
    fun `la basura no revienta, devuelve null`() {
        assertNull(DescargaDeCaracol.deJson("no soy json"))
        assertNull(DescargaDeCaracol.deJson(""))
    }

    @Test
    fun `las claves se leen y escriben como periodo punto grupo punto pista`() {
        assertEquals("0.1.4", ClaveDePista(0, 1, 4).texto())
        assertEquals(ClaveDePista(0, 1, 4), ClaveDePista.deTexto("0.1.4"))
    }

    @Test
    fun `una clave mal formada se descarta en vez de reventar`() {
        assertNull(ClaveDePista.deTexto("0.1"))
        assertNull(ClaveDePista.deTexto("a.b.c"))
        assertNull(ClaveDePista.deTexto("0.-1.2"))
    }
}
