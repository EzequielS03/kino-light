package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los packs guardan la lista de archivos del .torrent en `torrentData`: uno real llegó a 633 112
 * caracteres contra un límite de 200 000 en PocketBase, así que el servidor lo rechazaba SIEMPRE.
 * Un borrado no necesita ese payload: el tombstone viaja liviano y siempre entra.
 */
class TombstoneFieldsTest {

    private fun item(deleted: Boolean, torrent: String?) = ItemEntity(
        identifier = "torrent:abc",
        title = "Naruto — Pack",
        description = "una descripción larguísima".repeat(500),
        thumbnailUrl = "https://x/y.jpg",
        addedAt = 1L,
        source = "torrent",
        torrentData = torrent,
        updatedAt = 99L,
        deleted = deleted,
    )

    @Test fun un_borrado_no_manda_el_payload_pesado() {
        val f = itemToFields(item(deleted = true, torrent = "x".repeat(633_112)), "cuenta1")
        assertNull("torrentData debe ir vacío en un tombstone", f["torrentData"])
        assertNull("description debe ir vacía en un tombstone", f["description"])
    }

    @Test fun un_borrado_conserva_lo_que_lo_identifica() {
        val f = itemToFields(item(deleted = true, torrent = "x".repeat(633_112)), "cuenta1")
        assertEquals("torrent:abc", f["identifier"])
        assertEquals("cuenta1", f["accountId"])
        assertEquals(99L, f["updatedAt"])
        assertEquals(true, f["deleted"])
    }

    @Test fun el_tombstone_entra_de_sobra_en_el_limite_del_servidor() {
        val f = itemToFields(item(deleted = true, torrent = "x".repeat(633_112)), "cuenta1")
        val masLargo = f.values.filterIsInstance<String>().maxOf { it.length }
        assertTrue("un tombstone no debería acercarse al límite (era $masLargo)", masLargo < 200_000)
    }

    /** Una fila VIVA sí conserva todo: adelgazarla corrompería el dato en el otro dispositivo. */
    @Test fun una_fila_viva_conserva_su_payload() {
        val f = itemToFields(item(deleted = false, torrent = "abc"), "cuenta1")
        assertEquals("abc", f["torrentData"])
        assertTrue((f["description"] as String).isNotEmpty())
    }
}
