package com.arkiv.player.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Avisa cuando el aparato cambia de red, para que la capa de entrega abandone lo que quedó atado a
 * la anterior.
 *
 * Es la cáscara de Android y nada más: TODA la decisión vive en [SeguidorDeRed], que es puro y está
 * cubierto por tests. Acá solo se registra el callback y se traduce `Network` → `networkHandle`.
 *
 * Se escucha la red POR DEFECTO (`registerDefaultNetworkCallback`) y no todas: la que nos importa es
 * exactamente por la que salen nuestros sockets, que es esa.
 *
 * Por qué existe: hasta ahora no había un solo `ConnectivityManager` en la app, así que un cambio de
 * WiFi a datos a mitad de reproducción dejaba el socket viejo colgado contra una interfaz que ya no
 * existe, y la lectura esperaba hasta el plazo del CUERPO de [PoliticaOrigen] —90 s en archive, 30 s
 * en magis— antes de que empezara siquiera el primer reintento. Ver [CambioDeRed].
 *
 * Portado del reporte de estado del sistema de la app original de magis, que le manda a su motor de
 * entrega los cambios de red además de pantalla, primer plano y Doze (`dd/AbstractC3049d`).
 */
class VigilanteDeRed(
    private val context: Context,
    private val alCambiarDeRed: (motivo: String) -> Unit,
) {

    private val seguidor = SeguidorDeRed()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (seguidor.alAparecer(network.networkHandle)) {
                alCambiarDeRed("cambió la red")
            }
        }

        override fun onLost(network: Network) {
            if (seguidor.alPerderse(network.networkHandle)) {
                alCambiarDeRed("se perdió la red")
            }
        }
    }

    /**
     * Empieza a escuchar. Envuelto porque `registerDefaultNetworkCallback` puede tirar
     * (`SecurityException` sin el permiso, o el límite de callbacks del sistema) y quedarse sin
     * vigilante es exactamente el comportamiento de antes: peor, pero no roto.
     */
    fun empezar() {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(callback)
            android.util.Log.w("ArkivRed", "network watchdog active")
        }.onFailure {
            android.util.Log.w("ArkivRed", "couldn't watch the network: ${it.message}")
        }
    }

    fun parar() {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }
}
