package com.arkiv.player.ui.search

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class PackFirstOrderTest {
    private fun t(name: String) = PlaySource.Torrent(
        TorrentResult(name = name, seeders = 1, sizeBytes = 1L, lang = TorrentLang.LATINO)
    )

    @Test fun `los packs van primero`() {
        val out = packsFirst(listOf(
            t("Naruto Shippuden 268 spanish"),
            t("Naruto TODAS LAS TEMPORADAS 100% latino"),
            t("Naruto 671 [narutouchiha]"),
        ))
        assertEquals("Naruto TODAS LAS TEMPORADAS 100% latino", out.first().result.name)
    }

    @Test fun `conserva el orden relativo dentro de cada grupo`() {
        val out = packsFirst(listOf(t("Serie A 01"), t("Serie A 02"), t("Serie A 03")))
        assertEquals(listOf("Serie A 01", "Serie A 02", "Serie A 03"), out.map { it.result.name })
    }
}
