package com.arkiv.player.ui.player

import com.arkiv.player.data.subtitles.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las dos reglas del menú de audio/subtítulos que sí deciden algo. Vivían dentro de `PlayerContent`
 * como funciones locales cerradas sobre su estado, así que hasta ahora no se podían llamar desde
 * acá; salieron a `PlayerPistas.kt` justo para poder fijarlas.
 */
class PlayerPistasTest {

    private fun sub(fileId: Long, language: String, hashMatch: Boolean = false) =
        SubtitleTrack(fileId = fileId, language = language, label = "s$fileId", release = "r", hashMatch = hashMatch)

    // ---- ordenarSubtitulos ----

    @Test
    fun `el release exacto va primero aunque su idioma este mas abajo`() {
        val orden = listOf("es", "en")
        val ordenado = ordenarSubtitulos(
            listOf(sub(1, "es"), sub(2, "en", hashMatch = true)),
            orden,
        )
        assertEquals(listOf(2L, 1L), ordenado.map { it.fileId })
    }

    @Test
    fun `dentro del mismo nivel manda el orden de idiomas preferidos`() {
        val ordenado = ordenarSubtitulos(
            listOf(sub(1, "en"), sub(2, "es")),
            listOf("es", "en"),
        )
        assertEquals(listOf(2L, 1L), ordenado.map { it.fileId })
    }

    /**
     * El bug que motivó comparar por subetiqueta base: se pide "es" pero OpenSubtitles responde
     * "es-419"/"es-MX" para el latino. Comparando el código entero no matchea nunca y el latino
     * —justo el que se quería— se iba al fondo, debajo del inglés.
     */
    @Test
    fun `las variantes del espanol cuentan como espanol y no caen al fondo`() {
        val ordenado = ordenarSubtitulos(
            listOf(sub(1, "en"), sub(2, "es-419"), sub(3, "es-MX")),
            listOf("es", "en"),
        )
        assertEquals(listOf(2L, 3L, 1L), ordenado.map { it.fileId })
    }

    @Test
    fun `un idioma que no esta en la preferencia queda ultimo`() {
        val ordenado = ordenarSubtitulos(
            listOf(sub(1, "fr"), sub(2, "en")),
            listOf("es", "en"),
        )
        assertEquals(listOf(2L, 1L), ordenado.map { it.fileId })
    }

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
