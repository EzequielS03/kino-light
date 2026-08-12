package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode

/**
 * Decide qué pista de subtítulo activar, en una función PURA para poder testearla — `VlcPlayer`
 * depende de libVLC y no corre en la JVM.
 *
 * La regla: los subtítulos se prenden solo si el audio que quedó SONANDO no está en un idioma que
 * dijiste entender ([PlaybackPrefs.understoodLangs]). Si el audio ya se entiende, subtitularlo sobra.
 * OJO que NO se mira [PlaybackPrefs.audioLangs]: esa lista es solo el orden con el que se elige la
 * pista, y elegir a mano el japonés de un anime la reordena — no significa que sepas japonés.
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
