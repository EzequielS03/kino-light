package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cómo se nombra un capítulo en el detalle.
 *
 * El caso que originó esto: los capítulos de Magis guardan `season = null` y `episode = N`, y la
 * versión vieja (privada en `TvDetailScreen`) exigía season Y episode a la vez, así que caía a la
 * rama de `orderIndex` y mostraba el capítulo 5 como "E6".
 */
class EtiquetaDeCapituloTest {

    private fun ep(
        orderIndex: Int = 0,
        season: Int? = null,
        episode: Int? = null,
        id: String = "item::x",
        displayName: String = "",
    ) = Episode(
        id = id, itemId = "item", section = "", displayName = displayName, orderIndex = orderIndex,
        durationSeconds = 0.0, thumbPath = null, original = null, derivative = null,
        season = season, episode = episode,
    )

    @Test fun magis_numera_por_episodio_aunque_no_tenga_temporada() {
        assertEquals("E5", EtiquetaDeCapitulo.numero(ep(orderIndex = 5, episode = 5)))
    }

    @Test fun con_temporada_y_episodio_se_muestran_los_dos() {
        assertEquals("T2 · E5", EtiquetaDeCapitulo.numero(ep(season = 2, episode = 5)))
    }

    @Test fun un_pack_de_torrent_numera_desde_el_orderIndex() {
        // Los packs codifican temporada*1000 + episodio.
        assertEquals("T1 · E3", EtiquetaDeCapitulo.numero(ep(orderIndex = 1003)))
    }

    @Test fun sin_nada_el_orden_es_1_based() {
        // archive.org: correlativo 0..N-1.
        assertEquals("E1", EtiquetaDeCapitulo.numero(ep(orderIndex = 0)))
    }

    @Test fun el_nombre_real_va_AL_LADO_del_numero_no_en_su_lugar() {
        // El bug: la fila del detalle del celu mostraba `tmdbTitle ?: displayName`, así que en un
        // capítulo de Magis el número desaparecía — se veía solo "Panzy" donde antes decía
        // "E5  Daima T1_5". El número identifica el capítulo que se va a reproducir y es el dato
        // cierto aunque el cruce con TMDB quede corrido: no se puede sustituir por el nombre.
        assertEquals(
            "E5  ·  Panzy",
            EtiquetaDeCapitulo.conNombre(ep(orderIndex = 5, episode = 5, displayName = "E5  Daima T1_5"), "Panzy"),
        )
        assertEquals(
            "T5 · E8  ·  Ozymandias",
            EtiquetaDeCapitulo.conNombre(ep(season = 5, episode = 8, displayName = "s05e08.mkv"), "Ozymandias"),
        )
    }

    @Test fun sin_nombre_resuelto_queda_el_del_archivo() {
        // TMDB no siempre resuelve. Ahí manda el displayName, que en las fuentes que numeran ya
        // trae el número adentro — meterle un "E5 · " delante lo duplicaría.
        val e = ep(orderIndex = 5, episode = 5, displayName = "E5  Daima T1_5")
        assertEquals("E5  Daima T1_5", EtiquetaDeCapitulo.conNombre(e, null))
        assertEquals("E5  Daima T1_5", EtiquetaDeCapitulo.conNombre(e, "   "))
    }

    private fun detalle(progreso: Map<String, PlaybackEntity> = emptyMap()) = ItemDetail(
        identifier = "magis:ABC", title = "Daima", description = null, thumbnailUrl = "",
        episodes = (1..20).map { ep(orderIndex = it, episode = it, id = "magis:ABC::e$it") },
        progress = progreso,
    )

    @Test fun sin_haber_empezado_solo_dice_cuantos_hay() {
        assertEquals("20 episodios", EtiquetaDeCapitulo.avance(detalle(), "episodios"))
        assertEquals("Reproducir", EtiquetaDeCapitulo.botonReproducir(detalle()))
    }

    @Test fun empezada_dice_por_donde_vas() {
        val progreso = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 120_000L, 600_000L, false, 50L),
        )
        assertEquals("Vas en E5  ·  20 episodios", EtiquetaDeCapitulo.avance(detalle(progreso), "episodios"))
        assertEquals("Reproducir E5", EtiquetaDeCapitulo.botonReproducir(detalle(progreso)))
    }

    @Test fun terminado_un_capitulo_apunta_al_siguiente() {
        val progreso = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 600_000L, 600_000L, true, 50L),
        )
        assertEquals("Vas en E6  ·  20 episodios", EtiquetaDeCapitulo.avance(detalle(progreso), "episodios"))
        assertEquals("Reproducir E6", EtiquetaDeCapitulo.botonReproducir(detalle(progreso)))
    }

    @Test fun una_pelicula_no_dice_por_donde_vas() {
        val peli = ItemDetail(
            identifier = "magis:X", title = "Duro de matar", description = null, thumbnailUrl = "",
            episodes = listOf(ep(id = "magis:X::0")),
            progress = mapOf("magis:X::0" to PlaybackEntity("magis:X::0", 120_000L, 600_000L, false, 50L)),
        )
        assertEquals("Reproducir", EtiquetaDeCapitulo.botonReproducir(peli))
    }
}
