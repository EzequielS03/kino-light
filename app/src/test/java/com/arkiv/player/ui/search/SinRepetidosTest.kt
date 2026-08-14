package com.arkiv.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La grilla de títulos junta TMDB y AniList, así que todo lo que es anime y además está en TMDB
 * salía dos veces ("evangelion" es el caso de manual). Como ahora la grilla es el autocompletador
 * del buscador, un repetido no aporta nada: es la misma palabra dos veces.
 */
class SinRepetidosTest {

    private fun tmdb(title: String, year: String = "1995") =
        TitleCard("series", 1, null, title, "", year, null)

    private fun anime(title: String, year: String = "1995") =
        TitleCard("anime", null, 30L, title, "", year, null)

    @Test fun `lista vacia`() {
        assertEquals(emptyList<TitleCard>(), sinRepetidos(emptyList()))
    }

    @Test fun `el mismo titulo en TMDB y en AniList queda una sola vez`() {
        val salida = sinRepetidos(listOf(tmdb("Neon Genesis Evangelion"), anime("Neon Genesis Evangelion")))
        assertEquals(listOf("Neon Genesis Evangelion"), salida.map { it.title })
    }

    /** Gana el primero: la lista llega como `tmdb + anime`, y el orden que ya se ve no cambia. */
    @Test fun `gana el primero de la lista`() {
        assertEquals("series", sinRepetidos(listOf(tmdb("Evangelion"), anime("Evangelion"))).single().kind)
        assertEquals("anime", sinRepetidos(listOf(anime("Evangelion"), tmdb("Evangelion"))).single().kind)
    }

    @Test fun `mayusculas, acentos y puntuacion no hacen diferencia`() {
        val salida = sinRepetidos(listOf(tmdb("El Padrino"), anime("el padrino"), tmdb("¡El  PADRINO!")))
        assertEquals(1, salida.size)
    }

    /** Dos películas con el mismo nombre y distinto año son dos películas distintas (remakes). */
    @Test fun `mismo titulo con anios distintos NO se junta`() {
        val salida = sinRepetidos(listOf(tmdb("It", "1990"), tmdb("It", "2017")))
        assertEquals(listOf("1990", "2017"), salida.map { it.year })
    }

    @Test fun `titulos distintos no se tocan y conservan el orden`() {
        val entrada = listOf(tmdb("Evangelion 1.0"), tmdb("Evangelion 2.0"), anime("Evangelion 3.0"))
        assertEquals(entrada.map { it.title }, sinRepetidos(entrada).map { it.title })
    }

    /** Un título vacío no tiene con qué compararse: se deja pasar en vez de colapsar todos en uno. */
    @Test fun `los titulos vacios no se colapsan entre si`() {
        val salida = sinRepetidos(listOf(tmdb(""), anime("")))
        assertEquals(2, salida.size)
    }
}
