package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La ventana de capítulos con varias temporadas. Lo que protege de verdad es la primera mitad: que
 * una lista de Magis (sin temporada) se vea exactamente igual que antes.
 */
class CapitulosPorTemporadaTest {

    private fun cap(numero: Int, temporada: Int? = null) =
        GatewayEpisode(number = numero, title = "Episodio $numero", ref = "r-$temporada-$numero", season = temporada)

    @Test fun `magis, sin temporada, queda como estaba`() {
        val lista = listOf(cap(3), cap(1), cap(2))

        assertFalse(CapitulosPorTemporada.variasTemporadas(lista))
        // Mismo orden en que llegó: la ventana no reordena a Magis.
        assertEquals(lista, CapitulosPorTemporada.ordenar(lista))
        assertEquals("3", CapitulosPorTemporada.etiqueta(cap(3), variasTemporadas = false))
        assertEquals("E3", CapitulosPorTemporada.etiqueta(cap(3), variasTemporadas = false, sinTemporada = "E"))
    }

    @Test fun `una sola temporada tampoco cambia nada`() {
        val lista = listOf(cap(2, 1), cap(1, 1))
        val varias = CapitulosPorTemporada.variasTemporadas(lista)

        assertFalse(varias)
        assertEquals(lista, CapitulosPorTemporada.ordenar(lista))
        assertEquals("2", CapitulosPorTemporada.etiqueta(cap(2, 1), varias))
    }

    @Test fun `con varias temporadas cada fila dice la suya`() {
        val lista = listOf(cap(1, 1), cap(1, 2))
        val varias = CapitulosPorTemporada.variasTemporadas(lista)

        assertTrue(varias)
        assertEquals("T2 · E1", CapitulosPorTemporada.etiqueta(cap(1, 2), varias))
        assertEquals("T2 · E1", CapitulosPorTemporada.etiqueta(cap(1, 2), varias, sinTemporada = "E"))
        // El 1 de la T1 y el 1 de la T2 ya no se ven iguales.
        assertNotEquals(
            CapitulosPorTemporada.etiqueta(cap(1, 1), varias),
            CapitulosPorTemporada.etiqueta(cap(1, 2), varias),
        )
    }

    @Test fun `con varias temporadas va por temporada y despues por numero`() {
        val lista = listOf(cap(1, 2), cap(2, 1), cap(1, 1))

        assertEquals(listOf(cap(1, 1), cap(2, 1), cap(1, 2)), CapitulosPorTemporada.ordenar(lista))
    }
}
