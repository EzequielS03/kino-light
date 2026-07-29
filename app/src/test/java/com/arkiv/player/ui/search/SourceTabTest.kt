package com.arkiv.player.ui.search

import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTabTest {
    private fun torrent(name: String) =
        PlaySource.Torrent(TorrentResult(name = name, seeders = 1, sizeBytes = 1L, lang = TorrentLang.LATINO))

    private fun web(url: String) =
        PlaySource.Web(WebResult(siteId = "serieskao", siteName = "serieskao", title = "x", year = "",
            pageUrl = url, posterUrl = "", language = "latino", kind = "tv"))

    private fun webPack(site: String, eps: Int) = PlaySource.WebPack(
        MirrorWebPack(site, "Show", (1..eps).map {
            MirrorWebSource(site, "https://$site/$it", 1, it, "E$it", "", "latino")
        }),
    )

    private fun archive(id: String) = PlaySource.Archive(ArchiveSearchResult(identifier = id, title = id, year = ""))

    private val all = listOf(
        torrent("a"), torrent("b"),
        web("https://x/1"), webPack("serieskao", 3),
        archive("i1"),
    )

    @Test fun `cuenta por pestaña, con los packs web dentro de WEB`() {
        assertEquals(
            mapOf(SourceTab.TODO to 5, SourceTab.TORRENT to 2, SourceTab.WEB to 2, SourceTab.ARCHIVE to 1),
            countsByTab(all),
        )
    }

    @Test fun `TODO no filtra nada`() {
        assertEquals(all, filterByTab(all, SourceTab.TODO))
    }

    @Test fun `cada pestaña deja solo su tipo`() {
        assertEquals(2, filterByTab(all, SourceTab.TORRENT).size)
        assertEquals(1, filterByTab(all, SourceTab.ARCHIVE).size)
        // WEB junta capítulos sueltos y packs de serie: son la misma fuente para el usuario.
        assertEquals(
            listOf<PlaySource>(all[2], all[3]),
            filterByTab(all, SourceTab.WEB),
        )
    }

    @Test fun `sin fuentes todas las cuentas son cero`() {
        assertEquals(
            mapOf(SourceTab.TODO to 0, SourceTab.TORRENT to 0, SourceTab.WEB to 0, SourceTab.ARCHIVE to 0),
            countsByTab(emptyList()),
        )
    }
}
