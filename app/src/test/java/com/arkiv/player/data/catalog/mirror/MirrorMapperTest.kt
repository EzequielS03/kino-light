package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorMapperTest {
    private fun t(langNorm: String?, name: String? = null, quality: String? = null, seeders: Int? = null) =
        MirrorTorrent(magnet = "magnet:?xt=urn:btih:H", infohash = "h", season = null, episode = null,
            episodeEnd = null, isPack = false, langNorm = langNorm, langRaw = "raw", quality = quality,
            seeders = seeders, sizeBytes = 1_000_000_000L, sizeLabel = "0.9 GB", source = "x", name = name)

    @Test fun `lang_norm mapea a TorrentLang`() {
        assertEquals(TorrentLang.LATINO, MirrorLang.fromNorm("LATINO"))
        assertEquals(TorrentLang.CASTELLANO, MirrorLang.fromNorm("Castellano"))
        assertEquals(TorrentLang.DUAL, MirrorLang.fromNorm("DUAL"))
        assertEquals(TorrentLang.OTHER, MirrorLang.fromNorm(null))
        assertEquals(TorrentLang.OTHER, MirrorLang.fromNorm("marciano"))
    }

    @Test fun `toResult usa magnet, lang y seeders null como 0`() {
        val r = MirrorMapper.toResult(t(langNorm = "LATINO", name = "Peli 1080p"))
        assertEquals(TorrentLang.LATINO, r.lang)
        assertEquals("magnet:?xt=urn:btih:H", r.magnetUri)
        assertEquals(0, r.seeders)
        assertEquals(1_000_000_000L, r.sizeBytes)
    }

    @Test fun `toResult sintetiza nombre desde quality cuando name es null`() {
        val r = MirrorMapper.toResult(t(langNorm = "CASTELLANO", name = null, quality = "WEB-DL 1080p"))
        assertEquals(true, r.name.contains("1080p")) // qualityRank necesita ver la calidad
    }
}
