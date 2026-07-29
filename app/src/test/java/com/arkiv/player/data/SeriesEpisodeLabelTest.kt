package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesEpisodeLabelTest {
    @Test fun `formato con serie temporada capitulo y titulo`() {
        assertEquals("Superman & Lois · S02E05 · El regreso",
            SeriesEpisodeLabel.format("Superman & Lois", 2, 5, "El regreso"))
    }

    @Test fun `pad de dos digitos`() {
        assertEquals("Loki · S01E01 · Glorious Purpose",
            SeriesEpisodeLabel.format("Loki", 1, 1, "Glorious Purpose"))
    }

    @Test fun `sin titulo de episodio omite el ultimo segmento`() {
        assertEquals("Loki · S01E03", SeriesEpisodeLabel.format("Loki", 1, 3, ""))
    }

    @Test fun `numeros de tres digitos no se truncan`() {
        assertEquals("One Piece · S01E1024 · x",
            SeriesEpisodeLabel.format("One Piece", 1, 1024, "x"))
    }
}
