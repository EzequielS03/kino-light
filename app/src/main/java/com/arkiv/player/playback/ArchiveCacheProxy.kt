package com.arkiv.player.playback

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy HTTP local con caché en disco para archive.org, de **descarga única a archivo que crece**:
 * la primera vez que VLC pide un origen, una sola conexión baja el archivo secuencialmente a la caché
 * y VLC lo lee a medida que se llena (arranque inmediato + buffer sobre lo ya descargado, sin esperar
 * a bajar TODO primero). Al completarse queda cacheado (marcador `.done`) y las próximas veces se
 * sirve directo de disco sin volver a descargar. Reemplaza al CacheDataSource de media3 que VLC no usa.
 */
class ArchiveCacheProxy(private val cacheDir: File, maxBytes: Long = 512L * 1024 * 1024) {
    private val cache = DiskLruCache(cacheDir, maxBytes)
    // El socket se crea en start(), NO en el constructor: este proxy es singleton y vive todo el
    // proceso, así que un socket de construcción se cerraba en el primer stop() y ya no había forma
    // de reabrirlo (un ServerSocket cerrado no se reabre). Como el botón de parar de la barra llama
    // a stop(), eso dejaba archive cargando para siempre hasta matar la app.
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1
    @Volatile private var running = false

    // Una descarga en curso por origen (clave de caché). La comparten todas las conexiones de VLC de
    // ese origen: solo un thread baja, el resto lee del mismo archivo que crece.
    private val downloads = ConcurrentHashMap<String, Download>()
    private val initLocks = ConcurrentHashMap<String, Any>()
    private fun initLock(key: String): Any = initLocks.computeIfAbsent(key) { Any() }

    // Si VLC pide un tramo más de 8 MB por delante de lo ya descargado, se asume seek/moov y se trae
    // directo del origen en vez de esperar a la descarga secuencial.
    private val AHEAD_THRESHOLD = 8L * 1024 * 1024

    private class Download(val origin: String, val file: File, val doneMarker: File, val total: Long) {
        @Volatile var downloaded: Long = 0
        @Volatile var done = false
        @Volatile var failed = false
    }

    /** Idempotente: si ya hay un socket vivo devuelve su puerto; si no, abre uno nuevo. */
    @Synchronized
    fun start(): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = ServerSocket(0)
        server = sock
        running = true
        // El loop captura ESTE socket en vez de leer el campo: si mientras tanto hubo un stop()+start(),
        // el thread viejo muere con el suyo y no se queda aceptando sobre el del proxy nuevo.
        Thread {
            while (running && !sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return sock.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    fun proxyUrl(originUrl: String): String =
        "http://127.0.0.1:$port/s?u=${URLEncoder.encode(originUrl, "UTF-8")}"

    /**
     * Fracción [0..1] del archivo ya descargada a la caché para la URL de proxy dada (para pintar el
     * tramo "buffereado" en la barra de progreso). 1 = ya cacheado completo; 0 = sin descarga en curso.
     */
    fun bufferedFraction(proxyUrl: String): Float {
        val origin = runCatching {
            URLDecoder.decode(proxyUrl.substringAfter("u=", ""), "UTF-8")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return 0f
        val key = cache.keyFor(origin)
        if (File(cacheDir, "$key.done").exists() && cache.file(key).length() > 0) return 1f
        val dl = downloads[key] ?: return 0f
        return if (dl.total > 0) (dl.downloaded.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
    }

    private fun serve(socket: Socket) {
        // socket.use{} cierra el socket al salir; runCatching traga excepciones de red/IO (p.ej. VLC
        // cierra el socket al hacer seek → escribir tira broken pipe) para no matar el thread.
        socket.use { s ->
            runCatching {
                val input = s.getInputStream()
                val header = StringBuilder()
                val one = ByteArray(1)
                while (input.read(one) == 1) {
                    header.append(one[0].toInt().toChar())
                    if (header.endsWith("\r\n\r\n") || header.length > 8192) break
                }
                val lines = header.toString().split("\r\n")
                val reqLine = lines.firstOrNull().orEmpty()
                val path = reqLine.split(' ').getOrNull(1).orEmpty()
                val origin = path.substringAfter("u=", "").let {
                    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                } ?: return@runCatching
                val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()
                val range = RangeHeader.parse(rangeHeader)

                val key = cache.keyFor(origin)
                val file = cache.file(key)
                val doneMarker = File(cacheDir, "$key.done")
                val out = s.getOutputStream()

                // 1) Ya cacheado completo → servir de disco (rápido, con Range, sin red).
                if (doneMarker.exists() && file.exists() && file.length() > 0) {
                    cache.touch(key); cache.evictIfNeeded()
                    serveFromFile(file, file.length(), range, out)
                    return@runCatching
                }

                // 2) Asegurar/arrancar la descarga única y servir del archivo que crece.
                val dl = ensureDownload(key, origin, file, doneMarker)
                if (dl == null) {
                    // Sin red / 404 / 5xx / sin Content-Length: error real (no un 200 vacío que VLC
                    // tomaría como éxito → pantalla negra). Passthrough simple como último recurso.
                    if (!passthrough(origin, rangeHeader, out)) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    return@runCatching
                }
                serveGrowing(dl, range, out)
            }.onFailure { e ->
                runCatching { android.util.Log.w("ArchiveCacheProxy", "serve() falló: ${e.message}") }
            }
        }
    }

    /**
     * Arranca (una sola vez por origen) la descarga secuencial completa al archivo de caché, o
     * devuelve la que ya está en curso. Abre la conexión para conocer el tamaño total; si no se puede
     * (sin red, error HTTP, sin Content-Length) devuelve null y el llamador cae a passthrough/502.
     */
    private fun ensureDownload(key: String, origin: String, file: File, doneMarker: File): Download? {
        downloads[key]?.let { if (!it.failed) return it }
        return synchronized(initLock(key)) {
            downloads[key]?.let { if (!it.failed) return it }
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true // archive.org 302 → nodo de datos
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    connectTimeout = 15000; readTimeout = 20000
                }
            }.getOrNull() ?: return null
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            val total = conn.contentLengthLong
            if ((code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) || total <= 0) {
                runCatching { conn.disconnect() }
                return null
            }
            val dl = Download(origin, file, doneMarker, total)
            downloads[key] = dl
            Thread { runDownload(conn, dl, key) }.apply { isDaemon = true }.start()
            dl
        }
    }

    /** Baja el origen secuencialmente al archivo de caché, publicando el avance en [Download.downloaded]. */
    private fun runDownload(conn: HttpURLConnection, dl: Download, key: String) {
        runCatching {
            conn.inputStream.use { ins ->
                RandomAccessFile(dl.file, "rw").use { raf ->
                    raf.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    while (running) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        dl.downloaded += n
                    }
                }
            }
        }.onFailure { dl.failed = true }
        runCatching { conn.disconnect() }
        if (!dl.failed && dl.downloaded >= dl.total) {
            // Completo: marcador de "cacheado" para que las próximas veces se sirva directo de disco.
            runCatching { dl.doneMarker.createNewFile() }
            dl.done = true
            cache.touch(key); cache.evictIfNeeded()
        } else {
            dl.failed = true
            runCatching { dl.file.delete() }
        }
        downloads.remove(key)
    }

    /**
     * Sirve el rango pedido mientras la descarga secuencial (a caché) sigue en background:
     * - si el tramo YA está descargado (la descarga full-speed suele ir por delante del playhead) →
     *   se lee de disco (rápido, sin red).
     * - si el tramo AÚN no está en caché (típico: el `moov` de un MP4 no-faststart está al final del
     *   archivo y VLC lo pide al abrir) → se trae DIRECTO de archive.org por Range, sin esperar a que
     *   la descarga secuencial llegue hasta ahí. Así el arranque no se bloquea por el índice del final.
     */
    private fun serveGrowing(dl: Download, range: ByteRange?, out: java.io.OutputStream) {
        val total = dl.total
        if (range != null && range.start >= total) {
            out.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray()
            )
            out.flush(); return
        }
        val start = range?.start ?: 0L
        val end = (range?.end ?: (total - 1)).coerceAtMost(total - 1)
        val len = (end - start + 1).coerceAtLeast(0)
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val respHeaders = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(respHeaders.toByteArray())
        when {
            // Todo el tramo ya está en la caché en disco → lectura directa, sin red.
            dl.done || dl.downloaded > end ->
                readFromDisk(dl.file, start, len, out)
            // Tramo MUY por delante de la descarga (seek lejano o el `moov` del final) → directo del
            // origen para no esperar a que la descarga secuencial llegue hasta ahí.
            start > dl.downloaded + AHEAD_THRESHOLD ->
                fetchOriginRangeBody(dl.origin, start, end, out)
            // Reproducción secuencial (el playhead): se lee de disco a medida que la descarga —a
            // velocidad plena, que va por delante— lo va llenando; solo espera lo mínimo.
            else ->
                streamGrowingFromDisk(dl, start, end, out)
        }
        out.flush()
    }

    /** Lee [start, start+len) de un archivo con esos bytes ya presentes. */
    private fun readFromDisk(file: File, start: Long, len: Long, out: java.io.OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var remaining = len
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                out.write(buf, 0, n); remaining -= n
            }
        }
    }

    /** Lee [start,end] del archivo que crece, esperando a que la descarga secuencial pase de cada byte. */
    private fun streamGrowingFromDisk(dl: Download, start: Long, end: Long, out: java.io.OutputStream) {
        RandomAccessFile(dl.file, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            var pos = start
            while (pos <= end) {
                while (dl.downloaded <= pos && !dl.done && !dl.failed) Thread.sleep(40)
                val avail = minOf(end + 1, dl.downloaded)
                if (avail <= pos) { if (dl.done || dl.failed) break else continue }
                raf.seek(pos)
                val n = raf.read(buf, 0, minOf((avail - pos), buf.size.toLong()).toInt())
                if (n <= 0) { if (dl.done || dl.failed) break else { Thread.sleep(20); continue } }
                out.write(buf, 0, n)
                pos += n
            }
        }
    }

    /** Baja el tramo [start,end] directo de archive.org (Range) y lo escribe crudo a [out] (sin headers). */
    private fun fetchOriginRangeBody(origin: String, start: Long, end: Long, out: java.io.OutputStream) {
        val conn = runCatching {
            (URL(origin).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                setRequestProperty("Range", "bytes=$start-$end")
                connectTimeout = 15000; readTimeout = 20000
            }
        }.getOrNull() ?: return
        runCatching {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
        }
        runCatching { conn.disconnect() }
    }

    /** Sirve un rango desde un archivo YA completo en disco (RFC 7233: 206/200/416). */
    private fun serveFromFile(file: File, total: Long, range: ByteRange?, out: java.io.OutputStream) {
        if (range != null && range.start >= total) {
            out.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\n\r\n".toByteArray()
            )
            out.flush(); return
        }
        val start = range?.start ?: 0L
        val end = (range?.end ?: (total - 1)).coerceAtMost(total - 1)
        val len = (end - start + 1).coerceAtLeast(0)
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val respHeaders = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(respHeaders.toByteArray())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var remaining = len
            while (remaining > 0) {
                val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
        out.flush()
    }

    /** Passthrough directo origen→VLC (sin cachear), último recurso si no se pudo iniciar la descarga. */
    private fun passthrough(origin: String, rangeHeader: String?, out: java.io.OutputStream): Boolean {
        val conn = runCatching {
            (URL(origin).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
                connectTimeout = 15000; readTimeout = 20000
            }
        }.getOrNull() ?: return false
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            runCatching { conn.disconnect() }; return false
        }
        val contentLength = conn.getHeaderField("Content-Length")
        val contentRange = conn.getHeaderField("Content-Range")
        val statusLine = if (code == HttpURLConnection.HTTP_PARTIAL) "206 Partial Content" else "200 OK"
        val resp = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(resp.toByteArray())
        conn.inputStream.use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }
        out.flush()
        runCatching { conn.disconnect() }
        return true
    }
}
