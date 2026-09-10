package com.arkiv.player.ui.search

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySerie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Con qué temporada se guarda un capítulo de Caracol. Si el orden se invierte, en un
 * GROUP_OF_BUNDLES todos los capítulos toman la temporada única de la serie y el 1 de la T2 pisa al
 * 1 de la T1.
 */
class TemporadaDelCapituloTest {

    private fun serie(temporada: Int) = GatewaySerie(imdbId = "", tmdbId = 0, seasonNumber = temporada)

    @Test fun `la temporada del capitulo le gana a la de la serie`() {
        val capituloDeLaT2 = GatewayEpisode(number = 1, title = "Uno", ref = "ditu1:VOD:b", season = 2)
        assertEquals(2, temporadaDelCapitulo(capituloDeLaT2, serie(1)))
    }

    @Test fun `sin temporada propia vale la de la serie`() {
        assertEquals(3, temporadaDelCapitulo(GatewayEpisode(number = 1, title = "Uno", ref = "r"), serie(3)))
    }

    @Test fun `sin ninguna de las dos no se inventa`() {
        assertNull(temporadaDelCapitulo(GatewayEpisode(number = 1, title = "Uno", ref = "r"), null))
    }
}
