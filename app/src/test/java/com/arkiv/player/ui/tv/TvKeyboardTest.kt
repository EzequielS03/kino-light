package com.arkiv.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvKeyboardTest {
    @Test fun `la grilla trae A-Z y 0-9 una sola vez`() {
        val chars = TV_KEYBOARD_ROWS.flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('A'..'Z').toList() + ('0'..'9').toList(), chars)
        assertEquals(chars.size, chars.toSet().size)
    }

    @Test fun `la grilla tiene espacio y borrar`() {
        val keys = TV_KEYBOARD_ROWS.flatten()
        assertTrue(keys.contains(TvKey.Space))
        assertTrue(keys.contains(TvKey.Backspace))
    }

    @Test fun `las filas tienen a lo sumo 6 columnas`() {
        assertTrue(TV_KEYBOARD_ROWS.all { it.size <= 6 })
    }

    @Test fun `escribir agrega la letra`() {
        assertEquals("SUP", applyKey("SU", TvKey.Char('P')))
    }

    @Test fun `espacio agrega un espacio`() {
        assertEquals("LA ", applyKey("LA", TvKey.Space))
    }

    @Test fun `borrar quita el ultimo caracter`() {
        assertEquals("SU", applyKey("SUP", TvKey.Backspace))
    }

    @Test fun `borrar en vacio no rompe`() {
        assertEquals("", applyKey("", TvKey.Backspace))
    }
}
