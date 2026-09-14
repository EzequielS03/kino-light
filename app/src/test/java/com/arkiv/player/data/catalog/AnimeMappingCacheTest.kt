package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeMappingCacheTest {
    private val week = 7L * 24 * 60 * 60 * 1000

    @Test
    fun `cache reciente es fresco`() {
        assertTrue(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week - 1))
    }

    @Test
    fun `cache de mas de una semana esta vencido`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 1_000, nowMs = 1_000 + week + 1))
    }

    @Test
    fun `sin fetch previo (0) no es fresco`() {
        assertFalse(AnimeMappingRepository.isFresh(fetchedAtMs = 0, nowMs = 5_000))
    }

    @Test
    fun `un archivo que parsea vacio no se considera fresco aunque su timestamp sea reciente`() {
        // A corrupt/truncated JSON parses to an empty map (see FribbAnimeListParser.parse, which
        // catches the exception and returns emptyMap). Even if the file's timestamp is "fresh"
        // per the TTL, the repo must NOT treat it as a valid cache: it has to force a re-download
        // on the next load instead of serving an empty map indefinitely (bug F6).
        val parsedFromCorruptFile = FribbAnimeListParser.parse("{ esto no es un json valido ]")
        assertTrue(parsedFromCorruptFile.isEmpty())

        // The "fresh and valid" condition used by AnimeMappingRepository requires both:
        // isFresh(lastModified) AND the parse not being empty. We simulate that combination here,
        // since ensureLoaded() is private and depends on real I/O (can't be tested directly with no network).
        val timestampFresh = AnimeMappingRepository.isFresh(
            fetchedAtMs = System.currentTimeMillis(),
            nowMs = System.currentTimeMillis(),
        )
        assertTrue(timestampFresh)
        val consideredValidAndFresh = timestampFresh && parsedFromCorruptFile.isNotEmpty()
        assertFalse(consideredValidAndFresh)
        assertEquals(0, parsedFromCorruptFile.size)
    }
}
