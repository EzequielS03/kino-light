package com.arkiv.player.data

import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Por dónde vas en una serie.
 *
 * Con un solo capítulo guardado esto no se notaba. Ahora que reproducir uno trae la temporada
 * completa (ver `addMagisSeason`), "Reproducir" y el texto del detalle apuntan a un capítulo entre
 * veinte, y las dos formas viejas de equivocarse se ven enseguida: caer al E1 apenas terminás el E5,
 * y caer al E1 cuando le diste play al E5 hace tres segundos y todavía no hay progreso guardado.
 */
class ItemDetailResumeTest {

    private fun ep(n: Int) = Episode(
        id = "magis:ABC::e$n", itemId = "magis:ABC", section = "", displayName = "E$n",
        orderIndex = n, durationSeconds = 0.0, thumbPath = null, original = null, derivative = null,
        season = null, episode = n,
    )

    private fun detalle(vararg progreso: Pair<Int, PlaybackEntity>) = ItemDetail(
        identifier = "magis:ABC",
        title = "Dragon Ball Daima T1",
        description = null,
        thumbnailUrl = "",
        episodes = (1..5).map { ep(it) },
        progress = progreso.associate { (n, p) -> ep(n).id to p },
    )

    private fun visto(cuando: Long) =
        PlaybackEntity("", positionMs = 600_000L, durationMs = 600_000L, watched = true, lastPlayedAt = cuando)

    private fun aMedias(cuando: Long) =
        PlaybackEntity("", positionMs = 120_000L, durationMs = 600_000L, watched = false, lastPlayedAt = cuando)

    /** Recién le diste play: hay fila, pero todavía sin posición ni duración. */
    private fun reciénTocado(cuando: Long) =
        PlaybackEntity("", positionMs = 0L, durationMs = 0L, watched = false, lastPlayedAt = cuando)

    @Test fun sin_nada_empezado_vas_en_el_primero() {
        assertEquals("magis:ABC::e1", detalle().resumeEpisode?.id)
    }

    @Test fun un_capitulo_a_medias_es_donde_vas() {
        val d = detalle(1 to visto(10L), 2 to aMedias(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
        assertEquals("magis:ABC::e2", d.resumeEpisode?.id)
    }

    @Test fun un_capitulo_recien_tocado_ya_es_donde_vas() {
        // `saveProgress` no escribe nada hasta saber la duración, y en Magis la sonda tarda: sin
        // esto, salir a los tres segundos del E3 dejaba el detalle diciendo "vas en el E1".
        val d = detalle(3 to reciénTocado(30L))
        assertEquals("magis:ABC::e3", d.inProgressEpisode?.id)
    }

    @Test fun si_terminaste_el_tercero_vas_en_el_cuarto() {
        // Antes caía al E1 apenas el capítulo pasaba a `watched`.
        val d = detalle(1 to visto(10L), 2 to visto(20L), 3 to visto(30L))
        assertEquals(null, d.inProgressEpisode)
        assertEquals("magis:ABC::e4", d.resumeEpisode?.id)
    }

    @Test fun con_todo_visto_vuelve_al_primero() {
        val d = detalle(1 to visto(10L), 2 to visto(20L), 3 to visto(30L), 4 to visto(40L), 5 to visto(50L))
        assertEquals("magis:ABC::e1", d.resumeEpisode?.id)
    }

    @Test fun gana_el_ultimo_tocado_no_el_de_numero_mas_alto() {
        val d = detalle(2 to aMedias(90L), 5 to aMedias(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
    }
}
