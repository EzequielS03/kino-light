package com.arkiv.player.data

import com.arkiv.player.data.db.RecentTitleDao
import com.arkiv.player.data.db.RecentTitleEntity
import com.arkiv.player.data.db.SearchHistoryDao
import com.arkiv.player.data.db.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Historial del buscador unificado, sobre Room.
 *
 * Reemplaza a un store en SharedPreferences que duplicaba la tabla `search_history`, que ya
 * existía y ya usaban el catálogo, el anime y el buscador del TV.
 *
 * El orden, el tope y el dedupe los hace la consulta; acá solo se normaliza lo que entra
 * ([SearchHistoryPolicy]) y se traduce la entidad al modelo que ve la UI.
 */
class SearchHistoryRepo(
    private val searchHistoryDao: SearchHistoryDao,
    private val recentTitleDao: RecentTitleDao,
) {

    val queries: Flow<List<String>> =
        searchHistoryDao.observeRecent(KIND, SearchHistoryPolicy.MAX_QUERIES)
            .map { filas -> filas.map { it.query } }

    val titles: Flow<List<RecentTitle>> =
        recentTitleDao.observeRecent(SearchHistoryPolicy.MAX_TITLES)
            .map { filas -> filas.map { it.toRecentTitle() } }

    suspend fun addQuery(q: String) {
        val limpio = SearchHistoryPolicy.normalizeQuery(q) ?: return
        // Borrar primero las variantes de mayúsculas: la PK de search_history distingue "Dune"
        // de "dune", así que sin esto quedarían dos chips que son la misma búsqueda.
        searchHistoryDao.deleteOne(KIND, limpio)
        searchHistoryDao.upsert(SearchHistoryEntity(limpio, KIND, System.currentTimeMillis()))
    }

    suspend fun addTitle(t: RecentTitle) {
        recentTitleDao.upsert(
            RecentTitleEntity(
                id = SearchHistoryPolicy.titleId(t),
                kind = t.kind,
                tmdbId = t.tmdbId,
                anilistId = t.anilistId,
                title = t.title,
                posterUrl = t.posterUrl,
                year = t.year,
                atMs = System.currentTimeMillis(),
            ),
        )
        recentTitleDao.trim(SearchHistoryPolicy.MAX_TITLES)
    }

    suspend fun removeQuery(q: String) = searchHistoryDao.deleteOne(KIND, q)

    suspend fun removeTitle(t: RecentTitle) = recentTitleDao.deleteOne(SearchHistoryPolicy.titleId(t))

    /** Borra el historial entero (las dos listas): es lo que espera un botón que dice "borrar". */
    suspend fun clear() {
        searchHistoryDao.clearKind(KIND)
        recentTitleDao.clear()
    }

    private fun RecentTitleEntity.toRecentTitle() =
        RecentTitle(kind, tmdbId, anilistId, title, posterUrl, year)

    companion object {
        /**
         * Cajón propio del buscador unificado. NO se reusa "tv": TvSearchScreen guarda con ese
         * kind y CineCatalogScreen guarda ahí las búsquedas de series, así que esas dos listas ya
         * se mezclan entre sí. No le sumamos un tercero.
         */
        const val KIND = "buscar"
    }
}
