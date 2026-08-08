package com.arkiv.player.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El criterio del seriesId canónico: la MISMA serie tiene que dar el MISMO id entre por donde entre
 * el usuario. El bug real: DAN DA DAN quedó guardada como `web:series:tt30217403` (24 capítulos,
 * desde "Películas y series") y como `web:series:anilist171018` (1 capítulo, desde anime), con el
 * mismo capítulo bajado dos veces.
 */
class CanonicalSeriesIdTest {

    @Test
    fun `imdb manda sobre tmdb y sobre anilist`() {
        assertEquals(
            "tt30217403",
            SeriesItemIds.canonicalSeriesId(imdbId = "tt30217403", tmdbId = 240411, anilistId = 171018),
        )
    }

    @Test
    fun `sin imdb cae a tmdb`() {
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(imdbId = "", tmdbId = 240411, anilistId = 171018),
        )
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(imdbId = null, tmdbId = 240411, anilistId = 171018),
        )
    }

    /** Sin mapeo NO se inventa nada: queda el id de anilist, o sea lo que hacía la app hasta ahora. */
    @Test
    fun `sin imdb ni tmdb cae a anilist`() {
        assertEquals(
            "anilist171018",
            SeriesItemIds.canonicalSeriesId(imdbId = null, tmdbId = null, anilistId = 171018),
        )
    }

    /** El camino no-anime pasa por acá sin anilistId; que no haya nada que devolver es un no-caso. */
    @Test
    fun `el camino no-anime da exactamente lo de antes`() {
        assertEquals("tt0388629", SeriesItemIds.canonicalSeriesId("tt0388629", 37854))
        assertEquals("tmdb37854", SeriesItemIds.canonicalSeriesId("", 37854))
    }

    /**
     * NO se valida la forma del imdb que llega de TMDB: el `org.json` de Android devuelve el string
     * `"null"` (no `""`) cuando `optString` cae sobre un JSON `null`, y TMDB manda `"imdb_id": null`
     * en las series sin IMDb — o sea que hoy hay ítems guardados como `web:series:null`. Rechazarlo
     * los movería a `web:series:tmdb<id>`: un cambio de identidad sin mapeo de por medio, el mismo
     * bug que esto viene a cerrar pero al revés. (Este test pasa igual con el org.json de la JVM,
     * que sí filtra el null; el string se pasa a mano justamente por eso.)
     */
    @Test
    fun `un imdb con forma rara del camino de TMDB se respeta tal cual`() {
        assertEquals("null", SeriesItemIds.canonicalSeriesId(imdbId = "null", tmdbId = 240411))
        assertEquals("unknown", SeriesItemIds.canonicalSeriesId(imdbId = "unknown", tmdbId = 240411))
    }

    /**
     * El dataset de anime (Fribb) trae `imdb_id` a veces como lista y a veces con varios ids pegados
     * con coma. Ahí sí se valida: ese id es NUEVO para la app (antes el anime ni miraba el mapeo),
     * así que descartarlo no mueve nada ya guardado, y uno mal formado sería un ítem distinto del
     * que arma el camino de TMDB — el mismo bug de duplicación.
     */
    @Test
    fun `normaliza el imdb del dataset de anime`() {
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("tt30217403"))
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("  tt30217403 "))
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("tt30217403,tt9999999"))
        assertNull(SeriesItemIds.normalizeImdbId(""))
        assertNull(SeriesItemIds.normalizeImdbId(null))
        assertNull(SeriesItemIds.normalizeImdbId("unknown"))
        assertNull(SeriesItemIds.normalizeImdbId("null"))
        assertNull(SeriesItemIds.normalizeImdbId("30217403"))
    }

    /** Un imdb basura del DATASET se descarta y el anime cae a tmdb, que es lo que da TMDB. */
    @Test
    fun `un imdb basura del dataset no se usa y se cae a tmdb`() {
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(
                imdbId = SeriesItemIds.normalizeImdbId("unknown"),
                tmdbId = 240411,
                anilistId = 171018,
            ),
        )
    }

    /**
     * Sin repositorio de mapeo (o sin anilistId) el resultado es el de siempre. `"anilistnull"` es
     * feo pero es LITERALMENTE lo que armaba `"anilist${card.anilistId ?: animeShow?.id}"` con los
     * dos en null: se conserva a propósito para no cambiar bajo qué id quedó lo ya guardado.
     */
    @Test
    fun `sin mapeo disponible usa el fallback de anilist`() = runBlocking {
        assertEquals("anilist171018", SeriesItemIds.animeSeriesId(mappings = null, anilistId = 171018))
        assertEquals("anilistnull", SeriesItemIds.animeSeriesId(mappings = null, anilistId = null))
    }

    @Test
    fun `traduce el identifier local al seriesId desnudo`() {
        assertEquals("tt30217403", SeriesItemIds.seriesIdOrNull("web:series:tt30217403"))
        assertEquals("tt30217403", SeriesItemIds.seriesIdOrNull("torrent:series:tt30217403"))
        assertNull(SeriesItemIds.seriesIdOrNull("dragon-ball-gt"))
        assertNull(SeriesItemIds.seriesIdOrNull("torrent:anime:171018"))
    }
}
