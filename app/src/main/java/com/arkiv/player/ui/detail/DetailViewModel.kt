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

    init {
        // Al abrir el detalle, volver a mirar la fuente: los capítulos que se subieron DESPUÉS de
        // agregar la serie no aparecían nunca (la lista se copiaba una sola vez, al agregarla).
        // Va acá y no en las pantallas para que valga igual en TV y en teléfono. El ViewModel
        // sobrevive a los cambios de configuración, así que es una vez por apertura, no por giro.
        // `detail` observa la DB, así que la lista nueva se pinta sola; si falla la red,
        // refreshItem devuelve un Result fallido y la biblioteca se queda como estaba.
        refresh()
    }

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
