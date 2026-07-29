package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private val movieGenres = listOf(TmdbGenre(28, "Acción"), TmdbGenre(35, "Comedia"))
    private val tvGenres = listOf(TmdbGenre(16, "Animación"))
    private val animeGenres = listOf("Action")

    @Test fun `las filas fijas van en orden y antes de los generos`() {
        val ids = buildRowSpecs(movieGenres, tvGenres, emptyList()).map { it.id }
        assertEquals(
            listOf(
                "cartelera", "peliculas_populares", "tendencias", "series_populares", "series_top",
                "anime", "anime_populares", "anime_top",
            ),
            ids.take(8),
        )
    }

    @Test fun `agrega una fila por genero de pelicula y de serie`() {
        val specs = buildRowSpecs(movieGenres, tvGenres, animeGenres)
        assertTrue(specs.any { it.id == "g_movie_28" && it.source == RowSource.Discover("movie", 28) })
        assertTrue(specs.any { it.id == "g_movie_35" })
        assertTrue(specs.any { it.id == "g_tv_16" && it.source == RowSource.Discover("tv", 16) })
        assertEquals(8 + 3 + animeGenres.size, specs.size)
    }

    @Test fun `sin generos quedan solo las fijas`() {
        assertEquals(8, buildRowSpecs(emptyList(), emptyList(), emptyList()).size)
    }

    @Test fun `los ids son unicos`() {
        val specs = buildRowSpecs(movieGenres, tvGenres, animeGenres)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `un mismo id de genero en peliculas y series no colisiona`() {
        val shared = listOf(TmdbGenre(16, "Animación"))
        val specs = buildRowSpecs(shared, shared, emptyList())
        assertTrue(specs.any { it.id == "g_movie_16" })
        assertTrue(specs.any { it.id == "g_tv_16" })
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `populares apunta a POPULAR de peliculas`() {
        val spec = buildRowSpecs(emptyList(), emptyList(), emptyList()).first { it.id == "peliculas_populares" }
        assertEquals(RowSource.Curated("movie", TmdbCategory.POPULAR), spec.source)
    }

    @Test fun `no hay fila de proximamente (estrenos sin fuentes)`() {
        val sources = buildRowSpecs(emptyList(), emptyList(), emptyList()).map { it.source }
        assertTrue(sources.none { it == RowSource.Curated("movie", TmdbCategory.UPCOMING) })
    }

    @Test fun `los generos de anime generan su propia fila sin colisionar`() {
        val specs = buildRowSpecs(listOf(TmdbGenre(16, "Animación")), listOf(TmdbGenre(16, "Animación")), listOf("Action"))
        assertTrue(specs.any { it.id == "g_anime_action" && it.source == RowSource.Anime("POPULARITY_DESC", "Action") })
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test fun `ruta de atajo por tipo de card`() {
        val movie = TitleCard("movie", 42, null, "X", "", "2025", null)
        val series = TitleCard("series", 1399, null, "Y", "", "2011", null)
        val anime = TitleCard("anime", null, 20L, "Z", "", "1999", null)
        assertEquals("search?kind=movie&tmdbId=42", searchShortcutRoute(movie))
        assertEquals("search?kind=series&tmdbId=1399", searchShortcutRoute(series))
        assertEquals("search?kind=anime&anilistId=20", searchShortcutRoute(anime))
    }
}
