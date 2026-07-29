package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleFilterTest {

    @Test fun detectsSamplesAndExtras() {
        assertTrue(SampleFilter.isJunk("sample.mkv"))
        assertTrue(SampleFilter.isJunk("Movie.2020.1080p-GROUP.sample.mkv"))
        assertTrue(SampleFilter.isJunk("Extras/featurette.mkv"))
        assertTrue(SampleFilter.isJunk("The.Movie.trailer.mp4"))
        assertTrue(SampleFilter.isJunk("RARBG.com.mp4"))
        assertTrue(SampleFilter.isJunk("behind-the-scenes.mkv"))
    }

    @Test fun keepsRealTitles() {
        assertFalse(SampleFilter.isJunk("The.Matrix.1999.1080p.BluRay.x264.mkv"))
        assertFalse(SampleFilter.isJunk("One Piece - 1158 (1080p).mkv"))
        // "trailer" como parte de una palabra real de un título no debe disparar.
        assertFalse(SampleFilter.isJunk("Trailerpark.S01E01.mkv"))
    }

    @Test fun cleanRemovesJunkButKeepsMainVideo() {
        val files = listOf(0 to "Movie.2020.1080p.mkv", 1 to "Sample/movie-sample.mkv")
        assertEquals(listOf(0 to "Movie.2020.1080p.mkv"), SampleFilter.clean(files))
    }

    @Test fun cleanKeepsAllWhenEverythingLooksLikeJunk() {
        // Falso positivo total → no dejar la lista vacía.
        val files = listOf(0 to "sample.mkv", 1 to "trailer.mkv")
        assertEquals(files, SampleFilter.clean(files))
    }
}
