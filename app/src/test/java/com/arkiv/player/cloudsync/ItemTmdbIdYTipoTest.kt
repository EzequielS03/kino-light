package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.ItemEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `library_items` (PocketBase) ganó `tmdbId` y `tipo` para poder saber con exactitud si ya viste
 * algo, en vez de comparar por título (difuso). Antes `itemToFields` ni los mandaba -nacían
 * vacíos en el servidor aunque Room ya supiera el `tmdbId` de un ítem de Magis o de archive.org- y
 * `recordToItem` tampoco los leía de vuelta, así que ni push ni pull los movían.
 */
class ItemTmdbIdYTipoTest {

    private fun item(tmdbId: Int?, tipo: String?) = ItemEntity(
        identifier = "magis:ABC",
        title = "Dragon Ball Daima",
        description = null,
        thumbnailUrl = "poster.jpg",
        addedAt = 1L,
        source = "magis",
        tmdbId = tmdbId,
        tipo = tipo,
    )

    @Test fun manda_tmdbId_y_tipo_cuando_se_conocen() {
        val f = itemToFields(item(tmdbId = 12609, tipo = "tv"), "cuenta1")
        assertEquals(12609, f["tmdbId"])
        assertEquals("tv", f["tipo"])
    }

    @Test fun no_inventa_nada_cuando_no_se_conocen() {
        // Un torrent agregado por hash, o una fuente web: ninguna de las dos sabe su tmdbId ni su
        // tipo con certeza -- mejor un hueco vacío que un dato inventado (ver el KDoc de
        // ItemEntity.tipo).
        val f = itemToFields(item(tmdbId = null, tipo = null), "cuenta1")
        assertNull(f["tmdbId"])
        assertNull(f["tipo"])
    }

    @Test fun recordToItem_lee_tmdbId_y_tipo_de_vuelta() {
        val json = JSONObject()
            .put("identifier", "magis:ABC")
            .put("title", "Dragon Ball Daima")
            .put("addedAt", 1L)
            .put("tmdbId", 12609)
            .put("tipo", "tv")
        val entity = recordToItem(json)
        assertEquals(12609, entity.tmdbId)
        assertEquals("tv", entity.tipo)
    }

    @Test fun un_tmdbId_en_0_en_el_record_cuenta_como_ausente() {
        // El campo numérico de PocketBase nace en 0 en las filas que todavía no lo tienen (mismo
        // criterio que MagisEntities.refParaReparar): sin este filtro, un ítem sin tmdbId llegaría
        // por sync con un 0 que en Room se lee como "sí tiene, apunta a nada".
        val json = JSONObject()
            .put("identifier", "torrent:abc")
            .put("title", "X")
            .put("addedAt", 1L)
            .put("tmdbId", 0)
        assertNull(recordToItem(json).tmdbId)
    }

    @Test fun sin_los_campos_en_el_record_queda_null_y_no_0() {
        // Filas viejas, subidas antes de que estos dos campos existieran en el servidor.
        val json = JSONObject()
            .put("identifier", "torrent:abc")
            .put("title", "X")
            .put("addedAt", 1L)
        val entity = recordToItem(json)
        assertNull(entity.tmdbId)
        assertNull(entity.tipo)
    }
}
