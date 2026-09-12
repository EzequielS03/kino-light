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

    /**
     * Serve the file even while something else is still WRITING it.
     *
     * A fragmented MP4 is a chain of self-contained pieces, so a receiver can start on the first
     * one while the rest is still arriving -- which is the entire reason the remux writes one.
     * With this off, casting a remux meant waiting minutes for the whole title before a single
     * frame reached the TV.
     */
    @Volatile
    var creciendo: Boolean = false

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

    /**
     * SPIKE (2026-09-12): the same file wrapped in a one-segment HLS playlist.
     *
     * The Cast receiver refuses a bare MPEG-TS served progressively (measured: it fetched 5.3 MB
     * and hung up) but it does play HLS -- whose segments ARE MPEG-TS. So the container never has
     * to change; it only has to be announced as a playlist. One segment is deliberately crude: it
     * answers the only question that can sink the real implementation -- does this receiver decode
     * the HEVC inside? -- before any of it is built. Seeking on a single segment is bad, which is
     * exactly what the real version (segments cut on PCR, with EXT-X-BYTERANGE) is for.
     */
    @Synchronized
    fun playlistUrl(): String? {
        val ip = lanIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/hls.m3u8"
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

        if (reqLine.contains("/hls.m3u8")) {
            val body = playlistFor(file).toByteArray()
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            if (method != "HEAD") out.write(body)
            out.flush()
            Log.i(TAG, "-> $client 200 playlist · ${body.size} bytes · ${segmentsFor(file).size} segments")
            return
        }

        // One segment, addressed by index. The range is resolved HERE and the segment answered as
        // a resource of its own, because this receiver ignores `EXT-X-BYTERANGE` -- measured
        // 2026-09-12, it fetched the segment URI repeatedly with no `Range` header at all. See
        // the KDoc of [TsSegmenter.playlist].
        if (reqLine.contains("/seg")) {
            val n = reqLine.substringAfter("n=", "").substringBefore('&').substringBefore(' ').toIntOrNull()
            val seg = n?.let { segmentsFor(file).getOrNull(it) }
            val out = sock.getOutputStream()
            if (seg == null) {
                Log.w(TAG, "-> $client 404 segment n=$n (there are ${segmentsFor(file).size})")
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Content-Length: ${seg.length}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            if (method != "HEAD") {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(seg.start)
                    val buf = ByteArray(64 * 1024)
                    var left = seg.length
                    while (left > 0) {
                        val read = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        left -= read
                    }
                }
            }
            out.flush()
            Log.i(TAG, "-> $client 200 segment $n · ${seg.length}B · ${"%.1f".format(seg.durationSec)}s")
            return
        }

        // A file still being written has no final size, so there is no honest `Content-Length`
        // and no Range to satisfy: it goes out chunked, and the reader blocks at the end of what
        // exists until more is written or the writer finishes. Anything else would hand the
        // receiver a length that is a lie and get the stream cut short.
        if (creciendo) {
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            if (method == "HEAD") { out.flush(); return }
            var enviado = 0L
            var quietoDesde = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            java.io.RandomAccessFile(file, "r").use { raf ->
                while (true) {
                    val disponible = file.length() - enviado
                    if (disponible <= 0L) {
                        // Caught up with the writer. Give it a moment; give up only after it has
                        // stopped producing for long enough that it is finished or dead -- cutting
                        // early would truncate the title mid-playback.
                        if (System.currentTimeMillis() - quietoDesde > ESPERA_ESCRITOR_MS) break
                        Thread.sleep(200)
                        continue
                    }
                    quietoDesde = System.currentTimeMillis()
                    raf.seek(enviado)
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), disponible).toInt())
                    if (n <= 0) { Thread.sleep(200); continue }
                    out.write("${Integer.toHexString(n)}\r\n".toByteArray())
                    out.write(buf, 0, n)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    enviado += n
                }
            }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
            Log.i(TAG, "-> $client growing stream ended after ${enviado}B")
            return
        }

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

    /**
     * The file cut into HLS segments by BYTE RANGE -- nothing is copied or converted, each segment
     * is an interval of the very same file, and HLS segments are themselves MPEG-TS.
     *
     * Why a playlist at all: the receiver refuses a bare transport stream served progressively.
     * Read off the KALLEY's own log (2026-09-12), it says so in as many words --
     * `{"error":"FFmpegDemuxer: open context failed"}`, `{"pipeline_error":12}` -- so it never even
     * reaches a decoder. The same bytes announced as a playlist are the fix.
     *
     * One segment covering the whole file was refused too: the receiver fetched the playlist four
     * times in three seconds and never requested a byte of media. Short segments are what every
     * HLS client expects.
     *
     * The cutting itself lives in [TsSegmenter], which anchors every boundary on a packet carrying
     * a PCR and reads each `#EXTINF` from the PCR difference. That is what restores seeking: the
     * spike prorated durations from the byte size, so on a variable-bitrate title the receiver's
     * idea of a given minute drifted from the bytes actually there.
     */
    /** The segment table for [file], computed once. Cutting probes the file, so it is not free. */
    @Synchronized
    private fun segmentsFor(file: File): List<TsSegmenter.Segment> {
        cachedSegmentsFor?.let { if (it == file.path) return cachedSegments }
        cachedSegments = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                TsSegmenter.segment(file.length(), SEGMENT_TARGET_SEC) { offset, size ->
                    val room = (file.length() - offset).coerceAtLeast(0L)
                    val n = minOf(size.toLong(), room).toInt()
                    if (n <= 0) ByteArray(0) else ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
                }
            }
        }.getOrElse {
            Log.w(TAG, "could not cut ${file.name} into segments: $it")
            emptyList()
        }
        cachedSegmentsFor = file.path
        Log.i(
            TAG,
            "cut ${file.name} into ${cachedSegments.size} segments of ~${SEGMENT_TARGET_SEC}s " +
                "(${"%.1f".format(cachedSegments.sumOf { it.durationSec })}s total)",
        )
        return cachedSegments
    }

    @Volatile private var cachedSegmentsFor: String? = null
    @Volatile private var cachedSegments: List<TsSegmenter.Segment> = emptyList()

    private fun playlistFor(file: File): String =
        TsSegmenter.playlist(segmentsFor(file)) { "/seg?n=$it" }

    private companion object {
        const val TAG = "ArkivLocalServer"

        /** How long the writer may produce nothing before a growing stream is considered over. */
        const val ESPERA_ESCRITOR_MS = 20_000L

        /** Segment length aimed for. Ten seconds is the usual HLS default and what the live proxy,
         *  which already casts fine to this same TV, ends up serving. */
        const val SEGMENT_TARGET_SEC = 10.0

    }
}
