package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode

/**
 * Decides which subtitle track to activate, as a PURE function so it can be tested without a real
 * player instance.
 *
 * The rule: subtitles turn on only if the audio that ended up PLAYING isn't in a language you said
 * you understand ([PlaybackPrefs.understoodLangs]). If the audio is already understood, subtitling
 * it is redundant. NOTE that [PlaybackPrefs.audioLangs] is NOT consulted: that list is only the
 * order used to pick the track, and manually picking an anime's Japanese reorders it -- it doesn't
 * mean you understand Japanese.
 */
object SubtitleDecision {

    /**
     * Id de pista SPU a activar. `-1` = apagados.
     *
     * [spuClassifier] se puede sobrescribir para las pistas externas cuyo nombre no delata el idioma
     * (las de una fuente web, que llegan como una URL opaca del CDN pero con su idioma declarado
     * aparte).
     */
    fun decide(
        audioTrackName: String?,
        spuTracks: List<Pair<Int, String>>,
        prefs: PlaybackPrefs,
        spuClassifier: (String) -> TrackLang = LangTokens::classifyFileName,
    ): Int {
        if (prefs.subtitleMode == SubtitleMode.OFF) return APAGADO
        val audioLang = audioTrackName?.let { LangTokens.classify(it) } ?: TrackLang.UNKNOWN
        // Pista sin etiqueta ("Track 1"): asumir que es tu idioma. Lo contrario haría aparecer
        // subtítulos en cualquier película normal cuyo MKV no etiquete el audio.
        if (audioLang == TrackLang.UNKNOWN) return APAGADO
        if (LangTokens.satisfies(audioLang, prefs.understoodLangs)) return APAGADO
        // Audio extranjero → buscar subtítulo. requireChoice=false: un único subtítulo en tu idioma
        // hay que prenderlo igual, aunque no haya nada más entre qué elegir.
        return TrackSelector.select(
            tracks = spuTracks,
            order = prefs.subtitleLangs,
            requireChoice = false,
            classifier = spuClassifier,
        ) ?: APAGADO
    }

    const val APAGADO = -1
}
