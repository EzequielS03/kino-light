package com.arkiv.player.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.catalog.CatalogApi
import com.arkiv.player.data.catalog.CatalogItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CatalogViewModel(private val api: CatalogApi) : ViewModel() {

    private val _items = MutableStateFlow<List<CatalogItem>>(emptyList())
    val items: StateFlow<List<CatalogItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sort = MutableStateFlow("trending")
    val sort: StateFlow<String> = _sort.asStateFlow()

    /** "movies" | "shows". */
    private val _kind = MutableStateFlow("movies")
    val kind: StateFlow<String> = _kind.asStateFlow()

    private var page = 1
    private var query: String? = null
    private var endReached = false

    init { reload() }

    fun setKind(k: String) {
        if (k != _kind.value) { _kind.value = k; reload() }
    }

    fun setSort(s: String) {
        if (s != _sort.value) { _sort.value = s; reload() }
    }

    fun search(q: String) {
        query = q.trim().ifBlank { null }
        reload()
    }

    fun loadMore() {
        if (!_loading.value && !endReached) load(reset = false)
    }

    private fun reload() {
        page = 1
        endReached = false
        _items.value = emptyList()
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val result = runCatching { api.list(_kind.value, page, _sort.value, query) }.getOrDefault(emptyList())
            if (result.isEmpty()) {
                endReached = true
                if (page == 1) _error.value = "No se pudo cargar el catálogo. Revisá la conexión."
            } else {
                page++
            }
            _items.value = if (reset) result else _items.value + result
            _loading.value = false
        }
    }
}
