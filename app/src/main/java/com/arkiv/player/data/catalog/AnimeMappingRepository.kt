package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Dataset de mapeo de anime (Fribb/anime-lists). Se descarga a disco (TTL semanal) y se parsea a
 * un mapa en memoria bajo demanda. Si el refresh falla, se sirve lo cacheado; si no hay nada,
 * devuelve null (el resolver degrada a heurístico).
 */
class AnimeMappingRepository(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val file get() = File(cacheDir, CACHE_FILE_NAME)
    private val tmpFile get() = File(cacheDir, "$CACHE_FILE_NAME.tmp")
    private val mutex = Mutex()
    @Volatile private var cache: Map<Long, AnimeMapping>? = null
    @Volatile private var lastAttemptMs = 0L

    suspend fun mappingFor(anilistId: Long): AnimeMapping? = ensureLoaded()[anilistId]

    private suspend fun ensureLoaded(): Map<Long, AnimeMapping> = mutex.withLock {
        cache?.let { return it }
        withContext(Dispatchers.IO) {
            val existing = readExisting()
            val existingValid = existing.isNotEmpty()
            // Un archivo que existe pero parsea vacío (corrupto/truncado) NO cuenta como fresco:
            // forzamos re-descarga, siempre respetando el gate anti-martilleo de abajo.
            val fresh = existingValid && isFresh(file.lastModified(), System.currentTimeMillis())

            val parsed = if (fresh) {
                existing
            } else {
                val now = System.currentTimeMillis()
                val gated = !existingValid && (now - lastAttemptMs < RETRY_GATE_MS)
                if (gated) {
                    existing   // best-effort; degrada a heurístico sin martillar la red
                } else {
                    lastAttemptMs = now
                    download() ?: existing
                }
            }
            parsed.also { if (it.isNotEmpty()) cache = it }
        }
    }

    private fun readExisting(): Map<Long, AnimeMapping> =
        runCatching { if (file.exists()) file.readText() else null }
            .getOrNull()
            ?.let { FribbAnimeListParser.parse(it) }
            .orEmpty()

    /**
     * Descarga a un archivo temporal, valida que parsee a un mapa no vacío y solo entonces lo
     * promueve atómicamente al archivo final. Si algo falla o el resultado es inválido/vacío, NO
     * toca el archivo bueno existente (ni su lastModified) y devuelve null.
     */
    private fun download(): Map<Long, AnimeMapping>? {
        val tmp = tmpFile
        val parsed = runCatching {
            val body = client.newCall(Request.Builder().url(DATASET_URL).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            cacheDir.mkdirs()
            tmp.writeText(body)
            FribbAnimeListParser.parse(tmp.readText()).takeIf { it.isNotEmpty() }
        }.getOrNull()

        if (parsed == null) {
            runCatching { tmp.delete() }
            return null
        }

        val renamed = runCatching { tmp.renameTo(file) }.getOrDefault(false)
        if (!renamed) {
            runCatching {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                file.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
        return parsed
    }

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json"
        private const val CACHE_FILE_NAME = "anime-list-full.json"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Gate anti-martilleo: si no hay archivo válido, no reintentar descarga más de 1 vez cada 5 min. */
        private const val RETRY_GATE_MS = 5L * 60 * 1000

        /** ¿El cache descargado en [fetchedAtMs] sigue vigente en [nowMs]? (0 = sin descarga). */
        fun isFresh(fetchedAtMs: Long, nowMs: Long): Boolean =
            fetchedAtMs > 0 && nowMs - fetchedAtMs < TTL_MS
    }
}
