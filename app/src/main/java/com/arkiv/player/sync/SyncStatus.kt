package com.arkiv.player.sync

/** Estado de la sincronización LAN, para mostrar feedback en la UI. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    /** sent=true cuando este dispositivo ENVIÓ su biblioteca (fuente / teléfono). */
    data class Done(val peers: Int, val changes: Int, val sent: Boolean = false) : SyncStatus
    data class Error(val message: String) : SyncStatus
}
