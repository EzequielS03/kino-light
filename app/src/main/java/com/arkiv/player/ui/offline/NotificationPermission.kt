package com.arkiv.player.ui.offline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Pide POST_NOTIFICATIONS **en el momento en que hace falta** (justo al disparar una descarga a la
 * NUC), no al abrir la app: mismo criterio contextual que el permiso de cámara en
 * `QrScannerScreen` -- el usuario entiende para qué se le está pidiendo.
 *
 * El permiso está declarado en el manifiesto, pero desde Android 13 (API 33) además hay que
 * pedirlo en runtime; sin esto la notificación de "descarga completa" del
 * [com.arkiv.player.data.offline.NucDownloadCheckWorker] se descartaba en silencio. Por debajo de
 * API 33 el permiso no existe y las notificaciones funcionan sin pedir nada.
 *
 * Devuelve una lambda para invocar al iniciar la descarga. No bloquea nada: si el usuario dice que
 * no, la descarga igual arranca y el progreso se sigue viendo en la pantalla de Descargas.
 */
@Composable
fun rememberPostNotificationsRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
