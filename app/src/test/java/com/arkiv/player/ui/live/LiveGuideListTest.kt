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
    fun `the current program is the one containing the instant`() {
        assertEquals("Ahora", currentProgram(progs, 250)?.titulo)
    }

    @Test
    fun `the end boundary already belongs to the next one`() {
        assertEquals("Después", currentProgram(progs, 300)?.titulo)
    }

    @Test
    fun `outside the grid there's no program`() {
        assertNull(currentProgram(progs, 50))
        assertNull(currentProgram(progs, 999))
    }

    @Test
    fun `progress goes from zero to one`() {
        assertEquals(0.5f, progressOf(progs[1], 250), 0.001f)
        assertEquals(0f, progressOf(progs[1], 200), 0.001f)
    }
}
