package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode

/**
 * Decide qué pista de subtítulo activar, en una función PURA para poder testearla — `VlcPlayer`
 * depende de libVLC y no corre en la JVM.
 *
 * La regla: los subtítulos se prenden solo si el idioma que quedó SONANDO no está en tu lista de
 * audio. Si el audio ya está en un idioma que entendés, subtitularlo sobra.
 */
object SubtitleDecision {

    /** Id de pista SPU a activar. `-1` = apagados. */
    fun decide(
        audioTrackName: String?,
        spuTracks: List<Pair<Int, String>>,
        prefs: PlaybackPrefs,
    ): Int {
        if (prefs.subtitleMode == SubtitleMode.OFF) return APAGADO
        val audioLang = audioTrackName?.let { LangTokens.classify(it) } ?: TrackLang.UNKNOWN
        // Pista sin etiqueta ("Track 1"): asumir que es tu idioma. Lo contrario haría aparecer
        // subtítulos en cualquier película normal cuyo MKV no etiquete el audio.
        if (audioLang == TrackLang.UNKNOWN) return APAGADO
        if (LangTokens.satisfies(audioLang, prefs.audioLangs)) return APAGADO
        // Audio extranjero → buscar subtítulo. requireChoice=false: un único subtítulo en tu idioma
        // hay que prenderlo igual, aunque no haya nada más entre qué elegir.
        return TrackSelector.select(
            tracks = spuTracks,
            order = prefs.subtitleLangs,
            requireChoice = false,
            classifier = LangTokens::classifyFileName,
        ) ?: APAGADO
    }

    const val APAGADO = -1
}
