package com.arkiv.player.pocketbase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountState {
    data object Anonimo : AccountState
    data class Conectado(val email: String) : AccountState
}

class AccountException(message: String) : Exception(message)

/**
 * Cuentas de persona sobre PocketBase (colección `users`). Login = el device ADOPTA el accountId de
 * la persona (switchAccount) y se fusiona el historial anónimo vía [onAccountSwitched] (= syncNow).
 * Logout limpia lo local ([onLocalWipe]) y re-bootstrapea anónimo. La clave nunca se persiste.
 */
class AccountManager(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val store: DeviceStore,
    private val onAccountSwitched: suspend () -> Unit,
    private val onLocalWipe: suspend () -> Unit,
) {
    private val users = PocketBaseConfig.COLLECTION_USERS
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AccountState>(
        store.personEmail()?.let { AccountState.Conectado(it) } ?: AccountState.Anonimo
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    suspend fun register(email: String, password: String) = mutex.withLock {
        val session = deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        try {
            client.createRecord(users, mapOf(
                "email" to email,
                "password" to password,
                "passwordConfirm" to password,
                "accountId" to session.accountId,
            ))
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code == 400) "ese email ya está registrado o los datos son inválidos"
                else e.message ?: "no se pudo crear la cuenta"
            )
        }
        store.savePersonEmail(email)
        _state.value = AccountState.Conectado(email)
    }

    suspend fun login(email: String, password: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        val auth = try {
            client.authWithPasswordRecord(users, email, password)
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code in 400..403) "email o contraseña inválidos"
                else e.message ?: "no se pudo iniciar sesión"
            )
        }
        val personAccountId = auth.record.optString("accountId")
        if (personAccountId.isBlank()) throw AccountException("respuesta del servidor inválida (falta accountId)")
        deviceAuth.switchAccount(personAccountId)
        onAccountSwitched()   // cloudSync.syncNow() = reset cursores + push local + pull => MERGE
        store.savePersonEmail(email)
        _state.value = AccountState.Conectado(email)
    }

    suspend fun logout() = mutex.withLock {
        store.clearPersonEmail()
        onLocalWipe()
        deviceAuth.resetToAnonymous()
        _state.value = AccountState.Anonimo
    }
}
