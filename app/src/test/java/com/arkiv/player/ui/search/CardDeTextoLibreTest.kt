package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El botón "Buscar" del buscador del TV manda el texto del buscador directo a las fuentes, sin
 * pasar por la ficha de TMDB (venga de teclearlo o de autocompletarlo con una card).
 * [cardDeTextoLibre] es la pieza que traduce ese texto a la card que el resto del wizard ya sabe
 * manejar.
 */
class CardDeTextoLibreTest {

    @Test fun `texto vacio no busca nada`() {
        assertNull(cardDeTextoLibre(""))
    }

    @Test fun `solo espacios no busca nada`() {
        assertNull(cardDeTextoLibre("   \t "))
    }

    @Test fun `el texto queda como titulo, sin espacios de sobra`() {
        assertEquals("gladiador", cardDeTextoLibre("  gladiador  ")?.title)
    }

    /** Sin ids el gateway busca por texto en las cuatro fuentes; con ids desempataría por tmdb_id
     *  y volvería a atarnos al título exacto, que es justo lo que "Ir" evita. */
    @Test fun `no lleva ids de catalogo`() {
        val card = cardDeTextoLibre("el padrino")
        assertNull(card?.tmdbId)
        assertNull(card?.anilistId)
    }

    /** "movie" no filtra nada en el gateway (con season/episode en 0 las cuatro fuentes devuelven
     *  pelis y series por igual) y hace que atrás desde las fuentes vuelva al teclado en vez de
     *  caer en el selector de temporadas de una card que no existe. */
    @Test fun `es una card de pelicula, para que atras vuelva al teclado`() {
        assertEquals("movie", cardDeTextoLibre("el padrino")?.kind)
    }

    @Test fun `no inventa poster, anio ni sinopsis`() {
        val card = cardDeTextoLibre("el padrino")
        assertEquals("", card?.posterUrl)
        assertEquals("", card?.year)
        assertNull(card?.overview)
    }
}
