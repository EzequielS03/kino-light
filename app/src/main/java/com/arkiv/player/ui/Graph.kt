package com.arkiv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.AppGraph
import com.arkiv.player.ArkivApp

/** Acceso al grafo de dependencias desde composables. */
@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return (context.applicationContext as ArkivApp).graph
}

/** Formatea bytes a un texto legible (MB/GB). */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) String.format("%.1f GB", mb / 1000) else String.format("%.0f MB", mb)
}

/** Formatea milisegundos a m:ss o h:mm:ss. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
