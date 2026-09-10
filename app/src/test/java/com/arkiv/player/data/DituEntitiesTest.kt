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

    // ── La serie entera: lo que se guarda al tocar un capítulo (`ArkivRepository.addDituSeason`) ──

    private val grupo = "ditu1:GROUP_OF_BUNDLES:G1"

    /** Un GROUP_OF_BUNDLES de dos temporadas, cada una con su capítulo 1 (como `DituEpisodiosTest`). */
    private val listaDelGrupo = listOf(
        CapituloDeCaracol(1, "Uno", "ditu1:VOD:a1", season = 1),
        CapituloDeCaracol(2, "Dos", "ditu1:VOD:a2", season = 1),
        CapituloDeCaracol(1, "Uno de la T2", "ditu1:VOD:b1", season = 2),
        CapituloDeCaracol(2, "Dos de la T2", "ditu1:VOD:b2", season = 2),
    )

    private fun serie(
        capitulos: List<CapituloDeCaracol> = listaDelGrupo,
        elegido: CapituloDeCaracol = capitulos.first(),
        existente: ItemEntity? = null,
        episodiosVistosEnLista: Int? = null,
        posterUrl: String = "poster.jpg",
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ) = DituEntities.buildSerie(
        contentId = "G1", seriesRef = grupo, title = "Pedro el escamoso", capitulos = capitulos,
        elegido = elegido, posterUrl = posterUrl, ahora = 1_000L, existente = existente,
        episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, tituloCanonico = tituloCanonico,
    )

    /** El capítulo guardado suelto, como lo guarda `ArkivRepository.addDituSource`. */
    private fun suelto(cap: CapituloDeCaracol) = DituEntities.build(
        contentId = "G1", ref = cap.ref, title = "Pedro el escamoso", episode = cap.number,
        episodeTitle = cap.title, posterUrl = "poster.jpg", ahora = 1_000L, seriesRef = grupo,
        existente = null, season = cap.season,
    )

    /** El bug que reportó la persona: la biblioteca mostraba la serie con un solo capítulo. */
    @Test fun `la serie entra entera, cada capitulo con su ref`() {
        val guardada = serie(elegido = listaDelGrupo[2])

        assertEquals(listaDelGrupo.map { it.ref }, guardada.episodios.map { it.torrentData })
        assertEquals(listaDelGrupo.size, guardada.episodios.map { it.id }.toSet().size)
        assertTrue(guardada.episodios.all { it.itemId == "ditu:G1" })
        assertTrue(guardada.episodios.all { PlayerSource.kindFor(it.id) == SourceKind.DITU })
        // El ítem es la serie, con su ref: el del grupo, no el de ningún capítulo.
        assertEquals("ditu:G1", guardada.item.identifier)
        assertEquals(grupo, guardada.item.torrentData)
        assertEquals("series", guardada.item.categoryOverride)
        assertEquals("tv", guardada.item.tipo)
        assertEquals("ditu", guardada.item.source)
    }

    @Test fun `en la serie el capitulo 1 de la T1 y el de la T2 no se pisan`() {
        val (t1, t2) = serie().episodios.filter { it.episode == 1 }
        assertNotEquals(t1.id, t2.id)
        assertEquals("ditu:G1::e1", t1.id)
        assertEquals("ditu1:VOD:b1", t2.torrentData)
        assertEquals(2, t2.season)
        assertTrue(t1.orderIndex < t2.orderIndex)
    }

    /**
     * Lo que evita duplicados: la persona ya tiene la serie guardada con UN capítulo (por
     * `addDituSource`), y al tocar otro se guarda la serie entera. Si el mismo capítulo tuviera otro
     * id guardado con su serie, quedaría dos veces en la biblioteca.
     */
    @Test fun `el mismo capitulo tiene el mismo id suelto que guardado con su serie`() {
        val conSuSerie = serie()
        for (cap in listaDelGrupo) {
            val (itemSuelto, epSuelto) = suelto(cap)
            val epConSuSerie = conSuSerie.episodios.single { it.torrentData == cap.ref }
            assertEquals(epSuelto.id, epConSuSerie.id)
            assertEquals(epSuelto, epConSuSerie)
            assertEquals(itemSuelto, conSuSerie.item)
        }
        // Sin temporada, los dos caminos lo mandan a la 1.
        val sinTemporada = CapituloDeCaracol(3, "Tres", "ditu1:VOD:a3", season = null)
        assertEquals(suelto(sinTemporada).second, serie(capitulos = listOf(sinTemporada)).episodios.single())
        assertEquals(1, serie(capitulos = listOf(sinTemporada)).episodios.single().season)
    }

    @Test fun `guardar la serie de nuevo respeta lo guardado`() {
        val primera = serie(tmdbId = 77, tituloCanonico = "Pedro el Escamoso").item.copy(addedAt = 5L)
        // La segunda vez llegan vacíos: TMDB no la encontró, o no llegó póster.
        val segunda = serie(existente = primera, posterUrl = "", tmdbId = null, tituloCanonico = "  ").item

        assertEquals(5L, segunda.addedAt)
        assertEquals(77, segunda.tmdbId)
        assertEquals("Pedro el Escamoso", segunda.tituloCanonico)
        assertEquals("poster.jpg", segunda.thumbnailUrl)
    }

    /** El badge lo re-sella el repositorio contra la unión; acá solo se guarda lo que llega. */
    @Test fun `el badge queda en lo que le pasan, no en lo que tenia`() {
        val existente = serie().item.copy(episodiosVistosEnLista = 2)
        assertEquals(6, serie(existente = existente, episodiosVistosEnLista = 6).item.episodiosVistosEnLista)
        assertNull(serie(existente = existente, episodiosVistosEnLista = null).item.episodiosVistosEnLista)
    }

    /** La trampa de los números repetidos: por número solo, tocar el 1 de la T2 abriría el de la T1. */
    @Test fun `tocar el capitulo 1 de la T2 reproduce el de la T2`() {
        assertEquals("ditu:G1::t2e1", serie(elegido = listaDelGrupo[2]).idDelElegido)
        assertEquals("ditu:G1::e1", serie(elegido = listaDelGrupo[0]).idDelElegido)
        assertEquals("ditu:G1::t2e2", serie(elegido = listaDelGrupo[3]).idDelElegido)
    }

    /**
     * Si Caracol repitiera temporada y número, los dos capítulos caen en la misma fila y queda uno:
     * tocar el otro no puede reproducir ese, tiene que caer a guardarlo solo (null).
     */
    @Test fun `un elegido que no quedo guardado con su ref no se reproduce`() {
        val repetidos = listOf(
            CapituloDeCaracol(1, "A", "ditu1:VOD:a", season = 1),
            CapituloDeCaracol(1, "B", "ditu1:VOD:b", season = 1),
        )
        assertEquals(1, serie(capitulos = repetidos).episodios.size)
        assertNull(serie(capitulos = repetidos, elegido = repetidos[0]).idDelElegido)
        assertEquals("ditu:G1::e1", serie(capitulos = repetidos, elegido = repetidos[1]).idDelElegido)
    }

    @Test fun `a la serie solo entran capitulos de Caracol`() {
        val deMagis = CapituloDeCaracol(4, "Cuatro", "magis1:movie:0:C1", season = 1)
        val enCero = CapituloDeCaracol(0, "Cero", "ditu1:VOD:z", season = 1)
        val unaSerie = CapituloDeCaracol(5, "Cinco", "ditu1:BUNDLE:B2", season = 1)

        assertEquals(
            listaDelGrupo,
            DituEntities.capitulosGuardables(grupo, listaDelGrupo + deMagis + enCero + unaSerie),
        )
        // Con una serie que no es de Caracol, no entra ninguno.
        assertTrue(DituEntities.capitulosGuardables("magis1:teleplay:0:C1", listaDelGrupo).isEmpty())
    }
}
