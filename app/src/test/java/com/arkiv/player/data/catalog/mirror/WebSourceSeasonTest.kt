package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceSeasonTest {

    private fun src(site: String, season: Int, episode: Int, url: String) =
        MirrorWebSource(site, url, season, episode, "Ep $episode", "1080p", "lat")

    private val packs = listOf(
        MirrorWebPack(
            "sitioA", "Shingeki no Kyojin",
            listOf(
                src("sitioA", 1, 5, "http://a/s1e5"),
                src("sitioA", 2, 5, "http://a/s2e5"),
            ),
        ),
        MirrorWebPack("sitioB", "Shingeki no Kyojin", listOf(src("sitioB", 3, 5, "http://b/s3e5"))),
    )

    @Test fun `usa la temporada real del mirror para esa pageUrl`() {
        assertEquals(2, WebSourceSeason.forPageUrl(packs, "http://a/s2e5"))
    }

    @Test fun `no confunde episodios con el mismo numero en distintas temporadas`() {
        assertEquals(1, WebSourceSeason.forPageUrl(packs, "http://a/s1e5"))
        assertEquals(2, WebSourceSeason.forPageUrl(packs, "http://a/s2e5"))
    }

    @Test fun `busca en todos los packs, no solo en el primero`() {
        assertEquals(3, WebSourceSeason.forPageUrl(packs, "http://b/s3e5"))
    }

    @Test fun `cae al fallback cuando ningun pack conoce la url (scraping en vivo)`() {
        assertEquals(1, WebSourceSeason.forPageUrl(packs, "http://otro/sitio/ep5"))
    }

    @Test fun `cae al fallback sin packs cargados`() {
        assertEquals(1, WebSourceSeason.forPageUrl(emptyList(), "http://a/s2e5"))
    }

    // --- forResult: la temporada viaja con el resultado (mirror) ---

    private fun webResult(url: String, season: Int?) = com.arkiv.player.data.catalog.web.WebResult(
        siteId = "sitioA", siteName = "sitioA", title = "Ep 5", year = "", pageUrl = url,
        posterUrl = "", language = "lat", kind = "tv", season = season,
    )

    @Test fun `forResult usa la temporada del propio resultado SIN packs cargados`() {
        // El caso del buscador: SearchViewModel.runSourceSearch nunca emite WebPack junto a Web,
        // así que la lista de packs siempre llega vacía por ese camino.
        assertEquals(2, WebSourceSeason.forResult(webResult("http://a/s2e5", 2)))
    }

    @Test fun `forResult prefiere la del resultado sobre la de los packs`() {
        assertEquals(2, WebSourceSeason.forResult(webResult("http://a/s2e5", 2), packs))
    }

    @Test fun `forResult cae a los packs cuando el resultado no trae temporada`() {
        assertEquals(3, WebSourceSeason.forResult(webResult("http://b/s3e5", null), packs))
    }

    @Test fun `forResult cae al fallback con scraping en vivo y sin packs`() {
        assertEquals(1, WebSourceSeason.forResult(webResult("http://otro/sitio/ep5", null)))
    }
}
