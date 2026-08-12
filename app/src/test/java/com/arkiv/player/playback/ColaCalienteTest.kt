package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El final del archivo, servido desde memoria. Ver [ColaCaliente] para por qué hace falta.
 */
class ColaCalienteTest {

    // Un archivo de 1000 bytes del que tenemos guardados los últimos 100 (del 900 al 999), cada
    // byte valiendo su posición módulo 251 (primo, así ningún patrón se repite dentro del tramo).
    private val TOTAL = 1000L
    private val INICIO = 900L
    private val COLA = ByteArray(100) { ((INICIO + it) % 251).toByte() }

    private fun servir(header: String?) =
        ColaCaliente.servir(INICIO, COLA, RangeHeader.parse(header), TOTAL)

    @Test fun un_rango_abierto_dentro_de_la_cola_se_sirve_de_memoria() {
        // Es lo que pide libVLC sondeando el final: `bytes=N-` con N cerca del EOF.
        val esperado = ByteArray(60) { ((940 + it) % 251).toByte() }
        assertArrayEquals(esperado, servir("bytes=940-"))
    }

    @Test fun un_rango_cerrado_dentro_de_la_cola_tambien() {
        val esperado = ByteArray(10) { ((940 + it) % 251).toByte() }
        assertArrayEquals(esperado, servir("bytes=940-949"))
    }

    @Test fun el_primer_byte_de_la_cola_entra() {
        assertArrayEquals(COLA, servir("bytes=900-"))
    }

    @Test fun un_rango_que_empieza_antes_de_la_cola_no_se_sirve() {
        // Le faltarían bytes por delante: contestar solo el pedazo que tenemos le entregaría a VLC
        // un cuerpo más corto que el Content-Length, y se quedaría esperando el resto para siempre.
        assertNull(servir("bytes=899-"))
    }

    @Test fun un_rango_que_pasa_del_final_del_archivo_no_se_sirve() {
        // Un pedido inválido lo tiene que contestar el origen, no nosotros inventando un 206.
        assertNull(servir("bytes=940-1200"))
    }

    @Test fun sin_rango_no_se_sirve_de_la_cola() {
        // Sin Range, VLC quiere el archivo ENTERO desde el byte 0. La cola no es eso.
        assertNull(servir(null))
    }

    @Test fun sin_cola_guardada_no_se_sirve_nada() {
        assertNull(ColaCaliente.servir(INICIO, ByteArray(0), RangeHeader.parse("bytes=940-"), TOTAL))
    }

    @Test fun sin_saber_el_tamano_del_archivo_no_se_sirve() {
        // Sin el total no se puede validar el borde de arriba, y servir a ciegas es peor que ir al
        // origen: un 206 mal armado deja al reproductor colgado sin error.
        assertNull(ColaCaliente.servir(INICIO, COLA, RangeHeader.parse("bytes=940-"), 0L))
    }
}
