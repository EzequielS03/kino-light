package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `live_favorites`/`live_recents` viajaban SOLO por el sync LAN (sync/SyncSnapshot.kt): el celu y
 * el TV solo se enteraban de un favorito o un canal visto si estaban en la misma red en ese
 * momento. Estos mappers son la mitad que faltaba para que viajen por PocketBase igual que
 * `markers` -- mismo patrón: LWW por `updatedAt`, `live_favorites` con tombstone (`deleted`),
 * `live_recents` sin él (se poda por antigüedad, no se borra a mano).
 */
class LiveSyncMappersTest {

    private fun fav(
        code: String = "c1", nombre: String = "ESPN", numero: Int = 501,
        logo: String? = "https://x/logo.png", updatedAt: Long = 100, deleted: Boolean = false,
    ) = LiveFavoriteEntity(code, nombre, numero, logo, updatedAt, deleted)

    private fun recent(
        code: String = "c1", nombre: String = "ESPN", vistoAt: Long = 555, updatedAt: Long = 100,
    ) = LiveRecentEntity(code, nombre, vistoAt, updatedAt)

    // ---- live_favorites ----

    @Test fun favorito_ida_y_vuelta_conserva_todos_los_campos() {
        val f = liveFavoriteToFields(fav(), "cuenta1")
        val recibido = recordToLiveFavorite(JSONObject(f))
        assertEquals("c1", recibido.code)
        assertEquals("ESPN", recibido.nombre)
        assertEquals(501, recibido.numero)
        assertEquals("https://x/logo.png", recibido.logo)
        assertEquals(100L, recibido.updatedAt)
        assertFalse(recibido.deleted)
    }

    @Test fun favorito_lleva_accountId_para_el_aislamiento_por_cuenta() {
        val f = liveFavoriteToFields(fav(), "cuenta1")
        assertEquals("cuenta1", f["accountId"])
    }

    @Test fun favorito_sin_logo_viaja_null_y_vuelve_null() {
        val recibido = recordToLiveFavorite(JSONObject(liveFavoriteToFields(fav(logo = null), "cuenta1")))
        assertNull(recibido.logo)
    }

    @Test fun un_borrado_de_favorito_viaja_como_tombstone_con_lo_que_lo_identifica() {
        // Igual que markers/items: el borrado no es la ausencia de la fila, es `deleted=true` con
        // el mismo reloj que cualquier otra escritura -- así el otro dispositivo lo puede propagar.
        val f = liveFavoriteToFields(fav(deleted = true, updatedAt = 999), "cuenta1")
        assertEquals(true, f["deleted"])
        assertEquals("c1", f["code"])
        assertEquals(999L, f["updatedAt"])
        val recibido = recordToLiveFavorite(JSONObject(f))
        assertTrue("un borrado más nuevo tiene que llegar borrado", recibido.deleted)
    }

    // ---- live_recents ----

    @Test fun reciente_ida_y_vuelta_conserva_todos_los_campos() {
        val r = liveRecentToFields(recent(), "cuenta1")
        val recibido = recordToLiveRecent(JSONObject(r))
        assertEquals("c1", recibido.code)
        assertEquals("ESPN", recibido.nombre)
        assertEquals(555L, recibido.vistoAt)
        assertEquals(100L, recibido.updatedAt)
    }

    @Test fun reciente_no_manda_tombstone() {
        // `live_recents` no tiene columna `deleted` (LiveRecentEntity no la declara): no lleva
        // tombstone a propósito, se poda por antigüedad. Si esto algún día manda "deleted", algo se
        // rompió en el mapeo -- por eso el assert explícito en vez de solo omitir el campo.
        val r = liveRecentToFields(recent(), "cuenta1")
        assertFalse("live_recents no debería mandar un campo deleted", r.containsKey("deleted"))
    }

    @Test fun reciente_lleva_accountId_para_el_aislamiento_por_cuenta() {
        val r = liveRecentToFields(recent(), "cuenta1")
        assertEquals("cuenta1", r["accountId"])
    }
}
