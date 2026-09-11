package com.arkiv.player.data

import com.arkiv.player.data.catalog.AnimeMappingRepository
import kotlinx.coroutines.CancellationException

/**
 * Translation between a saved series' LOCAL `identifier` and the "bare" `seriesId` it's known by
 * outside the item table, and the **single source of the canonical seriesId**.
 *
 * The now-removed torrent and web sources used to prefix the seriesId (`web:series:`/
 * `torrent:series:`) so that the same show saved from two different sources wouldn't collide in
 * the same `items` row; [seriesIdOrNull] reads that prefix back out. Those add paths are gone, so
 * no new item gets one of these prefixes, but the ones already in someone's library still do, and
 * [com.arkiv.player.data.LibraryGrouping] still needs to recognize them to group a legacy item
 * with its TMDB-matched counterpart.
 *
 * Lo MISMO pasaba un nivel más arriba, con el seriesId en sí: la pantalla de anime lo armaba como
 * `"anilist$anilistId"` y la de cine/series como `imdbId ?: "tmdb$id"`, así que la MISMA serie
 * entraba a la biblioteca bajo dos ítems distintos según por dónde hubiera entrado el usuario
 * (verificado con DAN DA DAN: `web:series:tt30217403` con 24 capítulos y
 * `web:series:anilist171018` con 1, el mismo capítulo en los dos). Con descargas locales eso
 * significa bajar los mismos GB dos veces. Por eso el criterio vive acá, en
 * [canonicalSeriesId]/[animeSeriesId], y las dos pantallas lo usan.
 */
object SeriesItemIds {

    /** Prefix of a web series' local identifier (legacy: the web source was removed). */
    const val WEB_SERIES_PREFIX = "web:series:"

    /** Prefix of a torrent series' local identifier (legacy: the torrent source was removed). */
    const val TORRENT_SERIES_PREFIX = "torrent:series:"

    /** Prefix of a torrent anime's local identifier (legacy: the torrent source was removed). */
    const val TORRENT_ANIME_PREFIX = "torrent:anime:"

    /** Un id de IMDb bien formado: es lo único que se acepta como primera preferencia. */
    private val IMDB_SHAPE = Regex("""^tt\d+$""")

    /**
     * External seriesId of a saved series' local identifier, or `null` if the identifier isn't
     * one of a saved series (a standalone item, a movie, …). Returning `null` instead of the raw
     * identifier is on purpose: [com.arkiv.player.data.LibraryGrouping] can then skip trying to
     * match a seriesId that doesn't exist.
     */
    fun seriesIdOrNull(identifier: String): String? = when {
        identifier.startsWith(WEB_SERIES_PREFIX) -> identifier.removePrefix(WEB_SERIES_PREFIX)
        identifier.startsWith(TORRENT_SERIES_PREFIX) -> identifier.removePrefix(TORRENT_SERIES_PREFIX)
        else -> null
    }

    /**
     * seriesId canónico de una serie: **IMDb si hay, si no `"tmdb$id"`, si no `"anilist$id"`**.
     *
     * Es LITERALMENTE la preferencia que ya usaba el camino no-anime (`d.imdbId.ifBlank {
     * "tmdb${d.id}" }`), con el mismo criterio laxo de "no vacío": anilist queda solo como último
     * recurso, para el anime cuyo mapeo cruzado todavía no se conoce. Puro a propósito (misma
     * convención que las policies de descarga): quien tenga que ir a buscar el mapeo lo hace
     * afuera y le pasa los ids ya resueltos.
     *
     * **Ojo con endurecer esto.** El `org.json` de ANDROID devuelve el string `"null"` (no `""`)
     * cuando `optString` cae sobre un JSON `null`, y TMDB manda `"imdb_id": null` en las series sin
     * IMDb: hoy esas series están guardadas como `web:series:null`. Rechazar acá los ids mal
     * formados las movería a `web:series:tmdb<id>` — un cambio de identidad SIN mapeo de por medio,
     * o sea exactamente el bug que este archivo viene a cerrar, pero al revés. (El `org.json` de
     * los tests JVM sí filtra el null, así que ningún test lo vería.) Quien necesite validar la
     * forma del id lo hace ANTES de llamar acá: ver [normalizeImdbId], que usa [animeSeriesId] para
     * el dataset de Fribb.
     */
    fun canonicalSeriesId(imdbId: String?, tmdbId: Int?, anilistId: Long? = null): String = when {
        !imdbId.isNullOrBlank() -> imdbId
        tmdbId != null -> "tmdb$tmdbId"
        else -> anilistSeriesId(anilistId)
    }

    /** `"anilist$id"`: el fallback histórico del anime, el único id que siempre se puede armar. */
    fun anilistSeriesId(anilistId: Long?): String = "anilist$anilistId"

    /**
     * `"tt123"` / `["tt123"]` ya desarmado / `"tt123,tt456"` → `"tt123"`; cualquier otra cosa → null.
     *
     * Es solo para el `imdb_id` del dataset de anime (Fribb), que trae el campo a veces como lista y
     * a veces con varios ids pegados con coma. Ahí sí conviene ser estricto: ese id es NUEVO para la
     * app (antes el anime ni miraba el mapeo), así que descartarlo no mueve nada ya guardado, y un
     * id mal formado sería un ítem de biblioteca distinto del que arma el camino de TMDB. NO se
     * aplica al imdb que viene de TMDB — ver [canonicalSeriesId].
     */
    fun normalizeImdbId(raw: String?): String? =
        raw?.substringBefore(',')?.trim()?.takeIf { IMDB_SHAPE.matches(it) }

    /**
     * seriesId canónico de un anime de AniList, resolviendo el mapeo cruzado (imdb/tmdb) por
     * [mappings]. **Único punto** por el que el anime debe armar su seriesId.
     *
     * Es `suspend` porque el mapeo puede tocar disco o red ([AnimeMappingRepository.mappingFor]),
     * así que los llamadores lo invocan dentro de la corrutina que ya usan para guardar; lo que
     * necesita el id en COMPOSICIÓN lo resuelve en un `LaunchedEffect` y muestra el fallback
     * mientras tanto.
     *
     * Un mapeo ausente o un fallo al resolverlo NO rompen el guardado: se cae a `"anilist$id"`,
     * que es exactamente el comportamiento de siempre. La cancelación de la corrutina sí se
     * propaga (si no, un `runCatching` se la tragaría y devolvería un id de más).
     */
    suspend fun animeSeriesId(mappings: AnimeMappingRepository?, anilistId: Long?): String {
        if (mappings == null || anilistId == null) return anilistSeriesId(anilistId)
        val mapping = try {
            mappings.mappingFor(anilistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // normalizeImdbId solo acá: el imdb de Fribb es el que puede venir con varios ids pegados.
        return canonicalSeriesId(normalizeImdbId(mapping?.imdbId), mapping?.tmdbId, anilistId)
    }
}
