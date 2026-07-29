package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioLanguageTest {

    @Test fun classifyLatino() {
        assertEquals(AudioLang.LATINO, LangTokens.classify("Español Latino"))
        assertEquals(AudioLang.LATINO, LangTokens.classify("Pista 2 - LAT"))
        assertEquals(AudioLang.LATINO, LangTokens.classify("Audio Latino"))
        assertEquals(AudioLang.LATINO, LangTokens.classify("es-419"))
        assertEquals(AudioLang.LATINO, LangTokens.classify("Español (México)"))
    }

    @Test fun classifyCastellano() {
        assertEquals(AudioLang.CASTELLANO, LangTokens.classify("Castellano"))
        assertEquals(AudioLang.CASTELLANO, LangTokens.classify("Español (España)"))
        assertEquals(AudioLang.CASTELLANO, LangTokens.classify("Track 1 - [Cast]"))
    }

    @Test fun classifyGenericSpanishAndOthers() {
        assertEquals(AudioLang.SPANISH, LangTokens.classify("Track 1 - [Spanish]"))
        assertEquals(AudioLang.DUAL, LangTokens.classify("Dual"))
        assertEquals(AudioLang.ENGLISH, LangTokens.classify("Audio - [English]"))
        assertEquals(AudioLang.JAPANESE, LangTokens.classify("Japanese"))
        assertEquals(AudioLang.UNKNOWN, LangTokens.classify("Track 3"))
    }

    @Test fun selectPrefersLatinoOverCastellano() {
        val tracks = listOf(-1 to "Disable", 0 to "Castellano", 1 to "Español Latino")
        assertEquals(1, AudioTrackSelector.select(tracks))
    }

    @Test fun selectFallsBackToCastellanoWhenNoLatino() {
        val tracks = listOf(0 to "Castellano", 1 to "English")
        assertEquals(0, AudioTrackSelector.select(tracks))
    }

    @Test fun selectGenericSpanishSatisfiesLatinoPreference() {
        val tracks = listOf(0 to "Track 1 - [Spanish]", 1 to "Track 2 - [English]")
        assertEquals(0, AudioTrackSelector.select(tracks))
    }

    @Test fun selectReturnsNullWhenNoSpanish() {
        val tracks = listOf(0 to "English", 1 to "French")
        assertNull(AudioTrackSelector.select(tracks))
    }

    @Test fun selectSkipsWhenSingleTrack() {
        val tracks = listOf(-1 to "Disable", 0 to "English")
        assertNull(AudioTrackSelector.select(tracks))
    }

    @Test fun selectRespectsCustomPreferenceOrder() {
        val tracks = listOf(0 to "Español Latino", 1 to "Castellano")
        assertEquals(1, AudioTrackSelector.select(tracks, listOf(AudioLang.CASTELLANO, AudioLang.LATINO)))
    }
}
