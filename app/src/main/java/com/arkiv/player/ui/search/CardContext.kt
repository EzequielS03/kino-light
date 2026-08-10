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
