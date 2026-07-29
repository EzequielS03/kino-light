package com.arkiv.player.data

import com.arkiv.player.data.catalog.PackFileRow
import org.junit.Assert.assertEquals
import org.junit.Test

class PackEntitiesTest {
    private val rows = listOf(
        PackFileRow(1, "T1 · E1", 100, "1080p", "Temporada 1", 1001),
        PackFileRow(0, "T1 · E2", 200, "1080p", "Temporada 1", 1002),
    )

    @Test fun `arma item pack y episodios por archivo`() {
        val (item, eps) = PackEntities.build(
            title = "House of the Dragon — Pack", posterUrl = "http://p/x.jpg", description = "desc",
            infoHashHex = "abc123", infoBase64 = "BYTES", rows = rows, addedAt = 42L,
        )
        assertEquals("torrent:abc123", item.identifier)
        assertEquals("House of the Dragon — Pack", item.title)
        assertEquals("desc", item.description)
        assertEquals("http://p/x.jpg", item.thumbnailUrl)
        assertEquals("series", item.categoryOverride)
        assertEquals("torrent", item.source)
        assertEquals("BYTES", item.torrentData)
        assertEquals(42L, item.addedAt)

        assertEquals(2, eps.size)
        val e1 = eps.first { it.displayName == "T1 · E1" }
        assertEquals("torrent:abc123::1", e1.id)
        assertEquals("torrent:abc123", e1.itemId)
        assertEquals("Temporada 1", e1.section)
        assertEquals(1001, e1.orderIndex)
        assertEquals(1, e1.torrentFileIndex)
        assertEquals(100L, e1.originalSize)
        assertEquals(null, e1.torrentData) // el .torrent vive en el ítem, no por episodio
    }
}
