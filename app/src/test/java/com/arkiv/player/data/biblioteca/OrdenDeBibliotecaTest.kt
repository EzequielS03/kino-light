package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El orden de "Mi biblioteca": lo último que viste va primero, y lo recién agregado también, para
 * no tener que ir a buscar abajo la serie que venís viendo.
 */
class OrdenDeBibliotecaTest {

    private fun row(id: String, addedAt: Long) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = 10,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = "series",
        source = "web",
    )

    private fun grupo(key: String, vararg filas: LibraryRow) =
        LibraryGroup(key = key, primary = filas.first(), members = filas.toList())

    @Test
    fun `lo visto mas reciente va primero`() {
        val viejo = row("a", addedAt = 0L)
        val nuevo = row("b", addedAt = 0L)
        val r = OrdenDeBiblioteca.filas(
            listOf(viejo, nuevo),
            mapOf("a" to 100L, "b" to 900L),
        )
        assertEquals(listOf("b", "a"), r.map { it.identifier })
    }

    @Test
    fun `lo recien agregado le gana a lo visto hace rato`() {
        val visto = row("visto", addedAt = 10L)
        val recien = row("recien", addedAt = 900L)
        val r = OrdenDeBiblioteca.filas(listOf(visto, recien), mapOf("visto" to 100L))
        assertEquals(listOf("recien", "visto"), r.map { it.identifier })
    }

    /**
     * El mapa no distingue capítulo terminado de capítulo a medias: los dos llegan como una marca
     * de tiempo. Terminar el E4 anoche tiene que dejar la serie primera hoy, que es el caso que
     * motiva esta funcionalidad.
     */
    @Test
    fun `un item sin reproducciones se ordena por su fecha de agregado`() {
        val sinVer = row("sinver", addedAt = 500L)
        val visto = row("visto", addedAt = 0L)
        val r = OrdenDeBiblioteca.filas(listOf(visto, sinVer), mapOf("visto" to 100L))
        assertEquals(listOf("sinver", "visto"), r.map { it.identifier })
    }

    /** `max(...)` y no la reproducción sola: re-agregar algo viejo lo trae al frente. */
    @Test
    fun `con progreso viejo pero agregado reciente manda el agregado`() {
        val row = row("a", addedAt = 900L)
        assertEquals(900L, OrdenDeBiblioteca.recenciaDe(row, mapOf("a" to 100L)))
    }

    @Test
    fun `la recencia es la reproduccion cuando es posterior al agregado`() {
        val row = row("a", addedAt = 100L)
        assertEquals(900L, OrdenDeBiblioteca.recenciaDe(row, mapOf("a" to 900L)))
    }

    /** Empate: el orden entrante (que viene `addedAt DESC` del SQL) se respeta. */
    @Test
    fun `un empate mantiene el orden entrante`() {
        val primero = row("primero", addedAt = 100L)
        val segundo = row("segundo", addedAt = 100L)
        val r = OrdenDeBiblioteca.filas(listOf(primero, segundo), emptyMap())
        assertEquals(listOf("primero", "segundo"), r.map { it.identifier })
    }

    /**
     * Una serie guardada desde dos fuentes es UNA tarjeta: verla por cualquiera de las dos sube el
     * grupo entero. Mismo criterio que `VistosDeLaBiblioteca`.
     */
    @Test
    fun `la recencia de un grupo es la del miembro mas reciente`() {
        val naruto = grupo("tv:46260", row("web:series:a", 0L), row("torrent:series:a", 0L))
        val otra = grupo("tv:1", row("web:series:otra", 0L))
        val r = OrdenDeBiblioteca.grupos(
            listOf(otra, naruto),
            mapOf("torrent:series:a" to 900L, "web:series:otra" to 100L),
        )
        assertEquals(listOf("tv:46260", "tv:1"), r.map { it.key })
    }

    @Test
    fun `una reproduccion de un item que no esta en la lista no rompe nada`() {
        val a = row("a", addedAt = 100L)
        val r = OrdenDeBiblioteca.filas(listOf(a), mapOf("borrado" to 900L))
        assertEquals(listOf("a"), r.map { it.identifier })
    }
}
