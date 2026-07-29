package com.arkiv.player.torrent

import android.util.Log
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

private const val TS = "ArkivStream"

/**
 * Servidor HTTP local que sirve UN archivo de un torrent con soporte de Range,
 * esperando las piezas necesarias antes de entregar cada tramo (streaming).
 * ExoPlayer lee de http://127.0.0.1:<port>/video y reproduce mientras descarga.
 */
class TorrentStreamServer(
    private val handle: TorrentHandle,
    info: TorrentInfo,
    private val file: File,
    private val fileOffset: Long,
    private val fileSize: Long,
) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val mime: String get() = contentType()
    private val pieceLength = info.pieceLength().toLong()

    @Volatile
    private var running = true

    // Última pieza desde la que se promovió la ventana (dedup entre las varias conexiones de VLC).
    @Volatile private var lastPromoted = Int.MIN_VALUE
    private val promoteLock = Any()

    fun start() {
        Thread {
            while (running && !server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        val t0 = System.currentTimeMillis()
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val header = StringBuilder()
                val one = ByteArray(1)
                while (input.read(one) == 1) {
                    header.append(one[0].toInt().toChar())
                    if (header.endsWith("\r\n\r\n")) break
                    if (header.length > 8192) break
                }
                val lines = header.toString().split("\r\n")
                val reqLine = lines.firstOrNull().orEmpty()
                val method = reqLine.substringBefore(' ')
                Log.i(TS, "REQ +${System.currentTimeMillis()-t0}ms method=$method peer=${socket.remoteSocketAddress} hdr=${header.length}B")

                var start = 0L
                var end = fileSize - 1
                val range = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()
                val hasRange = range != null && range.startsWith("bytes=")
                if (hasRange) {
                    val parts = range!!.removePrefix("bytes=").split("-")
                    start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    end = parts.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
                }
                start = start.coerceIn(0, fileSize - 1)
                end = end.coerceIn(start, fileSize - 1)
                val length = end - start + 1
                val out = s.getOutputStream()
                // Señal de vida del cliente para abortar esperas de piezas si VLC cierra (seek/stop).
                val alive: () -> Boolean = { !clientGone(input, s) }

                // 206 solo si pidieron Range; 200 para el GET completo (webOS lo requiere).
                val statusLine = if (hasRange) "206 Partial Content" else "200 OK"
                val rangeHeader = if (hasRange) "Content-Range: bytes $start-$end/$fileSize\r\n" else ""
                val headerBytes = (
                    "HTTP/1.1 $statusLine\r\n" +
                        "Content-Type: ${contentType()}\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: $length\r\n" +
                        rangeHeader +
                        // Headers DLNA para renderers tipo LG webOS (cast).
                        "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                        "transferMode.dlna.org: Streaming\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
                // HEAD: solo cabecera, sin cuerpo (ni esperar piezas).
                if (method == "HEAD") {
                    out.write(headerBytes); out.flush()
                    return
                }

                // Anclar la descarga secuencial a la posición pedida SOLO si no es una lectura del
                // final: VLC salta al final a leer el índice (Cues/moov/idx1) antes de arrancar, y
                // si redirigimos la descarga hacia allá dejamos el INICIO incompleto y no arranca.
                // El tail ya se cubre con los deadlines de las últimas piezas (ver beginServing).
                val startPiece = ((fileOffset + start) / pieceLength).toInt()
                Log.i(TS, "RANGE start=$start end=$end bytes=$length startPiece=$startPiece fileSize=$fileSize")
                if (start < fileSize * 9 / 10) {
                    runCatching { handle.setSequentialRange(startPiece) }
                        .onFailure { Log.w(TS, "setSequentialRange($startPiece) failed: $it") }
                }

                val rafOpenStart = System.currentTimeMillis()
                val raf = waitAndOpen() ?: run {
                    Log.w(TS, "RAFTIMEOUT +${rafOpenStart-t0}ms file=${file.name} exist=${file.exists()}")
                    return
                }
                Log.i(TS, "RAFOPEN +${System.currentTimeMillis()-rafOpenStart}ms")

                // FIX B: esperar la PRIMERA pieza pedida ANTES de mandar el header HTTP. Si mandáramos el
                // 200 OK y después tardáramos 10-40s en la pieza (torrent frío), VLC recibe la cabecera, se
                // cansa de esperar el cuerpo y cierra (broken pipe) -> reintenta con sockets paralelos
                // (thrashing). Con la pieza lista, cabecera y datos salen juntos y VLC arranca limpio.
                val firstWaitT0 = System.currentTimeMillis()
                if (!waitForPiece(startPiece, alive)) {
                    Log.w(TS, "FIRST_PIECE_TIMEOUT piece=$startPiece waitedMs=${System.currentTimeMillis()-firstWaitT0}")
                    return
                }
                Log.i(TS, "FIRST_PIECE_READY piece=$startPiece waitedMs=${System.currentTimeMillis()-firstWaitT0}")
                out.write(headerBytes)
                val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
                raf.use { f ->
                    f.seek(start)
                    val buf = ByteArray(64 * 1024)
                    var pos = start
                    var windowPiece = -1
                    while (running && pos <= end) {
                        val piece = ((fileOffset + pos) / pieceLength).toInt()
                        // Read-ahead PROACTIVO: al entrar en una pieza nueva, ponemos deadline a las
                        // próximas piezas (las que aún no tenemos), para que la descarga vaya por delante
                        // del cabezal de lectura y no nos quedemos esperando pieza a pieza (menos cortes).
                        // Solo en el cambio de pieza, no en cada read de 64KB; clampado al final del archivo.
                        if (piece != windowPiece) {
                            windowPiece = piece
                            promoteWindow(piece)
                        }
                        val pieceT0 = System.currentTimeMillis()
                        if (!waitForPiece(piece, alive)) {
                            Log.w(TS, "WAIT_PIECE_TIMEOUT piece=$piece totalWaitMs=${System.currentTimeMillis()-pieceT0} pos=$pos")
                            break
                        }
                        if (piece != windowPiece || pos == start) {
                            Log.i(TS, "PIECE_READY piece=$piece waitedMs=${System.currentTimeMillis()-pieceT0} pos=$pos")
                        }
                        // No leer más allá del final de la pieza confirmada.
                        val pieceEndInFile = (piece + 1).toLong() * pieceLength - fileOffset
                        val chunk = minOf(buf.size.toLong(), end - pos + 1, pieceEndInFile - pos).toInt()
                        if (chunk <= 0) break
                        val read = f.read(buf, 0, chunk)
                        if (read <= 0) {
                            Thread.sleep(40)
                            continue
                        }
                        out.write(buf, 0, read)
                        pos += read
                        if (pos == start + read) {
                            Log.i(TS, "FIRST_BYTE +${System.currentTimeMillis()-t0}ms pos=$pos")
                        }
                    }
                    out.flush()
                }
                Log.i(TS, "DONE +${System.currentTimeMillis()-t0}ms served=${end-start+1}B")
            }
        } catch (e: Exception) {
            Log.w(TS, "SERVE_FAIL +${System.currentTimeMillis()-t0}ms: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * Enciende (TOP + deadline) la pieza [from] y una VENTANA por delante — el resto del archivo está
     * apagado (IGNORE) para no dispersar la descarga (estilo animeko). Es lo que hace que la pieza que
     * el player necesita AHORA baje primero, y va corriendo la ventana a medida que avanza la lectura.
     */
    private fun promoteWindow(from: Int) {
        val lastFilePiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
        // Ventana adaptativa por tamaño de pieza (~STREAM_WINDOW_BYTES), clamp [4,64]. Subida 8→40MB tras
        // validar en device: con piezas grandes (8MB) una ventana de 8MB = 1-2 piezas en vuelo, insuficiente
        // para enganchar un swarm de 30 seeds (throughput tapado ~500KB/s → estancones). 40MB ≈ 5 piezas de
        // 8MB en paralelo = más peers a la vez = más throughput. La prioridad escalonada mantiene lo urgente
        // arriba, así que ensanchar NO diluye el cabezal (la pieza actual sigue en TOP).
        val win = (STREAM_WINDOW_BYTES / pieceLength).toInt().coerceIn(4, 64)
        // DEDUP: VLC abre varias conexiones que leen la MISMA posición y llaman promoteWindow(from) casi a
        // la vez; repetir las llamadas JNI (piecePriority/setPieceDeadline) por cada una martillea el handle
        // nativo y contribuye al bache post-apertura observado en device. Si ya promovimos esta región hace
        // nada (dentro de media ventana), no repetir. Se re-promueve al avanzar el cabezal de verdad.
        synchronized(promoteLock) {
            // Umbral chico (3 piezas): corta la ráfaga de conexiones concurrentes que llegan a la misma
            // posición, pero re-promueve seguido al avanzar el cabezal para mantener deadlines frescos.
            if (from in lastPromoted until (lastPromoted + 3)) return
            lastPromoted = from
        }
        val end = (from + win).coerceAtMost(lastFilePiece)
        for (p in from..end) {
            if (!runCatching { handle.havePiece(p) }.getOrDefault(false)) {
                val d = p - from
                // Prioridad ESCALONADA por distancia al cabezal (robado de Elementum torrent.go:658): la
                // pieza que se lee AHORA se lleva todo el ancho de banda (TOP); las de más adelante bajan de
                // prioridad progresivamente pero siguen en cola. Antes todo era TOP binario → el prefetch
                // lejano competía de igual a igual con lo urgente.
                runCatching { handle.piecePriority(p, windowPriority(d)) }
                // Deadline NEGATIVO (urgencia máxima) para lo que se reproduce AHORA; escalonado mantiene
                // el orden dentro de la ventana.
                runCatching { handle.setPieceDeadline(p, WINDOW_DEADLINE_BASE + d * 40) }
            }
        }
        // ZONA TIBIA (2ª pasada de gap-analysis, robado del sequential+prioridad-de-fondo de Torrest):
        // piezas de fondo en LOW(1) por delante de la ventana. Cuando el reader se frena (el decoder HW
        // tarda ~7-11s en arrancar = el "bache post-apertura"), los peers que NO tienen piezas de la ventana
        // se quedaban sin trabajo → idle/redundantes → podados. Con una cola tibia LOW siguen aportando
        // (sin pisar la ventana, que va en 7..3) → los peers no se enfrían durante el stall. Acotada a
        // WARM_PIECES para NO comprometer bajar el archivo entero (no diluye en pelis gigantes; en swarms
        // débiles la ventana igual gana por prioridad). Complementa close_redundant_connections=false.
        val warmPieces = (WARM_BYTES / pieceLength).toInt().coerceIn(8, 128)
        val warmEnd = (end + warmPieces).coerceAtMost(lastFilePiece)
        for (p in (end + 1)..warmEnd) {
            if (!runCatching { handle.havePiece(p) }.getOrDefault(false)) {
                runCatching { handle.piecePriority(p, Priority.LOW) }
            }
        }
    }

    /** Prioridad decreciente por distancia al cabezal de lectura (7/6/5/4/3), estilo Elementum. */
    private fun windowPriority(distance: Int): Priority = when {
        distance == 0 -> Priority.TOP_PRIORITY
        distance <= 2 -> Priority.SIX
        distance <= 5 -> Priority.FIVE
        distance <= 9 -> Priority.DEFAULT
        else -> Priority.THREE
    }

    /** Espera a que la pieza esté disponible (encendiéndola + la ventana). Aborta si el cliente cerró. */
    private fun waitForPiece(piece: Int, isAlive: () -> Boolean = { true }): Boolean {
        val t0 = System.currentTimeMillis()
        // Sin log en el camino rápido: waitForPiece se llama por cada chunk de 64KB (×cada conexión de
        // VLC) → loguear "immediate" aquí inundaba el logcat (cientos de líneas/seg). Sólo se loguea
        // cuando de verdad se ESPERA una pieza (abajo) o hay timeout/abort.
        if (runCatching { handle.havePiece(piece) }.getOrDefault(false)) return true
        promoteWindow(piece)
        var waited = 0
        while (running && waited < 120_000) {
            if (runCatching { handle.havePiece(piece) }.getOrDefault(false)) {
                Log.i(TS, "PIECE_HAVE piece=$piece waitedMs=${waited} totalMs=${System.currentTimeMillis()-t0}")
                return true
            }
            // Cortar si VLC cerró la conexión (seek → abandona este /video): no seguir 120s bloqueado
            // priorizando/bajando piezas de un stream muerto (CloseNotifier de Torrest reader.go:75).
            if (!isAlive()) {
                Log.i(TS, "PIECE_ABORT piece=$piece client-gone waitedMs=$waited")
                return false
            }
            Thread.sleep(100)
            waited += 100
        }
        Log.w(TS, "PIECE_TIMEOUT piece=$piece waitedMs=${waited} peers=${runCatching { handle.status().numPeers() }.getOrDefault(-1)}")
        return runCatching { handle.havePiece(piece) }.getOrDefault(false)
    }

    /**
     * ¿El cliente (VLC) cerró la conexión? Robado del CloseNotifier de Torrest (reader.go:75). Lectura no
     * bloqueante con timeout corto sobre el input ya consumido: -1 = EOF (cerró limpio); SocketTimeout =
     * sigue vivo sin datos pendientes; cualquier otra excepción (RST) = se fue.
     */
    private fun clientGone(input: java.io.InputStream, socket: Socket): Boolean = try {
        val prev = socket.soTimeout
        socket.soTimeout = 5
        val b = try { input.read() } finally { socket.soTimeout = prev }
        b == -1
    } catch (_: java.net.SocketTimeoutException) {
        false
    } catch (_: Exception) {
        true
    }

    /** Espera a que libtorrent cree el archivo en disco. */
    private fun waitAndOpen(): RandomAccessFile? {
        val t0 = System.currentTimeMillis()
        var waited = 0
        while (running && waited < 30_000) {
            if (file.exists()) {
                Log.i(TS, "FILE_EXISTS waitedMs=$waited path=${file.absolutePath}")
                return runCatching { RandomAccessFile(file, "r") }.getOrNull()
            }
            Thread.sleep(100)
            waited += 100
        }
        Log.w(TS, "FILE_TIMEOUT waitedMs=${waited} totalMs=${System.currentTimeMillis()-t0} path=${file.absolutePath}")
        return runCatching { RandomAccessFile(file, "r") }.getOrNull()
    }

    private fun contentType(): String = when (file.extension.lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "video/x-matroska"
    }

    companion object {
        /** Bytes de read-ahead por delante del cabezal (se traduce a piezas según el tamaño de pieza).
         *  40MB (≈5 piezas de 8MB) para tener varias piezas en vuelo y saturar swarms grandes. */
        private const val STREAM_WINDOW_BYTES = 40L * 1024 * 1024

        /** Deadline base (negativo = urgencia máxima) para la ventana del cabezal de lectura. */
        private const val WINDOW_DEADLINE_BASE = -9_000

        /** Zona TIBIA de fondo (LOW) por delante de la ventana: mantiene peers activos durante el stall del
         *  decoder sin comprometer el archivo entero. 64MB constante (byte-based, cualquier tamaño de pieza). */
        private const val WARM_BYTES = 64L * 1024 * 1024
    }
}
