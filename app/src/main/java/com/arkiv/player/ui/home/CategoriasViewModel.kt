package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Géneros de AniList (en inglés) → español. */
val ANIME_GENRE_ES = mapOf(
    "Action" to "Acción",
    "Adventure" to "Aventura",
    "Comedy" to "Comedia",
    "Drama" to "Drama",
    "Fantasy" to "Fantasía",
    "Horror" to "Terror",
    "Mecha" to "Mecha",
    "Music" to "Música",
    "Mystery" to "Misterio",
    "Psychological" to "Psicológico",
    "Romance" to "Romance",
    "Sci-Fi" to "Ciencia Ficción",
    "Slice of Life" to "Vida Cotidiana",
    "Sports" to "Deportes",
    "Supernatural" to "Sobrenatural",
    "Thriller" to "Suspenso",
    "Historical" to "Histórico",
    "Military" to "Militar",
    "School" to "Escolar",
    "Space" to "Espacio",
    "Harem" to "Harem",
    "Ecchi" to "Ecchi",
    "Kids" to "Para niños",
    "Mahou Shoujo" to "Mahou Shoujo",
    "Isekai" to "Isekai",
)

class CategoriasViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** rowId → URL del primer póster de esa categoría (null = aún no cargado). */
    private val _previews = MutableStateFlow<Map<String, String?>>(emptyMap())
    val previews: StateFlow<Map<String, String?>> = _previews.asStateFlow()

    private val fetchedPreviews = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            val anime = runCatching { aniListApi.genres() }.getOrDefault(emptyList())
            _rows.value = buildRowSpecs(movie, tv, anime).map { spec ->
                // Traducir géneros de anime que vienen en inglés de AniList.
                if (spec.id.startsWith("g_anime_")) {
                    val partes = spec.title.split(" · ")
                    val generoEs = ANIME_GENRE_ES[partes.first()] ?: partes.first()
                    spec.copy(title = if (partes.size > 1) "$generoEs · ${partes.last()}" else generoEs)
                } else {
                    spec
                }
            }
            _loading.value = false
        }
    }

    /** Carga el primer póster de una categoría. Idempotente. */
    fun fetchPreview(rowId: String) {
        if (!fetchedPreviews.add(rowId)) return
        val source = RowBrowseViewModel.sourceFor(rowId) ?: return
        viewModelScope.launch {
            val url: String? = when (source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(source.type, source.category, 1) }
                        .getOrNull()?.firstOrNull()?.let {
                            if (source.type == "movie") it.backdropUrl.ifBlank { it.posterUrl }
                            else it.posterUrl
                        }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(source.type, source.genreId, 1) }
                        .getOrNull()?.firstOrNull()?.let {
                            if (source.type == "movie") it.backdropUrl.ifBlank { it.posterUrl }
                            else it.posterUrl
                        }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(1, source.sort, null, source.genre) }
                        .getOrNull()?.firstOrNull()?.let { anime ->
                            anime.bannerUrl.ifBlank { null } ?: anime.posterUrl.ifBlank { null }
                        }
            }
            _previews.update { it + (rowId to url) }
        }
    }
}
