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
 * Entry point of crash reporting. Installed from `ArkivApp.attachBaseContext`, which is the
 * earliest point there is in the process: before the ContentProviders (WorkManager and company)
 * and before `onCreate`, so a crash while building the `AppGraph` is also caught.
 *
 * Everything Android-specific lives here; the logic is in [CrashGuard]/[CrashStore], which are
 * tested on the JVM.
 *
 * BORN TEMPORARY, to hunt down a user's bug by sending every report to PocketBase's `crash_logs`
 * collection. Task 9 (sub-project 2B) took that upload away along with the rest of the accounts
 * -not a single line talking to PocketBase is left-: what remains here is purely local, saved to
 * disk and read via `adb logcat`.
 */
object Crash {
    private const val FOLDER = "crashes"
    private const val LOGCAT_LINES = 400
    private const val LOGCAT_CAP = 64_000

    @Volatile
    private var guard: CrashGuard? = null

    @Volatile
    private var deviceDataCache: DeviceData? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Builds the handler. Idempotent by neglect. */
    fun install(app: Context) {
        runCatching {
            val store = CrashStore(dir = File(app.filesDir, FOLDER))
            val installed = CrashGuard(
                store = store,
                data = { dataFor(app) },
                logcat = { processLogcat() },
            )
            guard = installed
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(previous = Thread.getDefaultUncaughtExceptionHandler(), guard = installed),
            )
            // The identity is warmed up separately, out of the crash's path: if `DeviceType` took
            // a while to resolve (it queries the PackageManager), better not right when there's
            // the least time left.
            scope.launch { runCatching { deviceDataCache = readData(app) } }
        }
    }

    /**
     * Reports an error caught by hand, with the process alive. For the `runCatching`s that today
     * swallow the exception silently. Never throws nor blocks whoever calls it.
     */
    fun report(error: Throwable, tag: String) {
        guard?.report(error, tag)
    }

    private fun dataFor(app: Context): DeviceData =
        deviceDataCache ?: readData(app).also { deviceDataCache = it }

    /**
     * Task 9 (sub-project 2B) took `accountId`/`deviceId` away entirely -with no accounts or
     * device identity there was nowhere to pull them from-: the only thing still telling one
     * report apart from another is [kind] (phone or TV), which doesn't depend on any store, only
     * on [DeviceType].
     */
    private fun readData(app: Context): DeviceData = DeviceData(
        kind = runCatching { if (DeviceType.isTelevision(app)) "tv" else "phone" }.getOrDefault(""),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
        system = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    /**
     * The last lines of THIS process's log (`--pid`), which is what really says what was
     * happening before it crashed. An app can only read its own logcat, so there's no way
     * anything from another app can slip in.
     */
    private fun processLogcat(): String {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "-t", "$LOGCAT_LINES", "--pid=${Process.myPid()}"),
        )
        return try {
            process.inputStream.bufferedReader().use { it.readText() }.takeLast(LOGCAT_CAP)
        } finally {
            runCatching { process.destroy() }
        }
    }
}
