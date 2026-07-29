package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoutTrackerTest {

    /**
     * El bug de la pantalla negra en archive: al recrearse el reproductor se suelta la superficie,
     * pero **VLC no avisa que la salida de video murió** — nunca manda un `Vout 0`. Como el contador
     * solo se actualizaba desde los eventos de VLC, quedaba clavado en 1 y la app creía que había
     * imagen. Consecuencia visible: el spinner de "esperando video" se apagaba al instante y te
     * dejaba un negro mudo. Soltar la ventana ES quedarse sin salida, lo diga VLC o no.
     */
    @Test fun soltar_la_superficie_deja_el_vout_en_cero() {
        val v = VoutTracker()
        v.onVout(1)
        assertTrue(v.hayVideo())
        v.onDetach()
        assertFalse(v.hayVideo())
    }

    /** Arranque en frío: VLC crea el vout solo cuando empieza a reproducir, no hay que empujarlo
     * (y empujarlo ahí, con el media todavía sin cargar, no tendría a qué agarrarse). */
    @Test fun primer_arranque_no_necesita_empujon() {
        val v = VoutTracker()
        assertFalse(v.necesitaEmpujon())
    }

    /** El caso real: hubo imagen, se perdió la superficie, se engancha una nueva. Acá VLC NO
     * reconstruye el vout por su cuenta —verificado en el Fire Stick: tras el attach no emite
     * ningún evento— así que hay que forzarlo. */
    @Test fun reenganche_despues_de_perder_la_superficie_necesita_empujon() {
        val v = VoutTracker()
        v.onVout(1)
        v.onDetach()
        assertTrue(v.necesitaEmpujon())
    }

    /** Con la imagen andando no se toca nada: el empujón apaga y prende la pista de video, o sea
     * que aplicado de más se vería como un parpadeo gratuito. */
    @Test fun con_video_vivo_no_necesita_empujon() {
        val v = VoutTracker()
        v.onVout(1)
        assertFalse(v.necesitaEmpujon())
    }

    /** Cuando VLC sí avisa que el vout se murió, vale igual que soltar la superficie. */
    @Test fun si_vlc_avisa_que_el_vout_murio_tambien_hay_que_empujar() {
        val v = VoutTracker()
        v.onVout(1)
        v.onVout(0)
        assertFalse(v.hayVideo())
        assertTrue(v.necesitaEmpujon())
    }

    /** Tras el empujón exitoso vuelve el evento de VLC y todo queda como al principio. */
    @Test fun el_empujon_deja_de_hacer_falta_cuando_vuelve_la_imagen() {
        val v = VoutTracker()
        v.onVout(1)
        v.onDetach()
        assertTrue(v.necesitaEmpujon())
        v.onVout(1)
        assertFalse(v.necesitaEmpujon())
        assertTrue(v.hayVideo())
    }
}
