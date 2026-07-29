package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El receptor de Chromecast decodifica un conjunto chico de códecs de audio. Lo que no está en él
 * se cae en SILENCIO: la TV muestra el video y no suena nada, sin un solo error. Estos tests fijan
 * qué se manda directo y qué hay que transcodificar antes.
 */
class CastAudioSupportTest {

    @Test
    fun `AC-3 no lo decodifica el receptor`() {
        // El caso del Avatar: se veía la imagen y no sonaba.
        assertFalse(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("a52 "), channels = 6))
    }

    @Test
    fun `E-AC-3 tampoco`() {
        assertFalse(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("eac3"), channels = 6))
    }

    @Test
    fun `DTS tampoco, ni por pasarela`() {
        // El caso del Naruto. Cast no lo soporta de ninguna forma.
        assertFalse(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("dts "), channels = 6))
    }

    @Test
    fun `AAC estereo va directo`() {
        // El caso de archive.org, que hoy funciona: no debe pagar ningún transcode.
        assertTrue(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("mp4a"), channels = 2))
    }

    @Test
    fun `AAC multicanal hay que bajarlo a estereo`() {
        // Cast lista AAC como soportado pero falla con 5.1: el módulo de Chromecast de VLC lo
        // prohíbe explícitamente y Jellyfin tuvo que arreglar lo mismo.
        assertFalse(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("mp4a"), channels = 6))
    }

    @Test
    fun `MP3, Opus, Vorbis y FLAC van directo`() {
        listOf("mpga", "Opus", "vorb", "flac").forEach { codec ->
            assertTrue(codec, CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf(codec), channels = 2))
        }
    }

    @Test
    fun `un codec desconocido se transcodifica por las dudas`() {
        // TrueHD y compañía. Transcodificar de más cuesta CPU; no hacerlo cuesta quedarse sin audio.
        assertFalse(CastAudioSupport.receiverDecodes(CastAudioSupport.fourccOf("mlp "), channels = 8))
    }

    @Test
    fun `sin informacion de pista se mantiene el camino de hoy`() {
        // fourcc 0 = no pudimos leer la pista. Forzar transcode acá rompería fuentes que ya andan.
        assertTrue(CastAudioSupport.receiverDecodes(fourcc = 0, channels = 0))
    }

    @Test
    fun `el fourcc se puede volver a leer para el log`() {
        // Un `fourcc=541677153` en el logcat no le sirve a nadie; `a52` sí.
        assertEquals("a52", CastAudioSupport.fourccToString(CastAudioSupport.fourccOf("a52 ")))
        assertEquals("mp4a", CastAudioSupport.fourccToString(CastAudioSupport.fourccOf("mp4a")))
    }

    @Test
    fun `sin pista que leer el log lo dice en castellano`() {
        assertEquals("desconocido", CastAudioSupport.fourccToString(0))
    }

    @Test
    fun `el fourcc se empaqueta como lo hace VLC`() {
        // VLC_FOURCC(a,b,c,d) = a | b<<8 | c<<16 | d<<24. Si esto se invierte, el portero no matchea
        // nada y todo el diagnóstico miente.
        assertEquals(
            'a'.code or ('5'.code shl 8) or ('2'.code shl 16) or (' '.code shl 24),
            CastAudioSupport.fourccOf("a52 "),
        )
    }
}
