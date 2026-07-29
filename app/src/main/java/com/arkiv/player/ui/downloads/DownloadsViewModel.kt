package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    repo: ArkivRepository,
    private val downloader: Downloader,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadRow>> = repo.observeDownloadRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Sondea el progreso del DownloadManager mientras la pantalla vive.
        viewModelScope.launch {
            while (true) {
                downloader.refreshProgress()
                delay(1_500)
            }
        }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch { downloader.remove(episodeId) }
    }
}
