package com.arkiv.player.data

/**
 * Traducción entre el `identifier` del ítem LOCAL de una serie guardada y el `seriesId` "desnudo"
 * con el que se la conoce afuera (catálogo y NUC).
 *
 * Al guardar, [ArkivRepository.addWebSeriesEpisode] y [ArkivRepository.addSeriesEpisodeMagnet]
 * prefijan el seriesId para que un mismo show guardado desde la web y desde torrent no colisione
 * en la misma fila de `items`. Pero `nuc_library_items`, `series_playback_prefs` y la API de
 * arkiv-offline usan el seriesId SIN prefijo (`"anilist$id"` / `imdbId` / `"tmdb$id"`), que es el
 * que mandan AnimeShowDetailScreen/CineDetailScreen al crear los jobs de descarga.
 *
 * Cruzar los dos formatos sin traducir no explota: simplemente no encuentra nada nunca. Por eso
 * vive acá, en un solo lugar, en vez de repetir el literal del prefijo en cada pantalla.
 */
object SeriesItemIds {

    /** Prefijo del identifier local de una serie web (ver [ArkivRepository.addWebSeriesEpisode]). */
    const val WEB_SERIES_PREFIX = "web:series:"

    /** Prefijo del identifier local de una serie torrent (ver [ArkivRepository.addSeriesEpisodeMagnet]). */
    const val TORRENT_SERIES_PREFIX = "torrent:series:"

    /**
     * seriesId externo de un identifier local de serie, o `null` si el identifier no es de una
     * serie guardada (un ítem de archive.org, una película web suelta, …). Devolver `null` en vez
     * del identifier crudo es a propósito: así el llamador puede saltarse la consulta a la NUC en
     * vez de preguntar por un seriesId que no existe.
     */
    fun seriesIdOrNull(identifier: String): String? = when {
        identifier.startsWith(WEB_SERIES_PREFIX) -> identifier.removePrefix(WEB_SERIES_PREFIX)
        identifier.startsWith(TORRENT_SERIES_PREFIX) -> identifier.removePrefix(TORRENT_SERIES_PREFIX)
        else -> null
    }
}
