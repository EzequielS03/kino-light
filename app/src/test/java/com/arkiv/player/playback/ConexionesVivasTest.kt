package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El registro de conexiones al origen que están abiertas AHORA, para poder abandonarlas todas de
 * golpe cuando la red cambia debajo.
 *
 * No es [ConexionUnica], que hacía algo distinto y se abandonó: aquella cerraba la anterior del
 * MISMO origen creyendo que el CDN atendía de a una, y resultó ser la causa del fallo que decía
 * evitar (le cortaba a libVLC las lecturas con las que identifica el stream). Acá no se cierra
 * nada por abrir otra: el único momento en que se cierra es cuando alguien de afuera dice que ya
 * no sirven, y hoy ese alguien es el cambio de red.
 */
class ConexionesVivasTest {

    private class Espia : ConexionUnica.Cerrable {
        var cerrada = 0
        override fun cerrar() { cerrada++ }
    }

    @Test fun `cerrarTodas cierra las que estan abiertas`() {
        val vivas = ConexionesVivas()
        val a = Espia()
        val b = Espia()
        vivas.registrar(a)
        vivas.registrar(b)

        assertEquals(2, vivas.cerrarTodas())

        assertEquals(1, a.cerrada)
        assertEquals(1, b.cerrada)
    }

    /** Abrir una conexión NO cierra las otras: eso es exactamente lo que salió mal en ConexionUnica. */
    @Test fun `registrar una no toca a las demas`() {
        val vivas = ConexionesVivas()
        val a = Espia()
        vivas.registrar(a)
        vivas.registrar(Espia())
        assertEquals(0, a.cerrada)
    }

    /** Una conexión que ya terminó sola no se cierra dos veces ni cuenta. */
    @Test fun `la que se solto ya no se cierra`() {
        val vivas = ConexionesVivas()
        val a = Espia()
        vivas.registrar(a)
        vivas.soltar(a)

        assertEquals(0, vivas.cerrarTodas())
        assertEquals(0, a.cerrada)
    }

    @Test fun `sin conexiones abiertas no hay nada que cerrar`() {
        assertEquals(0, ConexionesVivas().cerrarTodas())
    }

    /** Después de cerrarlas, el registro queda vacío: un segundo aviso no las cierra de nuevo. */
    @Test fun `cerrar dos veces seguidas no repite`() {
        val vivas = ConexionesVivas()
        val a = Espia()
        vivas.registrar(a)

        assertEquals(1, vivas.cerrarTodas())
        assertEquals(0, vivas.cerrarTodas())
        assertEquals(1, a.cerrada)
    }

    /**
     * Una conexión que revienta al cerrarse no puede impedir que se cierren las demás: el socket ya
     * está muerto, tirar desde acá es lo esperable y no hay nada que hacer con esa excepción.
     */
    @Test fun `una que revienta al cerrar no frena a las otras`() {
        val vivas = ConexionesVivas()
        val buena = Espia()
        vivas.registrar(ConexionUnica.Cerrable { error("socket muerto") })
        vivas.registrar(buena)

        assertEquals(2, vivas.cerrarTodas())
        assertEquals(1, buena.cerrada)
    }
}
