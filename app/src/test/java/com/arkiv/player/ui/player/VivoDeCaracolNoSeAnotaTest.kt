package com.arkiv.player.ui.player

import com.arkiv.player.data.DituEntities
import com.arkiv.player.playback.DituLive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un canal en vivo de Caracol no deja filas en `playback`, y todo lo demás se anota igual que antes.
 * Ver [hayQueAnotarHistorial] (progreso y frames) y [hayQueMarcarEnCurso] (al abrir).
 */
class VivoDeCaracolNoSeAnotaTest {

    private val vivo = "${DituLive.PREFIX}5"
    private val magis = "magis:2AD2591D4242471D96B68FF04FFD2784::e6"
    private val vodDeCaracol = DituEntities.episodioIdDePelicula(DituEntities.itemIdDe("42"))

    /** Sin playlist: así queda mientras suena Caracol, porque `loadDitu` la deja en null. */
    private val sinPlaylist: PlaylistData? = null

    @Test fun `el progreso de un vivo de Caracol no se anota`() {
        assertFalse(sinPlaylist.hayQueAnotarHistorial(vivo))
    }

    @Test fun `el progreso de Magis y de un VOD de Caracol se anota como antes`() {
        assertTrue(sinPlaylist.hayQueAnotarHistorial(magis))
        assertTrue(sinPlaylist.hayQueAnotarHistorial(vodDeCaracol))
    }

    @Test fun `un vivo de Caracol no se marca en curso`() {
        assertFalse(hayQueMarcarEnCurso(vivo, adulto = null))
    }

    @Test fun `Magis y un VOD de Caracol se marcan en curso como antes`() {
        assertTrue(hayQueMarcarEnCurso(magis, adulto = null))
        assertTrue(hayQueMarcarEnCurso(vodDeCaracol, adulto = null))
        // Y lo de adultos sigue sin marcarse: la regla de antes no cambió.
        assertFalse(hayQueMarcarEnCurso(magis, adulto = true))
    }
}
