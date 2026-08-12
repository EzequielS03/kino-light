package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué instantes cuentan como "el decodificador no está dando imagen". De acá salen los dos rescates
 * de [VlcPlayer], así que contar de más significa recargar la película sin motivo.
 */
class RachaSinVideoTest {

    @Test fun con_imagen_no_hay_racha() {
        assertFalse(RachaSinVideo.cuenta(hayVideo = true, superficieEnganchada = true, pistas = 3))
    }

    @Test fun sin_superficie_enganchada_no_se_acumula() {
        // Sin superficie NO PUEDE haber imagen, así que esa ausencia no dice nada del decodificador.
        // Contarla igual hacía que salir del reproductor 8 s disparara el rescate: al volver, la
        // película se recargaba entera con una conexión nueva al CDN (medido en device: DETACH a
        // las 23:04:28 y `loadMedia` a las 23:04:36, sin que nada estuviera fallando).
        assertFalse(RachaSinVideo.cuenta(hayVideo = false, superficieEnganchada = false, pistas = 3))
    }

    @Test fun sin_pistas_demuxeadas_no_se_acumula() {
        // El caso que este objeto viene a arreglar, medido el 2026-08-11 en el Fire TV: VLC llevaba
        // 10 s en `pistas=v0/a0 video=false dur=0ms` porque la petición del rango de cola al CDN
        // había salido muerta y NO HABÍA LLEGADO UN SOLO BYTE. El decodificador estaba impecable —
        // no tenía nada que decodificar todavía. Aun así saltó "hardware sin imagen → paso a
        // software", que recargó el media entero y encima lo dejó en decodificación por software.
        assertFalse(RachaSinVideo.cuenta(hayVideo = false, superficieEnganchada = true, pistas = 0))
    }

    @Test fun con_pistas_y_sin_imagen_si_se_acumula() {
        // Este es el fallo REAL del decodificador y el rescate tiene que seguir disparando: el
        // stream ya se identificó (hay pistas), hay dónde pintar, y sin embargo no sale imagen.
        assertTrue(RachaSinVideo.cuenta(hayVideo = false, superficieEnganchada = true, pistas = 2))
    }

    @Test fun una_sola_pista_de_audio_ya_cuenta_como_stream_identificado() {
        // No hace falta pista de VIDEO: que el demuxer haya sacado CUALQUIER pista prueba que los
        // datos están llegando y que ya se leyó lo suficiente para identificar el stream.
        assertTrue(RachaSinVideo.cuenta(hayVideo = false, superficieEnganchada = true, pistas = 1))
    }

    @Test fun si_no_se_pueden_contar_las_pistas_se_acumula_igual() {
        // libVLC devuelve -1 cuando la consulta falla. Ante la duda se conserva el comportamiento
        // viejo: desactivar el rescate por no poder medir sería cambiar un fallo conocido por uno
        // peor (pantalla negra para siempre).
        assertTrue(RachaSinVideo.cuenta(hayVideo = false, superficieEnganchada = true, pistas = -1))
    }
}
