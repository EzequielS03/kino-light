package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Ya visto" se arma cruzando el progreso por ítem con los grupos de la biblioteca, para que una
 * serie guardada desde dos fuentes sea UNA tarjeta y no dos.
 */
class VistosDeLaBibliotecaTest {

    private fun row(id: String, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = "series",
        source = "web",
    )

    private fun grupo(key: String, vararg filas: LibraryRow) =
        LibraryGroup(key = key, primary = filas.first(), members = filas.toList())

    @Test
    fun `el conteo del grupo es el maximo entre fuentes, no la suma`() {
        // Misma serie por dos fuentes: 12 y 10 capítulos vistos. Son copias alternativas del MISMO
        // contenido, así que sumarlas (22) mentiría igual que sumaba 794 episodios de Naruto.
        val g = grupo("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val vistos = listOf(
            VistoDeItem("web:series:a", episodios = 12, ultimoVistoMs = 100L),
            VistoDeItem("torrent:series:a", episodios = 10, ultimoVistoMs = 50L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(g), vistos)
        assertEquals(1, r.size)
        assertEquals(12, r.first().capitulosVistos)
    }

    @Test
    fun `el ultimo visto del grupo es el mas reciente entre fuentes`() {
        val g = grupo("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val vistos = listOf(
            VistoDeItem("web:series:a", episodios = 12, ultimoVistoMs = 100L),
            VistoDeItem("torrent:series:a", episodios = 10, ultimoVistoMs = 900L),
        )
        assertEquals(900L, VistosDeLaBiblioteca.cruzar(listOf(g), vistos).first().ultimoVistoMs)
    }

    @Test
    fun `un grupo sin nada visto queda afuera`() {
        val visto = grupo("tv:1", row("web:series:visto", 10))
        val sinVer = grupo("tv:2", row("web:series:sinver", 10))
        val vistos = listOf(VistoDeItem("web:series:visto", 3, 10L))
        val r = VistosDeLaBiblioteca.cruzar(listOf(visto, sinVer), vistos)
        assertEquals(listOf("tv:1"), r.map { it.grupo.key })
    }

    /** Un ítem que se quitó de la biblioteca deja su progreso en `playback`: no debe reaparecer. */
    @Test
    fun `una fila de visto sin grupo se ignora`() {
        val g = grupo("tv:1", row("web:series:a", 10))
        val vistos = listOf(
            VistoDeItem("web:series:a", 3, 10L),
            VistoDeItem("web:series:borrado", 5, 999L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(g), vistos)
        assertEquals(listOf("tv:1"), r.map { it.grupo.key })
    }

    @Test
    fun `ordena por ultimo visto, el mas reciente primero`() {
        val viejo = grupo("tv:viejo", row("web:series:viejo", 10))
        val nuevo = grupo("tv:nuevo", row("web:series:nuevo", 10))
        val vistos = listOf(
            VistoDeItem("web:series:viejo", 1, 100L),
            VistoDeItem("web:series:nuevo", 1, 900L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(viejo, nuevo), vistos)
        assertEquals(listOf("tv:nuevo", "tv:viejo"), r.map { it.grupo.key })
    }

    @Test
    fun `sin vistos devuelve vacio`() {
        val g = grupo("tv:1", row("web:series:a", 10))
        assertEquals(emptyList<GrupoVisto>(), VistosDeLaBiblioteca.cruzar(listOf(g), emptyList()))
    }
}
