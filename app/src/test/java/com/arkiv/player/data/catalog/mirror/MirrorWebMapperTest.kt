package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorWebMapperTest {

    @Test
    fun `toWebResult mapea los campos principales`() {
        val mirror = MirrorWebSource(
            siteId = "serieskao",
            pageUrl = "https://serieskao.top/anime/naruto/temporada/1/capitulo/1",
            season = 1,
            episode = 1,
            name = "Entra en escena",
            quality = "",
            langNorm = "latino",
        )

        val result = MirrorWebMapper.toWebResult(mirror)

        assertEquals("https://serieskao.top/anime/naruto/temporada/1/capitulo/1", result.pageUrl)
        assertEquals("serieskao", result.siteId)
        assertEquals("latino", result.language)
        assertEquals("Entra en escena", result.title)
        assertEquals("tv", result.kind)
    }

    @Test
    fun `toWebResult conserva la temporada real del mirror`() {
        val mirror = MirrorWebSource(
            siteId = "serieskao",
            pageUrl = "https://serieskao.top/anime/shingeki/temporada/2/capitulo/5",
            season = 2,
            episode = 5,
            name = "Bestia",
            quality = "",
            langNorm = "latino",
        )

        // Descartarla obligaba a los caminos de "episodio suelto" a inventar season=1 y a pisar la
        // fila que el pack guarda con la temporada real (misma clave: hash de pageUrl).
        assertEquals(2, MirrorWebMapper.toWebResult(mirror).season)
    }

    @Test
    fun `toWebResult con name en blanco cae a TxEy`() {
        val mirror = MirrorWebSource(
            siteId = "serieskao",
            pageUrl = "https://serieskao.top/anime/naruto/temporada/1/capitulo/1",
            season = 1,
            episode = 1,
            name = "",
            quality = "",
            langNorm = "latino",
        )

        val result = MirrorWebMapper.toWebResult(mirror)

        assertEquals("T1E1", result.title)
    }

    @Test
    fun `chooseWebSources prefiere el mirror cuando no esta vacio`() {
        val mirror = listOf("mirror-a", "mirror-b")
        val live = listOf("live-a")

        assertEquals(mirror, MirrorWebMapper.chooseWebSources(mirror, live))
    }

    @Test
    fun `chooseWebSources cae al live cuando el mirror esta vacio`() {
        val mirror = emptyList<String>()
        val live = listOf("live-a")

        assertEquals(live, MirrorWebMapper.chooseWebSources(mirror, live))
    }
}
