package com.arkiv.player.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.MetadataParser
import com.arkiv.player.data.Quality
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.DownloadEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Motor de descargas basado en el DownloadManager del sistema (cola,
 * reanudación y notificaciones nativas). El progreso se consulta bajo demanda
 * y se refleja en Room.
 */
class Downloader(
    context: Context,
    private val db: ArkivDatabase,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloadDao = db.downloadDao()
    private val prefs = appContext.getSharedPreferences("arkiv_downloads", Context.MODE_PRIVATE)

    /** Elige la variante a descargar según el ajuste de calidad. */
    private fun variantFor(episode: Episode): Pair<VideoVariant, String>? {
        return when (settings.downloadQuality.value) {
            Quality.DERIVATIVE -> episode.derivative?.let { it to "derivative" }
                ?: episode.original?.let { it to "original" }
            Quality.ORIGINAL -> episode.original?.let { it to "original" }
                ?: episode.derivative?.let { it to "derivative" }
        }
    }

    suspend fun enqueue(episode: Episode) = withContext(Dispatchers.IO) {
        Log.d(TAG, "enqueue() start id=${episode.id}")
        val existing = downloadDao.get(episode.id)
        Log.d(TAG, "enqueue() existing=$existing (state=${existing?.state})")
        if (existing != null && existing.state != "failed") {
            Log.w(TAG, "enqueue() SALIDA TEMPRANA: ya hay un registro con state=${existing.state} para id=${episode.id}")
            return@withContext
        }

        val variantResult = variantFor(episode)
        Log.d(
            TAG,
            "enqueue() quality=${settings.downloadQuality.value} original=${episode.original != null} " +
                "derivative=${episode.derivative != null} -> variantFor=$variantResult",
        )
        val (variant, kind) = variantResult ?: run {
            Log.w(TAG, "enqueue() SALIDA TEMPRANA: variantFor()==null (sin original ni derivative) para id=${episode.id}")
            return@withContext
        }
        val url = ArchiveUrls.download(episode.itemId, variant.path)
        val ext = MetadataParser.extensionOf(variant.path).ifEmpty { "mp4" }
        val fileName = "${sanitize(episode.id)}.$ext"
        Log.d(TAG, "enqueue() url=$url fileName=$fileName kind=$kind")

        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(episode.displayName)
                .setDescription("Arkiv")
                .addRequestHeader("User-Agent", "Arkiv/0.1 (personal)")
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_MOVIES, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

            val dmId = dm.enqueue(request)
            Log.d(TAG, "enqueue() dm.enqueue() OK dmId=$dmId para id=${episode.id}")
            prefs.edit()
                .putLong("ep_${episode.id}", dmId)
                .putString("dm_$dmId", episode.id)
                .apply()

            downloadDao.upsert(
                DownloadEntity(
                    episodeId = episode.id,
                    variant = kind,
                    state = "downloading",
                    progress = 0f,
                    localUri = null,
                    bytes = variant.sizeBytes,
                )
            )
            Log.d(TAG, "enqueue() upsert en Room OK para id=${episode.id}")
        } catch (e: Exception) {
            Log.e(TAG, "enqueue() EXCEPCIÓN llamando a DownloadManager para id=${episode.id} url=$url", e)
            throw e
        }
    }

    /** Consulta el DownloadManager y actualiza el estado/progreso en Room. */
    suspend fun refreshProgress() = withContext(Dispatchers.IO) {
        val downloads = downloadDao.getAll()
        for (d in downloads) {
            if (d.state == "completed") continue
            val dmId = prefs.getLong("ep_${d.episodeId}", -1L)
            if (dmId < 0) continue
            queryOne(dmId)?.let { info ->
                downloadDao.updateProgress(d.episodeId, info.state, info.progress, info.localUri)
            }
        }
    }

    private data class DmInfo(val state: String, val progress: Float, val localUri: String?)

    private fun queryOne(dmId: Long): DmInfo? {
        dm.query(DownloadManager.Query().setFilterById(dmId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DmInfo("completed", 1f, localUri)
                DownloadManager.STATUS_FAILED -> DmInfo("failed", 0f, null)
                else -> {
                    val p = if (total > 0) downloaded.toFloat() / total else 0f
                    DmInfo("downloading", p, null)
                }
            }
        }
    }

    suspend fun remove(episodeId: String) = withContext(Dispatchers.IO) {
        val dmId = prefs.getLong("ep_$episodeId", -1L)
        if (dmId >= 0) {
            dm.remove(dmId)
            prefs.edit().remove("ep_$episodeId").remove("dm_$dmId").apply()
        }
        downloadDao.delete(episodeId)
    }

    /** URI local si el episodio está descargado por completo, o null. */
    suspend fun completedUri(episodeId: String): String? = withContext(Dispatchers.IO) {
        downloadDao.get(episodeId)?.takeIf { it.state == "completed" }?.localUri
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TAG = "ArkivDownload"
    }
}
