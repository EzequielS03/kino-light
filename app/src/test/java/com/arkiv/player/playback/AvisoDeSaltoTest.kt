package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Avisarle a la capa de entrega A DÓNDE va a saltar el reproductor, ANTES de que salte.
 *
 * Es la técnica central del reproductor de magis original: su `B0(name, pos)` no le manda el seek al
 * player, se lo manda al MOTOR DE ENTREGA (`NativeJni.Seek`) y recién en el callback de ese motor
 * mueve el reproductor (`yc/C6280e.java:2115`). O sea que los bytes del destino ya se están
 * buscando cuando el player llega.
 *
 * Nosotros teníamos la mitad: `ArchiveCacheProxy.precalentarSalto` existe, está probado y ahorra
 * segundos —se midió 3,4 s de imagen congelada en el Fire TV— pero solo se llamaba AL ABRIR, desde
 * `PlayerViewModel`. Un salto hecho a mano con la barra no avisaba nada: el proxy se enteraba del
 * destino recién cuando VLC le pedía el rango, y ahí ya es tarde contra un CDN que tarda entre
 * 0,2 s y 20 s en contestar (ver `PoliticaOrigen`).
 *
 * Esto es la REGLA de cuándo avisar y con qué fracción; el aviso en sí lo hace el proxy.
 */
class AvisoDeSaltoTest {

    @Test fun `la fraccion es la posicion sobre la duracion`() {
        assertEquals(0.5f, AvisoDeSalto.fraccion(60_000, 120_000)!!, 0.0001f)
        assertEquals(0.25f, AvisoDeSalto.fraccion(30_000, 120_000)!!, 0.0001f)
    }

    /** Sin duración no hay fracción que calcular: el destino en bytes es indeducible. */
    @Test fun `sin duracion no se avisa`() {
        assertNull(AvisoDeSalto.fraccion(60_000, 0))
        assertNull(AvisoDeSalto.fraccion(60_000, -1))
    }

    /**
     * Los dos extremos quedan fuera a propósito, y no por prolijidad: `precalentarSalto` los
     * descarta igual, así que avisar ahí sería gastar una conexión al CDN para nada. El principio
     * ya lo cubre el arranque caliente y el final, la cola.
     */
    @Test fun `los extremos no se avisan`() {
        assertNull(AvisoDeSalto.fraccion(0, 120_000))
        assertNull(AvisoDeSalto.fraccion(-5_000, 120_000))
        assertNull(AvisoDeSalto.fraccion(120_000, 120_000))
        assertNull(AvisoDeSalto.fraccion(999_000, 120_000))
    }

    // ---- El freno ----

    /**
     * Cada aviso abre una conexión NUEVA al origen. Arrastrar la barra emite decenas de posiciones,
     * y sin freno cada una abriría la suya contra el mismo CDN que estamos tratando de no molestar
     * — el mismo motivo por el que `VlcPlayer` ya frena las reaperturas de ventana.
     */
    @Test fun `no se avisa dos veces seguidas`() {
        assert(AvisoDeSalto.hayQueAvisar(esDelProxy = true, msDesdeElUltimo = 5_000))
        assert(!AvisoDeSalto.hayQueAvisar(esDelProxy = true, msDesdeElUltimo = 200))
    }

    @Test fun `el primer aviso siempre pasa`() {
        assert(AvisoDeSalto.hayQueAvisar(esDelProxy = true, msDesdeElUltimo = Long.MAX_VALUE))
    }

    /**
     * Solo tiene sentido para lo que sale de NUESTRO proxy: es el único que entiende el aviso.
     * Un torrent lo sirve `TorrentStreamServer` (que ya prioriza piezas por su cuenta) y un archivo
     * local no tiene nada que precalentar.
     */
    @Test fun `lo que no viene del proxy no se avisa`() {
        assert(!AvisoDeSalto.hayQueAvisar(esDelProxy = false, msDesdeElUltimo = 60_000))
    }
}
