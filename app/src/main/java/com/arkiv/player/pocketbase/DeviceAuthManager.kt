package com.arkiv.player.pocketbase

import android.util.Log
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
        val id = DeviceIdentityFactory.newPhoneAccount()
        // Alta anónima permitida por la regla de create de `devices`.
        // IMPORTANTE: el create remoto DEBE ocurrir antes de store.save(id) (no reordenar):
        // si el proceso muere entre ambos pasos, el próximo arranque simplemente genera una
        // identidad nueva; si guardáramos primero, el dispositivo quedaría varado
        // reautenticando un registro que nunca se creó.
        client.createRecord(
            collection = col,
            fields = mapOf(
                "accountId" to id.accountId,
                "kind" to id.kind,
                "email" to id.email,
                "password" to id.password,
                "passwordConfirm" to id.password,
                "deviceName" to android.os.Build.MODEL,
            ),
        )
        store.save(id)
        val auth = client.authWithPassword(col, id.email, id.password)
        return DeviceSession(id.accountId, id.deviceId, auth.recordId, auth.token)
    }

    private suspend fun authExisting(id: DeviceIdentity): DeviceSession {
        return try {
            val auth = client.authWithPassword(col, id.email, id.password)
            DeviceSession(id.accountId, id.deviceId, auth.recordId, auth.token)
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
     * Refleja en la sesión viva + el store un `accountId` que el GATEWAY ya escribió en el
     * servidor (Task 5: `CuentaApi.adoptarAparato`, con el candado de Redis que cuenta contra el
     * tope de TVs de la licencia). A diferencia de [switchAccount] -pensado para el login de una
     * PERSONA, que se autentica con su propia contraseña y por eso puede autoescribir su
     * `accountId`- acá NO hay ningún PATCH a PocketBase: repetirlo con el token del propio
     * device sería una escritura redundante (el valor ya quedó en el record por el camino que
     * cuenta contra el tope), y si `devices.updateRule` se cierra más adelante (Task 7) además
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
