package com.arkiv.player.playback

import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDecisionTest {

    // audioLangs se deja a propósito con un orden que NO coincide con understoodLangs: la decisión de
    // subtítulos solo puede mirar la segunda. Si algún día se vuelve a mirar audioLangs, el japonés de
    // acá adentro haría fallar a `japaneseAudioStillGetsSubtitlesAfterPromotion`.
    private val prefs = PlaybackPrefs(
        audioLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO),
        understoodLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO),
        subtitleLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    )
    private val subs = listOf(-1 to "Disable", 0 to "English", 1 to "Spanish")

    @Test fun foreignAudioTurnsSubtitlesOnInThePreferredLanguage() {
        assertEquals(1, SubtitleDecision.decide("Japanese", subs, prefs))
    }

    @Test fun audioInMyLanguageLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Español Latino", subs, prefs))
    }

    /** El comodín del español: "Spanish" a secas cuenta como propio con Latino>Castellano. */
    @Test fun genericSpanishAudioCountsAsMine() {
        assertEquals(-1, SubtitleDecision.decide("Track 1 - [Spanish]", subs, prefs))
    }

    /** Si marcaste que entendés inglés, el audio en inglés deja de prender subs. */
    @Test fun audioInAnAddedLanguageAlsoCountsAsMine() {
        val conIngles = prefs.copy(understoodLangs = prefs.understoodLangs + TrackLang.ENGLISH)
        assertEquals(-1, SubtitleDecision.decide("English", subs, conIngles))
    }

    /**
     * El caso que motivó separar las dos listas: en un anime dual elegís japonés a mano, la promoción
     * lo sube al tope de `audioLangs` y en el próximo capítulo el japonés se auto-selecciona. Los
     * subtítulos TIENEN que seguir prendiéndose: nunca dijiste que entendías japonés.
     */
    @Test fun japaneseAudioStillGetsSubtitlesAfterPromotion() {
        val promovido = prefs.copy(audioLangs = listOf(TrackLang.JAPANESE) + prefs.audioLangs)
        assertEquals(1, SubtitleDecision.decide("Japanese", subs, promovido))
    }

    /** Y al revés: marcar japonés como entendido sí los apaga, aunque no esté en audioLangs. */
    @Test fun understandingJapaneseTurnsThemOffWithoutTouchingTheAudioOrder() {
        val entiendeJapones = prefs.copy(understoodLangs = prefs.understoodLangs + TrackLang.JAPANESE)
        assertEquals(-1, SubtitleDecision.decide("Japanese", subs, entiendeJapones))
    }

    /** Pista sin etiqueta: se asume que es tu idioma. Prender subs porque sí sería peor. */
    @Test fun unknownAudioLeavesSubtitlesOff() {
        assertEquals(-1, SubtitleDecision.decide("Track 1", subs, prefs))
        assertEquals(-1, SubtitleDecision.decide(null, subs, prefs))
    }

    @Test fun offModeNeverTurnsThemOn() {
        val off = prefs.copy(subtitleMode = SubtitleMode.OFF)
        assertEquals(-1, SubtitleDecision.decide("Japanese", subs, off))
    }

    @Test fun foreignAudioWithNoSubtitleInMyLanguagesStaysOff() {
        val soloFrances = listOf(-1 to "Disable", 0 to "French")
        assertEquals(-1, SubtitleDecision.decide("Japanese", soloFrances, prefs))
    }

    /** Una única pista de subtítulo SÍ se prende (a diferencia del audio, acá no se exige elección). */
    @Test fun aSingleMatchingSubtitleIsSelected() {
        val unaSola = listOf(0 to "Spanish")
        assertEquals(0, SubtitleDecision.decide("Japanese", unaSola, prefs))
    }

    /** Los .srt inyectados se clasifican por el sufijo del nombre de archivo. */
    @Test fun injectedSrtIsPickedByItsFileNameSuffix() {
        val externos = listOf(0 to "/data/x/movie.en.srt", 1 to "/data/x/movie.es.srt")
        assertEquals(1, SubtitleDecision.decide("Japanese", externos, prefs))
    }

    @Test fun noSubtitleTracksAtAllStaysOff() {
        assertEquals(-1, SubtitleDecision.decide("Japanese", listOf(-1 to "Disable"), prefs))
    }
}
