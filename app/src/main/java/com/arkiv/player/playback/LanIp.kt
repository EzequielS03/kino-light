package com.arkiv.player.playback

import android.content.Context
import android.net.wifi.WifiManager

/**
 * IP del dispositivo en la LAN, para servers HTTP locales que un renderer (TV/Chromecast/DLNA)
 * tenga que poder alcanzar (el proxy de canal en vivo, el cast transcodificado).
 *
 * Antes vivía como `TorrentEngine.lanIp()` (torrent se borró en la poda de esta rama); es un
 * helper genérico que nunca dependió de nada de torrent, así que se porta tal cual. Wi-Fi primero
 * (la interfaz que suele compartir LAN con el renderer); si no hay (p. ej. Fire TV cableado),
 * escanea las interfaces por una IPv4 site-local.
 */
object LanIp {
    fun current(context: Context): String? = wifiIp(context) ?: siteLocalIp()

    @Suppress("DEPRECATION")
    private fun wifiIp(context: Context): String? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    /** IPv4 privada de una interfaz activa (fallback de [wifiIp]); descarta loopback/virtuales, VPN
     *  (tun) y datos móviles (rmnet) para no anunciar una IP inalcanzable desde el renderer. */
    private fun siteLocalIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { val n = it.name.orEmpty(); n.startsWith("tun") || n.startsWith("rmnet") || n.startsWith("ap") }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.address } }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
