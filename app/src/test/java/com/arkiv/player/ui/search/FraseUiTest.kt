package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.FraseInterpretada
import com.arkiv.player.data.gateway.GatewayObraDeFrase
import org.junit.Assert.assertEquals
import org.junit.Test

class FraseUiTest {

    @Test
    fun `una obra de frase se vuelve card abrible por el flujo normal`() {
        val card = GatewayObraDeFrase(103, "Angustia", "1987", "https://img/a.jpg", "movie").toTitleCard()
        assertEquals("movie", card.kind)
        assertEquals(103, card.tmdbId)
        assertEquals("Angustia", card.title)
        assertEquals("1987", card.year)
        assertEquals("https://img/a.jpg", card.posterUrl)
    }

    @Test
    fun `el tipo tv del gateway es series en la UI`() {
        // La UI distingue "movie"|"series"|"anime"; el gateway habla el idioma de TMDB
        // ("movie"|"tv"). Confundirlos rompe pickTitle: una serie abriría como película.
        assertEquals("series", GatewayObraDeFrase(9, "El Capo", "2009", "", "tv").toTitleCard().kind)
    }

    @Test
    fun `las etiquetas cuentan lo que el gateway entendio`() {
        val i = FraseInterpretada(
            tipo = "movie", generos = listOf("terror"),
            anioDesde = 1980, anioHasta = 1989, idioma = "es",
        )
        assertEquals(listOf("película", "terror", "1980–1989", "en español"), etiquetasDeInterpretacion(i))
    }

    @Test
    fun `sin anios ni idioma las etiquetas no inventan`() {
        val i = FraseInterpretada(tipo = "tv", generos = listOf("crimen", "drama"), anioDesde = null, anioHasta = null, idioma = "")
        assertEquals(listOf("serie", "crimen", "drama"), etiquetasDeInterpretacion(i))
    }

    @Test
    fun `un solo extremo de anio se dice como rango abierto`() {
        val desde = FraseInterpretada("movie", emptyList(), 2020, null, "")
        val hasta = FraseInterpretada("movie", emptyList(), null, 1999, "")
        assertEquals(listOf("película", "desde 2020"), etiquetasDeInterpretacion(desde))
        assertEquals(listOf("película", "hasta 1999"), etiquetasDeInterpretacion(hasta))
    }

    @Test
    fun `un idioma sin nombre conocido se muestra por su codigo`() {
        val i = FraseInterpretada("movie", emptyList(), null, null, "tl")
        assertEquals(listOf("película", "en tl"), etiquetasDeInterpretacion(i))
    }
}
