package com.arkiv.player.ui.search

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orden de la lista de fuentes torrent. Es la MISMA función en las dos pantallas: la de fuentes del
 * celu ([ResultsContent] y la grilla directa de [SearchScreen]) y la lista única del TV
 * ([TvResultsContent]), así que lo que se rompa acá se rompe en los dos lados a la vez.
 *
 * Reemplaza al viejo `PackFirstOrderTest`, que apuntaba a `packsFirst()` — esa función se convirtió
 * en `ordenarTorrents()` (temporada ascendente + packs primero) y el test quedó apuntando a un
 * nombre inexistente, tumbando la compilación de toda la suite unitaria.
 */
class OrdenarTorrentsTest {

    private fun t(name: String) = PlaySource.Torrent(
        TorrentResult(name = name, seeders = 1, sizeBytes = 1L, lang = TorrentLang.LATINO)
    )

    private fun nombres(vararg names: String) = ordenarTorrents(names.map(::t)).map { it.result.name }

    // --- packs primero: la regla original que el TV da por sentada ---

    @Test fun `los packs van primero`() {
        val out = nombres(
            "Naruto Shippuden 268 spanish",
            "Naruto TODAS LAS TEMPORADAS 100% latino",
            "Naruto 671 [narutouchiha]",
        )
        assertEquals("Naruto TODAS LAS TEMPORADAS 100% latino", out.first())
    }

    /** `sortedWith` es estable: dentro de un mismo grupo manda la relevancia con la que llegó. */
    @Test fun `conserva el orden relativo dentro de cada grupo`() {
        assertEquals(
            listOf("Serie A 01", "Serie A 02", "Serie A 03"),
            nombres("Serie A 01", "Serie A 02", "Serie A 03"),
        )
    }

    // --- temporada ascendente: lo que agregó ordenarTorrents y no tenía cobertura ---

    @Test fun `las temporadas van en orden ascendente`() {
        assertEquals(
            listOf("Serie X S01E05", "Serie X S02E01", "Serie X S10E02"),
            nombres("Serie X S10E02", "Serie X S02E01", "Serie X S01E05"),
        )
    }

    /** El desempate es por temporada PRIMERO: un pack de T2 no se adelanta a un capítulo de T1. */
    @Test fun `el pack de una temporada posterior no se adelanta a la temporada anterior`() {
        assertEquals(
            listOf("Serie X S01E05", "Serie X Temporada 2 completa", "Serie X S02E01"),
            nombres("Serie X S02E01", "Serie X Temporada 2 completa", "Serie X S01E05"),
        )
    }

    /** Lo que no declara temporada va al final, no adelante: seasonOf() devuelve Int.MAX_VALUE. */
    @Test fun `lo que no tiene temporada detectable va al final`() {
        assertEquals(
            listOf("Serie X S01E01", "Serie X S02E01", "Serie X 1080p WEB-DL latino"),
            nombres("Serie X 1080p WEB-DL latino", "Serie X S02E01", "Serie X S01E01"),
        )
    }

    // --- seasonOf: las formas que se ven de verdad en los nombres de los trackers ---

    @Test fun `seasonOf reconoce las formas de escribir la temporada`() {
        assertEquals(6, seasonOf("Serie X T6 1080p"))
        assertEquals(6, seasonOf("Serie X T06 1080p"))
        assertEquals(6, seasonOf("Serie X S06 latino"))
        assertEquals(6, seasonOf("Serie X S06E03 latino"))
        assertEquals(4, seasonOf("Serie X Temporada 4 completa"))
    }

    @Test fun `seasonOf manda al final lo que no declara temporada`() {
        assertEquals(Int.MAX_VALUE, seasonOf("Naruto Shippuden 268 spanish"))
        assertEquals(Int.MAX_VALUE, seasonOf(""))
    }
}
