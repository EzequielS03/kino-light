package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parseo puro de la respuesta de `/v1/episodes`: capítulos enriquecidos con TMDB (still, nombre
 * real, sinopsis) más el bloque `series`. Sin red — igual que [NdjsonParserTest] para el stream de
 * búsqueda.
 */
class EpisodesParserTest {

    private fun parsear(json: String) = parseEpisodesResponse(json)

    private val CON_TODO = """
        {"episodes":[
           {"number":1,"title":"Dragon Ball Daima T1_1","ref":"r1",
            "still":"https://image.tmdb.org/t/p/w300/uno.jpg",
            "tmdb_title":"La conspiración","overview":"Goku y sus amigos…"},
           {"number":2,"title":"Dragon Ball Daima T1_2","ref":"r2"}],
         "series":{"imdb_id":"tt29485149","tmdb_id":236994,"season_number":1}}
    """.trimIndent()

    /** Lo que devuelve un gateway sin desplegar, o uno al que TMDB le falló. */
    private val SOLO_LO_VIEJO = """
        {"episodes":[{"number":1,"title":"Dragon Ball Daima T1_1","ref":"r1"}]}
    """.trimIndent()

    @Test fun un_capitulo_enriquecido_trae_still_nombre_y_sinopsis() {
        val (caps, _) = parsear(CON_TODO)
        assertEquals("https://image.tmdb.org/t/p/w300/uno.jpg", caps[0].still)
        assertEquals("La conspiración", caps[0].tmdbTitle)
        assertEquals("Goku y sus amigos…", caps[0].overview)
        // El título del portal se conserva aparte: es el que se guarda como displayName.
        assertEquals("Dragon Ball Daima T1_1", caps[0].title)
    }

    @Test fun un_capitulo_sin_enriquecer_deja_los_campos_nuevos_en_null() {
        val (caps, _) = parsear(CON_TODO)
        assertNull(caps[1].still)
        assertNull(caps[1].tmdbTitle)
        assertNull(caps[1].overview)
    }

    @Test fun el_bloque_series_se_lee_entero() {
        val (_, serie) = parsear(CON_TODO)
        assertEquals("tt29485149", serie?.imdbId)
        assertEquals(236994, serie?.tmdbId)
        assertEquals(1, serie?.seasonNumber)
    }

    @Test fun una_respuesta_vieja_sin_los_campos_nuevos_sigue_funcionando() {
        // Compatibilidad hacia atrás: no puede tirar excepción ni perder el capítulo.
        val (caps, serie) = parsear(SOLO_LO_VIEJO)
        assertEquals(1, caps.size)
        assertEquals("r1", caps[0].ref)
        assertNull(caps[0].still)
        assertNull(serie)
    }

    @Test fun un_still_vacio_se_lee_como_null_y_no_como_cadena_vacia() {
        // Si quedara "" la UI intentaría cargar una imagen inexistente en vez de caer al respaldo.
        val (caps, _) = parsear("""{"episodes":[{"number":1,"title":"t","ref":"r","still":"","tmdb_title":""}]}""")
        assertNull(caps[0].still)
        assertNull(caps[0].tmdbTitle)
    }
}
