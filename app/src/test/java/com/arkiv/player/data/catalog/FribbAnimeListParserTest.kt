package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FribbAnimeListParserTest {

    // Formas reales del dataset: imdb_id como array, themoviedb_id como objeto {tv:..},
    // season/episode_offset como objeto {tvdb:..}. Algunas entradas no traen season/offset.
    private val json = """
        [
          {"anilist_id":21,"mal_id":21,"tvdb_id":81797,"imdb_id":["tt0388629"],
           "themoviedb_id":{"tv":37854},"simkl_id":38636,"type":"tv"},
          {"anilist_id":110277,"tvdb_id":267440,"imdb_id":["tt2560140"],
           "themoviedb_id":{"tv":1429},"season":{"tvdb":4,"tmdb":4}},
          {"anilist_id":821,"tvdb_id":70900,"season":{"tvdb":0},"episode_offset":{"tvdb":2},"type":"OVA"},
          {"mal_id":999,"tvdb_id":123}
        ]
    """.trimIndent()

    @Test
    fun `indexa por anilist_id y omite entradas sin anilist`() {
        val map = FribbAnimeListParser.parse(json)
        assertEquals(3, map.size)          // la 4ª no tiene anilist_id
        assertNull(map[999L])
    }

    @Test
    fun `parsea cross-ids con imdb array y tmdb objeto tv`() {
        val m = FribbAnimeListParser.parse(json)[21L]!!
        assertEquals(81797L, m.tvdbId)
        assertEquals("tt0388629", m.imdbId)
        assertEquals(37854, m.tmdbId)
        assertEquals(38636L, m.simklId)
        assertNull(m.tvdbSeason)           // sin season
    }

    @Test
    fun `parsea season tvdb y offset cuando existen`() {
        val aot = FribbAnimeListParser.parse(json)[110277L]!!
        assertEquals(4, aot.tvdbSeason)
        assertNull(aot.episodeOffset)
        val ova = FribbAnimeListParser.parse(json)[821L]!!
        assertEquals(2, ova.episodeOffset)
        assertEquals(0, ova.tvdbSeason)
    }

    @Test
    fun `json invalido devuelve mapa vacio`() {
        assertTrue(FribbAnimeListParser.parse("no soy json").isEmpty())
    }
}
