package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orden, dedupe y tope del historial del buscador. Ver [SearchHistoryStore] para la persistencia. */
class SearchHistoryPolicyTest {

    private fun titulo(
        kind: String = "series",
        tmdbId: Int? = 1399,
        anilistId: Long? = null,
        title: String = "Game of Thrones",
    ) = RecentTitle(kind, tmdbId, anilistId, title, posterUrl = "", year = "2011")

    // ─── textos ────────────────────────────────────────────────────────────

    @Test fun lo_nuevo_queda_primero() {
        val r = SearchHistoryPolicy.pushQuery(listOf("dune", "akira"), "one piece")
        assertEquals(listOf("one piece", "dune", "akira"), r)
    }

    @Test fun repetir_un_texto_lo_sube_al_tope_sin_duplicarlo() {
        val r = SearchHistoryPolicy.pushQuery(listOf("dune", "akira", "one piece"), "akira")
        assertEquals(listOf("akira", "dune", "one piece"), r)
    }

    @Test fun el_dedupe_de_texto_ignora_mayusculas_y_espacios_sobrantes() {
        val r = SearchHistoryPolicy.pushQuery(listOf("One Piece", "dune"), "  one piece  ")
        assertEquals(listOf("one piece", "dune"), r)
    }

    @Test fun el_texto_se_guarda_recortado() {
        val r = SearchHistoryPolicy.pushQuery(emptyList(), "  dune  ")
        assertEquals(listOf("dune"), r)
    }

    @Test fun un_texto_vacio_o_de_solo_espacios_no_entra() {
        assertEquals(listOf("dune"), SearchHistoryPolicy.pushQuery(listOf("dune"), ""))
        assertEquals(listOf("dune"), SearchHistoryPolicy.pushQuery(listOf("dune"), "   "))
    }

    @Test fun se_respeta_el_tope_y_cae_el_mas_viejo() {
        val llena = (1..10).map { "q$it" }   // q1 es el más nuevo, q10 el más viejo
        val r = SearchHistoryPolicy.pushQuery(llena, "nueva")
        assertEquals(10, r.size)
        assertEquals("nueva", r.first())
        assertFalse(r.contains("q10"))
    }

    // ─── títulos ───────────────────────────────────────────────────────────

    @Test fun el_dedupe_de_titulos_usa_la_identidad_no_el_nombre() {
        // Dos series distintas que se llaman igual NO se pisan.
        val a = titulo(tmdbId = 1399, title = "The Office")
        val b = titulo(tmdbId = 2316, title = "The Office")
        val r = SearchHistoryPolicy.pushTitle(listOf(a), b)
        assertEquals(2, r.size)
        assertEquals(b, r.first())
    }

    @Test fun volver_a_abrir_un_titulo_lo_sube_al_tope_sin_duplicarlo() {
        val a = titulo(tmdbId = 1399, title = "Game of Thrones")
        val b = titulo(tmdbId = 66732, title = "Stranger Things")
        val r = SearchHistoryPolicy.pushTitle(listOf(b, a), a)
        assertEquals(listOf(a, b), r)
    }

    @Test fun una_peli_y_un_anime_con_el_mismo_numero_de_id_no_se_pisan() {
        val peli = RecentTitle("movie", tmdbId = 21, anilistId = null, title = "Peli", posterUrl = "", year = "")
        val anime = RecentTitle("anime", tmdbId = null, anilistId = 21L, title = "Anime", posterUrl = "", year = "")
        assertFalse(SearchHistoryPolicy.mismaIdentidad(peli, anime))
        assertEquals(2, SearchHistoryPolicy.pushTitle(listOf(peli), anime).size)
    }

    @Test fun sin_ningun_id_la_identidad_cae_al_nombre() {
        val a = RecentTitle("movie", null, null, "Dune", "", "2021")
        val b = RecentTitle("movie", null, null, "dune", "", "2021")
        assertTrue(SearchHistoryPolicy.mismaIdentidad(a, b))
        assertEquals(1, SearchHistoryPolicy.pushTitle(listOf(a), b).size)
    }

    @Test fun los_titulos_tambien_respetan_su_tope() {
        val llena = (1..12).map { titulo(tmdbId = it, title = "t$it") }
        val r = SearchHistoryPolicy.pushTitle(llena, titulo(tmdbId = 999, title = "nueva"))
        assertEquals(12, r.size)
        assertEquals("nueva", r.first().title)
        assertFalse(r.any { it.title == "t12" })
    }
}
