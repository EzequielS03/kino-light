package com.arkiv.player.ui.tv

import com.arkiv.player.data.ditu.DituCanal
import com.arkiv.player.data.gateway.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que la pestaña "En vivo" distinga "falló" de "no hay canales". Ver [EstadoDeCanales]. */
class EstadoDeCanalesTest {

    private val canal = DituCanal(channelId = 1, nombre = "Caracol TV", logoUrl = "", assetId = 11)

    @Test fun `un fallo da el estado de error con su mensaje`() {
        val estado = EstadoDeCanales.de(Result.failure(GatewayException("Caracol no responde")))

        assertEquals(EstadoDeCanales.Fallo("Caracol no responde"), estado)
    }

    /** Un error sin mensaje igual se dice: no puede quedar como "no hay canales". */
    @Test fun `un fallo sin mensaje igual es un error`() {
        val estado = EstadoDeCanales.de(Result.failure(RuntimeException()))

        assertTrue(estado is EstadoDeCanales.Fallo && estado.mensaje.isNotBlank())
    }

    @Test fun `una lista vacia da el estado de vacio`() {
        assertSame(EstadoDeCanales.Vacio, EstadoDeCanales.de(Result.success(emptyList())))
    }

    @Test fun `con canales da la lista`() {
        assertEquals(EstadoDeCanales.Listos(listOf(canal)), EstadoDeCanales.de(Result.success(listOf(canal))))
    }
}
