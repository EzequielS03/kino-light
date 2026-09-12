package com.arkiv.player.data.local

import com.arkiv.player.data.db.ArkivDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only thing the player checks to know if an episode is saved on the device.
 *
 * Verifies the file actually EXISTS, not just that the row says `completed`: if the user deleted
 * it from Android's settings, without this check the player would point at a ghost file and show
 * a black screen with no explanation.
 */
class LocalLibrary(private val db: ArkivDatabase) {

    private val downloadDao = db.downloadDao()

    suspend fun fileFor(episodeId: String): String? = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext null
        if (row.state != LocalDownloadState.COMPLETED) return@withContext null

        // filePath es lo nuevo; localUri es el `file://` que dejaron las descargas hechas con el
        // DownloadManager del sistema antes de la migración 16->17. Las dos siguen valiendo.
        val path = row.filePath ?: row.localUri?.removePrefix("file://") ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            // El archivo se fue: limpiar la fila para que la UI no siga diciendo "listo" y para que
            // el próximo play caiga a streaming en vez de fallar.
            downloadDao.delete(episodeId)
            return@withContext null
        }
        file.absolutePath
    }
}
