package com.arkiv.player.pairing

import android.util.Log
import com.arkiv.player.data.gateway.Adopcion
import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceSession
import com.arkiv.player.pocketbase.EstadoDeSesion
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface PairingState {
    data object Idle : PairingState
    data object WaitingScan : PairingState
    data object Claiming : PairingState
    data class Paired(val accountId: String) : PairingState
    data class Error(val msg: String) : PairingState

    /**
     * Sin cupo de TVs en la licencia (Task 5, `tope_alcanzado`). A propósito NO es un [Error]:
     * reintentar el mismo pareo no cambia nada -- primero hay que sacar un aparato desde
     * "Mis aparatos" (Task 6, todavía no existe) -- así que la UI que distinga esto no debería
     * ofrecer "reintentar" como si fuera un fallo transitorio.
     */
    data class TopeAlcanzado(val msg: String) : PairingState
}

/**
 * Lo que el celu deja cifrado en `pair_requests.payload` tras intentar
 * [CuentaApi.adoptarAparato] (Task 5), ya descifrado y parseado. Separado de [aplicarRespuesta]
 * como función de nivel de archivo -mismo motivo que `estadoDeEntrada` en la Task 4- para poder
 * probar las dos ramas sin PocketBase real ni la conexión SSE de por medio.
 */
sealed interface RespuestaDePareo {
    data class Ok(
        val accountId: String,
        val personToken: String,
        val personEmail: String,
        val gatewayUrl: String,
        val arkivApiKey: String,
    ) : RespuestaDePareo

    /** [codigo] es el `codigo` de [ErrorDeCuenta] que causó el fallo (o vacío si la respuesta
     *  vino incompleta/corrupta) -- lo usa [aplicarRespuesta] para distinguir `tope_alcanzado`
     *  (mensaje especial, ver [PairingState.TopeAlcanzado]) del resto. */
    data class Falla(val codigo: String, val mensaje: String) : RespuestaDePareo
}

/** Arma el JSON (sin cifrar todavía) que el TV manda al crear el pair_request: su PROPIO
 *  deviceToken. Es una credencial -autentica como ese aparato-, así que quien la usa DEBE
 *  cifrarla con [PairCrypto.encrypt] antes de escribirla en la fila; nunca en claro. */
fun payloadDeviceTokenTv(deviceToken: String): String =
    JSONObject(mapOf("deviceToken" to deviceToken)).toString()

/** Extrae el deviceToken del TV de su JSON (ya descifrado). Null si vino vacío/corrupto -- una
 *  fila de una versión vieja de la app, por ejemplo, donde `payload` tenía otra forma. */
fun deviceTokenDeTv(json: JSONObject): String? = json.optString("deviceToken").ifBlank { null }

/** Arma el JSON de éxito que el celu deja para el TV tras [CuentaApi.adoptarAparato]. */
fun payloadDeExito(accountId: String, personToken: String, personEmail: String, gatewayUrl: String, arkivApiKey: String): String =
    JSONObject(
        mapOf(
            "ok" to true,
            "accountId" to accountId,
            "personToken" to personToken,
            "personEmail" to personEmail,
            "gatewayUrl" to gatewayUrl,
            "arkivApiKey" to arkivApiKey,
        ),
    ).toString()

/** Arma el JSON de fallo que el celu deja para el TV cuando [CuentaApi.adoptarAparato] rechaza
 *  el pareo (`tope_alcanzado`, `aparato_de_otra_cuenta`, ...): sin esto la TV se queda esperando
 *  para siempre un `payload` que nunca llega. [codigo]/[mensaje] no son secretos -- no necesitan
 *  viajar por ningún otro resguardo además del cifrado que ya lleva todo el `payload`. */
fun payloadDeFalla(codigo: String, mensaje: String): String =
    JSONObject(mapOf("ok" to false, "errorCode" to codigo, "mensaje" to mensaje)).toString()

/** Descifra e interpreta la respuesta del celu del lado de la TV. Ver [RespuestaDePareo]. */
fun interpretarRespuestaDePareo(json: JSONObject): RespuestaDePareo {
    if (!json.optBoolean("ok", false)) {
        return RespuestaDePareo.Falla(
            codigo = json.optString("errorCode"),
            mensaje = json.optString("mensaje").ifBlank { "no se pudo completar el pareo" },
        )
    }
    val accountId = json.optString("accountId")
    val personToken = json.optString("personToken")
    val personEmail = json.optString("personEmail")
    if (accountId.isBlank() || personToken.isBlank() || personEmail.isBlank()) {
        // "ok" pero sin lo mínimo para operar: tratarlo como falla en vez de reventar con un
        // getString() -- más vale un mensaje de "pareo incompleto" que un crash del listener.
        return RespuestaDePareo.Falla(codigo = "", mensaje = "respuesta de pareo incompleta")
    }
    return RespuestaDePareo.Ok(
        accountId = accountId,
        personToken = personToken,
        personEmail = personEmail,
        gatewayUrl = json.optString("gatewayUrl", ""),
        arkivApiKey = json.optString("arkivApiKey", ""),
    )
}

/** Copy pensado para la PERSONA a partir de un [ErrorDeCuenta] de [CuentaApi.adoptarAparato].
 *  `tope_alcanzado`/`aparato_de_otra_cuenta` tienen redacción propia (el mensaje del gateway es
 *  correcto pero seco, sin decir qué hacer); el resto usa el mensaje del gateway tal cual. */
fun mensajeDeAdopcion(e: ErrorDeCuenta): String = when (e) {
    is ErrorDeCuenta.TopeAlcanzado ->
        "Ya llegaste al límite de TVs de tu cuenta. Sacá un aparato desde \"Mis aparatos\", en Ajustes del celular, para parear este."
    is ErrorDeCuenta.AparatoDeOtraCuenta -> "Esta TV ya está pareada con otra cuenta."
    else -> e.mensaje
}

private const val REINTENTOS_CANDADO = 4

// 400ms * 4 = 1.6s de espera total como mucho, bien por debajo de los 5000ms de TTL del candado
// del lado del servidor (ver `_TTL_CANDADO_MS` en `arkiv_api/identidad/cuentas.py`): si la otra
// operación que lo tiene tarda lo esperado (~200ms por pedido a PocketBase, tres pedidos
// secuenciales), un par de reintentos alcanza sobrado.
private const val ESPERA_ENTRE_REINTENTOS_MS = 400L

/**
 * Llama [CuentaApi.adoptarAparato], reintentando en silencio si el candado de Redis está
 * ocupado (`candado_ocupado`, Task 5: "reintentar solo, sin molestar a nadie" -- es OTRA
 * adopción para la misma cuenta+tipo en curso ahora mismo, no un rechazo). Cualquier otro
 * [ErrorDeCuenta] (`tope_alcanzado`, `aparato_de_otra_cuenta`, ...) se relanza de inmediato: no
 * es transitorio, reintentar no cambia el resultado. Top-level (no método de [PairingManager])
 * para poder probarlo con un [CuentaApi] real contra `MockWebServer`, sin instanciar el resto
 * del pareo.
 */
suspend fun adoptarConReintento(
    cuentaApi: CuentaApi,
    tvToken: String,
    intentos: Int = REINTENTOS_CANDADO,
    esperaMs: Long = ESPERA_ENTRE_REINTENTOS_MS,
): Adopcion {
    var ultimoCandado: ErrorDeCuenta.CandadoOcupado? = null
    repeat(intentos) {
        try {
            return cuentaApi.adoptarAparato(tvToken)
        } catch (e: ErrorDeCuenta.CandadoOcupado) {
            ultimoCandado = e
            delay(esperaMs)
        }
    }
    throw ultimoCandado!!
}

/**
 * Empareja celu↔TV por QR. El TV crea un pair_request (con SU PROPIO deviceToken cifrado, Task
 * 5), escucha por realtime y, cuando el celu contesta, aplica accountId + sesión de persona
 * compartida. El celu escanea, descifra el deviceToken de la TV y llama
 * [CuentaApi.adoptarAparato]: es el gateway -no PocketBase directo- quien mueve ese aparato a la
 * cuenta, así que el alta cuenta contra `maxTvs` con el candado de Redis que ya existe.
 *
 * Antes de la Task 5 el celu FABRICABA un device TV nuevo con un `createRecord` directo: el
 * gateway nunca se enteraba y el tope de "1 TV por licencia" no se aplicaba (`devices.createRule`
 * sigue abierta hasta la Task 7). Ver la expansión de la Task 5 en
 * `.superpowers/sdd/2026-08-12-login-obligatorio-en-la-app/task-5-brief.md`.
 */
class PairingManager(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val cuentaApi: CuentaApi,
    private val sesion: SesionDePersona,
    /**
     * Config de gateway efectiva de ESTE aparato (para propagarla al TV) + el hook para aplicar
     * la que llegue sincronizada. Lambdas en vez de [com.arkiv.player.data.SettingsStore]
     * directo -mismo patrón que [CuentaApi]- para poder construir y probar [PairingManager]
     * entero contra `MockWebServer`, sin Context real: `SettingsStore` pide
     * `SharedPreferences` y este módulo no tiene Robolectric (ver `GatewayConfigPrecedenceTest`).
     */
    private val gatewayUrl: () -> String,
    private val arkivApiKey: () -> String,
    private val applySyncedGatewayConfig: (gatewayUrl: String, arkivApiKey: String) -> Boolean,
    private val setTvLinked: (Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val col = PairingConfig.COLLECTION

    private var pairingJob: Job? = null
    private var pendingRequestId: String? = null

    // ---------- Rol TV ----------

    /**
     * Crea el pair_request, arranca la escucha realtime y devuelve el string para el QR.
     * Devuelve null (y publica [PairingState.Error]) si la configuración síncrona falla,
     * por ejemplo sin sesión PocketBase (offline-first) o si falla la creación del registro.
     */
    suspend fun startTvPairing(deviceName: String): String? {
        val session: DeviceSession
        val code: String
        val recordId: String
        try {
            session = deviceAuth.ensureBootstrapped()
                ?: throw IllegalStateException("sin sesión PocketBase")
            code = PairCode.generate()
            val codeHash = PairCrypto.codeHash(code)
            val expiresAt = java.time.Instant.now().plusMillis(PairingConfig.TTL_MS).toString()
            // El TV manda su PROPIO deviceToken cifrado con el code (Task 5): es una credencial
            // -autentica como este aparato-, así que viaja por el mismo canal cifrado que antes
            // usaban las credenciales que fabricaba el celu, nunca en claro en la fila. El celu
            // lo descifra al escanear y llama a CuentaApi.adoptarAparato con él.
            val tvPayloadCipher = PairCrypto.encrypt(payloadDeviceTokenTv(session.token), code)
            recordId = client.createRecord(
                collection = col,
                fields = mapOf(
                    "codeHash" to codeHash,
                    "status" to "pending",
                    "tvName" to deviceName,
                    "expiresAt" to expiresAt,
                    "payload" to tvPayloadCipher,
                ),
                token = session.token,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = PairingState.Error(e.message ?: "fallo al iniciar el pareo")
            return null
        }
        pendingRequestId = recordId
        _state.value = PairingState.WaitingScan
        // Escuchar el propio pair_request; al claimed, descifrar y aplicar la respuesta del celu.
        pairingJob?.cancel()
        pairingJob = scope.launch {
            realtime.subscribe(listOf("$col/$recordId")).collect { ev ->
                if (ev.record.optString("status") == "claimed") {
                    val payload = ev.record.optString("payload")
                    if (payload.isNotBlank()) {
                        runCatching { aplicarRespuesta(payload, code, recordId) }
                            .onFailure {
                                // Re-lanzar cancelaciones (p. ej. al cancelar la suscripción tras
                                // aplicar la respuesta): no son un error del pareo y no deben
                                // mostrarse como tal.
                                if (it is CancellationException) throw it
                                _state.value = PairingState.Error(it.message ?: "fallo al parear")
                            }
                    }
                }
            }
        }
        return QrPayloadCodec.encode(QrPayload(PocketBaseConfig.BASE_URL, code))
    }

    /** Cancela la escucha y borra el pair_request pendiente (al salir de la pantalla sin parear). */
    fun stopTvPairing() {
        pairingJob?.cancel()
        val id = pendingRequestId ?: return
        pendingRequestId = null
        scope.launch {
            runCatching {
                val token = deviceAuth.session.value?.token ?: return@launch
                client.deleteRecord(PairingConfig.COLLECTION, id, token)
            }
        }
        if (_state.value !is PairingState.Paired) _state.value = PairingState.Idle
    }

    private suspend fun aplicarRespuesta(payloadCipher: String, code: String, recordId: String) {
        _state.value = PairingState.Claiming
        val json = JSONObject(PairCrypto.decrypt(payloadCipher, code))
        when (val r = interpretarRespuestaDePareo(json)) {
            is RespuestaDePareo.Falla -> {
                _state.value = if (r.codigo == "tope_alcanzado") {
                    PairingState.TopeAlcanzado(r.mensaje)
                } else {
                    PairingState.Error(r.mensaje)
                }
                pendingRequestId = null
                pairingJob?.cancel()
            }
            is RespuestaDePareo.Ok -> {
                // El device YA es este mismo aparato -nunca cambia de identidad-: el gateway ya
                // movió su accountId del lado del servidor (CuentaApi.adoptarAparato, con el
                // candado que cuenta contra el tope de TVs). Acá solo hay que reflejarlo en la
                // sesión viva; repetir el PATCH por este camino sería redundante (ver KDoc de
                // DeviceAuthManager.aplicarAccountIdAdoptado).
                val deviceSession = deviceAuth.aplicarAccountIdAdoptado(r.accountId)
                // Sin login manual (spec), la TV nunca escribe su propia contraseña de persona:
                // adopta el MISMO token de sesión que ya tenía vigente el celu.
                sesion.aplicarSesionCompartida(r.personToken, r.personEmail)
                val configAplicada = applySyncedGatewayConfig(r.gatewayUrl, r.arkivApiKey)
                // Limpiar el pair_request (un solo uso).
                runCatching { client.deleteRecord(col, recordId, deviceSession.token) }
                pendingRequestId = null
                _state.value = PairingState.Paired(deviceSession.accountId)
                // OJO: nunca loguear gatewayUrl/arkivApiKey/personToken acá (son credenciales) --
                // solo si el pareo terminó actualizándolas o no.
                Log.i("ArkivPair", "TV pareado correctamente (config de gateway ${if (configAplicada) "sincronizada" else "sin cambios"})")
                // Ya aplicamos la respuesta: detener la suscripción realtime.
                pairingJob?.cancel()
            }
        }
    }

    // ---------- Rol celu ----------

    /**
     * Escanea el QR, descifra el deviceToken que la TV dejó al crear el pair_request y lo
     * adopta vía el gateway ([CuentaApi.adoptarAparato], Task 5): es el único camino que cuenta
     * contra el tope de TVs. Ya no crea ningún device nuevo a mano.
     */
    suspend fun claimFromQr(qr: String): Boolean {
        val payload = QrPayloadCodec.decode(qr) ?: run {
            _state.value = PairingState.Error("QR inválido"); return false
        }
        val session = deviceAuth.ensureBootstrapped() ?: run {
            _state.value = PairingState.Error("sin sesión"); return false
        }
        val personEmail = (sesion.estado.value as? EstadoDeSesion.Con)?.email
        val personToken = sesion.token()
        if (personEmail == null || personToken == null) {
            // No debería pasar: el gate de MainActivity (Task 4) no deja llegar acá sin sesión
            // de persona. Defensivo por si algún llamador futuro escanea sin pasar por el gate.
            _state.value = PairingState.Error("sin sesión de cuenta"); return false
        }
        _state.value = PairingState.Claiming
        return runCatching {
            // 1) Buscar el pair_request pendiente y validar que no haya expirado, ANTES de
            //    adoptar nada: si falta o expiró, fallamos sin gastar cupo de la licencia.
            val codeHash = PairCrypto.codeHash(payload.code)
            val reqs = client.listRecords(col, "codeHash='$codeHash' && status='pending'", session.token)
            val req = reqs.firstOrNull()
                ?: throw IllegalStateException("pair_request no encontrado o expirado")
            val expiresAt = req.optString("expiresAt")
            val expired = expiresAt.isNotBlank() && runCatching {
                java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.now())
            }.getOrDefault(false)
            if (expired) {
                _state.value = PairingState.Error("El código expiró")
                return@runCatching false
            }
            val reqId = req.getString("id")

            // 2) Descifrar el deviceToken que la TV dejó al crear la fila (Task 5): es una
            //    credencial, viaja por el mismo canal cifrado que antes usaban
            //    accountId/email/password del device que este celu fabricaba -- nunca en claro.
            val tvSecretCipher = req.optString("payload")
            val tvToken = tvSecretCipher.takeIf { it.isNotBlank() }
                ?.let { runCatching { deviceTokenDeTv(JSONObject(PairCrypto.decrypt(it, payload.code))) }.getOrNull() }
                ?: throw IllegalStateException("QR de una versión vieja de la TV")

            // 3) Adoptar el aparato vía el gateway: el único camino que cuenta contra el tope de
            //    TVs de la licencia, con el candado de Redis que ya existe.
            val adopcion: Adopcion
            try {
                adopcion = adoptarConReintento(cuentaApi, tvToken)
            } catch (e: ErrorDeCuenta) {
                val mensaje = mensajeDeAdopcion(e)
                _state.value = if (e is ErrorDeCuenta.TopeAlcanzado) {
                    PairingState.TopeAlcanzado(mensaje)
                } else {
                    PairingState.Error(mensaje)
                }
                // Avisarle a la TV también (Task 5: "tope_alcanzado -> mensaje claro en la TV Y
                // en el celular"): sin esto se queda esperando para siempre un payload que nunca
                // llega. Best-effort -- si esto falla, la TV eventualmente vence por expiresAt.
                runCatching {
                    val cipher = PairCrypto.encrypt(payloadDeFalla(e.codigo, mensaje), payload.code)
                    client.updateRecord(col, reqId, mapOf("payload" to cipher, "status" to "claimed"), session.token)
                }
                return@runCatching false
            }

            // 4) Cifrar la respuesta con el code y escribirla en el pair_request. Sumamos la
            //    config de gateway EFECTIVA de este celu (URL + llave que usa ahora mismo) y el
            //    token de sesión de la PERSONA que este celu ya tiene vigente: la TV nunca
            //    escribe su propia contraseña (sin login manual, spec), así que adopta el MISMO
            //    token -- ver SesionDePersona.aplicarSesionCompartida.
            val secret = payloadDeExito(
                accountId = session.accountId,
                personToken = personToken,
                personEmail = personEmail,
                gatewayUrl = gatewayUrl(),
                arkivApiKey = arkivApiKey(),
            )
            val cipher = PairCrypto.encrypt(secret, payload.code)
            client.updateRecord(col, reqId, mapOf("payload" to cipher, "status" to "claimed"), session.token)
            // Rol celu: a partir de acá este teléfono tiene TV. Se marca aquí (y no solo cuando
            // RemoteController resuelve el device) para que el icono del control remoto y el
            // diálogo aparezcan al volver del escáner, sin esperar al poll de 5 s.
            setTvLinked(true)
            _state.value = PairingState.Paired(session.accountId)
            // `adopcion` no se usa más allá de este punto -- ya cumplió su función (contar
            // contra el tope). Queda nombrada por si un log de diagnóstico la necesita.
            adopcion
            true
        }.getOrElse {
            // Re-lanzar cancelaciones (p. ej. si la pantalla del escáner se cierra): no son un
            // fallo del pareo y no deben mostrarse como error al usuario.
            if (it is CancellationException) throw it
            _state.value = PairingState.Error(it.message ?: "fallo al parear")
            false
        }
    }
}
