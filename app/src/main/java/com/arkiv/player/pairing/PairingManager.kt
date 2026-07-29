package com.arkiv.player.pairing

import android.util.Log
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.DeviceIdentityFactory
import com.arkiv.player.pocketbase.DeviceSession
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import com.arkiv.player.pocketbase.SecureDeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
}

/**
 * Empareja celu↔TV por QR. El TV crea un pair_request y escucha por realtime; el celu
 * escanea, crea el device TV en su cuenta y escribe las credenciales cifradas. El TV
 * descifra, adopta esa identidad (misma cuenta que el celu) y re-autentica.
 */
class PairingManager(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime,
    private val store: SecureDeviceStore,
    private val deviceAuth: DeviceAuthManager,
    private val settings: SettingsStore,
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
            recordId = client.createRecord(
                collection = col,
                fields = mapOf(
                    "codeHash" to codeHash,
                    "status" to "pending",
                    "tvName" to deviceName,
                    "expiresAt" to expiresAt,
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
        // Escuchar el propio pair_request; al claimed, descifrar y adoptar identidad.
        pairingJob?.cancel()
        pairingJob = scope.launch {
            realtime.subscribe(listOf("$col/$recordId")).collect { ev ->
                if (ev.record.optString("status") == "claimed") {
                    val payload = ev.record.optString("payload")
                    if (payload.isNotBlank()) {
                        runCatching { adoptIdentity(payload, code, recordId) }
                            .onFailure {
                                // Re-lanzar cancelaciones (p. ej. al cancelar la suscripción tras
                                // adoptar): no son un error del pareo y no deben mostrarse como tal.
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

    private suspend fun adoptIdentity(payloadCipher: String, code: String, recordId: String) {
        _state.value = PairingState.Claiming
        val json = JSONObject(PairCrypto.decrypt(payloadCipher, code))
        val id = DeviceIdentity(
            accountId = json.getString("accountId"),
            deviceId = json.getString("deviceId"),
            email = json.getString("email"),
            password = json.getString("password"),
            kind = "tv",
        )
        // Adoptar la identidad de la cuenta del celu: autentica primero y actualiza la sesión viva.
        val session = deviceAuth.adoptIdentity(id)
        // Limpiar el pair_request (un solo uso).
        runCatching { client.deleteRecord(col, recordId, session.token) }
        pendingRequestId = null
        _state.value = PairingState.Paired(session.accountId)
        Log.i("ArkivPair", "TV pareado correctamente")
        // Ya adoptamos la identidad: detener la suscripción realtime.
        pairingJob?.cancel()
    }

    // ---------- Rol celu ----------

    /** Escanea el QR, crea el device TV en MI cuenta y escribe las credenciales cifradas. */
    suspend fun claimFromQr(qr: String): Boolean {
        val payload = QrPayloadCodec.decode(qr) ?: run {
            _state.value = PairingState.Error("QR inválido"); return false
        }
        val session = deviceAuth.ensureBootstrapped() ?: run {
            _state.value = PairingState.Error("sin sesión"); return false
        }
        _state.value = PairingState.Claiming
        return runCatching {
            // 1) Buscar el pair_request pendiente y validar que no haya expirado, ANTES de
            //    crear ningún recurso: si falta o expiró, fallamos sin dejar un device huérfano.
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

            // 2) Crear el device TV en MI cuenta.
            val tvId = DeviceIdentityFactory.newTvDevice(session.accountId)
            client.createRecord(
                collection = PocketBaseConfig.COLLECTION_DEVICES,
                fields = mapOf(
                    "accountId" to tvId.accountId,
                    "kind" to "tv",
                    "email" to tvId.email,
                    "password" to tvId.password,
                    "passwordConfirm" to tvId.password,
                    "deviceName" to "Arkiv TV",
                ),
                token = session.token,
            )
            // 3) Cifrar credenciales con el code y escribirlas en el pair_request.
            val secret = JSONObject(
                mapOf(
                    "accountId" to tvId.accountId,
                    "deviceId" to tvId.deviceId,
                    "email" to tvId.email,
                    "password" to tvId.password,
                ),
            ).toString()
            val cipher = PairCrypto.encrypt(secret, payload.code)
            client.updateRecord(col, reqId, mapOf("payload" to cipher, "status" to "claimed"), session.token)
            // Rol celu: a partir de acá este teléfono tiene TV. Se marca aquí (y no solo cuando
            // RemoteController resuelve el device) para que el icono del control remoto y el
            // diálogo aparezcan al volver del escáner, sin esperar al poll de 5 s.
            settings.setTvLinked(true)
            _state.value = PairingState.Paired(session.accountId)
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
