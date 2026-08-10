package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reanudar un TS de magis abriendo una ventana del archivo en vez de saltar. El porqué está en
 * [VentanaDeArchivo]; acá se fija la aritmética, que es donde un error se vuelve invisible: el
 * video reproduce igual, solo que empezando en otro lado.
 */
class VentanaDeArchivoTest {

    private val TOTAL = 1_118_023_968L // la película real con la que se reprodujo el fallo

    @Test fun el_inicio_cae_en_borde_de_paquete() {
        val i = VentanaDeArchivo.inicio(TOTAL, 0.5f)
        assertEquals(0L, i % 188)
    }

    @Test fun el_inicio_es_proporcional_a_la_fraccion() {
        val i = VentanaDeArchivo.inicio(TOTAL, 0.25f)
        // A lo sumo un paquete por debajo del ideal (se alinea hacia abajo).
        val ideal = (TOTAL * 0.25).toLong()
        assertEquals(true, i <= ideal && ideal - i < 188)
    }

    @Test fun sin_fraccion_no_hay_ventana() {
        assertEquals(0L, VentanaDeArchivo.inicio(TOTAL, 0f))
        assertEquals(0L, VentanaDeArchivo.inicio(TOTAL, -1f))
    }

    @Test fun la_ventana_nunca_queda_vacia() {
        // Pedir el final entero dejaría un archivo virtual de 0 bytes, que VLC lee como fallo.
        val i = VentanaDeArchivo.inicio(TOTAL, 1f)
        assertEquals(true, VentanaDeArchivo.tamanoVisible(TOTAL, i) >= 188)
    }

    @Test fun un_archivo_diminuto_no_se_ventanea() {
        assertEquals(0L, VentanaDeArchivo.inicio(100L, 0.5f))
    }

    @Test fun el_tamano_visible_es_lo_que_queda() {
        assertEquals(TOTAL - 1880L, VentanaDeArchivo.tamanoVisible(TOTAL, 1880L))
    }

    // ─── traducción de rangos ──────────────────────────────────────────────

    @Test fun el_rango_abierto_del_reproductor_arranca_en_el_desfase() {
        // VLC abre pidiendo "bytes=0-": para él el archivo empieza en 0.
        assertEquals("bytes=1880-", VentanaDeArchivo.rangoAlOrigen(ByteRange(0L, null), 1880L))
    }

    @Test fun sin_rango_pedido_se_pide_la_ventana_entera() {
        assertEquals("bytes=1880-", VentanaDeArchivo.rangoAlOrigen(null, 1880L))
    }

    @Test fun el_rango_cerrado_se_corre_por_los_dos_extremos() {
        assertEquals("bytes=2880-3880", VentanaDeArchivo.rangoAlOrigen(ByteRange(1000L, 2000L), 1880L))
    }

    @Test fun el_content_range_vuelve_a_coordenadas_del_reproductor() {
        val visible = VentanaDeArchivo.contentRangeVisible("bytes 1880-1118023967/1118023968", 1880L)
        assertEquals("bytes 0-1118022087/1118022088", visible)
    }

    @Test fun un_content_range_ilegible_no_se_inventa() {
        assertNull(VentanaDeArchivo.contentRangeVisible(null, 1880L))
        assertNull(VentanaDeArchivo.contentRangeVisible("bytes */1118023968", 1880L))
    }

    @Test fun un_content_range_antes_del_desfase_se_descarta() {
        // El origen ignoró el Range y respondió desde el principio: traducirlo daría negativos.
        assertNull(VentanaDeArchivo.contentRangeVisible("bytes 0-99/1118023968", 1880L))
    }

    // ─── cuándo abrir ventana en vez de saltar ─────────────────────────────

    @Test fun se_ventanea_el_ts_sin_duracion_propia() {
        assertEquals(true, VentanaDeArchivo.hayQueAbrirVentana(158_158L, lengthMs = 0L, duracionMs = 8_580_000L))
    }

    @Test fun no_se_ventanea_si_el_reproductor_ya_sabe_cuanto_dura() {
        // Con duración propia (mp4, HLS) el seek por tiempo de libVLC es exacto: no hay nada que arreglar.
        assertEquals(false, VentanaDeArchivo.hayQueAbrirVentana(158_158L, lengthMs = 8_580_000L, duracionMs = 8_580_000L))
    }

    @Test fun no_se_ventanea_sin_ninguna_duracion() {
        assertEquals(false, VentanaDeArchivo.hayQueAbrirVentana(158_158L, lengthMs = 0L, duracionMs = 0L))
    }

    @Test fun arrancar_desde_cero_no_necesita_ventana() {
        assertEquals(false, VentanaDeArchivo.hayQueAbrirVentana(0L, lengthMs = 0L, duracionMs = 8_580_000L))
    }

    @Test fun la_fraccion_sale_del_tiempo_sobre_la_duracion() {
        assertEquals(0.5f, VentanaDeArchivo.fraccionDe(4_290_000L, 8_580_000L), 0.0001f)
        assertEquals(0f, VentanaDeArchivo.fraccionDe(1_000L, 0L), 0.0001f)
        assertEquals(1f, VentanaDeArchivo.fraccionDe(99_000_000L, 8_580_000L), 0.0001f)
    }

    // ─── dónde va el reproductor dentro de la ventana ──────────────────────
    // No se usa el reloj de VLC: dentro de una ventana no arranca en 0 (estos VOD reinician el PCR
    // por tramos). Medido en device: ventana abierta en 1:07:29 y VLC reportando ~13:38 propios.

    @Test fun al_abrir_la_ventana_la_posicion_es_el_punto_de_reanudacion() {
        assertEquals(4_048_951L, VentanaDeArchivo.posicionAbsolutaMs(4_048_951L, 0f, 7_740_000L))
    }

    @Test fun a_mitad_de_ventana_va_a_mitad_de_lo_que_queda() {
        // Ventana desde 1:00:00 de una película de 2:00:00 → la mitad de la ventana es 1:30:00.
        assertEquals(5_400_000L, VentanaDeArchivo.posicionAbsolutaMs(3_600_000L, 0.5f, 7_200_000L))
    }

    @Test fun el_final_de_la_ventana_es_el_final_de_la_pelicula() {
        assertEquals(7_200_000L, VentanaDeArchivo.posicionAbsolutaMs(3_600_000L, 1f, 7_200_000L))
    }

    @Test fun una_ventana_incoherente_no_devuelve_disparates() {
        assertEquals(3_600_000L, VentanaDeArchivo.posicionAbsolutaMs(3_600_000L, 0.5f, 1_000L))
    }

    @Test fun el_total_sale_del_content_range() {
        assertEquals(TOTAL, VentanaDeArchivo.totalDelContentRange("bytes 0-4095/1118023968"))
        assertEquals(0L, VentanaDeArchivo.totalDelContentRange("cualquier cosa"))
    }
}
