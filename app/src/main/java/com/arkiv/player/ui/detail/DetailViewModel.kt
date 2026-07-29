package com.arkiv.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repo: ArkivRepository,
    private val identifier: String,
) : ViewModel() {

    val detail: StateFlow<ItemDetail?> = repo.observeItemDetail(identifier)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val skipMarker: StateFlow<SkipMarkerEntity?> = repo.observeSkipMarker(identifier)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveSkipMarker(openingStartMs: Long?, openingEndMs: Long?, endingStartMs: Long?) {
        viewModelScope.launch {
            repo.saveSkipMarker(identifier, openingStartMs, openingEndMs, endingStartMs)
        }
    }

    fun toggleWatched(episodeId: String, watched: Boolean) {
        viewModelScope.launch { repo.setWatched(episodeId, watched) }
    }

    fun removeFromLibrary(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.removeItem(identifier)
            onDone()
        }
    }

    fun rename(title: String) {
        viewModelScope.launch { repo.renameItem(identifier, title) }
    }

    fun refresh() {
        viewModelScope.launch { repo.refreshItem(identifier) }
    }
}
