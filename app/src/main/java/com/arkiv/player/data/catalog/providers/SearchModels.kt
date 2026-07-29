package com.arkiv.player.data.catalog.providers

/** Tipo de contenido buscado; selecciona la plantilla de query del proveedor. */
enum class ContentType { MOVIE, TV, ANIME }

/** Contexto estructurado de una búsqueda (lo que necesita cada proveedor para armar sus queries). */
data class SearchContext(
    val titles: List<String>,
    val type: ContentType,
    val season: Int = 0,
    val episode: Int = 0,
    val year: String = "",
    val episodeAbs: Int = 0,
)
