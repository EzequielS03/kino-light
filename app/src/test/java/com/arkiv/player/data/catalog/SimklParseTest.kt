package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimklParseTest {
    @Test
    fun `parseSearch toma el simkl id del primer match`() {
        val json = """[{"type":"anime","ids":{"simkl":38636,"slug":"one-piece"},"total_episodes":1176}]"""
        assertEquals(38636L, SimklParser.parseSearch(json))
    }

    @Test
    fun `parseSearch vacio devuelve null`() {
        assertNull(SimklParser.parseSearch("[]"))
    }

    @Test
    fun `parseDetail extrae episodios, cross-ids y alt_titles`() {
        val json = """
            {"ids":{"simkl":38636,"imdb":"tt0388629","tvdb":"81797","tmdb":"37854","anilist":"21"},
             "en_title":"One Piece","total_episodes":1176,
             "alt_titles":[{"name":"Wan Piisu"},{"name":"ワンピース"}]}
        """.trimIndent()
        val info = SimklParser.parseDetail(json)!!
        assertEquals(38636L, info.simklId)
        assertEquals(1176, info.totalEpisodes)
        assertEquals("tt0388629", info.imdbId)
        assertEquals(37854, info.tmdbId)
        assertEquals(81797L, info.tvdbId)
        assertEquals(listOf("Wan Piisu", "ワンピース"), info.altTitles)
    }
}
