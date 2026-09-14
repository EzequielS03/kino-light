package com.arkiv.player.data

import com.arkiv.player.data.model.EpisodeNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down the season/chapter parsing that `EpisodeNumbering.displayLabel` (the player header's
 * label) depends on: `seasonOf` is one of its season fallbacks, today its only real caller
 * (`grep -rn "seasonOf(\|episodeOf(" app/src/main/java`). Nobody in production calls `episodeOf`;
 * these tests are its only coverage. The example texts are literally what `addWebSeriesEpisode` /
 * `addSeriesEpisodeMagnet` build.
 */
class EpisodeNumberingTest {

    @Test
    fun `saca la temporada de la seccion`() {
        assertEquals(1, EpisodeNumbering.seasonOf("Temporada 1"))
        assertEquals(12, EpisodeNumbering.seasonOf("Temporada 12"))
    }

    @Test
    fun `sin numero en la seccion no hay temporada`() {
        assertNull(EpisodeNumbering.seasonOf(""))
        assertNull(EpisodeNumbering.seasonOf("Extras"))
    }

    @Test
    fun `saca el capitulo del displayName de una serie web`() {
        // addWebSeriesEpisode's format: "T<season> · E<chapter>  <name>".
        assertEquals(1, EpisodeNumbering.episodeOf("T1 · E1"))
        assertEquals(7, EpisodeNumbering.episodeOf("T2 · E7  El regreso"))
    }

    @Test
    fun `el nombre del capitulo no le gana al numero real`() {
        // find() returns the FIRST match, so an "E9" inside the title doesn't clobber E3.
        assertEquals(3, EpisodeNumbering.episodeOf("T1 · E3  Fuga del bloque E9"))
    }

    @Test
    fun `sin marca de capitulo no hay numero`() {
        assertNull(EpisodeNumbering.episodeOf("Pelicula completa"))
    }
}
