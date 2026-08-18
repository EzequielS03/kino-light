package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbCategory
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.toTitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RowBrowseViewModel(
    private val rowId: String,
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TitleCard>>(emptyList())
    val items: StateFlow<List<TitleCard>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private var page = 1
    private var loading = false

    /** Carga la siguiente página. Idempotente mientras la anterior no termina. */
    fun loadMore() {
        if (loading || !_canLoadMore.value) return
        loading = true
        _isLoading.value = true
        viewModelScope.launch {
            val source = sourceFor(rowId)
            if (source == null) {
                _canLoadMore.value = false
                _isLoading.value = false
                loading = false
                return@launch
            }
            val result: List<TitleCard> = when (source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(source.type, source.category, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(source.type, source.genreId, page) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(page, source.sort, null, source.genre) }
                        .getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            _items.value = _items.value + result
            // Si llegaron menos de 2 ítems, la fuente se agotó (heurística: TMDB da 20/página,
            // AniList da ~50; cualquier resultado vacío o casi vacío indica fin de páginas).
            _canLoadMore.value = result.size >= 2
            if (result.isNotEmpty()) page++
            _isLoading.value = false
            loading = false
        }
    }

    companion object {
        /** Decodifica el rowId al origen de datos, sin hacer ninguna llamada de red. */
        fun sourceFor(rowId: String): RowSource? = when (rowId) {
            "cartelera"          -> RowSource.Curated("movie", TmdbCategory.NOW_PLAYING)
            "peliculas_populares" -> RowSource.Curated("movie", TmdbCategory.POPULAR)
            "tendencias"         -> RowSource.Curated("movie", TmdbCategory.TRENDING)
            "series_populares"   -> RowSource.Curated("tv", TmdbCategory.POPULAR)
            "series_top"         -> RowSource.Curated("tv", TmdbCategory.TOP_RATED)
            "anime"              -> RowSource.Anime("TRENDING_DESC")
            "anime_populares"    -> RowSource.Anime("POPULARITY_DESC")
            "anime_top"          -> RowSource.Anime("SCORE_DESC")
            else -> when {
                rowId.startsWith("g_movie_") ->
                    rowId.removePrefix("g_movie_").toIntOrNull()
                        ?.let { RowSource.Discover("movie", it) }
                rowId.startsWith("g_tv_") ->
                    rowId.removePrefix("g_tv_").toIntOrNull()
                        ?.let { RowSource.Discover("tv", it) }
                rowId.startsWith("g_anime_") -> {
                    // El slug se creó con g.lowercase().replace(" ", "_").
                    // Reconstruimos el nombre con title-case para que AniList lo reconozca.
                    val genre = rowId.removePrefix("g_anime_")
                        .replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                    RowSource.Anime(sort = "POPULARITY_DESC", genre = genre)
                }
                else -> null
            }
        }
    }
}
