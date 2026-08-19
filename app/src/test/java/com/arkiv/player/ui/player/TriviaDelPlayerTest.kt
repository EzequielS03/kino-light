package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regla de qué dato toca según el minuto. Vive en una función pura porque este proyecto no
 * tiene tests de interfaz: escrita adentro del Composable no se podría probar de ninguna forma
 * (mismo criterio que `DpadDelDrawer`).
 */
class TriviaDelPlayerTest {

    @Test
    fun `al arrancar toca el primero`() {
        assertEquals(0, TriviaDelPlayer.indiceEn(0L, cantidad = 8))
    }

    @Test
    fun `cambia recien al cumplirse los diez minutos`() {
        val casi = TriviaDelPlayer.INTERVALO_MS - 1
        assertEquals(0, TriviaDelPlayer.indiceEn(casi, cantidad = 8))
        assertEquals(1, TriviaDelPlayer.indiceEn(TriviaDelPlayer.INTERVALO_MS, cantidad = 8))
    }

    @Test
    fun `cuando se acaban se queda con el ultimo y no vuelve a empezar`() {
        // Una película de dos horas con ocho datos llega al final antes de terminar. Repetir
        // desde el principio haría que el aviso mienta: diría "hay algo nuevo" mostrando lo mismo.
        val tresHoras = 3 * 60 * 60 * 1000L
        assertEquals(7, TriviaDelPlayer.indiceEn(tresHoras, cantidad = 8))
    }

    @Test
    fun `sin datos no hay indice`() {
        assertEquals(-1, TriviaDelPlayer.indiceEn(0L, cantidad = 0))
        assertEquals(-1, TriviaDelPlayer.indiceEn(TriviaDelPlayer.INTERVALO_MS * 5, cantidad = 0))
    }

    @Test
    fun `una posicion negativa no rompe nada`() {
        // El player reporta -1 mientras no ha empezado a medir.
        assertEquals(0, TriviaDelPlayer.indiceEn(-1L, cantidad = 8))
    }

    @Test
    fun `el boton existe solo si hay algo que leer`() {
        assertFalse(TriviaDelPlayer.hayBoton(emptyList()))
        assertTrue(TriviaDelPlayer.hayBoton(listOf("Un dato.")))
    }

    @Test
    fun `un capitulo corto alcanza a mostrar dos datos`() {
        // 24 minutos: el de entrada, el de los 10 y el de los 20.
        val veinticuatro = 24 * 60 * 1000L
        assertEquals(2, TriviaDelPlayer.indiceEn(veinticuatro, cantidad = 8))
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
