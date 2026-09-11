package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake en memoria de [EpisodeFrameDao], compartido por los tests de frames.
 *
 * Cada método copia la semántica de su `@Query` real.
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

    override fun observeForItem(itemId: String) = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override fun observeTodos() = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override suspend fun borrarTodo() {
        filas.clear()
    }
}
