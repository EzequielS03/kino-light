package com.arkiv.player.tvservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arkiv.player.AppGraph

/**
 * Servicio en primer plano que mantiene vivo el proceso de la TV para que las
 * suscripciones SSE de PocketBase (iniciadas en [AppGraph.applicationScope])
 * sigan corriendo aunque la app quede en segundo plano.
 */
class TvConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "arkiv_connection"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Conexión Arkiv", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Arkiv conectado")
            .setContentText("Recibiendo del teléfono")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
        // Tocar el grafo asegura que las suscripciones (applicationScope) estén vivas.
        AppGraph.from(applicationContext)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TvConnectionService::class.java))
        }
    }
}
