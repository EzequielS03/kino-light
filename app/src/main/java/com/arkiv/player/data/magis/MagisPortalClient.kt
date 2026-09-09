package com.arkiv.player.data.magis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transporte del portal de Magis. Las capas de arriba (sesión, catálogo, resolución) dependen de
 * esta interfaz y no de [MagisPortalClient], para poder inyectarles un doble en sus tests.
 */
internal interface MagisPortalClientLike {
    suspend fun call(
        path: String,
        bean: Map<String, Any?> = emptyMap(),
        baseFields: Boolean = true,
        userId: String = "",
        userToken: String = "",
    ): MagisResult<JSONObject>
}

/**
 * Habla directo con el portal de Magis (sin pasar por el gateway `arkiv-api`). Puerto de
 * `IPTVClient.call` — `/Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/vendor/iptv_client.py`,
 * que a su vez salió de decompilar la app original.
 *
 * Tres cosas no son negociables (si faltan, el portal responde "版本已停止使用" o 未登录):
 *  - el body va cifrado con [MagisCrypto] (hex(base64(3DES))), nunca JSON pelado;
 *  - a TODO body se le pegan los ~15 campos de [deviceDict] (el interceptor `C6357b` de la app);
 *  - los headers `apk` / `apkVer` / `spkgVer` van siempre.
 *
 * [hosts] se recibe por constructor (y no se lee de `BuildConfig` acá adentro) para que los tests
 * puedan apuntarlo a un `MockWebServer`; el wiring real le pasa `BuildConfig.IPTV_HOSTS.split(",")`.
 */
internal class MagisPortalClient(
    private val crypto: MagisCrypto,
    private val hosts: List<String>,
    private val appId: String,
    private val apkVersion: String,
    private val scheme: String = "https",
    /** `sn` del device acuñado, leído en cada llamada: lo mintea `MagisSession` y cambia en
     *  caliente (en el puerto de Python esto era `self.device["sn"]`, estado mutable del cliente).
     *  Vacío mientras no haya device — que es justo lo que espera `v3/snToken`. */
    private val snProvider: () -> String = { "" },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MagisPortalClientLike {

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    /** Ritmo mínimo entre llamadas: el portal es susceptible a ráfagas (en el gateway esto era un
     *  TokenBucket global de 1,5 s, compartido entre todos los usuarios; acá el cliente es uno
     *  solo, así que alcanza con espaciarlas). */
    private val ritmo = Mutex()
    private var ultimaLlamadaMs = 0L

    /** Host que funcionó la última vez: se prueba primero para no pagar el timeout de un host
     *  caído en cada llamada (igual que `self.host` en el puerto de Python). */
    @Volatile
    private var hostPreferido: String? = null

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
    ): MagisResult<JSONObject> {
        val body = buildMap<String, Any?> {
            if (baseFields) {
                put("portalCode", PORTAL_CODE)
                put("userId", userId)
                put("userToken", userToken)
            }
            putAll(bean)
            putAll(deviceDict())   // el device enriquece (y pisa) lo que venga en el bean
        }
        val wire = crypto.encryptBody(JSONObject(body).toString())

        esperarTurno()

        var ultima: Throwable? = null
        for (host in ordenDeHosts()) {
            val pedido = Request.Builder()
                .url("$scheme://$host/api/portalCore/$path")
                .post(wire.toRequestBody(jsonType))
                .apply { headers().forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                val crudo = withContext(Dispatchers.IO) {
                    http.newCall(pedido).execute().use { it.body?.string().orEmpty() }
                }
                val j = JSONObject(crudo)
                hostPreferido = host
                val rc = j.optString("returnCode").takeIf { it.isNotEmpty() }
                if (rc != null && rc != "0") {
                    return MagisResult.PortalError(rc, j.optString("errorMessage").ifBlank { null })
                }
                val data = j.optString("data")
                return if (data.isNotEmpty()) {
                    MagisResult.Ok(JSONObject(crypto.decryptBlob(data)))
                } else {
                    MagisResult.Ok(j)
                }
            } catch (e: Throwable) {
                // Red caída, TLS, o respuesta que no es JSON: el host no sirve, se prueba el que sigue.
                ultima = e
            }
        }
        return MagisResult.RedError(ultima ?: IllegalStateException("sin hosts configurados"))
    }

    private fun ordenDeHosts(): List<String> {
        val preferido = hostPreferido ?: return hosts
        return listOf(preferido) + hosts.filter { it != preferido }
    }

    private suspend fun esperarTurno() = ritmo.withLock {
        val desde = System.currentTimeMillis() - ultimaLlamadaMs
        if (ultimaLlamadaMs != 0L && desde < RITMO_MS) delay(RITMO_MS - desde)
        ultimaLlamadaMs = System.currentTimeMillis()
    }

    /**
     * Los ~15 campos que la app original le pega a cada body. Los valores fijos son los del
     * emulador con el que se capturó el protocolo: cambiarlos no está probado y el portal valida
     * algunos contra el device acuñado.
     *
     * `reserve1`/`deviceToken`/`drmId` van VACÍOS a propósito — así los manda producción y así los
     * limpia `new_anonymous_device` antes de acuñar (`iptv_client.py:187-188`).
     */
    private fun deviceDict(): Map<String, Any?> = mapOf(
        "loginType" to "2",
        "appLanguage" to "en",
        "apkVersion" to apkVersion,
        "sysVersion" to SPKG_VER,
        "appId" to appId,
        "hardwareInfo" to "ranchu",
        "model" to "sdk_gphone64_arm64",
        "product" to "sdk_gphone64_arm64",
        "cpu" to "arm64-v8a",
        "B29" to "",
        "reserve1" to "",
        "deviceToken" to "",
        "sn" to snProvider(),
        "drmId" to "",
        "sdkVer" to 36,
    )

    /** `apkVer` es un literal fijo `43404`, distinto del `apkVersion` del device dict: son dos
     *  campos distintos de la app original, no un error de copia. */
    private fun headers(): Map<String, String> = mapOf(
        "apk" to appId,
        "apkVer" to "43404",
        "spkgVer" to SPKG_VER,
        "User-Agent" to "okhttp/3.12.12",
    )

    private companion object {
        const val PORTAL_CODE = "masnew"
        const val SPKG_VER = "2025-08-07 05:40:11_36_16_"
        const val RITMO_MS = 400L
    }
}
