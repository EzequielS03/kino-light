package com.arkiv.player.sync

import com.arkiv.player.data.db.LiveFavoriteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El merge de favoritos de TV en vivo usa la MISMA regla LWW que items/episodes/skip_markers:
 * [SyncMerge.aAplicar]. No hay una función `ganador(a, b)` en producción -- `aAplicar` resuelve
 * sobre listas, no par a par -- así que acá se envuelve en un helper de UNA fila solo para que el
 * assert quede legible como en el brief. El helper no decide nada por su cuenta: delega la
 * decisión (aplicar el remoto o quedarse con el local) al propio [SyncMerge.aAplicar], que es el
 * código de producción. Ver [SyncMergeTest] para la prueba equivalente sobre `items`.
 */
class SyncLiveFavoritesTest {

    private fun ganador(local: LiveFavoriteEntity, remoto: LiveFavoriteEntity): LiveFavoriteEntity {
        val aplicar = SyncMerge.aAplicar(
            locales = listOf(local),
            remotas = listOf(remoto),
            llave = { it.code },
            updatedAt = { it.updatedAt },
        )
        return aplicar.singleOrNull() ?: local
    }

    @Test
    fun `gana la escritura mas nueva`() {
        val local = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 100, deleted = false)
        val remoto = LiveFavoriteEntity("c1", "ESPN HD", 501, null, updatedAt = 200, deleted = false)
        assertEquals("ESPN HD", ganador(local, remoto).nombre)
    }

    @Test
    fun `un borrado mas nuevo no revive por un alta vieja`() {
        val local = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 300, deleted = true)
        val remoto = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 100, deleted = false)
        assertTrue(ganador(local, remoto).deleted)
    }
}
