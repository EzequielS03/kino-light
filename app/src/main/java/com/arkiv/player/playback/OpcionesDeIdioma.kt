package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs

/**
 * Qué idioma de audio pedirle a libVLC AL ABRIR, en vez de corregirlo después.
 *
 * Portado de la app original de magis: le pasa a su ijkplayer `setOption(4, "audio_language", …)` al
 * construirlo (`yc/C6276a.java:37`, en el decompilado), o sea que ffmpeg ya elige bien en el momento
 * de abrir el archivo. Nosotros hacíamos lo contrario —dejar que libVLC eligiera la primera pista y
 * corregirla después de `Playing` con reintentos— y esa es una carrera que se pierde sola cuando las
 * pistas pueblan tarde: el KDoc de [VlcPlayer.applyPreferredAudio] la describe como "hay que
 * ganarle esa carrera".
 *
 * Esto NO reemplaza a [TrackSelector], lo complementa: `--audio-language` compara contra el CÓDIGO
 * ISO de la pista, y ahí Latino y Castellano son los dos `spa`. Distinguir las variantes del español
 * exige mirar el NOMBRE de la pista, que es lo que hace el pase fino. Lo que se gana acá es no abrir
 * nunca más en inglés o japonés cuando existía una pista en español.
 *
 * Sobre los SUBTÍTULOS: a propósito no se manda `:sub-language`. La decisión de subtítulo depende de
 * qué audio quedó sonando ([SubtitleDecision] los apaga si el audio ya se entiende), y eso no se
 * sabe hasta después de abrir. Pedirle a VLC que active uno al abrir pelearía contra ese apagado.
 */
object OpcionesDeIdioma {

    /**
     * Códigos ISO por bucket, en el orden en que se le ofrecen a VLC.
     *
     * Van el de tres letras y el de dos porque el contenedor puede traer cualquiera de los dos:
     * un MKV de escena suele etiquetar `spa`, y un MP4 con metadatos de iTunes, `es`.
     */
    private val CODIGOS = mapOf(
        TrackLang.LATINO to listOf("spa", "es"),
        TrackLang.CASTELLANO to listOf("spa", "es"),
        TrackLang.SPANISH to listOf("spa", "es"),
        TrackLang.ENGLISH to listOf("eng", "en"),
        TrackLang.JAPANESE to listOf("jpn", "ja"),
        // DUAL y UNKNOWN no son idiomas: no hay nada que pedirle a VLC.
    )

    /**
     * La lista de códigos que espera `--audio-language`, en el orden de [orden] y sin repetir, o
     * null si ninguno de los buckets pedidos corresponde a un idioma real.
     */
    fun codigosDe(orden: List<TrackLang>): String? =
        orden.flatMap { CODIGOS[it].orEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    /** La opción lista para agregarle al `Media`, o null si no hay nada que pedir. */
    fun opcionDeAudio(prefs: PlaybackPrefs): String? =
        codigosDe(prefs.audioLangs)?.let { ":audio-language=$it" }
}
