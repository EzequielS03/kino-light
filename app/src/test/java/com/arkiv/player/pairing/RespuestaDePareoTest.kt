package com.arkiv.player.pairing

import com.arkiv.player.data.gateway.ErrorDeCuenta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Funciones puras de nivel de archivo de `PairingManager.kt` (Task 5): armar/interpretar lo que
 * viaja (sin cifrar todavía) en `pair_requests.payload` en cada dirección. Separadas para poder
 * probar las ramas de resultado sin PocketBase real ni la conexión SSE -- mismo criterio que
 * `estadoDeEntrada` en la Task 4.
 */
class RespuestaDePareoTest {

    // --- ida: TV -> celu, el propio deviceToken de la TV -------------------------------------

    @Test
    fun `payloadDeviceTokenTv y deviceTokenDeTv hacen el viaje de ida y vuelta`() {
        val json = JSONObject(payloadDeviceTokenTv("tv-tok-123"))

        assertEquals("tv-tok-123", deviceTokenDeTv(json))
    }

    @Test
    fun `deviceTokenDeTv devuelve null si el campo esta vacio o ausente (fila de una version vieja)`() {
        assertNull(deviceTokenDeTv(JSONObject()))
        assertNull(deviceTokenDeTv(JSONObject(mapOf("deviceToken" to ""))))
    }

    // --- vuelta: celu -> TV, resultado de CuentaApi.adoptarAparato ---------------------------

    @Test
    fun `payloadDeExito interpretado da Ok con los mismos campos`() {
        val json = JSONObject(
            payloadDeExito(
                accountId = "acc-1",
                personToken = "ptok",
                personEmail = "a@b.co",
                gatewayUrl = "https://gw.example",
            ),
        )

        val r = interpretarRespuestaDePareo(json)

        assertTrue(r is RespuestaDePareo.Ok)
        r as RespuestaDePareo.Ok
        assertEquals("acc-1", r.accountId)
        assertEquals("ptok", r.personToken)
        assertEquals("a@b.co", r.personEmail)
        assertEquals("https://gw.example", r.gatewayUrl)
    }

    // Task 8 (Paso 3): `arkivApiKey` salió del payload -- un pareo viejo que todavía la mandara
    // no debe romper el parseo (la clave de más simplemente se ignora, ver KDoc de
    // `interpretarRespuestaDePareo`).
    @Test
    fun `un payload viejo que todavia manda arkivApiKey no rompe el parseo`() {
        val json = JSONObject(
            mapOf(
                "ok" to true,
                "accountId" to "acc-1",
                "personToken" to "ptok",
                "personEmail" to "a@b.co",
                "gatewayUrl" to "https://gw.example",
                "arkivApiKey" to "clave-de-un-celu-viejo",
            ),
        )

        val r = interpretarRespuestaDePareo(json) as RespuestaDePareo.Ok

        assertEquals("acc-1", r.accountId)
        assertEquals("https://gw.example", r.gatewayUrl)
    }

    @Test
    fun `payloadDeFalla interpretado da Falla con codigo y mensaje`() {
        val json = JSONObject(payloadDeFalla("tope_alcanzado", "ya tenes 1 de 1 aparatos de tipo tv"))

        val r = interpretarRespuestaDePareo(json)

        assertTrue(r is RespuestaDePareo.Falla)
        r as RespuestaDePareo.Falla
        assertEquals("tope_alcanzado", r.codigo)
        assertEquals("ya tenes 1 de 1 aparatos de tipo tv", r.mensaje)
    }

    @Test
    fun `ok=true sin accountId personToken o personEmail se trata como Falla, no revienta`() {
        val json = JSONObject(mapOf("ok" to true, "personToken" to "t", "personEmail" to "e")) // sin accountId

        val r = interpretarRespuestaDePareo(json)

        assertTrue(r is RespuestaDePareo.Falla)
    }

    @Test
    fun `un JSON vacio (sin ok) se trata como Falla generica, no crashea el listener`() {
        val r = interpretarRespuestaDePareo(JSONObject())

        assertTrue(r is RespuestaDePareo.Falla)
        assertEquals("no se pudo completar el pareo", (r as RespuestaDePareo.Falla).mensaje)
    }

    // --- copy para la persona a partir de un ErrorDeCuenta ------------------------------------

    @Test
    fun `mensajeDeAdopcion de TopeAlcanzado menciona Mis aparatos`() {
        val mensaje = mensajeDeAdopcion(ErrorDeCuenta.TopeAlcanzado("ya tenes 1 de 1 aparatos de tipo tv"))

        assertTrue(mensaje.contains("Mis aparatos"))
    }

    @Test
    fun `mensajeDeAdopcion de AparatoDeOtraCuenta tiene copy propio`() {
        val mensaje = mensajeDeAdopcion(ErrorDeCuenta.AparatoDeOtraCuenta("ese aparato ya es de otra cuenta"))

        assertEquals("Esta TV ya está pareada con otra cuenta.", mensaje)
    }

    @Test
    fun `mensajeDeAdopcion del resto de los codigos usa el mensaje del gateway tal cual`() {
        val e = ErrorDeCuenta.SesionInvalida("token vencido")

        assertEquals("token vencido", mensajeDeAdopcion(e))
    }
}
