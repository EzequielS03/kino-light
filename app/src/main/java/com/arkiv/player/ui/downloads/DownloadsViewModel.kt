package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.LocalDownloadManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * El estado sale de Room, que actualiza el worker. Ya no hay poll: el `refreshProgress()` cada 1,5 s
 * existía porque el progreso vivía en el DownloadManager del sistema y había que ir a buscarlo.
 */
class DownloadsViewModel(
    private val manager: LocalDownloadManager,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadRow>> = manager.observeRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** El usuario aceptó bajar un torrent que superaba el umbral de tamaño. */
    fun confirm(episodeId: String) {
        viewModelScope.launch { manager.confirmSize(episodeId) }
    }

    /** Reintenta una descarga fallida: la vuelve a poner en cola y despierta al worker. */
    fun retry(episodeId: String) {
        viewModelScope.launch { manager.retry(episodeId) }
    }

    /** Detiene la descarga conservando el parcial (se puede reintentar y reanuda desde donde iba). */
    fun cancel(episodeId: String) {
        viewModelScope.launch { manager.cancel(episodeId) }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch { manager.remove(episodeId) }
    }
}
