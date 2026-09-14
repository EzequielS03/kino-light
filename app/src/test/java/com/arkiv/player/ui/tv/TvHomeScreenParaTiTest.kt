package com.arkiv.player.ui.tv

import com.arkiv.player.data.db.RecomendacionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure logic behind the TV home's "Para ti" row: whether it's drawn or not
 * ([showForYouRow]) and what the hero shows on focusing a card ([recommendationFeatured]).
 * Compose for TV has no UI test infrastructure in this project (same reason as
 * `TvMagisLinkOfferTest`), so these functions -extracted outside the composable on purpose- are
 * the part that CAN be tested in a plain JVM.
 */
class TvHomeScreenParaTiTest {

    private fun recommendation(
        id: String = "r1",
        tmdbId: Int = 603,
        tipo: String = "movie",
        titulo: String = "Matrix",
        posterUrl: String = "https://image.tmdb.org/poster.jpg",
        porque: String = "porque terminaste Dragon Ball",
        ref: String = "magis:algo",
        orden: Int = 0,
    ) = RecomendacionEntity(
        id = id, tmdbId = tmdbId, tipo = tipo, titulo = titulo, posterUrl = posterUrl,
        porque = porque, ref = ref, orden = orden, generadoAt = 0L,
    )

    // --- showForYouRow: with nothing to show, neither the title nor a gap ---

    @Test fun `with no recommendations the row isn't shown`() {
        assertEquals(false, showForYouRow(emptyList()))
    }

    @Test fun `with at least one current recommendation the row is shown`() {
        assertEquals(true, showForYouRow(listOf(recommendation())))
    }

    // --- recommendationFeatured: what gets painted in the hero on focusing a card ---

    @Test fun `the reason goes in meta, same as Continuar viendo's chapter label`() {
        val f = recommendationFeatured(recommendation(porque = "porque terminaste Dragon Ball"))
        assertEquals("porque terminaste Dragon Ball", f.meta)
    }

    @Test fun `the title passes through as-is`() {
        val f = recommendationFeatured(recommendation(titulo = "El Padrino"))
        assertEquals("El Padrino", f.title)
    }

    @Test fun `type movie translates to Pelicula`() {
        val f = recommendationFeatured(recommendation(tipo = "movie"))
        assertEquals("Película", f.subtitle)
    }

    @Test fun `type tv translates to Serie`() {
        val f = recommendationFeatured(recommendation(tipo = "tv"))
        assertEquals("Serie", f.subtitle)
    }

    @Test fun `an empty posterUrl falls back to null, not a blank URL`() {
        val f = recommendationFeatured(recommendation(posterUrl = ""))
        assertNull(f.imageUrl)
    }

    @Test fun `a posterUrl with content is kept`() {
        val f = recommendationFeatured(recommendation(posterUrl = "https://image.tmdb.org/poster.jpg"))
        assertEquals("https://image.tmdb.org/poster.jpg", f.imageUrl)
    }
}
