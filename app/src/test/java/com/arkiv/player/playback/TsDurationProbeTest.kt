package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un MPEG-TS no dice cuánto dura en ninguna cabecera: la duración se deduce restando el reloj (PCR)
 * del final menos el del principio. Estos tests fijan ese cálculo con paquetes TS sintéticos.
 */
class TsDurationProbeTest {

    /** Paquete TS de 188 bytes con un PCR (base a 90 kHz) en el campo de adaptación. */
    private fun paqueteConPcr(pid: Int, base90k: Long): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x20                              // solo campo de adaptación, sin payload
        p[4] = 183.toByte()                      // longitud del campo de adaptación
        p[5] = 0x10                              // flag de PCR presente
        p[6] = ((base90k shr 25) and 0xFF).toByte()
        p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte()
        p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = ((base90k and 1L) shl 7).toByte()
        p[11] = 0
        return p
    }

    /** Paquete TS sin PCR (solo payload), del mismo pid. */
    private fun paqueteSinPcr(pid: Int): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10                              // solo payload
        return p
    }

    private fun bloque(vararg paquetes: ByteArray): ByteArray =
        paquetes.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test fun duracion_es_el_ultimo_pcr_de_la_cola_menos_el_primero_de_la_cabeza() {
        val cabeza = bloque(
            paqueteConPcr(0x100, 90_000L),        // 1 s
            paqueteSinPcr(0x100),
            paqueteConPcr(0x100, 180_000L),
        )
        val cola = bloque(
            paqueteConPcr(0x100, 900_000_000L),
            paqueteConPcr(0x100, 913_050_000L),   // 10 145 s
        )
        // (913050000 - 90000) / 90 = 10144000 ms
        assertEquals(10_144_000L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun se_alinea_cuando_la_cola_arranca_a_mitad_de_paquete() {
        val cabeza = bloque(paqueteConPcr(0x100, 0L))
        // Una petición Range cae en cualquier byte: la cola llega desalineada y hay que
        // encontrar el sincronismo antes de parsear.
        val cola = ByteArray(57) { 0x11 } + bloque(
            paqueteConPcr(0x100, 90_000L),
            paqueteConPcr(0x100, 180_000L),
            paqueteConPcr(0x100, 270_000L),
        )
        assertEquals(3_000L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun ignora_los_pcr_de_otro_pid() {
        val cabeza = bloque(paqueteConPcr(0x100, 90_000L))
        val cola = bloque(
            paqueteConPcr(0x100, 90_090_000L),    // el bueno: 1000 s después
            paqueteConPcr(0x200, 500_000_000L),   // otro programa, no debe contar
        )
        assertEquals(1_000_000L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun cero_cuando_la_cola_no_trae_pcr_del_mismo_pid() {
        val cabeza = bloque(paqueteConPcr(0x100, 90_000L))
        val cola = bloque(paqueteConPcr(0x200, 90_090_000L))
        assertEquals(0L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun cero_cuando_no_hay_ningun_pcr() {
        val cabeza = bloque(paqueteSinPcr(0x100), paqueteSinPcr(0x100))
        val cola = bloque(paqueteSinPcr(0x100))
        assertEquals(0L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun contempla_el_giro_del_contador_de_33_bits() {
        // El PCR es de 33 bits a 90 kHz: da la vuelta cada ~26,5 h. Un archivo que arranca cerca
        // del tope termina con un PCR MENOR que el del principio.
        val tope = 1L shl 33
        val cabeza = bloque(paqueteConPcr(0x100, tope - 90_000L))   // 1 s antes del giro
        val cola = bloque(paqueteConPcr(0x100, 180_000L))           // 2 s después del giro
        assertEquals(3_000L, TsDurationProbe.durationMs(cabeza, cola))
    }

    @Test fun cero_cuando_el_resultado_no_es_creible() {
        // Más de 24 h = el parseo se fue a la basura (PCR de otro programa, basura en el buffer).
        // Vale más no mostrar duración que mostrar una inventada.
        val cabeza = bloque(paqueteConPcr(0x100, 0L))
        val cola = bloque(paqueteConPcr(0x100, 90_000L * 60 * 60 * 25))
        assertEquals(0L, TsDurationProbe.durationMs(cabeza, cola))
    }

    // ─── cuánto puede tardar la sonda ──────────────────────────────────────
    // El video NO arranca hasta que esto termina, así que cada segundo de acá es un segundo de
    // spinner. Medido el 2026-08-11 en el Fire TV: un tramo se comió su timeout de 8 s y la sonda
    // entera costó 9,01 s de un arranque de 13,4 s. El reintento contestó en ~1 s — el problema no
    // era el CDN, era cuánto se le esperaba a una conexión que ya estaba muerta.

    @Test fun la_sonda_no_le_aguanta_mas_que_el_proxy_a_la_misma_conexion_muerta() {
        // Le pega al MISMO CDN que ArchiveCacheProxy, así que tener su propia calibración solo
        // servía para que las dos se fueran separando. Una sola fuente de verdad: PoliticaOrigen.
        assertEquals(
            PoliticaOrigen.respuestaMs(0, PoliticaOrigen.Perfil.MAGIS),
            TsDurationProbe.timeoutLecturaMs(0),
        )
    }

    @Test fun el_presupuesto_de_la_sonda_cabe_en_lo_que_un_humano_espera() {
        // Eran 30 s: media hora de spinner por una barra de progreso. La duración es una mejora,
        // nunca un motivo para no reproducir.
        assertTrue(
            "presupuesto = ${TsDurationProbe.PRESUPUESTO_MS}ms",
            TsDurationProbe.PRESUPUESTO_MS <= 12_000,
        )
    }

    @Test fun el_caso_medido_una_conexion_muerta_por_tramo_entra_en_el_presupuesto() {
        // Es el caso REAL, no el peor teórico: cada tramo se come una conexión muerta y se recupera
        // en el segundo intento. Si eso no entra en el presupuesto, la sonda se cancela y la barra
        // queda sin duración justo en el caso que sí tenía arreglo.
        val porTramo = TsDurationProbe.timeoutLecturaMs(0) +
            TsDurationProbe.esperaEntreIntentosMs(0) +
            TsDurationProbe.timeoutLecturaMs(1)
        assertTrue(
            "dos tramos en serie = ${2 * porTramo}ms contra ${TsDurationProbe.PRESUPUESTO_MS}ms",
            2 * porTramo <= TsDurationProbe.PRESUPUESTO_MS,
        )
    }
}
