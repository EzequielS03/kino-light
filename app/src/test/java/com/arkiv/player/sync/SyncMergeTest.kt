package com.arkiv.player.sync

import com.arkiv.player.data.db.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regla del sync entre celu y TV.
 *
 * Antes era un **espejo one-way**: el TV adoptaba la biblioteca del celu y **borraba en duro** todo
 * ítem que el celu no tuviera. O sea que lo que agregabas EN EL TV (buscar, reproducir, guardar una
 * temporada) desaparecía solo en el próximo sync, sin aviso. Estos tests fijan la regla nueva, que
 * es la misma que ya usa el sync por nube (`cloudsync.CloudSyncManager`): **last-write-wins por
 * `updatedAt`, y los borrados viajan como tombstone**, no como ausencia.
 *
 * La diferencia clave: "no está en el snapshot del otro" ya NO significa "borralo". Significa que
 * el otro todavía no se enteró.
 */
class SyncMergeTest {

    private fun item(id: String, updatedAt: Long, deleted: Boolean = false, title: String = "T") =
        ItemEntity(
            identifier = id, title = title, description = null, thumbnailUrl = "",
            addedAt = 0L, source = "magis", updatedAt = updatedAt, deleted = deleted,
        )

    private fun aplicar(locales: List<ItemEntity>, remotas: List<ItemEntity>) =
        SyncMerge.aAplicar(locales, remotas, { it.identifier }, { it.updatedAt })

    @Test fun una_fila_que_el_otro_no_tiene_se_agrega() {
        val cambios = aplicar(locales = emptyList(), remotas = listOf(item("a", updatedAt = 10)))
        assertEquals(listOf("a"), cambios.map { it.identifier })
    }

    @Test fun lo_que_agregue_en_el_TV_no_se_borra_por_no_estar_en_el_celu() {
        // EL bug: el celu no conoce "daima", y eso alcanzaba para que el TV la borrara en duro.
        val cambios = aplicar(locales = listOf(item("daima", updatedAt = 99)), remotas = emptyList())
        assertTrue("nada que tocar: la fila local se queda", cambios.isEmpty())
    }

    @Test fun gana_la_version_mas_nueva() {
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 10, title = "viejo")),
            remotas = listOf(item("a", updatedAt = 20, title = "nuevo")),
        )
        assertEquals(listOf("nuevo"), cambios.map { it.title })
    }

    @Test fun no_gana_la_version_mas_vieja() {
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 20, title = "nuevo")),
            remotas = listOf(item("a", updatedAt = 10, title = "viejo")),
        )
        assertTrue("lo local es más nuevo: no se pisa", cambios.isEmpty())
    }

    @Test fun en_empate_no_se_escribe() {
        // Sin esto, cada sync reescribiría toda la biblioteca y los triggers subirían `updatedAt`,
        // dejando las dos puntas peleando para siempre por filas idénticas.
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 10)),
            remotas = listOf(item("a", updatedAt = 10)),
        )
        assertTrue(cambios.isEmpty())
    }

    @Test fun un_borrado_viaja_como_tombstone_y_gana_si_es_mas_nuevo() {
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 10)),
            remotas = listOf(item("a", updatedAt = 20, deleted = true)),
        )
        assertEquals(listOf(true), cambios.map { it.deleted })
    }

    @Test fun un_tombstone_viejo_no_resucita_ni_mata_una_fila_reagregada() {
        // Borré "a" en el celu y después la volví a agregar en el TV: manda la re-agregada.
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 30)),
            remotas = listOf(item("a", updatedAt = 20, deleted = true)),
        )
        assertTrue(cambios.isEmpty())
    }

    @Test fun una_fila_sin_reloj_no_pisa_a_una_que_si_lo_tiene() {
        // Compatibilidad: una punta con la app vieja manda `updatedAt = 0`. Que no propague es
        // aceptable; que borre lo nuestro con datos en blanco, no.
        val cambios = aplicar(
            locales = listOf(item("a", updatedAt = 5, title = "bueno")),
            remotas = listOf(item("a", updatedAt = 0, title = "sin reloj")),
        )
        assertTrue(cambios.isEmpty())
    }

    @Test fun el_merge_es_simetrico() {
        // Las dos puntas corren exactamente el mismo código: no hay "emisor" ni "receptor". Cada
        // una se trae lo que le falta y lo que el otro tiene más fresco, y conserva lo propio.
        val celu = listOf(item("a", updatedAt = 10), item("b", updatedAt = 40))
        val tv = listOf(item("a", updatedAt = 30), item("c", updatedAt = 50))
        // El TV se trae "b" (no la tenía) y conserva su "a", que es más nueva.
        assertEquals(listOf("b"), aplicar(locales = tv, remotas = celu).map { it.identifier })
        // El celu se trae la "a" más nueva del TV y la "c" que no conocía.
        assertEquals(listOf("a", "c"), aplicar(locales = celu, remotas = tv).map { it.identifier })
    }
}
