package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPrefsTest {

    @Test fun roundTripPreservesEverything() {
        val p = PlaybackPrefs(
            audioLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            subtitleLangs = listOf(TrackLang.LATINO),
            subtitleMode = SubtitleMode.OFF,
            sizePercent = 140, textColor = 0xFFFFEB3B, backgroundColor = 0xCC000000, edge = 2,
        )
        assertEquals(p, PlaybackPrefs.fromJson(p.toJson()))
    }

    /** Una build vieja manda un JSON sin los campos nuevos: hay que caer a los defaults. */
    @Test fun oldJsonWithoutNewFieldsFallsBackToDefaults() {
        val viejo = """{"language":"es","sizePercent":120,"textColor":4294967295,"backgroundColor":2147483648,"edge":1}"""
        val p = PlaybackPrefs.fromJson(viejo)!!
        assertEquals(PlaybackPrefs().audioLangs, p.audioLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(SubtitleMode.AUTO, p.subtitleMode)
        assertEquals(120, p.sizePercent)
    }

    @Test fun legacyLanguageOffMigratesToSubtitleModeOff() {
        val p = PlaybackPrefs.fromJson("""{"language":"off"}""")!!
        assertEquals(SubtitleMode.OFF, p.subtitleMode)
    }

    /** Y al revés: una build vieja tiene que seguir entendiendo lo que escribimos. */
    @Test fun toJsonStillWritesTheLegacyLanguageField() {
        assertEquals("off", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.OFF).toJson()).getString("language"))
        assertEquals("es", org.json.JSONObject(PlaybackPrefs(subtitleMode = SubtitleMode.AUTO).toJson()).getString("language"))
    }

    @Test fun fromJsonReturnsNullOnGarbage() {
        assertEquals(null, PlaybackPrefs.fromJson("no soy json"))
    }

    // --- códigos para OpenSubtitles ---

    @Test fun spanishVariantsCollapseToASingleEsCode() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH))
        assertEquals("es", p.openSubtitlesCodes())
    }

    @Test fun openSubtitlesCodesKeepTheUserOrder() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO))
        assertEquals("en,es", p.openSubtitlesCodes())
    }

    @Test fun dualIsIgnoredBecauseItIsNotATextLanguage() {
        val p = PlaybackPrefs(subtitleLangs = listOf(TrackLang.DUAL, TrackLang.JAPANESE))
        assertEquals("ja", p.openSubtitlesCodes())
    }

    /** OFF no apaga la búsqueda online: el menú CC tiene que seguir teniendo opciones. */
    @Test fun emptyOrOffStillSearchesInSpanish() {
        assertEquals("es", PlaybackPrefs(subtitleLangs = emptyList()).openSubtitlesCodes())
        assertEquals("es", PlaybackPrefs(subtitleLangs = listOf(TrackLang.DUAL)).openSubtitlesCodes())
    }
}
