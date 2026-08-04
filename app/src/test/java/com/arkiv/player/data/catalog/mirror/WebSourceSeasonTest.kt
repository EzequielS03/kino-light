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
}
