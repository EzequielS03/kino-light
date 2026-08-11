package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La diferencia entre "no se pudo consultar" y "se consultó y no había".
 *
 * El caso que originó estos tests: `seasonEpisodes` devolvía `emptyList()` para las dos cosas, así
 * que `ArkivRepository.ensureEpisodeStills` no podía distinguirlas y escribía igual la fila de cada
 * capítulo. Para una serie sin filas previas, una sola apertura del detalle sin red la sellaba
 * entera en null: su corte temprano (`previas.containsAll(...)`) daba true desde ahí en adelante y
 * esa serie se quedaba sin imágenes ni nombres hasta reinstalar la app.
 *
 * El error opuesto —tratar "TMDB contestó y no tenía" como fallo— también es malo: repreguntaría en
 * cada apertura, para siempre. Por eso son dos valores distintos y no un booleano de "hubo datos".
 */
class TmdbSeasonEpisodesParseTest {

    @Test fun sin_respuesta_devuelve_null_para_que_se_reintente() {
        // `get()` devuelve null tanto en timeout como en 429 o 5xx: todos son "no se pudo preguntar".
        assertNull(parseSeasonEpisodes(json = null, seasonNumber = 1))
    }

    @Test fun una_respuesta_ilegible_tambien_cuenta_como_fallo() {
        // Un cuerpo que no es JSON (error del gateway, portal cautivo de un wifi) no es una
        // respuesta de TMDB: mejor reintentar que sellar la serie en null.
        assertNull(parseSeasonEpisodes(json = "<html>502 Bad Gateway</html>", seasonNumber = 1))
    }

    @Test fun una_temporada_sin_capitulos_devuelve_lista_vacia_no_null() {
        // Esto SÍ es una respuesta: la fila se escribe vacía como marca de "ya preguntado". Si acá
        // saliera null, se repreguntaría en cada apertura del detalle para siempre.
        assertEquals(emptyList<TmdbEpisode>(), parseSeasonEpisodes("""{"episodes":[]}""", 1))
        // Un JSON válido al que le falta la clave `episodes` es el mismo caso, no un fallo.
        assertEquals(emptyList<TmdbEpisode>(), parseSeasonEpisodes("""{"id":1234}""", 1))
    }

    @Test fun una_temporada_con_capitulos_se_parsea_completa() {
        val eps = parseSeasonEpisodes(
            """
            {"episodes":[
              {"season_number":5,"episode_number":1,"name":"La conspiración",
               "overview":"Goku entrena…","air_date":"2013-08-11","still_path":"/abc.jpg"}
            ]}
            """.trimIndent(),
            seasonNumber = 5,
        )
        assertNotNull(eps)
        assertEquals(1, eps!!.size)
        assertEquals(5, eps[0].season)
        assertEquals(1, eps[0].episode)
        assertEquals("La conspiración", eps[0].name)
        assertEquals("Goku entrena…", eps[0].overview)
        assertEquals("2013-08-11", eps[0].air)
        assertEquals("https://image.tmdb.org/t/p/w300/abc.jpg", eps[0].stillUrl)
    }

    @Test fun sin_still_la_url_queda_vacia_no_a_medio_armar() {
        // `stillUrl` en blanco es lo que `ensureEpisodeStills` lee como "TMDB no tiene imagen"; una
        // URL a medio armar se guardaría como si la hubiera y dejaría el hueco en la UI.
        val eps = parseSeasonEpisodes("""{"episodes":[{"episode_number":2}]}""", 3)
        assertEquals("", eps!![0].stillUrl)
        // Sin `season_number` en el JSON manda la temporada que se pidió, que es la que se consultó.
        assertEquals(3, eps[0].season)
        // Sin nombre, un respaldo legible en vez de una cadena vacía.
        assertTrue(eps[0].name.isNotBlank())
    }
}
