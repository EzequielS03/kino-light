package com.arkiv.player.crash

import android.content.Context
import android.os.Build
import android.os.Process
import com.arkiv.player.BuildConfig
import com.arkiv.player.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Punto de entrada del reporte de errores. Se instala desde `ArkivApp.attachBaseContext`, que es
 * lo más temprano que existe en el proceso: antes que los ContentProviders (WorkManager y compañía)
 * y antes de `onCreate`, así que un crash armando el `AppGraph` también queda capturado.
 *
 * Todo lo específico de Android vive acá; la lógica está en [CrashGuard]/[CrashStore], que se
 * prueban en la JVM.
 *
 * NACIÓ TEMPORAL, para cazar el error de un usuario mandando cada reporte a la colección
 * `crash_logs` de PocketBase. Task 9 (sub-proyecto 2B) se llevó esa subida junto con el resto de
 * las cuentas -no queda una sola línea que hable con PocketBase-: lo que sigue acá es puro local,
 * se guarda a disco y se lee por `adb logcat`.
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

    /** Arma el handler. Idempotente por descuido. */
    fun instalar(app: Context) {
        runCatching {
            val store = CrashStore(dir = File(app.filesDir, CARPETA))
            val armado = CrashGuard(
                store = store,
                datos = { datosDe(app) },
                logcat = { logcatDelProceso() },
            )
            guard = armado
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(previo = Thread.getDefaultUncaughtExceptionHandler(), guard = armado),
            )
            // La identidad se calienta aparte, fuera del camino del crash: si `DeviceType`
            // tardara en resolver (consulta el PackageManager), que no sea justo cuando menos
            // tiempo queda.
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

    /**
     * Task 9 (sub-proyecto 2B) se llevó `accountId`/`deviceId` del todo -sin cuentas ni identidad
     * de aparato no había de dónde sacarlos-: lo único que sigue distinguiendo un reporte de otro
     * es [kind] (celular o TV), que no depende de ningún store, solo de [DeviceType].
     */
    private fun leerDatos(app: Context): DatosDelAparato = DatosDelAparato(
        kind = runCatching { if (DeviceType.isTelevision(app)) "tv" else "phone" }.getOrDefault(""),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
        sistema = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}",
    )

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
