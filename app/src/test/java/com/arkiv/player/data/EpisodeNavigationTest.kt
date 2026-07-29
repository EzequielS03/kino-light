package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNavigationTest {

    private val serie = listOf(
        NavEpisode("t1e1", "Temporada 1"),
        NavEpisode("t1e2", "Temporada 1"),
        NavEpisode("t1e3", "Temporada 1"),
        NavEpisode("t2e1", "Temporada 2"),
    )

    @Test
    fun `next devuelve el siguiente de la misma seccion`() {
        assertEquals("t1e2", EpisodeNavigation.nextId(serie, "t1e1"))
    }

    @Test
    fun `next no cruza de seccion`() {
        assertNull(EpisodeNavigation.nextId(serie, "t1e3"))
    }

    @Test
    fun `prev devuelve el anterior de la misma seccion`() {
        assertEquals("t1e2", EpisodeNavigation.prevId(serie, "t1e3"))
    }

    @Test
    fun `prev no cruza de seccion`() {
        assertNull(EpisodeNavigation.prevId(serie, "t2e1"))
    }

    @Test
    fun `episodio desconocido devuelve null`() {
        assertNull(EpisodeNavigation.nextId(serie, "nope"))
        assertNull(EpisodeNavigation.prevId(serie, "nope"))
    }

    @Test
    fun `pelicula de un solo episodio no tiene vecinos`() {
        val peli = listOf(NavEpisode("solo", ""))
        assertNull(EpisodeNavigation.nextId(peli, "solo"))
        assertNull(EpisodeNavigation.prevId(peli, "solo"))
    }
}
