package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.RecomendacionDao
import com.arkiv.player.data.db.RecomendacionEntity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake en memoria de [RecomendacionDao], para probar [mergeRecomendacion] de verdad sin construir
 * un [CloudSyncManager] completo (ver el KDoc de esa función para el porqué). Mismo patrón que
 * `FakeEpisodeFrameDao` en `miniaturas`: cada método copia la semántica de su equivalente real.
 */
class FakeRecomendacionDao : RecomendacionDao {
    val filas: MutableMap<String, RecomendacionEntity> = mutableMapOf()

    override suspend fun upsert(r: RecomendacionEntity) {
        filas[r.id] = r
    }

    override suspend fun get(id: String): RecomendacionEntity? = filas[id]

    override fun observeVigentes() =
        MutableStateFlow(filas.values.filter { !it.deleted }.sortedBy { it.orden })
}
