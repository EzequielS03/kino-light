package com.arkiv.player.cast

/**
 * Qué audio puede decodificar el receptor de Chromecast por sí mismo, y qué hay que transcodificarle
 * antes de mandárselo.
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
 * Se decide por `fourcc` y no por el string `codec` de libVLC a propósito: ese string es
 * `libvlc_media_get_codec_description()`, o sea texto para humanos ("A/52 Audio (aka AC3)"), que
 * cambia entre versiones. El fourcc es el identificador estable.
 */
object CastAudioSupport {

    /** Empaqueta 4 caracteres como el `VLC_FOURCC` de VLC: `a | b<<8 | c<<16 | d<<24`. */
    fun fourccOf(code: String): Int {
        require(code.length == 4) { "un fourcc son exactamente 4 caracteres, no '$code'" }
        return code[0].code or (code[1].code shl 8) or (code[2].code shl 16) or (code[3].code shl 24)
    }

    /** Desempaqueta un fourcc para leerlo en el logcat: `a52` en vez de `541677153`. */
    fun fourccToString(fourcc: Int): String {
        if (fourcc == SIN_INFO) return "desconocido"
        return String(
            charArrayOf(
                (fourcc and 0xFF).toChar(),
                ((fourcc shr 8) and 0xFF).toChar(),
                ((fourcc shr 16) and 0xFF).toChar(),
                ((fourcc shr 24) and 0xFF).toChar(),
            ),
        ).trim()
    }

    /**
     * La familia AAC. Cast la lista como soportada, pero se cae con más de 2 canales: el módulo de
     * Chromecast de VLC lo prohíbe explícitamente ("Disallow multichannel AAC") y Jellyfin tuvo que
     * arreglar exactamente lo mismo. Un 5.1 en AAC hay que bajarlo a estéreo.
     */
    private val AAC = setOf("mp4a", "laac", "haac", "saac").map(::fourccOf).toSet()

    /** Lo que el receptor decodifica sin importar cuántos canales traiga. */
    private val DECODIFICABLE =
        setOf("mpga", "mp3 ", "Opus", "vorb", "flac", "araw", "lpcm").map(::fourccOf).toSet()

    /** No se pudo leer la pista. */
    private const val SIN_INFO = 0

    /**
     * ¿El receptor decodifica este audio tal cual, o hay que transcodificarlo?
     *
     * Es una lista blanca: lo que no reconocemos se transcodifica. Transcodificar de más cuesta algo
     * de CPU y siempre suena; no transcodificar de menos cuesta quedarse sin audio y sin pistas de
     * por qué. La única excepción es [SIN_INFO]: ahí no sabemos nada, y forzar el transcode rompería
     * fuentes que hoy andan bien (archive.org es MP4 con AAC).
     */
    fun receiverDecodes(fourcc: Int, channels: Int): Boolean = when (fourcc) {
        SIN_INFO -> true
        in AAC -> channels <= 2
        in DECODIFICABLE -> true
        else -> false
    }
}
