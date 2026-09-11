package com.arkiv.player.ui.catalog

import com.arkiv.player.data.ditu.DituCanal
import com.arkiv.player.data.gateway.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Que la pestaña "En vivo" distinga "falló" de "no hay canales". Ver [EstadoDeCanales]. */
class EstadoDeCanalesTest {

    private val canal = DituCanal(channelId = 1, nombre = "Caracol TV", logoUrl = "", assetId = 11)

    /** Con el TV sin internet la pestaña decía "Unable to resolve host…": ahora lo dice para la persona. */
    @Test fun `un fallo da el estado de error en palabras de persona`() {
        val sinDns = GatewayException(
            "Caracol no responde: Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            java.net.UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""),
        )
        val estado = EstadoDeCanales.de(Result.failure(sinDns))

        assertEquals(EstadoDeCanales.Fallo("Caracol no respondió: sin conexión a internet"), estado)
    }

    /** Lo que no se reconoce es el genérico de canales, nunca el mensaje crudo. */
    @Test fun `un fallo que no se reconoce no muestra el mensaje crudo`() {
        val estado = EstadoDeCanales.de(Result.failure(GatewayException("JSONObject[\"resultObj\"] not found")))

        assertEquals(EstadoDeCanales.Fallo("No se pudieron cargar los canales de Caracol"), estado)
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
