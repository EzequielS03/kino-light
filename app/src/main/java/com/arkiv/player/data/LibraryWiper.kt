package com.arkiv.player.data

import com.arkiv.player.cloudsync.SyncCursors
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.miniaturas.DestructorDeFrames

/**
 * Borra la biblioteca local sincronizable + resetea cursores. Se usa en logout: la nueva identidad
 * anónima arranca en blanco y no re-empuja los datos de la persona (que quedan a salvo en el server).
 */
class LibraryWiper(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val cursors: SyncCursors,
    /**
     * Los frames también se van: son JPEG de escenas de lo que miró la persona que se está yendo, y
     * el único reclamo de disco que existe es "capítulo visto", así que sin esto quedarían en el
     * aparato para siempre. Va sin default a propósito: es parte de lo que esta clase promete
     * ("arranca en blanco"), no un extra opcional que se pueda olvidar en un call site nuevo.
     */
    private val destructorDeFrames: DestructorDeFrames,
) {
    suspend fun wipe() {
        itemDao.deleteAllEpisodes()
        itemDao.deleteAllItems()
        playbackDao.deleteAllPlayback()
        skipMarkerDao.deleteAllMarkers()
        destructorDeFrames.destruirTodo()
        // `episode_frames` va en la lista por lo mismo que las otras cuatro: la identidad nueva
        // arranca en blanco, y un cursor heredado de la anterior la dejaría sin traer los frames que
        // ya estaban en el servidor por debajo de esa marca (y sin volver a empujar los suyos).
        cursors.resetAll(listOf("library_items", "episodes", "progress", "markers", "episode_frames"))
    }
}
