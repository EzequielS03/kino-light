package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPrefsTest {

    @Test fun roundTripPreservesEverything() {
        val p = PlaybackPrefs(
            audioLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            understoodLangs = listOf(TrackLang.ENGLISH, TrackLang.JAPANESE),
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
        assertEquals(PlaybackPrefs().understoodLangs, p.understoodLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(SubtitleMode.AUTO, p.subtitleMode)
        assertEquals(120, p.sizePercent)
    }

    // --- "idiomas que entiendo": migración desde audioLangs ---

    /**
     * Antes de separar las dos listas, `audioLangs` cargaba también el significado de "los entiendo".
     * Al leer unas prefs guardadas con esa forma hay que sembrar desde ahí, no desde los defaults: si
     * no, alguien que tenía `[INGLÉS]` configurado empezaría a ver subtítulos sobre su audio inglés.
     */
    @Test fun understoodLangsIsSeededFromAudioLangsWhenAbsent() {
        val guardado = """{"language":"es","audioLangs":["ENGLISH","LATINO"],"subtitleLangs":["LATINO"]}"""
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            PlaybackPrefs.fromJson(guardado)!!.understoodLangs,
        )
    }

    @Test fun understoodLangsFallsBackToTheDefaultWhenThereIsNoAudioLangsEither() {
        assertEquals(
            PlaybackPrefs().understoodLangs,
            PlaybackPrefs.fromJson("""{"language":"es"}""")!!.understoodLangs,
        )
    }

    /** Con el campo propio presente manda él, aunque audioLangs diga otra cosa. */
    @Test fun understoodLangsWinsOverTheSeedWhenItIsPresent() {
        val json = """{"audioLangs":["JAPANESE"],"understoodLangs":["LATINO"]}"""
        assertEquals(listOf(TrackLang.LATINO), PlaybackPrefs.fromJson(json)!!.understoodLangs)
    }

    // --- mezcla con lo que ya hay (sync desde una build vieja) ---

    /**
     * El Fire Stick corre la build anterior y manda un JSON sin listas de idioma. Antes eso pisaba con
     * los defaults lo que el celular tenía configurado; ahora lo que no viene se queda como estaba.
     */
    @Test fun jsonFromAnOldBuildKeepsTheListsAlreadyConfigured() {
        val actual = PlaybackPrefs(
            audioLangs = listOf(TrackLang.ENGLISH, TrackLang.LATINO),
            understoodLangs = listOf(TrackLang.ENGLISH),
            subtitleLangs = listOf(TrackLang.ENGLISH),
        )
        val delTv = """{"language":"es","sizePercent":110,"textColor":4294967295,"backgroundColor":2147483648,"edge":1}"""
        val p = PlaybackPrefs.fromJson(delTv, base = actual)!!
        assertEquals(actual.audioLangs, p.audioLangs)
        assertEquals(actual.understoodLangs, p.understoodLangs)
        assertEquals(actual.subtitleLangs, p.subtitleLangs)
        assertEquals(110, p.sizePercent) // lo que sí vino, se aplica
    }

    /** Y lo que la otra punta sí manda tiene que ganar: mezclar no es ignorar. */
    @Test fun listsPresentInTheJsonReplaceTheCurrentOnes() {
        val actual = PlaybackPrefs(audioLangs = listOf(TrackLang.ENGLISH))
        val json = PlaybackPrefs(audioLangs = listOf(TrackLang.JAPANESE)).toJson()
        assertEquals(listOf(TrackLang.JAPANESE), PlaybackPrefs.fromJson(json, base = actual)!!.audioLangs)
    }

    /**
     * Una build futura que agregue un `TrackLang` manda nombres que este binario no conoce. Quedarse
     * con la lista vacía sería "nunca auto-seleccionar audio" y "siempre poner subtítulos".
     */
    @Test fun anArrayOfUnknownNamesFallsBackInsteadOfEmptyingTheList() {
        val json = """{"audioLangs":["KOREAN"],"subtitleLangs":[],"understoodLangs":["KOREAN"]}"""
        val p = PlaybackPrefs.fromJson(json)!!
        assertEquals(PlaybackPrefs().audioLangs, p.audioLangs)
        assertEquals(PlaybackPrefs().subtitleLangs, p.subtitleLangs)
        assertEquals(PlaybackPrefs().understoodLangs, p.understoodLangs)
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
