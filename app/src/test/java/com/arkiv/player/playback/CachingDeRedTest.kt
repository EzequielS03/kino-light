package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cuánto colchón de red le pedimos a libVLC, por FUENTE.
 *
 * Estaba escrito como un `when` con dos casos medidos y un `else` de 1500 ms que se tragaba a todos
 * los demás — incluido el canal en vivo, que no es "todos los demás": tiene la misma forma de camino
 * que WEB (VLC → proxy local nuestro → CDN por internet) y por lo tanto la misma latencia variable
 * por segmento que hizo subir a WEB de 1500 a 8000.
 *
 * El decompilado respalda el principio: su reproductor configura el vivo APARTE del VOD
 * (`live_mode=1` cuando `buss == "live"`, más `live-streaming` y `delay-optimization`), no lo deja
 * caer en el caso general. Esas tres son opciones de SU fork de ijkplayer y no tienen equivalente
 * literal en libVLC; lo que se porta es la decisión, no el nombre.
 *
 * Sacarlo a una función pura es la mitad del punto: hasta ahora estos números vivían sueltos dentro
 * de `loadMedia` y no había dónde escribir por qué valía cada uno.
 */
class CachingDeRedTest {

    /** Medido en device: con 2,5 s VLC se quedaba sin datos y estancaba; 6 s da el arranque limpio. */
    @Test fun `torrent lleva el colchon medido para bajar y reproducir a la vez`() {
        assertEquals(6_000, CachingDeRed.msPara(SourceKind.TORRENT))
    }

    /** El HLS web va proxeado por blog (2 CPU) + Cloudflare: con 1,5 s se drenaba y se alcanzaba. */
    @Test fun `web lleva el colchon medido contra el proxy`() {
        assertEquals(8_000, CachingDeRed.msPara(SourceKind.WEB))
    }

    /**
     * El vivo ya NO hereda el colchón de web: se midió su CDN y no se parece.
     *
     * Cuando se separó del caso general no sabíamos nada de ese CDN, así que se le puso el de web
     * (8000 ms) por precaución. Medido en el Fire TV el 2026-08-14 sobre 15 minutos de canal:
     * playlist en 141 ms de mediana (p95 283, max 355) y segmento en 206 ms (p95 360, max 596),
     * con cero 403, cero 502 y cero segmentos cortados. Es un orden de magnitud más predecible que
     * el CDN de VOD, que va de 0,2 s a 20 s por rango y rechaza al azar.
     *
     * Con esa latencia, 8 s de colchón son ~13× el peor segmento observado: puro retardo de
     * arranque. 3000 ms siguen siendo 5× ese peor caso.
     */
    @Test fun `el vivo usa el colchon medido de su propio CDN`() {
        assertEquals(3_000, CachingDeRed.msPara(SourceKind.LIVE))
    }

    /**
     * El piso NO es arbitrario: el colchón tiene que cubrir con margen al peor segmento medido
     * (596 ms). Si alguien lo baja de ahí, este test lo frena.
     */
    @Test fun `el colchon del vivo cubre con margen el peor segmento medido`() {
        val peorSegmentoMedidoMs = 596
        assert(CachingDeRed.msPara(SourceKind.LIVE) >= peorSegmentoMedidoMs * 4) {
            "el colchón del vivo (${CachingDeRed.msPara(SourceKind.LIVE)}ms) no cubre 4× el peor segmento"
        }
    }

    /**
     * Los orígenes de archivo estable se quedan como estaban. MAGIS incluido: su CDN es lento por
     * rango (0,2 s a 20 s) pero VLC lee de corrido sobre un rango abierto, y subirle el colchón
     * alarga el arranque, que es justo lo que más costó bajar. Sin medición no se toca.
     */
    @Test fun `el resto se queda en el colchon de siempre`() {
        listOf(SourceKind.ARCHIVE, SourceKind.MAGIS, SourceKind.LOCAL, SourceKind.NUC)
            .forEach { assertEquals("kind=$it", 1_500, CachingDeRed.msPara(it)) }
    }

    /** Sin tag (una fuente que no declaró nada) se comporta como el caso general. */
    @Test fun `sin fuente conocida vale el caso general`() {
        assertEquals(1_500, CachingDeRed.msPara(null))
    }

    /** Ningún valor puede quedar en cero: sería reproducir sin colchón ninguno. */
    @Test fun `ninguna fuente se queda sin colchon`() {
        (SourceKind.entries + null).forEach {
            assert(CachingDeRed.msPara(it) > 0) { "kind=$it sin colchón" }
        }
    }
}
