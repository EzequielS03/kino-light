package com.arkiv.player.data

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySerie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Con qué temporada se guarda un capítulo de Caracol. Si el orden se invierte, en un
 * GROUP_OF_BUNDLES todos los capítulos toman la temporada única de la serie y el 1 de la T2 pisa al
 * 1 de la T1.
 *
 * Moved here from `ui/search/TemporadaDelCapituloTest.kt` alongside `DituEntities.capituloDeCaracol`
 * / `DituEntities.temporadaDelCapitulo`, which used to live in `ui/search/SearchPlayback.kt`.
 */
class TemporadaDelCapituloTest {

    private fun serie(temporada: Int) = GatewaySerie(imdbId = "", tmdbId = 0, seasonNumber = temporada)

    @Test fun `la temporada del capitulo le gana a la de la serie`() {
        val capituloDeLaT2 = GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:b", season = 2)
        assertEquals(2, DituEntities.temporadaDelCapitulo(capituloDeLaT2, serie(1)))
    }

    @Test fun `sin temporada propia vale la de la serie`() {
        assertEquals(3, DituEntities.temporadaDelCapitulo(GatewayEpisode(number = 1, title = "Uno", ref = "r"), serie(3)))
    }

    @Test fun `sin ninguna de las dos no se inventa`() {
        assertNull(DituEntities.temporadaDelCapitulo(GatewayEpisode(number = 1, title = "Uno", ref = "r"), null))
    }

    /**
     * La cadena de `SearchPlayback.playDituSeason` sin la base: la lista y el elegido pasan por
     * [DituEntities.capituloDeCaracol] y de ahí a [DituEntities.buildSerie]. En un grupo, la serie
     * trae UNA temporada (la 1) para todas, así que si el elegido la tomara de ahí, o se buscara por
     * número, el 1 de la T2 reproduciría el 1 de la T1.
     */
    @Test fun `en un grupo tocar el capitulo 1 de la T2 reproduce el de la T2`() {
        val delGrupo = serie(1)
        val lista = listOf(
            GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:a1", season = 1),
            GatewayEpisode(number = 2, title = "Dos", ref = "ditu1:VOD:a2", season = 1),
            GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:b1", season = 2),
        )
        val tocado = lista[2]
        val guardada = DituEntities.buildSerie(
            contentId = "G1", seriesRef = "ditu1:GROUP_OF_BUNDLES:G1", title = "Pedro",
            capitulos = lista.map { DituEntities.capituloDeCaracol(it, delGrupo) },
            elegido = DituEntities.capituloDeCaracol(tocado, delGrupo),
            posterUrl = "", ahora = 1L, existente = null, episodiosVistosEnLista = null,
        )

        assertEquals(3, guardada.episodios.size)
        assertEquals("ditu:G1::t2e1", guardada.idDelElegido)
        assertEquals("ditu1:VOD:b1", guardada.episodios.single { it.id == guardada.idDelElegido }.torrentData)
        // El respaldo (`playDituEpisode`, un capítulo suelto) cae en la misma fila.
        val suelto = DituEntities.build(
            contentId = "G1", ref = tocado.ref, title = "Pedro", episode = tocado.number,
            episodeTitle = tocado.title, posterUrl = "", ahora = 1L,
            seriesRef = "ditu1:GROUP_OF_BUNDLES:G1", existente = null,
            season = DituEntities.temporadaDelCapitulo(tocado, delGrupo),
        )
        assertEquals(guardada.idDelElegido, suelto.second.id)
    }
}
