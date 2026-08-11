package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupPolicyTest {

    private fun episode(id: String, itemId: String, order: Int, name: String = id) = Episode(
        id = id,
        itemId = itemId,
        section = "",
        displayName = name,
        orderIndex = order,
        durationSeconds = 0.0,
        thumbPath = null,
        original = null,
        derivative = null,
    )

    private fun row(
        episodeId: String,
        itemId: String,
        state: String,
        displayName: String = episodeId,
    ) = DownloadRow(
        episodeId = episodeId,
        itemId = itemId,
        itemTitle = "Serie $itemId",
        displayName = displayName,
        thumbPath = null,
        state = state,
        progress = 0f,
        localUri = null,
        bytes = 0,
        source = "archive",
        error = null,
        bytesDone = 0,
    )

    private fun meta(itemId: String) = mapOf(
        itemId to DownloadItemMeta(title = "Serie $itemId", thumbnailUrl = "https://x/$itemId.jpg", source = "archive"),
    )

    // --- buildGroups -----------------------------------------------------------------------

    @Test
    fun `el grupo trae TODOS los episodios del item, no solo los encolados`() {
        val episodes = listOf(episode("s::1", "s", 0), episode("s::2", "s", 1), episode("s::3", "s", 2))
        val downloads = listOf(row("s::2", "s", LocalDownloadState.COMPLETED))

        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))

        assertEquals(1, groups.size)
        assertEquals(3, groups[0].episodes.size)
        assertEquals(
            listOf(EpisodeDownloadStatus.NotDownloaded::class, EpisodeDownloadStatus.Tracked::class, EpisodeDownloadStatus.NotDownloaded::class),
            groups[0].episodes.map { it.status::class },
        )
    }

    @Test
    fun `los episodios quedan ordenados por orderIndex sin importar el orden de entrada`() {
        val episodes = listOf(episode("s::3", "s", 2), episode("s::1", "s", 0), episode("s::2", "s", 1))
        val downloads = listOf(row("s::1", "s", LocalDownloadState.QUEUED))

        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))

        assertEquals(listOf("s::1", "s::2", "s::3"), groups[0].episodes.map { it.episode.id })
    }

    @Test
    fun `un item sin metadata (borrado de la biblioteca) se excluye del listado`() {
        val downloads = listOf(row("s::1", "s", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to listOf(episode("s::1", "s", 0))), itemMeta = emptyMap())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `un item sin cache de episodios todavia cae a los tracked de downloads`() {
        val downloads = listOf(
            row("s::1", "s", LocalDownloadState.DOWNLOADING),
            row("s::2", "s", LocalDownloadState.QUEUED),
        )
        val groups = DownloadGroupPolicy.buildGroups(downloads, episodesByItem = emptyMap(), itemMeta = meta("s"))

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].episodes.size)
        assertTrue(groups[0].episodes.all { it.status is EpisodeDownloadStatus.Tracked })
    }

    @Test
    fun `el orden de los grupos sigue el orden de aparicion en downloads`() {
        val downloads = listOf(
            row("b::1", "b", LocalDownloadState.QUEUED),
            row("a::1", "a", LocalDownloadState.QUEUED),
            row("b::2", "b", LocalDownloadState.QUEUED),
        )
        val episodesByItem = mapOf(
            "a" to listOf(episode("a::1", "a", 0)),
            "b" to listOf(episode("b::1", "b", 0), episode("b::2", "b", 1)),
        )
        val itemMeta = meta("a") + meta("b")

        val groups = DownloadGroupPolicy.buildGroups(downloads, episodesByItem, itemMeta)

        assertEquals(listOf("b", "a"), groups.map { it.itemId })
    }

    @Test
    fun `pelicula (un solo episodio) es de fila simple`() {
        val downloads = listOf(row("m::1", "m", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("m" to listOf(episode("m::1", "m", 0))), meta("m"))
        assertTrue(groups[0].isSingleEpisode)
    }

    @Test
    fun `serie con un solo episodio trackeado pero varios en total NO es de fila simple`() {
        val episodes = listOf(episode("s::1", "s", 0), episode("s::2", "s", 1))
        val downloads = listOf(row("s::1", "s", LocalDownloadState.COMPLETED))
        val groups = DownloadGroupPolicy.buildGroups(downloads, mapOf("s" to episodes), meta("s"))
        assertTrue(!groups[0].isSingleEpisode)
    }

    // --- summarize ---------------------------------------------------------------------------

    @Test
    fun `resumen base sin actividad extra`() {
        val episodes = (1..24).map {
            val state = if (it <= 3) LocalDownloadState.COMPLETED else null
            GroupedEpisode(
                episode("s::$it", "s", it),
                state?.let { st -> EpisodeDownloadStatus.Tracked(row("s::$it", "s", st)) } ?: EpisodeDownloadStatus.NotDownloaded,
            )
        }
        assertEquals("3 de 24 guardados", DownloadGroupPolicy.summarize(episodes))
    }

    @Test
    fun `resumen con una descarga en curso, igual al ejemplo del pedido`() {
        val episodes = mutableListOf<GroupedEpisode>()
        repeat(3) { i -> episodes += GroupedEpisode(episode("s::c$i", "s", i), EpisodeDownloadStatus.Tracked(row("s::c$i", "s", LocalDownloadState.COMPLETED))) }
        episodes += GroupedEpisode(episode("s::d", "s", 3), EpisodeDownloadStatus.Tracked(row("s::d", "s", LocalDownloadState.DOWNLOADING)))
        repeat(20) { i -> episodes += GroupedEpisode(episode("s::n$i", "s", 4 + i), EpisodeDownloadStatus.NotDownloaded) }

        assertEquals("3 de 24 guardados · 1 bajando", DownloadGroupPolicy.summarize(episodes))
    }

    @Test
    fun `resumen encadena todas las clausulas no vacias en orden fijo`() {
        val episodes = listOf(
            GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.COMPLETED))),
            GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.DOWNLOADING))),
            GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.STAGING))),
            GroupedEpisode(episode("s::4", "s", 3), EpisodeDownloadStatus.Tracked(row("s::4", "s", LocalDownloadState.QUEUED))),
            GroupedEpisode(episode("s::5", "s", 4), EpisodeDownloadStatus.Tracked(row("s::5", "s", LocalDownloadState.FAILED))),
            GroupedEpisode(episode("s::6", "s", 5), EpisodeDownloadStatus.Tracked(row("s::6", "s", LocalDownloadState.NEEDS_CONFIRMATION))),
        )

        assertEquals(
            "1 de 6 guardados · 1 bajando · 1 preparando · 1 en cola · 1 con error · 1 por confirmar",
            DownloadGroupPolicy.summarize(episodes),
        )
    }

    @Test
    fun `sin episodios el resumen es 0 de 0`() {
        assertEquals("0 de 0 guardados", DownloadGroupPolicy.summarize(emptyList()))
    }

    // --- filtros de acciones de grupo -------------------------------------------------------

    @Test
    fun `activeEpisodeIds toma encolados, bajando y preparando, y descarta el resto`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.QUEUED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.DOWNLOADING))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.STAGING))),
                GroupedEpisode(episode("s::4", "s", 3), EpisodeDownloadStatus.Tracked(row("s::4", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::5", "s", 4), EpisodeDownloadStatus.Tracked(row("s::5", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::6", "s", 5), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(listOf("s::1", "s::2", "s::3"), DownloadGroupPolicy.activeEpisodeIds(group))
    }

    @Test
    fun `failedEpisodeIds toma solo failed, no needs_confirmation`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.NEEDS_CONFIRMATION))),
            ),
        )
        assertEquals(listOf("s::1"), DownloadGroupPolicy.failedEpisodeIds(group))
    }

    @Test
    fun `trackedEpisodeIds toma todo lo que tiene fila, sin importar el estado`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.FAILED))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(listOf("s::1", "s::2"), DownloadGroupPolicy.trackedEpisodeIds(group))
    }

    // --- firstPlayableEpisodeId ---------------------------------------------------------------

    @Test
    fun `firstPlayableEpisodeId toma el primer completado en el orden del grupo, no el primero en terminar`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.NotDownloaded),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.Tracked(row("s::2", "s", LocalDownloadState.COMPLETED))),
                GroupedEpisode(episode("s::3", "s", 2), EpisodeDownloadStatus.Tracked(row("s::3", "s", LocalDownloadState.COMPLETED))),
            ),
        )
        assertEquals("s::2", DownloadGroupPolicy.firstPlayableEpisodeId(group))
    }

    @Test
    fun `firstPlayableEpisodeId es null si ningun episodio esta completado`() {
        val group = DownloadGroup(
            itemId = "s", itemTitle = "Serie", itemThumbnailUrl = "", source = "archive",
            episodes = listOf(
                GroupedEpisode(episode("s::1", "s", 0), EpisodeDownloadStatus.Tracked(row("s::1", "s", LocalDownloadState.DOWNLOADING))),
                GroupedEpisode(episode("s::2", "s", 1), EpisodeDownloadStatus.NotDownloaded),
            ),
        )
        assertEquals(null, DownloadGroupPolicy.firstPlayableEpisodeId(group))
    }
}
