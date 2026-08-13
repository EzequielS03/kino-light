package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import kotlinx.coroutines.CancellationException
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

/**
 * Cuentas de persona: PocketBase (colección `users`) es la fuente de verdad local del device;
 * el gateway (`/v1/cuenta`, [CuentaApi]) es quien decide si una persona puede TENER cuenta (exige
 * licencia); Magis es la fuente de verdad de identidad "real" (créditos/plan), y es enteramente
 * opcional: se vincula después de que la cuenta Arkiv ya existe, nunca antes ([registrar] no la
 * toca; ver [vincularMagis]/[vincularMagisEnviarCodigo]/[vincularMagisConfirmar]).
 *
 * Login = el device ADOPTA el accountId de la persona (switchAccount) y se fusiona el historial
 * anónimo vía [onAccountSwitched] (= syncNow). Registro = se crea la cuenta con una licencia y el
 * accountId del device NO cambia (la biblioteca ya anónima queda atribuida a esa persona sin
 * migrar nada). Logout limpia lo local ([onLocalWipe]); YA NO re-bootstrapea una identidad anónima
 * (spec: sin sesión no hay app, así que no tiene sentido fabricar una). La clave nunca se persiste.
 */
class AccountManager(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val store: DeviceStore,
    private val magisLink: MagisLinkClient,
    private val cuentaApi: CuentaApi,
    private val sesion: SesionDePersona,
    private val onAccountSwitched: suspend () -> Unit,
    private val onLocalWipe: suspend () -> Unit,
) {
    private val users = PocketBaseConfig.COLLECTION_USERS
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AccountState>(
        store.personEmail()?.let { AccountState.Conectado(it, false) } ?: AccountState.Anonimo
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /**
     * Login: valida contra PocketBase, que es la única fuente de identidad de una persona que YA
     * tiene cuenta. Si PocketBase no la conoce, la respuesta es "no tenés cuenta, registrate con tu
     * código" — a propósito NO cae a validar contra Magis y crear la cuenta si Magis la acepta:
     * ese camino (que existía acá) permitía fabricarse una cuenta de Arkiv SIN licencia con
     * cualquier credencial de Magis válida, que es justo el agujero que este login cierra. Crear
     * cuenta tiene un solo camino, y pide licencia: [registrar].
     */
    suspend fun login(email: String, password: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")

        val auth = try {
            client.authWithPasswordRecord(users, email, password)
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code in 400..403) "no tenés cuenta, registrate con tu código"
                else e.message ?: "no se pudo iniciar sesión",
            )
        }
        val personAccountId = auth.record.optString("accountId")
        if (personAccountId.isBlank()) throw AccountException("respuesta del servidor inválida (falta accountId)")

        // Lo que puede fallar (persistir la sesión) va antes de lo irreversible (adoptar el
        // accountId del device y fusionar el historial): así un fallo acá no deja el device a medias.
        // `persistirSesion` ya guarda el email en `store` (SesionDePersona.iniciar) — no hace falta repetirlo.
        persistirSesion(email, password)
        // El aparato lo mueve a la cuenta EL GATEWAY, no un PATCH directo a PocketBase: es el
        // unico camino que cuenta contra el cupo de la licencia (`maxCelulares`/`maxTvs`) y que
        // serializa con el candado de Redis. Con `switchAccount` -que escribia el accountId por su
        // cuenta- entrar en un telefono nuevo no consumia cupo: se podia iniciar sesion en cinco.
        // Mismo camino que ya usa el pareo de la TV (Task 5).
        val tokenDelAparato = deviceAuth.session.value?.token
            ?: throw AccountException("sin sesión de dispositivo")
        try {
            cuentaApi.adoptarAparato(tokenDelAparato)
        } catch (e: ErrorDeCuenta) {
            throw AccountException(
                when (e) {
                    is ErrorDeCuenta.TopeAlcanzado ->
                        "Llegaste al límite de aparatos de tu cuenta. Sacá uno desde \"Mis aparatos\" y volvé a entrar."
                    is ErrorDeCuenta.AparatoDeOtraCuenta -> "Este aparato ya está en otra cuenta."
                    else -> e.mensaje
                },
            )
        }
        deviceAuth.aplicarAccountIdAdoptado(personAccountId)
        onAccountSwitched()   // cloudSync.syncNow() = reset cursores + push local + pull => MERGE
        _state.value = AccountState.Conectado(email, magisVinculadoSeguro())
    }

    /**
     * Registro: crea la cuenta de Arkiv con una licencia, vía el gateway ([CuentaApi.registrar]).
     *
     * El orden es lo que importa acá, no el CRUD: la licencia es lo único de este método que puede
     * fallar por una razón de negocio (inválida, ya usada, revocada), así que va PRIMERO. Recién con
     * la cuenta creada se persiste la sesión de la persona ([SesionDePersona.iniciar], Task 1). Antes,
     * el registro le pedía un código de verificación a Magis ANTES de tocar nada de Arkiv: si la
     * licencia resultaba inválida, quedaba una cuenta de Magis creada al pedo, y esa no se puede
     * deshacer desde acá. Magis, si la persona lo quiere, se vincula DESPUÉS y aparte —
     * [vincularMagisEnviarCodigo] / [vincularMagisConfirmar] — nunca como parte de este método.
     */
    suspend fun registrar(email: String, password: String, licencia: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")

        try {
            cuentaApi.registrar(email, password, licencia)
        } catch (e: ErrorDeCuenta) {
            throw AccountException(e.mensaje)
        }

        // La cuenta ya existe en el gateway; si esto falla (poco probable justo después de crearla,
        // pero posible: un corte de red entre los dos pedidos) NO se marca Conectado — la cuenta
        // quedó creada de verdad y la persona puede entrar con `login()` en cuanto vuelva la red.
        // `persistirSesion` ya guarda el email en `store` (SesionDePersona.iniciar).
        persistirSesion(email, password)
        _state.value = AccountState.Conectado(email, magisLinked = false)
    }

    /** Autentica y persiste la sesión de la persona (Task 1); traduce cualquier falla a
     *  [AccountException] para no filtrar tipos internos ([PocketBaseException]) al llamador. */
    private suspend fun persistirSesion(email: String, password: String) {
        try {
            sesion.iniciar(email, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AccountException(e.message ?: "no se pudo iniciar sesión")
        }
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

    /**
     * Corta la sesión de la persona y limpia lo local. YA NO llama a `deviceAuth.resetToAnonymous()`:
     * ese método fabricaba una identidad de device anónima nueva contra PocketBase, que es
     * exactamente el modo "sin sesión" que este plan viene a eliminar (spec: sin sesión no hay
     * app). Recrearla acá no serviría de nada — la persona vuelve a la pantalla de entrada de
     * todos modos — y de paso deja un record de `devices` huérfano en cada logout.
     */
    suspend fun logout() = mutex.withLock {
        sesion.cerrar()
        store.clearPersonEmail()
        onLocalWipe()
        _state.value = AccountState.Anonimo
    }

    private suspend fun magisVinculadoSeguro(): Boolean =
        try { magisLink.status() } catch (e: Exception) { false }

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
