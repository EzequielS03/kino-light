package com.arkiv.player.pocketbase

import com.arkiv.player.data.gateway.CuentaApi
import okhttp3.OkHttpClient

class FakeDeviceStore(private var identity: DeviceIdentity? = null) : DeviceStore {
    private var token: String? = null
    private var personEmail: String? = null
    private var personToken: String? = null
    override fun save(identity: DeviceIdentity) { this.identity = identity }
    override fun load(): DeviceIdentity? = identity
    override fun saveToken(token: String) { this.token = token }
    override fun token(): String? = token
    override fun clear() { identity = null; token = null; personEmail = null; personToken = null }
    override fun savePersonEmail(email: String) { personEmail = email }
     private var dueno: String? = null
    override fun saveDuenoDeLaBase(accountId: String) { dueno = accountId }
    override fun duenoDeLaBase(): String? = dueno

   override fun personEmail(): String? = personEmail
    override fun clearPersonEmail() { personEmail = null }
    override fun savePersonToken(token: String) { personToken = token }
    override fun personToken(): String? = personToken
    override fun clearPersonToken() { personToken = null }
}

/**
 * `CuentaApi` de relleno para el 3er parámetro de `DeviceAuthManager` (Task 7) en tests que NO
 * ejercitan `createNewAccount()` -- es decir, casi todos: alcanza con que el `store` ya tenga una
 * identidad seedeada (`FakeDeviceStore(DeviceIdentity(...))`) para que `ensureBootstrapped()` tome
 * el camino de `authExisting()` y jamás llame a `altaAparato`. Apunta a un host inalcanzable a
 * propósito -mismo criterio que el resto de este archivo de tests-: si algún cambio futuro hiciera
 * que SÍ se llamara, el test que lo use fallaría ruidoso en vez de pasar en silencio contra un
 * servidor real.
 */
fun cuentaApiSinUsarParaBootstrap(client: PocketBaseClient, store: DeviceStore): CuentaApi = CuentaApi(
    baseUrl = { "http://unused.invalid" },
    deviceToken = { null },
    sesion = SesionDePersona(client, store),
    http = OkHttpClient(),
)
