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

    // --- Task 9: teclado extendido (minusculas + simbolos) para el login de la TV. El teclado de
    // busqueda sigue usando TV_KEYBOARD_ROWS/applyKey tal cual -los tests de arriba no cambiaron-;
    // estos prueban el agregado nuevo sin tocar ese contrato.

    @Test fun `applyKey con una tecla de modo no toca el texto`() {
        assertEquals("SU", applyKey("SU", TvKey.Modo(TvKeyboardMode.MINUS)))
        assertEquals("SU", applyKey("SU", TvKey.Modo(TvKeyboardMode.SIMBOLOS)))
        assertEquals("SU", applyKey("SU", TvKey.Modo(TvKeyboardMode.MAYUS)))
    }

    @Test fun `tvKeyboardRows MAYUS trae A-Z y 0-9, la fila de modos y espacio-borrar`() {
        val rows = tvKeyboardRows(TvKeyboardMode.MAYUS)
        val chars = rows.flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('A'..'Z').toList() + ('0'..'9').toList(), chars)
        val flat = rows.flatten()
        assertTrue(flat.contains(TvKey.Modo(TvKeyboardMode.MAYUS)))
        assertTrue(flat.contains(TvKey.Modo(TvKeyboardMode.MINUS)))
        assertTrue(flat.contains(TvKey.Modo(TvKeyboardMode.SIMBOLOS)))
        assertTrue(flat.contains(TvKey.Space))
        assertTrue(flat.contains(TvKey.Backspace))
    }

    @Test fun `tvKeyboardRows MINUS trae a-z y 0-9`() {
        val chars = tvKeyboardRows(TvKeyboardMode.MINUS).flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertEquals(('a'..'z').toList() + ('0'..'9').toList(), chars)
    }

    @Test fun `tvKeyboardRows SIMBOLOS trae arroba, punto y guion (los que pide el brief)`() {
        val chars = tvKeyboardRows(TvKeyboardMode.SIMBOLOS).flatten().filterIsInstance<TvKey.Char>().map { it.c }
        assertTrue(chars.contains('@'))
        assertTrue(chars.contains('.'))
        assertTrue(chars.contains('-'))
        assertEquals("sin simbolos repetidos", chars.size, chars.toSet().size)
    }

    @Test fun `todas las filas de las variantes tienen a lo sumo 6 columnas`() {
        TvKeyboardMode.entries.forEach { modo ->
            assertTrue("modo=$modo", tvKeyboardRows(modo).all { it.size <= 6 })
        }
    }

    @Test fun `escribir en minuscula agrega la letra tal cual`() {
        assertEquals("su", applyKey("s", TvKey.Char('u')))
    }
}
