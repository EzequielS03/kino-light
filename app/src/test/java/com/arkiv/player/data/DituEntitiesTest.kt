package com.arkiv.player.data

import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cómo se guarda en la biblioteca lo que llega de Caracol.
 *
 * El contrato que importa es con el reproductor: lo que se guarda acá lo abre
 * `PlayerViewModel.loadDitu`, que entra solo si el id empieza con `ditu:` (`PlayerSource.kindFor`)
 * y lee el ref del `torrentData` del episodio. Si cualquiera de las dos cosas se rompe, lo guardado
 * no se vuelve a reproducir.
 */
class DituEntitiesTest {

    private fun pelicula(existente: ItemEntity? = null, tmdbId: Int? = null) = DituEntities.build(
        contentId = "P1", ref = "ditu1:VOD:P1", title = "Rigo", episode = 0, episodeTitle = "",
        posterUrl = "poster.jpg", ahora = 1_000L, seriesRef = "", existente = existente, tmdbId = tmdbId,
    )

    private fun capitulo(
        contentId: String = "B9",
        ref: String = "ditu1:VOD:E3",
        number: Int = 3,
        season: Int? = 1,
        seriesRef: String = "ditu1:BUNDLE:B9",
    ) = DituEntities.build(
        contentId = contentId, ref = ref, title = "Pedro el escamoso", episode = number,
        episodeTitle = "El regreso", posterUrl = "poster.jpg", ahora = 1_000L,
        seriesRef = seriesRef, existente = null, season = season,
    )

    @Test fun `los tres ids tienen su forma exacta`() {
        assertEquals("ditu:B9", DituEntities.itemIdDe("B9"))
        assertEquals("ditu:B9::e3", DituEntities.episodioIdDe("ditu:B9", 3))
        assertEquals("ditu:P1::0", DituEntities.episodioIdDePelicula("ditu:P1"))
    }

    /** El eslabón con la Task 9: sin esto, lo guardado nunca entra por `loadDitu`. */
    @Test fun `lo guardado entra al reproductor por Caracol`() {
        val deUnCapitulo = DituEntities.episodioIdDe(DituEntities.itemIdDe("B9"), 3)
        val deUnaPelicula = DituEntities.episodioIdDePelicula(DituEntities.itemIdDe("P1"))

        assertEquals(SourceKind.DITU, PlayerSource.kindFor(deUnCapitulo))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(deUnaPelicula))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(capitulo().second.id))
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(pelicula().second.id))
        // Una temporada que no es la primera también lleva su id con el prefijo de Caracol.
        assertEquals(SourceKind.DITU, PlayerSource.kindFor(capitulo(season = 2).second.id))
    }

    @Test fun `el ref va en torrentData del episodio`() {
        assertEquals("ditu1:VOD:E3", capitulo().second.torrentData)
        assertEquals("ditu1:VOD:P1", pelicula().second.torrentData)
    }

    @Test fun `un capitulo cae en el item de su serie, marcado como serie`() {
        val (item, ep) = capitulo()
        assertEquals("ditu:B9", item.identifier)
        assertEquals("ditu:B9", ep.itemId)
        assertEquals("ditu:B9::e3", ep.id)
        assertEquals("series", item.categoryOverride)
        assertEquals("tv", item.tipo)
        assertEquals("ditu", item.source)
        assertEquals(1, ep.season)
        assertEquals(3, ep.episode)
    }

    @Test fun `una pelicula es su propio item`() {
        val (item, ep) = pelicula()
        assertEquals("ditu:P1", item.identifier)
        assertEquals("ditu:P1::0", ep.id)
        assertEquals("movie", item.tipo)
        assertEquals("ditu", item.source)
        assertNull(item.categoryOverride)
    }

    /**
     * `ensureEpisodeStills` solo cruza por (temporada, capítulo) si TODOS los episodios del ítem
     * tienen temporada: uno en null lo hace aplanar desde la T1 (ver el KDoc de
     * `MagisEntities.capituloDe`).
     */
    @Test fun `un capitulo sin temporada queda en la 1, nunca en null`() {
        assertEquals(1, capitulo(season = null).second.season)
        assertEquals(1, capitulo(season = 0).second.season)
    }

    /**
     * En un GROUP_OF_BUNDLES cada temporada trae su propio capítulo 1 (así lo arma
     * `DituEpisodiosTest`): si la temporada no entrara al id, el capítulo 1 de la T2 pisaría la
     * fila del de la T1 y quedaría reproduciendo otro capítulo.
     */
    @Test fun `en un grupo el capitulo 1 de cada temporada no se pisa`() {
        val grupo = "ditu1:GROUP_OF_BUNDLES:G1"
        val (_, t1) = capitulo(contentId = "G1", ref = "ditu1:VOD:a", number = 1, season = 1, seriesRef = grupo)
        val (_, t2) = capitulo(contentId = "G1", ref = "ditu1:VOD:b", number = 1, season = 2, seriesRef = grupo)

        assertNotEquals(t1.id, t2.id)
        assertEquals("ditu:G1::e1", t1.id)
        assertEquals("ditu1:VOD:b", t2.torrentData)
        // La biblioteca ordena por orderIndex (`ItemDao.getEpisodesOf`): la T2 va después de la T1.
        assertTrue(t1.orderIndex < t2.orderIndex)
    }

    @Test fun `solo se guarda lo que es de Caracol`() {
        assertEquals("P1", DituEntities.contentIdDelItem("ditu1:VOD:P1", seriesRef = "", episode = 0))
        assertEquals("B9", DituEntities.contentIdDelItem("ditu1:VOD:E3", seriesRef = "ditu1:BUNDLE:B9", episode = 3))
        // Un ref de Magis nunca sale con un id `ditu:`: el reproductor lo mandaría a Caracol.
        assertNull(DituEntities.contentIdDelItem("magis1:movie:0:C1", seriesRef = "", episode = 0))
        assertNull(DituEntities.contentIdDelItem("ditu1:VOD:E3", seriesRef = "magis1:teleplay:0:C1", episode = 3))
        // Una serie no se guarda como película: primero se eligen sus capítulos.
        assertNull(DituEntities.contentIdDelItem("ditu1:BUNDLE:B9", seriesRef = "", episode = 0))
        // Un capítulo sin su serie no tiene ítem al que ir.
        assertNull(DituEntities.contentIdDelItem("ditu1:VOD:E3", seriesRef = "", episode = 3))
    }

    @Test fun `guardar de nuevo no pierde la fecha de alta ni el tmdbId`() {
        val (primera, _) = pelicula(tmdbId = 77)
        val (segunda, _) = pelicula(existente = primera.copy(addedAt = 5L))
        assertEquals(5L, segunda.addedAt)
        assertEquals(77, segunda.tmdbId)
    }
}
