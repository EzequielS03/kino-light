package com.arkiv.player.data.nuevos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué capítulos pedirle a la fuente cuando se revisa una serie seguida.
 * Ver [CapitulosFaltantes] para el porqué de cada cota.
 */
class CapitulosFaltantesTest {

    @Test fun si_no_salio_nada_no_se_pide_nada() {
        assertEquals(
            emptyList<Int>(),
            CapitulosFaltantes.aPedir(tengo = listOf(1, 2, 3), enLaFuente = listOf(1, 2, 3)),
        )
    }

    @Test fun un_capitulo_nuevo_se_pide() {
        assertEquals(
            listOf(4),
            CapitulosFaltantes.aPedir(tengo = listOf(1, 2, 3), enLaFuente = listOf(1, 2, 3, 4)),
        )
    }

    @Test fun varios_seguidos_se_piden_en_orden() {
        assertEquals(
            listOf(4, 5, 6),
            CapitulosFaltantes.aPedir(listOf(1, 2, 3), listOf(1, 2, 3, 4, 5, 6)),
        )
    }

    // ─── la cota que hace barato el caso web ───────────────────────────────

    @Test fun un_hueco_viejo_NO_se_vuelve_a_pedir() {
        // Falta el 2, pero tengo hasta el 5. Ese hueco casi siempre es un capítulo que la fuente
        // nunca tuvo: re-buscarlo en cada arranque es pagar una búsqueda para nada, para siempre.
        assertEquals(
            emptyList<Int>(),
            CapitulosFaltantes.aPedir(tengo = listOf(1, 3, 4, 5), enLaFuente = listOf(1, 2, 3, 4, 5)),
        )
    }

    @Test fun con_hueco_viejo_igual_se_pide_lo_realmente_nuevo() {
        assertEquals(
            listOf(6),
            CapitulosFaltantes.aPedir(tengo = listOf(1, 3, 4, 5), enLaFuente = listOf(1, 2, 3, 4, 5, 6)),
        )
    }

    // ─── tope por serie ────────────────────────────────────────────────────

    @Test fun una_avalancha_se_trae_de_a_poco() {
        // 20 capítulos de golpe (una serie que se dejó de ver hace un año). Se traen los primeros
        // y el resto en el próximo arranque: una sola serie no puede comerse todo el presupuesto.
        val fuente = (1..25).toList()
        val pedidos = CapitulosFaltantes.aPedir(tengo = listOf(1, 2, 3, 4, 5), enLaFuente = fuente)
        assertEquals(CapitulosFaltantes.MAX_POR_SERIE, pedidos.size)
        assertEquals(listOf(6, 7, 8, 9, 10), pedidos)
    }

    // ─── bordes ────────────────────────────────────────────────────────────

    @Test fun sin_nada_guardado_se_piden_los_primeros_de_la_fuente() {
        assertEquals(listOf(1, 2, 3, 4, 5), CapitulosFaltantes.aPedir(emptyList(), (1..9).toList()))
    }

    @Test fun fuente_vacia_no_pide_nada() {
        assertEquals(emptyList<Int>(), CapitulosFaltantes.aPedir(listOf(1, 2), emptyList()))
    }

    @Test fun los_repetidos_de_la_fuente_se_piden_una_sola_vez() {
        assertEquals(listOf(4), CapitulosFaltantes.aPedir(listOf(1, 2, 3), listOf(4, 4, 4)))
    }

    @Test fun la_fuente_desordenada_igual_devuelve_en_orden() {
        assertEquals(listOf(4, 5, 6), CapitulosFaltantes.aPedir(listOf(1, 2, 3), listOf(6, 4, 5)))
    }

    @Test fun una_fuente_atrasada_no_pide_nada() {
        // La fuente reporta menos de lo que ya tengo: no hay nada nuevo, y NO hay que borrar nada.
        assertEquals(emptyList<Int>(), CapitulosFaltantes.aPedir(listOf(1, 2, 3, 4, 5), listOf(1, 2)))
    }

    // ─── season-aware variant (Caracol numbers chapters PER SEASON) ────────
    //
    // Plain `aPedir` compares against the highest NUMBER seen so far, which breaks for a source
    // that restarts numbering every season: with ten chapters in season 1, season 2's chapter 1
    // would look like a duplicate of something already stored. `aPedirPorTemporada` keys by
    // `(season, number)` instead, so it compares season-first.

    @Test fun una_temporada_nueva_se_pide() {
        assertEquals(
            listOf(2 to 1),
            CapitulosFaltantes.aPedirPorTemporada(tengo = listOf(1 to 10), enLaFuente = listOf(1 to 10, 2 to 1)),
        )
    }

    @Test fun un_capitulo_nuevo_de_la_misma_temporada_se_pide() {
        assertEquals(
            listOf(1 to 11),
            CapitulosFaltantes.aPedirPorTemporada(tengo = listOf(1 to 10), enLaFuente = listOf(1 to 10, 1 to 11)),
        )
    }

    @Test fun una_fuente_con_menos_temporadas_no_pide_nada() {
        // Ya tengo T2E3; la fuente solo reporta T1: nada de eso es "posterior" a lo guardado.
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            CapitulosFaltantes.aPedirPorTemporada(tengo = listOf(2 to 3), enLaFuente = listOf(1 to 1, 1 to 2)),
        )
    }

    @Test fun el_tope_por_serie_tambien_aplica_por_temporada() {
        val fuente = (1..8).map { 2 to it }
        val pedidos = CapitulosFaltantes.aPedirPorTemporada(tengo = listOf(1 to 10), enLaFuente = fuente)
        assertEquals(CapitulosFaltantes.MAX_POR_SERIE, pedidos.size)
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4, 2 to 5), pedidos)
    }

    @Test fun temporada_null_se_trata_como_cero() {
        assertEquals(
            listOf(0 to 2),
            CapitulosFaltantes.aPedirPorTemporada(tengo = listOf(null to 1), enLaFuente = listOf(null to 1, null to 2)),
        )
    }
}
