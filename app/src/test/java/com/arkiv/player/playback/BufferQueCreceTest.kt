package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * El buffer del arranque, leído MIENTRAS se llena. Ver [BufferQueCrece] para el porqué.
 */
class BufferQueCreceTest {

    private fun datos(desde: Int, n: Int) = ByteArray(n) { ((desde + it) % 251).toByte() }

    @Test fun lo_escrito_se_puede_leer_enseguida() {
        val b = BufferQueCrece(1000)
        b.escribir(datos(0, 10), 10)
        assertEquals(10, b.disponible)
        assertArrayEquals(datos(0, 10), b.porcion(0))
    }

    @Test fun porcion_devuelve_solo_lo_nuevo_desde_un_offset() {
        // Es como lo consume el proxy: escribe a VLC lo que llegó y avanza su marca.
        val b = BufferQueCrece(1000)
        b.escribir(datos(0, 30), 30)
        assertArrayEquals(datos(10, 20), b.porcion(10))
    }

    @Test fun porcion_al_dia_devuelve_vacio() {
        val b = BufferQueCrece(1000)
        b.escribir(datos(0, 10), 10)
        assertEquals(0, b.porcion(10).size)
    }

    @Test fun esperar_vuelve_apenas_llegan_los_bytes() {
        // El caso que da sentido a todo esto: el lector llega ANTES que los datos y no se lo hace
        // esperar más de lo que tarda el origen en mandarlos.
        val b = BufferQueCrece(1000)
        thread { Thread.sleep(50); b.escribir(datos(0, 40), 40) }
        assertTrue("tenía que despertarse al llegar los bytes", b.esperarHasta(40, 5_000))
        assertEquals(40, b.disponible)
    }

    @Test fun esperar_se_rinde_si_el_buffer_se_cierra_sin_llegar_a_ese_tamano() {
        // El origen cortó antes. No es un error: quien lee tiene que poder salir del bucle.
        val b = BufferQueCrece(1000)
        thread { Thread.sleep(50); b.escribir(datos(0, 5), 5); b.cerrar() }
        assertFalse(b.esperarHasta(40, 5_000))
        assertTrue("lo que sí llegó tiene que quedar disponible", b.disponible == 5)
    }

    @Test fun esperar_no_bloquea_para_siempre_si_nunca_llega_nada() {
        val b = BufferQueCrece(1000)
        val t0 = System.currentTimeMillis()
        assertFalse(b.esperarHasta(10, 120))
        assertTrue("tenía que vencer solo", System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun sobre_un_buffer_cerrado_esperar_vuelve_en_el_acto() {
        val b = BufferQueCrece(1000)
        b.escribir(datos(0, 10), 10)
        b.cerrar()
        assertTrue("lo ya disponible se concede aunque esté cerrado", b.esperarHasta(10, 5_000))
        assertFalse(b.esperarHasta(11, 5_000))
    }

    @Test fun no_se_pasa_de_la_capacidad() {
        // El tope es el tamaño del arranque caliente: pasarse seria corromper memoria ajena.
        val b = BufferQueCrece(16)
        b.escribir(datos(0, 100), 100)
        assertEquals(16, b.disponible)
        assertArrayEquals(datos(0, 16), b.porcion(0))
    }

    @Test fun avisa_cuando_ya_no_va_a_venir_nada_mas() {
        val b = BufferQueCrece(1000)
        assertFalse(b.cerrado)
        b.cerrar()
        assertTrue(b.cerrado)
    }
}
