package com.arkiv.player.data.magis

import org.json.JSONObject

/** Cola de respuestas por endpoint -- cada llamada a ese `path` consume la siguiente de su cola. */
internal class FakePortalClient : MagisPortalClientLike {
    val llamadas = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val colasPorPath = mutableMapOf<String, ArrayDeque<MagisResult<JSONObject>>>()
    var respuestaPorDefecto: MagisResult<JSONObject> = MagisResult.Ok(JSONObject())

    fun encolarRespuesta(path: String, resultado: MagisResult<JSONObject>) {
        colasPorPath.getOrPut(path) { ArrayDeque() }.addLast(resultado)
    }

    /** Cuántas veces se llamó a ese endpoint (para afirmar que hubo UN reintento, no dos). */
    fun vecesLlamado(path: String): Int = llamadas.count { it.first == path }

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
    ): MagisResult<JSONObject> {
        llamadas.add(path to bean)
        val cola = colasPorPath[path]
        return if (cola != null && cola.isNotEmpty()) cola.removeFirst() else respuestaPorDefecto
    }
}

internal class FakeCredentialStore : MagisCredentialStore {
    private var sesion: SesionGuardada? = null
    private var cuenta: Pair<String, String>? = null

    override fun guardarSesion(s: SesionGuardada) { sesion = s }
    override fun leerSesion(): SesionGuardada? = sesion
    override fun guardarCuenta(email: String, password: String) { cuenta = email to password }
    override fun leerCuenta(): Pair<String, String>? = cuenta
    override fun borrarCuenta() { cuenta = null }
}

/** Sesión sin cuenta vinculada, ya "activada" (userToken presente) -- para tests que no
 * necesitan ejercitar el flujo de activación en sí. */
internal fun sesionDeTest(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.guardarSesion(SesionGuardada(userId = "u-test", userToken = "t-test", jwtToken = "", sn = "sn-test"))
    return MagisSession(fake, store)
}

/** Igual que [sesionDeTest] pero con una cuenta vinculada (para lo que exige cuenta, ej. vivo). */
internal fun sesionDeTestConCuenta(fake: FakePortalClient = FakePortalClient()): MagisSession {
    val store = FakeCredentialStore()
    store.guardarSesion(SesionGuardada(userId = "u-cuenta", userToken = "t-cuenta", jwtToken = "", sn = "sn-cuenta"))
    store.guardarCuenta("persona@ejemplo.com", "MiClaveMagis123")
    return MagisSession(fake, store)
}

/** Sesión activada pero SIN cuenta vinculada -- para probar el guard de "vivo exige cuenta". */
internal fun sesionDeTestSinCuenta(fake: FakePortalClient = FakePortalClient()): MagisSession =
    sesionDeTest(fake)

/** Atajo para armar respuestas del portal en los tests. */
internal fun portalOk(vararg campos: Pair<String, Any?>): MagisResult<JSONObject> =
    MagisResult.Ok(JSONObject(campos.toMap()))
