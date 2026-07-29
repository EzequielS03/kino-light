package com.arkiv.player.data.catalog.web

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Un hit de TMDB reducido a lo que el matcher necesita. */
data class TmdbHit(val id: Int, val title: String, val year: String, val posterUrl: String)

/** Búsqueda TMDB desacoplada (para testear sin la API real). type: "movie" | "tv". */
fun interface TmdbLookup {
    suspend fun search(type: String, query: String): List<TmdbHit>
}

/**
 * Capa de identidad: enriquece un [WebResult] con TMDB (equivalente a set_infoLabels_itemlist de Alfa).
 * Match confiable = título normalizado igual + año ±1 (o año faltante en la web) → hereda tmdbId + póster.
 */
class WebTmdbMatcher(private val lookup: TmdbLookup) {

    // ConcurrentHashMap: enrichAll llama a enrich() en paralelo (async por cada resultado),
    // por lo que el cache se accede concurrentemente desde varios hilos del dispatcher.
    private val cache = ConcurrentHashMap<String, List<TmdbHit>>()

    suspend fun enrichAll(results: List<WebResult>): List<WebResult> = coroutineScope {
        results.map { async { enrich(it) } }.map { it.await() }
    }

    suspend fun enrich(result: WebResult): WebResult {
        val type = if (result.kind == "tv") "tv" else "movie"
        val key = "$type:${normalize(result.title)}"
        val hits = cache.getOrPut(key) {
            runCatching { lookup.search(type, result.title) }.getOrDefault(emptyList())
        }
        val match = hits.firstOrNull { hit -> isConfident(result, hit) } ?: return result
        return result.copy(
            tmdbId = match.id,
            posterUrl = match.posterUrl.ifBlank { result.posterUrl },
        )
    }

    private fun isConfident(web: WebResult, hit: TmdbHit): Boolean {
        if (normalize(web.title) != normalize(hit.title)) return false
        val wy = web.year.take(4).toIntOrNull()
        val hy = hit.year.take(4).toIntOrNull()
        if (wy == null || hy == null) return true // sin año en alguno → basta el título
        return kotlin.math.abs(wy - hy) <= 1
    }

    companion object {
        /** minúsculas, sin tildes, sin puntuación, sin "(2024)", espacios colapsados. */
        fun normalize(title: String): String {
            var s = title.lowercase().replace(Regex("\\(\\d{4}\\)"), " ")
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            s = s.replace(Regex("[^a-z0-9 ]"), " ")
            return s.replace(Regex("\\s+"), " ").trim()
        }
    }
}
