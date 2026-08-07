package com.arkiv.player

import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class ArkivApp : Application() {
    lateinit var graph: AppGraph
        private set

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Cualquier descarga terminó: refrescar estado en Room app-wide,
            // no solo cuando la pantalla de Descargas está abierta.
            graph.applicationScope.launch { graph.downloader.refreshProgress() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Corregir estados que quedaron pendientes si una descarga terminó
        // mientras la app estaba cerrada.
        graph.applicationScope.launch { graph.downloader.refreshProgress() }
        // Servidor de sincronización LAN (expone/recibe la DB entre dispositivos).
        runCatching { graph.syncManager.start() }

        // OTA: chequeo periódico cada 6 horas + chequeo inmediato al arrancar.
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.arkiv.player.data.update.UpdateWorker>(
                6, java.util.concurrent.TimeUnit.HOURS,
            ).setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
            ).build(),
        )
        graph.applicationScope.launch { graph.checkForUpdate() }
    }
}
