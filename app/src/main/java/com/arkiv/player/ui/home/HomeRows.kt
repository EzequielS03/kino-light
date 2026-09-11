package com.arkiv.player.ui.home

import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.data.catalog.TmdbGenre
import com.arkiv.player.ui.search.TitleCard

/** De dónde saca sus títulos una fila del home. */
sealed interface RowSource {
    data class Curated(val type: String, val category: TmdbCategory) : RowSource
    data class Discover(val type: String, val genreId: Int) : RowSource
    data class Anime(val sort: String, val genre: String? = null) : RowSource
}

/** Una fila horizontal del home. El `id` es la clave de caché y de carga perezosa. */
data class HomeRowSpec(val id: String, val title: String, val source: RowSource)

/**
 * Filas fijas primero (las más útiles) y luego una por género — películas y después series.
 * Las de género son muchas a propósito: se cargan solo cuando entran en pantalla.
 */
fun buildRowSpecs(
    movieGenres: List<TmdbGenre>,
    tvGenres: List<TmdbGenre>,
    animeGenres: List<String>,
): List<HomeRowSpec> = buildList {
    add(HomeRowSpec("cartelera", "En cartelera", RowSource.Curated("movie", TmdbCategory.NOW_PLAYING)))
    // "Upcoming" (UPCOMING) was dropped on purpose: it's titles not released yet, so there's
    // almost never a source for them (true back when torrent was a source here, and still true
    // for Magis/Ditu today). Overlap between the rest of the rows is handled with dedup (see
    // dedupAgainst), not by dropping rows.
    add(HomeRowSpec("peliculas_populares", "Películas populares", RowSource.Curated("movie", TmdbCategory.POPULAR)))
    add(HomeRowSpec("tendencias", "Tendencias de la semana", RowSource.Curated("movie", TmdbCategory.TRENDING)))
    add(HomeRowSpec("series_populares", "Series populares", RowSource.Curated("tv", TmdbCategory.POPULAR)))
    add(HomeRowSpec("series_top", "Series mejor valoradas", RowSource.Curated("tv", TmdbCategory.TOP_RATED)))
    add(HomeRowSpec("anime", "Anime del momento", RowSource.Anime("TRENDING_DESC")))
    add(HomeRowSpec("anime_populares", "Anime populares", RowSource.Anime("POPULARITY_DESC")))
    add(HomeRowSpec("anime_top", "Anime mejor valorados", RowSource.Anime("SCORE_DESC")))
    movieGenres.forEach { g -> add(HomeRowSpec("g_movie_${g.id}", "${g.name} · Películas", RowSource.Discover("movie", g.id))) }
    tvGenres.forEach { g -> add(HomeRowSpec("g_tv_${g.id}", "${g.name} · Series", RowSource.Discover("tv", g.id))) }
    animeGenres.forEach { g ->
        add(HomeRowSpec("g_anime_${g.lowercase().replace(" ", "_")}", "$g · Anime", RowSource.Anime("POPULARITY_DESC", g)))
    }
}

/**
 * Normaliza un texto para comparación tolerante a tildes y mayúsculas.
 * "Acción" y "accion" resultan iguales; "Sci-Fi" y "sci-fi" también.
 */
private fun String.normalizarBusqueda(): String =
    this.lowercase().map { c ->
        when (c) {
            'á', 'à', 'â', 'ä' -> 'a'; 'é', 'è', 'ê', 'ë' -> 'e'
            'í', 'ì', 'î', 'ï' -> 'i'; 'ó', 'ò', 'ô', 'ö' -> 'o'
            'ú', 'ù', 'û', 'ü' -> 'u'; 'ñ' -> 'n'; else -> c
        }
    }.joinToString("").trim()

/**
 * Devuelve la primera [HomeRowSpec] cuya fila coincide con [q].
 * Estrategias (en orden):
 *  1. El genre-name antes del " · " es idéntico: "Acción · Películas" ← "accion" ✓
 *  2. Alguna palabra suelta del título coincide: "Anime del momento" ← "anime" ✓
 *                                                 "En cartelera"     ← "cartelera" ✓
 * Tolerante a tildes y mayúsculas. Devuelve null si [q] está en blanco.
 */
fun matchCategoryRow(q: String, rows: List<HomeRowSpec>): HomeRowSpec? {
    val normalized = q.normalizarBusqueda()
    if (normalized.isBlank()) return null
    return rows.firstOrNull { spec ->
        val titleNorm = spec.title.normalizarBusqueda()
        val base = spec.title.split(" · ").first().normalizarBusqueda()
        base == normalized || titleNorm.split(" ").contains(normalized)
    }
}

/** Ruta del buscador que salta la fase de escribir y arranca ya en ese título. */
fun searchShortcutRoute(card: TitleCard): String = when (card.kind) {
    "anime" -> "search?kind=anime&anilistId=${card.anilistId}"
    "movie" -> "search?kind=movie&tmdbId=${card.tmdbId}"
    else -> "search?kind=series&tmdbId=${card.tmdbId}"
}

/**
 * Identidad de un título para deduplicar entre filas. El id de TMDB se repite entre películas y
 * series (son espacios distintos), así que va con el tipo; el de AniList es propio.
 */
fun cardKey(card: TitleCard): String = when {
    card.anilistId != null -> "anilist:${card.anilistId}"
    card.tmdbId != null -> "${card.kind}:${card.tmdbId}"
    else -> "title:${card.title.lowercase()}"
}

/**
 * Quita de [cards] los títulos que ya aparecieron en otra fila, y de paso los repetidos dentro de la
 * propia lista. Sin esto, "En cartelera", "Populares", "Tendencias" y los géneros muestran casi las
 * mismas películas: cada título se queda en la primera fila donde aparece.
 */
fun dedupAgainst(seen: Set<String>, cards: List<TitleCard>): List<TitleCard> {
    val used = seen.toMutableSet()
    return cards.filter { used.add(cardKey(it)) }
}

/** Evita que una fila vuelva a pedir red al recomponerse o al volver a entrar en pantalla. */
class LoadGuard {
    private val started = mutableSetOf<String>()
    fun shouldLoad(id: String): Boolean = started.add(id)
}
