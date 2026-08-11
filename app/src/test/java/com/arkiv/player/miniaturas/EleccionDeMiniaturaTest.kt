package com.arkiv.player.miniaturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué imagen se muestra en una tarjeta. El frame capturado gana SOLO donde existe, y existe solo
 * si el capítulo tuvo progreso: por eso "gana solo en lo empezado" no necesita un parámetro de
 * progreso, sale de que el frame sea o no null.
 */
class EleccionDeMiniaturaTest {

    @Test
    fun `el frame le gana a todos los respaldos`() {
        assertEquals(
            "/data/frames/a.jpg",
            EleccionDeMiniatura.elegir("/data/frames/a.jpg", "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    @Test
    fun `sin frame gana el primer respaldo con contenido`() {
        assertEquals(
            "https://tmdb/still.jpg",
            EleccionDeMiniatura.elegir(null, "https://tmdb/still.jpg", "https://cdn/caratula.jpg"),
        )
    }

    /** Una ruta vacía es "no hay frame", no "hay un frame que es la cadena vacía". */
    @Test
    fun `un frame en blanco se ignora y cae al respaldo`() {
        assertEquals("https://tmdb/still.jpg", EleccionDeMiniatura.elegir("", "https://tmdb/still.jpg"))
        assertEquals("https://tmdb/still.jpg", EleccionDeMiniatura.elegir("   ", "https://tmdb/still.jpg"))
    }

    /** Mismo criterio para los respaldos: se saltan los vacíos en vez de pintar nada. */
    @Test
    fun `los respaldos vacios se saltan`() {
        assertEquals("https://cdn/caratula.jpg", EleccionDeMiniatura.elegir(null, null, "", "https://cdn/caratula.jpg"))
    }

    @Test
    fun `sin nada devuelve null`() {
        assertNull(EleccionDeMiniatura.elegir(null, null, ""))
    }
}
