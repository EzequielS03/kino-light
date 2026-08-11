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

    private fun temporada(
        contentId: String = "ABC",
        title: String = "Dragon Ball Daima T1",
        capitulos: List<CapituloDeTemporada> = listOf(
            CapituloDeTemporada(1, "El misterio", "ref-1"),
            CapituloDeTemporada(2, "El deseo", "ref-2"),
            CapituloDeTemporada(3, "La aventura", "ref-3"),
        ),
        seriesRef: String = "ref-temporada",
        existente: ItemEntity? = null,
        // Null por default: en la app real lo calcula `ArkivRepository.addMagisSeason` contra la
        // base (ver ContadorDeNuevos.reSellar) y se lo pasa ya resuelto. Acá, sin DB, cada test que
        // le importe el badge lo fija a mano.
        episodiosVistosEnLista: Int? = null,
    ) = MagisEntities.buildSeason(
        contentId = contentId, title = title, capitulos = capitulos,
        posterUrl = "poster.jpg", ahora = 1_000L, seriesRef = seriesRef, existente = existente,
        episodiosVistosEnLista = episodiosVistosEnLista,
    )

    @Test fun la_temporada_entra_como_UN_item_con_todos_sus_capitulos() {
        val (item, eps) = temporada()
        assertEquals("magis:ABC", item.identifier)
        assertEquals("series", item.categoryOverride)
        assertEquals(listOf("magis:ABC::e1", "magis:ABC::e2", "magis:ABC::e3"), eps.map { it.id })
        assertEquals(listOf(1, 2, 3), eps.map { it.episode })
        assertEquals(listOf(1, 2, 3), eps.map { it.orderIndex })
        assertEquals(listOf("ref-1", "ref-2", "ref-3"), eps.map { it.torrentData })
    }

    @Test fun un_capitulo_de_la_temporada_sale_igual_que_guardado_de_a_uno() {
        // Si divergieran, guardar la temporada duplicaría los capítulos que ya estaban sueltos:
        // el id es la clave primaria y `upsert` es REPLACE, así que TIENE que coincidir.
        val (_, suelto) = capitulo(episode = 2, ref = "ref-2", episodeTitle = "El deseo")
        val dentro = temporada().second.first { it.episode == 2 }
        assertEquals(suelto.id, dentro.id)
        assertEquals(suelto.displayName, dentro.displayName)
        assertEquals(suelto.itemId, dentro.itemId)
    }

    @Test fun guardar_la_temporada_otra_vez_no_duplica_ni_reordena_el_home() {
        // Esto corre en CADA reproducción: si moviera `addedAt`, la serie saltaría al principio del
        // home cada vez que le das play a un capítulo.
        val previo = temporada().first.copy(addedAt = 500L, episodiosVistosEnLista = 4, tmdbId = 123)
        val (item, eps) = temporada(existente = previo, episodiosVistosEnLista = 4)
        assertEquals(500L, item.addedAt)
        assertEquals(4, item.episodiosVistosEnLista)
        assertEquals(123, item.tmdbId)
        assertEquals(3, eps.size)
        assertEquals(3, eps.map { it.id }.distinct().size)
    }

    @Test fun el_badge_lo_deja_buildSeason_en_lo_que_le_pasan_no_en_lo_que_tenia_el_existente() {
        // Antes `buildSeason` copiaba `existente?.episodiosVistosEnLista` sin tocar, y el
        // repositorio lo corregía después con un segundo UPDATE (`marcarEpisodiosVistos`) -- la
        // segunda escritura que hacía recursar el trigger de sync si caía en el mismo segundo que
        // el `upsertItem`. Ahora quien llama (el repositorio, que sí tiene la base para calcular la
        // unión) ya le pasa el total re-sellado, y `buildSeason` solo lo guarda: si acá adentro
        // volviera a leer `existente.episodiosVistosEnLista` en vez del parámetro, este test lo
        // agarraría (dejaría 4, no 9).
        val previo = temporada().first.copy(episodiosVistosEnLista = 4)
        val (item, _) = temporada(existente = previo, episodiosVistosEnLista = 9)
        assertEquals(9, item.episodiosVistosEnLista)
    }

    @Test fun el_badge_sigue_null_si_nunca_se_habia_sellado() {
        // `ContadorDeNuevos.reSellar` devuelve null cuando `vistos` es null (nunca se abrió el
        // detalle): sellarlo acá prendería el badge de novedades sobre capítulos que en realidad
        // nunca se mostraron como "nuevos". `buildSeason` no debe inventar un valor por su cuenta.
        val previo = temporada().first.copy(episodiosVistosEnLista = null)
        val (item, _) = temporada(existente = previo, episodiosVistosEnLista = null)
        assertNull(item.episodiosVistosEnLista)
    }

    @Test fun el_ref_de_la_temporada_manda_y_en_blanco_no_pisa_el_guardado() {
        // Los refs caducan y se re-emiten; uno en blanco nunca debe borrar uno bueno.
        assertEquals("ref-temporada", temporada().first.torrentData)
        val previo = temporada(seriesRef = "ref-buena").first
        assertEquals("ref-buena", temporada(seriesRef = "", existente = previo).first.torrentData)
    }

    @Test fun una_temporada_sin_capitulos_no_inventa_episodios() {
        val (item, eps) = temporada(capitulos = emptyList())
        assertEquals("magis:ABC", item.identifier)
        assertEquals(0, eps.size)
    }
}
