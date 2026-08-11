package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao

/**
 * Destruye el frame (archivo + fila) de un capítulo que quedó visto.
 *
 * Un capítulo puede quedar visto por más de un camino: el toggle manual y el automático por
 * progreso ([com.arkiv.player.data.ArkivRepository]), y también por sync — LAN
 * ([com.arkiv.player.data.ArkivRepository.mergeFromSync]) o nube
 * ([com.arkiv.player.cloudsync.CloudSyncManager]) — cuando OTRO dispositivo ya lo vio y el
 * progreso llega acá con `watched = true`. Todos tienen que terminar en el mismo sitio, así que
 * esta clase es el único lugar que sabe borrar un frame: se instancia una sola vez en `AppGraph` y
 * se comparte entre quien la necesite, en vez de que cada camino repita las mismas dos líneas.
 *
 * `almacen` es nullable con default null por el mismo motivo que en `ArkivRepository`: no romper
 * call sites que arman estas clases sin almacén configurado (ahí el borrado de archivo
 * simplemente no aplica; la fila de Room se borra igual).
 */
class DestructorDeFrames(
    private val almacen: AlmacenDeFrames? = null,
    private val dao: EpisodeFrameDao,
) {
    /**
     * Se puede llamar incondicionalmente, sin preguntar antes si el frame existe: tanto
     * `AlmacenDeFrames.borrar` (usa `File.delete()`, no lanza si no hay archivo) como
     * `EpisodeFrameDao.borrar` (un `DELETE` que no falla si no hay fila) son seguros de invocar
     * de más.
     */
    suspend fun destruir(episodeId: String) {
        almacen?.borrar(episodeId)
        dao.borrar(episodeId)
    }
}
