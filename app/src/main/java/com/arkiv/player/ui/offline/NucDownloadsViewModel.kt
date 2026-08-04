package com.arkiv.player.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.LocalActiveJobDao
import com.arkiv.player.data.db.NucLibraryItemDao
import com.arkiv.player.data.db.NucLibraryItemEntity
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.NucJob
import com.arkiv.player.data.offline.NucJobEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Progreso en vivo de los jobs de descarga a la NUC (arkiv-offline) que este dispositivo disparó.
 * arkiv-offline no tiene un endpoint "listame todos los jobs" (solo `GET /jobs/<id>` puntual), así
 * que [jobDao] (tabla `local_active_jobs`, Task 9) es la única fuente de qué ids observar -- se
 * llena en `downloadPack`/`downloadEpisode` (Task 8) y se limpia acá apenas un job llega a un
 * estado terminal (done/failed).
 *
 * Nota de diseño respecto al sketch del brief: ahí `activeJobIds` era `() -> List<Long>` (síncrono);
 * acá el DAO real es `suspend`, así que la carga inicial se hace dentro de `viewModelScope.launch`
 * en vez de una lambda no-suspend inyectada.
 */
class NucDownloadsViewModel(
    private val api: ArkivOfflineApi,
    private val events: NucJobEvents,
    private val jobDao: LocalActiveJobDao,
    private val libraryDao: NucLibraryItemDao,
) : ViewModel() {
    private val _activeJobs = MutableStateFlow<List<NucJob>>(emptyList())
    val activeJobs: StateFlow<List<NucJob>> = _activeJobs

    private val _finished = MutableStateFlow<List<NucLibraryItemEntity>>(emptyList())
    val finished: StateFlow<List<NucLibraryItemEntity>> = _finished

    init {
        viewModelScope.launch {
            jobDao.getAll().forEach { local -> observe(local.jobId) }
            _finished.value = libraryDao.getAll()
        }
    }

    private fun observe(jobId: Long) {
        viewModelScope.launch {
            events.observeJob(jobId).collect { job ->
                _activeJobs.value = _activeJobs.value.filterNot { it.jobId == job.jobId } + job
                // Estado terminal: ya no hace falta seguirlo la próxima vez que se abra esta
                // pantalla -- se limpia el registro local (el job en sí sigue vivo en la NUC).
                if (job.status == "done" || job.status == "failed") {
                    jobDao.delete(job.jobId)
                    _finished.value = libraryDao.getAll()
                }
            }
        }
    }

    fun cancel(jobId: Long) {
        viewModelScope.launch {
            api.deleteJob(jobId)
            jobDao.delete(jobId)
            _activeJobs.value = _activeJobs.value.filterNot { it.jobId == jobId }
        }
    }
}
