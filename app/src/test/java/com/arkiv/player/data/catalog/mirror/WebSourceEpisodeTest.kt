package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceEpisodeTest {

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
        MirrorWebPack("sitioB", "Shingeki no Kyojin", listOf(src("sitioB", 3, 12, "http://b/s3e12"))),
    )

    @Test fun `usa el episodio real del mirror para esa pageUrl`() {
        assertEquals(5, WebSourceEpisode.forPageUrl(packs, "http://a/s2e5", fallback = 99))
    }

    @Test fun `busca en todos los packs, no solo en el primero`() {
        assertEquals(12, WebSourceEpisode.forPageUrl(packs, "http://b/s3e12", fallback = 99))
    }

    @Test fun `cae al fallback dado cuando ningun pack conoce la url (scraping en vivo)`() {
        assertEquals(7, WebSourceEpisode.forPageUrl(packs, "http://otro/sitio/ep7", fallback = 7))
    }

    @Test fun `cae al fallback dado sin packs cargados`() {
        assertEquals(7, WebSourceEpisode.forPageUrl(emptyList(), "http://a/s2e5", fallback = 7))
    }

    @Test fun `resuelve numeracion absoluta del mirror distinta a la de AniList`() {
        // El usuario tocó "Ep 5" en la grilla de AniList (temporada actual), pero el sitio numera
        // absoluto para series de larga duración: debe ganar el 1071 del mirror, no el 5 que llega
        // como fallback.
        val onePiece = listOf(
            MirrorWebPack("sitioC", "One Piece", listOf(src("sitioC", 1, 1071, "http://c/abs1071"))),
        )
        assertEquals(1071, WebSourceEpisode.forPageUrl(onePiece, "http://c/abs1071", fallback = 5))
    }
}
