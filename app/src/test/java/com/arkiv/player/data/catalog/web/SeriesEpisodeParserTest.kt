package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesEpisodeParserTest {

    private val base = "https://serieskao.top"

    // Reglas de serieskao (las mismas que van en web_sources.json).
    private val serieskao = WebEpisodeRules(
        regex = """<a[^>]+href="([^"]*?/temporada/(\d+)/capitulo/(\d+))"[^>]*>(.*?)</a>""",
        fields = listOf("pageUrl", "season", "episode", "title"),
    )

    // --- Lógica pura, con fixture mínimo controlado ---

    @Test
    fun `extrae temporada, capitulo, titulo y url absoluta`() {
        val html = """
            <a href="/anime/naruto/temporada/1/capitulo/1">1 Entra en escena Naruto Uzumaki</a>
            <a href="/anime/naruto/temporada/1/capitulo/2">2 ¡Me llamo Konohamaru!</a>
        """.trimIndent()
        val eps = SeriesEpisodeParser.parse(base, html, serieskao)
        assertEquals(2, eps.size)
        assertEquals(WebEpisode(1, 1, "Entra en escena Naruto Uzumaki", "$base/anime/naruto/temporada/1/capitulo/1"), eps[0])
        assertEquals(WebEpisode(1, 2, "¡Me llamo Konohamaru!", "$base/anime/naruto/temporada/1/capitulo/2"), eps[1])
    }

    @Test
    fun `patron sololatino (temporada-N-episodio-M)`() {
        val sololatino = WebEpisodeRules(
            regex = """<a[^>]+href="([^"]*?/temporada-(\d+)/episodio-(\d+))"[^>]*>(.*?)</a>""",
            fields = listOf("pageUrl", "season", "episode", "title"),
        )
        val html = """
            <a href="https://sololatino.net/serie/boruto/temporada-1/episodio-2">E2 ¡El hijo del Hokage!</a>
            <a href="https://sololatino.net/serie/boruto/temporada-1/episodio-1">E1 ¡Boruto Uzumaki!</a>
        """.trimIndent()
        val eps = SeriesEpisodeParser.parse("https://sololatino.net", html, sololatino)
        assertEquals(2, eps.size)
        assertEquals(1 to 1, eps[0].season to eps[0].episode)
        assertEquals("https://sololatino.net/serie/boruto/temporada-1/episodio-1", eps[0].pageUrl)
    }

    @Test
    fun `deduplica y ordena por temporada y capitulo`() {
        val html = """
            <a href="/s/temporada/2/capitulo/1">1 dos-uno</a>
            <a href="/s/temporada/1/capitulo/10">10 uno-diez</a>
            <a href="/s/temporada/1/capitulo/2">2 uno-dos</a>
            <a href="/s/temporada/1/capitulo/2">2 uno-dos (repetido)</a>
        """.trimIndent()
        val eps = SeriesEpisodeParser.parse(base, html, serieskao)
        assertEquals(listOf(1 to 2, 1 to 10, 2 to 1), eps.map { it.season to it.episode })
    }

    // --- Validación end-to-end contra la página REAL de Naruto en serieskao ---

    @Test
    fun `parsea la pagina real de Naruto de serieskao`() {
        val html = javaClass.classLoader!!.getResourceAsStream("serieskao_naruto.html")!!
            .bufferedReader().use { it.readText() }
        val eps = SeriesEpisodeParser.parse(base, html, serieskao)

        // La página lista ~220 capítulos: exigimos al menos 200 para no ser frágiles ante cambios menores.
        assertTrue("esperaba >=200 episodios, obtuve ${eps.size}", eps.size >= 200)

        // Primer episodio bien formado.
        val first = eps.first()
        assertEquals(1, first.season)
        assertEquals(1, first.episode)
        assertTrue("título del ep1 vacío", first.title.contains("Naruto", ignoreCase = true))
        assertEquals("$base/anime/naruto/temporada/1/capitulo/1", first.pageUrl)

        // Sin duplicados (URLs únicas) y episodios positivos.
        assertEquals(eps.size, eps.map { it.pageUrl }.toSet().size)
        assertTrue(eps.all { it.episode > 0 && it.season > 0 })
    }
}
