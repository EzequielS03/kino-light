package com.arkiv.player.pocketbase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MagisLinkException(val code: Int, message: String) : Exception(message)

/**
 * Cliente de los endpoints /v1/magis/link del gateway. Manda, desde la Task 8, Authorization +
 * X-Arkiv-Device -- las dos que `require_sesion` exige desde la Task 5b (la sesión está atada al
 * aparato). `X-Arkiv-Key` salió del todo en el Paso 3. `X-Arkiv-Account` salió después: el
 * gateway dejó de leerla y saca el accountId de la sesión autenticada -- una cabecera la elegía
 * el CLIENTE, y con eso se podía pedir la cuenta de Magis de otra persona.
 */
class MagisLinkClient(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient(),
    /** Token de sesión de la PERSONA, misma fuente que ya usa `CuentaApi` para `Authorization`
     *  (`SesionDePersona.token()`). Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- ver KDoc del
     *  mismo parámetro en `ArkivApiClient`. */
    private val personToken: () -> String? = { null },
    /** Token del APARATO que llama, misma fuente que ya usa `CuentaApi` para `X-Arkiv-Device`
     *  (`DeviceAuthManager.session.value?.token`). */
    private val deviceToken: () -> String? = { null },
) {
    private val jsonType = "application/json".toMediaType()

    private fun req(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        // Sin sesión/aparato todavía (null o vacío) se omiten las cabeceras -- mandarlas vacías
        // sería peor que no mandarlas (ver ArkivApiClient.pedido).
        personToken()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
        deviceToken()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Device", it) }
        return b
    }

    private fun exec(request: Request): JSONObject =
        runCatching {
            client.newCall(request).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                    throw MagisLinkException(r.code, msg?.ifBlank { raw } ?: raw)
                }
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }
        }.getOrElse { e ->
            if (e is MagisLinkException) throw e
            throw MagisLinkException(0, e.message ?: "error de red")
        }

    suspend fun status(): Boolean = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").get().build()).optBoolean("linked", false)
    }

    suspend fun link(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("username" to username, "password" to password))
            .toString().toRequestBody(jsonType)
        exec(req("${baseUrl()}/v1/magis/link").post(body).build())   // 422 -> MagisLinkException
    }

    suspend fun unlink(): Unit = withContext(Dispatchers.IO) {
        exec(req("${baseUrl()}/v1/magis/link").delete().build())
    }

    suspend fun registerSendCode(email: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("email" to email))
            .toString().toRequestBody(jsonType)
        exec(req("${baseUrl()}/v1/magis/register/send-code").post(body).build())
    }

    suspend fun registerConfirm(email: String, password: String, code: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("email" to email, "password" to password, "code" to code))
            .toString().toRequestBody(jsonType)
        exec(req("${baseUrl()}/v1/magis/register/confirm").post(body).build())
    }
}
