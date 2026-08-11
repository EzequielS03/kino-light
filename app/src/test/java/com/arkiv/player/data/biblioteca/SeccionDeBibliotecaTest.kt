package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeccionDeBibliotecaTest {

    private fun row(id: String, categoria: String?, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = categoria,
        source = "web",
    )

    private val serie = LibraryGroup("tv:1", row("s", "series", 24), listOf(row("s", "series", 24)))
    private val peli = LibraryGroup("item:p", row("p", "movie", 1), listOf(row("p", "movie", 1)))
    private val todos = listOf(serie, peli)

    @Test
    fun `todo devuelve los grupos sin tocar`() {
        assertEquals(todos, FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.TODO_LO_GUARDADO, todos))
    }

    @Test
    fun `series deja fuera las peliculas`() {
        assertEquals(listOf(serie), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.SERIES, todos))
    }

    @Test
    fun `peliculas deja fuera las series`() {
        assertEquals(listOf(peli), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.PELICULAS, todos))
    }

    /** Un ítem de un solo episodio sin override es película por detección automática. */
    @Test
    fun `un item de un episodio sin override cuenta como pelicula`() {
        val suelto = LibraryGroup("item:x", row("x", null, 1), listOf(row("x", null, 1)))
        assertEquals(listOf(suelto), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.PELICULAS, listOf(suelto)))
    }

    /** Estas dos NO salen de la biblioteca guardada: pedirlas acá es un error del llamador. */
    @Test
    fun `vistos y descargas no salen de esta lista`() {
        assertNull(FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.VISTOS, todos))
        assertNull(FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.DESCARGAS, todos))
    }

    @Test
    fun `las etiquetas del menu van en el orden de la pantalla`() {
        assertEquals(
            listOf("Todo", "Series", "Películas", "Ya visto", "Descargas"),
            SeccionDeBiblioteca.entries.map { it.etiqueta },
        )
    }
}
