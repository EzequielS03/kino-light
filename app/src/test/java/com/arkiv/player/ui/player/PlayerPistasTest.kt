package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La regla del menú de audio/subtítulos que sí decide algo: cómo se etiqueta una pista sin idioma
 * ([etiquetaDeSpu]). Vivía dentro de `PlayerContent` como función local cerrada sobre su estado, así
 * que hasta ahora no se podía llamar desde acá; salió a `PlayerPistas.kt` justo para poder fijarla.
 */
class PlayerPistasTest {

    // ---- etiquetaDeSpu ----

    private val pistas = listOf(0 to "Track 1", 1 to "Track 2")

    @Test
    fun `magis antepone el idioma declarado a la pista sin nombre`() {
        val etiqueta = etiquetaDeSpu(
            id = 0, nombre = "Track 1", esMagis = true,
            idiomasDeclarados = listOf("es-419", "en"), spuTracks = pistas,
        )
        assertEquals("Español latino · Track 1", etiqueta)
    }

    @Test
    fun `el idioma se toma por POSICION de la pista, no por su id`() {
        val etiqueta = etiquetaDeSpu(
            id = 1, nombre = "Track 2", esMagis = true,
            idiomasDeclarados = listOf("es-419", "en"), spuTracks = pistas,
        )
        assertEquals("Inglés · Track 2", etiqueta)
    }

    /**
     * Fuera de magis la lista de idiomas viene de otra fuente y no describe estas pistas:
     * etiquetarlas con ella sería mentir en el menú.
     */
    @Test
    fun `fuera de magis el nombre se deja crudo`() {
        val etiqueta = etiquetaDeSpu(
            id = 0, nombre = "Track 1", esMagis = false,
            idiomasDeclarados = listOf("es-419"), spuTracks = pistas,
        )
        assertEquals("Track 1", etiqueta)
    }

    @Test
    fun `sin idioma declarado para esa posicion no se adivina`() {
        val etiqueta = etiquetaDeSpu(
            id = 1, nombre = "Track 2", esMagis = true,
            idiomasDeclarados = listOf("es-419"), spuTracks = pistas,
        )
        assertEquals("Track 2", etiqueta)
    }

    @Test
    fun `un codigo que no se reconoce deja el nombre crudo`() {
        val etiqueta = etiquetaDeSpu(
            id = 0, nombre = "Track 1", esMagis = true,
            idiomasDeclarados = listOf("zz"), spuTracks = pistas,
        )
        assertEquals("Track 1", etiqueta)
    }

    @Test
    fun `la entrada sintetica Desactivar nunca se etiqueta`() {
        val etiqueta = etiquetaDeSpu(
            id = -1, nombre = "Desactivar", esMagis = true,
            idiomasDeclarados = listOf("es-419"), spuTracks = pistas,
        )
        assertEquals("Desactivar", etiqueta)
    }
}
