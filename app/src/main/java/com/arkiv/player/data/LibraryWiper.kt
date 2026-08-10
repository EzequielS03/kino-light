package com.arkiv.player.data

import com.arkiv.player.cloudsync.SyncCursors
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao

/**
 * Borra la biblioteca local sincronizable + resetea cursores. Se usa en logout: la nueva identidad
 * anónima arranca en blanco y no re-empuja los datos de la persona (que quedan a salvo en el server).
 */
class LibraryWiper(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val cursors: SyncCursors,
) {
    suspend fun wipe() {
        itemDao.deleteAllEpisodes()
        itemDao.deleteAllItems()
        playbackDao.deleteAllPlayback()
        skipMarkerDao.deleteAllMarkers()
        cursors.resetAll(listOf("library_items", "episodes", "progress", "markers"))
    }
}
