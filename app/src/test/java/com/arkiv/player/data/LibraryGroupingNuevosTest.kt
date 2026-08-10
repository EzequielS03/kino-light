package com.arkiv.player.data

import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El contador del badge a nivel GRUPO. Ver [LibraryGroup.nuevos] para por qué es el máximo entre
 * fuentes y no la suma.
 */
class LibraryGroupingNuevosTest {

    private fun row(id: String, eps: Int, vistos: Int?) = LibraryRow(
        identifier = id, title = "Serie", description = null, thumbnailUrl = "",
        episodeCount = eps, durationSeconds = 0.0, addedAt = 0L,
        categoryOverride = "series", source = "archive", episodiosVistosEnLista = vistos,
    )

    private fun grupo(vararg filas: LibraryRow) =
        LibraryGroup(key = "k", primary = filas.first(), members = filas.toList())

    @Test fun una_sola_fuente_cuenta_lo_suyo() {
        assertEquals(2, grupo(row("a", eps = 26, vistos = 24)).nuevos)
    }

    @Test fun sin_novedades_no_hay_badge() {
        assertEquals(0, grupo(row("a", eps = 26, vistos = 26)).nuevos)
    }

    @Test fun el_mismo_capitulo_en_dos_fuentes_cuenta_UNA_vez() {
        // Archive y web tienen la misma serie y a las dos les aparecieron 2 capítulos. Son 2
        // capítulos nuevos, no 4: sumar mentiría igual que sumaba 794 episodios para una de 220.
        assertEquals(2, grupo(row("a", 26, 24), row("w", 26, 24)).nuevos)
    }

    @Test fun manda_la_fuente_con_mas_novedades() {
        assertEquals(5, grupo(row("a", 26, 24), row("w", 30, 25)).nuevos)
    }

    @Test fun una_fuente_nunca_mirada_no_aporta_badge() {
        // vistos = null es "nunca se abrió": no puede inventar novedades.
        assertEquals(0, grupo(row("a", 26, null)).nuevos)
    }

    @Test fun una_fuente_sin_mirar_no_tapa_a_la_que_si_tiene_novedades() {
        assertEquals(3, grupo(row("a", 26, null), row("w", 26, 23)).nuevos)
    }
}
