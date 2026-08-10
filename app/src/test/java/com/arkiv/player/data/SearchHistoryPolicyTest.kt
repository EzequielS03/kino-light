package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo único del historial que SQLite no resuelve solo: normalizar el texto buscado y decidir
 * cuándo dos títulos son la misma obra. El orden, el tope y el dedupe los hace la consulta
 * (`ORDER BY atMs DESC LIMIT`) y la PK con REPLACE — ver [SearchHistoryRepo].
 */
class SearchHistoryPolicyTest {

    @Test fun el_texto_se_guarda_recortado() {
        assertEquals("dune", SearchHistoryPolicy.normalizeQuery("  dune  "))
    }

    @Test fun un_texto_vacio_o_de_solo_espacios_no_se_guarda() {
        assertNull(SearchHistoryPolicy.normalizeQuery(""))
        assertNull(SearchHistoryPolicy.normalizeQuery("   "))
    }

    @Test fun el_texto_conserva_sus_mayusculas() {
        // El dedupe sin mirar mayúsculas lo hace la consulta con lower(); lo que se GUARDA es
        // lo que el usuario escribió, que es lo que va a ver en el chip.
        assertEquals("One Piece", SearchHistoryPolicy.normalizeQuery("One Piece"))
    }

    @Test fun la_identidad_de_un_titulo_sale_del_id_de_su_fuente() {
        assertEquals("series:tmdb-1399", SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"))
        assertEquals("anime:anilist-21", SearchHistoryPolicy.titleId("anime", null, 21L, "One Piece"))
    }

    @Test fun dos_series_con_el_mismo_nombre_no_son_la_misma_obra() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("series", 1399, null, "The Office"),
            SearchHistoryPolicy.titleId("series", 2316, null, "The Office"),
        )
    }

    @Test fun una_peli_y_un_anime_con_el_mismo_numero_no_se_pisan() {
        assertNotEquals(
            SearchHistoryPolicy.titleId("movie", 21, null, "Peli"),
            SearchHistoryPolicy.titleId("anime", null, 21L, "Anime"),
        )
    }

    @Test fun sin_ningun_id_la_identidad_cae_al_nombre_en_minusculas() {
        assertEquals("movie:n-dune", SearchHistoryPolicy.titleId("movie", null, null, "Dune"))
        assertEquals(
            SearchHistoryPolicy.titleId("movie", null, null, "Dune"),
            SearchHistoryPolicy.titleId("movie", null, null, "  DUNE  "),
        )
    }

    @Test fun la_sobrecarga_de_RecentTitle_da_el_mismo_id() {
        val t = RecentTitle("series", 1399, null, "Game of Thrones", "", "2011")
        assertEquals(SearchHistoryPolicy.titleId("series", 1399, null, "Game of Thrones"), SearchHistoryPolicy.titleId(t))
    }
}
