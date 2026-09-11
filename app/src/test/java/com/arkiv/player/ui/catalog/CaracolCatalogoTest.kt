package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CaracolCatalogoTest {

    private fun item(id: String, tipo: String, titulo: String = id) =
        DituItem(contentId = id, titulo = titulo, contentType = tipo)

    @Test fun `separa peliculas y series`() {
        val serie = item("1", "BUNDLE", "Serie A")
        val pelicula = item("2", "VOD", "Película A")

        val catalogo = CaracolCatalogo.de(listOf(serie, pelicula))

        assertEquals(listOf(serie), catalogo.series)
        assertEquals(listOf(pelicula), catalogo.peliculas)
    }

    @Test fun `quita duplicados por ref conservando el orden`() {
        val a = item("1", "VOD", "A")
        val b = item("2", "VOD", "B")
        val aRepetida = item("1", "VOD", "A de nuevo")

        val catalogo = CaracolCatalogo.de(listOf(a, b, aRepetida))

        assertEquals(listOf(a, b), catalogo.peliculas)
    }

    @Test fun `un catalogo vacio da las dos listas vacias`() {
        val catalogo = CaracolCatalogo.de(emptyList())

        assertEquals(emptyList<DituItem>(), catalogo.series)
        assertEquals(emptyList<DituItem>(), catalogo.peliculas)
    }

    @Test fun `GROUP_OF_BUNDLES cuenta como serie, no como pelicula`() {
        val grupo = item("1", "GROUP_OF_BUNDLES", "Grupo A")

        val catalogo = CaracolCatalogo.de(listOf(grupo))

        assertEquals(listOf(grupo), catalogo.series)
        assertEquals(emptyList<DituItem>(), catalogo.peliculas)
    }
}
