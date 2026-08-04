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
                src("sitioA", 2, 105, "http://a/s2e5"),
            ),
        ),
        MirrorWebPack("sitioB", "Shingeki no Kyojin", listOf(src("sitioB", 3, 12, "http://b/s3e12"))),
    )

    @Test fun `usa el episodio real del mirror para esa pageUrl`() {
        assertEquals(105, WebSourceEpisode.forPageUrl(packs, "http://a/s2e5", fallback = 99))
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

    // --- forResult: el episodio viaja con el resultado (mirror) ---

    private fun webResult(url: String, episode: Int?) = com.arkiv.player.data.catalog.web.WebResult(
        siteId = "sitioA", siteName = "sitioA", title = "Ep 5", year = "", pageUrl = url,
        posterUrl = "", language = "lat", kind = "tv", episode = episode,
    )

    @Test fun `forResult usa el episodio del propio resultado SIN packs cargados`() {
        // El caso del buscador: SearchViewModel.runSourceSearch nunca emite WebPack junto a Web, así
        // que la lista de packs siempre llega vacía por ese camino.
        assertEquals(1071, WebSourceEpisode.forResult(webResult("http://c/abs1071", 1071), fallback = 5))
    }

    @Test fun `forResult prefiere el del resultado sobre el de los packs`() {
        assertEquals(105, WebSourceEpisode.forResult(webResult("http://a/s2e5", 105), packs, fallback = 99))
    }

    @Test fun `forResult cae a los packs cuando el resultado no trae episodio`() {
        assertEquals(12, WebSourceEpisode.forResult(webResult("http://b/s3e12", null), packs, fallback = 99))
    }

    @Test fun `forResult cae al fallback dado con scraping en vivo y sin packs`() {
        assertEquals(7, WebSourceEpisode.forResult(webResult("http://otro/sitio/ep7", null), fallback = 7))
    }
}
