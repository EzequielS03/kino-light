package com.arkiv.player.data

import com.arkiv.player.data.catalog.AnimeMappingRepository
import kotlinx.coroutines.CancellationException

/**
 * Traducción entre el `identifier` del ítem LOCAL de una serie guardada y el `seriesId` "desnudo"
 * con el que se la conoce afuera (catálogo y NUC), y **fuente única del seriesId canónico**.
 *
 * Al guardar, [ArkivRepository.addWebSeriesEpisode] y [ArkivRepository.addSeriesEpisodeMagnet]
 * prefijan el seriesId para que un mismo show guardado desde la web y desde torrent no colisione
 * en la misma fila de `items`. Pero `nuc_library_items`, `series_playback_prefs` y la API de
 * arkiv-offline usan el seriesId SIN prefijo (`"anilist$id"` / `imdbId` / `"tmdb$id"`), que es el
 * que mandan AnimeShowDetailScreen/CineDetailScreen al crear los jobs de descarga.
 *
 * Cruzar los dos formatos sin traducir no explota: simplemente no encuentra nada nunca. Por eso
 * vive acá, en un solo lugar, en vez de repetir el literal del prefijo en cada pantalla.
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

    /** Prefijo del identifier local de una serie web (ver [ArkivRepository.addWebSeriesEpisode]). */
    const val WEB_SERIES_PREFIX = "web:series:"

    /** Prefijo del identifier local de una serie torrent (ver [ArkivRepository.addSeriesEpisodeMagnet]). */
    const val TORRENT_SERIES_PREFIX = "torrent:series:"

    /** Prefijo del identifier local de un anime torrent (ver [ArkivRepository.addAnimeEpisode]). */
    const val TORRENT_ANIME_PREFIX = "torrent:anime:"

    /** Prefijo del identifier local de una serie de Ditu (ver [ArkivRepository.saveDituSeriesEpisode]). */
    const val DITU_SERIE_PREFIX = "ditu:serie:"

    /** Un id de IMDb bien formado: es lo único que se acepta como primera preferencia. */
    private val IMDB_SHAPE = Regex("""^tt\d+$""")

    /**
     * seriesId externo de un identifier local de serie, o `null` si el identifier no es de una
     * serie guardada (un ítem de archive.org, una película web suelta, …). Devolver `null` en vez
     * del identifier crudo es a propósito: así el llamador puede saltarse la consulta a la NUC en
     * vez de preguntar por un seriesId que no existe.
     */
    fun seriesIdOrNull(identifier: String): String? = when {
        identifier.startsWith(WEB_SERIES_PREFIX) -> identifier.removePrefix(WEB_SERIES_PREFIX)
        identifier.startsWith(TORRENT_SERIES_PREFIX) -> identifier.removePrefix(TORRENT_SERIES_PREFIX)
        identifier.startsWith(DITU_SERIE_PREFIX) -> identifier.removePrefix(DITU_SERIE_PREFIX)
        else -> null
    }

    /**
     * seriesId canónico de una serie: **IMDb si hay, si no `"tmdb$id"`, si no `"anilist$id"`**.
     *
     * Es LITERALMENTE la preferencia que ya usaba el camino no-anime (`d.imdbId.ifBlank {
     * "tmdb${d.id}" }`), con el mismo criterio laxo de "no vacío": anilist queda solo como último
     * recurso, para el anime cuyo mapeo cruzado todavía no se conoce. Puro a propósito (misma
     * convención que `TorrentSizeGate` y compañía): quien tenga que ir a buscar el mapeo lo hace
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
