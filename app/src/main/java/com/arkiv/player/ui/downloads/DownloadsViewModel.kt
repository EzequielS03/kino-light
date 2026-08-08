package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.data.local.DownloadItemMeta
import com.arkiv.player.data.local.LocalDownloadManager
import com.arkiv.player.data.model.Episode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * El estado sale de Room, que actualiza el worker. Ya no hay poll: el `refreshProgress()` cada 1,5 s
 * existía porque el progreso vivía en el DownloadManager del sistema y había que ir a buscarlo.
 *
 * Agrupa las filas de `downloads` por ítem (ver [DownloadGroupPolicy], que es la parte pura y
 * testeada). Para mostrar TODOS los capítulos de la serie -- no solo los que pasaron por la cola --
 * mantiene un cache de `repository.episodesOf(itemId)` por itemId, que solo se refresca cuando
 * cambia el CONJUNTO de ítems en la cola (no en cada tick de progreso, que llega varias veces por
 * segundo mientras algo está bajando).
 */
class DownloadsViewModel(
    private val manager: LocalDownloadManager,
    private val repository: ArkivRepository,
) : ViewModel() {

    private val downloadRows = manager.observeRows()

    private val episodesByItem = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())

    init {
        viewModelScope.launch {
            downloadRows
                .map { rows -> rows.map { it.itemId }.toSet() }
                .distinctUntilChanged()
                .collect { itemIds ->
                    episodesByItem.value = itemIds.associateWith { repository.episodesOf(it) }
                }
        }
    }

    val groups: StateFlow<List<DownloadGroup>> = combine(
        downloadRows,
        repository.observeLibrary(),
        episodesByItem,
    ) { downloads, library, episodes ->
        val meta = library.associate { it.identifier to DownloadItemMeta(it.title, it.thumbnailUrl, it.source) }
        DownloadGroupPolicy.buildGroups(downloads, episodes, meta)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Encola un capítulo que todavía no se había descargado, desde la fila expandida del grupo. */
    fun download(episodeId: String, source: String) {
        viewModelScope.launch { manager.enqueue(episodeId, source) }
    }

    /**
     * Cancela TODO lo activo del grupo (encolado + en vuelo). Va fila por fila por
     * [LocalDownloadManager.cancel] -- es el único camino que corta de verdad el worker cuando la
     * que está en vuelo es una de estas (ver su KDoc); llamarlo también para las encoladas de más
     * no hace nada raro, porque `cancel` ya distingue cuál es la fila que corre. Sin esto, "cancelar
     * todos" solo tacharía filas de la cola dejando la descarga en curso corriendo sola -- el bug de
     * archivo huérfano que ya se arregló una vez.
     */
    fun cancelGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.activeEpisodeIds(group)) manager.cancel(episodeId)
        }
    }

    /** Quita todas las filas descargadas/en curso del grupo (los "no descargados" no tienen nada que quitar). */
    fun removeGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.trackedEpisodeIds(group)) manager.remove(episodeId)
        }
    }

    /** Reencola solo los capítulos fallidos del grupo. */
    fun retryFailedGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.failedEpisodeIds(group)) manager.retry(episodeId)
        }
    }
}
