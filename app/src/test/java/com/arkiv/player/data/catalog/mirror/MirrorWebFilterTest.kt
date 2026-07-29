package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorWebFilterTest {
    private fun w(season: Int, episode: Int, tag: String = "") =
        MirrorWebSource(siteId = "serieskao", pageUrl = "https://x/$tag", season = season,
            episode = episode, name = tag, quality = "", langNorm = "latino")

    @Test fun `movie devuelve todas sin filtrar`() {
        val list = listOf(w(1, 1, "a"), w(2, 3, "b"))
        val r = MirrorWebFilter.select(list, ContentType.MOVIE, 0, emptySet())
        assertEquals(setOf("a", "b"), r.map { it.name }.toSet())
    }

    @Test fun `serie filtra por temporada y episodio exactos`() {
        val list = listOf(
            w(1, 1, "ok"),
            w(2, 1, "otraTemp"),
            w(1, 2, "otroEp"),
        )
        val r = MirrorWebFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(1))
        assertEquals(listOf("ok"), r.map { it.name })
    }

    // Buscar "capítulo 1" SIN elegir temporada (season=0) tiene que devolver ese capítulo de
    // cualquier temporada, no vacío: es la búsqueda más natural y la que hace el usuario.
    @Test fun `serie sin temporada elegida matchea el episodio en cualquier temporada`() {
        val list = listOf(w(1, 1, "t1"), w(2, 1, "t2"), w(1, 2, "otroEp"))
        val r = MirrorWebFilter.select(list, ContentType.TV, season = 0, episodeNumbers = setOf(1))
        assertEquals(setOf("t1", "t2"), r.map { it.name }.toSet())
    }

    @Test fun `anime matchea nº absoluto o relativo sin exigir temporada`() {
        val list = listOf(
            w(1, 1085, "abs"),
            w(21, 5, "rel"),
            w(1, 3, "no"),
        )
        val r = MirrorWebFilter.select(list, ContentType.ANIME, season = 0, episodeNumbers = setOf(5, 1085))
        assertEquals(setOf("abs", "rel"), r.map { it.name }.toSet())
    }
}
