package com.arkiv.player.data.magis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * La sesión del portal de Magis vista desde el aparato. Puerto de `IPTVClient.activate` /
 * `new_anonymous_device` / `login` (`vendor/iptv_client.py`) más la lógica de recuperación que
 * `arkiv-api` aprendió en producción (`adapters/magis/session.py`).
 *
 * Dos modos, igual que en el gateway:
 *  - **anónimo**: el aparato acuña SU device (`v3/snToken` → `sn` → `v8/active`) y con eso ya hay
 *    catálogo y VOD. No hace falta que nadie tenga cuenta.
 *  - **cuenta**: `v8/login` con email+clave. Hace falta para el canal en vivo.
 *
 * Lo que nunca se puede hacer es inventarse un `sn`: el portal contesta `snToken已经失效`. Y dos
 * identidades sobre el MISMO `sn` se expulsan mutuamente en cada activación (el ping-pong que
 * documenta `session.py`), así que el `sn` acuñado se guarda y se reusa para siempre.
 */
internal class MagisSession(
    private val portal: MagisPortalClientLike,
    private val store: MagisCredentialStore,
) {

    /** Activar/loguear son "leer-modificar-escribir" sobre el store: dos corrutinas a la vez
     *  acuñarían dos devices y una expulsaría a la otra. */
    private val candado = Mutex()

    val userId: String get() = store.leerSesion()?.userId.orEmpty()
    val userToken: String get() = store.leerSesion()?.userToken.orEmpty()

    /** El device acuñado de este aparato. Lo lee `MagisPortalClient` en cada llamada. */
    val sn: String get() = store.leerSesion()?.sn.orEmpty()

    val hasAccountLinked: Boolean get() = store.leerCuenta() != null

    /** Solo el email: la clave guardada es para relogar sola, no para mostrarla ni pasearla. */
    fun emailVinculado(): String? = store.leerCuenta()?.first

    /**
     * Deja el aparato con la MEJOR sesión que pueda tener: la de la cuenta vinculada si hay una, y
     * si no la anónima. No-op si ya hay token.
     *
     * La diferencia con [ensureAnonymous] importa para el canal en vivo: el portal lo rechaza con
     * sesión anónima, así que un aparato con cuenta vinculada que cayera a anónimo quedaría sin
     * vivo y sin explicación. Es el mismo orden de precedencia que usa el gateway en `client()`.
     */
    suspend fun ensureSession(): MagisResult<Unit> = candado.withLock {
        if (!store.leerSesion()?.userToken.isNullOrBlank()) return@withLock MagisResult.Ok(Unit)
        val cuenta = store.leerCuenta()
        if (cuenta != null) {
            val r = loguearSinCandado(cuenta.first, cuenta.second)
            // Credencial rechazada o portal caído: se sirve anónimo antes que no servir nada. Quien
            // necesite cuenta de verdad (el vivo) lo chequea con `hasAccountLinked`.
            if (r is MagisResult.Ok) return@withLock r
        }
        asegurarAnonimoSinCandado()
    }

    /** Deja el aparato con una sesión usable sin pedirle cuenta a nadie. No-op si ya hay token. */
    suspend fun ensureAnonymous(): MagisResult<Unit> = candado.withLock { asegurarAnonimoSinCandado() }

    suspend fun login(email: String, password: String): MagisResult<Unit> =
        candado.withLock { loguearSinCandado(email, password) }

    /**
     * Desvincula la cuenta: cierra sesión en el portal, borra las credenciales y vuelve a anónimo
     * con el MISMO device (el `sn` es del aparato, no de la cuenta).
     */
    suspend fun logout(): MagisResult<Unit> = candado.withLock {
        val previa = store.leerSesion()
        if (!previa?.userToken.isNullOrBlank()) {
            portal.call(
                path = "v5/loginOut",
                bean = mapOf("userId" to previa!!.userId, "userToken" to previa.userToken),
                baseFields = false,
            )
        }
        store.borrarCuenta()
        olvidarToken()
        asegurarAnonimoSinCandado()
    }

    /**
     * Corre [bloque] y, si el portal lo rechaza, reautentica y lo reintenta UNA vez.
     *
     * Reautentica ante **cualquier** `PortalError`, no solo ante los códigos conocidos de sesión
     * vencida (`aaa100027`/`aaa100028`). Es lo que aprendió el gateway a la fuerza
     * (`adapter.py:_llamar`): el portal también mata la sesión cuando otro aparato entra con el
     * mismo device, y eso lo avisa con un mensaje localizado en chino cuyo código no está
     * documentado. Discriminar fino deja la sesión guardada muerta y TODAS las llamadas que
     * siguen fallando para siempre; el costo de equivocarse al revés es una llamada de más.
     *
     * Un [MagisResult.RedError] NO reautentica: el portal no dijo nada, está caído.
     */
    suspend fun <T> conSesionValida(bloque: suspend () -> MagisResult<T>): MagisResult<T> {
        val primera = bloque()
        if (primera !is MagisResult.PortalError) return primera
        val reauth = reautenticar()
        if (reauth !is MagisResult.Ok) return primera
        return bloque()
    }

    // --- adentro del candado -------------------------------------------------

    private suspend fun asegurarAnonimoSinCandado(): MagisResult<Unit> {
        val previa = store.leerSesion()
        if (!previa?.userToken.isNullOrBlank()) return MagisResult.Ok(Unit)

        val snGuardado = previa?.sn.orEmpty()
        if (snGuardado.isNotBlank()) {
            val r = activar(snToken = "")
            if (r is MagisResult.Ok) return r
            // Solo estos dos códigos significan "ese device ya no sirve": cualquier otra cosa
            // (red, portal caído) NO autoriza a acuñar otro -- acuñar de más es gastar devices.
            if (!(r is MagisResult.PortalError && r.codigo in SN_INVALIDO)) return r
        }
        return acunarDevice()
    }

    /**
     * `v3/snToken` con una huella de hardware → `sn = md5(snToken + salt)` → `v8/active`.
     * La huella va aleatoria en cada acuñación: el portal entrega un device por huella, y dos
     * aparatos con la misma huella serían el mismo device.
     */
    private suspend fun acunarDevice(): MagisResult<Unit> {
        val r = portal.call("v3/snToken", huellaDeHardware(), baseFields = false)
        val j = r.dato() ?: return r.map { }
        val snToken = j.optString("snToken").takeIf { it.isNotBlank() }
            ?: return MagisResult.PortalError("snToken_failed", "el portal no devolvió snToken")
        val sn = (j.optString("sn").takeIf { it.isNotBlank() } ?: md5Hex(snToken + SNTOKEN_SALT))
            .lowercase()
        // El `sn` se guarda ANTES de activar porque es lo que el device dict tiene que llevar en
        // esa misma llamada (`MagisPortalClient` lo lee del store).
        store.guardarSesion(SesionGuardada(userId = "", userToken = "", jwtToken = "", sn = sn))
        return activar(snToken)
    }

    /** `v8/active`: con [snToken] vacío reactiva el device que ya está guardado. */
    private suspend fun activar(snToken: String): MagisResult<Unit> {
        val bean = mapOf(
            "snToken" to snToken,
            "authVersion" to "",
            "authCode" to "",
            "preCode" to "",
            "macAddr" to MAC_FIJA,
            "reserve1" to "",
            "openNum" to 4,
            "channel" to "default",
            // Solo los STB con /system/etc/.UCERT los llevan.
            "matadata" to "",
            "signdata" to "",
        )
        val r = portal.call("v8/active", bean, baseFields = false)
        val j = r.dato() ?: return r.map { }
        if (j.optString("userToken").isBlank()) {
            return MagisResult.PortalError("active_sin_token", "activación sin userToken")
        }
        guardarDeRespuesta(j)
        return MagisResult.Ok(Unit)
    }

    private suspend fun loguearSinCandado(email: String, password: String): MagisResult<Unit> {
        val bean = mapOf(
            "accountType" to "2",
            "userName" to email,
            // MD5(clave + "cloudstream") en hex minúsculas. El salt sale de `AbstractC5729e`
            // en la decompilada; sin él el portal rechaza credenciales CORRECTAS.
            "password" to md5Hex(password + SALT_CLAVE),
            "type" to "1",
            "macAddr" to MAC_FIJA,
            "areaCode" to "",
            "verificationCode" to "",
            "verificationToken" to "",
            "matadata" to "",
            "signdata" to "",
            "channel" to "default",
        )
        // Loguear NO activa el device: gastaría una activación por `sn` sin necesidad.
        val r = portal.call("v8/login", bean, baseFields = false)
        val j = r.dato() ?: return r.map { }
        if (j.optString("userToken").isBlank()) {
            return MagisResult.PortalError("login_sin_token", "login sin userToken")
        }
        guardarDeRespuesta(j)
        store.guardarCuenta(email, password)
        return MagisResult.Ok(Unit)
    }

    private suspend fun reautenticar(): MagisResult<Unit> = candado.withLock {
        val cuenta = store.leerCuenta()
        // Lo guardado ya no sirve; si no se blanquea, `asegurarAnonimoSinCandado` lo daría por bueno.
        olvidarToken()
        if (cuenta != null) loguearSinCandado(cuenta.first, cuenta.second)
        else asegurarAnonimoSinCandado()
    }

    /** Tira el token pero conserva el device: acuñar de nuevo sería gastar un device por cada
     *  vencimiento de sesión. */
    private fun olvidarToken() {
        val previa = store.leerSesion() ?: return
        store.guardarSesion(previa.copy(userId = "", userToken = "", jwtToken = ""))
    }

    private fun guardarDeRespuesta(j: JSONObject) {
        store.guardarSesion(
            SesionGuardada(
                userId = j.optString("userId"),
                userToken = j.optString("userToken"),
                jwtToken = j.optString("jwtToken"),
                // El device es del APARATO: ni el login ni la reactivación lo cambian.
                sn = store.leerSesion()?.sn.orEmpty(),
            ),
        )
    }

    /**
     * La huella que `v3/snToken` espera (`SnTokenBean` en la decompilada). La app original manda
     * los datos reales del teléfono; acá van los del emulador con el que se capturó el protocolo,
     * con los campos identificatorios aleatorios para que cada aparato acuñe SU device.
     */
    private fun huellaDeHardware(): Map<String, Any?> = mapOf(
        "androidId" to hexAleatorio(8),
        "board" to "goldfish_arm64",
        "brand" to "google",
        "cpuAbi" to "arm64-v8a",
        "cpuId" to hexAleatorio(8),
        "device" to "emu64a",
        "diskInfo" to "8GB",
        "display" to "sdk_gphone64_arm64",
        "etheMac" to macAleatoria(),
        "fingerprint" to
            "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
        "gatewayMac" to macAleatoria(),
        "hardware" to "ranchu",
        "host" to "abfarm",
        "manufacturer" to "Google",
        "ramSize" to "4GB",
        "romSize" to "8GB",
        "serialNumber" to hexAleatorio(8),
        "tags" to "release-keys",
        "verId" to "",
        "wifiMac" to macAleatoria(),
    )

    private fun hexAleatorio(bytes: Int): String =
        ByteArray(bytes).also { azar.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun macAleatoria(): String =
        ByteArray(6).also { azar.nextBytes(it) }.joinToString(":") { "%02x".format(it) }

    private fun md5Hex(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val azar = SecureRandom()

        const val SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"
        const val SALT_CLAVE = "cloudstream"

        /** La MAC que manda la app original, fija y falsa. */
        const val MAC_FIJA = "02:00:00:00:00:00"

        /**
         * "Este `sn` no sirve para activar en anónimo" → hay que acuñar otro device.
         *  - `aaa100080`: snToken/sn inválido.
         *  - `aaa100082`: ese device quedó bindeado a una cuenta con contraseña y solo admite
         *    login. Sin este código, toda la rama anónima queda muerta y el portal contesta
         *    "请求参数异常！" a cada llamada, que no apunta ni de casualidad a acá (visto en
         *    producción el 2026-08-12).
         */
        val SN_INVALIDO = setOf("aaa100080", "aaa100082")
    }
}
