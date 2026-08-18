package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CategoriasViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
) : ViewModel() {

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            val anime = runCatching { aniListApi.genres() }.getOrDefault(emptyList())
            _rows.value = buildRowSpecs(movie, tv, anime)
            _loading.value = false
        }
    }
}
