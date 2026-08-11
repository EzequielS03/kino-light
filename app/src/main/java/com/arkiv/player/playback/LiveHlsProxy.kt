package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Proxy HLS local para la TV en vivo de Magis.
 *
 * Existe porque VLC solo deja pasar `:http-referrer` y `:http-user-agent`, y el CDN del vivo
 * exige `Content-Auth` y `Content-License`. Además el `Content-Auth` **caduca en segundos**:
 * cada segmento necesita una firma fresca, así que no alcanza con entregarle a VLC un m3u8
 * estático.
 *
 * El proxy baja el playlist, reescribe las URLs absolutas de los `.ts` hacia sí mismo y pone
 * las cabeceras en cada petición al origen. VLC solo ve `127.0.0.1`.
 */
class LiveHlsProxy(private val firmas: FirmaDeSegmentos) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var sesion: LiveSession? = null

    val port: Int get() = server?.localPort ?: -1

    private suspend fun contentAuth(): String {
        val s = sesion ?: error("no hay sesión de canal")
        val f = firmas.firmar(s.token)
        return "${s.authBase}&sign2_method=sign_o3&instance=0" +
            "&start_moment=${f.moment}&sign2=${f.sign2}"
    }

    /** Idempotente, igual que [ArchiveCacheProxy.start]. [bindLan] es para el Chromecast. */
    @Synchronized
    fun start(bindLan: Boolean = false): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = if (bindLan) ServerSocket(0) else ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = sock
        running = true
        Thread {
            while (running && !sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread { atender(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return sock.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        sesion = null
    }

    /** Fija la sesión del canal y devuelve la URL que se le pasa a VLC. */
    fun urlPara(nueva: LiveSession): String {
        sesion = nueva
        if (port <= 0) start()
        return "http://127.0.0.1:$port/live.m3u8"
    }

    private fun atender(socket: Socket) = socket.use { s ->
        val entrada = s.getInputStream().bufferedReader()
        val linea = entrada.readLine() ?: return
        val ruta = linea.split(" ").getOrNull(1) ?: return
        val salida = s.getOutputStream()
        when {
            ruta.startsWith("/live.m3u8") -> servirPlaylist(salida)
            ruta.startsWith("/seg?") -> servirSegmento(ruta, salida)
            else -> salida.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
    }

    /**
     * Pide al origen con la firma vigente y, ante un 403, refresca la firma y reintenta
     * **una** vez. Un 403 que sobrevive al reintento significa que caducó la sesión del
     * canal, no la firma: quien reproduce debe re-resolver (ver [PlayerViewModel]).
     */
    private fun pedirAlOrigen(url: String): HttpURLConnection? {
        repeat(2) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Auth", runBlocking { contentAuth() })
                setRequestProperty("Content-License", sesion!!.license)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("App", APP)
                setRequestProperty("App-Version", APP_VERSION)
                setRequestProperty("X-Buffer", "0")
            }
            if (c.responseCode != 403) return c
            // El aviso es lo que permite a FirmaConRespaldo detectar que el algoritmo
            // dejó de servir y conmutar al gateway. Sin esto, el respaldo nunca entra.
            firmas.rechazada()
            c.disconnect()
        }
        return null
    }

    private fun servirPlaylist(salida: java.io.OutputStream) {
        val s = sesion ?: return
        val c = pedirAlOrigen("http://${s.cflHost}/live/${s.channel}.m3u8")
        if (c == null || c.responseCode != 200) {
            salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val cuerpo = c.inputStream.bufferedReader().readText().lineSequence().joinToString("\n") { ln ->
            val t = ln.trim()
            if (t.startsWith("http") && t.contains(".ts")) {
                "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(t, "UTF-8")}"
            } else ln
        } + "\n"
        val bytes = cuerpo.toByteArray()
        salida.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        salida.write(bytes)
    }

    private fun servirSegmento(ruta: String, salida: java.io.OutputStream) {
        val u = URLDecoder.decode(ruta.substringAfter("u=").substringBefore("&"), "UTF-8")
        val c = pedirAlOrigen(u)
        if (c == null) {
            salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        salida.write("HTTP/1.1 ${c.responseCode} OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        runCatching { c.inputStream.copyTo(salida, 64 * 1024) }
    }

    companion object {
        const val UA = "Ranger/4.9.4-17294ac0"
        private const val APP = "com.android.msandroid"
        private const val APP_VERSION = "49902"
    }
}
