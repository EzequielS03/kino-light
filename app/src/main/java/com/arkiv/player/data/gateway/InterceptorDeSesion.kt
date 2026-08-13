package com.arkiv.player.data.gateway

import com.arkiv.player.pocketbase.SesionDePersona
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Único punto que traduce un rechazo de identidad del gateway en un cierre de sesión (Task 7b).
 *
 * Hasta acá esa regla -distinguir "te revocaron" de "el servidor no contesta"- solo la implementaban
 * [com.arkiv.player.ui.entrada.EntradaViewModel.manejarErrorDeCuenta] y
 * `MisAparatosViewModel.manejarErrorDeSesion`, cada una a mano y solo para SU pantalla. Los clientes
 * de contenido (`ArkivApiClient`, `LiveApi`, `TmdbApi`, `SimklApi`, `MirrorApiClient`, `SubtitleApi`,
 * `MagisLinkClient`) no la tenían: un 401 en medio de una búsqueda porque la licencia se revocó
 * quedaba como un error genérico y la app seguía como si nada. Acá se reusa la MISMA regla vía
 * [ErrorDeCuenta.parsear] -no una copia-, porque un tercer lugar que la reinventara mal es
 * exactamente el riesgo que ya costó tres rondas de corrección en este proyecto.
 *
 * Vive como [Interceptor] de un [okhttp3.OkHttpClient] COMPARTIDO (`AppGraph.httpGateway`) en vez de
 * un helper que cada cliente llame a mano: así ningún cliente nuevo que hable con el gateway puede
 * sumarse olvidándose de cablear esto -alcanza con construirse sobre `httpGateway`-, y de paso deja
 * de haber seis `OkHttpClient()` sueltos sin pool de conexiones compartido.
 *
 * Tres guardas, en ESTE orden, cada una un motivo real para no tocar la sesión:
 *
 * 1. Solo importan 401/403: ningún otro código de [ErrorDeCuenta] cierra sesión (ver su tabla), así
 *    que ni vale la pena mirar el cuerpo de un 200 o de un 503/5xx.
 * 2. Solo respuestas cuyo HOST coincide con el gateway EFECTIVO ([gatewayUrl], leído en CADA
 *    respuesta -no una constante-, porque esa URL se puede cambiar desde Ajustes o llegar por el
 *    pareo con la TV): un 401 de archive.org, de un tracker o de un host de video no dice nada sobre
 *    la identidad de la persona, aunque haya salido de un cliente que también le habla al gateway
 *    (p.ej. `MirrorApiClient`, que habla con el mirror Y con el gateway desde la misma instancia).
 * 3. Nunca `/v1/cuenta/registrar`: ahí el aparato TODAVÍA no tiene cuenta de persona -el 401
 *    `sin_device` y el 400 `datos_invalidos` de ese endpoint son errores del INTENTO de alta, no de
 *    una sesión que exista para cerrar-. Se excluye por RUTA, no solo confiando en que `sin_device`
 *    no está en la lista de códigos que cierran: es la garantía explícita que pide el brief.
 *
 * El cuerpo se lee con [Response.peekBody] y no con `response.body?.string()`: esto último
 * CONSUMIRÍA el stream que el llamador real todavía necesita leer (el NDJSON de
 * `ArkivApiClient.search`, o el `ejecutar()` de cada cliente) -`peekBody` clona el buffer sin
 * gastarlo, así que la respuesta sigue intacta después de pasar por acá-.
 *
 * Los fallos de TRANSPORTE (sin red, timeout, host inalcanzable) nunca llegan a las guardas de
 * arriba: son una [java.io.IOException] que `chain.proceed()` deja pasar sin que este interceptor la
 * atrape, así que ni se entera y la sesión queda intacta -es la misma regla que ya aplican
 * [SesionDePersona.refrescar] y `CuentaApi.ejecutar`, solo que acá no hace falta código para
 * lograrlo: NO envolver `chain.proceed()` en un try/catch YA es no tocar la sesión-.
 */
class InterceptorDeSesion(
    /** URL base efectiva del gateway, leída en CADA respuesta -no una constante-: ver el punto 2
     *  de la KDoc de la clase. En producción es `{ settings.gatewayUrl.value }`. */
    private val gatewayUrl: () -> String,
    private val sesion: SesionDePersona,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 401 && response.code != 403) return response
        if (!esDelGateway(request)) return response
        if (esAltaDeCuenta(request)) return response

        val cuerpo = runCatching { response.peekBody(MAX_PEEK_BYTES).string() }.getOrDefault("")
        when (ErrorDeCuenta.parsear(cuerpo, response.code)) {
            is ErrorDeCuenta.SesionInvalida,
            is ErrorDeCuenta.LicenciaNoVigente,
            is ErrorDeCuenta.IdentidadInvalida -> sesion.cerrar()
            else -> Unit
        }
        return response
    }

    /** Compara host Y puerto (no solo el host): dos `MockWebServer` de test viven en el mismo
     *  `127.0.0.1` con puertos distintos, igual que -en producción- un `gatewayUrl` con puerto
     *  explícito de un ambiente de pruebas no debe confundirse con otro servicio en el mismo host. */
    private fun esDelGateway(request: Request): Boolean {
        val gateway = gatewayUrl().toHttpUrlOrNull() ?: return false
        val url = request.url
        return url.host == gateway.host && url.port == gateway.port
    }

    private fun esAltaDeCuenta(request: Request): Boolean =
        request.url.encodedPath.endsWith(RUTA_REGISTRAR)

    companion object {
        // Los cuerpos de error del gateway son JSON cortos (codigo+mensaje); de sobra para cualquiera.
        private const val MAX_PEEK_BYTES = 8192L
        private const val RUTA_REGISTRAR = "/v1/cuenta/registrar"
    }
}
