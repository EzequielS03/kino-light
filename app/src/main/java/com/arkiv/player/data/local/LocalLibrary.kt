package com.arkiv.player.data.local

import com.arkiv.player.data.db.ArkivDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lo único que el reproductor consulta para saber si un episodio está guardado en el dispositivo.
 *
 * Verifica que el archivo EXISTA de verdad, no solo que la fila diga `completed`: si el usuario lo
 * borró desde los ajustes de Android, sin esta comprobación el player apuntaría a un archivo
 * fantasma y VLC mostraría pantalla negra sin explicación.
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
