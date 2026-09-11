package com.arkiv.player.cast

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El receptor de Chromecast decodifica un conjunto chico de códecs de audio. Lo que no está en él
 * se cae en SILENCIO: la TV muestra el video y no suena nada, sin un solo error. Estos tests fijan
 * qué se manda directo y qué no -- mismas decisiones que el gate anterior basado en fourcc de
 * libVLC, ahora leídas directo del `Format` de ExoPlayer.
 */
class CastAudioSupportTest {

    @Test
    fun `AC-3 no lo decodifica el receptor`() {
        // El caso del Avatar: se veía la imagen y no sonaba.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AC3, channelCount = 6))
    }

    @Test
    fun `E-AC-3 tampoco`() {
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_E_AC3, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_E_AC3_JOC, channelCount = 6))
    }

    @Test
    fun `DTS tampoco, ni por pasarela`() {
        // El caso del Naruto. Cast no lo soporta de ninguna forma.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS_HD, channelCount = 6))
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_DTS_EXPRESS, channelCount = 6))
    }

    @Test
    fun `TrueHD tampoco`() {
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_TRUEHD, channelCount = 8))
    }

    @Test
    fun `AAC estereo va directo`() {
        // AAC stereo -the shape Magis downloads carry today- decodes on the receiver directly.
        assertTrue(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AAC, channelCount = 2))
    }

    @Test
    fun `AAC multicanal hay que avisar que puede sonar mudo`() {
        // Cast lista AAC como soportado pero falla con 5.1: el módulo de Chromecast de VLC lo
        // prohíbe explícitamente y Jellyfin tuvo que arreglar lo mismo.
        assertFalse(CastAudioSupport.receiverDecodes(MimeTypes.AUDIO_AAC, channelCount = 6))
    }

    @Test
    fun `MP3, Opus, Vorbis, FLAC y PCM van directo`() {
        listOf(
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_MPEG_L1,
            MimeTypes.AUDIO_MPEG_L2,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_RAW,
        ).forEach { mime ->
            assertTrue(mime, CastAudioSupport.receiverDecodes(mime, channelCount = 2))
        }
    }

    @Test
    fun `un codec desconocido se marca como que puede sonar mudo`() {
        // Un mime que el gate nunca vio (por ejemplo un códec nuevo). No hay transcode al que
        // caer: se castea igual, pero la lista blanca decide que puede sonar mudo.
        assertFalse(CastAudioSupport.receiverDecodes("audio/x-something-new", channelCount = 2))
    }

    @Test
    fun `sin informacion de pista se mantiene el camino de hoy`() {
        // mime null = no pudimos leer la pista todavía. Mantiene "no sé → mando directo".
        assertTrue(CastAudioSupport.receiverDecodes(sampleMimeType = null, channelCount = 0))
    }
}
