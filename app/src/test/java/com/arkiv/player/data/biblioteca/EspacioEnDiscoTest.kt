package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.GroupedEpisode
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

class EspacioEnDiscoTest {

    private val GB = 1L shl 30

    // Ojo: es `data.model.Episode` (el del dominio), NO `db.EpisodeEntity`.
    private fun episodio(id: String) = Episode(
        id = id,
        itemId = "item",
        section = "",
        displayName = id,
        orderIndex = 0,
        durationSeconds = 0.0,
        thumbPath = null,
    )

    private fun bajado(id: String, bytesDone: Long) = GroupedEpisode(
        episode = episodio(id),
        status = EpisodeDownloadStatus.Tracked(
            DownloadRow(
                episodeId = id,
                itemId = "item",
                itemTitle = "item",
                displayName = id,
                thumbPath = null,
                state = LocalDownloadState.COMPLETED,
                progress = 1f,
                localUri = null,
                bytes = bytesDone,
                source = "web",
                error = null,
                bytesDone = bytesDone,
            ),
        ),
    )

    private fun sinBajar(id: String) =
        GroupedEpisode(episode = episodio(id), status = EpisodeDownloadStatus.NotDownloaded)

    private fun grupo(vararg eps: GroupedEpisode) =
        DownloadGroup("item", "Serie", "", "web", eps.toList())

    @Test
    fun `suma los bytes de todos los grupos`() {
        val grupos = listOf(grupo(bajado("a", 2 * GB)), grupo(bajado("b", 1 * GB)))
        assertEquals(3 * GB, EspacioEnDisco.ocupadoPorDescargas(grupos))
    }

    @Test
    fun `los episodios sin descargar no suman`() {
        assertEquals(GB, EspacioEnDisco.ocupadoPorDescargas(listOf(grupo(bajado("a", GB), sinBajar("b")))))
    }

    @Test
    fun `sin descargas el ocupado es cero`() {
        assertEquals(0L, EspacioEnDisco.ocupadoPorDescargas(emptyList()))
    }

    @Test
    fun `el resumen muestra libres y ocupado`() {
        assertEquals("12.0 GB libres  ·  3.0 GB en descargas", EspacioEnDisco.resumen(12 * GB, 3 * GB))
    }

    @Test
    fun `sin nada ocupado el resumen solo dice libres`() {
        assertEquals("12.0 GB libres", EspacioEnDisco.resumen(12 * GB, 0L))
    }
}
