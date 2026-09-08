package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity

/**
 * Destruye el frame (archivo + fila) de un capítulo que quedó visto.
 *
 * Un capítulo puede quedar visto por el toggle manual o el automático por progreso
 * ([com.arkiv.player.data.ArkivRepository]). Hasta Task 5 también podía llegar por sync -LAN o
 * nube (`ArkivRepository.mergeFromSync`/`CloudSyncManager`, borrados en esa poda)- cuando OTRO
 * dispositivo ya lo había visto; sin cloud sync ese camino ya no existe, pero esta clase sigue
 * siendo el único lugar que sabe borrar un frame: se instancia una sola vez en `AppGraph` y se
 * comparte entre quien la necesite, en vez de que cada camino repita las mismas dos líneas.
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
     * Se puede (y hay que poder) llamar de más sin preguntar antes si el frame existe:
     * `savePlayback` la dispara en CADA tick del reproductor (cada ~5 s, mientras `watched` siga
     * en `true`) sin ninguna guarda propia, así que esto se repite muchas veces por capítulo.
     *
     * `AlmacenDeFrames.borrar` (usa `File.delete()`, no lanza si no hay archivo) siempre fue
     * segura de invocar de más, y sigue corriendo en cada llamada. La FILA ya NO: escribir el
     * tombstone en cada llamada pisaría `updatedAt` con el reloj local en cada tick, y como el
     * loop de push mira `updatedAt > cursor` para decidir qué empujar, eso mandaría la misma fila
     * a PocketBase cada ~5 s sostenidos durante todo el resto del capítulo — nada de esto es un
     * caso borde, es el camino más común (ver más abajo). Por eso se lee la fila primero
     * ([EpisodeFrameDao.getIncluyendoBorradas], que ve también los tombstones) y si YA es
     * tombstone (`deleted == 1`) no se toca: el sello (`upsert` con `updatedAt` nuevo) pasa UNA
     * sola vez, la que hace la transición de vivo a borrado.
     *
     * El archivo se borra de verdad, pero la FILA no desaparece: se deja tombstone (`deleted = 1`,
     * `updatedAt` nuevo) en vez de un `DELETE`, para que el borrado viaje por el sync — una fila
     * que desaparece de Room no tiene nada que empujar a PocketBase ni forma de ganarle el LWW a
     * una copia remota vieja. `positionMs`/`capturedAt` quedan en 0 y `remoteUrl` en null a
     * propósito: una vez borrado el frame esos campos no significan nada (nadie los lee de una
     * fila con `deleted = 1`) y guardar los valores previos exigiría leer la fila antes de
     * pisarla, para un dato que no se usa.
     */
    suspend fun destruir(episodeId: String) {
        // Se borra el archivo SIEMPRE, incluso si la fila ya es tombstone: puede haber quedado un
        // JPEG huérfano (p. ej. una captura que corrió justo antes de que el tombstone llegara por
        // sync desde otro dispositivo).
        almacen?.borrar(episodeId)
        val actual = dao.getIncluyendoBorradas(episodeId)
        if (actual?.deleted == 1) return // ya sellado: no reescribir updatedAt de nuevo
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
     * Borra SOLO el archivo, sin tocar la fila de Room.
     *
     * Único uso pensado: `CloudSyncManager.mergeFrame` cuando un tombstone remoto gana el LWW. Ahí
     * la fila YA quedó escrita por el `upsert` de la fila remota, con el `updatedAt` que trajo el
     * servidor — llamar a [destruir] encima la volvería a pisar con el reloj LOCAL, inflando el
     * timestamp del borrado muy por encima del real (con el riesgo de perder, contra ese
     * timestamp inflado, una actualización legítima de un tercer dispositivo que todavía no
     * llegó) y generando un push de eco extra. Lo único que falta hacer ahí es lo que ese `upsert`
     * no hace: borrar el JPEG viejo del disco.
     */
    suspend fun borrarArchivo(episodeId: String) {
        almacen?.borrar(episodeId)
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
