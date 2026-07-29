package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffRouteTest {
    private fun tmdb(kind: String) = TitleCard(kind, 42, null, "X", "", "2025", null)
    private fun anime() = TitleCard("anime", null, 100L, "Y", "", "2020", null)

    @Test fun `pelicula sin season episode`() {
        assertEquals("cine/movie/42", handoffRouteFor(tmdb("movie"), null, null))
    }
    @Test fun `serie sin season episode`() {
        assertEquals("cine/tv/42", handoffRouteFor(tmdb("series"), null, null))
    }
    @Test fun `serie con season y episode`() {
        assertEquals("cine/tv/42?season=2&episode=5", handoffRouteFor(tmdb("series"), 2, 5))
    }
    @Test fun `anime sin episode`() {
        assertEquals("catalog_anime/100", handoffRouteFor(anime(), null, null))
    }
    @Test fun `anime con episode`() {
        assertEquals("catalog_anime/100?episode=7", handoffRouteFor(anime(), null, 7))
    }
}
