package com.arkiv.player.playback

import com.arkiv.player.playback.VentanaDeDescarga.Rama
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dónde empieza a bajar el proxy cuando se reanuda, y de dónde sale cada tramo.
 * Ver [VentanaDeDescarga] para el porqué.
 */
class VentanaDeDescargaTest {

    // Datos del caso real (Get Backers 14, medido el 2026-08-10).
    private val TOTAL = 51_386_329L
    private val DURACION = 1_445_767L

    // ─── dónde arrancar la descarga ────────────────────────────────────────

    @Test fun desde_el_principio_baja_desde_el_byte_cero() {
        assertEquals(0L, VentanaDeDescarga.byteDeArranque(0L, DURACION, TOTAL))
    }

    @Test fun un_arranque_chico_no_justifica_ventanear() {
        // Reanudar a los 20 s: la descarga desde 0 llega enseguida, y empezar desde 0 deja el
        // archivo cacheable entero. Ventanear acá sería perder eso a cambio de nada.
        assertEquals(0L, VentanaDeDescarga.byteDeArranque(20_000L, DURACION, TOTAL))
    }

    @Test fun reanudar_pasada_la_mitad_arranca_cerca_de_ahi() {
        // El caso que rompía: reanudar en 10:59 de 24:05 hay que leer a partir del ~45%.
        val b = VentanaDeDescarga.byteDeArranque(659_222L, DURACION, TOTAL)
        val esperado = (TOTAL * 659_222.0 / DURACION).toLong()
        // Arranca un poco ANTES del punto exacto, nunca después.
        assert(b in (esperado - VentanaDeDescarga.MARGEN_ATRAS - 1)..esperado) {
            "byte=$b, esperado≈$esperado"
        }
    }

    @Test fun sin_duracion_conocida_no_se_inventa_una_posicion() {
        assertEquals(0L, VentanaDeDescarga.byteDeArranque(659_222L, 0L, TOTAL))
    }

    @Test fun sin_tamano_conocido_tampoco() {
        assertEquals(0L, VentanaDeDescarga.byteDeArranque(659_222L, DURACION, 0L))
    }

    @Test fun nunca_devuelve_un_byte_negativo() {
        assertEquals(0L, VentanaDeDescarga.byteDeArranque(-5_000L, DURACION, TOTAL))
    }

    @Test fun un_arranque_pasado_el_final_se_acota() {
        val b = VentanaDeDescarga.byteDeArranque(DURACION * 5, DURACION, TOTAL)
        assert(b in 0 until TOTAL) { "byte=$b fuera de [0,$TOTAL)" }
    }

    // ─── de dónde sale cada tramo ──────────────────────────────────────────
    // Sin ventana (inicio=0) tiene que comportarse EXACTAMENTE como siempre: es el camino por el
    // que pasa toda la reproducción normal de archive.

    private val ADELANTE = 8L * 1024 * 1024

    @Test fun sin_ventana_lo_ya_descargado_sale_de_disco() {
        assertEquals(
            Rama.DISCO,
            VentanaDeDescarga.rama(inicio = 0, descargado = 5_000_000, completo = false, start = 100, end = 200, umbralAdelante = ADELANTE),
        )
    }

    @Test fun sin_ventana_el_archivo_completo_sale_de_disco() {
        assertEquals(
            Rama.DISCO,
            VentanaDeDescarga.rama(0, descargado = 0, completo = true, start = 40_000_000, end = 40_000_100, umbralAdelante = ADELANTE),
        )
    }

    @Test fun sin_ventana_el_playhead_lee_del_que_crece() {
        assertEquals(
            Rama.CRECIENDO,
            VentanaDeDescarga.rama(0, descargado = 1_000_000, completo = false, start = 1_000_000, end = 1_010_000, umbralAdelante = ADELANTE),
        )
    }

    @Test fun sin_ventana_un_salto_lejano_va_al_origen() {
        // El moov de un MP4 no-faststart: está al final y VLC lo pide al abrir.
        assertEquals(
            Rama.ORIGEN,
            VentanaDeDescarga.rama(0, descargado = 1_000_000, completo = false, start = 50_000_000, end = 50_000_100, umbralAdelante = ADELANTE),
        )
    }

    // ─── con ventana ───────────────────────────────────────────────────────

    @Test fun con_ventana_lo_anterior_al_inicio_va_al_origen() {
        // La cabecera del MP4 (byte 0) NO está en el archivo si la ventana empieza en 23 MB.
        // Servirla desde disco leería basura: es el error que rompería todo.
        assertEquals(
            Rama.ORIGEN,
            VentanaDeDescarga.rama(inicio = 23_000_000, descargado = 25_000_000, completo = false, start = 0, end = 1_000, umbralAdelante = ADELANTE),
        )
    }

    @Test fun con_ventana_lo_anterior_va_al_origen_aunque_este_completa() {
        // `completo` significa "llegué al final", no "tengo el archivo entero desde 0".
        assertEquals(
            Rama.ORIGEN,
            VentanaDeDescarga.rama(inicio = 23_000_000, descargado = 51_386_329, completo = true, start = 100, end = 200, umbralAdelante = ADELANTE),
        )
    }

    @Test fun con_ventana_lo_de_adentro_sale_de_disco() {
        assertEquals(
            Rama.DISCO,
            VentanaDeDescarga.rama(23_000_000, descargado = 25_000_000, completo = false, start = 23_500_000, end = 23_600_000, umbralAdelante = ADELANTE),
        )
    }

    @Test fun con_ventana_el_playhead_lee_del_que_crece() {
        assertEquals(
            Rama.CRECIENDO,
            VentanaDeDescarga.rama(23_000_000, descargado = 25_000_000, completo = false, start = 25_000_000, end = 25_010_000, umbralAdelante = ADELANTE),
        )
    }

    // ─── posición dentro del archivo ───────────────────────────────────────

    @Test fun sin_ventana_el_byte_absoluto_es_la_posicion_del_archivo() {
        assertEquals(1_234L, VentanaDeDescarga.offsetEnArchivo(inicio = 0, byteAbsoluto = 1_234))
    }

    @Test fun con_ventana_se_le_resta_el_inicio() {
        assertEquals(
            500_000L,
            VentanaDeDescarga.offsetEnArchivo(inicio = 23_000_000, byteAbsoluto = 23_500_000),
        )
    }

    @Test fun una_ventana_solo_es_cacheable_si_empieza_en_cero() {
        // Un archivo que no arranca en 0 NO es el archivo: marcarlo como cacheado haría que la
        // próxima reproducción lo sirviera entero desde disco y saliera cortada.
        assert(VentanaDeDescarga.esCacheable(inicio = 0))
        assert(!VentanaDeDescarga.esCacheable(inicio = 23_000_000))
    }
}
