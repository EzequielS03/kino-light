package com.arkiv.player.data.catalog.web

/**
 * Un resultado crudo de una web de streaming (una tarjeta del listado o de la búsqueda).
 * [pageUrl] es la carga útil para la fase de reproducción (aún no implementada). [tmdbId] lo
 * rellena WebTmdbMatcher; [subtitleUrl]/[audioLanguages]/[quality] fina se pueblan al resolver.
 *
 * [season]: temporada REAL del capítulo cuando el resultado viene del mirror (`MirrorWebSource.season`,
 * ver `MirrorWebMapper`); `null` en el scraping en vivo, que no la conoce. La fila local del episodio
 * se guarda con clave = hash de [pageUrl] (`ArkivRepository.addWebSeriesEpisode`), o sea que el camino
 * de packs y el de "un episodio suelto" escriben LA MISMA fila y gana el último: si el suelto inventa
 * `season = 1`, le revierte la temporada a la fila y `PlaybackPreferenceStore.decide()` deja de
 * encontrar el capítulo bajado a la NUC. Por eso la temporada viaja acá, con el resultado, en vez de
 * reconstruirse después (ver `WebSourceSeason`).
 *
 * [episode]: mismo problema que [season] pero de número de episodio (`MirrorWebSource.episode`, que
 * para anime de larga duración puede ser absoluto y no el de AniList); `null` en scraping en vivo.
 * Ver `WebSourceEpisode`.
 */
data class WebResult(
    val siteId: String,
    val siteName: String,
    val title: String,
    val year: String,
    val pageUrl: String,
    val posterUrl: String,
    val language: String,
    val quality: String = "",
    val kind: String,              // "movie" | "tv"
    val tmdbId: Int? = null,
    val subtitleUrl: String? = null,
    val audioLanguages: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    /** Ref opaco del gateway, cuando el resultado vino de ahi. Se manda tal cual a `/v1/resolve`
     *  y la app nunca lo interpreta: asi una fuente puede cambiar por dentro sin obligar a un APK. */
    val gatewayRef: String? = null,
) {
    /**
     * Identidad del resultado para deduplicar y para las keys de las listas.
     *
     * El `ref` manda cuando existe: los resultados del gateway llegan SIN `pageUrl` (la pagina se
     * resuelve recien al reproducir), asi que identificarlos por esa URL vacia hacia que todos los
     * de un titulo se vieran como el mismo y la lista mostrara uno solo.
     */
    val identity: String get() = gatewayRef ?: pageUrl
}
