package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * el gateway (`/v1/cuenta`, [CuentaApi]) es quien decide si el aparato puede entrar a esa cuenta
 * (cupo de aparatos, licencia vigente); Magis es la fuente de verdad de identidad "real"
 * (créditos/plan), y es enteramente opcional: se vincula después de que la cuenta Arkiv ya existe
 * (ver [vincularMagis]/[vincularMagisEnviarCodigo]/[vincularMagisConfirmar]).
 *
 * Login = el device ADOPTA el accountId de la persona (switchAccount); [onAccountSwitched] se
 * sigue llamando en ese momento pero, sin cloud sync (poda Arkiv Light), es un no-op -- no hay
 * historial anónimo remoto que fusionar al loguearse. El alta de cuentas nuevas con licencia
 * (`registrar()`) se sacó en Task 7 (poda "Arkiv Light"): ya no se crean cuentas desde la app,
 * solo se entra a una que ya existe. Logout limpia lo local ([onLocalWipe]); YA NO re-bootstrapea
 * una identidad anónima (spec: sin sesión no hay app, así que no tiene sentido fabricar una). La
 * clave nunca se persiste.
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
     * tiene cuenta. Si PocketBase no la conoce, la respuesta es "no existe una cuenta con ese
     * email" — a propósito NO cae a validar contra Magis y crear la cuenta si Magis la acepta: ese
     * camino (que existía acá)
     * permitía fabricarse una cuenta de Arkiv con cualquier credencial de Magis válida, que es
     * justo el agujero que este login cierra. Task 7 (poda "Arkiv Light") sacó el alta de cuentas
     * nuevas de la app por completo: esta app ya no crea cuentas, solo entra a una que ya existe.
     */
    suspend fun login(email: String, password: String) = mutex.withLock {
        deviceAuth.ensureBootstrapped()
            ?: throw AccountException("sin conexión: intentá de nuevo")

        val auth = try {
            client.authWithPasswordRecord(users, email, password)
        } catch (e: PocketBaseException) {
            throw AccountException(
                if (e.code in 400..403) "no existe una cuenta con ese email"
                else e.message ?: "no se pudo iniciar sesión",
            )
        }
        val personAccountId = auth.record.optString("accountId")
        if (personAccountId.isBlank()) throw AccountException("respuesta del servidor inválida (falta accountId)")

        // El aparato lo mueve a la cuenta EL GATEWAY, no un PATCH directo a PocketBase: es el
        // unico camino que cuenta contra el cupo de la licencia (`maxCelulares`/`maxTvs`) y que
        // serializa con el candado de Redis. Con `switchAccount` -que escribia el accountId por su
        // cuenta- entrar en un telefono nuevo no consumia cupo: se podia iniciar sesion en cinco.
        //
        // Por `/entrar` y NO por `/aparatos` (que es lo que hacia hasta el 2026-08-14): adoptar
        // exige sesion de persona Y que el aparato que llama ya sea de la cuenta, y meterlo en la
        // cuenta es lo que adoptar viene a hacer. En un aparato recien instalado esa condicion no
        // se cumple nunca, asi que el login era un 401 eterno -de vuelta a la pantalla de login-
        // en cualquier aparato nuevo. `/entrar` corre sin sesion previa: manda el token del
        // aparato y la contrasena, que es la prueba que un aparato recien echado no tiene.
        // `cuentaApi` saca el token del aparato de esta MISMA sesión viva (`deviceToken` en
        // AppGraph), así que el chequeo no es redundante con el suyo: está para cortar acá con un
        // mensaje claro en vez de mandar un pedido sin `Authorization` y traducir el `sin_device`
        // que devolvería el gateway.
        if (deviceAuth.session.value?.token == null) throw AccountException("sin sesión de dispositivo")
        try {
            // Y de paso: si la cuenta ya tenia una TV (o un celular) y este es otro, el gateway
            // desvincula el viejo solo. Una TV y un celular por cuenta, sin tener que ir a "Mis
            // aparatos" a hacer lugar a mano.
            cuentaApi.entrar(email, password)
        } catch (e: ErrorDeCuenta) {
            throw AccountException(
                when (e) {
                    is ErrorDeCuenta.CredencialesInvalidas -> "revisá el email y la contraseña"
                    is ErrorDeCuenta.TopeAlcanzado ->
                        "Llegaste al límite de aparatos de tu cuenta. Sacá uno desde \"Mis aparatos\" y volvé a entrar."
                    is ErrorDeCuenta.AparatoDeOtraCuenta -> "Este aparato ya está en otra cuenta."
                    else -> e.mensaje
                },
            )
        }
        deviceAuth.aplicarAccountIdAdoptado(personAccountId)
        // ANTES del merge, no despues: `onAccountSwitched` hace push local + pull, y si lo que hay
        // en el aparato es de OTRA persona eso se lo sube a la cuenta que acaba de entrar. Paso de
        // verdad el 2026-08-14 -- una cuenta recien creada abrio con 145 items ajenos y los subio
        // con su accountId. Ver [DuenoDeLaBase].
        borrarSiEsDeOtro(personAccountId)

        // ---- LA PUERTA ----
        //
        // `persistirSesion` no es un paso más: es lo que DEJA ENTRAR a la app. El portero de
        // `MainActivity` mira `SesionDePersona.estado`, así que en el instante en que esto vuelve,
        // la pantalla de entrada desaparece y el home empieza a pedir contra el gateway.
        //
        // Por eso va acá abajo y no arriba de todo, que es donde estaba. Con el orden viejo la
        // puerta se abría ANTES de que el aparato estuviera en la cuenta, y el home disparaba
        // decenas de pedidos autenticados con un aparato que el gateway todavía no reconocía. El
        // 2026-08-14 en el Google TV fueron 82 pedidos, todos 401, medio segundo antes de que la
        // adopción saliera bien — y el interceptor de sesión, que cierra la sesión cuando ve un
        // 401, echaba a la persona de vuelta al login. El login "fallaba" con todo funcionando.
        //
        // El orden viejo se justificaba con "lo que puede fallar va antes de lo irreversible".
        // Sigue siendo cierto y ya no alcanza: si `persistirSesion` falla después de adoptar, la
        // persona reintenta y `entrar` contesta `yaEra` sin cobrar cupo. Barato. Al revés no:
        // abrir la puerta sin llave no se arregla reintentando.
        persistirSesion(email, password)

        // Y de acá en más, lo que quede tiene que poder terminar SIN la pantalla que llamó: la
        // línea de arriba acaba de sacarla de la composición, y con ella muere el
        // `rememberCoroutineScope()` desde el que corre este método (`PanelDeLogin.enviar`). Sin
        // esto, la cancelación se comía el merge y el `Conectado` de abajo: en el aparato se veía
        // como `sync -> Error(The coroutine scope left the composition)`, y Ajustes le mostraba
        // "no tenés cuenta" a alguien que estaba adentro.
        withContext(NonCancellable) {
            onAccountSwitched()   // no-op (poda Arkiv Light): sin cloud sync no hay nada que fusionar
            _state.value = AccountState.Conectado(email, magisVinculadoSeguro())
        }
    }

    /**
     * Deja la base local lista para [cuenta]: si era de otra persona -o no se sabe de quien es- la
     * borra, y en cualquier caso la marca como suya de ahi en mas.
     *
     * La decision vive en [DuenoDeLaBase], que es pura y esta cubierta por tests: aca el modo de
     * fallar en la otra direccion es borrarle la biblioteca a quien no hizo nada.
     */
    private suspend fun borrarSiEsDeOtro(cuenta: String) {
        if (cuenta.isBlank()) return
        if (DuenoDeLaBase.hayQueBorrar(store.duenoDeLaBase(), cuenta)) {
            onLocalWipe()
        }
        store.saveDuenoDeLaBase(cuenta)
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
