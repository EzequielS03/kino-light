package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.FilaDeHistorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenalesDeHistorialTest {

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

    @Test fun `visto es terminado`() {
        assertEquals("terminado", SenalesDeHistorial.de(listOf(fila("a", 100, 100, visto = true))).single().estado)
    }

    @Test fun `menos del diez por ciento es abandonado`() {
        assertEquals("abandonado", SenalesDeHistorial.de(listOf(fila("a", 5, 100))).single().estado)
    }

    /** A mitad de camino no dice nada: lo estás viendo ahora. */
    @Test fun `a mitad de camino no cuenta`() {
        assertTrue(SenalesDeHistorial.de(listOf(fila("a", 50, 100))).isEmpty())
    }

    @Test fun `a mitad de camino deja decidir a una fila anterior del mismo item`() {
        val v = SenalesDeHistorial.de(listOf(fila("a", 50, 100), fila("a", 100, 100, visto = true)))
        assertEquals(listOf("terminado"), v.map { it.estado })
    }

    @Test fun `un item cuenta una sola vez`() {
        val v = SenalesDeHistorial.de(listOf(fila("a", 100, 100, visto = true), fila("a", 5, 100)))
        assertEquals(1, v.size)
    }

    @Test fun `sin duracion no se sabe si se abandono`() {
        assertTrue(SenalesDeHistorial.de(listOf(fila("a", 0, 0))).isEmpty())
    }

    @Test fun `se queda con los mas recientes hasta el tope`() {
        val filas = (1..40).map { fila("i$it", 100, 100, visto = true) }
        val v = SenalesDeHistorial.de(filas)
        assertEquals(SenalesDeHistorial.TOPE, v.size)
        assertEquals("i1", v.first().titulo)
    }

    @Test fun `el titulo canonico gana`() {
        val f = fila("a", 100, 100, visto = true, titulo = "Shin seiki Temp.1").copy(tituloCanonico = "Neon Genesis Evangelion")
        assertEquals("Neon Genesis Evangelion", SenalesDeHistorial.de(listOf(f)).single().titulo)
    }

    @Test fun `el tipo sale de la misma regla que el dato curioso`() {
        val f = fila("a", 100, 100, visto = true, tipo = null, episodio = 3)
        assertEquals("tv", SenalesDeHistorial.de(listOf(f)).single().tipo)
    }

    @Test fun `los renglones tienen la forma del gateway`() {
        val r = SenalesDeHistorial.renglones(listOf(Vista("Coco", "movie", "terminado"), Vista("Naruto", "tv", "abandonado")))
        assertEquals("- Coco (movie): terminado\n- Naruto (tv): abandonado", r)
    }
}
