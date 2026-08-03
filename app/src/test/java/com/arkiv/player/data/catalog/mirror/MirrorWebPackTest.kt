package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorWebPackTest {
    private fun w(site: String, season: Int, episode: Int) =
        MirrorWebSource(siteId = site, pageUrl = "https://$site/s$season/e$episode", season = season,
            episode = episode, name = "T${season}E$episode", quality = "", langNorm = "latino")

    @Test fun `agrupa por sitio y cuenta episodios`() {
        val sources = listOf(
            w("serieskao", 1, 2), w("serieskao", 1, 1), w("serieskao", 2, 1),
            w("sololatino", 1, 1),
        )
        val packs = MirrorWebPack.groupBySite("Naruto", sources)
        assertEquals(2, packs.size)
        // el sitio con MAS episodios va primero
        assertEquals("serieskao", packs[0].siteId)
        assertEquals(3, packs[0].episodeCount)
        assertEquals("Naruto", packs[0].showTitle)
        assertEquals(listOf(1, 2), packs[0].seasons)
        assertEquals(1, packs[1].episodeCount)
    }

    @Test fun `ordena los episodios de cada pack por temporada y numero`() {
        val sources = listOf(w("serieskao", 2, 1), w("serieskao", 1, 10), w("serieskao", 1, 2))
        val packs = MirrorWebPack.groupBySite("X", sources)
        assertEquals(
            listOf(1 to 2, 1 to 10, 2 to 1),
            packs[0].episodes.map { it.season to it.episode },
        )
    }

    @Test fun `sin fuentes devuelve lista vacia`() {
        assertEquals(emptyList<MirrorWebPack>(), MirrorWebPack.groupBySite("X", emptyList()))
    }

    // El diálogo de "guardar pack" muestra los capítulos agrupados por temporada (Naruto trae 220
    // en 4 temporadas: una lista plana es inusable y no deja marcar "toda la temporada 2").
    @Test fun `agrupa los episodios por temporada en orden`() {
        val sources = listOf(w("s", 2, 1), w("s", 1, 2), w("s", 1, 1), w("s", 2, 3))
        val pack = MirrorWebPack.groupBySite("X", sources)[0]
        assertEquals(listOf(1, 2), pack.bySeason.map { it.first })
        assertEquals(listOf(1, 2), pack.bySeason[0].second.map { it.episode })
        assertEquals(listOf(1, 3), pack.bySeason[1].second.map { it.episode })
    }

    @Test fun `bySeason de un pack vacio es vacio`() {
        assertEquals(emptyList<Pair<Int, List<MirrorWebSource>>>(), MirrorWebPack("s", "X", emptyList()).bySeason)
    }

    @Test fun `coversEpisode con seasonStrict exige temporada exacta`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 1, 5), w("s", 2, 5)))[0]
        assertEquals(true, pack.coversEpisode(season = 1, episode = 5, seasonStrict = true))
        assertEquals(false, pack.coversEpisode(season = 3, episode = 5, seasonStrict = true))
    }

    @Test fun `coversEpisode sin seasonStrict matchea el episodio en cualquier temporada`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 2, 5)))[0]
        assertEquals(true, pack.coversEpisode(season = 0, episode = 5, seasonStrict = false))
        assertEquals(false, pack.coversEpisode(season = 0, episode = 9, seasonStrict = false))
    }

    @Test fun `coversEpisode devuelve false para episodio ausente`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 1, 1), w("s", 1, 2)))[0]
        assertEquals(false, pack.coversEpisode(season = 1, episode = 3, seasonStrict = true))
    }
}
