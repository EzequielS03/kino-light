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

/** Alta anónima de un aparato (Task 7): lo que el gateway devuelve al crearlo con
 *  credenciales de admin -- el `id` real que le quedó en PocketBase. */
data class AltaDeAparato(val id: String, val accountId: String)

/**
 * Resultado del login de un aparato nuevo (`POST /v1/cuenta/entrar`).
 *
 * [desvinculado] es el id del aparato que SALIÓ de la cuenta para hacerle lugar a este —
 * el más viejo del mismo tipo, cuando el cupo ya estaba lleno. Es `null` cuando no hubo que
 * sacar a nadie (había lugar, o este aparato ya era de la cuenta).
 */
data class Entrada(
    val userId: String,
    val accountId: String,
    val kind: String,
    val usados: Int,
    val tope: Int,
    val yaEra: Boolean,
    val desvinculado: String?,
)

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

    /**
     * La licencia de la cuenta fue revocada DESPUÉS de existir (Cristian la dio de baja a mano):
     * volver a la entrada. NO es parte del registro -ver [Desconocido]/`desde()`, este código lo
     * puede devolver cualquier pedido autenticado del gateway, no solo `/v1/cuenta/registrar`- así
     * que sobrevivió a la poda de licencias/registro (Task 7): [InterceptorDeSesion] y
     * `EntradaViewModel.manejarErrorDeCuenta`/`MisAparatosViewModel.manejarErrorDeSesion` siguen
     * cerrando la sesión con esta rama para una cuenta YA logueada.
     */
    class LicenciaNoVigente(mensaje: String) : ErrorDeCuenta("licencia_no_vigente", mensaje)

    /** Datos de identidad corruptos del lado del servidor: volver a la entrada, con mensaje de soporte. */
    class IdentidadInvalida(mensaje: String) : ErrorDeCuenta("identidad_invalida", mensaje)

    /** El gateway no pudo responder (503). NO es un rechazo de identidad: no tocar la sesión, avisar y reintentar. */
    class BackendNoDisponible(mensaje: String) : ErrorDeCuenta("backend_no_disponible", mensaje)

    /** Email o contraseña con formato inválido. Error inline. */
    class DatosInvalidos(mensaje: String) : ErrorDeCuenta("datos_invalidos", mensaje)

    /** El aparato que llama no está dado de alta contra el gateway. */
    class SinDevice(mensaje: String) : ErrorDeCuenta("sin_device", mensaje)

    /**
     * El email o la contraseña no son (login, `POST /v1/cuenta/entrar`). Error inline.
     *
     * Rama propia y NO [SesionInvalida], aunque las dos lleguen como 401: aquella cierra la sesión
     * y manda a la pantalla de entrada, que es justo donde la persona está parada tipeando cuando
     * pasa esto. Confundirlas convierte un typo en un rebote inexplicable.
     */
    class CredencialesInvalidas(mensaje: String) : ErrorDeCuenta("credenciales_invalidas", mensaje)

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
            "datos_invalidos" -> DatosInvalidos(mensaje)
            "sin_device" -> SinDevice(mensaje)
            "credenciales_invalidas" -> CredencialesInvalidas(mensaje)
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
 * Cliente de `/v1/cuenta`: login de un aparato nuevo y el ciclo de vida de los aparatos de la
 * cuenta. El registro con licencia (`registrar()`, la `data class Registro`, los errores
 * `licencia_invalida`/`email_en_uso`/`device_ya_registrado`) se sacó en Task 7 -poda de "Arkiv
 * Light": ya no se dan de alta cuentas nuevas desde la app, solo se entra a una que ya existe-.
 *
 * `baseUrl` es un proveedor y no un valor fijo, para que un cambio de gateway
 * -[com.arkiv.player.data.SettingsStore]- se refleje sin reconstruir el cliente. Acá NO hay
 * streaming: son pedidos JSON cortos, así que los timeouts son finitos en lectura (no `0`).
 *
 * El `Authorization` es la parte que importa: [altaAparato] no manda ninguno -el aparato todavía
 * no existe, ver su KDoc-, [entrar] identifica al APARATO que todavía no es de ninguna cuenta (por
 * eso usa [deviceToken], no la sesión), y los otros dos identifican a la PERSONA ya autenticada
 * (por eso usan [sesion]). Mezclarlos es un agujero de suplantación -del lado del servidor ya se
 * corrigió una vez exactamente eso-, así que cada método usa una sola de las tres fuentes, nunca
 * otra. Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- este cliente ya no manda ninguna llave de
 * build, solo las credenciales de sesión/aparato de arriba.
 *
 * Desde que sacar un aparato tiene que desconectarlo de verdad (spec de "Mis aparatos"), los dos
 * métodos que identifican a la PERSONA mandan ADEMÁS `X-Arkiv-Device` con [deviceToken]: el
 * gateway valida las dos credenciales -quién sos y desde qué fierro- y exige que el aparato siga
 * siendo de esa cuenta. [entrar] no manda esa cabecera: ahí el aparato ya viaja en `Authorization`,
 * porque todavía no hay ninguna persona a la que atarlo.
 */
class CuentaApi(
    private val baseUrl: () -> String,
    /** Token del APARATO (el que ya usa `DeviceAuthManager`/`DeviceStore.token()`). Solo lo usa
     *  [entrar]: antes de tener cuenta, la única identidad que existe es la del fierro. */
    private val deviceToken: () -> String?,
    /** Sesión de la PERSONA (Task 1). La usan [listarAparatos]/[sacarAparato], nunca [entrar]. */
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
        // conDevice: solo lo mandan los métodos que identifican a la PERSONA
        // ([listarAparatos], [sacarAparato]) -- [entrar] ya manda el
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

    /**
     * Login de un aparato que TODAVÍA no es de ninguna cuenta.
     *
     * Existe porque el viejo camino de sumar un aparato al cupo (`POST /v1/cuenta/aparatos`,
     * borrado en Task 5 junto con el pareo QR) no podía correr en un aparato nuevo: el gateway le
     * exigía sesión de persona Y que el aparato que llama ya fuera de la cuenta — que es
     * exactamente lo que ese camino venía a hacer. Medido en producción el 2026-08-14 en el
     * Google TV: `alta -> 201`, `aparatos -> 401`, en bucle, y el login rebotando a la pantalla de
     * login para siempre.
     *
     * Por eso las credenciales van en el cuerpo y el token del APARATO en `Authorization`: la única
     * identidad que existe antes de entrar es la del fierro, y la de la persona es su contraseña.
     * NO usa [sesion] — puede no haber ninguna.
     */
    suspend fun entrar(email: String, password: String): Entrada = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("email" to email, "password" to password))
            .toString().toRequestBody(jsonType)
        val req = pedido("${baseUrl()}/v1/cuenta/entrar", deviceToken()).post(body).build()
        val o = JSONObject(ejecutar(req))
        Entrada(
            userId = o.optString("userId"),
            accountId = o.getString("accountId"),
            kind = o.optString("kind"),
            usados = o.optInt("usados"),
            tope = o.optInt("tope"),
            yaEra = o.optBoolean("yaEra", false),
            // `isNull` antes de leer: `optString` sobre un JSON null devuelve la CADENA "null",
            // no null. Ya mordió una vez en el catálogo, con un anime llamado literalmente "null".
            desvinculado = if (o.isNull("desvinculado")) null else o.optString("desvinculado"),
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
