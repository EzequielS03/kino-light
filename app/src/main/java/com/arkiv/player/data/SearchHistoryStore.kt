package com.arkiv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial del buscador: qué se buscó y qué títulos se abrieron.
 *
 * Va aparte de [SettingsStore] a propósito. Ese archivo es configuración (URLs, llaves, calidad);
 * esto es dato de uso, que se ensucia y se borra. La lógica de orden y tope vive en
 * [SearchHistoryPolicy]; acá solo hay prefs y JSON.
 *
 * Lectura tolerante: un JSON corrupto o de un formato viejo devuelve lista vacía. El historial
 * nunca puede tumbar la pantalla de búsqueda.
 */
class SearchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _queries = MutableStateFlow(leerQueries())
    val queries: StateFlow<List<String>> = _queries

    private val _titles = MutableStateFlow(leerTitles())
    val titles: StateFlow<List<RecentTitle>> = _titles

    fun addQuery(q: String) = guardarQueries(SearchHistoryPolicy.pushQuery(_queries.value, q))

    fun addTitle(t: RecentTitle) = guardarTitles(SearchHistoryPolicy.pushTitle(_titles.value, t))

    fun removeQuery(q: String) = guardarQueries(_queries.value.filterNot { it.equals(q, ignoreCase = true) })

    fun removeTitle(t: RecentTitle) =
        guardarTitles(_titles.value.filterNot { SearchHistoryPolicy.mismaIdentidad(it, t) })

    /** Borra el historial entero (las dos listas): es lo que espera un botón que dice "borrar". */
    fun clear() {
        guardarQueries(emptyList())
        guardarTitles(emptyList())
    }

    private fun guardarQueries(lista: List<String>) {
        prefs.edit().putString(KEY_QUERIES, JSONArray(lista).toString()).apply()
        _queries.value = lista
    }

    private fun guardarTitles(lista: List<RecentTitle>) {
        val arr = JSONArray()
        for (t in lista) {
            arr.put(
                JSONObject().apply {
                    put("kind", t.kind)
                    t.tmdbId?.let { put("tmdbId", it) }
                    t.anilistId?.let { put("anilistId", it) }
                    put("title", t.title)
                    put("poster", t.posterUrl)
                    put("year", t.year)
                },
            )
        }
        prefs.edit().putString(KEY_TITLES, arr.toString()).apply()
        _titles.value = lista
    }

    private fun leerQueries(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_QUERIES, "[]"))
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun leerTitles(): List<RecentTitle> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_TITLES, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            if (title.isBlank()) return@mapNotNull null
            RecentTitle(
                kind = o.optString("kind", "movie"),
                tmdbId = if (o.has("tmdbId")) o.optInt("tmdbId") else null,
                anilistId = if (o.has("anilistId")) o.optLong("anilistId") else null,
                title = title,
                posterUrl = o.optString("poster"),
                year = o.optString("year"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PREFS_NAME = "arkiv_search_history"
        private const val KEY_QUERIES = "queries"
        private const val KEY_TITLES = "titles"
    }
}
