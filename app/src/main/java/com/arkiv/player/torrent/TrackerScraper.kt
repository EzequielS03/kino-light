package com.arkiv.player.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer

/**
 * Scrape de salud del swarm por UDP (BEP 15), para traer los seeders REALES de la red — muchas fuentes
 * (pelispanda/elitetorrent/dontorrent) no reportan seeders y quedan en 1. Consulta los trackers UDP del
 * magnet y devuelve el máximo de seeders visto. Aislado de la sesión de streaming (socket propio).
 *
 * Protocolo (BEP 15):
 *  connect  req  (16B): protocol_id(0x41727101980) action(0) transaction_id
 *  connect  resp (16B): action(0) transaction_id connection_id
 *  scrape   req  (16B+20*n): connection_id action(2) transaction_id infohash...
 *  scrape   resp (8B+12*n): action(2) transaction_id [seeders completed leechers]...
 */
class TrackerScraper(
    private val timeoutMs: Int = 3500,
    private val txId: () -> Int = { (System.nanoTime() and 0x7fffffff).toInt() },
) {
    companion object {
        const val PROTOCOL_ID = 0x41727101980L
        const val ACTION_CONNECT = 0
        const val ACTION_SCRAPE = 2

        /** Trackers públicos de respaldo cuando el magnet no trae `udp://` propios. */
        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.stealth.si:80/announce",
        )

        /** Extrae los trackers `udp://` de un magnet (params &tr=), decodificados. */
        fun trackersFromMagnet(magnet: String?): List<String> {
            if (magnet.isNullOrBlank()) return emptyList()
            return Regex("[?&]tr=([^&]+)").findAll(magnet)
                .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .filter { it.startsWith("udp://") }
                .toList()
        }

        /** Hex de 40 chars → 20 bytes del infohash. Null si no es un hash válido. */
        fun hexToInfoHash(hex: String?): ByteArray? {
            val h = hex?.trim()?.lowercase() ?: return null
            if (h.length != 40 || !h.all { it in "0123456789abcdef" }) return null
            return ByteArray(20) { ((h[it * 2].digitToInt(16) shl 4) or h[it * 2 + 1].digitToInt(16)).toByte() }
        }
    }

    // --- helpers puros (testeables sin red) ---

    fun buildConnectRequest(transactionId: Int): ByteArray =
        ByteBuffer.allocate(16).putLong(PROTOCOL_ID).putInt(ACTION_CONNECT).putInt(transactionId).array()

    /** Devuelve connection_id si la respuesta es un connect válido para [transactionId], si no null. */
    fun parseConnectResponse(resp: ByteArray, transactionId: Int): Long? {
        if (resp.size < 16) return null
        val b = ByteBuffer.wrap(resp)
        if (b.int != ACTION_CONNECT) return null
        if (b.int != transactionId) return null
        return b.long
    }

    fun buildScrapeRequest(connectionId: Long, transactionId: Int, infoHash: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + 20).putLong(connectionId).putInt(ACTION_SCRAPE)
            .putInt(transactionId).put(infoHash).array()

    /** Devuelve (seeders, completed, leechers) del primer hash, o null si la respuesta no es válida. */
    fun parseScrapeResponse(resp: ByteArray, transactionId: Int): Triple<Int, Int, Int>? {
        if (resp.size < 8 + 12) return null
        val b = ByteBuffer.wrap(resp)
        if (b.int != ACTION_SCRAPE) return null
        if (b.int != transactionId) return null
        return Triple(b.int, b.int, b.int)
    }

    // --- red ---

    private fun udpRoundTrip(sock: DatagramSocket, addr: InetSocketAddress, payload: ByteArray): ByteArray? {
        sock.send(DatagramPacket(payload, payload.size, addr))
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        return runCatching { sock.receive(pkt); buf.copyOf(pkt.length) }.getOrNull()
    }

    /** Scrape de UN tracker. Devuelve seeders o null si falla/timeout. */
    suspend fun scrapeOne(trackerUrl: String, infoHash: ByteArray): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI(trackerUrl)
            val addr = InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80)
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val tx1 = txId()
                val cResp = udpRoundTrip(sock, addr, buildConnectRequest(tx1)) ?: return@use null
                val connId = parseConnectResponse(cResp, tx1) ?: return@use null
                val tx2 = txId()
                val sResp = udpRoundTrip(sock, addr, buildScrapeRequest(connId, tx2, infoHash)) ?: return@use null
                parseScrapeResponse(sResp, tx2)?.first
            }
        }.getOrNull()
    }

    /**
     * Seeders reales de un infohash: prueba los trackers del magnet (+ fallbacks) y devuelve el MÁXIMO
     * visto. Null si ningún tracker respondió (torrent solo-DHT o trackers muertos) → el caller conserva
     * el valor de la fuente.
     */
    suspend fun seeders(infoHashHex: String?, magnetTrackers: List<String>): Int? {
        val ih = hexToInfoHash(infoHashHex) ?: return null
        val trackers = (magnetTrackers.filter { it.startsWith("udp://") } + FALLBACK_TRACKERS).distinct().take(5)
        var best: Int? = null
        for (t in trackers) {
            val s = scrapeOne(t, ih)
            if (s != null && (best == null || s > best!!)) best = s
        }
        return best
    }
}
