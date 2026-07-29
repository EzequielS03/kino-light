package com.arkiv.player.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano que mantiene VIVO el proceso mientras se transmite un torrent, para que la
 * sesión de libtorrent + el [TorrentStreamServer] local no mueran al pasar la app a segundo plano.
 * Es crítico al castear/DLNA: ahí VLC queda pausado y el renderer sigue jalando bytes de
 * `lanStreamUrl`, así que si el SO mata el proceso la TV se congela. Se arranca desde la pantalla de
 * reproducción (que está en foreground, requisito para lanzar un FGS en Android 12+) y se para al salir.
 */
class TorrentServingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmisión de torrent", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arkiv")
            .setContentText("Transmitiendo…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "arkiv_torrent_serving"
        private const val NOTIF_ID = 4210

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TorrentServingService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TorrentServingService::class.java)) }
        }
    }
}
