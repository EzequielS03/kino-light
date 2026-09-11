package com.arkiv.player.data.nuevos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A qué series de la biblioteca vale la pena preguntarles si salió algo nuevo.
 * Ver [SeriesPorRevisar] para el porqué de cada filtro.
 */
class SeriesPorRevisarTest {

    private val AHORA = 1_754_000_000_000L
    private val DIA = 24 * 60 * 60 * 1000L

    private fun serie(
        id: String,
        fuente: String = "magis",
        vistoHace: Long = 0L,
        episodios: Int = 12,
    ) = SerieCandidata(id, fuente, ultimoVistoMs = AHORA - vistoHace, episodios = episodios)

    @Test fun una_serie_vista_ayer_entra() {
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("a", vistoHace = DIA)), AHORA)
        assertEquals(listOf("a"), elegidas.map { it.itemId })
    }

    @Test fun una_serie_que_no_toco_hace_un_ano_queda_afuera() {
        // No es que no importe: es que revisar 45 series dormidas en cada arranque gasta batería y
        // datos en cosas que nadie está viendo. Si la retomás, el progreso la vuelve a traer.
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("a", vistoHace = 365 * DIA)), AHORA)
        assertTrue(elegidas.isEmpty())
    }

    @Test fun justo_en_el_borde_de_la_ventana_entra() {
        val elegidas = SeriesPorRevisar.elegir(
            listOf(serie("a", vistoHace = (SeriesPorRevisar.VENTANA_DIAS - 1) * DIA)), AHORA,
        )
        assertEquals(1, elegidas.size)
    }

    @Test fun sin_progreso_no_se_revisa() {
        // ultimoVistoMs = 0 es "nunca se reprodujo": no es una serie que estés viendo.
        val elegidas = SeriesPorRevisar.elegir(
            listOf(SerieCandidata("a", "magis", ultimoVistoMs = 0L, episodios = 12)), AHORA,
        )
        assertTrue(elegidas.isEmpty())
    }

    @Test fun una_pelicula_nunca_entra() {
        // Un solo episodio = película. No hay "capítulo nuevo" que buscarle.
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("peli", episodios = 1, vistoHace = DIA)), AHORA)
        assertTrue(elegidas.isEmpty())
    }

    @Test fun torrent_queda_afuera_por_ahora() {
        // Fuera de alcance: sus series vienen en packs, "capítulo nuevo" ahí significa otra cosa.
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("t", fuente = "torrent", vistoHace = DIA)), AHORA)
        assertTrue(elegidas.isEmpty())
    }

    @Test fun both_sources_qualify() {
        val elegidas = SeriesPorRevisar.elegir(
            listOf(
                serie("m", fuente = "magis", vistoHace = DIA),
                serie("d", fuente = "ditu", vistoHace = DIA),
            ),
            AHORA,
        )
        assertEquals(2, elegidas.size)
    }

    @Test fun a_recently_watched_ditu_series_qualifies() {
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("d", fuente = "ditu", vistoHace = DIA)), AHORA)
        assertEquals(listOf("d"), elegidas.map { it.itemId })
    }

    @Test fun archive_is_no_longer_checked() {
        // Removed in this branch's pruning: leaving it in FUENTES only cost a real series a slot,
        // since BuscadorDeCapitulos does nothing with it.
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("a", fuente = "archive", vistoHace = DIA)), AHORA)
        assertTrue(elegidas.isEmpty())
    }

    @Test fun web_is_no_longer_checked() {
        val elegidas = SeriesPorRevisar.elegir(listOf(serie("w", fuente = "web", vistoHace = DIA)), AHORA)
        assertTrue(elegidas.isEmpty())
    }

    @Test fun manda_lo_mas_recientemente_visto() {
        val elegidas = SeriesPorRevisar.elegir(
            listOf(
                serie("vieja", vistoHace = 20 * DIA),
                serie("hoy", vistoHace = 1),
                serie("media", vistoHace = 5 * DIA),
            ),
            AHORA,
        )
        assertEquals(listOf("hoy", "media", "vieja"), elegidas.map { it.itemId })
    }

    @Test fun el_tope_corta_y_deja_las_mas_frescas() {
        val muchas = (1..30).map { serie("s$it", vistoHace = it * 60_000L) }
        val elegidas = SeriesPorRevisar.elegir(muchas, AHORA)
        assertEquals(SeriesPorRevisar.MAX_SERIES, elegidas.size)
        assertEquals("s1", elegidas.first().itemId)
    }

    @Test fun sin_candidatas_no_explota() {
        assertTrue(SeriesPorRevisar.elegir(emptyList(), AHORA).isEmpty())
    }
}
