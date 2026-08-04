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
)
