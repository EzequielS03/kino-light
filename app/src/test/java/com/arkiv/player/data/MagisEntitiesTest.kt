package com.arkiv.player.data

import com.arkiv.player.data.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cómo se guarda lo que llega de Magis.
 *
 * El caso que originó estos tests: se vio el capítulo 1 de "Dragon Ball Daima" y la tarjeta salió
 * en la fila **Películas** del home. Magis era el único camino de "guardar un capítulo" que no
 * forzaba `categoryOverride = "series"` —y además creaba un ítem por capítulo (`magis:<id>:e1`)—,
 * así que la detección automática (`episodeCount <= 1` en `LibraryRow.isMovie`) lo leía como
 * película. Los tests de abajo fijan las dos mitades del contrato: **un ítem por temporada** y
 * **marcado como serie desde el primer capítulo**.
 */
class MagisEntitiesTest {

    private fun capitulo(
        contentId: String = "ABC",
        ref: String = "ref-cap",
        title: String = "Dragon Ball Daima T1",
        episode: Int = 1,
        episodeTitle: String = "",
        seriesRef: String = "ref-temporada",
        existente: ItemEntity? = null,
    ) = MagisEntities.build(
        contentId = contentId, ref = ref, title = title, episode = episode,
        episodeTitle = episodeTitle, posterUrl = "poster.jpg", ahora = 1_000L,
        seriesRef = seriesRef, existente = existente,
    )

    @Test fun un_capitulo_marca_el_item_como_serie() {
        // El corazón del bug: sin esto, un ítem de un solo episodio cae en Películas.
        val (item, _) = capitulo()
        assertEquals("series", item.categoryOverride)
    }

    @Test fun el_item_de_un_capitulo_es_la_temporada_no_el_capitulo() {
        val (item, ep) = capitulo(episode = 1)
        assertEquals("magis:ABC", item.identifier)
        assertEquals("magis:ABC", ep.itemId)
    }

    @Test fun dos_capitulos_de_la_misma_temporada_caen_en_UN_solo_item() {
        // Lo que hacía que cada capítulo fuera su propia tarjeta en el home.
        val (item1, ep1) = capitulo(episode = 1)
        val (item2, ep2) = capitulo(episode = 2)
        assertEquals(item1.identifier, item2.identifier)
        assertNotEquals(ep1.id, ep2.id)
    }

    @Test fun el_capitulo_queda_numerado_para_que_se_sepa_cual_falta() {
        // `BuscadorDeCapitulos` compara estos números contra los del portal; sin ellos no puede
        // saber qué capítulo bajar.
        val (_, ep) = capitulo(episode = 7)
        assertEquals(7, ep.episode)
        assertEquals(7, ep.orderIndex)
    }

    @Test fun el_ref_del_capitulo_viaja_en_el_capitulo() {
        // Es lo que el player resuelve al reproducir (`magisRefForEpisode`).
        val (_, ep) = capitulo(ref = "ref-del-cap-3", episode = 3)
        assertEquals("ref-del-cap-3", ep.torrentData)
    }

    @Test fun el_ref_de_la_temporada_viaja_en_el_item() {
        // El ítem representa la TEMPORADA: su ref es el que sirve para pedir la lista de capítulos,
        // no el del capítulo que se acaba de ver.
        val (item, _) = capitulo(ref = "ref-del-cap", seriesRef = "ref-de-la-temporada")
        assertEquals("ref-de-la-temporada", item.torrentData)
    }

    @Test fun sin_ref_de_temporada_no_se_pisa_el_que_ya_estaba() {
        // Los refs caducan y se re-emiten; uno en blanco nunca debe borrar uno bueno.
        val previo = capitulo(seriesRef = "ref-buena").first
        val (item, _) = capitulo(seriesRef = "", existente = previo)
        assertEquals("ref-buena", item.torrentData)
    }

    @Test fun un_capitulo_nuevo_no_reinicia_la_serie() {
        // upsertItem es REPLACE (borra e inserta), así que lo que no se copie acá se pierde: la
        // fecha de alta reordenaría el home y `episodiosVistosEnLista` volvería a prender el badge
        // de novedades sobre capítulos ya mirados.
        val previo = ItemEntity(
            identifier = "magis:ABC", title = "Dragon Ball Daima T1", description = null,
            thumbnailUrl = "poster.jpg", addedAt = 500L, categoryOverride = "series",
            source = "magis", torrentData = "ref-temporada", episodiosVistosEnLista = 4, tmdbId = 123,
        )
        val (item, _) = capitulo(episode = 5, existente = previo)
        assertEquals(500L, item.addedAt)
        assertEquals(4, item.episodiosVistosEnLista)
        assertEquals(123, item.tmdbId)
    }

    @Test fun una_pelicula_sigue_siendo_pelicula() {
        // episode = 0 es el camino de siempre y no se toca: sin override, un episodio, id sin :e.
        val (item, ep) = capitulo(title = "Duro de matar", episode = 0, seriesRef = "")
        assertEquals("magis:ABC", item.identifier)
        assertNull(item.categoryOverride)
        assertEquals("magis:ABC::0", ep.id)
    }

    @Test fun el_id_viejo_de_un_capitulo_se_puede_reconocer_para_barrerlo() {
        // Las filas guardadas antes de este cambio quedaron como `magis:<contentId>:e<n>`, o sea una
        // tarjeta-película por capítulo. Se borran al volver a guardar ese mismo capítulo.
        assertEquals("magis:ABC:e1", MagisEntities.idLegacyDeCapitulo("ABC", 1))
    }
}
