package com.arkiv.player.pocketbase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountState {
    data object Anonimo : AccountState
    data class Conectado(val email: String, val magisLinked: Boolean) : AccountState
}

class AccountException(message: String) : Exception(message)

/** Qué debe hacer la UI después de [AccountManager.registerSendCode]. */
enum class RegistroPaso {
    /** Magis mandó el código: la UI debe pedirlo y llamar a [AccountManager.registerConfirm]. */
    CODIGO_ENVIADO,
    /** Magis no estaba disponible: la cuenta ya quedó creada solo en PocketBase (sin Magis). */
    CREADA_SIN_MAGIS,
}

/**
 * Cuentas de persona: PocketBase (colección `users`) es la fuente de verdad local del device,
 * Magis es la fuente de verdad de identidad "real" (créditos/plan). Login y registro son
 * Magis-first: primero se valida/crea contra Magis y recién después se refleja en PocketBase con
 * la MISMA credencial, para que ambas cuentas puedan autenticarse indistintamente más adelante.
 *
 * Login = el device ADOPTA el accountId de la persona (switchAccount) y se fusiona el historial
 * anónimo vía [onAccountSwitched] (= syncNow). Logout limpia lo local ([onLocalWipe]) y
 * re-bootstrapea anónimo. La clave nunca se persiste.
 */
class AccountManager(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val store: DeviceStore,
    private val magisLink: MagisLinkClient,
    private val onAccountSwitched: suspend () -> Unit,
    private val onLocalWipe: suspend () -> Unit,
) {
    private val users = PocketBaseConfig.COLLECTION_USERS
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AccountState>(
        store.personEmail()?.let { AccountState.Conectado(it, false) } ?: AccountState.Anonimo
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Login unificado: primero PocketBase (rápido, ya conocido por el device); si no existe ahí,
     *  se valida contra Magis y, si Magis lo acepta, se crea/adopta en PocketBase con la misma clave. */
    suspend fun login(email: String, password: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")

        val pbOk = try {
            val auth = client.authWithPasswordRecord(users, email, password)
            val personAccountId = auth.record.optString("accountId")
            if (personAccountId.isBlank()) throw AccountException("respuesta del servidor inválida (falta accountId)")
            deviceAuth.switchAccount(personAccountId)
            onAccountSwitched()   // cloudSync.syncNow() = reset cursores + push local + pull => MERGE
            true
        } catch (e: PocketBaseException) {
            if (e.code !in 400..403) throw AccountException(e.message ?: "no se pudo iniciar sesión")
            false
        }

        if (pbOk) {
            store.savePersonEmail(email)
            _state.value = AccountState.Conectado(email, magisVinculadoSeguro())
            return@withLock
        }

        // PocketBase no la tiene → validar contra Magis.
        deviceAuth.session.value ?: throw AccountException("sin sesión de dispositivo")
        try {
            magisLink.link(email, password)
        } catch (e: MagisLinkException) {
            throw AccountException(
                if (e.code == 503) "Magis no disponible, intentá más tarde" else "email o contraseña inválidos"
            )
        }

        // Magis OK → crear la cuenta PocketBase con la MISMA credencial.
        try {
            crearPocketBase(email, password)
        } catch (e: AccountException) {
            throw AccountException("tu cuenta ya existe con otra clave; usá la clave con la que la creaste")
        }
        _state.value = AccountState.Conectado(email, magisLinked = true)
    }

    /** Registro paso 1: pide el código de verificación a Magis. Si Magis está caído, cae a crear
     *  solo la cuenta PocketBase (se puede vincular Magis después con [vincularMagisEnviarCodigo]). */
    suspend fun registerSendCode(email: String, password: String): RegistroPaso = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        try {
            magisLink.registerSendCode(email)
            RegistroPaso.CODIGO_ENVIADO
        } catch (e: MagisLinkException) {
            if (e.code == 503) {
                crearPocketBase(email, password)
                _state.value = AccountState.Conectado(email, magisLinked = false)
                RegistroPaso.CREADA_SIN_MAGIS
            } else {
                throw AccountException("ese email ya está registrado o los datos son inválidos")
            }
        }
    }

    /** Registro paso 2: confirma el código → Magis crea la cuenta y acá se refleja en PocketBase. */
    suspend fun registerConfirm(email: String, password: String, code: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")
        try {
            magisLink.registerConfirm(email, password, code)
        } catch (e: MagisLinkException) {
            if (e.code == 503) {
                crearPocketBase(email, password)
                _state.value = AccountState.Conectado(email, magisLinked = false)
                return@withLock
            } else {
                throw AccountException("código incorrecto")
            }
        }
        crearPocketBase(email, password)
        _state.value = AccountState.Conectado(email, magisLinked = true)
    }

    /** Estando Conectado sin Magis: vincula Magis con credenciales de una cuenta Magis existente. */
    suspend fun vincularMagis(email: String, password: String) = mutex.withLock {
        try {
            magisLink.link(email, password)
        } catch (e: MagisLinkException) {
            throw AccountException(if (e.code == 503) "Magis no disponible" else "credenciales de Magis inválidas")
        }
        (_state.value as? AccountState.Conectado)?.let { _state.value = it.copy(magisLinked = true) }
    }

    suspend fun vincularMagisEnviarCodigo(email: String) = mutex.withLock {
        try {
            magisLink.registerSendCode(email)
        } catch (e: MagisLinkException) {
            throw AccountException(if (e.code == 503) "Magis no disponible" else "ese email ya está registrado en Magis")
        }
    }

    suspend fun vincularMagisConfirmar(email: String, password: String, code: String) = mutex.withLock {
        try {
            magisLink.registerConfirm(email, password, code)
        } catch (e: MagisLinkException) {
            throw AccountException(if (e.code == 503) "Magis no disponible" else "código incorrecto")
        }
        (_state.value as? AccountState.Conectado)?.let { _state.value = it.copy(magisLinked = true) }
    }

    suspend fun refrescarMagis() {
        (_state.value as? AccountState.Conectado)?.let { _state.value = it.copy(magisLinked = magisVinculadoSeguro()) }
    }

    suspend fun logout() = mutex.withLock {
        store.clearPersonEmail()
        onLocalWipe()
        deviceAuth.resetToAnonymous()
        _state.value = AccountState.Anonimo
    }

    private suspend fun magisVinculadoSeguro(): Boolean =
        try { magisLink.status() } catch (e: Exception) { false }

    private suspend fun crearPocketBase(email: String, password: String) {
        val session = deviceAuth.session.value ?: throw AccountException("sin sesión")
        try {
            client.createRecord(users, mapOf(
                "email" to email,
                "password" to password,
                "passwordConfirm" to password,
                "accountId" to session.accountId,
            ), session.token)   // hardening: registro autenticado con el token del device (createRule PB)
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code == 400) "ese email ya está registrado o los datos son inválidos" else e.message ?: "no se pudo crear la cuenta"
            )
        }
        store.savePersonEmail(email)
    }

    /** Desvincula Magis de la cuenta Arkiv conectada; PocketBase sigue como fuente local. */
    suspend fun desvincularMagis() = mutex.withLock {
        try {
            magisLink.unlink()
        } catch (e: MagisLinkException) {
            throw AccountException(if (e.code == 503) "Magis no disponible" else (e.message ?: "no se pudo desvincular"))
        }
        (_state.value as? AccountState.Conectado)?.let { _state.value = it.copy(magisLinked = false) }
    }
}
