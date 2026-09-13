package com.arkiv.player.data.local

import com.arkiv.player.data.caracol.CaracolDownload
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
        // Caracol NO pasa por acá. Lo que su descarga deja en `filePath` no es un video sino el
        // registro de la descarga (un JSON: ver `DituDownloadStrategy`), porque sus bytes son
        // segmentos DASH cifrados dentro del caché de media3 y no un archivo reproducible.
        // Entregarlo como si lo fuera manda al reproductor de archivos locales a abrir un JSON:
        // pantalla negra y ningún error que lo explique. Quien sabe abrirlo es
        // `descargaDeCaracol`, y la usa el reproductor de Caracol.
        if (row.source == FUENTE_CARACOL) return@withContext null

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

    /**
     * El registro de un capítulo de Caracol bajado, o `null` si no está en el dispositivo.
     *
     * Devuelve lo que hace falta para ABRIRLO: con qué URL se llenó el caché y qué calidad se bajó.
     * Ver [CaracolDownload], que explica por qué ninguno de los dos se puede adivinar después.
     *
     * Igual que [fileFor], comprueba que lo de disco siga existiendo: si la persona borró los datos
     * de la app por fuera, la fila se limpia y el próximo play cae a streaming en vez de fallar.
     */
    suspend fun descargaDeCaracol(episodeId: String): CaracolDownload? = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext null
        if (row.state != LocalDownloadState.COMPLETED || row.source != FUENTE_CARACOL) return@withContext null
        val path = row.filePath ?: return@withContext null
        val registro = File(path)
        if (!registro.exists()) {
            downloadDao.delete(episodeId)
            return@withContext null
        }
        val datos = runCatching { CaracolDownload.fromJson(registro.readText()) }.getOrNull()
        if (datos == null) {
            // Un registro ilegible es una descarga que no se puede abrir. Se limpia la fila para que
            // la UI deje de prometer algo que no va a funcionar, y se vuelve a poder bajar.
            downloadDao.delete(episodeId)
            return@withContext null
        }
        datos
    }

    private companion object {
        /** El valor de `downloads.source` de Caracol. Ver [com.arkiv.player.data.local.FuenteDeDescarga]. */
        const val FUENTE_CARACOL = "ditu"
    }
}
