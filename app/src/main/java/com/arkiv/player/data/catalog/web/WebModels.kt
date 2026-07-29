package com.arkiv.player.data.catalog.web

/**
 * Un resultado crudo de una web de streaming (una tarjeta del listado o de la búsqueda).
 * [pageUrl] es la carga útil para la fase de reproducción (aún no implementada). [tmdbId] lo
 * rellena WebTmdbMatcher; [subtitleUrl]/[audioLanguages]/[quality] fina se pueblan al resolver.
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
)
