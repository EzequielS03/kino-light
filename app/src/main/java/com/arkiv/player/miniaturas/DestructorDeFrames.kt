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
     * Can (and needs to) be called more than necessary without checking first whether the frame
     * exists: `savePlayback` fires it on EVERY player tick (~5 s, as long as `watched` stays
     * `true`) with no guard of its own, so this repeats many times per chapter.
     *
     * `AlmacenDeFrames.borrar` (uses `File.delete()`, doesn't throw if there's no file) was always
     * safe to call redundantly, and still runs on every call. The ROW is not: writing the tombstone
     * on every call would stamp `updatedAt` with the local clock on every tick, and this row lives
     * in `episode_frame`, the table that triggers [EpisodeFrameDao.observeTodos] -the only Flow
     * that notices a new frame for "Continue watching" (see its own KDoc)-, so rewriting it every
     * ~5 sustained seconds for the rest of the chapter would needlessly invalidate that home row --
     * none of this is an edge case, it's the most common path (see below). (Until Task 5 this would
     * also have re-queued the row for the push to PocketBase; that push -and PocketBase itself-
     * were removed entirely in that pruning, so it no longer applies, but the reason not to
     * over-write still stands because of the Flow invalidation.) That's why the row is read first
     * ([EpisodeFrameDao.getIncluyendoBorradas], which also sees tombstones), and if it's ALREADY a
     * tombstone (`deleted == 1`) it's left alone: the seal (`upsert` with a fresh `updatedAt`)
     * happens exactly ONCE, the one that makes the live-to-deleted transition.
     *
     * The file really is deleted, but the ROW doesn't disappear: a tombstone is left (`deleted = 1`,
     * fresh `updatedAt`) instead of a `DELETE` -until Task 5 that was so the deletion would travel
     * through sync; without cloud sync there's nobody left to tell, but the tombstone is kept
     * anyway because it's still the signal [EpisodeFrameDao.getIncluyendoBorradas] uses to avoid
     * over-writing (see above)-. `positionMs`/`capturedAt` stay at 0 and `remoteUrl` at null on
     * purpose: once the frame is deleted those fields mean nothing (nobody reads them off a row
     * with `deleted = 1`), and keeping the previous values would require reading the row before
     * overwriting it, for data nothing uses.
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
     * El mismo borrado pero de TODO. Lo usaba `LibraryWiper` para el wipe de logout -la identidad
     * nueva se quedaba, si no, con los JPEG de las escenas que miró la persona anterior-;
     * `LibraryWiper` se borró entero en la Task 9 (sub-proyecto 2B) junto con el resto de cuentas, y
     * hoy este método no tiene llamador de producción (solo su test). Se deja porque documenta el
     * único borrado físico -sin tombstone- que existe en esta clase, por si vuelve a hacer falta un
     * wipe completo.
     *
     * A diferencia de [destruir], acá SÍ es un `DELETE` físico (`dao.borrarTodo`) y NO deja
     * tombstones: sin cloud sync en esta rama (ver el KDoc de la clase) ya no hay a quién avisarle
     * del borrado, así que no hace falta dejar rastro.
     *
     * No es un `forEach` de [destruir] a propósito: el wipe borra `items` y `episodes` en el mismo
     * barrido, y tanto el directorio como la tabla se vacían de una sola pasada.
     */
    suspend fun destruirTodo() {
        almacen?.borrarTodo()
        dao.borrarTodo()
    }
}
