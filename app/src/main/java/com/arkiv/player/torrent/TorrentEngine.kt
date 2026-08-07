package com.arkiv.player.torrent

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File

/** Un archivo dentro de un torrent. */
data class TorrentFile(val index: Int, val name: String, val sizeBytes: Long)

/** Metadata resuelta de un torrent (magnet o .torrent). */
data class TorrentMeta(
    val infoHashHex: String,
    val name: String,
    val files: List<TorrentFile>,
    val infoBytes: ByteArray,
)

/** Estado de descarga del stream activo. */
data class TorrentProgress(val downloadKbps: Int, val peers: Int, val progress: Float)

/** Pista del episodio pedido, para elegir el archivo correcto dentro de un pack (magnet). */
data class EpisodeHint(val season: Int, val episode: Int, val absoluteEpisode: Int? = null)

/**
 * Motor de torrents con streaming secuencial. Modo "stream y descartar":
 * descarga al cacheDir y borra al parar. Un solo stream activo a la vez.
 */
class TorrentEngine(context: Context, private val extraTrackers: () -> List<String> = { DEFAULT_TRACKERS }) {
    private val appContext = context.applicationContext
    private val session = SessionManager()
    private val workDir = File(appContext.cacheDir, "torrents")

    @Volatile private var server: TorrentStreamServer? = null
    @Volatile private var currentHandle: TorrentHandle? = null
    @Volatile private var currentDir: File? = null
    // Estado del stream desde magnet (no bloqueante): la URL se completa al llegar la metadata.
    @Volatile private var readyUrl: String? = null
    @Volatile private var awaitingMetadata = false
    // Hint del episodio pedido (camino magnet): usado por pickVideoIndex para elegir, dentro de un
    // pack, el archivo correcto en vez del más grande.
    @Volatile private var pendingHint: EpisodeHint? = null
    // Piezas de la CABEZA del archivo servido (colchón de arranque). Se llena en beginServing y lo
    // consulta la pantalla (headBuffered) para no arrancar VLC hasta tener la primera pieza.
    @Volatile private var headRange: IntRange? = null
    // Piezas de la COLA del archivo servido (índice: Cues de MKV / moov de MP4 / idx1 de AVI). VLC las lee
    // al ABRIR para conocer duración/seek; si no están, estanca. El gate espera cabeza + cola (bufferReady).
    @Volatile private var footerRange: IntRange? = null
    // Archivo servido en disco (para calcular el moviehash de OpenSubtitles: head+tail 64KB, ya bufferizados).
    @Volatile private var servedFile: File? = null
    // Subtítulos EMBEBIDOS en el torrent (.srt/.ass junto al video), priorizados para bajar al instante.
    @Volatile private var embeddedSubFiles: List<File> = emptyList()
    // Geometría del archivo servido (para isFractionBuffered: mapear una fracción de tiempo a pieza).
    @Volatile private var servedFileOffset: Long = 0
    @Volatile private var servedFileSize: Long = 0
    @Volatile private var servedPieceLen: Long = 0
    // Loop de reannounce mientras hay pocos peers (Tier 2 #1): busca más fuentes agresivamente.
    @Volatile private var reannounceThread: Thread? = null

    // Locks para que el SO no throttlee la descarga con pantalla apagada o casteando (Tier 2 #2).
    // WifiLock mantiene la Wi-Fi despierta; el PARTIAL_WAKE_LOCK es el que de verdad sostiene la CPU/red
    // en background. No referenceCounted: acquire()/release() idempotentes.
    private val wifiLock: WifiManager.WifiLock by lazy {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm.createWifiLock(mode, "arkiv:torrent").apply { setReferenceCounted(false) }
    }
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arkiv:torrent").apply { setReferenceCounted(false) }
    }

    /** Escucha la llegada de la metadata del magnet (técnica no bloqueante estilo Popcorn Time). */
    private val alertListener = object : org.libtorrent4j.AlertListener {
        override fun types() = intArrayOf(AlertType.METADATA_RECEIVED.swig())
        override fun alert(alert: Alert<*>) {
            if (alert is MetadataReceivedAlert && awaitingMetadata) {
                runCatching { onMetadataReady(alert.handle()) }
                    .onFailure { Log.w("ArkivTorrent", "onMetadata: $it") }
            }
        }
    }

    /**
     * Calienta la sesión + DHT en segundo plano (fire-and-forget, idempotente vía [ensureStarted]).
     * Llamar al componer una pantalla de detalle de torrent: para cuando el usuario elija un episodio,
     * el routing table de DHT ya está poblado y el fetch de metadata/peers arranca en caliente.
     */
    fun warmUp() {
        Thread { runCatching { ensureStarted() } }.apply { isDaemon = true }.start()
    }

    /**
     * Peers REALES del swarm por DHT (lo que ve el player al reproducir). Bloqueante ~[timeoutSec]s:
     * la sesión ya tiene la DHT caliente, así que devuelve los peers que la red conoce del infohash.
     * Muchas fuentes latino/cast reportan 1 seed y el scrape de trackers subcuenta (peers en DHT, no
     * en trackers); esto da el número correcto. Null si el hash es inválido o no hay peers.
     */
    suspend fun dhtPeerCount(infoHashHex: String?, timeoutSec: Int = 4): Int? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val hex = infoHashHex?.trim()?.takeIf { it.length == 40 } ?: return@withContext null
            runCatching { ensureStarted() }
            val hash = runCatching { Sha1Hash.parseHex(hex) }.getOrNull() ?: return@withContext null
            val peers = runCatching { session.dhtGetPeers(hash, timeoutSec) }.getOrNull() ?: return@withContext null
            peers.size.takeIf { it > 0 }
        }

    @Synchronized
    fun ensureStarted() {
        if (!session.isRunning) {
            // Arrancar con los settings (incluidos los nodos DHT de bootstrap) vía SessionParams, ANTES de
            // startDht — así el bootstrap usa los nodos extra (applySettings después no lo re-dispara).
            runCatching { session.start(SessionParams(tunedSettings())) }
                .onFailure { Log.w("ArkivTorrent", "start(params): $it"); session.start() }
            session.startDht()
            session.addListener(alertListener)
            Log.i("ArkivTorrent", "session started")
        }
    }

    /**
     * SettingsPack de arranque: la sesión venía SIN tunear (defaults conservadores). Estos son los knobs
     * que de verdad mueven la aguja para los torrents de POCOS SEEDS que apunta esta app (arranque + más
     * peers), verificados contra la API de libtorrent 2.0 (libtorrent4j 2.1.0-31):
     *  - announce_to_all_trackers/tiers: el PRIMER announce pega a TODOS los trackers inyectados
     *    (todos en tier 0) en paralelo, en vez de parar en el primero que responde.
     *  - connection_speed alto: más intentos de conexión saliente por segundo hacia los pocos peers que
     *    devuelven DHT/trackers (el lever real del arranque).
     *  - connections_limit alto + unchoke_slots_limit=-1: más peers conectados y mejor reciprocidad
     *    (más peers te desahogan de vuelta = más throughput).
     *  - peer_connect_timeout=10s: descarta peers muertos rápido sin cortar handshakes lentos de móvil.
     *  - allow_multiple_connections_per_ip: peers detrás de un mismo IP (CGNAT LatAm / VPN).
     *  - prefer_udp_trackers: los trackers inyectados son UDP; priorizarlos.
     * (LSD/UPnP/NAT-PMP y uTP/TCP ya vienen ON por default: no se tocan.)
     */
    private fun tunedSettings(): SettingsPack = SettingsPack().apply {
        connectionsLimit(300)
        setInteger(settings_pack.int_types.connection_speed.swigValue(), 300)
        setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), -1)
        // peer_connect_timeout 10→5: descarta peers muertos más rápido (Elementum/Torrest usan 2-3;
        // 5 es el punto medio que no corta handshakes lentos de móvil). request_timeout=5: no esperar
        // eternamente un bloque pedido a un peer lento; reintenta con otro antes.
        setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 5)
        setInteger(settings_pack.int_types.request_timeout.swigValue(), 5)
        setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
        setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true)

        // --- Flags de STREAMING robados de Elementum/Torrest (bittorrent/service.go) ---
        // strict_end_game_mode: al final del buffer, pide las piezas que faltan a VARIOS peers a la vez
        // (mata la "última pieza lenta" que estanca el arranque). prioritize_partial_pieces=false: deja
        // que MANDE nuestra prioridad/deadline de streaming en vez de rellenar piezas a medias dispersas.
        setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), true)
        setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), false)
        // mixed_mode_algorithm=prefer_tcp(0): en móvil el uTP compite mal; preferir TCP da throughput
        // más estable. whole_pieces_threshold=10: si a un peer le faltan ≤10 piezas para completar una,
        // pídesela entera → mejor contigüidad para lectura secuencial.
        setInteger(settings_pack.int_types.mixed_mode_algorithm.swigValue(), 0)
        setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 10)
        setBoolean(settings_pack.bool_types.use_parole_mode.swigValue(), true)
        // no_atime_storage: no reescribir atime en cada lectura → menos desgaste/IO en flash (Fire TV/SD).
        setBoolean(settings_pack.bool_types.no_atime_storage.swigValue(), true)

        // --- Límites de sesión + timeouts idle (modo streaming de 1 torrent) ---
        // Solo servimos UN stream: sin límite de torrents activos para que nunca quede en cola.
        setInteger(settings_pack.int_types.active_downloads.swigValue(), -1)
        setInteger(settings_pack.int_types.active_limit.swigValue(), -1)
        setInteger(settings_pack.int_types.active_dht_limit.swigValue(), 88)
        // Timeouts idle LARGOS (Elementum modo memoria): al pausar el usuario, no soltar los peers ni
        // matar conexiones ociosas — así al reanudar sigue con el swarm caliente en vez de reconstruirlo.
        setInteger(settings_pack.int_types.peer_timeout.swigValue(), 600)
        setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 1800)

        // --- GESTIÓN DE CONEXIONES (2ª pasada de gap-analysis, robado de Elementum+Torrest) ---
        // Objetivo: el "bache post-apertura" (throughput cae ~7-11s al arrancar el decoder HW de VLC en la
        // TV, y los peers bajan de golpe). Causa: cuando el reader se frena, la descarga se para → libtorrent
        // PODA peers "redundantes" y el pipeline de requests se vacía → hay que re-cebar todo. Estos knobs
        // mantienen peers y pipeline vivos durante el stall:
        // - close_redundant_connections=false: NO podar peers cuando la descarga se para (el default true es
        //   el sospechoso #1 del "peers 59→1"). Elementum service.go:436.
        setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        // - Pipeline de requests PROFUNDO (default 500 bloques ≈8MB): que el stall del decoder no lo drene.
        setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 5000)
        setInteger(settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 5000)
        setInteger(settings_pack.int_types.request_queue_time.swigValue(), 2)
        // - min_reconnect_time 60→20: recuperar 3× más rápido los peers caídos durante el bache.
        setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 20)
        // - Banco de peers grande + PEX rápido: más candidatos para reponer throughput tras el bache.
        setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 50000)
        setInteger(settings_pack.int_types.max_pex_peers.swigValue(), 200)
        // - min_announce_interval 300→30 + dht_announce_interval 900→60: DESBLOQUEA el loop de reannounce
        //   agresivo de Arkiv (hoy topaba contra el mínimo de 300s de libtorrent). Elementum service.go:268.
        setInteger(settings_pack.int_types.min_announce_interval.swigValue(), 30)
        setInteger(settings_pack.int_types.dht_announce_interval.swigValue(), 60)
        // - smooth_connects=false: abrir conexiones en ráfaga (re-cebado más rápido al arrancar y tras el bache).
        setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), false)
        // - aio_threads: I/O de disco en paralelo (Arkiv baja a disco); en la CPU débil del Fire Stick, ×2.
        setInteger(settings_pack.int_types.aio_threads.swigValue(), Runtime.getRuntime().availableProcessors() * 2)

        // Nodos DHT de bootstrap extra (estilo animeko): descubrimiento de peers más robusto en el
        // arranque frío. Incluye el default de libtorrent (no se puede prepend por API) + los clásicos.
        setDhtBootstrapNodes(
            "dht.libtorrent.org:25401,router.bittorrent.com:6881," +
                "dht.transmissionbt.com:6881,router.utorrent.com:6881," +
                "dht.aelitis.com:6881,router.silotis.us:6881", // +Vuze e IPv6 (Torrest service.go:27)
        )
    }

    /**
     * Streaming NO bloqueante desde un magnet (como Popcorn Time): arranca `session.download` y al
     * recibir la metadata por alert elige el video más grande y levanta el server. No se cuelga
     * esperando: [streamReadyUrl] devuelve la URL cuando está lista y [streamStatus] da peers/progreso
     * mientras tanto. Ideal para torrents de pocos seeds (sigue buscando en vez de fallar a los 45s).
     */
    @Synchronized
    fun startMagnetStream(magnet: String, hint: EpisodeHint? = null) {
        ensureStarted()
        stopStreamInternal()
        acquireLocks()
        val clean = cleanMagnet(magnet) ?: run { Log.w("ArkivTorrent", "magnet inválido"); return }
        val dir = File(workDir, "m${System.nanoTime()}").apply { mkdirs() }
        currentDir = dir
        readyUrl = null
        awaitingMetadata = true
        pendingHint = hint
        runCatching { session.download(clean, dir, org.libtorrent4j.swig.torrent_flags_t.from_int(0)) }
            .onFailure { Log.w("ArkivTorrent", "download magnet: $it"); awaitingMetadata = false }
    }

    /** URL local del stream una vez lista (null mientras se busca metadata/peers). */
    fun streamReadyUrl(): String? = readyUrl

    /**
     * True si la cabeza del archivo (colchón de arranque) ya está descargada, o si no hay rango que
     * esperar todavía. La pantalla la sondea (con feedback de peers/progreso) para NO abrir VLC hasta
     * tener la primera pieza: así arranca limpio (sin el broken-pipe de VLC esperando datos que no llegan).
     */
    fun headBuffered(): Boolean {
        val h = currentHandle ?: return false
        val r = headRange ?: return false
        if (r.isEmpty()) return true
        return r.all { runCatching { h.havePiece(it) }.getOrDefault(false) }
    }

    /**
     * Gate de arranque REAL (robado de Elementum/Torrest): listo sólo cuando la CABEZA **y** la COLA
     * (índice: Cues/moov/idx1) están descargadas al 100%. Antes bastaba la primera pieza de la cabeza,
     * pero VLC lee el índice del final al abrir el contenedor y, si no estaba, estancaba al arrancar.
     * Esperar ambos bloques da un arranque predecible sin rebuffering temprano.
     */
    /**
     * Hash de OpenSubtitles (OSDb) del archivo servido, para buscar subtítulos del release EXACTO. Válido
     * sólo tras el gate (bufferReady): necesita los primeros y últimos 64KB, que están en la cabeza+cola.
     */
    fun servedMovieHash(): String? {
        // El hash necesita los primeros y ÚLTIMOS 64KB reales en disco. Si la cola aún no bajó (p.ej. gate
        // por timeout), leer el tail devolvería basura (ceros del archivo sparse) → hash inservible. Sólo
        // calcular con cabeza+cola confirmadas.
        if (!bufferReady()) return null
        return servedFile?.let {
            com.arkiv.player.data.subtitles.MovieHash.compute(it)
                .also { h -> Log.i("ArkivTorrent", "moviehash=$h file=${servedFile?.name}") }
        }
    }

    /** Archivos de subtítulo embebidos en el torrent que YA se descargaron (para cargarlos como pista). */
    fun embeddedSubtitleFiles(): List<File> = embeddedSubFiles.filter { it.exists() && it.length() > 0 }

    fun bufferReady(): Boolean {
        val h = currentHandle ?: return false
        val head = headRange ?: return false
        val allHead = head.isEmpty() || head.all { runCatching { h.havePiece(it) }.getOrDefault(false) }
        if (!allHead) return false
        val foot = footerRange ?: return true
        return foot.isEmpty() || foot.all { runCatching { h.havePiece(it) }.getOrDefault(false) }
    }

    /**
     * Progreso del buffer de arranque en 0..100 para el overlay "Preparando…". Usa bytes PARCIALES
     * (totalWantedDone/totalWanted) en vez de contar piezas completas: con piezas grandes (8MB) contar
     * completas saltaba 0%→50%→100% (parecía congelado); así sube suave. En el gate, "wanted" == cabeza
     * + cola (todo lo demás es IGNORE), por lo que el ratio es exactamente el llenado del buffer de arranque.
     */
    fun bufferProgress(): Int {
        val h = currentHandle ?: return 0
        val s = runCatching { h.status() }.getOrNull() ?: return 0
        val wanted = runCatching { s.totalWanted() }.getOrDefault(0L)
        if (wanted <= 0L) return 0
        val done = runCatching { s.totalWantedDone() }.getOrDefault(0L)
        return (done * 100 / wanted).toInt().coerceIn(0, 100)
    }

    /**
     * True si la posición [fraction] (0..1) del archivo servido ya está descargada. La pantalla lo usa
     * para NO retomar ("continuar donde ibas") saltando a una zona que el torrent aún no bajó — como baja
     * secuencial desde el inicio, saltar en frío a la mitad stalea. Si no está, se arranca desde 0.
     */
    fun isFractionBuffered(fraction: Float): Boolean {
        val h = currentHandle ?: return false
        if (servedFileSize <= 0 || servedPieceLen <= 0) return false
        val byte = servedFileOffset + (servedFileSize * fraction.coerceIn(0f, 1f)).toLong()
        val piece = (byte / servedPieceLen).toInt()
        return runCatching { h.havePiece(piece) }.getOrDefault(false)
    }

    @Synchronized
    private fun onMetadataReady(alertHandle: TorrentHandle) {
        if (!awaitingMetadata) return
        val info = runCatching { alertHandle.torrentFile() }.getOrNull() ?: return
        if (!info.isValid) return
        // El handle del alert es TEMPORAL: usarlo desde el thread del server crashea (SIGSEGV en
        // havePiece). Obtenemos uno ESTABLE por infohash, como hace el path de bytes con pollHandle.
        val handle = runCatching { session.find(info.infoHash()) }.getOrNull()?.takeIf { it.isValid }
            ?: pollHandle(info) ?: return
        awaitingMetadata = false
        currentHandle = handle
        val dir = currentDir ?: File(workDir, info.infoHash().toHex()).apply { mkdirs() }
        val fileIndex = pickVideoIndex(info)
        readyUrl = beginServing(handle, info, dir, fileIndex)
        Log.i("ArkivTorrent", "magnet metadata -> serving file=$fileIndex $readyUrl")
    }

    /** Elige el índice del archivo de video del episodio pedido (hint) o, si no hay match, el más grande. */
    private fun pickVideoIndex(info: TorrentInfo): Int {
        val fs = info.files()
        val videos = (0 until fs.numFiles()).filter {
            fs.fileName(it).substringAfterLast('.', "").lowercase() in VIDEO_EXT
        }.ifEmpty { (0 until fs.numFiles()).toList() }
            // Quitar samples/extras: sin esto un torrent con "movie.mkv" + "sample.mkv" podía servir el
            // sample (30s) si el matching por nombre lo pescaba. Si todo parece basura, no filtra.
            .let { vs -> vs.filterNot { SampleFilter.isJunk(fs.fileName(it)) }.ifEmpty { vs } }
        val hint = pendingHint
        if (hint != null) {
            val names = videos.map { fs.fileName(it) }
            EpisodeFilePicker.pick(names, hint.season, hint.episode, hint.absoluteEpisode)?.let { return videos[it] }
        }
        return videos.maxByOrNull { fs.fileSize(it) } ?: 0
    }

    /** Resuelve un magnet (descarga la metadata del .torrent). Bloqueante ~timeout. */
    suspend fun resolveMagnet(magnet: String, timeoutSec: Int = 45): TorrentMeta? =
        withContext(Dispatchers.IO) {
            ensureStarted()
            workDir.mkdirs()
            val clean = cleanMagnet(magnet) ?: run {
                Log.w("ArkivTorrent", "magnet inválido (sin btih): ${magnet.take(40)}")
                return@withContext null
            }
            val bytes = runCatching { session.fetchMagnet(clean, timeoutSec, workDir) }
                .onFailure { Log.w("ArkivTorrent", "fetchMagnet: $it") }
                .getOrNull() ?: return@withContext null
            runCatching { metaFrom(TorrentInfo.bdecode(bytes), bytes) }.getOrNull()
        }

    /**
     * Reconstruye un magnet limpio: extrae el infohash y le pone SOLO trackers públicos válidos
     * (UDP/HTTP). Descarta los trackers originales, que a veces incluyen `wss://` (webtorrent) u
     * otros protocolos que libtorrent rechaza con "unsupported URL protocol". Devuelve null si el
     * magnet no tiene un btih válido.
     */
    private fun cleanMagnet(magnet: String): String? {
        if (!magnet.startsWith("magnet:")) return null
        val hash = Regex("xt=urn:btih:([a-zA-Z0-9]{32,40})").find(magnet)?.groupValues?.get(1) ?: return null
        val dn = Regex("[&?]dn=([^&]*)").find(magnet)?.groupValues?.get(1)?.let { "&dn=$it" } ?: ""
        val tr = extraTrackers().joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$hash$dn$tr"
    }

    /** Resuelve un .torrent a partir de sus bytes. */
    suspend fun resolveTorrent(data: ByteArray): TorrentMeta? = withContext(Dispatchers.IO) {
        runCatching { metaFrom(TorrentInfo(data), data) }.getOrNull()
    }

    private fun metaFrom(info: TorrentInfo, raw: ByteArray): TorrentMeta? {
        if (!info.isValid) return null
        val fs = info.files()
        val files = (0 until fs.numFiles()).map { TorrentFile(it, fs.fileName(it), fs.fileSize(it)) }
        return TorrentMeta(info.infoHash().toHex(), info.name(), files, raw)
    }

    /** Todos los archivos de video del torrent, ordenados por nombre (para packs/series). */
    fun videoFiles(meta: TorrentMeta): List<TorrentFile> =
        meta.files.filter { it.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT }
            .sortedWith(compareBy({ naturalKey(it.name) }, { it.name }))

    /** Elige el archivo de video más grande (o el más grande si no hay video claro). */
    fun pickVideo(meta: TorrentMeta): TorrentFile? {
        val videos = meta.files.filter {
            it.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
        }.ifEmpty { meta.files }
        val notJunk = videos.filterNot { SampleFilter.isJunk(it.name) }.ifEmpty { videos }
        return notJunk.maxByOrNull { it.sizeBytes }
    }

    /** URL del stream accesible por la LAN (para castear a Chromecast/DLNA). */
    fun lanStreamUrl(): String? {
        val s = server ?: return null
        val ip = lanIp() ?: return null
        return "http://$ip:${s.port}/video"
    }

    /** MIME del stream activo (para el cast). */
    fun streamMime(): String? = server?.mime

    /** URL del stream por loopback. Es la que conviene consumir DESDE el propio celu (por ejemplo el
     *  transcodificador de cast): no sale a la red. */
    fun localStreamUrl(): String? = server?.let { "http://127.0.0.1:${it.port}/video" }

    /** Nombre del archivo que se está sirviendo. Es el único título real que hay cuando el torrent
     *  vino por magnet (ahí no se resuelve metadata por adelantado como en la rama de bytes). */
    fun servedFileName(): String? = servedFile?.name

    /** IP del host en la LAN para castear/DLNA: Wi-Fi primero (la interfaz que suele compartir LAN con
     *  el renderer); si no hay (p. ej. Fire TV cableado), escanea las interfaces por una IPv4 site-local. */
    fun lanIp(): String? = wifiIp() ?: siteLocalIp()

    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo.ipAddress
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

    /** Estado del stream activo (velocidad, peers, progreso), o null si no hay. */
    fun streamStatus(): TorrentProgress? {
        val h = currentHandle ?: return null
        val s = runCatching { h.status() }.getOrNull() ?: return null
        return TorrentProgress(
            downloadKbps = s.downloadRate() / 1024,
            peers = s.numPeers(),
            progress = s.progress(),
        )
    }

    /** Clave de orden natural: rellena los números con ceros para ordenar E2 < E10. */
    private fun naturalKey(s: String): String =
        Regex("\\d+").replace(s.lowercase()) { it.value.padStart(6, '0') }

    /** Inicia el stream desde un .torrent ya resuelto (bytes) y devuelve la URL HTTP local. */
    @Synchronized
    fun startStream(meta: TorrentMeta, fileIndex: Int): String {
        ensureStarted()
        stopStreamInternal()
        acquireLocks()
        val info = TorrentInfo(meta.infoBytes)
        val dir = File(workDir, meta.infoHashHex).apply { mkdirs() }
        session.download(info, dir)
        val handle = pollHandle(info) ?: error("No se pudo iniciar el torrent")
        currentHandle = handle
        currentDir = dir
        return beginServing(handle, info, dir, fileIndex).also {
            Log.i("ArkivTorrent", "stream ${meta.name} file=$fileIndex -> $it")
        }
    }

    /**
     * Arranca una descarga PERSISTENTE del archivo [fileIndex] en [saveDir]. A diferencia de
     * [startStream]:
     *
     * - NO llama a `stopStreamInternal()`, así que no mata el stream que se esté reproduciendo;
     * - NO toca `currentHandle` ni levanta el server local: el handle se lo queda quien llama;
     * - prioriza el archivo en NORMAL (descarga completa) en vez del gate cabeza+cola del streaming;
     * - `saveDir` está fuera de `workDir`, así que `sweepOrphans()` no lo borra.
     *
     * Devuelve null si el torrent no arrancó.
     */
    @Synchronized
    fun startPersistentDownload(meta: TorrentMeta, fileIndex: Int, saveDir: File): PersistentTorrentDownload? {
        ensureStarted()
        acquireLocks()
        saveDir.mkdirs()
        val info = TorrentInfo(meta.infoBytes)
        session.download(info, saveDir)
        val handle = pollHandle(info) ?: run {
            Log.w("ArkivTorrent", "startPersistentDownload: no se pudo obtener el handle")
            return null
        }
        runCatching {
            extraTrackers().forEach { handle.addTracker(AnnounceEntry(it)) }
            handle.forceReannounce()
        }
        // Solo el archivo pedido: bajar el pack entero llenaría el disco del celular.
        // NOTA: la API real no tiene Priority.NORMAL (el brief lo daba por sentado); el nivel
        // "normal" en esta versión de libtorrent4j es Priority.DEFAULT.
        val priorities = Array(info.numFiles()) { if (it == fileIndex) Priority.DEFAULT else Priority.IGNORE }
        runCatching { handle.prioritizeFiles(priorities) }
            .onFailure { Log.w("ArkivTorrent", "prioritizeFiles: $it") }
        val relativePath = info.files().filePath(fileIndex)
        Log.i("ArkivTorrent", "DESCARGA infohash=${meta.infoHashHex} file=$fileIndex '$relativePath' -> $saveDir")
        return PersistentTorrentDownload(session, handle, saveDir, relativePath)
    }

    /** Prioriza el archivo elegido, inyecta trackers, arranca el server local y devuelve la URL. */
    private fun beginServing(handle: TorrentHandle, info: TorrentInfo, dir: File, fileIndex: Int): String {
        // Inyectar trackers públicos + reanunciar para encontrar más peers (muchos .torrent,
        // sobre todo de trackers españoles, traen pocos trackers). DHT ya está activo.
        runCatching {
            extraTrackers().forEach { handle.addTracker(AnnounceEntry(it)) }
            handle.forceReannounce()
        }
        val fs = info.files()
        // Traza del stream: infohash + nombre + archivo servido (para reproducir/depurar un torrent concreto).
        Log.i("ArkivTorrent", "STREAM infohash=${info.infoHash().toHex()} name='${info.name()}' file=$fileIndex '${fs.fileName(fileIndex)}' size=${fs.fileSize(fileIndex)}")
        val pieceLen = info.pieceLength().toLong()
        val firstPiece = (fs.fileOffset(fileIndex) / pieceLen).toInt()
        val fileEnd = fs.fileOffset(fileIndex) + fs.fileSize(fileIndex) - 1
        val lastPiece = (fileEnd / pieceLen).toInt()

        // Geometría del archivo servido (para isFractionBuffered y para que el server promueva la ventana).
        servedFileOffset = fs.fileOffset(fileIndex)
        servedFileSize = fs.fileSize(fileIndex)
        servedPieceLen = pieceLen

        // ESTRATEGIA DE DESCARGA (estilo animeko): NO bajar todo — eso dispersa la descarga y mata la
        // pieza 0. Apagamos TODAS las piezas del torrent y encendemos solo la CABEZA + el FOOTER del
        // archivo servido. CLAVE: una SOLA llamada ATÓMICA prioritizePieces() — no prioritizeFiles()
        // seguido de piecePriority() pieza a pieza, porque prioritizeFiles (async) pisaba nuestros IGNORE
        // de vuelta a TOP y por eso bajaban 95MB dispersos. El resto lo enciende el server (promoteWindow).
        // Cabeza INICIAL chica (solo el header ~2MB = la(s) primera(s) pieza(s)): así la pieza 0 se lleva
        // TODO el ancho de banda y baja en pocos segundos, en vez de repartirlo entre 4 piezas (32MB) y
        // tardar 20s. La ventana grande la extiende promoteWindow a medida que VLC lee.
        // Tamaños de cabeza/cola por contenedor (antes MP4_LIKE era código muerto). El moov de un MP4/MOV
        // no-faststart va AL FINAL y puede ser grande → footer amplio; si es faststart va al principio →
        // cabeza amplia. En MKV el SeekHead es chico al frente pero los Cues (índice) al final pueden pasar
        // de 1MB en pelis largas → footer de 5MB en vez de 1MB para no estancar el primer seek/duración.
        val ext = fs.fileName(fileIndex).substringAfterLast('.', "").lowercase()
        val isMp4Like = ext in MP4_LIKE
        val headerBytes = if (isMp4Like) HEADER_BYTES_MP4 else HEADER_BYTES
        val footerBytes = if (isMp4Like) FOOTER_BYTES_MP4 else FOOTER_BYTES
        val headWin = (headerBytes / pieceLen).toInt().coerceIn(1, if (isMp4Like) 16 else 8)
        val headEnd = (firstPiece + headWin - 1).coerceAtMost(lastPiece)
        val footerWin = (footerBytes / pieceLen).toInt().coerceAtLeast(1)
        val footerStart = (lastPiece - footerWin + 1).coerceAtLeast(headEnd + 1)
        // Gate de arranque = EXACTAMENTE las piezas priorizadas de cabeza + cola (todo lo demás IGNORE).
        // VLC lee el header (frente) y el índice (final) al abrir. Al gatear sobre las mismas piezas que
        // se priorizan, bufferProgress (totalWantedDone/totalWanted) sube limpio de 0 a 100%.
        headRange = firstPiece..headEnd
        footerRange = footerStart..lastPiece

        // Subtítulos EMBEBIDOS en el torrent (.srt/.ass junto al video): identificarlos y priorizarlos
        // (son KB → bajan al instante) para ofrecerlos como pista sin depender de subs online. Robado de
        // Alfa (servers/torrent.py:444, copiar los .srt que acompañan al video).
        val allFiles = (0 until fs.numFiles()).map { it to fs.filePath(it) }
        val subIndices = runCatching { SubtitleFilePicker.pick(allFiles, fs.filePath(fileIndex)) }.getOrDefault(emptyList())
        val subPieceRanges = subIndices.map { idx ->
            val so = fs.fileOffset(idx); val se = so + fs.fileSize(idx) - 1
            (so / pieceLen).toInt()..(se / pieceLen).toInt()
        }
        embeddedSubFiles = subIndices.map { File(dir, fs.filePath(it)) }
        if (subIndices.isNotEmpty()) Log.i("ArkivTorrent", "subs embebidos: ${embeddedSubFiles.map { it.name }}")

        val pr = Array(info.numPieces()) { Priority.IGNORE }
        for (p in firstPiece..headEnd) if (p in pr.indices) pr[p] = Priority.TOP_PRIORITY
        for (p in footerStart..lastPiece) if (p in pr.indices) pr[p] = Priority.TOP_PRIORITY
        subPieceRanges.forEach { r -> for (p in r) if (p in pr.indices) pr[p] = Priority.TOP_PRIORITY }
        runCatching { handle.prioritizePieces(pr) }
            .onFailure { Log.w(DIAG, "prioritizePieces FALLÓ: $it") }
        subPieceRanges.forEach { r -> for (p in r) runCatching { handle.setPieceDeadline(p, -8_000) } }
        Log.i(DIAG, "SETUP numPieces=${info.numPieces()} pieceLen=$pieceLen head=$firstPiece..$headEnd footer=$footerStart..$lastPiece headWin=$headWin footerWin=$footerWin")
        // DIAGNÓSTICO: (1) leer de vuelta las prioridades reales (¿pegó el IGNORE?); (2) loop periódico con
        // peers/tasa/progreso y si piezas del CUERPO se están filtrando (bajan aunque deberían estar apagadas).
        val midBody = (headEnd + footerStart) / 2
        val quarterBody = firstPiece + (lastPiece - firstPiece) / 4
        Thread {
            Thread.sleep(300)
            val h0 = runCatching { handle.piecePriority(firstPiece) }.getOrNull()
            val hb = runCatching { handle.piecePriority(midBody) }.getOrNull()
            val hq = runCatching { handle.piecePriority(quarterBody) }.getOrNull()
            Log.i(DIAG, "PRIO-CHECK head[$firstPiece]=$h0  body[$midBody]=$hb  quarter[$quarterBody]=$hq  (esperado head=TOP_PRIORITY, body/quarter=IGNORE)")
            repeat(60) {
                if (currentHandle !== handle) return@Thread
                val st = runCatching { handle.status() }.getOrNull() ?: return@repeat
                val have0 = runCatching { handle.havePiece(firstPiece) }.getOrDefault(false)
                val haveB = runCatching { handle.havePiece(midBody) }.getOrDefault(false)
                val haveQ = runCatching { handle.havePiece(quarterBody) }.getOrDefault(false)
                val haveL = runCatching { handle.havePiece(lastPiece) }.getOrDefault(false)
                Log.i(
                    DIAG,
                    "STATE peers=${st.numPeers()} seeds=${st.numSeeds()} dl=${st.downloadRate() / 1024}KB/s " +
                        "prog=${(st.progress() * 100).toInt()}% | have head0=$have0 footer=$haveL " +
                        "BODY-LEAK body=$haveB quarter=$haveQ (body/quarter deberían ser false)",
                )
                Thread.sleep(2000)
            }
        }.apply { isDaemon = true }.start()

        // Deadlines NEGATIVOS (= "ya vencido" → urgencia MÁXIMA + orden estricto): la cabeza primero (la
        // pieza 0 la más urgente), el footer detrás para no competir con el arranque.
        for (p in firstPiece..headEnd) {
            runCatching { handle.setPieceDeadline(p, HEAD_DEADLINE_BASE + (p - firstPiece) * 40) }
        }
        for (p in footerStart..lastPiece) {
            runCatching { handle.setPieceDeadline(p, FOOTER_DEADLINE_BASE + (p - footerStart) * 40) }
        }

        servedFile = File(dir, fs.filePath(fileIndex))
        val srv = TorrentStreamServer(
            handle = handle,
            info = info,
            file = servedFile!!,
            fileOffset = fs.fileOffset(fileIndex),
            fileSize = fs.fileSize(fileIndex),
        )
        srv.start()
        server = srv
        startReannounceLoop()
        return "http://127.0.0.1:${srv.port}/video"
    }

    /** Pre-buffer del PRÓXIMO archivo del pack (mismo torrent): pone su cabeza en prioridad BAJA para que
     * baje con banda sobrante SIN robarle a las TOP del archivo en curso. Best-effort. */
    fun preBufferNextFile(fileIndex: Int) {
        runCatching {
            val handle = currentHandle?.takeIf { it.isValid } ?: return
            val info = handle.torrentFile() ?: return
            if (fileIndex < 0 || fileIndex >= info.numFiles()) return
            val fs = info.files()
            val pieceLen = info.pieceLength().toLong()
            val range = computeHeadPieceRange(pieceLen, fs.fileOffset(fileIndex), fs.fileSize(fileIndex), info.numPieces(), HEADER_BYTES)
            if (range.isEmpty()) return
            // Solo SUBIR de IGNORE→LOW: nunca bajar una pieza que ya esté TOP (solape con la cola del actual).
            for (p in range) if (handle.piecePriority(p) == Priority.IGNORE) {
                handle.piecePriority(p, Priority.LOW)
            }
            Log.i("ArkivTorrent", "preBuffer próximo file=$fileIndex piezas=${range.first}..${range.last} (LOW)")
        }
    }

    private fun pollHandle(info: TorrentInfo): TorrentHandle? {
        repeat(60) {
            val h = runCatching { session.find(info.infoHash()) }.getOrNull()
            if (h != null && h.isValid) return h
            Thread.sleep(100)
        }
        return null
    }

    /** Para el stream y borra lo descargado (descartar). */
    @Synchronized
    fun stopStream() = stopStreamInternal()

    private fun stopStreamInternal() {
        awaitingMetadata = false
        readyUrl = null
        pendingHint = null
        headRange = null
        footerRange = null
        servedFile = null
        embeddedSubFiles = emptyList()
        servedFileSize = 0; servedFileOffset = 0; servedPieceLen = 0
        stopReannounceLoop()
        server?.stop(); server = null
        currentHandle?.let { h -> runCatching { session.remove(h) } }
        currentHandle = null
        currentDir?.let { d -> runCatching { d.deleteRecursively() } }
        currentDir = null
        sweepOrphans()
        releaseLocks()
    }

    /**
     * Borra los directorios de trabajo que quedaron huérfanos: cuando la app se mata mid-stream (swipe,
     * OOM) stopStreamInternal nunca corre y el .torrent parcial queda ocupando espacio. Como solo hay un
     * stream a la vez, todo lo que no sea [currentDir] es basura. Barato (normalmente 0-1 dirs).
     */
    private fun sweepOrphans() {
        val keep = currentDir
        runCatching {
            workDir.listFiles()?.forEach { d -> if (d != keep) runCatching { d.deleteRecursively() } }
        }
    }

    /** Adquiere WifiLock + WakeLock para que el SO no throttlee la descarga (pantalla apagada / cast). */
    private fun acquireLocks() {
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
        runCatching { if (!wakeLock.isHeld) wakeLock.acquire() }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    /**
     * Mientras el stream tenga POCOS peers, reanuncia agresivamente (todos los trackers ignorando el
     * min-interval + DHT + LSD) para encontrar más fuentes. Se apaga al parar el stream. Gated en el
     * umbral para no spamear a los trackers cuando ya hay peers suficientes (evita rate-limit/bans).
     */
    private fun startReannounceLoop() {
        stopReannounceLoop()
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(REANNOUNCE_INTERVAL_MS) } catch (_: InterruptedException) { break }
                val h = currentHandle ?: continue
                val peers = runCatching { h.status().numPeers() }.getOrDefault(0)
                if (peers < REANNOUNCE_PEER_THRESHOLD) {
                    announceAll(h)
                    Log.i("ArkivTorrent", "reannounce agresivo (peers=$peers)")
                }
            }
        }.apply { isDaemon = true }
        reannounceThread = t
        t.start()
    }

    private fun stopReannounceLoop() {
        reannounceThread?.interrupt()
        reannounceThread = null
    }

    /** Anuncia a todos los trackers (ignorando min-interval) + DHT + LSD, para buscar más peers. */
    private fun announceAll(h: TorrentHandle) {
        runCatching {
            h.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)
            h.forceDHTAnnounce()
            h.forceLSDAnnounce()
        }
    }

    /** Reannounce INMEDIATO (para el botón "Reintentar" del overlay cuando no aparecen peers). */
    fun retryPeers() {
        currentHandle?.let { announceAll(it) }
    }

    companion object {
        /** Tag de logs de diagnóstico del arranque/descarga (filtrar con `adb logcat -s ArkivDiag`). */
        private const val DIAG = "ArkivDiag"

        /** Rango de piezas de CABEZA de un archivo (para pre-priorizar el próximo episodio del pack). Puro. */
        fun computeHeadPieceRange(pieceLen: Long, fileOffset: Long, fileSize: Long, numPieces: Int, headBytes: Long): IntRange {
            if (pieceLen <= 0L || fileSize <= 0L || numPieces <= 0) return IntRange.EMPTY
            val firstPiece = (fileOffset / pieceLen).toInt()
            val headWin = (headBytes / pieceLen).toInt().coerceIn(1, 16)
            val fileEnd = fileOffset + fileSize - 1
            val lastPiece = (fileEnd / pieceLen).toInt().coerceAtMost(numPieces - 1)
            val headEnd = (firstPiece + headWin - 1).coerceAtMost(lastPiece)
            return firstPiece..headEnd
        }

        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "m2ts")

        /** Contenedores tipo ISO-BMFF cuyo `moov` (índice) va al final y es obligatorio para arrancar. */
        private val MP4_LIKE = setOf("mp4", "m4v", "mov")

        /** Cabeza a priorizar+gatear antes de reproducir. 16MB (≈4 piezas de 4MB) tras validar en device:
         *  con piezas grandes bajar UNA sola pieza no engancha el swarm (throughput tapado ~500KB/s con 30
         *  seeds); varias piezas en vuelo enganchan más peers en paralelo. 16MB balancea enganche/cushion
         *  sin cargar de más el gate en swarms débiles. (Elementum usa buffer de ~20MB.) */
        private const val HEADER_BYTES = 16L * 1024 * 1024

        /** Cabeza para MP4/MOV: el moov faststart va al principio y puede ocupar decenas de MB. */
        private const val HEADER_BYTES_MP4 = 16L * 1024 * 1024

        /** Ventana de read-ahead que promueve el server por delante del cabezal de lectura. */
        private const val STREAM_WINDOW_BYTES = 8L * 1024 * 1024

        /** Deadlines NEGATIVOS (= "ya vencido" → urgencia máxima) para orden estricto (estilo animeko). */
        private const val HEAD_DEADLINE_BASE = -10_000

        /** Footer AHORA igual de urgente que la cabeza (antes -6000, menos urgente → se bajaba DESPUÉS).
         *  El gate de arranque NECESITA la cola (índice), así que bajarla en PARALELO con la cabeza hace
         *  que el arranque = max(cabeza, cola) en vez de cabeza + cola. Clave en swarms débiles / cola rara. */
        private const val FOOTER_DEADLINE_BASE = -10_000

        /** Footer (índice del final: Cues de MKV / idx1 de AVI) que se prioriza para arrancar y hacer seek.
         *  Subido de 1MB→5MB: en pelis largas los Cues superan 1MB y sin ellos VLC estanca el primer seek. */
        private const val FOOTER_BYTES = 5L * 1024 * 1024

        /** Footer para MP4/MOV no-faststart: el moov (índice obligatorio) va al final y puede ser grande. */
        private const val FOOTER_BYTES_MP4 = 12L * 1024 * 1024

        /** Reannounce agresivo cada este intervalo mientras los peers estén por debajo del umbral. */
        private const val REANNOUNCE_INTERVAL_MS = 30_000L
        private const val REANNOUNCE_PEER_THRESHOLD = 4
    }
}
