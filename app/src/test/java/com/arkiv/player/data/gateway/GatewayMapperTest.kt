package com.arkiv.player.data.gateway

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayMapperTest {

    private fun torrent(lang: String = "LATINO", extra: Map<String, String> = mapOf("infohash" to "aa")) =
        GatewayResult(
            source = "torrent", title = "Duna (2021) 4k", ref = "r",
            lang = lang, seeders = 42, sizeBytes = 123, extra = extra,
        )

    @Test
    fun `torrent conserva idioma seeds tamano e infohash`() {
        val ps = torrent().toPlaySource() as PlaySource.Torrent
        assertEquals(TorrentLang.LATINO, ps.result.lang)
        assertEquals(42, ps.result.seeders)
        assertEquals(123L, ps.result.sizeBytes)
        assertEquals("aa", ps.result.infoHash)
        assertEquals("Duna (2021) 4k", ps.result.name)
    }

    @Test
    fun `castellano y jap_sub se mapean a su enum`() {
        assertEquals(
            TorrentLang.CASTELLANO,
            (torrent(lang = "CASTELLANO").toPlaySource() as PlaySource.Torrent).result.lang,
        )
        assertEquals(
            TorrentLang.JAP_SUB,
            (torrent(lang = "JAP_SUB").toPlaySource() as PlaySource.Torrent).result.lang,
        )
    }

    @Test
    fun `un idioma que la app no conoce no rompe el mapeo`() {
        assertTrue(torrent(lang = "KLINGON").toPlaySource() is PlaySource.Torrent)
    }

    @Test
    fun `un torrent sin infohash igual se mapea`() {
        // Los resultados solo-Link de Jackett no traen infohash: se resuelven al reproducir.
        val ps = torrent(extra = emptyMap()).toPlaySource() as PlaySource.Torrent
        assertNull(ps.result.infoHash)
    }

    @Test
    fun `archive conserva identificador titulo y anio`() {
        val ps = GatewayResult(
            source = "archive", title = "Duna", ref = "r", year = "2021",
            extra = mapOf("identifier" to "mi-item"),
        ).toPlaySource() as PlaySource.Archive
        assertEquals("mi-item", ps.item.identifier)
        assertEquals("Duna", ps.item.title)
        assertEquals("2021", ps.item.year)
    }

    @Test
    fun `web conserva sitio idioma y temporada-capitulo`() {
        val ps = GatewayResult(
            source = "web", title = "Piloto", ref = "r", lang = "LATINO", quality = "1080p",
            season = 1, episode = 1, kind = "tv", extra = mapOf("site_id" to "serieskao"),
        ).toPlaySource() as PlaySource.Web
        assertEquals("serieskao", ps.result.siteId)
        assertEquals("LATINO", ps.result.language)
        assertEquals("1080p", ps.result.quality)
        assertEquals(1, ps.result.season)
        assertEquals(1, ps.result.episode)
        assertEquals("tv", ps.result.kind)
    }

    @Test
    fun `magis se mapea a su propio tipo`() {
        // Sin esta rama el mapper devolvia null y los resultados de Magis nunca llegaban a la
        // pantalla, aunque el gateway los estuviera entregando.
        val ps = GatewayResult(
            source = "magis", title = "Duna", ref = "r", year = "2021",
            extra = mapOf("content_id" to "abc", "program_type" to "movie"),
        ).toPlaySource()
        assertTrue(ps is PlaySource.Magis)
        assertEquals("Duna", (ps as PlaySource.Magis).result.title)
        assertEquals("abc", ps.result.extra["content_id"])
        assertEquals("r", ps.result.ref)
    }

    @Test
    fun `las cuatro fuentes del gateway se mapean- ninguna cae en null`() {
        // Guarda contra el bug real: el gateway sirve cuatro fuentes y el mapper conocia tres.
        for (fuente in listOf("torrent", "archive", "web", "magis")) {
            val r = GatewayResult(source = fuente, title = "x", ref = "r")
            assertTrue("la fuente '$fuente' no se mapea", r.toPlaySource() != null)
        }
    }

    @Test
    fun `una fuente desconocida se descarta sin romper`() {
        // Si el servidor agrega una fuente que este APK no conoce, se ignora en vez de fallar.
        assertNull(GatewayResult(source = "fuente_nueva", title = "x", ref = "r").toPlaySource())
    }

    @Test
    fun `el ref sobrevive al mapeo en las tres fuentes`() {
        // Sin esto no se puede resolver despues: /v1/resolve solo entiende el ref.
        assertEquals("r", (torrent().toPlaySource() as PlaySource.Torrent).result.gatewayRef)
        assertEquals(
            "r",
            (GatewayResult(source = "archive", title = "x", ref = "r").toPlaySource()
                as PlaySource.Archive).item.gatewayRef,
        )
        assertEquals(
            "r",
            (GatewayResult(source = "web", title = "x", ref = "r").toPlaySource()
                as PlaySource.Web).result.gatewayRef,
        )
    }

    // ─── Identidad de los resultados web ────────────────────────────────────
    // La lista de fuentes deduplica por la identidad del resultado. Los web del gateway no traen
    // `pageUrl` (la página se resuelve al reproducir), así que si la identidad dependiera solo de
    // esa URL los 36 resultados de un título colapsarían en UNO y la pestaña Web mostraría 1.

    private fun web(ref: String) = GatewayResult(
        source = "web", title = "Loki 1x1", ref = ref, extra = mapOf("site_id" to "cuevana"),
    )

    @Test
    fun `dos web del gateway no comparten identidad`() {
        val a = (web("ref-a").toPlaySource() as PlaySource.Web).result
        val b = (web("ref-b").toPlaySource() as PlaySource.Web).result
        assertTrue(a.identity != b.identity)
    }

    @Test
    fun `un web del scraping local sigue identificandose por su pagina`() {
        val local = com.arkiv.player.data.catalog.web.WebResult(
            siteId = "cuevana", siteName = "Cuevana", title = "Loki 1x1",
            year = "2021", pageUrl = "https://cuevana/loki-1x1", posterUrl = "",
            language = "", quality = "", kind = "tv",
        )
        assertEquals("https://cuevana/loki-1x1", local.identity)
    }
}
