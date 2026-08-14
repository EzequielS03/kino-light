package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Qué contenido era el que falló, dicho en la misma línea en que se dispara el rescate.
 *
 * Hoy un rescate deja escrito `hardware sin imagen 10234ms → paso a software` y nada más: no dice
 * qué contenedor, qué códec ni en qué equipo, que es exactamente lo que hace falta para saber si el
 * problema es un HEVC de magis, un AVI viejo o el decodificador del Fire Stick. La app original sí
 * lo guarda —su telemetría `SwitchPlayer` lleva `format`, `vcodec` y `model`, y ante el error 1100
 * hasta vuelca el `getCandidateCodecList()` del equipo— solo que se lo manda a su backend.
 *
 * Es una función pura porque el modo de fallar de un log es silencioso: una excepción leyendo el
 * códec dentro del rescate se comería el rescate entero.
 */
class DiagnosticoDeFalloTest {

    @Test fun `arma una linea con todo lo que hace falta`() {
        assertEquals(
            "FALLO motivo=hardware-sin-imagen contenedor=mpegts vcodec=hevc 1920x1080 " +
                "pistas=v1/a3 decodificador=hardware equipo=AFTKM",
            DiagnosticoDeFallo.linea(
                motivo = "hardware-sin-imagen", contenedor = "mpegts", codecVideo = "hevc",
                ancho = 1920, alto = 1080, pistasVideo = 1, pistasAudio = 3,
                porHardware = true, equipo = "AFTKM",
            ),
        )
    }

    /** Lo que no se pudo leer se dice como no leído, no se omite: un hueco callado engaña. */
    @Test fun `lo que no se pudo leer sale como interrogante`() {
        assertEquals(
            "FALLO motivo=estancado-en-0 contenedor=? vcodec=? ?x? " +
                "pistas=v0/a0 decodificador=software equipo=?",
            DiagnosticoDeFallo.linea(
                motivo = "estancado-en-0", contenedor = null, codecVideo = "",
                ancho = 0, alto = 0, pistasVideo = 0, pistasAudio = 0,
                porHardware = false, equipo = "",
            ),
        )
    }

    /** Una consulta que devolvió negativo ("no se pudo preguntar") tampoco es un cero. */
    @Test fun `las pistas ilegibles no se confunden con cero pistas`() {
        val l = DiagnosticoDeFallo.linea(
            motivo = "x", contenedor = "mp4", codecVideo = "h264",
            ancho = 640, alto = 480, pistasVideo = -1, pistasAudio = -1,
            porHardware = true, equipo = "Pixel",
        )
        assert(l.contains("pistas=v?/a?")) { l }
    }
}
