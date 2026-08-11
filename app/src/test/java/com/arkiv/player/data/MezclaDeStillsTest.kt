package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeStillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué pasa cuando dos fuentes escriben la MISMA fila de `episode_still`.
 *
 * El caso que originó estos tests: se guardó una temporada de Magis (con still, nombre y sinopsis
 * que el gateway cruzó contra TMDB), se abrió el detalle sin red, `ensureEpisodeStills` no pudo
 * consultar nada y —como `upsertAll` es REPLACE— escribió la fila entera en null encima. La serie se
 * quedaba sin imágenes ni nombres, y como la fila seguía existiendo, nadie volvía a preguntar nunca.
 */
class MezclaDeStillsTest {

    private fun fila(
        still: String? = null,
        title: String? = null,
        overview: String? = null,
        fetchedAt: Long = 0L,
    ) = EpisodeStillEntity("magis:ABC::e1", still, fetchedAt, title, overview)

    @Test fun sin_fila_previa_la_nueva_entra_tal_cual() {
        val nueva = fila(still = "u", title = "t", overview = "o", fetchedAt = 9L)
        assertEquals(nueva, MezclaDeStills.mezclar(previa = null, nueva = nueva))
    }

    @Test fun lo_nuevo_manda_cuando_trae_algo() {
        val r = MezclaDeStills.mezclar(
            previa = fila(still = "viejo.jpg", title = "viejo", overview = "vieja"),
            nueva = fila(still = "nuevo.jpg", title = "nuevo", overview = "nueva"),
        )
        assertEquals("nuevo.jpg", r.stillUrl)
        assertEquals("nuevo", r.title)
        assertEquals("nueva", r.overview)
    }

    @Test fun una_consulta_vacia_no_borra_lo_que_ya_estaba() {
        // El corazón del bug: TMDB se cayó, el mapa quedó vacío y la fila igual se escribía con
        // todo en null encima de lo que Magis había guardado bien.
        val r = MezclaDeStills.mezclar(
            previa = fila(still = "magis.jpg", title = "La conspiración", overview = "Goku…"),
            nueva = fila(),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
        assertEquals("Goku…", r.overview)
    }

    @Test fun la_mezcla_es_campo_por_campo_no_fila_por_fila() {
        // Las dos fuentes se complementan: el gateway solo le pide a TMDB es-MX, así que puede
        // traer still y nombre sin sinopsis; la consulta de acá puede traer la del respaldo en
        // inglés. Con una regla por FILA, cada escritura borraría la mitad buena de la otra.
        val r = MezclaDeStills.mezclar(
            previa = fila(still = "magis.jpg", title = "La conspiración"),
            nueva = fila(overview = "Goku entrena…"),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
        assertEquals("Goku entrena…", r.overview)
    }

    @Test fun un_valor_en_blanco_cuenta_como_ausente() {
        // TMDB devuelve "" (no null) para lo que no tiene; un "" pisando un título bueno se vería
        // en la UI igual que si se hubiera borrado.
        val r = MezclaDeStills.mezclar(
            previa = fila(still = "magis.jpg", title = "La conspiración"),
            nueva = fila(still = "", title = "   "),
        )
        assertEquals("magis.jpg", r.stillUrl)
        assertEquals("La conspiración", r.title)
    }

    @Test fun sin_nada_de_ningun_lado_la_fila_igual_queda_vacia() {
        // Sigue siendo la marca de "ya preguntado y TMDB no tenía": lo que no puede pasar es que
        // *invente* datos, ni que repregunte para siempre.
        val r = MezclaDeStills.mezclar(previa = fila(), nueva = fila())
        assertNull(r.stillUrl)
        assertNull(r.title)
        assertNull(r.overview)
    }

    @Test fun la_fecha_es_la_de_la_ultima_consulta() {
        // `fetchedAt` dice cuándo se preguntó, no de cuándo es el dato: se acaba de preguntar.
        val r = MezclaDeStills.mezclar(previa = fila(title = "viejo", fetchedAt = 100L), nueva = fila(fetchedAt = 500L))
        assertEquals(500L, r.fetchedAt)
        assertEquals("viejo", r.title)
    }
}
