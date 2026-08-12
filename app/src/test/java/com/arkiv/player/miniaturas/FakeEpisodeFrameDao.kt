package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake en memoria de [EpisodeFrameDao], compartido por los tests de frames.
 *
 * Cada método copia la semántica de su `@Query` real —sobre todo [marcarBajado], que es un UPDATE
 * CONDICIONAL: si la fila cambió (otro `updatedAt`) o se volvió tombstone, no toca nada y devuelve
 * 0. Los tests del bajador dependen de eso.
 */
class FakeEpisodeFrameDao : EpisodeFrameDao {
    /**
     * Concurrente porque `BajadorDeFramesTest` escribe filas desde el hilo del MockWebServer
     * (simula un borrado que cae JUSTO a mitad de una bajada) mientras el bajador lee desde el
     * suyo.
     */
    val filas: MutableMap<String, EpisodeFrameEntity> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun upsert(frame: EpisodeFrameEntity) {
        filas[frame.episodeId] = frame
    }

    override suspend fun get(episodeId: String): EpisodeFrameEntity? =
        filas[episodeId]?.takeIf { it.deleted == 0 }

    override suspend fun getIncluyendoBorradas(episodeId: String): EpisodeFrameEntity? = filas[episodeId]

    override suspend fun getFramesSince(cursor: Long): List<EpisodeFrameEntity> =
        filas.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }

    /** Ordenada por `episodeId` (el `@Query` real no promete orden) para que los tests sean estables. */
    override suspend fun pendientesDeBajar(episodeIds: Collection<String>): List<EpisodeFrameEntity> =
        filas.values
            .filter { it.deleted == 0 && it.remoteUrl != null && it.episodeId in episodeIds }
            .sortedBy { it.episodeId }

    override suspend fun marcarBajado(episodeId: String, updatedAt: Long): Int {
        val fila = filas[episodeId] ?: return 0
        if (fila.updatedAt != updatedAt || fila.deleted != 0) return 0
        filas[episodeId] = fila.copy(remoteUrl = null)
        return 1
    }

    override fun observeForItem(itemId: String) = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override fun observeTodos() = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override suspend fun borrarTodo() {
        filas.clear()
    }
}
