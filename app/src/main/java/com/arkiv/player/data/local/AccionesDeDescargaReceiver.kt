package com.arkiv.player.data.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arkiv.player.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * El botón "Cancelar" de la notificación de descarga.
 *
 * Va por un receiver y no por una Activity porque cancelar no tiene nada que mostrar: abrir la app
 * para frenar una descarga es justo lo que hacía falta evitar.
 *
 * `goAsync()` porque [LocalDownloadManager.cancel] toca la base y el worker: sin eso el proceso
 * puede morir apenas retorna `onReceive` y la cancelación quedar a medias — la fila marcada pero el
 * worker sin cortar.
 */
class AccionesDeDescargaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCELAR) return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val app = context.applicationContext
        val pendiente = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppGraph.from(app).localDownloads.cancel(episodeId)
            } finally {
                pendiente.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCELAR = "com.arkiv.player.CANCELAR_DESCARGA"
        const val EXTRA_EPISODE_ID = "episodeId"
    }
}
