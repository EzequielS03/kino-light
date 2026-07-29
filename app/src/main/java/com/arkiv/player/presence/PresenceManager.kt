package com.arkiv.player.presence

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Heartbeat de presencia: mientras el dispositivo esté autenticado, actualiza
 * periódicamente `online`/`lastSeen` en el registro `devices` de PocketBase.
 */
class PresenceManager(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            while (true) {
                try {
                    val s = deviceAuth.session.value
                    if (s != null) {
                        client.updateRecord("devices", s.recordId,
                            mapOf("online" to true, "lastSeen" to java.time.Instant.now().toString()), s.token)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ArkivPB", "presence heartbeat falló: ${e.message}")
                    val msg = e.message.orEmpty()
                    if (msg.contains("wasn't found", ignoreCase = true) ||
                        msg.contains("not found", ignoreCase = true) ||
                        msg.contains("404")
                    ) {
                        // El record de este device ya no existe en el server (p. ej. desvinculado).
                        // Re-registrarse solo para no quedar atascado para siempre (guard anti-bucle
                        // dentro de onDeviceRecordMissing()).
                        runCatching { deviceAuth.onDeviceRecordMissing() }
                    }
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
}
