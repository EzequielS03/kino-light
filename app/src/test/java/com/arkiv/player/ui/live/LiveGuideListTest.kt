package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGuideListTest {
    private val progs = listOf(
        LiveProgram("Anterior", 100, 200, ""),
        LiveProgram("Ahora", 200, 300, ""),
        LiveProgram("Después", 300, 400, ""),
    )

    @Test
    fun `el programa en curso es el que contiene el instante`() {
        assertEquals("Ahora", enCurso(progs, 250)?.titulo)
    }

    @Test
    fun `el borde de fin ya pertenece al siguiente`() {
        assertEquals("Después", enCurso(progs, 300)?.titulo)
    }

    @Test
    fun `fuera de la grilla no hay programa`() {
        assertNull(enCurso(progs, 50))
        assertNull(enCurso(progs, 999))
    }

    @Test
    fun `el avance va de cero a uno`() {
        assertEquals(0.5f, avance(progs[1], 250), 0.001f)
        assertEquals(0f, avance(progs[1], 200), 0.001f)
    }
}
