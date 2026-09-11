package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake en memoria de [EpisodeFrameDao], compartido por los tests de frames.
 *
 * Each method copies the semantics of its real `@Query`.
 */
class FakeEpisodeFrameDao : EpisodeFrameDao {
    val filas: MutableMap<String, EpisodeFrameEntity> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun upsert(frame: EpisodeFrameEntity) {
        filas[frame.episodeId] = frame
    }

    override suspend fun get(episodeId: String): EpisodeFrameEntity? =
        filas[episodeId]?.takeIf { it.deleted == 0 }

    override suspend fun getIncluyendoBorradas(episodeId: String): EpisodeFrameEntity? = filas[episodeId]

    override fun observeForItem(itemId: String) = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override fun observeTodos() = MutableStateFlow(emptyList<EpisodeFrameEntity>())

    override suspend fun borrarTodo() {
        filas.clear()
    }
}
