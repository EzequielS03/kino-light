package com.arkiv.player.data.marcadores

import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSkipMarkerDao : SkipMarkerDao {
    val filas: MutableMap<String, SkipMarkerEntity> = linkedMapOf()

    override suspend fun upsert(marker: SkipMarkerEntity) {
        filas[marker.id] = marker
    }

    override suspend fun get(itemId: String): SkipMarkerEntity? =
        filas.values.firstOrNull { it.itemId == itemId && it.episodeId == "" }

    override fun observe(itemId: String) =
        MutableStateFlow(filas.values.firstOrNull { it.itemId == itemId && it.episodeId == "" })

    override fun observeDeCapitulo(itemId: String, episodeId: String) =
        MutableStateFlow(
            filas.values.filter {
                it.itemId == itemId && (it.episodeId == episodeId || it.episodeId == "") && !it.deleted
            },
        )

    override suspend fun getById(id: String): SkipMarkerEntity? = filas[id]

    override suspend fun delete(itemId: String) {
        filas.entries.removeAll { it.value.itemId == itemId && it.value.episodeId == "" }
    }

    override suspend fun getAll(): List<SkipMarkerEntity> = filas.values.toList()
}
