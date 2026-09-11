package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySerie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué se guarda al agregar una recomendación de "Para ti" a la biblioteca.
 *
 * El caso que originó estos tests, medido en producción: se agregó "My Hero Academia" desde "Para
 * ti" y entró a la biblioteca con **un** capítulo (`magis:cammiy790r3ky1o::0`, `tipo = "movie"`).
 * El `ref` que manda el gateway es el de la TEMPORADA (lleva `episode: 0` adentro) y, preguntado
 * por `/v1/episodes`, devuelve los 13 capítulos con su bloque `series` — pero nadie se los pedía:
 * la fila guardaba con `addMagisSource` sin `episode`, que es la rama de PELÍCULA de
 * `MagisEntities.build` (un ítem, un episodio, sin `categoryOverride = "series"`).
 *
 * Y no se arreglaba solo: `SeriesPorRevisar.elegir` filtra `episodios > 1`, así que el buscador de
 * capítulos nuevos de fondo tampoco lo iba a mirar nunca.
 */
class GuardadoDeRecomendacionTest {

    private fun rec(tipo: String) = RecomendacionEntity(
        id = "cammiy790r3ky1o",
        tmdbId = 65930,
        tipo = tipo,
        titulo = "My Hero Academia",
        posterUrl = "https://image.tmdb.org/t/p/w500/mho.jpg",
        porque = "porque terminaste Dragon Ball",
        ref = "ref-de-la-temporada",
        orden = 0,
        generadoAt = 1_000L,
    )

    /** Los 13 que devolvió de verdad el portal para esa recomendación. */
    private val TRECE = (1..13).map {
        GatewayEpisode(
            number = it,
            title = "My Hero Academia Temporada 1_My Hero Academia T1-%02d".format(it),
            ref = "ref-cap-$it",
        )
    }

    private val SERIE = GatewaySerie(imdbId = "tt5626028", tmdbId = 65930, seasonNumber = 1)

    @Test fun una_serie_se_guarda_con_todos_sus_capitulos() {
        val temporada = GuardadoDeRecomendacion.temporadaDe(TRECE, SERIE)
        assertNotNull(temporada)
        assertEquals(13, temporada!!.capitulos.size)
        assertEquals((1..13).toList(), temporada.capitulos.map { it.number })
        // El ref de CADA capítulo, no el de la temporada: es lo que el player resuelve al
        // reproducir, y el bug era justo guardar el de la temporada como si fuera un capítulo.
        assertEquals("ref-cap-1", temporada.capitulos.first().ref)
        assertEquals("ref-cap-13", temporada.capitulos.last().ref)
    }

    @Test fun una_serie_le_pregunta_los_capitulos_al_gateway() {
        assertTrue(GuardadoDeRecomendacion.pideCapitulos(rec(tipo = "tv")))
    }

    @Test fun una_pelicula_no_le_pregunta_capitulos_a_nadie() {
        assertFalse(GuardadoDeRecomendacion.pideCapitulos(rec(tipo = "movie")))
    }

    /**
     * The episode listing can legitimately come back empty -- a series whose portal listing failed,
     * or (until this branch's pruning) a legacy recommendation row pointing at a source that could
     * no longer list chapters (`/v1/episodes` used to answer 422 for those). Sin temporada que
     * guardar, quien llama tiene que caer al guardado suelto de siempre — no dejar el ítem a medias.
     */
    @Test fun una_fuente_que_no_lista_capitulos_no_deja_temporada() {
        assertNull(GuardadoDeRecomendacion.temporadaDe(emptyList(), SERIE))
    }

    /**
     * Sin estos tres campos el capítulo queda sin fila en `episode_still`: tarjeta negra y numerada
     * en la biblioteca hasta que alguien abra la serie. Mismo motivo por el que
     * `SearchPlayback.magisEpisodeIdDe` los arrastra.
     */
    @Test fun los_capitulos_llevan_still_nombre_real_y_sinopsis() {
        val enriquecido = GatewayEpisode(
            number = 1,
            title = "My Hero Academia Temporada 1_My Hero Academia T1-01",
            ref = "ref-cap-1",
            still = "https://image.tmdb.org/t/p/w300/uno.jpg",
            tmdbTitle = "Izuku Midoriya: Orígenes",
            overview = "En un mundo donde casi todos tienen superpoderes…",
        )
        val cap = GuardadoDeRecomendacion.temporadaDe(listOf(enriquecido), SERIE)!!.capitulos.single()
        assertEquals("https://image.tmdb.org/t/p/w300/uno.jpg", cap.still)
        assertEquals("Izuku Midoriya: Orígenes", cap.tmdbTitle)
        assertEquals("En un mundo donde casi todos tienen superpoderes…", cap.overview)
        // El título del portal se conserva aparte: es el `displayName` del episodio.
        assertEquals("My Hero Academia Temporada 1_My Hero Academia T1-01", cap.title)
    }

    /**
     * Sin `seasonNumber`, `ArkivRepository.ensureEpisodeStills` cae a su rama de aplanar desde la
     * temporada 1 y le pone a cada capítulo el still de otro (ver el KDoc de `MagisEntities.build`).
     */
    @Test fun la_temporada_viaja_para_que_los_stills_no_se_aplanen() {
        assertEquals(1, GuardadoDeRecomendacion.temporadaDe(TRECE, SERIE)!!.seasonNumber)
    }

    @Test fun el_tmdbId_de_la_serie_viaja_con_la_temporada() {
        assertEquals(65930, GuardadoDeRecomendacion.temporadaDe(TRECE, SERIE)!!.tmdbId)
    }

    /**
     * `GatewaySerie.tmdbId` sale de un `optInt`: un campo que no vino da 0, no null. Ese 0 le
     * ganaría al `?:` con el que `buildSeason` preserva el tmdbId ya guardado.
     */
    @Test fun un_tmdbId_en_cero_no_es_una_identificacion() {
        val temporada = GuardadoDeRecomendacion.temporadaDe(TRECE, GatewaySerie("", 0, 1))
        assertNull(temporada!!.tmdbId)
    }

    /** El gateway no siempre cruza la serie contra TMDB; los capítulos se guardan igual. */
    @Test fun sin_bloque_series_igual_se_guardan_los_capitulos() {
        val temporada = GuardadoDeRecomendacion.temporadaDe(TRECE, null)
        assertEquals(13, temporada!!.capitulos.size)
        assertNull(temporada.tmdbId)
        assertNull(temporada.seasonNumber)
    }

    private fun recCon(id: String, ref: String) = RecomendacionEntity(
        id = id, tmdbId = 0, tipo = "movie", titulo = "x", posterUrl = "", porque = "", ref = ref,
        orden = 0, generadoAt = 0,
    )

    @Test fun `un ref de Caracol va a Caracol con su contentId`() {
        assertEquals(
            DestinoDeRecomendacion.Caracol("99"),
            GuardadoDeRecomendacion.destino(recCon("ditu:99", "ditu1:BUNDLE:99")),
        )
    }

    @Test fun `un ref de Magis va a Magis con su contentId y no con el id de la fila`() {
        assertEquals(
            DestinoDeRecomendacion.Magis("C42"),
            GuardadoDeRecomendacion.destino(recCon("otro-id", "magis1:teleplay:0:C42")),
        )
    }

    /** Filas viejas del gateway: su id es el del registro de PocketBase y su ref puede no entenderse. */
    @Test fun `una fila con un ref que no se entiende cae a Magis con su id`() {
        assertEquals(
            DestinoDeRecomendacion.Magis("pbrecord123"),
            GuardadoDeRecomendacion.destino(recCon("pbrecord123", "ilegible")),
        )
    }

    @Test fun `el ref sin fuente conocida no tiene destino`() {
        assertNull(GuardadoDeRecomendacion.destinoDeRef("otra:cosa"))
    }

    @Test fun `el item guardado es el de la fuente`() {
        assertEquals(
            com.arkiv.player.data.MagisEntities.itemIdDe("C42"),
            GuardadoDeRecomendacion.itemIdDe(DestinoDeRecomendacion.Magis("C42")),
        )
        assertEquals(
            com.arkiv.player.data.DituEntities.itemIdDe("99"),
            GuardadoDeRecomendacion.itemIdDe(DestinoDeRecomendacion.Caracol("99")),
        )
    }
}
