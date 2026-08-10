package com.arkiv.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    private val repo: ArkivRepository,
    /** Llave de grupo (`tv:46260`) o identifier crudo: `item:<id>` y los identifiers sueltos resuelven a un solo miembro. */
    private val groupKey: String,
) : ViewModel() {

    /** Todas las adquisiciones de esta serie; alimenta el selector de fuente. */
    val sources: StateFlow<List<LibraryRow>> = repo.observeGroupMembers(groupKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** La fuente elegida a mano; si es null se usa la primera de [sources] (la más completa). */
    private val _selected = MutableStateFlow<String?>(null)

    /** Identifier del ítem que se está mostrando. */
    val selectedId: StateFlow<String?> = combine(sources, _selected) { list, manual ->
        manual?.takeIf { id -> list.any { it.identifier == id } } ?: list.firstOrNull()?.identifier
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val detail: StateFlow<ItemDetail?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeItemDetail(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val skipMarker: StateFlow<SkipMarkerEntity?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeSkipMarker(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // El refresh necesita saber QUÉ ítem refrescar, y eso llega con el primer valor de
        // selectedId (viene de la DB, no es inmediato). filterNotNull().first() evita refrescar
        // null y evita que el detalle quede sin recargar si el grupo tarda en resolverse.
        viewModelScope.launch {
            val id = selectedId.filterNotNull().first()
            repo.refreshItem(id)
        }
    }

    fun selectSource(identifier: String) { _selected.value = identifier }

    fun saveSkipMarker(openingStartMs: Long?, openingEndMs: Long?, endingStartMs: Long?) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.saveSkipMarker(id, openingStartMs, openingEndMs, endingStartMs) }
    }

    fun toggleWatched(episodeId: String, watched: Boolean) {
        viewModelScope.launch { repo.setWatched(episodeId, watched) }
    }

    /** Borra SOLO la fuente que se está viendo. Si era la última del grupo, la tarjeta desaparece. */
    fun removeFromLibrary(onDone: () -> Unit) {
        val id = selectedId.value ?: return onDone()
        viewModelScope.launch {
            repo.removeItem(id)
            onDone()
        }
    }

    fun rename(title: String) {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.renameItem(id, title) }
    }

    fun refresh() {
        val id = selectedId.value ?: return
        viewModelScope.launch { repo.refreshItem(id) }
    }
}
