package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.FilaDeHistorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySignalsTest {

    private var reloj = 1_000L
    private fun fila(
        item: String,
        pos: Long,
        dur: Long,
        visto: Boolean = false,
        titulo: String = item,
        tipo: String? = "movie",
        episodio: Int? = null,
    ) = FilaDeHistorial(
        episodeId = "$item::$reloj", positionMs = pos, durationMs = dur, watched = visto,
        lastPlayedAt = reloj--, episodio = episodio, itemId = item, titulo = titulo,
        tituloCanonico = null, tipo = tipo, categoryOverride = null, tmdbId = null,
    )

    @Test fun `watched is terminado`() {
        assertEquals("terminado", HistorySignals.of(listOf(fila("a", 100, 100, visto = true))).single().status)
    }

    @Test fun `less than ten percent is abandonado`() {
        assertEquals("abandonado", HistorySignals.of(listOf(fila("a", 5, 100))).single().status)
    }

    /** Halfway through says nothing: it's being watched right now. */
    @Test fun `halfway through does not count`() {
        assertTrue(HistorySignals.of(listOf(fila("a", 50, 100))).isEmpty())
    }

    @Test fun `halfway through lets an earlier row of the same item decide`() {
        val v = HistorySignals.of(listOf(fila("a", 50, 100), fila("a", 100, 100, visto = true)))
        assertEquals(listOf("terminado"), v.map { it.status })
    }

    @Test fun `an item counts only once`() {
        val v = HistorySignals.of(listOf(fila("a", 100, 100, visto = true), fila("a", 5, 100)))
        assertEquals(1, v.size)
    }

    @Test fun `with no duration it is not known whether it was abandoned`() {
        assertTrue(HistorySignals.of(listOf(fila("a", 0, 0))).isEmpty())
    }

    @Test fun `keeps the most recent ones up to the cap`() {
        val filas = (1..40).map { fila("i$it", 100, 100, visto = true) }
        val v = HistorySignals.of(filas)
        assertEquals(HistorySignals.CAP, v.size)
        assertEquals("i1", v.first().title)
    }

    @Test fun `the canonical title wins`() {
        val f = fila("a", 100, 100, visto = true, titulo = "Shin seiki Temp.1").copy(tituloCanonico = "Neon Genesis Evangelion")
        assertEquals("Neon Genesis Evangelion", HistorySignals.of(listOf(f)).single().title)
    }

    @Test fun `the kind comes from the same rule as the trivia fact`() {
        val f = fila("a", 100, 100, visto = true, tipo = null, episodio = 3)
        assertEquals("tv", HistorySignals.of(listOf(f)).single().kind)
    }

    @Test fun `the lines have the gateway's shape`() {
        val r = HistorySignals.lines(listOf(Watched("Coco", "movie", "terminado"), Watched("Naruto", "tv", "abandonado")))
        assertEquals("- Coco (movie): terminado\n- Naruto (tv): abandonado", r)
    }
}
