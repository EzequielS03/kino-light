package com.arkiv.player.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/** Enruta cada comando por LAN (si el peer está en la misma red) o por la nube; con failover. */
class TransportRouter(
    private val lan: RemoteTransport,
    private val cloud: RemoteTransport,
) {
    suspend fun sendPlay(p: PlayPayload, seq: Long): Boolean = route({ it.sendPlay(p, seq) })

    suspend fun sendKey(key: String, seq: Long): Boolean = route({ it.sendKey(key, seq) })

    suspend fun sendSubPrefs(json: String, seq: Long): Boolean = route({ it.sendSubPrefs(json, seq) })
    suspend fun sendWebQuality(value: String, seq: Long): Boolean = route({ it.sendWebQuality(value, seq) })

    /**
     * Transporte del miniplayer: derecho a la nube, **sin sondear la LAN**.
     *
     * A diferencia de play/key/subprefs/webquality, [LanTransport] no implementa `sendTransport`
     * (queda el `false` por defecto de la interfaz), así que la rama LAN jamás podía tener éxito. Lo
     * único que aportaba era el costo: `lan.reachable()` bloquea ~2,5s en el descubrimiento multicast
     * de `SyncManager.discover` cuando no contesta nadie —que es siempre, porque la LAN entre celu y
     * TV está aislada por el router— y encima dejaba un "LAN falló el envío" engañoso en cada toque.
     * Un botón de transporte con 2,5s de retardo se siente roto.
     *
     * Si algún día LanTransport implementa `sendTransport`, esto vuelve a [route].
     */
    suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean {
        if (!cloud.reachable()) {
            android.util.Log.w("ArkivRemote", "transporte '$type': la nube no está disponible")
            return false
        }
        val r = cloud.sendTransport(type, payload, seq)
        android.util.Log.i("ArkivRemote", "transporte '$type' -> enviado por NUBE (result=$r)")
        return r
    }

    private suspend fun route(action: suspend (RemoteTransport) -> Boolean): Boolean {
        val lanOk = lan.reachable()
        android.util.Log.i("ArkivRemote", "route: lan.reachable=$lanOk")
        if (lanOk) {
            if (action(lan)) { android.util.Log.i("ArkivRemote", "route -> enviado por LAN ✓"); return true }
            android.util.Log.w("ArkivRemote", "route: LAN falló el envío, failover a la nube")
        }
        val cloudOk = cloud.reachable()
        android.util.Log.i("ArkivRemote", "route: cloud.reachable=$cloudOk")
        if (cloudOk) {
            val r = action(cloud)
            android.util.Log.i("ArkivRemote", "route -> enviado por NUBE (result=$r)")
            return r
        }
        android.util.Log.w("ArkivRemote", "route: ni LAN ni nube disponibles")
        return false
    }

    fun incoming(): Flow<RemoteCommand> = merge(lan.incoming(), cloud.incoming())
}
