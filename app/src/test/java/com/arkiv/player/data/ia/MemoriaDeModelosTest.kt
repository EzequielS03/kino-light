package com.arkiv.player.data.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriaDeModelosTest {

    private class AlmacenEnMemoria : AlmacenDeMemoria {
        var json: String? = null
        override fun leer() = json
        override fun guardar(json: String) { this.json = json }
    }

    private var ahora = 1_000_000L
    private val almacen = AlmacenEnMemoria()
    private fun memoria() = MemoriaDeModelos(almacen) { ahora }
    private val a = ModeloDeKilo("a")
    private val b = ModeloDeKilo("b")
    private val c = ModeloDeKilo("c")

    @Test fun `sin historia se conserva el orden del catalogo`() {
        assertEquals(listOf(a, b, c), memoria().ordenar(listOf(a, b, c)))
    }

    @Test fun `el que respondio bien sube`() {
        val m = memoria()
        m.exito("c")
        assertEquals(c, m.ordenar(listOf(a, b, c)).first())
    }

    @Test fun `el que fallo baja`() {
        val m = memoria()
        m.fallo("a", Falla.Servidor)
        ahora += 6 * 60 * 1000L // ya pasó su espera de 5 min
        assertEquals(a, m.ordenar(listOf(a, b, c)).last())
    }

    @Test fun `un 429 sin Retry-After espera 10 minutos`() {
        val m = memoria()
        m.fallo("a", Falla.Limite(retryAfterMs = null))
        ahora += 9 * 60 * 1000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2 * 60 * 1000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    @Test fun `un 429 con Retry-After espera lo que dice`() {
        val m = memoria()
        m.fallo("a", Falla.Limite(retryAfterMs = 30_000L))
        ahora += 29_000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2_000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    @Test fun `un 5xx espera 5 minutos`() {
        val m = memoria()
        m.fallo("a", Falla.Servidor)
        ahora += 4 * 60 * 1000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2 * 60 * 1000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    /** Un fallo del lado del cliente no puede excluir un modelo (lección de llm-libre). */
    @Test fun `una respuesta ilegible no castiga`() {
        val m = memoria()
        m.fallo("a", Falla.Ilegible)
        assertEquals(listOf(a, b, c), m.ordenar(listOf(a, b, c)))
    }

    @Test fun `todos en espera da vacio`() {
        val m = memoria()
        listOf("a", "b").forEach { m.fallo(it, Falla.Servidor) }
        assertTrue(m.ordenar(listOf(a, b)).isEmpty())
    }

    @Test fun `la memoria sobrevive entre sesiones`() {
        memoria().exito("c")
        assertEquals(c, memoria().ordenar(listOf(a, b, c)).first())
    }

    @Test fun `un almacen roto no tumba nada`() {
        almacen.json = "{esto no es json"
        assertEquals(listOf(a, b), memoria().ordenar(listOf(a, b)))
    }
}
