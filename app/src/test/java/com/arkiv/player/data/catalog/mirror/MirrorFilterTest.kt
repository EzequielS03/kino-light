package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.providers.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorFilterTest {
    private fun t(season: Int? = null, episode: Int? = null, episodeEnd: Int? = null,
                  isPack: Boolean = false, size: Long = 1L, tag: String = "") =
        MirrorTorrent("magnet:$tag", tag.ifBlank { "h$season$episode" }, season, episode, episodeEnd, isPack,
            "LATINO", "raw", "1080p", 1, size, "1B", "src", "n$tag")

    @Test fun `movie devuelve todos y aplica corte de tamaño (packs exentos)`() {
        val big = 30L shl 30; val small = 1L shl 30; val max = 21L shl 30
        val list = listOf(t(size = small, tag = "a"), t(size = big, tag = "b"),
            t(size = big, isPack = true, tag = "c"))
        val r = MirrorFilter.select(list, ContentType.MOVIE, 0, emptySet(), max)
        assertEquals(setOf("magnet:a", "magnet:c"), r.map { it.magnet }.toSet()) // b cae por tamaño
    }

    @Test fun `serie filtra por temporada y episodio exactos`() {
        val list = listOf(
            t(season = 1, episode = 1, tag = "ok"),
            t(season = 2, episode = 1, tag = "otraTemp"),
            t(season = 1, episode = 2, tag = "otroEp"),
        )
        val r = MirrorFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(1), maxSizeBytes = 0)
        assertEquals(listOf("magnet:ok"), r.map { it.magnet })
    }

    @Test fun `serie incluye pack que cubre el episodio por rango`() {
        val list = listOf(t(season = 1, episode = 1, episodeEnd = 8, isPack = true, tag = "pack"))
        val r = MirrorFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(5), maxSizeBytes = 0)
        assertEquals(listOf("magnet:pack"), r.map { it.magnet })
    }

    @Test fun `serie excluye pack de rango de otra temporada`() {
        val list = listOf(t(season = 2, episode = 1, episodeEnd = 8, isPack = true, tag = "otraTemp"))
        val r = MirrorFilter.select(list, ContentType.TV, season = 1, episodeNumbers = setOf(5), maxSizeBytes = 0)
        assertEquals(emptyList<String>(), r.map { it.magnet })
    }

    @Test fun `anime matchea nº absoluto o relativo sin exigir temporada`() {
        val list = listOf(
            t(season = 1, episode = 1085, tag = "abs"),   // fansub S01E<absoluto>
            t(season = 21, episode = 5, tag = "rel"),     // numeración relativa
            t(season = 1, episode = 3, tag = "no"),
        )
        val r = MirrorFilter.select(list, ContentType.ANIME, season = 0,
            episodeNumbers = setOf(5, 1085), maxSizeBytes = 0)
        assertEquals(setOf("magnet:abs", "magnet:rel"), r.map { it.magnet }.toSet())
    }

    @Test fun `selectAll devuelve todo respetando el corte de tamaño`() {
        val list = listOf(t(size = 1L shl 30, tag = "a"), t(size = 30L shl 30, tag = "b"))
        val r = MirrorFilter.selectAll(list, 21L shl 30)
        assertEquals(listOf("magnet:a"), r.map { it.magnet })
    }
}
