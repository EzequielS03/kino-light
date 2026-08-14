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

    // ---- A qué contenedores les hace falta la cola ----

    /**
     * MEDIDO EN EL FIRE TV el 2026-08-14, siete reproducciones seguidas: los tres títulos en **mp4**
     * bajaron su cola y NO la usaron **ni una vez** (cero líneas `cola caliente`), mientras que los
     * mpegts la usaron en todas sus aperturas, varias veces cada una.
     *
     * Y bajarla no es gratis: en uno de esos mp4 costó **8284 ms y tres rechazos del CDN**, en
     * paralelo con la apertura del video y contra el mismo origen que tiene que servirlo.
     *
     * ```
     * 10:04:42.079  origen rechazó bytes=129893353-130155496 con -1 (intento 1/3)
     * 10:04:43.279  origen rechazó bytes=129893353-130155496 con -1 (intento 1/3)
     * 10:04:45.131  origen rechazó bytes=129893353-130155496 con -1 (intento 2/3)
     * 10:04:46.758  precalentada la cola: 256KB en 8284ms
     * ```
     */
    @Test fun el_mp4_no_necesita_la_cola() {
        assert(!ColaCaliente.hayQuePrecalentar("mp4"))
        assert(!ColaCaliente.hayQuePrecalentar("MP4"))
    }

    /** El TS sí: libVLC le lee el último PCR para deducir la duración, y sin eso no abre. */
    @Test fun el_ts_la_necesita() {
        assert(ColaCaliente.hayQuePrecalentar("ts"))
        assert(ColaCaliente.hayQuePrecalentar("mpegts"))
    }

    /**
     * Ante la duda, se precalienta. Un contenedor desconocido puede tener su índice al final —el
     * Matroska guarda los Cues ahí, que es de donde salió el bug del buffering infinito en los
     * torrents `.mkv`— y no traerla sería volver a ese fallo por ahorrar 256 KB.
     */
    @Test fun ante_la_duda_se_precalienta() {
        assert(ColaCaliente.hayQuePrecalentar(""))
        assert(ColaCaliente.hayQuePrecalentar("matroska"))
        assert(ColaCaliente.hayQuePrecalentar("flv"))
        assert(ColaCaliente.hayQuePrecalentar(null))
    }
}
