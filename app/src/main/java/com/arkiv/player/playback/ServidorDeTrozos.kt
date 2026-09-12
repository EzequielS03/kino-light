package com.arkiv.player.playback

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Serves every file in one directory, over the LAN, at the same time.
 *
 * [LocalFileServer] cannot do this: it holds a single file and restarts its socket whenever that
 * file changes, which is right when one title is being cast and wrong here. A title cast as a
 * QUEUE of chunks needs several of them reachable at once -- the receiver holds a playlist of
 * URLs and fetches whichever it is about to play, including ones queued minutes ago.
 *
 * Range is supported because the receiver asks for one: a chunk is a complete mp4 and it seeks
 * inside it normally.
 *
 * Only files directly inside [carpeta] are served, and the name is taken as a bare filename with
 * no path separators -- a request is a URL from the network, and a media server that resolves
 * `../` reaches the whole app's storage.
 */
class ServidorDeTrozos(private val carpeta: File, private val lanIp: () -> String?) {

    @Volatile private var server: ServerSocket? = null

    /** The LAN url for [archivo], starting the server if needed. Null without an ip. */
    @Synchronized
    fun serve(archivo: File): String? {
        if (!archivo.exists()) return null
        if (server == null || server?.isClosed == true) start()
        val ip = lanIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/${archivo.name}"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun start() {
        // No bind address: every interface, the same convention LiveHlsProxy documents and
        // ArchiveCacheProxy relies on. The receiver reaches this over the LAN.
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { atender(socket) }.onFailure { Log.w(TAG, "serve: $it") } }
                    .apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "chunk server up on port ${s.localPort}")
    }

    private fun atender(socket: Socket): Unit = socket.use { sock ->
        val entrada = sock.getInputStream()
        val cabecera = StringBuilder()
        val uno = ByteArray(1)
        while (entrada.read(uno) == 1) {
            cabecera.append(uno[0].toInt().toChar())
            if (cabecera.endsWith("\r\n\r\n") || cabecera.length > 8192) break
        }
        val lineas = cabecera.toString().split("\r\n")
        val peticion = lineas.firstOrNull().orEmpty()
        val metodo = peticion.substringBefore(' ')
        val ruta = peticion.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val nombre = runCatching { URLDecoder.decode(ruta.trimStart('/'), "UTF-8") }
            .getOrDefault("")
            .substringAfterLast('/')   // a request is network input: no traversing out of here
        val archivo = File(carpeta, nombre)
        val out = sock.getOutputStream()

        if (nombre.isBlank() || !archivo.exists() || !archivo.isFile) {
            Log.w(TAG, "404 $nombre")
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val total = archivo.length()
        val rango = lineas.firstOrNull { it.startsWith("Range:", true) }
            ?.substringAfter(':')?.trim()
        val r = RangeHeader.parse(rango)
        val desde = (r?.start ?: 0L).coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val hasta = (r?.end ?: (total - 1)).coerceAtMost(total - 1)
        val largo = (hasta - desde + 1).coerceAtLeast(0L)

        val estado = if (r != null) "206 Partial Content" else "200 OK"
        val contentRange = if (r != null) "Content-Range: bytes $desde-$hasta/$total\r\n" else ""
        out.write(
            (
                "HTTP/1.1 $estado\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    contentRange +
                    "Content-Length: $largo\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        if (metodo == "HEAD") { out.flush(); return }

        var enviado = 0L
        java.io.RandomAccessFile(archivo, "r").use { raf ->
            raf.seek(desde)
            val buf = ByteArray(64 * 1024)
            while (enviado < largo) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), largo - enviado).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                enviado += n
            }
        }
        out.flush()
        Log.i(TAG, "-> $nombre $estado ${enviado}B of $total")
    }

    private companion object { const val TAG = "ArkivTrozos" }
}
