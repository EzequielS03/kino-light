package com.arkiv.player.data

import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.miniaturas.DestructorDeFrames

/**
 * Borra la biblioteca local. Se usa en logout: la nueva identidad anónima arranca en blanco.
 *
 * Hasta Task 5 esto también reseteaba los cursores de `cloudsync` (borrado en esa poda: sin
 * servidor propio no hay nada que sincronizar) -- lo único que queda es limpiar Room.
 */
class LibraryWiper(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
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
    }
}
