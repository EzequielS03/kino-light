package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTabTest {

    private fun magis() = PlaySource.Magis(
        GatewayResult(source = "magis", title = "Duna", ref = "r", year = "2021"),
    )

    @Test
    fun `magis tiene su propia pestana`() {
        assertEquals(SourceTab.MAGIS, tabOf(magis()))
    }

    @Test
    fun `todo sigue contando todas las fuentes`() {
        val conteos = countsByTab(listOf(magis()))
        assertEquals(1, conteos[SourceTab.TODO])
        assertEquals(1, conteos[SourceTab.MAGIS])
        assertEquals(0, conteos[SourceTab.TORRENT])
    }

    @Test
    fun `los chips no bailan- siempre estan todas las claves`() {
        assertEquals(SourceTab.entries.size, countsByTab(emptyList()).size)
    }

    @Test
    fun `filtrar por magis deja solo magis`() {
        assertEquals(1, filterByTab(listOf(magis()), SourceTab.MAGIS).size)
        assertEquals(0, filterByTab(listOf(magis()), SourceTab.WEB).size)
    }

    @Test
    fun `el episodeId de magis se reconoce como MAGIS`() {
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor("magis:abc123"))
        assertEquals(SourceKind.TORRENT, PlayerSource.kindFor("torrent:abc"))
        assertEquals(SourceKind.WEB, PlayerSource.kindFor("web:abc"))
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("cualquier-otra-cosa"))
    }
}
