package com.arkiv.player.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.view.KeyEvent
import com.arkiv.player.data.ArkivRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Estado de presencia de la TV: si está disponible y cuántos descubrimientos vacíos van seguidos. */
data class TvPresence(val available: Boolean, val misses: Int)

/**
 * Regla de presencia "pegajosa": el descubrimiento LAN por UDP es probabilístico (el multicast en
 * WiFi se pierde seguido), así que un solo fallo NO debe declarar la TV como no disponible. El flag
 * solo baja tras [threshold] descubrimientos vacíos consecutivos; un único éxito lo restablece.
 */
fun reduceTvPresence(prev: TvPresence, found: Boolean, threshold: Int = SyncManager.MISS_THRESHOLD): TvPresence =
    if (found) {
        TvPresence(available = true, misses = 0)
    } else {
        val misses = prev.misses + 1
        TvPresence(available = if (misses >= threshold) false else prev.available, misses = misses)
    }

/** Hosts .1..254 de la /24 de [myIp], excluyendo la propia IP. Vacío si myIp es inválida. */
fun subnetHosts(myIp: String?): List<String> {
    val pre = myIp?.substringBeforeLast('.', "")?.takeIf { it.count { c -> c == '.' } == 2 } ?: return emptyList()
    val last = myIp.substringAfterLast('.').toIntOrNull() ?: return emptyList()
    return (1..254).filter { it != last }.map { "$pre.$it" }
}

/**
 * Sincronización LAN entre dispositivos Arkiv (teléfono <-> TV):
 * - Servidor HTTP: GET /sync/export (da nuestra DB) y POST /sync/import (mergea la del otro).
 * - Descubrimiento por multicast UDP.
 * - syncNow(): descubre peers, baja la DB de cada uno y le sube la nuestra (bidireccional,
 *   last-write-wins).
 */
class SyncManager(
    context: Context,
    private val repo: ArkivRepository,
) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Cliente aparte de timeouts cortos para el control remoto (baja latencia).
    private val keyClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    // Cliente de timeouts muy cortos para el escaneo de subred (254 hosts, no podemos esperar
    // el timeout normal en cada uno).
    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .build()

    // Último peer (TV) descubierto, para mandar teclas sin re-descubrir cada vez.
    @Volatile
    private var cachedPeer: Peer? = null

    private var httpServer: ServerSocket? = null
    private val httpPort get() = httpServer?.localPort ?: 0

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    // Comando remoto "reproducir este episodio" recibido de otro dispositivo (lo escucha el TV).
    private val _remotePlay = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val remotePlay: SharedFlow<String> = _remotePlay.asSharedFlow()

    // Teclas del control remoto (keycodes de Android) recibidas del teléfono; las inyecta el TV.
    private val _remoteKeys = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val remoteKeys: SharedFlow<Int> = _remoteKeys.asSharedFlow()

    // ¿Hay otro dispositivo Arkiv (TV) alcanzable? Actualizado en cada descubrimiento.
    private val _tvAvailable = MutableStateFlow(false)
    val tvAvailable: StateFlow<Boolean> = _tvAvailable.asStateFlow()

    // Presencia "pegajosa": un descubrimiento vacío puntual no apaga el flag (ver reduceTvPresence).
    @Volatile
    private var presence = TvPresence(available = false, misses = 0)

    private fun updateTvAvailable(found: Boolean) {
        presence = reduceTvPresence(presence, found)
        _tvAvailable.value = presence.available
    }

    private data class Peer(val ip: String, val port: Int)

    @Synchronized
    fun start() {
        if (httpServer == null || httpServer!!.isClosed) startHttp()
        startResponder()
    }

    private fun startHttp() {
        val ss = runCatching { ServerSocket(DIRECT_PORT) }.getOrElse { ServerSocket(0) }
        httpServer = ss
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread { handleHttp(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handleHttp(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val header = StringBuilder()
                val one = ByteArray(1)
                while (input.read(one) == 1) {
                    header.append(one[0].toInt().toChar())
                    if (header.endsWith("\r\n\r\n")) break
                    if (header.length > 16384) break
                }
                val lines = header.toString().split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val method = requestLine.substringBefore(' ')
                val path = requestLine.substringAfter(' ').substringBefore(' ')
                val out = s.getOutputStream()

                if (method == "GET" && path.startsWith("/sync/export")) {
                    val json = runBlocking { repo.exportForSync().toJson() }
                    writeJson(out, json)
                } else if (method == "GET" && path.startsWith("/ping")) {
                    // Identificación por IP directa: confirma "soy un TV Arkiv" y expone el puerto real.
                    val bytes = "$REPLY:$httpPort".toByteArray(Charsets.UTF_8)
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    out.write(bytes)
                } else if (method == "POST" && path.startsWith("/play")) {
                    val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = input.read(body, read, len - read)
                        if (r < 0) break
                        read += r
                    }
                    val episodeId = String(body, Charsets.UTF_8).trim()
                    if (episodeId.isNotBlank()) _remotePlay.tryEmit(episodeId)
                    writeJson(out, "{\"ok\":true}")
                } else if (method == "POST" && path.startsWith("/key")) {
                    val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = input.read(body, read, len - read)
                        if (r < 0) break
                        read += r
                    }
                    val code = keyCodeFor(String(body, Charsets.UTF_8).trim())
                    if (code != null) _remoteKeys.tryEmit(code)
                    writeJson(out, "{\"ok\":true}")
                } else if (method == "POST" && path.startsWith("/sync/import")) {
                    val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = input.read(body, read, len - read)
                        if (r < 0) break
                        read += r
                    }
                    // Las dos puntas mergean igual (last-write-wins); no hay emisor ni receptor.
                    val changes = runCatching {
                        runBlocking {
                            repo.mergeFromSync(SyncSnapshot.fromJson(String(body, Charsets.UTF_8)))
                        }
                    }.getOrDefault(0)
                    writeJson(out, "{\"merged\":$changes}")
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun writeJson(out: java.io.OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
    }

    // --- Descubrimiento por multicast ---

    private var responderStarted = false

    @Synchronized
    private fun startResponder() {
        if (responderStarted) return
        responderStarted = true
        Thread {
            val lock = wifi.createMulticastLock("arkiv-sync").apply {
                setReferenceCounted(false); runCatching { acquire() }
            }
            try {
                // Bucle externo: si el socket muere (cambio de estado de energía del WiFi, error
                // transitorio en receive()), lo recreamos y seguimos respondiendo. Antes un solo
                // error rompía el while y mataba el responder PARA SIEMPRE (hasta reiniciar la app),
                // dejando al TV "invisible" para el descubrimiento del teléfono.
                while (true) {
                    try {
                        MulticastSocket(DISCOVERY_PORT).use { socket ->
                            socket.broadcast = true
                            runCatching { socket.joinGroup(InetAddress.getByName(GROUP)) }
                            val buf = ByteArray(256)
                            while (true) {
                                val packet = DatagramPacket(buf, buf.size)
                                socket.receive(packet)
                                val msg = String(packet.data, 0, packet.length)
                                if (msg.startsWith(MAGIC) && httpPort > 0) {
                                    val reply = "$REPLY:$httpPort".toByteArray()
                                    socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ArkivSync", "responder caído, reintentando en 1s: ${e.message}")
                        runCatching { Thread.sleep(1000) }
                    }
                }
            } finally {
                runCatching { lock.release() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun discover(timeoutMs: Long = 2500): List<Peer> {
        val myIp = wifiIp()
        val peers = LinkedHashSet<Peer>()
        try {
            val socket = DatagramSocket().apply { soTimeout = 350; broadcast = true }
            val query = MAGIC.toByteArray()
            // Mandar por multicast Y broadcast (255.255.255.255 + broadcast de subred):
            // el multicast en WiFi se pierde seguido, el broadcast suele llegar.
            val targets = buildList {
                runCatching { add(InetAddress.getByName(GROUP)) }
                runCatching { add(InetAddress.getByName("255.255.255.255")) }
                myIp?.substringBeforeLast('.')?.let { pre ->
                    runCatching { add(InetAddress.getByName("$pre.255")) }
                }
            }
            fun sendQuery() = targets.forEach { addr ->
                runCatching { socket.send(DatagramPacket(query, query.size, addr, DISCOVERY_PORT)) }
            }
            sendQuery()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    if (msg.startsWith("$REPLY:") && ip != myIp) {
                        val port = msg.substringAfter("$REPLY:").trim().toIntOrNull() ?: continue
                        peers.add(Peer(ip, port))
                    }
                } catch (_: SocketTimeoutException) {
                    sendQuery() // reenviar en cada timeout: multicast se pierde seguido
                }
            }
            socket.close()
        } catch (_: Exception) {
        }
        // Fallback: si el multicast/broadcast no encontró nada (p. ej. client isolation en la
        // red WiFi bloqueando ambos), escanea la subred por IP directa en DIRECT_PORT.
        if (peers.isEmpty()) {
            scanSubnetForPeer(myIp)?.let { peers.add(it) }
        }
        val list = peers.toList()
        cachedPeer = list.firstOrNull() ?: cachedPeer
        return list
    }

    /**
     * Escanea la subred /24 de [myIp] buscando un TV Arkiv por IP directa (GET /ping en
     * [DIRECT_PORT]), en paralelo y por chunks para no abrir 254 sockets de golpe. Corta apenas
     * encuentra un peer.
     */
    private fun scanSubnetForPeer(myIp: String?): Peer? {
        val hosts = subnetHosts(myIp)
        if (hosts.isEmpty()) return null
        return runBlocking {
            var found: Peer? = null
            for (chunk in hosts.chunked(48)) {
                if (found != null) break
                val results = chunk.map { host -> async(Dispatchers.IO) { pingHost(host) } }.awaitAll()
                found = results.firstOrNull { it != null }
            }
            found
        }
    }

    private fun pingHost(host: String): Peer? = runCatching {
        val req = Request.Builder().url("http://$host:$DIRECT_PORT/ping").build()
        scanClient.newCall(req).execute().use { resp ->
            val body = if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            if (body.startsWith("$REPLY:")) {
                body.substringAfter("$REPLY:").trim().toIntOrNull()?.let { Peer(host, it) }
            } else {
                null
            }
        }
    }.getOrNull()

    /**
     * Sincroniza con los peers en ambos sentidos: baja su snapshot (mergea) y sube el nuestro.
     * Biblioteca y progreso van en las dos vías, con la misma regla en las dos puntas
     * (last-write-wins por `updatedAt`, borrados como tombstone). Ver [SyncMerge].
     */
    suspend fun syncNow(): SyncStatus = withContext(Dispatchers.IO) {
        _status.value = SyncStatus.Syncing
        val result = try {
            // Si la discovery falla pero ya conocíamos la TV, usar el peer cacheado.
            val peers = discover().ifEmpty { cachedPeer?.let { listOf(it) } ?: emptyList() }
            updateTvAvailable(peers.isNotEmpty())
            if (peers.isEmpty()) {
                // No hay TV en la LAN: en un setup por nube esto es NORMAL, no un fallo. Status
                // neutro (Idle) para que la UI no lo muestre como error ("no se encontró
                // dispositivo en la red WiFi"); el caso con peers reales sigue reportando Done.
                SyncStatus.Idle
            } else {
                val ours = runCatching { repo.exportForSync().toJson() }.getOrNull()
                var changes = 0
                var okPeers = 0
                var lastError: String? = null
                for (peer in peers) {
                    var peerOk = false
                    // Bajar del peer y mergear (items solo si somos la TV; progreso siempre).
                    runCatching {
                        val req = Request.Builder().url("http://${peer.ip}:${peer.port}/sync/export").build()
                        val json = client.newCall(req).execute().use { it.body?.string() }
                        if (json != null) changes += repo.mergeFromSync(SyncSnapshot.fromJson(json))
                        peerOk = true
                    }.onFailure { lastError = it.message }
                    // Subir lo nuestro (el peer lo mergea según su propio rol).
                    if (ours != null) {
                        runCatching {
                            val req = Request.Builder()
                                .url("http://${peer.ip}:${peer.port}/sync/import")
                                .post(ours.toRequestBody("application/json".toMediaType()))
                                .build()
                            client.newCall(req).execute().close()
                            peerOk = true
                        }.onFailure { lastError = it.message }
                    }
                    if (peerOk) okPeers++
                }
                if (okPeers == 0) SyncStatus.Error(lastError ?: "No se pudo conectar con el dispositivo")
                else SyncStatus.Done(peers = okPeers, changes = changes, sent = true)
            }
        } catch (e: Exception) {
            SyncStatus.Error(e.message ?: "Error de sincronización")
        }
        Log.i("ArkivSync", "sync -> $result")
        _status.value = result
        result
    }

    /**
     * Manda "reproducí este episodio" a los otros dispositivos Arkiv (el TV lo recibe y abre
     * el player). Devuelve true si al menos uno aceptó el comando.
     */
    suspend fun playOnTv(episodeId: String): Boolean = withContext(Dispatchers.IO) {
        val peers = discover()
        updateTvAvailable(peers.isNotEmpty())
        var ok = false
        for (peer in peers) {
            runCatching {
                val req = Request.Builder()
                    .url("http://${peer.ip}:${peer.port}/play")
                    .post(episodeId.toRequestBody("text/plain".toMediaType()))
                    .build()
                client.newCall(req).execute().use { if (it.isSuccessful) ok = true }
            }
        }
        ok
    }

    /** Descubre la TV para el control remoto y la deja cacheada. Devuelve si la encontró. */
    suspend fun connectRemote(): Boolean = withContext(Dispatchers.IO) {
        val peers = discover()
        updateTvAvailable(peers.isNotEmpty())
        peers.isNotEmpty()
    }

    /** Manda una tecla (nombre: UP/DOWN/LEFT/RIGHT/OK/BACK/PLAYPAUSE) al app del TV. */
    suspend fun sendKey(key: String): Boolean = withContext(Dispatchers.IO) {
        val peer = cachedPeer ?: discover().firstOrNull() ?: return@withContext false
        val ok = runCatching {
            val req = Request.Builder()
                .url("http://${peer.ip}:${peer.port}/key")
                .post(key.toRequestBody("text/plain".toMediaType()))
                .build()
            keyClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (!ok) cachedPeer = null // reintentar descubrimiento la próxima
        ok
    }

    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    companion object {
        private const val DISCOVERY_PORT = 8770

        /** Puerto FIJO del server HTTP (fallback a ServerSocket(0) si está ocupado). Permite
         *  identificar el TV por IP directa vía /ping cuando multicast/broadcast están bloqueados. */
        private const val DIRECT_PORT = 8771
        private const val GROUP = "239.42.42.42"
        private const val MAGIC = "ARKIV_SYNC_DISCOVER"
        private const val REPLY = "ARKIV_SYNC"

        /** Descubrimientos vacíos consecutivos antes de declarar la TV como no disponible. */
        const val MISS_THRESHOLD = 3

        /** Traduce el nombre de tecla del control remoto a un keycode de Android. */
        fun keyCodeFor(name: String): Int? = when (name.uppercase()) {
            "UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "OK", "CENTER", "SELECT" -> KeyEvent.KEYCODE_DPAD_CENTER
            "BACK" -> KeyEvent.KEYCODE_BACK
            "PLAYPAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> null
        }
    }
}
