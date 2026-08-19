package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas puras del dato curioso: cuál sigue al que se está viendo, si hay algo que mostrar y
 * si la obra es serie o película. Viven en funciones puras porque este proyecto no
 * tiene tests de interfaz: escritas adentro del Composable no se podrían probar de ninguna forma
 * (mismo criterio que `DpadDelDrawer`).
 */
class TriviaDelPlayerTest {

    // ---- siguienteIndice: la rotación por pulsación ----

    @Test
    fun `cada pulsacion avanza al siguiente`() {
        assertEquals(1, TriviaDelPlayer.siguienteIndice(actual = 0, cantidad = 8))
        assertEquals(2, TriviaDelPlayer.siguienteIndice(actual = 1, cantidad = 8))
        assertEquals(7, TriviaDelPlayer.siguienteIndice(actual = 6, cantidad = 8))
    }

    /** Al pasar el último vuelve al primero: siempre hay algo que mostrar al pulsar arriba. */
    @Test
    fun `despues del ultimo vuelve al primero`() {
        assertEquals(0, TriviaDelPlayer.siguienteIndice(actual = 7, cantidad = 8))
    }

    /** Con un solo dato, pulsar arriba lo deja donde está en vez de dividir por cero o salirse. */
    @Test
    fun `con un solo dato se queda en el`() {
        assertEquals(0, TriviaDelPlayer.siguienteIndice(actual = 0, cantidad = 1))
    }

    @Test
    fun `sin datos no hay indice al que avanzar`() {
        assertEquals(-1, TriviaDelPlayer.siguienteIndice(actual = 0, cantidad = 0))
        assertEquals(-1, TriviaDelPlayer.siguienteIndice(actual = -1, cantidad = 0))
    }

    /** El primer arriba, partiendo de "ninguno mostrado todavía", tiene que caer en el primero. */
    @Test
    fun `desde ninguno arranca en el primero`() {
        assertEquals(0, TriviaDelPlayer.siguienteIndice(actual = -1, cantidad = 8))
    }

    @Test
    fun `el boton existe solo si hay algo que leer`() {
        assertFalse(TriviaDelPlayer.hayBoton(emptyList()))
        assertTrue(TriviaDelPlayer.hayBoton(listOf("Un dato.")))
    }

    @Test
    fun `el tipo lo dice el item cuando lo sabe`() {
        // Lo escribe el gateway al canonizar, verificado contra TMDB: es mejor dato que
        // cualquier cosa que deduzcamos acá.
        assertEquals("tv", TriviaDelPlayer.tipoDe(tipoDelItem = "tv", episodio = null))
        assertEquals("movie", TriviaDelPlayer.tipoDe(tipoDelItem = "movie", episodio = 7))
    }

    @Test
    fun `sin tipo manda que haya numero de episodio`() {
        // Un ítem sin canonizar todavía: si tiene número de capítulo, es serie.
        assertEquals("tv", TriviaDelPlayer.tipoDe(tipoDelItem = null, episodio = 7))
        assertEquals("movie", TriviaDelPlayer.tipoDe(tipoDelItem = "", episodio = null))
    }
}
