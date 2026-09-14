package com.arkiv.player.ui.tv

import com.arkiv.player.data.db.RecomendacionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cubre la lógica pura detrás de la fila "Para ti" del inicio de TV: si se dibuja o no
 * ([mostrarFilaParaTi]) y qué muestra el hero al enfocar una tarjeta ([recommendationFeatured]).
 * Compose para TV no tiene infraestructura de tests de UI en este proyecto (mismo motivo que
 * `TvMagisLinkOfferTest`), así que estas funciones -extraídas fuera del composable a
 * propósito- son la parte que sí se puede probar en un JVM plano.
 */
class TvHomeScreenParaTiTest {

    private fun recomendacion(
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

    // --- mostrarFilaParaTi: sin nada que mostrar, ni el título ni un hueco ---

    @Test fun `sin recomendaciones no se muestra la fila`() {
        assertEquals(false, mostrarFilaParaTi(emptyList()))
    }

    @Test fun `con al menos una recomendacion vigente se muestra la fila`() {
        assertEquals(true, mostrarFilaParaTi(listOf(recomendacion())))
    }

    // --- recommendationFeatured: qué se pinta en el hero al enfocar una tarjeta ---

    @Test fun `el porque va en meta, igual que la etiqueta de capitulo de Continuar viendo`() {
        val f = recommendationFeatured(recomendacion(porque = "porque terminaste Dragon Ball"))
        assertEquals("porque terminaste Dragon Ball", f.meta)
    }

    @Test fun `el titulo pasa tal cual`() {
        val f = recommendationFeatured(recomendacion(titulo = "El Padrino"))
        assertEquals("El Padrino", f.title)
    }

    @Test fun `tipo movie se traduce a Pelicula`() {
        val f = recommendationFeatured(recomendacion(tipo = "movie"))
        assertEquals("Película", f.subtitle)
    }

    @Test fun `tipo tv se traduce a Serie`() {
        val f = recommendationFeatured(recomendacion(tipo = "tv"))
        assertEquals("Serie", f.subtitle)
    }

    @Test fun `posterUrl vacio cae a null, no a una URL en blanco`() {
        val f = recommendationFeatured(recomendacion(posterUrl = ""))
        assertNull(f.imageUrl)
    }

    @Test fun `posterUrl con datos se conserva`() {
        val f = recommendationFeatured(recomendacion(posterUrl = "https://image.tmdb.org/poster.jpg"))
        assertEquals("https://image.tmdb.org/poster.jpg", f.imageUrl)
    }
}
