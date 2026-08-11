package com.arkiv.player.sync

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que viaja por el cable entre celu y TV.
 *
 * El formato nacía sin `updatedAt` ni `deleted`, y eso solo ya rompía el sync de dos maneras: sin
 * reloj no hay last-write-wins posible (ver [SyncMerge]), y **un tombstone llegaba del otro lado
 * como una fila viva**, o sea que borrar algo en el celu podía resucitarlo desde el TV. Igual de
 * grave: `tmdbId` y `episodiosVistosEnLista` tampoco viajaban, así que cada merge se los borraba a
 * la punta que los recibía.
 */
class SyncSnapshotTest {

    private fun item(
        id: String = "magis:ABC", updatedAt: Long = 777, deleted: Boolean = false,
        vistos: Int? = 4, tmdbId: Int? = 12697,
    ) = ItemEntity(
        identifier = id, title = "Dragon Ball Daima T1", description = null, thumbnailUrl = "p.jpg",
        addedAt = 100L, categoryOverride = "series", source = "magis", torrentData = "ref",
        updatedAt = updatedAt, deleted = deleted, episodiosVistosEnLista = vistos, tmdbId = tmdbId,
    )

    private fun episode(id: String = "magis:ABC::e1", updatedAt: Long = 888, deleted: Boolean = false) =
        EpisodeEntity(
            id = id, itemId = "magis:ABC", section = "", displayName = "E1", orderIndex = 1,
            durationSeconds = 0.0, thumbPath = null, originalPath = null, originalFormat = null,
            originalSize = 0, derivativePath = null, derivativeFormat = null, derivativeSize = 0,
            season = null, episode = 1, torrentFileIndex = null, torrentData = "ref-cap",
            updatedAt = updatedAt, deleted = deleted,
        )

    private fun ida_y_vuelta(
        items: List<ItemEntity> = emptyList(),
        episodes: List<EpisodeEntity> = emptyList(),
        liveFavorites: List<LiveFavoriteEntity> = emptyList(),
        liveRecents: List<LiveRecentEntity> = emptyList(),
    ) = SyncSnapshot.fromJson(
        SyncSnapshot(items, episodes, emptyList(), emptyList(), liveFavorites, liveRecents).toJson(),
    )

    @Test fun el_reloj_del_item_sobrevive_al_viaje() {
        assertEquals(777L, ida_y_vuelta(items = listOf(item(updatedAt = 777))).items.single().updatedAt)
    }

    @Test fun un_item_borrado_llega_borrado() {
        // Sin esto, borrar en el celu resucitaba desde el TV en el sync siguiente.
        assertTrue(ida_y_vuelta(items = listOf(item(deleted = true))).items.single().deleted)
    }

    @Test fun un_item_vivo_llega_vivo() {
        assertFalse(ida_y_vuelta(items = listOf(item(deleted = false))).items.single().deleted)
    }

    @Test fun el_reloj_y_el_tombstone_del_episodio_sobreviven() {
        val ep = ida_y_vuelta(episodes = listOf(episode(updatedAt = 888, deleted = true))).episodes.single()
        assertEquals(888L, ep.updatedAt)
        assertTrue(ep.deleted)
    }

    @Test fun el_vinculo_con_tmdb_y_el_contador_de_novedades_no_se_pierden() {
        // Los recibe la otra punta con `upsert`, que reemplaza la fila entera: lo que no viaje acá
        // se borra. `tmdbId` es lo que permite titular los capítulos; el contador apaga el badge.
        val recibido = ida_y_vuelta(items = listOf(item(vistos = 4, tmdbId = 12697))).items.single()
        assertEquals(4, recibido.episodiosVistosEnLista)
        assertEquals(12697, recibido.tmdbId)
    }

    @Test fun la_numeracion_del_capitulo_no_se_pierde() {
        // `season`/`episode` son con lo que se sabe qué capítulo falta y se piden títulos a TMDB.
        val ep = ida_y_vuelta(episodes = listOf(episode())).episodes.single()
        assertEquals(1, ep.episode)
    }

    // --- liveFavorites/liveRecents: mismo cable, agregados en la Task 10 de TV en vivo. Antes de
    // este test, SyncLiveFavoritesTest cubría el MERGE (SyncMerge.aAplicar) pero nada probaba el
    // FORMATO DE CABLE -que toJson()/fromJson() de verdad conserven reloj y tombstone- que es lo
    // que de verdad viaja celu<->TV. ---

    @Test fun el_favorito_de_vivo_sobrevive_al_viaje_con_reloj_y_tombstone() {
        val fav = LiveFavoriteEntity("c1", "ESPN", 501, "logo.png", updatedAt = 555, deleted = true)
        val recibido = ida_y_vuelta(liveFavorites = listOf(fav)).liveFavorites.single()
        assertEquals("ESPN", recibido.nombre)
        assertEquals(501, recibido.numero)
        assertEquals("logo.png", recibido.logo)
        assertEquals(555L, recibido.updatedAt)
        assertTrue(recibido.deleted)
    }

    @Test fun el_reciente_de_vivo_sobrevive_al_viaje() {
        val rec = LiveRecentEntity("c2", "TNT Sports", vistoAt = 999, updatedAt = 111)
        val recibido = ida_y_vuelta(liveRecents = listOf(rec)).liveRecents.single()
        assertEquals("TNT Sports", recibido.nombre)
        assertEquals(999L, recibido.vistoAt)
        assertEquals(111L, recibido.updatedAt)
    }

    @Test fun un_snapshot_de_la_app_vieja_sigue_leyendose() {
        // Compatibilidad: la otra punta puede tener la versión anterior, que no manda estos campos.
        // Tiene que entrar sin explotar; sin reloj simplemente no le gana a nada (ver SyncMerge).
        val viejo = """
            {"items":[{"identifier":"a","title":"T","description":null,"thumbnailUrl":"",
            "addedAt":1,"categoryOverride":null,"source":"archive","torrentData":null}],
            "episodes":[],"playback":[],"markers":[]}
        """.trimIndent()
        val leido = SyncSnapshot.fromJson(viejo).items.single()
        assertEquals(0L, leido.updatedAt)
        assertFalse(leido.deleted)
    }
}
