package com.arkiv.player.crash

import android.content.Context
import android.os.Build
import android.os.Process
import com.arkiv.player.BuildConfig
import com.arkiv.player.DeviceType
import com.arkiv.player.pocketbase.SecureDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Punto de entrada del reporte de errores. Se instala desde `ArkivApp.attachBaseContext`, que es
 * lo más temprano que existe en el proceso: antes que los ContentProviders (WorkManager y compañía)
 * y antes de `onCreate`, así que un crash armando el `AppGraph` también queda capturado.
 *
 * Todo lo específico de Android vive acá; la lógica está en [CrashGuard]/[CrashStore]/[CrashUploader],
 * que se prueban en la JVM.
 *
 * TEMPORAL: esto es para cazar un error que le está pasando a un usuario. Cuando aparezca, se
 * borra el paquete entero y las tres líneas de `ArkivApp`.
 */
object Crash {
    private const val CARPETA = "crashes"
    private const val LINEAS_DE_LOGCAT = 400
    private const val TOPE_DE_LOGCAT = 64_000

    @Volatile
    private var guard: CrashGuard? = null

    @Volatile
    private var cacheDeDatos: DatosDelAparato? = null

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Arma el handler y manda lo que haya quedado del arranque anterior. Idempotente por descuido. */
    fun instalar(app: Context) {
        runCatching {
            val store = CrashStore(dir = File(app.filesDir, CARPETA))
            val uploader = CrashUploader()
            val armado = CrashGuard(
                store = store,
                datos = { datosDe(app) },
                logcat = { logcatDelProceso() },
            )
            guard = armado
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(
                    previo = Thread.getDefaultUncaughtExceptionHandler(),
                    guard = armado,
                    envioDeUltimoMomento = EnvioDeUltimoMomento(
                        store = store,
                        subir = { json -> runBlocking { uploader.subir(json) } },
                    ),
                ),
            )
            // Lo pendiente sale primero: si el app revienta apenas abre, este es el único momento
            // en que hay proceso vivo para mandarlo.
            alcance.launch { runCatching { uploader.drenar(store) } }
            // Y la identidad se calienta aparte, fuera del camino del crash: leerla ahí adentro
            // cuesta (prefs cifradas + keystore) justo cuando menos tiempo queda.
            alcance.launch { runCatching { cacheDeDatos = leerDatos(app) } }
        }
    }

    /**
     * Reporta un error atrapado a mano, con el proceso vivo. Para los `runCatching` que hoy se
     * tragan la excepción en silencio. No revienta nunca ni bloquea a quien la llama.
     */
    fun reportar(error: Throwable, etiqueta: String) {
        guard?.reportar(error, etiqueta)
    }

    private fun datosDe(app: Context): DatosDelAparato =
        cacheDeDatos ?: leerDatos(app).also { cacheDeDatos = it }

    private fun leerDatos(app: Context): DatosDelAparato {
        val identidad = runCatching { SecureDeviceStore(app).load() }.getOrNull()
        return DatosDelAparato(
            accountId = identidad?.accountId.orEmpty(),
            deviceId = identidad?.deviceId.orEmpty(),
            kind = identidad?.kind
                ?: runCatching { if (DeviceType.isTelevision(app)) "tv" else "phone" }.getOrDefault(""),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
            sistema = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    /**
     * Las últimas líneas del log de ESTE proceso (`--pid`), que es lo que de verdad dice qué venía
     * pasando antes de reventar. Un app solo puede leer su propio logcat, así que no hay forma de
     * que se cuele nada de otras apps.
     */
    private fun logcatDelProceso(): String {
        val proceso = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "-t", "$LINEAS_DE_LOGCAT", "--pid=${Process.myPid()}"),
        )
        return try {
            proceso.inputStream.bufferedReader().use { it.readText() }.takeLast(TOPE_DE_LOGCAT)
        } finally {
            runCatching { proceso.destroy() }
        }
    }
}
