package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLanguageTest {

    @Test fun classifyLatino() {
        assertEquals(TrackLang.LATINO, LangTokens.classify("Español Latino"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Pista 2 - LAT"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Audio Latino"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("es-419"))
        assertEquals(TrackLang.LATINO, LangTokens.classify("Español (México)"))
    }

    @Test fun classifyCastellano() {
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Castellano"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Español (España)"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classify("Track 1 - [Cast]"))
    }

    @Test fun classifyGenericSpanishAndOthers() {
        assertEquals(TrackLang.SPANISH, LangTokens.classify("Track 1 - [Spanish]"))
        assertEquals(TrackLang.DUAL, LangTokens.classify("Dual"))
        assertEquals(TrackLang.ENGLISH, LangTokens.classify("Audio - [English]"))
        assertEquals(TrackLang.JAPANESE, LangTokens.classify("Japanese"))
        assertEquals(TrackLang.UNKNOWN, LangTokens.classify("Track 3"))
    }

    @Test fun selectPrefersLatinoOverCastellano() {
        val tracks = listOf(-1 to "Disable", 0 to "Castellano", 1 to "Español Latino")
        assertEquals(1, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectFallsBackToCastellanoWhenNoLatino() {
        val tracks = listOf(0 to "Castellano", 1 to "English")
        assertEquals(0, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectGenericSpanishSatisfiesLatinoPreference() {
        val tracks = listOf(0 to "Track 1 - [Spanish]", 1 to "Track 2 - [English]")
        assertEquals(0, TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectReturnsNullWhenNothingMatches() {
        val tracks = listOf(0 to "English", 1 to "French")
        assertNull(TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectSkipsWhenSingleTrack() {
        val tracks = listOf(-1 to "Disable", 0 to "English")
        assertNull(TrackSelector.select(tracks, TrackSelector.DEFAULT_AUDIO))
    }

    @Test fun selectRespectsCustomPreferenceOrder() {
        val tracks = listOf(0 to "Español Latino", 1 to "Castellano")
        assertEquals(1, TrackSelector.select(tracks, listOf(TrackLang.CASTELLANO, TrackLang.LATINO)))
    }

    // --- NUEVO: recorre la lista completa hasta encontrar algo que exista ---

    @Test fun selectFallsThroughOrderUntilAMatchExists() {
        val tracks = listOf(0 to "Japanese", 1 to "English")
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)
        assertEquals(1, TrackSelector.select(tracks, order))
    }

    // --- NUEVO: una sola pista SÍ se puede elegir cuando no se exige que haya opciones ---

    @Test fun selectWithoutRequireChoicePicksTheOnlyTrack() {
        val tracks = listOf(0 to "Spanish")
        assertNull(TrackSelector.select(tracks, listOf(TrackLang.SPANISH)))
        assertEquals(0, TrackSelector.select(tracks, listOf(TrackLang.SPANISH), requireChoice = false))
    }

    // --- NUEVO: clasificación por nombre de archivo (los .srt inyectados) ---

    @Test fun classifyFileNameReadsTheLanguageSuffix() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("movie.es.srt"))
        assertEquals(TrackLang.LATINO, LangTokens.classifyFileName("movie.lat.srt"))
        assertEquals(TrackLang.CASTELLANO, LangTokens.classifyFileName("movie.cast.srt"))
        assertEquals(TrackLang.JAPANESE, LangTokens.classifyFileName("movie.jpn.ass"))
    }

    /** El agujero que motivó este clasificador: `classify()` NO reconoce "en" suelto. */
    @Test fun classifyFileNameCatchesTwoLetterEnglishThatClassifyMisses() {
        assertEquals(TrackLang.UNKNOWN, LangTokens.classify("movie.en.srt"))
        assertEquals(TrackLang.ENGLISH, LangTokens.classifyFileName("movie.en.srt"))
    }

    /** Y no debe inventar inglés donde solo hay la preposición "en". */
    @Test fun classifyFileNameDoesNotFalsePositiveOnSpanishProse() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Audio en español"))
    }

    /** libVLC decora el nombre de la pista externa; igual hay que encontrar el sufijo. */
    @Test fun classifyFileNameSurvivesVlcDecoration() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Track 1 - [/data/x/movie.es.srt]"))
    }

    @Test fun classifyFileNameFallsBackToFreeTextWhenThereIsNoSuffix() {
        assertEquals(TrackLang.SPANISH, LangTokens.classifyFileName("Spanish.srt"))
        assertEquals(TrackLang.UNKNOWN, LangTokens.classifyFileName("subtitulo1.srt"))
    }

    // --- NUEVO: "¿está en mi lista?" con el español como familia ---

    @Test fun satisfiesTreatsAllSpanishVariantsAsOneFamily() {
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO)
        assertTrue(LangTokens.satisfies(TrackLang.SPANISH, order))
        assertTrue(LangTokens.satisfies(TrackLang.LATINO, order))
        assertTrue(LangTokens.satisfies(TrackLang.CASTELLANO, listOf(TrackLang.SPANISH)))
    }

    @Test fun satisfiesIsFalseForLanguagesOutsideTheList() {
        val order = listOf(TrackLang.LATINO, TrackLang.CASTELLANO)
        assertFalse(LangTokens.satisfies(TrackLang.JAPANESE, order))
        assertFalse(LangTokens.satisfies(TrackLang.ENGLISH, order))
    }

    @Test fun satisfiesMatchesNonSpanishExactly() {
        assertTrue(LangTokens.satisfies(TrackLang.ENGLISH, listOf(TrackLang.LATINO, TrackLang.ENGLISH)))
    }
}
