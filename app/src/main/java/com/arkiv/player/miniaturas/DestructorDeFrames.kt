package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity

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
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Se puede llamar incondicionalmente, sin preguntar antes si el frame existe: tanto
     * `AlmacenDeFrames.borrar` (usa `File.delete()`, no lanza si no hay archivo) como el `upsert`
     * de más abajo (crea la fila si no existía) son seguros de invocar de más.
     *
     * El archivo se borra de verdad, pero la FILA no: se deja tombstone (`deleted = 1`,
     * `updatedAt` nuevo) en vez de un `DELETE`, para que el borrado viaje por el sync — una fila
     * que desaparece de Room no tiene nada que empujar a PocketBase ni forma de ganarle el LWW a
     * una copia remota vieja. `positionMs`/`capturedAt` quedan en 0 y `remoteUrl` en null a
     * propósito: una vez borrado el frame esos campos no significan nada (nadie los lee de una
     * fila con `deleted = 1`) y guardar los valores previos exigiría leer la fila antes de
     * pisarla, para un dato que no se usa.
     */
    suspend fun destruir(episodeId: String) {
        almacen?.borrar(episodeId)
        dao.upsert(
            EpisodeFrameEntity(
                episodeId = episodeId,
                positionMs = 0,
                capturedAt = 0,
                updatedAt = ahora(),
                deleted = 1,
                remoteUrl = null,
            )
        )
    }

    /**
     * El mismo borrado pero de TODO, para el wipe de logout
     * ([com.arkiv.player.data.LibraryWiper]): sin esto, la identidad nueva se queda con los JPEG de
     * las escenas que miró la persona anterior (además del disco, es un tema de privacidad).
     *
     * A diferencia de [destruir], acá SÍ es un `DELETE` físico (`dao.borrarTodo`) y NO deja
     * tombstones: el wipe es "esta identidad se va de ESTE aparato", no "borrá esto en todos
     * lados". Si dejara tombstones, cerrar sesión en un dispositivo borraría —al viajar por
     * sync— los frames de la cuenta en los demás, que ni se enteraron del logout.
     *
     * No es un `forEach` de [destruir] a propósito: en ese momento no hay una lista de capítulos a
     * mano —el wipe borra `items` y `episodes` en el mismo barrido— y tanto el directorio como la
     * tabla se vacían de una sola pasada.
     */
    suspend fun destruirTodo() {
        almacen?.borrarTodo()
        dao.borrarTodo()
    }
}
