package com.arkiv.player.data.catalog

import com.arkiv.player.torrent.TorrentFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PackRowBuilderTest {
    @Test fun `series arma etiqueta seccion y orden por temporada-episodio`() {
        val rows = PackRowBuilder.build(listOf(
            TorrentFile(0, "Show.S01E02.1080p.mkv", 200),
            TorrentFile(1, "Show.S01E01.1080p.mkv", 100),
        ))
        // Ordenadas por orderIndex (1002 > 1001): E01 primero.
        assertEquals(listOf(1, 0), rows.map { it.index })
        assertEquals("T1 · E1", rows[0].label)
        assertEquals("Temporada 1", rows[0].section)
        assertEquals(1001, rows[0].orderIndex)
        assertEquals("1080p", rows[0].quality)
        assertEquals(100L, rows[0].sizeBytes)
    }

    @Test fun `anime absoluto sin seccion`() {
        val rows = PackRowBuilder.build(listOf(TorrentFile(3, "One Piece - 1085 [720p].mkv", 500)))
        assertEquals("Ep 1085", rows[0].label)
        assertEquals("", rows[0].section)
        assertEquals(1085, rows[0].orderIndex)
    }

    @Test fun `sin parseo usa nombre limpio y posicion`() {
        val rows = PackRowBuilder.build(listOf(TorrentFile(7, "pelicula_random.mkv", 900)))
        assertEquals("", rows[0].section)
        assertEquals(7, rows[0].index)
    }
}
