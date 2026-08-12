package com.arkiv.player.ui.player

import com.arkiv.player.data.GatewayConfigSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mensajeErrorVivo] es lo que decide si, al fallar la apertura de un canal, la pantalla muestra
 * el "No se pudo abrir X" genérico o la guía concreta para un TV sin vincular -- ver su KDoc para
 * el porqué (antes, un 401 por falta de config quedaba indistinguible de un canal caído de
 * verdad).
 */
class MensajeErrorVivoTest {

    @Test fun `TV sin vincular (DEFAULT) recibe una guia accionable, no el mensaje generico`() {
        val msg = mensajeErrorVivo(esTelevision = true, fuenteGateway = GatewayConfigSource.DEFAULT, nombreCanal = "Canal 5")
        assertTrue(msg.contains("Re-parear"))
        assertTrue(msg.contains("teléfono"))
    }

    @Test fun `TV ya sincronizado (SYNCED) recibe el mensaje generico -- el fallo es otra cosa`() {
        assertEquals(
            "No se pudo abrir Canal 5",
            mensajeErrorVivo(esTelevision = true, fuenteGateway = GatewayConfigSource.SYNCED, nombreCanal = "Canal 5"),
        )
    }

    @Test fun `TV con config manual (MANUAL) recibe el mensaje generico`() {
        assertEquals(
            "No se pudo abrir Canal 5",
            mensajeErrorVivo(esTelevision = true, fuenteGateway = GatewayConfigSource.MANUAL, nombreCanal = "Canal 5"),
        )
    }

    @Test fun `en el celu (no TV) siempre es el mensaje generico, aunque este en DEFAULT`() {
        assertEquals(
            "No se pudo abrir Canal 5",
            mensajeErrorVivo(esTelevision = false, fuenteGateway = GatewayConfigSource.DEFAULT, nombreCanal = "Canal 5"),
        )
    }
}
