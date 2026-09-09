package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué filas se dibujan en los resultados del TV, en qué orden y cuáles se saltean. */
class SourceTabTest {

    private fun magis(titulo: String) = PlaySource.Magis(
        GatewayResult(source = "magis", title = titulo, ref = "r-$titulo"),
    )

    @Test fun las_filas_van_en_el_orden_del_enum() {
        val r = filasVisibles(listOf(magis("m")), SourceTab.TODO)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
    }

    @Test fun una_fuente_sin_resultados_no_deja_fila() {
        val r = filasVisibles(listOf(magis("m")), SourceTab.TODO)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
    }

    @Test fun sin_resultados_no_hay_ninguna_fila() {
        assertTrue(filasVisibles(emptyList(), SourceTab.TODO).isEmpty())
    }

    @Test fun con_un_filtro_puesto_queda_una_sola_fila() {
        val r = filasVisibles(listOf(magis("m")), SourceTab.MAGIS)
        assertEquals(listOf(SourceTab.MAGIS), r.map { it.first })
        assertEquals(1, r.first().second.size)
    }

    @Test fun un_filtro_sobre_una_fuente_vacia_no_deja_filas() {
        assertTrue(filasVisibles(emptyList(), SourceTab.MAGIS).isEmpty())
    }

    @Test fun cada_fila_conserva_el_orden_de_llegada_de_su_fuente() {
        val fuentes = listOf(magis("a"), magis("b"))
        val fila = filasVisibles(fuentes, SourceTab.TODO).first { it.first == SourceTab.MAGIS }
        assertEquals(listOf("a", "b"), fila.second.map { (it as PlaySource.Magis).result.title })
    }
}
