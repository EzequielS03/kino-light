package com.arkiv.player.cast

import androidx.media3.common.MimeTypes

/**
 * Qué audio puede decodificar el receptor de Chromecast por sí mismo.
 *
 * Existe porque el fallo es MUDO: si el receptor no sabe el códec, reproduce el video y no suena
 * nada, sin un solo error ni en el celu ni en la TV. Pasó con AC-3 (Avatar) y con DTS (Naruto),
 * ambos H.264 — por eso se veía la imagen.
 *
 * La lista sale de la doc de Cast (https://developers.google.com/cast/docs/media): AAC, MP3, Opus,
 * Vorbis, FLAC y LPCM. AC-3 y E-AC-3 el Chromecast NO los decodifica, solo los reenvía por HDMI para
 * que los decodifique la TV, y eso hay que habilitarlo aparte; DTS y TrueHD no están de ninguna
 * forma.
 *
 * Ya no hay transcodificador: cuando el receptor no puede con el audio, se castea igual y se avisa
 * (ver `castRequestFor` en `PlayerScreen.kt`). Este objeto solo decide el aviso, no un fallback.
 */
object CastAudioSupport {

    /**
     * La familia AAC. Cast la lista como soportada, pero se cae con más de 2 canales: el módulo de
     * Chromecast de VLC lo prohíbe explícitamente ("Disallow multichannel AAC") y Jellyfin tuvo que
     * arreglar exactamente lo mismo.
     */
    private val AAC = setOf(MimeTypes.AUDIO_AAC)

    /** Lo que el receptor decodifica sin importar cuántos canales traiga. */
    private val DECODIFICABLE = setOf(
        MimeTypes.AUDIO_MPEG,
        MimeTypes.AUDIO_MPEG_L1,
        MimeTypes.AUDIO_MPEG_L2,
        MimeTypes.AUDIO_OPUS,
        MimeTypes.AUDIO_VORBIS,
        MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_RAW,
    )

    /**
     * ¿El receptor decodifica este audio tal cual?
     *
     * Es una lista blanca: lo que no reconocemos NO se da por decodificable. Sin transcodificador
     * ya no hay a dónde caer, así que esto solo decide si hace falta el aviso de "puede sonar
     * mudo", nunca si se castea o no. La única excepción es un `sampleMimeType` nulo: ahí la pista
     * todavía no se parseó, y forzar el aviso rompería el camino de hoy en fuentes que andan bien.
     */
    fun receiverDecodes(sampleMimeType: String?, channelCount: Int): Boolean = when (sampleMimeType) {
        null -> true
        in AAC -> channelCount <= 2
        in DECODIFICABLE -> true
        else -> false
    }
}
