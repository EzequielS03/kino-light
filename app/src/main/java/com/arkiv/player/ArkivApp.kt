package com.arkiv.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.launch

class ArkivApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    /**
     * The earliest thing that runs in the process: before the ContentProviders (WorkManager and
     * friends) and before [onCreate]. Error reporting is installed here on purpose, so a crash on
     * startup -including one while building the [AppGraph]- also gets captured.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.arkiv.player.crash.Crash.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)

        // One-off migration (Task 7, sub-project 2B; rewritten in Task 9): the 18+ lock and the
        // recents purge used to live in `SecureDeviceStore` (encrypted prefs from the accounts
        // subsystem, deleted entirely in this same task). Neither of the two is account data, so
        // [SettingsStore.migrarDelStoreDeCuentasViejo] rescues them by reading that file directly,
        // without that class -- a single read, synchronous and BEFORE any screen, so nothing ever
        // reads `graph.settings.adultosDesbloqueado` before it's migrated. If the file doesn't
        // exist or the Keystore can't decrypt it, it's treated as "there was nothing to migrate"
        // and falls back to the default -- it can never be a reason not to start.
        runCatching {
            graph.settings.migrarDelStoreDeCuentasViejo(this)
        }.onFailure { report(it, "startup: migrate encrypted-store prefs") }

        // One-off purge from 2026-08-14: adult channels that stayed logged in "Recents" from
        // BEFORE `abrirCanalActual` stopped logging them. They were showing up in the home's
        // "Live channels" row, in plain view of anyone, with their name and logo.
        //
        // EVERYTHING gets deleted, not just the adult ones, because the device can't know which
        // ones were: recents only store code and name, never the category. And it costs nothing —
        // the cloud ones were already cleaned up by hand, so the next sync repopulates the list
        // with the legitimate ones.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!graph.settings.recientesPurgados) {
                    graph.database.liveRecentDao().deleteAll()
                    graph.settings.setRecientesPurgados(true)
                    android.util.Log.w("ArkivCuenta", "recent items purged (adult channel leak)")
                }
            }.onFailure { report(it, "startup: purge recents") }
        }

        graph.startNetworkMonitor()

        // OTA: periodic check every 6 hours + immediate check on startup.
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.arkiv.player.data.update.UpdateWorker>(
                6, java.util.concurrent.TimeUnit.HOURS,
            ).setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
            ).build(),
        )
        graph.applicationScope.launch {
            runCatching { graph.checkForUpdate() }.onFailure { report(it, "startup: check for update") }
        }

        // New episodes of the series you're watching. Runs in the background and blocks nothing:
        // it's an opportunistic improvement, not a critical path. The "once every N hours" cap
        // lives inside because app startup happens many times a day (just leaving and coming back
        // does it), and checking every single time would waste network for nothing.
        graph.applicationScope.launch {
            runCatching { graph.lookForNewChapters() }
                .onFailure { report(it, "startup: look for new episodes") }
        }
    }

    /**
     * Coil came with the defaults, and its default memory cache is **25% of the heap limit**. On
     * the Fire TV Stick that's ~48 MB reserved just for bitmaps on a 1.7 GB device that runs with
     * ~48 MB free and swap nearly full (measured with `dumpsys meminfo`). With TMDB's episode
     * stills there are quite a few more images on screen than before, so it's worth setting an
     * explicit ceiling instead of leaving the default percentage.
     *
     * On TV it's trimmed to 10% and RGB_565 is allowed: posters and stills are JPEGs with no
     * transparency, so they drop to half the bytes per bitmap with no visible difference from
     * couch distance. On phone it's left at 20% (generous, but below the default) and full color,
     * which is where it actually shows on a screen 30 cm away.
     */
    /**
     * Every startup task runs in its own `runCatching` so a failure doesn't take down the others
     * — but that also made them silent: if a sync never started, there was no trace of why.
     * Reporting doesn't change the isolation, it just leaves a record.
     */
    private fun report(error: Throwable, label: String) {
        android.util.Log.w("ArkivArranque", "$label: ${error.message}", error)
        com.arkiv.player.crash.Crash.report(error, label)
    }

    override fun newImageLoader(): ImageLoader {
        val tv = DeviceType.isTelevision(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (tv) 0.10 else 0.20)
                    .build()
            }
            // The disk cache avoids re-downloading the same cover art on every launch; Coil's
            // default (2% of free space) can be huge on a phone with a lot of disk.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (tv) 64L * 1024 * 1024 else 192L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(tv)
            .build()
    }
}
