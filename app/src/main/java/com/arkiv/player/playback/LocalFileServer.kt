package com.arkiv.player.playback

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Sirve UN archivo del almacenamiento local por HTTP con soporte de Range, para que el Chromecast y
 * el DLNA puedan reproducir lo que se guardó en el dispositivo (no pueden abrir un `file://`).
 *
 * Un archivo a la vez: [serve] reemplaza al anterior. Same pattern as the torrent HTTP server
 * (source removed in this branch's pruning), with one key difference: here the server RESTARTS
 * (a new `ServerSocket`, a new port) every time the served file changes, instead of reusing the
 * same port for different files. Motivo: si dos archivos compartieran URL, una
 * conexión que ya hizo HEAD sobre el archivo viejo (con su Content-Length) podría mandar el GET
 * DESPUÉS del cambio y terminar leyendo el archivo nuevo con la cabecera vieja — un escenario real
 * en el flujo de "cambiar de episodio mientras se castea". Con un puerto nuevo por archivo, una
 * conexión vieja sigue sirviendo lo suyo sin verse afectada (`ServerSocket.close()` no toca los
 * sockets ya aceptados) y cualquier conexión nueva fuerza al cliente a reconectar de cero contra el
 * archivo correcto.
 */
class LocalFileServer(private val lanIp: () -> String?) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var current: File? = null

    /** Devuelve la URL alcanzable desde la LAN, o null si no hay IP (sin red) o el archivo no está. */
    @Synchronized
    fun serve(file: File): String? {
        if (!file.exists()) return null
        if (server == null || file != current) {
            closeServer()
            current = file
            start(file)
        }
        val ip = lanIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/file"
    }

    @Synchronized
    fun stop() {
        closeServer()
        current = null
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
    }

    /** Arranca un ServerSocket nuevo atado a [file]: esa es la única generación de conexiones que va
     *  a aceptar hasta el próximo [closeServer] (ver KDoc de la clase). */
    private fun start(file: File) {
        val s = ServerSocket(0)
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                // [file] se captura acá, no se relee `current`: un cambio de archivo durante una
                // conexión en vuelo (o mientras se espera el accept) no la afecta.
                Thread { runCatching { handle(socket, file) }.onFailure { Log.w(TAG, "handle: $it") } }
                    .apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    // Explicit Unit: the body ends in a Log call, and android.util.Log returns Int, which would
    // otherwise infer this function as Int and break the bare `return`s inside it.
    private fun handle(socket: Socket, file: File): Unit = socket.use { sock ->
        val sockIn = sock.getInputStream()
        // Lectura byte a byte hasta la línea en blanco que cierra las cabeceras — mismo enfoque que
        // TorrentStreamServer.serve(), no un BufferedReader sobre el InputStream del socket. Un
        // BufferedReader tira hacia su buffer interno más bytes de los que consume readLine(), lo
        // cual acá no llegaría a corromper nada (no hay body HTTP que leer después de las cabeceras),
        // pero preferimos el patrón ya probado del proyecto antes que introducir uno nuevo. El cap de
        // 8KB evita quedar bloqueados leyendo para siempre si un cliente abre la conexión y nunca
        // termina de mandar las cabeceras.
        val header = StringBuilder()
        val one = ByteArray(1)
        while (sockIn.read(one) == 1) {
            header.append(one[0].toInt().toChar())
            if (header.endsWith("\r\n\r\n")) break
            if (header.length > 8192) break
        }
        val lines = header.toString().split("\r\n")
        val reqLine = lines.firstOrNull().orEmpty()
        if (reqLine.isBlank()) return
        val method = reqLine.substringBefore(' ')
        // Who asked and for what. This is the line that splits a failed cast in two: if the
        // receiver never shows up here, the problem is reachability or a load that never happened;
        // if it shows up and then gives up, the problem is what we serve it.
        val client = runCatching { sock.inetAddress?.hostAddress }.getOrNull() ?: "?"
        val userAgent = lines.firstOrNull { it.startsWith("User-Agent:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        Log.i(TAG, "request from $client · $reqLine · agent=$userAgent")

        val size = file.length()
        val maxIndex = (size - 1).coerceAtLeast(0)
        var start = 0L
        var end = maxIndex
        // Soporta "bytes=START-" (lo más común: Chromecast/DLNA arrancando o retomando desde un
        // punto) Y "bytes=START-END" (rango cerrado). El brief original solo leía el inicio; parsear
        // también el final es barato (ya teníamos el tamaño completo del archivo a mano) y evita
        // servir de más si algún cliente sí pide un rango cerrado.
        val rangeValue = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val hasRange = rangeValue != null && rangeValue.startsWith("bytes=")
        if (hasRange) {
            val parts = rangeValue!!.removePrefix("bytes=").split("-")
            start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            end = parts.getOrNull(1)?.toLongOrNull() ?: maxIndex
        }
        start = start.coerceIn(0, maxIndex)
        end = end.coerceIn(start, maxIndex)
        val length = (end - start + 1).coerceAtLeast(0)

        val status = if (hasRange) "206 Partial Content" else "200 OK"
        val rangeHeader = if (hasRange) "Content-Range: bytes $start-$end/$size\r\n" else ""
        val responseHeader = "HTTP/1.1 $status\r\n" +
            "Content-Type: ${mimeOf(file)}\r\n" +
            "Accept-Ranges: bytes\r\n" +
            rangeHeader +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n\r\n"

        Log.i(
            TAG,
            "-> $client $status · type=${mimeOf(file)} · range=${rangeValue ?: "(none)"} " +
                "· serving $length of $size bytes",
        )
        val out = sock.getOutputStream()
        out.write(responseHeader.toByteArray())
        // HEAD: el Chromecast lo manda antes del GET para conocer tamaño y tipo.
        if (method == "HEAD") { out.flush(); return }

        var sent = 0L
        val outcome = runCatching {
            file.inputStream().use { input ->
                input.skip(start)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    remaining -= n
                }
            }
            out.flush()
        }
        // How it ended matters as much as that it started: a receiver that cannot parse what it is
        // being served hangs up after the first few KB, which shows up here as a short send plus a
        // broken pipe -- not as any error on the sending side.
        Log.i(
            TAG,
            "<- $client sent $sent/$length bytes" +
                (outcome.exceptionOrNull()?.let { " · CUT OFF: $it" } ?: " · complete"),
        )
    }

    /**
     * El `Content-Type` sale de los BYTES, no de la extensión. Ver [ContenedorDeVideo].
     *
     * Acá el nombre miente sistemáticamente: `LocalFilePaths.fileNameFor` guarda como `.mp4` todo
     * lo que no traiga una extensión de video reconocible en el origen, y a la descarga de la NUC
     * le llega una URL de página web —sin extensión— aunque yt-dlp haya producido un mkv. Como
     * este servidor es el que alimenta al Chromecast, ese `.mp4` inventado se convertía en un
     * `video/mp4` que el receptor no podía cumplir.
     */
    private fun mimeOf(file: File): String = ContenedorDeVideo.deArchivo(file).mime

    private companion object { const val TAG = "ArkivLocalServer" }
}
