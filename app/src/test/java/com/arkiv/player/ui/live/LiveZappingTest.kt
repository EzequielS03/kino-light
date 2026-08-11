package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZappingTest {
    private val lista = listOf(
        LiveChannel("c1", "Uno", 1, null),
        LiveChannel("c2", "Dos", 2, null),
        LiveChannel("c3", "Tres", 3, null),
    )

    @Test
    fun `avanza y da la vuelta al llegar al final`() {
        val z = LiveZapping(lista, 2)
        assertEquals("c1", z.siguiente().code)
    }

    @Test
    fun `retrocede y da la vuelta al llegar al principio`() {
        val z = LiveZapping(lista, 0)
        assertEquals("c3", z.anterior().code)
    }

    @Test
    fun `los vecinos son el de antes y el de despues`() {
        assertEquals(setOf("c1", "c3"), LiveZapping(lista, 1).vecinos().map { it.code }.toSet())
    }

    @Test
    fun `con un solo canal el zapping no se mueve ni falla`() {
        val z = LiveZapping(listOf(lista[0]), 0)
        assertEquals("c1", z.siguiente().code)
        assertEquals(emptyList<LiveChannel>(), z.vecinos())
    }
}
