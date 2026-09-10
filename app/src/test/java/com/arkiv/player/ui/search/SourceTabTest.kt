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

    private fun caracol(titulo: String) = PlaySource.Ditu(
        GatewayResult(source = "ditu", title = titulo, ref = "ditu1:VOD:$titulo"),
    )

    @Test fun un_resultado_de_caracol_cae_en_su_pestana() {
        assertEquals(SourceTab.CARACOL, tabOf(caracol("c")))
    }

    @Test fun los_conteos_traen_caracol_aunque_este_en_cero() {
        // Sin la clave, el chip de Caracol no se pinta hasta que llega su primer resultado.
        val conteos = countsByTab(listOf(magis("m")))
        assertTrue(SourceTab.CARACOL in conteos)
        assertEquals(0, conteos[SourceTab.CARACOL])
        assertEquals(1, conteos[SourceTab.MAGIS])
        assertEquals(1, conteos[SourceTab.TODO])
    }

    @Test fun caracol_va_despues_de_magis_aunque_llegue_primero() {
        val r = filasVisibles(listOf(caracol("c"), magis("m")), SourceTab.TODO)
        assertEquals(listOf(SourceTab.MAGIS, SourceTab.CARACOL), r.map { it.first })
    }

    @Test fun el_filtro_de_caracol_deja_solo_caracol() {
        val r = filasVisibles(listOf(caracol("c"), magis("m")), SourceTab.CARACOL)
        assertEquals(listOf(SourceTab.CARACOL), r.map { it.first })
        assertEquals(listOf(caracol("c")), filterByTab(listOf(caracol("c"), magis("m")), SourceTab.CARACOL))
    }

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
