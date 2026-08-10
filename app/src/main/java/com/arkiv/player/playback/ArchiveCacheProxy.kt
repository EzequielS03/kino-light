package com.arkiv.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * Cuántas conexiones al origen hay vivas por archivo. Solo para diagnóstico: ya NO se cierra
     * ninguna a la fuerza (ver la nota en [abrirEnOrigen]), pero si este número crece sin volver a
     * bajar hay conexiones que quedaron colgadas y se ve acá antes de que se note reproduciendo.
     */
    private val vivasPorClave = ConcurrentHashMap<String, Int>()

    private fun soltarViva(clave: String) {
        vivasPorClave.computeIfPresent(clave) { _, n -> if (n <= 1) null else n - 1 }
    }
    // Tamaño real de cada origen, para poder ventanear. Ver totalDelOrigen.
    private val totales = ConcurrentHashMap<String, Long>()

    /**
     * Arranque ya descargado y esperando en memoria, por clave de caché. Ver [precalentar].
     *
     * Solo lo usa magis: nadie más llama a `precalentar`, así que para el resto de las fuentes este
     * mapa está siempre vacío y el camino es exactamente el de siempre.
     */
    private val calientes = ConcurrentHashMap<String, ByteArray>()
    private val initLocks = ConcurrentHashMap<String, Any>()
    private fun initLock(key: String): Any = initLocks.computeIfAbsent(key) { Any() }

    // Si VLC pide un tramo más de 8 MB por delante de lo ya descargado, se asume seek/moov y se trae
    // directo del origen en vez de esperar a la descarga secuencial.
    private val AHEAD_THRESHOLD = 8L * 1024 * 1024

    // Reintentos contra el origen en el camino directo. El CDN de magis rechaza o demora peticiones
    // al azar (medido: entre 0,2 s y 20 s hasta el primer byte, y no-206 esporádicos sobre rangos
    // perfectamente válidos), así que un solo intento convierte cualquier mala racha en "la película
    // no reproduce". Ver TsDurationProbe, que ya aprendió lo mismo.
    private val INTENTOS_ORIGEN = 3
    private val ESPERA_ORIGEN_MS = 400L

    // Cuánto se precalienta. 2 MB ≈ 15 s de estos TS (~1,1 Mbps): de sobra para que libVLC
    // identifique programas y pistas sin depender de la latencia del CDN, y poco como para tenerlo
    // en memoria sin pensarlo dos veces.
    private val ARRANQUE_CALIENTE = 2 * 1024 * 1024

    private class Download(
        val origin: String,
        val file: File,
        val doneMarker: File,
        val total: Long,
        val headers: Map<String, String> = emptyMap(),
    ) {
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

    /**
     * URL local que VLC puede reproducir. [headers] viaja codificado en la propia URL porque es lo
     * unico que VLC nos deja pasar: solo entiende `:http-referrer` y `:http-user-agent`, y magis
     * sirve el VOD detras de `Content-Auth` y `Content-License`. El proxy los pone en la peticion
     * al origen.
     *
     * `h` va ANTES de `u` a proposito: hay codigo que saca el origen con `substringAfter("u=")`.
     */
    fun proxyUrl(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        directo: Boolean = false,
    ): String {
        val u = URLEncoder.encode(originUrl, "UTF-8")
        val d = if (directo) "d=1&" else ""
        if (headers.isEmpty()) return "http://127.0.0.1:$port/s?${d}u=$u"
        val h = URLEncoder.encode(HeaderCodec.encode(headers), "UTF-8")
        return "http://127.0.0.1:$port/s?h=$h&${d}u=$u"
    }

    companion object {
        /**
         * La misma URL de proxy, pero pidiendo que sirva desde [fraccion] del archivo como si ese
         * tramo fuera el archivo entero. Ver [VentanaDeArchivo] para el porqué.
         *
         * `f` va antes de `u` como el resto de los parámetros: hay código que saca el origen con
         * `substringAfter("u=")` y todo lo que vaya después se le colaría dentro.
         */
        fun conFraccion(proxyUrl: String, fraccion: Float): String {
            if (fraccion <= 0f) return proxyUrl
            val limpia = proxyUrl.replace(Regex("""[?&]f=[^&]*"""), "")
            val i = limpia.indexOf("u=")
            if (i < 0) return limpia
            return limpia.substring(0, i) + "f=$fraccion&" + limpia.substring(i)
        }
    }

    /**
     * Fracción [0..1] del archivo ya descargada a la caché para la URL de proxy dada (para pintar el
     * tramo "buffereado" en la barra de progreso). 1 = ya cacheado completo; 0 = sin descarga en curso.
     */
    fun bufferedFraction(proxyUrl: String): Float {
        val origin = runCatching {
            URLDecoder.decode(proxyUrl.substringAfter("u=", "").substringBefore('&'), "UTF-8")
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
                val origin = path.substringAfter("u=", "").substringBefore('&').let {
                    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                } ?: return@runCatching
                val extraHeaders = HeaderCodec.decode(
                    path.substringAfter("h=", "").substringBefore('&'),
                )
                // Modo de servicio. Lo elige quien arma la URL (magis → directo) para que el camino
                // de archive siga siendo exactamente el de siempre.
                val directo = path.contains("d=1")
                // Ventana: servir desde esta fracción del archivo como si fuera el archivo entero,
                // para poder REANUDAR sin que el reproductor tenga que saltar. Ver VentanaDeArchivo.
                val fraccion = path.substringAfter("f=", "").substringBefore('&')
                    .toFloatOrNull()?.takeIf { it > 0f } ?: 0f
                val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()
                val range = RangeHeader.parse(rangeHeader)

                val key = cache.keyFor(origin)
                val file = cache.file(key)
                val doneMarker = File(cacheDir, "$key.done")
                val out = s.getOutputStream()

                // Toda petición que entra queda registrada: el proxy es la frontera entre "el
                // reproductor no pide" y "el proxy no entrega", que desde afuera se ven igual (VLC
                // buffereando al 0% para siempre).
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "← pide rango=${rangeHeader ?: "(todo)"} directo=$directo ventana=$fraccion " +
                        "descargado=${downloads[key]?.downloaded ?: -1}",
                )

                // 0) Camino DIRECTO (magis): cada Range va tal cual al origen y su cuerpo se devuelve
                //    sin tocar el disco. Ver la nota de arriba de por qué la caché no sirve acá.
                if (directo) {
                    if (!passthrough(origin, rangeHeader, out, extraHeaders, claveUnica = key, fraccion = fraccion)) {
                        android.util.Log.w("ArchiveCacheProxy", "directo: el origen no sirvió el tramo")
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    return@runCatching
                }

                // 1) Ya cacheado completo → servir de disco (rápido, con Range, sin red).
                if (doneMarker.exists() && file.exists() && file.length() > 0) {
                    cache.touch(key); cache.evictIfNeeded()
                    serveFromFile(file, file.length(), range, out)
                    return@runCatching
                }

                // 2) Asegurar/arrancar la descarga única y servir del archivo que crece.
                val dl = ensureDownload(key, origin, file, doneMarker, extraHeaders)
                if (dl == null) {
                    // Sin red / 404 / 5xx / sin Content-Length: error real (no un 200 vacío que VLC
                    // tomaría como éxito → pantalla negra). Passthrough simple como último recurso.
                    if (!passthrough(origin, rangeHeader, out, extraHeaders)) {
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
    private fun ensureDownload(
        key: String,
        origin: String,
        file: File,
        doneMarker: File,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Download? {
        downloads[key]?.let { if (!it.failed) return it }
        return synchronized(initLock(key)) {
            downloads[key]?.let { if (!it.failed) return it }
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true // archive.org 302 → nodo de datos
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    connectTimeout = 15000; readTimeout = 20000
                }
            }.getOrNull() ?: return null
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            val total = conn.contentLengthLong
            if ((code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) || total <= 0) {
                runCatching { conn.disconnect() }
                return null
            }
            val dl = Download(origin, file, doneMarker, total, extraHeaders)
            // El archivo se crea ACÁ, antes de lanzar el hilo: el que sirve lo abre para leer
            // apenas vuelve esta función, y si el escritor todavía no lo creó la lectura muere con
            // ENOENT y el reproductor se queda en "buffering 0%" para siempre.
            runCatching { file.parentFile?.mkdirs(); if (!file.exists()) file.createNewFile() }
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
        val rama = when {
            dl.done || dl.downloaded > end -> "disco"
            start > dl.downloaded + AHEAD_THRESHOLD -> "origen"
            else -> "creciendo"
        }
        // Qué camino se tomó para este tramo: cada uno falla distinto y desde fuera se ven igual.
        android.util.Log.w(
            "ArchiveCacheProxy",
            "→ rama=$rama tramo=$start-$end descargado=${dl.downloaded}/${dl.total}",
        )
        when (rama) {
            // Todo el tramo ya está en la caché en disco → lectura directa, sin red.
            "disco" -> readFromDisk(dl.file, start, len, out)
            // Tramo MUY por delante de la descarga (seek lejano o el `moov` del final) → directo del
            // origen para no esperar a que la descarga secuencial llegue hasta ahí.
            "origen" -> fetchOriginRangeBody(dl.origin, start, end, out, dl.headers)
            // Reproducción secuencial (el playhead): se lee de disco a medida que la descarga —a
            // velocidad plena, que va por delante— lo va llenando; solo espera lo mínimo.
            else -> streamGrowingFromDisk(dl, start, end, out)
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
        var escritos = 0L
        val t0 = System.currentTimeMillis()
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
                escritos += n
            }
        }
        // Resumen de lo entregado al reproductor: distingue "el proxy no manda datos" de
        // "el reproductor no puede con el contenido", que se ven igual desde afuera.
        android.util.Log.w(
            "ArchiveCacheProxy",
            "→reproductor ${escritos / 1024 / 1024}MB en ${System.currentTimeMillis() - t0}ms",
        )
    }

    /** Baja el tramo [start,end] directo de archive.org (Range) y lo escribe crudo a [out] (sin headers). */
    private fun fetchOriginRangeBody(
        origin: String,
        start: Long,
        end: Long,
        out: java.io.OutputStream,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val conn = runCatching {
            (URL(origin).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", "bytes=$start-$end")
                connectTimeout = 15000; readTimeout = 20000
            }
        }.getOrNull() ?: run {
            android.util.Log.w("ArchiveCacheProxy", "origen: no se pudo abrir la conexión ($start-$end)")
            return
        }
        // El código del origen y los bytes entregados: sin esto, un 403/timeout acá es INVISIBLE —
        // la cabecera 206 ya salió, así que el reproductor espera un cuerpo que nunca llega y se
        // queda buffereando al 0% sin error.
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        var escritos = 0L
        val t0 = System.currentTimeMillis()
        val fallo = runCatching {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); escritos += n
                }
            }
        }.exceptionOrNull()
        android.util.Log.w(
            "ArchiveCacheProxy",
            "origen $start-$end → code=$code ${escritos / 1024}KB en ${System.currentTimeMillis() - t0}ms" +
                (fallo?.let { " ¡FALLÓ: ${it.message}!" } ?: ""),
        )
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

    /**
     * Tamaño real de un origen, leído del `Content-Range` de una petición de 1 byte.
     *
     * Hace falta para poder ventanear: la fracción que pide el reproductor solo se puede convertir
     * a byte sabiendo el total. Se recuerda por origen porque cada salto vuelve a abrir el stream
     * con otra fracción y este viaje al CDN, aunque sea de un byte, también paga su latencia.
     */
    private fun totalDelOrigen(origin: String, extraHeaders: Map<String, String>): Long {
        totales[origin]?.let { return it }
        repeat(INTENTOS_ORIGEN) { intento ->
            val total = runCatching {
                val conn = (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=0-0")
                    connectTimeout = 8000; readTimeout = 8000
                }
                val cr = conn.getHeaderField("Content-Range")
                runCatching { conn.inputStream.use { it.readBytes() } }
                runCatching { conn.disconnect() }
                VentanaDeArchivo.totalDelContentRange(cr)
            }.getOrDefault(0L)
            if (total > 0) { totales[origin] = total; return total }
            if (intento < INTENTOS_ORIGEN - 1) Thread.sleep(ESPERA_ORIGEN_MS)
        }
        android.util.Log.w("ArchiveCacheProxy", "ventana: no se pudo saber el tamaño del origen")
        return 0L
    }

    /**
     * Abre el tramo en el origen, reintentando: el CDN de magis rechaza peticiones al azar (visto
     * en device: un salto perfectamente válido devolvió no-206 dos veces seguidas y la película se
     * murió ahí). Antes esto se rendía al primer no, y como la cabecera de respuesta ya había
     * salido, el reproductor se quedaba esperando un cuerpo que no llegaba nunca.
     *
     * Registra la conexión buena en [conexiones] antes de devolverla: la anterior del mismo archivo
     * tiene que morir en el acto o el CDN deja colgada a la nueva.
     */
    private fun abrirEnOrigen(
        origin: String,
        rango: String?,
        extraHeaders: Map<String, String>,
        claveUnica: String?,
    ): Pair<HttpURLConnection, ConexionUnica.Cerrable>? {
        repeat(INTENTOS_ORIGEN) { intento ->
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (rango != null) setRequestProperty("Range", rango)
                    connectTimeout = 15000; readTimeout = 20000
                }
            }.getOrNull()
            if (conn != null) {
                val cerrable = ConexionUnica.Cerrable { runCatching { conn.disconnect() } }
                // NO se mata la conexión anterior, y esto es lo contrario de lo que hacía antes.
                //
                // `ConexionUnica` se puso creyendo que el CDN atendía de a una conexión por archivo.
                // Medido hoy: es falso — sirve dos simultáneas al mismo archivo sin quejarse (206 en
                // 0,77 s la segunda, con la primera todavía descargando). Y al abrir, libVLC hace
                // VARIAS peticiones seguidas para sondear el stream (visto: bytes=0-, 216576-,
                // 1115160- en 800 ms): matarle la anterior en cada una le cortaba justo las lecturas
                // con las que identifica programas y pistas, y terminaba sin ninguna (`pistas=v0/a0`),
                // negro y mudo. O sea: la protección estaba causando el problema que decía evitar.
                //
                // Lo que sí hacía falta —que una conexión abandonada no siga drenando— ya está
                // resuelto por el `disconnect()` del finally de passthrough, que corre también
                // cuando el reproductor corta de golpe ("broken pipe").
                val vivas = claveUnica?.let { vivasPorClave.merge(it, 1) { a, b -> a + b } } ?: 1
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "abro ${rango ?: "(todo)"} → conexiones vivas de este archivo: $vivas",
                )
                val code = runCatching { conn.responseCode }.getOrDefault(-1)
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    return conn to cerrable
                }
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "origen rechazó ${rango ?: "(todo)"} con $code (intento ${intento + 1}/$INTENTOS_ORIGEN)",
                )
                claveUnica?.let { soltarViva(it) }
                runCatching { conn.disconnect() }
            }
            if (intento < INTENTOS_ORIGEN - 1) Thread.sleep(ESPERA_ORIGEN_MS)
        }
        return null
    }

    /**
     * Deja el arranque del stream listo en memoria ANTES de que el reproductor abra la URL.
     *
     * El porqué, medido: el CDN de magis tarda entre 0,2 s y 20 s en soltar el primer byte, y
     * cuando la primera lectura se demora **libVLC se rinde identificando el stream**. No falla ni
     * avisa: se queda sin pistas (`pistas=v0/a0`, ni imagen ni sonido) y desde ahí traga el archivo
     * a toda velocidad sin volver a intentarlo — la película queda negra para siempre aunque los
     * datos lleguen dos segundos después. Es la explicación de "la primera vez anda y la segunda
     * no": no era el decodificador ni la vista sin destruir, era quién ganaba esa carrera.
     *
     * Con el arranque ya en la mano, la primera lectura de VLC se responde al instante y siempre
     * llega a identificar las pistas. Lo que tarde el CDN pasa a ser espera ANTES de abrir el
     * video, que es recuperable, en vez de un fallo silencioso del que no se vuelve.
     *
     * Devuelve false si no se pudo (sin red, el origen no colabora): el que llama reproduce igual,
     * solo que sin la garantía.
     */
    suspend fun precalentar(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        fraccion: Float = 0f,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = cache.keyFor(originUrl)
        val inicio = if (fraccion > 0f) {
            VentanaDeArchivo.inicio(totalDelOrigen(originUrl, headers), fraccion)
        } else 0L
        val t0 = System.currentTimeMillis()
        val (conn, cerrable) = abrirEnOrigen(originUrl, "bytes=$inicio-", headers, claveUnica = null)
            ?: run {
                android.util.Log.w("ArchiveCacheProxy", "precalentar: el origen no dio el arranque")
                return@withContext false
            }
        val bytes = runCatching {
            conn.inputStream.use { ins ->
                val buf = ByteArray(ARRANQUE_CALIENTE)
                var n = 0
                while (n < buf.size) {
                    val leidos = ins.read(buf, n, buf.size - n)
                    if (leidos < 0) break
                    n += leidos
                }
                buf.copyOf(n)
            }
        }.getOrNull()
        runCatching { conn.disconnect() }
        if (bytes == null || bytes.isEmpty()) {
            android.util.Log.w("ArchiveCacheProxy", "precalentar: no llegaron bytes")
            return@withContext false
        }
        // Indexado por archivo Y punto de arranque: solo sirve para quien abra exactamente ahí.
        calientes["$key@$inicio"] = bytes
        android.util.Log.w(
            "ArchiveCacheProxy",
            "precalentado ${bytes.size / 1024}KB desde $inicio en ${System.currentTimeMillis() - t0}ms",
        )
        true
    }

    /** Passthrough directo origen→VLC (sin cachear), último recurso si no se pudo iniciar la descarga. */
    private fun passthrough(
        origin: String,
        rangeHeader: String?,
        out: java.io.OutputStream,
        extraHeaders: Map<String, String> = emptyMap(),
        claveUnica: String? = null,
        fraccion: Float = 0f,
    ): Boolean {
        // Ventana: el reproductor pide en coordenadas de un archivo que empieza en 0, y acá se
        // traducen a las del archivo real. Si no se pudo saber el tamaño, `inicio` queda en 0 y
        // esto se comporta como el passthrough de siempre: sin duración es peor, pero reproduce.
        val inicio = if (fraccion > 0f) {
            VentanaDeArchivo.inicio(totalDelOrigen(origin, extraHeaders), fraccion)
        } else 0L
        val rangoCliente = RangeHeader.parse(rangeHeader)
        val rangoAlOrigen = if (inicio > 0L) {
            VentanaDeArchivo.rangoAlOrigen(rangoCliente, inicio)
        } else rangeHeader
        // El arranque precalentado sirve UNA vez y solo para la petición que empieza en el byte 0
        // (la primera que hace el reproductor al abrir): es ahí donde se juega la identificación
        // del stream. Se consume del mapa para que un salto posterior no reciba bytes del principio.
        // La clave incluye el BYTE de arranque, no solo el archivo. Sin eso, un precalentado hecho
        // para otro punto se le pegaba igual al principio del stream: 2 MB de otra parte de la
        // película empalmados en la cabecera, que es basura para el demuxer y deja a VLC sin pistas
        // — o sea, causando exactamente el fallo que este precalentado venía a evitar. Los offsets
        // NO siempre coinciden: la posición guardada sigue avanzando entre que se precalienta y que
        // el reproductor abre.
        val caliente = if ((rangoCliente?.start ?: 0L) == 0L && claveUnica != null) {
            calientes.remove("$claveUnica@$inicio").also {
                if (it == null && calientes.isNotEmpty()) {
                    // Había arranque precalentado pero para OTRO punto: el reproductor abrió en un
                    // sitio distinto al que se preparó. No es fatal (se sirve del origen), pero es
                    // el precalentado desperdiciado y hay que verlo: era el fallo silencioso.
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "arranque caliente NO coincide: se abrió en $inicio y había ${calientes.keys}",
                    )
                }
            }
        } else null
        // Cada intento mata el tramo anterior de ESTE mismo archivo antes de hablarle al origen: si
        // la conexión vieja sigue viva, la nueva queda colgada hasta el timeout.
        val (conn, cerrable) = abrirEnOrigen(origin, rangoAlOrigen, extraHeaders, claveUnica)
            ?: return false
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val contentLength = conn.getHeaderField("Content-Length")
        // Con ventana el Content-Range del origen viene en coordenadas REALES: mandárselo al
        // reproductor tal cual le haría creer que su archivo empieza en un byte que para él no
        // existe. El Content-Length no se toca: el cuerpo que se reenvía es el mismo.
        val contentRange = if (inicio > 0L) {
            VentanaDeArchivo.contentRangeVisible(conn.getHeaderField("Content-Range"), inicio)
        } else conn.getHeaderField("Content-Range")
        // 206 solo si el REPRODUCTOR pidió un rango: con ventana siempre se le pide uno al origen,
        // pero para quien abrió el archivo entero eso es un 200 normal.
        val esParcial = rangeHeader != null && code == HttpURLConnection.HTTP_PARTIAL
        val statusLine = if (esParcial) "206 Partial Content" else "200 OK"
        val resp = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            if (esParcial && contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        out.write(resp.toByteArray())
        var escritos = 0L
        val t0 = System.currentTimeMillis()
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                // ARRANQUE CALIENTE: si este tramo empieza justo donde se precalentó, esos bytes ya
                // están en memoria y salen ahora mismo — que es lo único que le importa a libVLC
                // para no rendirse identificando el stream (ver precalentar). Los mismos bytes se
                // descartan después del origen para no tener que tocar las cabeceras ya enviadas:
                // son 2 MB de más una vez por reproducción, a cambio de que nunca quede en negro.
                if (caliente != null) {
                    out.write(caliente); out.flush(); escritos += caliente.size
                    var porDescartar = caliente.size
                    while (porDescartar > 0) {
                        val n = ins.read(buf, 0, minOf(porDescartar, buf.size))
                        if (n < 0) break
                        porDescartar -= n
                    }
                }
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); escritos += n
                }
            }
            out.flush()
        } finally {
            claveUnica?.let { soltarViva(it) }
            // disconnect() SIEMPRE, también cuando el reproductor corta la conexión a mitad (seek →
            // "broken pipe"). Antes la excepción se saltaba esta línea y la conexión al CDN quedaba
            // viva en el pool de HttpURLConnection drenando el resto del archivo: el origen veía dos
            // conexiones a la vez y la NUEVA (la del punto al que se saltó) se quedaba sin datos.
            runCatching { conn.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "directo ${rangeHeader ?: "(todo)"}" +
                    (if (inicio > 0L) " [ventana desde $inicio → pedí $rangoAlOrigen]" else "") +
                    " → code=$code ${escritos / 1024}KB en ${System.currentTimeMillis() - t0}ms",
            )
        }
        return true
    }
}
