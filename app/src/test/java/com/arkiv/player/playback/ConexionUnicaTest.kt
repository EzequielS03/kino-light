package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El CDN de magis atiende UNA conexión por origen: al adelantar, VLC abre el tramo nuevo antes de
 * que muera el anterior y el nuevo se queda colgado hasta el timeout (visto en device: la petición
 * entra al proxy y nunca sale). Este registro cierra la anterior apenas entra la siguiente.
 */
class ConexionUnicaTest {

    private class Falsa : ConexionUnica.Cerrable {
        var cerrada = false
        override fun cerrar() { cerrada = true }
    }

    @Test fun `la nueva cierra la anterior del mismo origen`() {
        val reg = ConexionUnica()
        val vieja = Falsa()
        val nueva = Falsa()

        reg.registrar("peli", vieja)
        reg.registrar("peli", nueva)

        assertTrue("la anterior debe quedar cerrada", vieja.cerrada)
        assertFalse("la nueva sigue viva", nueva.cerrada)
    }

    @Test fun `origenes distintos no se pisan`() {
        val reg = ConexionUnica()
        val a = Falsa()
        val b = Falsa()

        reg.registrar("peli", a)
        reg.registrar("serie", b)

        assertFalse(a.cerrada)
        assertFalse(b.cerrada)
    }

    @Test fun `soltar quita la entrada sin cerrar nada mas`() {
        val reg = ConexionUnica()
        val vieja = Falsa()
        val nueva = Falsa()

        reg.registrar("peli", vieja)
        reg.soltar("peli", vieja)
        reg.registrar("peli", nueva)

        assertFalse("ya no estaba registrada: no hay que cerrarla otra vez", vieja.cerrada)
        assertFalse(nueva.cerrada)
    }

    @Test fun `soltar una que ya fue reemplazada no toca a la nueva`() {
        // La vieja termina DESPUÉS de que la nueva se registró: al soltar no debe borrar a la nueva.
        val reg = ConexionUnica()
        val vieja = Falsa()
        val nueva = Falsa()

        reg.registrar("peli", vieja)
        reg.registrar("peli", nueva)
        reg.soltar("peli", vieja)

        val tercera = Falsa()
        reg.registrar("peli", tercera)
        assertTrue("la nueva seguía siendo la activa y debe cerrarse", nueva.cerrada)
        assertEquals(false, tercera.cerrada)
    }
}
