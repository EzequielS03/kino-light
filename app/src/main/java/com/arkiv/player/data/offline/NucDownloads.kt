package com.arkiv.player.data.offline

import android.content.Context
import com.arkiv.player.data.db.LocalActiveJobDao
import com.arkiv.player.data.db.LocalActiveJobEntity
import com.arkiv.player.data.db.NucLibraryItemDao
import com.arkiv.player.data.db.NucLibraryItemEntity

/**
 * Punto ÚNICO para disparar una descarga a la NUC (arkiv-offline) y para refrescar la caché local
 * de "qué está descargado".
 *
 * Existe porque estas dos secuencias estaban copiadas y pegadas en 5 y 2 lugares respectivamente
 * (`downloadPack`/`downloadEpisode` de AnimeShowDetailScreen y CineDetailScreen,
 * `downloadWholeSeries` de SearchScreen; y el refresco de biblioteca al abrir cada detalle). Cada
 * copia era una oportunidad de que un arreglo se aplicara a unas sí y a otras no -- de hecho el bug
 * de `season` fijo en 1 se "arregló" tres veces en esta rama, una por cada copia que se había
 * quedado afuera. Con un solo punto, arreglar acá arregla todos los flujos a la vez.
 */
object NucDownloads {

    /** Mensaje de error único (antes repetido literal en las 5 copias). */
    const val START_ERROR = "No se pudo iniciar la descarga (revisá la conexión con la NUC)"

    /**
     * Crea el job en la NUC, lo registra en `local_active_jobs` (única fuente de qué ids observar:
     * arkiv-offline no tiene "listame todos los jobs") y programa el [NucDownloadCheckWorker] que
     * avisa por notificación cuando termina.
     *
     * @return `null` si arrancó bien, o el mensaje de error a mostrarle al usuario.
     */
    suspend fun start(
        context: Context,
        api: ArkivOfflineApi,
        jobDao: LocalActiveJobDao,
        seriesId: String,
        showTitle: String,
        posterUrl: String,
        items: List<NucDownloadItem>,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val jobId = api.createJob(seriesId, showTitle, posterUrl, items) ?: return START_ERROR
        // seriesId se guarda con el job (no solo el id): cuando el job termina, la pantalla de
        // Descargas necesita saber de qué serie era para traerse su biblioteca ya bajada y poder
        // mostrarla en "Terminados" sin depender de que alguien visite el detalle de la serie.
        jobDao.insert(LocalActiveJobEntity(jobId, nowMs, seriesId))
        NucDownloadCheckWorker.schedule(context, jobId)
        return null
    }

    /**
     * Trae `GET /library` de una serie y la vuelca en la caché local `nuc_library_items`.
     *
     * [replace] = true (abrir el detalle de una serie): la respuesta es la verdad completa de esa
     * serie, así que se borra lo local y se reescribe -- también refleja borrados hechos desde otro
     * dispositivo. false (un job que acaba de terminar): solo se agrega, sin borrar nada.
     *
     * Si la consulta FALLA ([ArkivOfflineApi.library] devuelve null) no se toca nada: la caché
     * vieja es mucho más útil que una caché vacía, y "falló la red" no es "no hay nada bajado".
     *
     * @return true si se aplicó (hubo respuesta real), false si la consulta falló.
     */
    suspend fun refreshLibraryCache(
        api: ArkivOfflineApi,
        libraryDao: NucLibraryItemDao,
        seriesId: String,
        replace: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val entries = api.library(seriesId) ?: return false
        val items = entries.map {
            NucLibraryItemEntity(it.itemId, seriesId, it.season, it.episode, "done", it.sizeBytes, nowMs)
        }
        if (replace) libraryDao.clearForSeries(seriesId)
        libraryDao.upsertAll(items)
        return true
    }
}
