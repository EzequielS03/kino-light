package com.arkiv.player.ui.player

import com.arkiv.player.playback.AdultContent
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El guarda de [AdultContent] aplicado al progreso de reproducción.
 *
 * `saveProgress` recibe un `episodeId` pelado —lo que el reproductor sabe de sí mismo cada ~5 s— y
 * no un ítem, así que la pregunta "¿esto es de adultos?" hay que contestarla contra la playlist que
 * está sonando. Esa búsqueda vive acá afuera, y no dentro del ViewModel, por lo mismo que
 * [AdultContent] vive afuera de `savePlayback`: es donde se pueden fijar sus bordes, y el
 * borde que importa es el que NO se ve —qué pasa cuando el episodio ni siquiera está en la playlist.
 */
class ProgresoDeAdultosTest {

    private fun item(episodeId: String, adulto: Boolean) = PlayerData(
        episodeId = episodeId,
        itemId = episodeId,
        title = "",
        subtitle = "",
        mediaUrl = "",
        castUrl = null,
        artworkUrl = "",
        openingStartMs = null,
        openingEndMs = null,
        endingStartMs = null,
        kind = SourceKind.MAGIS,
        adulto = adulto,
    )

    private fun playlist(vararg items: PlayerData) =
        PlaylistData(items.toList(), startIndex = 0, startPositionMs = 0L, pedido = items.first().episodeId)

    @Test fun `el progreso de contenido normal se guarda`() {
        val lista = playlist(item("magis:1", adulto = false))
        assertTrue(lista.hayQueAnotarHistorial("magis:1"))
    }

    @Test fun `el progreso de contenido de adultos no se guarda`() {
        val lista = playlist(item("magis:xxx", adulto = true))
        assertFalse(lista.hayQueAnotarHistorial("magis:xxx"))
    }

    /**
     * Se pregunta por EL episodio que suena, no por la playlist entera. Una serie de adultos no
     * puede envenenar el progreso de lo que venga después en la misma cola, ni al revés: un solo
     * capítulo marcado alcanza para que ese capítulo no se anote.
     */
    @Test fun `en una playlist mezclada manda el episodio que se pregunta`() {
        val lista = playlist(item("magis:normal", adulto = false), item("magis:18", adulto = true))
        assertTrue(lista.hayQueAnotarHistorial("magis:normal"))
        assertFalse(lista.hayQueAnotarHistorial("magis:18"))
    }

    /**
     * EL BORDE QUE IMPORTA, y va en la misma dirección que [AdultContent.shouldLog]: si el
     * episodio no está en la playlist, no se sabe, y lo que no se sabe SE ANOTA.
     *
     * Pasa de verdad y todo el tiempo: el ViewModel sobrevive a la navegación entre capítulos y
     * `_playlist` sigue publicando la del capítulo anterior mientras la fuente nueva resuelve (ver
     * el KDoc de [PlaylistData.pedido]). Si un desfase de esos se leyera como "es adulto", dejaría
     * de guardarse el progreso de películas normales sin que nadie se entere.
     */
    @Test fun `si el episodio no esta en la playlist, se anota`() {
        val lista = playlist(item("magis:1", adulto = true))
        assertTrue(lista.hayQueAnotarHistorial("magis:otro"))
    }

    /** Sin playlist todavía (arranque, o justo después de un `_playlist.value = null`): se anota. */
    @Test fun `sin playlist, se anota`() {
        assertTrue((null as PlaylistData?).hayQueAnotarHistorial("magis:1"))
    }
}
