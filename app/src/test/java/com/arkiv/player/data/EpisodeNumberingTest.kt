package com.arkiv.player.data

import com.arkiv.player.data.model.EpisodeNumbering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fija el parseo de temporada/capítulo del que depende `EpisodeNumbering.displayLabel` (el rótulo
 * del encabezado del player): `seasonOf` es uno de sus fallbacks de temporada, hoy su único
 * consumidor real (`grep -rn "seasonOf(\|episodeOf(" app/src/main/java`). `episodeOf` no lo llama
 * nadie en producción; estos tests son su única cobertura. Los textos de ejemplo son literalmente
 * los que arma `addWebSeriesEpisode` / `addSeriesEpisodeMagnet`.
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
        // Formato de addWebSeriesEpisode: "T<temporada> · E<capitulo>  <nombre>".
        assertEquals(1, EpisodeNumbering.episodeOf("T1 · E1"))
        assertEquals(7, EpisodeNumbering.episodeOf("T2 · E7  El regreso"))
    }

    @Test
    fun `el nombre del capitulo no le gana al numero real`() {
        // find() devuelve la PRIMERA coincidencia, así que un "E9" dentro del título no pisa al E3.
        assertEquals(3, EpisodeNumbering.episodeOf("T1 · E3  Fuga del bloque E9"))
    }

    @Test
    fun `sin marca de capitulo no hay numero`() {
        assertNull(EpisodeNumbering.episodeOf("Pelicula completa"))
    }
}
