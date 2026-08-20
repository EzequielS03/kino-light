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
        season: Int? = null,
        tmdbId: Int? = null,
    ) = MagisEntities.build(
        contentId = contentId, ref = ref, title = title, episode = episode,
        episodeTitle = episodeTitle, posterUrl = "poster.jpg", ahora = 1_000L,
        seriesRef = seriesRef, existente = existente, season = season, tmdbId = tmdbId,
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

    @Test fun un_capitulo_suelto_queda_tipado_como_tv() {
        // Para que `library_items` (PocketBase) sepa con exactitud que esto es una serie, no por
        // título difuso -- ver el KDoc de ItemEntity.tipo.
        val (item, _) = capitulo(episode = 3)
        assertEquals("tv", item.tipo)
    }

    @Test fun una_pelicula_suelta_queda_tipada_como_movie() {
        val (item, _) = capitulo(episode = 0)
        assertEquals("movie", item.tipo)
    }

    @Test fun el_id_viejo_de_un_capitulo_se_puede_reconocer_para_barrerlo() {
        // Las filas guardadas antes de este cambio quedaron como `magis:<contentId>:e<n>`, o sea una
        // tarjeta-película por capítulo. Se borran al volver a guardar ese mismo capítulo.
        assertEquals("magis:ABC:e1", MagisEntities.idLegacyDeCapitulo("ABC", 1))
    }

    @Test fun un_capitulo_agregado_suelto_puede_llevar_su_temporada_real() {
        // El camino de `BuscadorDeCapitulos.revisarMagis`: agrega un capítulo nuevo en background y,
        // si el gateway resolvió TMDB, ya sabe la temporada real. Sin esto, ese capítulo quedaría
        // con `season = null` mezclado con los que sí la tienen, y `ensureEpisodeStills` aplanaría
        // toda la temporada en vez de cruzar por (temporada, capítulo) exacto (ver el KDoc de
        // `capituloDe`).
        val (_, ep) = capitulo(episode = 8, season = 5)
        assertEquals(5, ep.season)
    }

    @Test fun un_capitulo_suelto_sin_temporada_resuelta_no_inventa_una() {
        // El gateway no siempre pudo cruzar contra TMDB (`GatewaySerie` null): ahí no hay
        // temporada que guardar, y no se inventa una.
        val (_, ep) = capitulo()
        assertNull(ep.season)
    }

    @Test fun el_tmdbId_de_un_capitulo_suelto_nuevo_manda_pero_uno_ausente_no_borra_el_que_ya_estaba() {
        // Mismo contrato que `buildSeason` (ver ese test más abajo), pero para el camino de un
        // capítulo agregado suelto.
        val previo = capitulo().first.copy(tmdbId = 123)
        assertEquals(456, capitulo(existente = previo, tmdbId = 456).first.tmdbId)
        assertEquals(123, capitulo(existente = previo).first.tmdbId)
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
        tmdbId: Int? = null,
        seasonNumber: Int? = null,
        tituloCanonico: String? = null,
    ) = MagisEntities.buildSeason(
        contentId = contentId, title = title, capitulos = capitulos,
        posterUrl = "poster.jpg", ahora = 1_000L, seriesRef = seriesRef, existente = existente,
        episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, seasonNumber = seasonNumber,
        tituloCanonico = tituloCanonico,
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

    @Test fun la_temporada_siempre_queda_tipada_como_tv() {
        assertEquals("tv", temporada().first.tipo)
    }

    @Test fun el_tmdbId_nuevo_manda_pero_uno_ausente_no_borra_el_que_ya_estaba() {
        val previo = temporada().first.copy(tmdbId = 123)
        // Con un tmdbId NUEVO, ese es el que queda -- si `buildSeason` invirtiera la prioridad
        // (`existente?.tmdbId ?: tmdbId`, favoreciendo lo viejo) este assert lo agarraría.
        assertEquals(456, temporada(existente = previo, tmdbId = 456).first.tmdbId)
        // Si TMDB no resolvió esta vez (tmdbId = null), no puede borrar el que ya se había
        // guardado en una llamada anterior: es exactamente el mismo caso que `episodiosVistosEnLista`
        // (ver el test del badge más arriba), pero para el tmdbId.
        assertEquals(123, temporada(existente = previo).first.tmdbId)
    }

    @Test fun el_capitulo_guarda_la_temporada_real() {
        // Sin esto, `ArkivRepository.ensureEpisodeStills` no puede cruzar por (temporada, capítulo)
        // exacto y cae a repartir los capítulos 1..N como si la serie arrancara en la T1 (ver el
        // KDoc de `MagisEntities.capituloDe`): una serie que no arranca ahí (Breaking Bad T5)
        // terminaría con los stills de otra temporada, en silencio. Si alguien vuelve a poner
        // `season = null` acá, este test se cae.
        val (_, eps) = temporada(seasonNumber = 5)
        assertEquals(listOf(5, 5, 5), eps.map { it.season })
    }

    @Test fun sin_temporada_resuelta_el_capitulo_queda_sin_season() {
        // El gateway no siempre pudo cruzar la serie contra TMDB (`GatewaySerie` null): ahí no hay
        // número de temporada que guardar, y no se inventa uno.
        val (_, eps) = temporada(seasonNumber = null)
        assertEquals(listOf(null, null, null), eps.map { it.season })
    }

    @Test fun un_capitulo_enriquecido_deja_su_fila_de_still() {
        val filas = MagisEntities.stillsDeTemporada(
            itemId = "magis:ABC",
            capitulos = listOf(
                CapituloDeTemporada(1, "T1_1", "ref-1", still = "https://img/1.jpg", tmdbTitle = "La conspiración", overview = "Goku…"),
                CapituloDeTemporada(2, "T1_2", "ref-2"),
            ),
            ahora = 1_000L,
        )
        assertEquals(1, filas.size)
        assertEquals("magis:ABC::e1", filas[0].episodeId)
        assertEquals("https://img/1.jpg", filas[0].stillUrl)
        assertEquals("La conspiración", filas[0].title)
        assertEquals("Goku…", filas[0].overview)
    }

    @Test fun un_capitulo_sin_enriquecer_no_deja_fila() {
        // Sin fila, la UI cae al displayName del portal. Con una fila vacía mostraría un hueco.
        assertEquals(0, MagisEntities.stillsDeTemporada("magis:ABC", listOf(CapituloDeTemporada(1, "T1_1", "ref-1")), 1_000L).size)
    }

    @Test fun el_id_de_la_fila_calza_con_el_del_episodio() {
        // La fila se cruza por episodeId: si no calzara, la imagen no aparecería nunca.
        val (_, eps) = temporada()
        val filas = MagisEntities.stillsDeTemporada("magis:ABC", listOf(CapituloDeTemporada(2, "x", "r", still = "u")), 1_000L)
        assertEquals(eps.first { it.episode == 2 }.id, filas[0].episodeId)
    }

    /**
     * El id del episodio con forma de PELÍCULA, como función y no como literal suelto: quien guarda
     * una temporada tiene que poder BARRERLO.
     *
     * Una serie que primero entró como ref suelto —así guardaba "Para ti" antes de saber pedirle los
     * capítulos al gateway— deja esta fila, y su id no es el de ningún capítulo: el upsert de la
     * temporada no la pisa y quedaría de capítulo fantasma, con el título de la serie y el ref de la
     * temporada entera.
     */
    @Test fun el_episodio_de_una_pelicula_no_comparte_id_con_ningun_capitulo() {
        val itemId = MagisEntities.itemIdDe("ABC")
        val dePelicula = MagisEntities.episodioIdDePelicula(itemId)
        assertEquals("magis:ABC::0", dePelicula)
        (1..13).forEach { assertNotEquals(dePelicula, MagisEntities.episodioIdDe(itemId, it)) }
    }

    /** Y es EXACTAMENTE el id con el que [MagisEntities.build] guarda una película: si divergieran, el
     * barrido borraría algo que no es, o dejaría el fantasma intacto. */
    @Test fun el_barrido_apunta_al_mismo_id_con_el_que_se_guardo_la_pelicula() {
        val (_, ep) = capitulo(episode = 0, seriesRef = "")
        assertEquals(MagisEntities.episodioIdDePelicula(MagisEntities.itemIdDe("ABC")), ep.id)
    }


    // --- el nombre con el que TMDB conoce la serie ---

    /**
     * El título del portal NO se pisa: se guarda al lado. "Shin seiki evangerion Temp.1" es como la
     * llama magis y así queda en `title`; "Neon Genesis Evangelion" es lo que muestra la biblioteca.
     * Pisarlo sería perder de qué venía el ítem el día que TMDB se equivoque — y además `title` es
     * donde vive el renombre manual de la persona.
     */
    @Test fun el_nombre_canonico_se_guarda_al_lado_sin_pisar_el_del_portal() {
        val (item, _) = temporada(
            title = "Shin seiki evangerion Temp.1",
            tituloCanonico = "Neon Genesis Evangelion",
        )
        assertEquals("Shin seiki evangerion Temp.1", item.title)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }

    /**
     * Mismo `?:` que [tmdbId]: que TMDB no resuelva HOY no puede borrar el nombre que ya se había
     * resuelto ayer. `buildSeason` corre en cada guardado de la temporada, así que sin esto un solo
     * guardado con el gateway caído dejaría la tarjeta con el nombre del portal otra vez.
     */
    @Test fun un_nombre_canonico_ausente_no_borra_el_que_ya_estaba() {
        val previo = temporada(tituloCanonico = "Neon Genesis Evangelion").first
        val (item, _) = temporada(tituloCanonico = null, existente = previo)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }

    /** Un nombre en blanco es "no vino", no un nombre: dejaría la tarjeta sin texto. */
    @Test fun un_nombre_canonico_en_blanco_tampoco_borra_el_que_ya_estaba() {
        val previo = temporada(tituloCanonico = "Neon Genesis Evangelion").first
        val (item, _) = temporada(tituloCanonico = "   ", existente = previo)
        assertEquals("Neon Genesis Evangelion", item.tituloCanonico)
    }
}

/**
 * Reparación de los ítems de Magis guardados SIN identidad.
 *
 * El `tmdbId` se escribe al guardar la temporada, no al abrirla, así que los que se guardaron
 * cuando el gateway no resolvía la serie se quedaron para siempre sin nombre de capítulo, sin
 * miniatura y sin sinopsis. El `seriesRef` sí quedó guardado: con eso alcanza para volver a
 * preguntar una vez.
 */
class RefParaRepararTest {
    private val ref = "eyJzIjoibWFnaXMi"

    @Test fun `un item de magis sin tmdbId se repara con su ref`() {
        assertEquals(ref, MagisEntities.refParaReparar("magis:ABC", null, ref))
    }

    @Test fun `un tmdbId invalido cuenta como ausente`() {
        // `GatewaySerie.tmdbId` sale de un `optInt`: un campo ausente da 0, no null.
        assertEquals(ref, MagisEntities.refParaReparar("magis:ABC", 0, ref))
    }

    @Test fun `si ya tiene identidad no se vuelve a preguntar`() {
        assertNull(MagisEntities.refParaReparar("magis:ABC", 12609, ref))
    }

    @Test fun `sin ref guardado no hay con que preguntar`() {
        assertNull(MagisEntities.refParaReparar("magis:ABC", null, null))
        assertNull(MagisEntities.refParaReparar("magis:ABC", null, "  "))
    }

    /** Torrent, web y archive tienen su propio camino (`ensureEpisodeStills` por título o tmdbId):
     *  pedirle capítulos al gateway con lo que guardaron en ese campo no tiene sentido. */
    @Test fun `lo que no es de magis no se toca`() {
        assertNull(MagisEntities.refParaReparar("torrent:abc123", null, ref))
        assertNull(MagisEntities.refParaReparar("web:abc123", null, ref))
    }
}
