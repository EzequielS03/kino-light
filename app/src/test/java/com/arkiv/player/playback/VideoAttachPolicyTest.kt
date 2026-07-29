package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAttachPolicyTest {

    private class Spy {
        var attaches = 0
        var detaches = 0
        val policy = VideoAttachPolicy(attach = { attaches++ }, detach = { detaches++ })
    }

    /** Entrar a la pantalla: el layout ya se enganchó al construirse, así que el ON_START inicial
     * (que el Lifecycle despacha al registrar el observador) NO debe tumbar y rehacer el vout. */
    @Test fun onStart_inicial_no_reengancha() {
        val s = Spy()
        s.policy.onStart()
        assertEquals(0, s.attaches)
        assertEquals(0, s.detaches)
    }

    /** El bug: irse a otra app y volver dejaba la pantalla negra porque nadie re-enganchaba. */
    @Test fun ida_a_otra_app_y_vuelta_reengancha() {
        val s = Spy()
        s.policy.onStop()
        assertEquals(1, s.detaches)
        assertEquals(0, s.attaches)
        s.policy.onStart()
        assertEquals(1, s.attaches)
    }

    /** Varias idas y vueltas seguidas: un attach por regreso, sin acumular. */
    @Test fun varias_idas_y_vueltas() {
        val s = Spy()
        repeat(3) {
            s.policy.onStop()
            s.policy.onStart()
        }
        assertEquals(3, s.detaches)
        assertEquals(3, s.attaches)
    }

    /** Eventos repetidos no deben duplicar: attachViews() pisa el VideoHelper anterior sin liberarlo
     * (fuga), así que cada attach tiene que venir de un detach real. */
    @Test fun eventos_repetidos_no_duplican() {
        val s = Spy()
        s.policy.onStop()
        s.policy.onStop()
        assertEquals(1, s.detaches)
        s.policy.onStart()
        s.policy.onStart()
        assertEquals(1, s.attaches)
    }
}
