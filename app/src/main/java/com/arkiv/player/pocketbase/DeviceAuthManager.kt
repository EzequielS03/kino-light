package com.arkiv.player.pocketbase

import android.util.Log
import com.arkiv.player.data.gateway.CuentaApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceSession(val accountId: String, val deviceId: String, val recordId: String, val token: String)

/**
 * Bootstrap idempotente de la identidad del dispositivo contra PocketBase.
 * Offline-first: si falla la red, devuelve null y se reintenta más tarde (no crashea).
 */
class DeviceAuthManager(
    private val client: PocketBaseClient,
    private val store: DeviceStore,
    /** Task 7: el alta anónima ([createNewAccount]) ya no escribe `devices` directo -- pasa
     *  por [CuentaApi.altaAparato], con credenciales de admin del lado del gateway. */
    private val cuentaApi: CuentaApi,
    /**
     * Qué tipo de aparato es ESTE, para darse de alta con el `kind` correcto.
     *
     * Se daba de alta siempre como `"phone"`, incluido el Fire TV. Con el pareo de la Task 5 —donde
     * la TV aporta su PROPIO aparato en vez de que el celular le fabrique uno— el gateway cuenta el
     * cupo por el `kind` del registro, así que una TV recién instalada consumía el cupo de
     * CELULARES: con `maxCelulares = 1` ya ocupado por el teléfono, el pareo fallaba con
     * "tope alcanzado" sin que hubiera ninguna otra TV.
     */
    private val esTv: () -> Boolean = { false },
) {
    private val _session = MutableStateFlow<DeviceSession?>(null)
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    private val col = PocketBaseConfig.COLLECTION_DEVICES

    private val mutex = Mutex()

    // Guard anti-bucle para onDeviceRecordMissing(): si el 404 persiste (p. ej. el server sigue
    // caído o el record recién creado también da 404 por alguna razón), no re-bootstrapear en
    // cada heartbeat/llamada — como mucho una vez cada REBOOTSTRAP_COOLDOWN_MS.
    @Volatile
    private var lastRebootstrapMs: Long = 0L

    private companion object {
        const val REBOOTSTRAP_COOLDOWN_MS = 30_000L
    }

    suspend fun ensureBootstrapped(): DeviceSession? {
        _session.value?.let { return it }        // fast path, no lock
        return mutex.withLock {
            _session.value?.let { return@withLock it }  // re-check inside lock
            try {
                val existing = store.load()
                val session = if (existing == null) createNewAccount() else authExisting(existing)
                _session.value = session
                store.saveToken(session.token)
                session
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ArkivPB", "bootstrap falló (se reintenta): ${e.message}")
                null
            }
        }
    }

    private suspend fun createNewAccount(): DeviceSession {
        val id = DeviceIdentityFactory.newAnonimo(esTv = esTv())
        // Alta anónima: Task 7 la mueve del create directo a PocketBase (createRule ya
        // cerrada) al gateway, que la crea con sus propias credenciales de admin.
        // IMPORTANTE: el alta remota DEBE ocurrir antes de store.save(id) (no reordenar):
        // si el proceso muere entre ambos pasos, el próximo arranque simplemente genera una
        // identidad nueva; si guardáramos primero, el dispositivo quedaría varado
        // reautenticando un registro que nunca se creó.
        cuentaApi.altaAparato(
            accountId = id.accountId,
            kind = id.kind,
            email = id.email,
            password = id.password,
            deviceName = android.os.Build.MODEL ?: "",
        )
        store.save(id)
        val auth = client.authWithPassword(col, id.email, id.password)
        return DeviceSession(id.accountId, id.deviceId, auth.recordId, auth.token)
    }

    private suspend fun authExisting(id: DeviceIdentity): DeviceSession {
        return try {
            val auth = client.authWithPasswordRecord(col, id.email, id.password)
            DeviceSession(reconciliarAccountId(id, auth.record), id.deviceId, auth.recordId, auth.token)
        } catch (e: PocketBaseException) {
            // Fallo de AUTENTICACIÓN (400/401/403): la identidad guardada ya no vale (device
            // borrado en el servidor, credenciales muertas). Descartarla y crear una cuenta nueva
            // para no quedar atascado para siempre. Otros errores (5xx/429) o de red (IOException,
            // que ni siquiera es PocketBaseException) se propagan → se reintenta luego sin perder
            // la identidad.
            if (e.code == 400 || e.code == 401 || e.code == 403) {
                Log.w("ArkivPB", "identidad guardada inválida (${e.code}); creando cuenta nueva")
                store.clear()
                createNewAccount()
            } else {
                throw e
            }
        }
    }

    /**
     * El `accountId` del record en el SERVIDOR manda sobre el guardado localmente.
     *
     * Cuando el celu adopta un aparato, el gateway escribe el `accountId` nuevo en su record y el
     * aparato se entera por un ÚNICO evento SSE (`PairingManager`), sin poll de respaldo. Si ese
     * evento se pierde -y el realtime de PocketBase no re-entrega-, el aparato se queda con su
     * `accountId` viejo mientras el servidor ya tiene el nuevo; desde ahí la regla
     * `accountId = @request.auth.accountId` rechaza EN BLOQUE todo lo que empuja el sync, que las
     * pone en cuarentena y avanza el cursor por encima: la biblioteca se pierde en silencio.
     *
     * Reautenticar es el momento natural para reconciliar, porque la respuesta de auth YA trae el
     * record: no cuesta ninguna petición extra. Un `accountId` remoto vacío o ausente NO pisa al
     * guardado -preferir un valor que no está por encima de uno bueno dejaría al aparato sin cuenta.
     */
    private fun reconciliarAccountId(id: DeviceIdentity, record: org.json.JSONObject): String {
        val remoto = record.optString("accountId")
        if (remoto.isBlank() || remoto == id.accountId) return id.accountId
        Log.i("ArkivPB", "accountId reconciliado con el servidor: el guardado estaba desactualizado")
        store.save(id.copy(accountId = remoto))
        return remoto
    }

    /**
     * El record del device desapareció en el server (404, p. ej. tras desvincular/borrar). Limpia
     * la identidad guardada + la sesión viva y re-bootstrapea una cuenta NUEVA (createNewAccount,
     * ya que store.load() devolverá null tras el clear), para que la app no quede atascada
     * reautenticando para siempre un registro muerto. Best-effort e idempotente: protegido por un
     * guard de tiempo para no re-bootstrapear en bucle si el 404 persiste.
     */
    suspend fun onDeviceRecordMissing() {
        val now = System.currentTimeMillis()
        if (now - lastRebootstrapMs < REBOOTSTRAP_COOLDOWN_MS) return
        lastRebootstrapMs = now
        runCatching {
            mutex.withLock {
                _session.value = null
                runCatching { store.clear() }
            }
            ensureBootstrapped()
        }.onFailure { e ->
            Log.w("ArkivPB", "onDeviceRecordMissing: re-bootstrap falló: ${e.message}")
        }
    }

    /** Adopta una identidad nueva (p. ej. tras parear): autentica primero, luego persiste y
     *  actualiza la sesión viva. Lanza si la autenticación falla (sin tocar la identidad previa). */
    suspend fun adoptIdentity(identity: DeviceIdentity): DeviceSession = mutex.withLock {
        val auth = client.authWithPassword(PocketBaseConfig.COLLECTION_DEVICES, identity.email, identity.password)
        store.clear()
        store.save(identity)
        store.saveToken(auth.token)
        val session = DeviceSession(identity.accountId, identity.deviceId, auth.recordId, auth.token)
        _session.value = session
        session
    }

    /**
     * Refleja en la sesión viva + el store un `accountId` que el GATEWAY ya movió del lado del
     * servidor -- hoy, el login normal de una persona (`CuentaApi.entrar()`, ver
     * `AccountManager.login()`). A diferencia de [switchAccount] -pensado para el login de una
     * PERSONA, que se autentica con su propia contraseña y por eso puede autoescribir su
     * `accountId`- acá NO hay ningún PATCH a PocketBase: repetirlo con el token del propio
     * device sería una escritura redundante (el valor ya quedó en el record por el camino que el
     * gateway usó para moverlo), y si `devices.updateRule` se cierra más adelante (Task 7) además
     * fallaría, sin necesidad -- el único control real ya lo aplicó el gateway.
     */
    suspend fun aplicarAccountIdAdoptado(accountId: String): DeviceSession = mutex.withLock {
        val current = _session.value ?: error("aplicarAccountIdAdoptado sin sesión de dispositivo")
        store.load()?.let { store.save(it.copy(accountId = accountId)) }
        val updated = current.copy(accountId = accountId)
        _session.value = updated
        updated
    }

    /** El device adopta un accountId (login de persona): actualiza server + store + sesión viva. */
    suspend fun switchAccount(newAccountId: String): DeviceSession = mutex.withLock {
        val current = _session.value ?: error("switchAccount sin sesión de dispositivo")
        client.updateRecord(col, current.recordId, mapOf("accountId" to newAccountId), current.token)
        store.load()?.let { store.save(it.copy(accountId = newAccountId)) }
        val updated = current.copy(accountId = newAccountId)
        _session.value = updated
        updated
    }

    /** Logout: descarta la identidad actual y crea una anónima nueva (accountId nuevo, vacío). */
    suspend fun resetToAnonymous(): DeviceSession? {
        mutex.withLock {
            _session.value = null
            store.clear()
        }
        return ensureBootstrapped()   // store vacío -> createNewAccount()
    }
}
