package com.arkiv.player.crash

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manda los reportes a la colección `crash_logs` de PocketBase.
 *
 * SIN token y sin sesión: la colección tiene `createRule` público justamente porque el escenario
 * que más interesa capturar es aquel donde la sesión está rota o vencida. Solo se puede crear;
 * listar y leer siguen siendo de admin.
 */
class CrashUploader(
    private val baseUrl: String = com.arkiv.player.pocketbase.PocketBaseConfig.BASE_URL,
    private val client: OkHttpClient = clientePorDefecto(),
) {
    private val jsonType = "application/json".toMediaType()

    /** `true` si el reporte quedó guardado del otro lado. */
    suspend fun subir(json: String): Boolean = enviarConRespaldo(json) == Resultado.SUBIDO

    /**
     * Vacía la cola, del más viejo al más nuevo.
     *
     * Corta al primer fallo reintentable: si falló uno por red o por servidor caído, los que
     * siguen van a fallar igual, y la cola se reintenta entera en el arranque siguiente.
     */
    suspend fun drenar(store: CrashStore) {
        for (pendiente in store.pendientes()) {
            val json = runCatching { pendiente.readText() }.getOrNull()
            if (json == null) {
                store.borrar(pendiente)
                continue
            }
            when (enviarConRespaldo(json)) {
                Resultado.SUBIDO, Resultado.RECHAZADO -> store.borrar(pendiente)
                Resultado.REINTENTAR -> return
            }
        }
    }

    private enum class Resultado { SUBIDO, RECHAZADO, REINTENTAR }

    /**
     * Un rechazo casi siempre es un campo que no entró, y el candidato obvio es el logcat. Antes
     * de dar el reporte por perdido se manda pelado: el stacktrace es lo único irrecuperable.
     *
     * Esto existe por una quemada real: PocketBase rechazaba el reporte porque el logcat pasaba
     * el tope del campo, y el 4xx se descartaba en seco. El reporte se perdía en silencio — justo
     * lo contrario de para qué existe todo esto.
     */
    private suspend fun enviarConRespaldo(json: String): Resultado {
        val primero = enviar(json)
        if (primero != Resultado.RECHAZADO) return primero
        Log.w(TAG, "reporte rechazado entero; reintentando sin el logcat")
        return enviar(CrashReport.sinLogcat(json))
    }

    private suspend fun enviar(json: String): Resultado = withContext(Dispatchers.IO) {
        val peticion = Request.Builder()
            .url("$baseUrl/api/collections/$COLECCION/records")
            .post(json.toRequestBody(jsonType))
            .build()
        runCatching {
            client.newCall(peticion).execute().use { respuesta ->
                when {
                    respuesta.isSuccessful -> Resultado.SUBIDO
                    // 4xx es "este reporte está mal armado": reintentarlo lo deja atascado
                    // tapando la cola para siempre.
                    respuesta.code in 400..499 -> {
                        // Que nunca vuelva a fallar callado: sin esto, un reporte descartado es
                        // indistinguible de un reporte que nunca se generó.
                        Log.w(TAG, "PocketBase rechazó el reporte (${'$'}{respuesta.code}): " +
                            respuesta.body?.string()?.take(300))
                        Resultado.RECHAZADO
                    }
                    else -> Resultado.REINTENTAR
                }
            }
        }.getOrElse { Resultado.REINTENTAR }
    }

    companion object {
        private const val TAG = "ArkivCrash"
        private const val COLECCION = "crash_logs"

        /** Timeouts cortos: esto corre en el arranque y no puede demorar nada más. */
        private fun clientePorDefecto(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
