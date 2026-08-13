package com.arkiv.player.data.gateway

import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Alta con licencia: lo que el gateway devuelve al crear la cuenta. */
data class Registro(val userId: String, val accountId: String)

/** Alta anónima de un aparato (Task 7): lo que el gateway devuelve al crearlo con
 *  credenciales de admin -- el `id` real que le quedó en PocketBase. */
data class AltaDeAparato(val id: String, val accountId: String)

/** Resultado de sumar un aparato al cupo de la cuenta (`POST /v1/cuenta/aparatos`). */
data class Adopcion(val kind: String, val usados: Int, val tope: Int, val yaEra: Boolean)

/** Un aparato dado de alta contra la cuenta (una fila de "Mis aparatos"). */
data class Aparato(val id: String, val kind: String, val nombre: String, val ultimoUso: String)

/**
 * Todo error de `/v1/cuenta` viaja como `{"detail": {"codigo": "...", "mensaje": "..."}}`. Acá hay
 * una rama por cada `codigo` que el contrato documenta, para que quien llama ramifique comparando
 * TIPOS (`is ErrorDeCuenta.SesionInvalida`) y no strings sueltos, donde un typo pasa desapercibido.
 *
 * [Desconocido] es la red de seguridad, y no es opcional: el gateway puede sumar códigos nuevos en
 * cualquier deploy suyo sin que esta app se entere, y una respuesta puede llegar filtrada por un
 * proxy en el medio (Cloudflare, nginx) sin la forma `{codigo, mensaje}` en absoluto. Lo crítico es
 * que ninguno de esos casos ambiguos caiga por error en [SesionInvalida] o similares: eso cerraría
 * una sesión que en realidad seguía siendo válida, dejando a la persona en el mismo callejón sin
 * salida que ya costó rondas de corrección en el gateway y en `SesionDePersona` (Task 1) — sin
 * sesión, frente a una pantalla que tampoco funciona sin backend.
 */
sealed class ErrorDeCuenta(val codigo: String, val mensaje: String) : Exception(mensaje) {
    /** El token no vale (expiró, la cuenta se borró): hay que volver a la pantalla de entrada. */
    class SesionInvalida(mensaje: String) : ErrorDeCuenta("sesion_invalida", mensaje)

    /** La licencia fue revocada: volver a la entrada. */
    class LicenciaNoVigente(mensaje: String) : ErrorDeCuenta("licencia_no_vigente", mensaje)

    /** Datos de identidad corruptos del lado del servidor: volver a la entrada, con mensaje de soporte. */
    class IdentidadInvalida(mensaje: String) : ErrorDeCuenta("identidad_invalida", mensaje)

    /** El gateway no pudo responder (503). NO es un rechazo de identidad: no tocar la sesión, avisar y reintentar. */
    class BackendNoDisponible(mensaje: String) : ErrorDeCuenta("backend_no_disponible", mensaje)

    /** El código de licencia no existe, ya se usó o fue revocado. Error inline en el formulario. */
    class LicenciaInvalida(mensaje: String) : ErrorDeCuenta("licencia_invalida", mensaje)

    /** Ese email ya tiene cuenta. Error inline. */
    class EmailEnUso(mensaje: String) : ErrorDeCuenta("email_en_uso", mensaje)

    /** Email o contraseña con formato inválido. Error inline. */
    class DatosInvalidos(mensaje: String) : ErrorDeCuenta("datos_invalidos", mensaje)

    /** El aparato que llama no está dado de alta contra el gateway. */
    class SinDevice(mensaje: String) : ErrorDeCuenta("sin_device", mensaje)

    /** Este aparato ya tiene una cuenta asociada (no puede registrarse de nuevo). */
    class DeviceYaRegistrado(mensaje: String) : ErrorDeCuenta("device_ya_registrado", mensaje)

    /** Sin cupo de ese tipo de aparato: mandar a la pantalla de "Mis aparatos". */
    class TopeAlcanzado(mensaje: String) : ErrorDeCuenta("tope_alcanzado", mensaje)

    /** Ese aparato ya es de otra persona. */
    class AparatoDeOtraCuenta(mensaje: String) : ErrorDeCuenta("aparato_de_otra_cuenta", mensaje)

    /** El aparato pedido no existe. */
    class AparatoNoEncontrado(mensaje: String) : ErrorDeCuenta("aparato_no_encontrado", mensaje)

    /** El aparato no dice si es celular o TV. */
    class TipoInvalido(mensaje: String) : ErrorDeCuenta("tipo_invalido", mensaje)

    /** Otra operación sobre el mismo cupo (candado de Redis ocupado). Reintentable en el momento. */
    class CandadoOcupado(mensaje: String) : ErrorDeCuenta("candado_ocupado", mensaje)

    /**
     * Cualquier `codigo` que esta app todavía no conoce, o una respuesta donde ni siquiera se pudo
     * leer un `codigo` (sin `detail`, `detail` que no es el objeto esperado -p. ej. el string que
     * manda FastAPI por default-, o un cuerpo que no es JSON). En ese último grupo [codigo] llega
     * vacío: no hay de dónde sacarlo, y hay que decirlo en vez de inventar uno.
     */
    class Desconocido(codigo: String, mensaje: String) : ErrorDeCuenta(codigo, mensaje)

    companion object {
        /** Arma la rama que corresponde a partir de un `codigo` ya extraído del `detail`. */
        private fun desde(codigo: String, mensaje: String): ErrorDeCuenta = when (codigo) {
            "sesion_invalida" -> SesionInvalida(mensaje)
            "licencia_no_vigente" -> LicenciaNoVigente(mensaje)
            "identidad_invalida" -> IdentidadInvalida(mensaje)
            "backend_no_disponible" -> BackendNoDisponible(mensaje)
            "licencia_invalida" -> LicenciaInvalida(mensaje)
            "email_en_uso" -> EmailEnUso(mensaje)
            "datos_invalidos" -> DatosInvalidos(mensaje)
            "sin_device" -> SinDevice(mensaje)
            "device_ya_registrado" -> DeviceYaRegistrado(mensaje)
            "tope_alcanzado" -> TopeAlcanzado(mensaje)
            "aparato_de_otra_cuenta" -> AparatoDeOtraCuenta(mensaje)
            "aparato_no_encontrado" -> AparatoNoEncontrado(mensaje)
            "tipo_invalido" -> TipoInvalido(mensaje)
            "candado_ocupado" -> CandadoOcupado(mensaje)
            else -> Desconocido(codigo, mensaje)
        }

        /**
         * Punto de entrada real: recibe el cuerpo crudo de una respuesta no exitosa y decide la
         * rama. Deliberadamente pesimista -cualquier forma que no sea EXACTAMENTE
         * `{"detail":{"codigo":..., "mensaje":...}}` cae en [Desconocido]-, porque el costo de
         * adivinar mal acá es cerrar una sesión que seguía siendo válida.
         */
        internal fun parsear(cuerpo: String, httpCode: Int): ErrorDeCuenta {
            val detail = runCatching { JSONObject(cuerpo).optJSONObject("detail") }.getOrNull()
                ?: return Desconocido("", cuerpo.ifBlank { "HTTP $httpCode" }.take(200))
            val codigo = detail.optString("codigo")
            val mensaje = detail.optString("mensaje").ifBlank { "HTTP $httpCode" }
            return desde(codigo, mensaje)
        }
    }
}

/**
 * Cliente de `/v1/cuenta`: registro con licencia y el ciclo de vida de los aparatos de la cuenta.
 *
 * Sigue la forma de [ArkivApiClient] para `baseUrl` (proveedor en vez de valor fijo, para que un
 * cambio de gateway -[com.arkiv.player.data.SettingsStore]- se refleje sin reconstruir el
 * cliente). A diferencia de aquel, acá NO hay streaming: son pedidos JSON cortos, así que los
 * timeouts son finitos en lectura (no `0`).
 *
 * El `Authorization` es la parte que importa: [altaAparato] no manda ninguno -el aparato todavía
 * no existe, ver su KDoc-, [registrar] identifica al APARATO que todavía no tiene cuenta (por eso
 * usa [deviceToken], no la sesión), y los otros tres identifican a la PERSONA ya autenticada (por
 * eso usan [sesion]). Mezclarlos es un agujero de suplantación -del lado del servidor ya se
 * corrigió una vez exactamente eso-, así que cada método usa una sola de las tres fuentes, nunca
 * otra. Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- este cliente ya no manda ninguna llave de
 * build, solo las credenciales de sesión/aparato de arriba.
 *
 * Desde que sacar un aparato tiene que desconectarlo de verdad (spec de "Mis aparatos"), los tres
 * métodos que identifican a la PERSONA mandan ADEMÁS `X-Arkiv-Device` con [deviceToken]: el
 * gateway valida las dos credenciales -quién sos y desde qué fierro- y exige que el aparato siga
 * siendo de esa cuenta. [registrar] no cambia: ahí el aparato ya viaja en `Authorization`, porque
 * todavía no hay ninguna persona a la que atarlo.
 */
class CuentaApi(
    private val baseUrl: () -> String,
    /** Token del APARATO (el que ya usa `DeviceAuthManager`/`DeviceStore.token()`). Solo lo usa
     *  [registrar]: antes de tener cuenta, la única identidad que existe es la del fierro. */
    private val deviceToken: () -> String?,
    /** Sesión de la PERSONA (Task 1). La usan los tres métodos de aparatos, nunca [registrar]. */
    private val sesion: SesionDePersona,
    http: OkHttpClient,
) {
    private val http = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    private fun pedido(url: String, token: String?, conDevice: Boolean = false): Request.Builder {
        val b = Request.Builder().url(url)
        token?.let { b.header("Authorization", it) }
        // conDevice: solo lo mandan los tres métodos que identifican a la PERSONA
        // ([adoptarAparato], [listarAparatos], [sacarAparato]) -- [registrar] ya manda el
        // token del aparato en `Authorization` (arriba), así que no le hace falta esta
        // cabecera aparte. Si [deviceToken] todavía no está disponible (null), se omite la
        // cabecera en vez de fallar acá: el gateway la va a rechazar con `sin_device`
        // ([ErrorDeCuenta.SinDevice]), que la app ya sabe traducir.
        if (conDevice) deviceToken()?.let { b.header("X-Arkiv-Device", it) }
        return b
    }

    /**
     * Alta anónima de un aparato (Task 7): reemplaza el `createRecord` directo a PocketBase que
     * hacía [com.arkiv.player.pocketbase.DeviceAuthManager.ensureBootstrapped] -- `devices.createRule`
     * ya no acepta creaciones sin autenticar, así que el gateway lo crea con sus propias
     * credenciales de admin. Sin `Authorization` ni `X-Arkiv-Device`: el aparato todavía no
     * existe, no hay ninguna credencial propia que mandar. `email`/`password` los generó
     * [com.arkiv.player.pocketbase.DeviceIdentityFactory] -al azar, local-; el aparato ya los
     * tiene, así que la respuesta del gateway no necesita repetirlos.
     */
    suspend fun altaAparato(
        accountId: String,
        kind: String,
        email: String,
        password: String,
        deviceName: String,
    ): AltaDeAparato = withContext(Dispatchers.IO) {
        val body = JSONObject(
            mapOf(
                "accountId" to accountId, "kind" to kind, "email" to email,
                "password" to password, "deviceName" to deviceName,
            ),
        ).toString().toRequestBody(jsonType)
        val req = pedido("${baseUrl()}/v1/cuenta/aparatos/alta", token = null).post(body).build()
        val o = JSONObject(ejecutar(req))
        AltaDeAparato(id = o.getString("id"), accountId = o.getString("accountId"))
    }

    suspend fun registrar(email: String, password: String, licencia: String): Registro =
        withContext(Dispatchers.IO) {
            val body = JSONObject(mapOf("email" to email, "password" to password, "licencia" to licencia))
                .toString().toRequestBody(jsonType)
            val req = pedido("${baseUrl()}/v1/cuenta/registrar", deviceToken()).post(body).build()
            val o = JSONObject(ejecutar(req))
            Registro(userId = o.getString("userId"), accountId = o.getString("accountId"))
        }

    suspend fun adoptarAparato(deviceToken: String): Adopcion = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("deviceToken" to deviceToken)).toString().toRequestBody(jsonType)
        val req = pedido("${baseUrl()}/v1/cuenta/aparatos", sesion.token(), conDevice = true).post(body).build()
        val o = JSONObject(ejecutar(req))
        Adopcion(
            kind = o.getString("kind"),
            usados = o.getInt("usados"),
            tope = o.getInt("tope"),
            yaEra = o.optBoolean("yaEra", false),
        )
    }

    suspend fun listarAparatos(): List<Aparato> = withContext(Dispatchers.IO) {
        val req = pedido("${baseUrl()}/v1/cuenta/aparatos", sesion.token(), conDevice = true).get().build()
        val arr = JSONObject(ejecutar(req)).optJSONArray("aparatos") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Aparato(
                id = o.getString("id"),
                kind = o.getString("kind"),
                nombre = o.optString("nombre"),
                ultimoUso = o.optString("ultimoUso"),
            )
        }
    }

    suspend fun sacarAparato(id: String) {
        withContext(Dispatchers.IO) {
            val req = pedido("${baseUrl()}/v1/cuenta/aparatos/$id", sesion.token(), conDevice = true)
                .delete().build()
            ejecutar(req, allowEmpty = true)
        }
    }

    /**
     * Ejecuta el pedido y devuelve el cuerpo crudo si respondió 2xx.
     *
     * La falla de RED (sin conexión, timeout, host inalcanzable) se traduce acá mismo a
     * [ErrorDeCuenta.BackendNoDisponible]: es exactamente la misma situación que el `codigo` del
     * mismo nombre -no se pudo hablar con el gateway-, así que el llamador no tiene que distinguir
     * un [java.io.IOException] de transporte de un 503 con cuerpo: ambos llegan como la misma rama,
     * y ninguno de los dos toca la sesión.
     */
    private fun ejecutar(request: Request, allowEmpty: Boolean = false): String {
        val respuesta: Response = runCatching { http.newCall(request).execute() }
            .getOrElse { throw ErrorDeCuenta.BackendNoDisponible("no se pudo conectar con el gateway") }
        respuesta.use { r ->
            val cuerpo = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ErrorDeCuenta.parsear(cuerpo, r.code)
            if (cuerpo.isBlank() && !allowEmpty) {
                throw ErrorDeCuenta.Desconocido("", "respuesta vacía inesperada (HTTP ${r.code})")
            }
            return cuerpo
        }
    }
}
