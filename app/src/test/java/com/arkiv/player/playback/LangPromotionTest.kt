package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangPromotionTest {

    private val orden = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun pickingAnotherLanguageMovesItToTheTop() {
        val todas = listOf("Español Latino", "English")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(orden, "English", todas),
        )
    }

    /** La mitigación clave: sin alternativa no hubo elección, así que no dice nada de tu gusto. */
    @Test fun aFileWithASingleLanguageNeverPromotes() {
        assertNull(LangPromotion.promote(orden, "English", listOf("English")))
        assertNull(LangPromotion.promote(orden, "English", listOf("English", "Audio - [English]")))
    }

    @Test fun anUnknownBucketNeverPromotes() {
        assertNull(LangPromotion.promote(orden, "Track 3", listOf("Track 3", "English")))
    }

    @Test fun pickingWhatIsAlreadyOnTopChangesNothing() {
        assertNull(LangPromotion.promote(orden, "Español Latino", listOf("Español Latino", "English")))
    }

    @Test fun promotingALanguageNotInTheListAddsIt() {
        val todas = listOf("Japanese", "English")
        assertEquals(
            listOf(TrackLang.JAPANESE, TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH),
            LangPromotion.promote(orden, "Japanese", todas),
        )
    }

    @Test fun subtitlesUseTheFileNameClassifier() {
        val todas = listOf("/x/movie.es.srt", "/x/movie.en.srt")
        assertEquals(
            listOf(TrackLang.ENGLISH, TrackLang.LATINO, TrackLang.CASTELLANO),
            LangPromotion.promote(orden, "/x/movie.en.srt", todas, LangTokens::classifyFileName),
        )
    }
}

class LangOrderEditsTest {

    private val orden = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.ENGLISH)

    @Test fun toggleRemovesWhenPresentAndAppendsWhenNot() {
        assertEquals(listOf(TrackLang.LATINO, TrackLang.ENGLISH), LangOrderEdits.toggle(orden, TrackLang.CASTELLANO))
        assertEquals(orden + TrackLang.JAPANESE, LangOrderEdits.toggle(orden, TrackLang.JAPANESE))
    }

    /** No se puede quedar sin idiomas: el último no se saca. */
    @Test fun toggleRefusesToEmptyTheList() {
        val uno = listOf(TrackLang.LATINO)
        assertEquals(uno, LangOrderEdits.toggle(uno, TrackLang.LATINO))
    }

    @Test fun moveUpAndDownSwapNeighbours() {
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveUp(orden, TrackLang.CASTELLANO),
        )
        assertEquals(
            listOf(TrackLang.CASTELLANO, TrackLang.LATINO, TrackLang.ENGLISH),
            LangOrderEdits.moveDown(orden, TrackLang.LATINO),
        )
    }

    @Test fun movingPastTheEdgesOrMovingAnAbsentLanguageIsANoOp() {
        assertEquals(orden, LangOrderEdits.moveUp(orden, TrackLang.LATINO))
        assertEquals(orden, LangOrderEdits.moveDown(orden, TrackLang.ENGLISH))
        assertEquals(orden, LangOrderEdits.moveUp(orden, TrackLang.JAPANESE))
    }
}
