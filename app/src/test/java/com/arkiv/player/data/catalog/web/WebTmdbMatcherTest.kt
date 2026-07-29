package com.arkiv.player.data.catalog.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTmdbMatcherTest {
    private fun web(title: String, year: String, kind: String = "movie") = WebResult(
        siteId = "s", siteName = "S", title = title, year = year, pageUrl = "https://s/x",
        posterUrl = "https://s/p.jpg", language = "LAT", kind = kind,
    )
    private val hits = listOf(
        TmdbHit(id = 354912, title = "Coco", year = "2017", posterUrl = "https://tmdb/coco.jpg"),
    )
    private fun matcher() = WebTmdbMatcher(lookup = { _, _ -> hits })

    @Test fun `normalize quita tildes puntuacion y parentesis de anio`() {
        assertEquals("coco", WebTmdbMatcher.normalize("¡Coco! (2017)"))
        assertEquals("el laberinto del fauno", WebTmdbMatcher.normalize("El Laberinto del Fauno"))
    }

    @Test fun `match confiable setea tmdbId y hereda poster`() = runBlocking {
        val out = matcher().enrich(web("Coco", "2017"))
        assertEquals(354912, out.tmdbId)
        assertEquals("https://tmdb/coco.jpg", out.posterUrl)
    }

    @Test fun `sin match conserva datos de la web y tmdbId null`() = runBlocking {
        val out = WebTmdbMatcher(lookup = { _, _ -> emptyList() }).enrich(web("Peli Rara", "1999"))
        assertNull(out.tmdbId)
        assertEquals("https://s/p.jpg", out.posterUrl) // póster original
    }

    @Test fun `no matchea si el anio difiere mas de uno`() = runBlocking {
        val out = matcher().enrich(web("Coco", "2005"))
        assertNull(out.tmdbId)
    }

    @Test fun `matchea con anio faltante en la web`() = runBlocking {
        val out = matcher().enrich(web("Coco", ""))
        assertEquals(354912, out.tmdbId) // sin año, basta el título normalizado
    }
}
