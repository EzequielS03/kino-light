package com.arkiv.player.ui.search

import com.arkiv.player.data.RecentTitle
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbItem

enum class SearchPhase { QUERY, REFINE, RESULTS }

/** Card de la Fase 1. kind: "movie" | "series" (TMDB) | "anime" (AniList). */
data class TitleCard(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val overview: String?,
    /** Imagen apaisada (16:9) para las filas del TV; vacía si la fuente no la trae. */
    val backdropUrl: String = "",
)

/** Lo que volvió de la búsqueda por descripción: cómo se entendió la frase + las cards. */
data class ResultadoDeFrase(
    val interpretado: com.arkiv.player.data.gateway.FraseInterpretada?,
    val cards: List<TitleCard>,
)

/** Obra de la búsqueda por frase → card. El gateway ya garantiza tmdbId y título, así que la card
 *  se abre por el flujo normal de pickTitle. `tipo` habla el idioma de TMDB ("movie"|"tv"). */
fun com.arkiv.player.data.gateway.GatewayObraDeFrase.toTitleCard(): TitleCard = TitleCard(
    kind = if (tipo == "tv") "series" else "movie",
    tmdbId = tmdbId,
    anilistId = null,
    title = titulo,
    posterUrl = posterUrl,
    year = anio,
    overview = null,
)

private val NOMBRES_DE_IDIOMA = mapOf(
    "es" to "español", "en" to "inglés", "ja" to "japonés", "ko" to "coreano",
    "fr" to "francés", "it" to "italiano", "de" to "alemán", "pt" to "portugués",
)

/**
 * Chips con lo que el gateway ENTENDIÓ de la frase. Existen para que un filtro raro se vea venir:
 * si "una de miedo" salió interpretada como comedia, la persona lo ve antes de culpar al catálogo.
 */
fun etiquetasDeInterpretacion(i: com.arkiv.player.data.gateway.FraseInterpretada): List<String> {
    val etiquetas = mutableListOf(if (i.tipo == "tv") "serie" else "película")
    etiquetas += i.generos
    when {
        i.anioDesde != null && i.anioHasta != null -> etiquetas += "${i.anioDesde}–${i.anioHasta}"
        i.anioDesde != null -> etiquetas += "desde ${i.anioDesde}"
        i.anioHasta != null -> etiquetas += "hasta ${i.anioHasta}"
    }
    if (i.idioma.isNotBlank()) etiquetas += "en ${NOMBRES_DE_IDIOMA[i.idioma] ?: i.idioma}"
    return etiquetas
}

/** TMDB → card del home/buscador. `type` de TMDB es "movie"|"tv"; en la UI usamos "movie"|"series". */
fun TmdbItem.toTitleCard(): TitleCard = TitleCard(
    kind = if (type == "tv") "series" else "movie",
    tmdbId = id,
    anilistId = null,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = overview,
    backdropUrl = backdropUrl,
)

/** AniList → card. `year` puede venir 0 cuando no se conoce: mejor vacío que "0". */
fun AnimeShow.toTitleCard(): TitleCard = TitleCard(
    kind = "anime",
    tmdbId = null,
    anilistId = id,
    title = title,
    posterUrl = posterUrl,
    year = if (year > 0) year.toString() else "",
    overview = description,
    // AniList ya trae una imagen apaisada propia (banner); si falta, el TV cae al póster.
    backdropUrl = bannerUrl,
)

/**
 * Texto del buscador → card, para buscar fuentes por lo que hay escrito y no por la ficha del
 * catálogo (botón "Buscar" del buscador del TV). Devuelve null si no hay nada que buscar.
 *
 * Va sin `tmdbId`/`anilistId` a propósito: en el gateway el tmdb_id es solo el desempate entre los
 * títulos que matchean el texto, así que sin él las cuatro fuentes buscan por `q` — que es
 * justamente lo que se quiere acá. `kind` es "movie" porque con season/episode en 0 ninguna fuente
 * filtra por tipo (magis devuelve pelis y series igual), y porque `SearchViewModel.back()` manda
 * las películas de vuelta a QUERY: sin eso, atrás desde las fuentes caería en el selector de
 * temporadas de una card que no existe.
 */
fun cardDeTextoLibre(texto: String): TitleCard? {
    val q = texto.trim().takeIf { it.isNotBlank() } ?: return null
    return TitleCard(
        kind = "movie",
        tmdbId = null,
        anilistId = null,
        title = q,
        posterUrl = "",
        year = "",
        overview = null,
    )
}

/**
 * Quita los repetidos de la grilla de títulos, que junta TMDB con AniList: todo lo que es anime y
 * además está en TMDB salía dos veces con el mismo nombre. Gana el primero de la lista, así el
 * orden que ya se ve no cambia.
 *
 * La clave lleva el AÑO además del nombre: dos películas con el mismo título y distinto año son
 * dos películas distintas (los remakes), y colapsarlas escondería una. Un título vacío no tiene con
 * qué compararse, así que pasa siempre — juntarlos sería juntar cosas que no sabemos si son la
 * misma.
 */
fun sinRepetidos(cards: List<TitleCard>): List<TitleCard> {
    val vistos = HashSet<String>()
    return cards.filter { card ->
        val clave = normalizarTitulo(card.title)
        clave.isEmpty() || vistos.add("$clave|${card.year}")
    }
}

/** Nombre comparable: sin mayúsculas, sin acentos, sin puntuación y con un solo espacio entre
 *  palabras. "¡El  PADRINO!" y "el padrino" son el mismo título. */
private fun normalizarTitulo(titulo: String): String =
    java.text.Normalizer.normalize(titulo.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/** Card → entrada del historial. Se tira `overview`/`backdrop`: el hero los vuelve a pedir igual. */
fun TitleCard.toRecent(): RecentTitle = RecentTitle(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
)

/** Historial → card, para poder tocar un póster reciente y caer directo en las fuentes. */
fun RecentTitle.toTitleCard(): TitleCard = TitleCard(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = null,
)
