package com.arkiv.player.data

import com.arkiv.player.data.catalog.TmdbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuál de los resultados de TMDB es el arte de un ítem de la biblioteca.
 *
 * El bug real: `ensureArtwork` se quedaba con `results.first()` y TMDB ordena por su score de
 * relevancia, no por coincidencia exacta. Para `search/tv?query=Dragon Ball` devuelve "Dragon Ball
 * Z" de primero y el "Dragon Ball" de 1986 en la posición 7 de 9, así que los tres Dragon Ball de
 * la biblioteca quedaron con el `tmdbId` de Z: con la carátula de Z y —peor— fundidos en UNA sola
 * tarjeta, porque [LibraryGrouping] agrupa las series por `tv:<tmdbId>`.
 *
 * Las listas de acá son las respuestas REALES del gateway (es-MX, 2026-08-11), no inventadas: el
 * orden es justamente lo que está en discusión.
 */
class PickTmdbMatchTest {

    private fun tv(id: Int, title: String, original: String, year: String) =
        TmdbItem(id = id, type = "tv", title = title, originalTitle = original, posterUrl = "", year = year)

    /** Respuesta real de `search/tv?query=Dragon Ball`, en su orden real. */
    private val dragonBall = listOf(
        tv(12971, "Dragon Ball Z", "ドラゴンボールゼット", "1989"),
        tv(236994, "Dragon Ball Daima", "ドラゴンボールDAIMA", "2024"),
        tv(80020, "Dragon Ball Heroes", "スーパードラゴンボールヒーローズ", "2018"),
        tv(62715, "Dragon Ball Super", "ドラゴンボール超（スーパー）", "2015"),
        tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"),
        tv(12697, "Dragon Ball GT", "ドラゴンボールGT", "1996"),
        tv(12609, "Dragon Ball", "ドラゴンボール", "1986"),
        tv(330965, "DragonBall Z Abridged", "DragonBall Z Abridged", "2008"),
    )

    @Test
    fun `elige la coincidencia exacta aunque TMDB la mande al fondo`() {
        assertEquals(12609, pickTmdbMatch("Dragon Ball", dragonBall)?.item?.id)
    }

    /**
     * El mismo caso al revés, para que la regla sea "gana el título exacto" y no "gana el más
     * corto": con el de 1986 de primero, buscar "Dragon Ball Z" tiene que seguir dando Z.
     */
    @Test
    fun `la coincidencia exacta gana tambien cuando el titulo corto va primero`() {
        assertEquals(12971, pickTmdbMatch("Dragon Ball Z", dragonBall.reversed())?.item?.id)
    }

    /**
     * Sin coincidencia exacta se conserva el comportamiento viejo (el primero), que es la mejor
     * apuesta que queda: la biblioteca tiene "Dragon Ball Kai" y TMDB lo llama "Dragon Ball Z Kai".
     */
    @Test
    fun `sin coincidencia exacta cae al primer resultado`() {
        val kai = listOf(tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"))
        assertEquals(61709, pickTmdbMatch("Dragon Ball Kai", kai)?.item?.id)
    }

    /**
     * TMDB devuelve el título en es-MX, pero los releases suelen venir con el original en inglés.
     * Sin mirar `originalTitle`, "The Simpsons" no coincidiría con "Los Simpson" y caería al
     * primero por accidente (que acá sí es el bueno, por eso el orden está alterado a propósito).
     */
    @Test
    fun `tambien matchea contra el titulo original`() {
        val simpsons = listOf(
            tv(304530, "Fortnite x Los Simpson", "Fortnite x The Simpsons", "2025"),
            tv(456, "Los Simpson", "The Simpsons", "1989"),
        )
        assertEquals(456, pickTmdbMatch("The Simpsons", simpsons)?.item?.id)
    }

    /** Normalización: tildes, mayúsculas y puntuación no deben romper la coincidencia exacta. */
    @Test
    fun `la coincidencia exacta ignora tildes mayusculas y puntuacion`() {
        val list = listOf(
            tv(1, "Otra Cosa", "Something Else", "2020"),
            tv(2, "El Señor de los Cielos", "El Señor de los Cielos", "2013"),
        )
        assertEquals(2, pickTmdbMatch("el senor de los cielos!", list)?.item?.id)
    }

    /**
     * Un título que al normalizarlo queda vacío (japonés, cirílico) haría match "exacto" contra
     * cualquier original que también normalice a vacío — que es casi todo el anime. Ahí no hay
     * coincidencia que valga: se cae al primero.
     */
    @Test
    fun `un titulo sin caracteres latinos no inventa coincidencia exacta`() {
        assertEquals(12971, pickTmdbMatch("ドラゴンボール", dragonBall)?.item?.id)
    }

    @Test
    fun `sin resultados no hay match`() {
        assertNull(pickTmdbMatch("Lo Que Sea", emptyList()))
    }

    @Test
    fun `un match por titulo igual se marca exacto`() {
        assertTrue(pickTmdbMatch("Dragon Ball", dragonBall)!!.exact)
    }

    @Test
    fun `un match por descarte NO se marca exacto`() {
        // Este es el que importa. El primer resultado sirve para sacarle un backdrop
        // decente a "Dragon Ball Kai", pero NO es identidad: `ensureArtwork` guardaba
        // ese id y `LibraryGrouping` agrupa por el, asi que un titulo que TMDB no
        // conoce -- "Construido por los hombres" -- se llevaba el id del primer
        // resultado que cayera y fundia dos obras sin relacion en una tarjeta.
        val kai = listOf(tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"))
        val m = pickTmdbMatch("Dragon Ball Kai", kai)
        assertEquals(61709, m?.item?.id)
        assertFalse(m!!.exact)
    }

    @Test
    fun `sin query util el primer resultado tampoco es exacto`() {
        // Query en blanco tras limpiar: se devuelve algo para el arte, pero no hay
        // NADA con que afirmar que es la misma obra.
        val m = pickTmdbMatch("", dragonBall)
        assertEquals(12971, m?.item?.id)
        assertFalse(m!!.exact)
    }
}
