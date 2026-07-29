package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListGenreTest {
    @Test fun `query incluye genre_in solo cuando hay genero`() {
        val withGenre = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = true)
        assertTrue(withGenre.contains("genre_in"))
        assertTrue(withGenre.contains("\$genre"))
        val without = buildAnimeBrowseQuery("TRENDING_DESC", hasSearch = false, hasGenre = false)
        assertFalse(without.contains("genre_in"))
    }
}
