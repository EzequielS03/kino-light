package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveViewModelTest {
    private val canales = listOf(
        LiveChannel("c1", "ESPN", 501, null),
        LiveChannel("c2", "TNT Sports", 502, null),
        LiveChannel("c3", "Caracol", 101, null),
    )

    @Test
    fun `busca por nombre sin importar mayusculas ni tildes`() {
        assertEquals(listOf("c3"), filtrar(canales, "caracol").map { it.code })
    }

    @Test
    fun `busca por numero de canal`() {
        assertEquals(listOf("c2"), filtrar(canales, "502").map { it.code })
    }

    @Test
    fun `sin texto devuelve todo en el orden que vino`() {
        assertEquals(canales, filtrar(canales, "  "))
    }

    // --- progresoDePrograma: barra fina de avance del programa en curso (Step 5 del brief) ---

    @Test
    fun `progreso es cero justo al arrancar el programa`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(0f, progresoDePrograma(p, ahoraSegundos = 1000L))
    }

    @Test
    fun `progreso es la mitad a mitad de camino`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(0.5f, progresoDePrograma(p, ahoraSegundos = 1500L))
    }

    @Test
    fun `progreso no pasa de uno aunque el programa ya haya terminado`() {
        val p = LiveProgram("Noticias", inicio = 1000L, fin = 2000L, sinopsis = "")
        assertEquals(1f, progresoDePrograma(p, ahoraSegundos = 5000L))
    }

    @Test
    fun `progreso no baja de cero con datos inconsistentes de duracion`() {
        // fin <= inicio no debería pasar en la práctica, pero si el portal manda algo raro,
        // la barra no debe romperse ni mostrar un número negativo o NaN.
        val p = LiveProgram("Raro", inicio = 2000L, fin = 2000L, sinopsis = "")
        assertEquals(0f, progresoDePrograma(p, ahoraSegundos = 2000L))
    }
}
