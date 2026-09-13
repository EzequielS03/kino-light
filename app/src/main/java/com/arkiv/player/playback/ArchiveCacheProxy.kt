package com.arkiv.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP proxy for magis's CDN: this was originally built because VLC couldn't send
 * `Content-Auth`/`Content-License` or put them on a Range request, so the stream goes through
 * here instead, which can add them to the request to the origin. It also pre-warms the startup
 * chunk and the tail of the file (see [precalentar]) and serves seek windows (see
 * [precalentarSalto]) to paper over the CDN's variable latency (0.2-20s per range) so the player
 * never runs out of tracks or data.
 *
 * Until this branch's (light-magis) archive.org pruning, this proxy had a SECOND mode -- a
 * disk cache that downloaded once into a growing file, exclusive to archive.org -- that was
 * deleted along with the rest of that source: magis never used it (its path is `directo=true`,
 * see [serve]'s dispatcher). What's left here is exactly what magis needed.
 */
class ArchiveCacheProxy(private val cacheDir: File) {
    /**
     * Clave de caché estable por URL de origen (SHA-1), para las tablas en memoria/disco de acá
     * abajo (`calientes`, `colas`, `saltos`, [colaEnDisco], `vivasPorClave`). Antes salía de
     * `DiskLruCache.keyFor`, borrado junto con el resto de la caché en disco (era exclusiva de
     * archive.org); el hash en sí no era parte de eso y se conserva para no invalidar las colas ya
     * guardadas en disco de instalaciones existentes.
     */
    private fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    // El socket se crea en start(), NO en el constructor: este proxy es singleton y vive todo el
    // proceso, así que un socket de construcción se cerraba en el primer stop() y ya no había forma
    // de reabrirlo (un ServerSocket cerrado no se reabre). Como el botón de parar de la barra llama
    // a stop(), eso dejaba archive cargando para siempre hasta matar la app.
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1
    @Volatile private var running = false

    /**
     * Cuántas conexiones al origen hay vivas por archivo. Solo para diagnóstico: ya NO se cierra
     * ninguna a la fuerza (ver la nota en [abrirEnOrigen]), pero si este número crece sin volver a
     * bajar hay conexiones que quedaron colgadas y se ve acá antes de que se note reproduciendo.
     */
    private val vivasPorClave = ConcurrentHashMap<String, Int>()

    private fun soltarViva(clave: String) {
        vivasPorClave.computeIfPresent(clave) { _, n -> if (n <= 1) null else n - 1 }
    }

    /**
     * Qué rango pidió cada conexión abierta AHORA, y desde cuándo. Es puro diagnóstico y existe por
     * una pregunta concreta: cuando el origen rechaza, ¿es porque le estamos pidiendo varias cosas
     * a la vez? Contar conexiones no alcanza para responderla — hace falta ver QUÉ se está pidiendo
     * en paralelo y desde hace cuánto, que es lo que distingue "el CDN nos limita por concurrencia"
     * de "esta petición concreta salió mal".
     */
    private val rangosEnVuelo = ConcurrentHashMap<String, Long>()

    /** Los rangos abiertos ahora mismo, con su antigüedad, para meterlos en una línea de log. */
    private fun fotoDeRangosEnVuelo(): String {
        val ahora = System.currentTimeMillis()
        if (rangosEnVuelo.isEmpty()) return "ninguno"
        return rangosEnVuelo.entries
            .sortedBy { it.value }
            .joinToString(" | ") { (r, t) -> "$r hace ${ahora - t}ms" }
    }

    /**
     * Las conexiones al origen abiertas AHORA, para poder abandonarlas cuando la red cambia debajo.
     * A diferencia de [vivasPorClave] —que solo cuenta, para diagnóstico— esto guarda con qué
     * cerrarlas. Ver [abandonarConexiones] y [CambioDeRed].
     */
    private val conexionesVivas = ConexionesVivas()

    /**
     * Cierra todas las conexiones al origen que haya abiertas. Lo llama el vigilante de red cuando
     * el aparato cambia de red: esos sockets quedaron atados a una interfaz que ya no existe y sin
     * esto la lectura se queda esperando hasta el plazo del CUERPO de [PoliticaOrigen] —90 s en
     * archive, 30 s en magis— ANTES de que empiece siquiera el primer reintento.
     *
     * No hace falta avisarle a nadie más: cerrar el socket hace que la lectura falle en el acto, y
     * de ahí en adelante se encarga la política de reintentos de siempre, que ya sabe abrir de nuevo
     * — esta vez por la red nueva.
     */
    fun abandonarConexiones(motivo: String) {
        val cerradas = conexionesVivas.cerrarTodas()
        if (cerradas > 0) {
            android.util.Log.w("ArchiveCacheProxy", "$motivo → abandoning $cerradas connection(s) to the origin")
        }
    }
    // Tamaño real de cada origen, para poder ventanear. Ver totalDelOrigen.
    private val totales = ConcurrentHashMap<String, Long>()

    // Duración de cada origen, para el playlist de cast. Ver duracionDelOrigen.
    private val duraciones = ConcurrentHashMap<String, Long>()

    // Tabla de segmentos de cada origen, para el playlist de cast. Ver segmentosDe.
    private val segmentos = ConcurrentHashMap<String, List<TsSegmenter.Segment>>()

    // Dónde empieza DE VERDAD cada segmento (el IDR más cercano a la frontera estimada), por
    // origen y por índice. Se aprende al servir, ver serveSegment.
    private val idrPorOrigen = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    /** Cuánto se mira más allá de la frontera estimada buscando el keyframe real. Un GOP típico
     *  son 2-5 s, que a los bitrates de magis es medio mega largo. */
    private val MARGEN_GOP = 1_500_000L

    /** Cuántas ventanas de [MARGEN_GOP] se recorren buscando un IDR tras un salto. */
    private val VENTANAS_IDR = 6

    /**
     * Segment length aimed for in the cast playlist, in seconds.
     *
     * Thirty. Ten was too fine once each segment carries [MARGEN_GOP] of overlap while the real
     * keyframe is found -- 1.5 MB of slack on a 1.35 MB segment is more overlap than segment. At
     * thirty the slack is a third of the fetch, and sixty was MEASURED to be worse (2026-09-12). Longer segments were tried as a way
     * to reduce how often a mid-GOP boundary can stall the receiver -- ~120 boundaries instead of
     * ~700 -- and the cure cost more than the disease: at 60 s a segment is 8.1 MB, the proxy
     * holds all of it before sending a byte, and the receiver fetches segment 0 in full even when
     * resuming at minute 62. Startup went from ~11 s to ~50 s and the retry loop merely got more
     * expensive per attempt.
     *
     * The stalls were never about how MANY boundaries there are; they are about WHERE each one
     * lands. See [TsSegmenter.segmentByBitrate].
     */
    private val SEGMENTO_OBJETIVO_SEG = 30.0

    /**
     * Último código HTTP que dio cada origen. Existe porque el reproductor NO puede distinguir por
     * qué falló: pase lo que pase acá, el player ve un 502 del proxy. Y la diferencia importa — un 404
     * significa "archive renombró el archivo" y se puede arreglar solo (ver CoincidenciaDeArchivo),
     * mientras que un 503 o un timeout solo se pueden reintentar. Guardarlo acá es la forma más
     * barata de que esa distinción sobreviva hasta quien sabe qué hacer con ella.
     */
    private val ultimosCodigos = ConcurrentHashMap<String, Int>()

    /** Qué contestó [originUrl] la última vez, o null si nunca se le pidió nada. */
    fun ultimoCodigoDe(originUrl: String): Int? = ultimosCodigos[originUrl]

    private fun anotarCodigo(origin: String, code: Int) {
        ultimosCodigos[origin] = code
    }

    /**
     * Arranque BAJÁNDOSE en memoria, por clave de caché. Ver [precalentar].
     *
     * Antes acá había un `ByteArray` ya completo, y por eso [precalentar] tenía que esperar los 2 MB
     * enteros antes de dejar abrir el video: medido en el Fire TV, 0,5 a 5 s de spinner en cada
     * reproducción. Ahora es un [BufferQueCrece] y se lee mientras se llena — el player abre apenas
     * hay algo y no se queda sin datos porque el buffer sigue creciendo detrás.
     *
     * Solo lo usa magis: nadie más llama a `precalentar`, así que para el resto de las fuentes este
     * mapa está siempre vacío y el camino es exactamente el de siempre.
     */
    private val calientes = ConcurrentHashMap<String, BufferQueCrece>()

    /**
     * Cuánto del arranque tiene que haber llegado antes de dejar abrir el video.
     *
     * No es "cuánto se precalienta" (eso sigue siendo [ARRANQUE_CALIENTE]): es solo cuánto se
     * ESPERA. Alcanza con que haya empezado a fluir — lo que hundía a libVLC era que su primera
     * lectura se quedara colgada, no el tamaño del colchón.
     */
    private val ARRANQUE_MINIMO = 64 * 1024

    /**
     * Tope de espera para que el arranque empiece a fluir. Si en este tiempo no llegó ni el primer
     * bloque, el origen está muerto y se reproduce sin garantía (mejor eso que un spinner eterno).
     */
    private val ESPERA_ARRANQUE_MS = 8_000L

    /**
     * Tope de espera de la COLA, y solo cuando de ella tiene que salir la duración.
     *
     * Corto a propósito: la duración es una mejora de la barra, nunca un motivo para no reproducir.
     * El camino bueno es que la mande el gateway (ya lo hace para películas y, desde 2026-08-11,
     * también para capítulos de serie); esto es el respaldo del respaldo.
     */
    private val ESPERA_COLA_MS = 2_000L

    /**
     * El FINAL de cada archivo, por clave de caché: (byte absoluto donde arranca, bytes). Lo llena
     * [precalentar] y lo consume [ColaCaliente], que es donde está el porqué.
     *
     * A diferencia de [calientes], esta NO se consume al usarla: libVLC sondeaba el final VARIAS
     * veces seguidas y con offsets distintos, y todas esas son las que hay que contestar sin red.
     */
    private val colas = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

    /**
     * La misma cola, pero en disco, para que sobreviva al reinicio de la app.
     *
     * Sin esto [colas] se vaciaba en cada arranque y el sondeo de EOF de libVLC volvía a pagar la
     * red — medido en el Fire TV el 2026-08-14: 6205 ms para traer 256 KB con dos rechazos del CDN,
     * y 5376 ms hasta la primera imagen. En un Fire TV, que mata la app apenas se va al fondo, esa
     * "primera vez" es casi siempre. Ver [ColaEnDisco].
     */
    private val colaEnDisco = ColaEnDisco(File(cacheDir, "colas"))

    /**
     * Colas que TODAVÍA se están bajando, por clave de caché.
     *
     * Existe porque desde que el arranque dejó de esperar a la cola, el precalentado de la cola y el
     * sondeo de EOF de libVLC dejaron de ir en fila y pasaron a ir a la vez: dos conexiones pidiendo
     * LOS MISMOS bytes del final. Medido en el Fire TV el 2026-08-13, con el CDN en una mala racha,
     * se rechazaron una a la otra durante 7 s —`origen rechazó bytes=-262144`, `origen rechazó
     * bytes=632603872-`, dos intentos cada una— y recién al tercero contestó, en 167 ms. VLC tardó
     * 7719 ms en abrir esperando su propia cola.
     *
     * Con esto, quien llega segundo espera a la que ya está en vuelo en vez de abrir una conexión
     * que compite. Es la misma idea de [BufferQueCrece] para la cabeza: una sola descarga, varios
     * lectores.
     */
    private val colasEnVuelo = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /**
     * Cuánto se le espera a una cola en vuelo antes de ir al origen igual.
     *
     * Generoso a propósito: acá esperar NO es tiempo perdido —la descarga que se espera es la que va
     * a contestar— y el plazo solo existe para que un precalentado que murió sin avisar no deje al
     * reproductor colgado. Pasado el plazo se pide al origen, que es lo que se hacía siempre.
     */
    private val ESPERA_COLA_EN_VUELO_MS = 10_000L

    /**
     * Un tramo del archivo alrededor de un punto de SALTO, guardado en memoria.
     *
     * El porqué, medido en el Fire TV el 2026-08-13 reanudando una película en 13:26: libVLC no
     * saltaba de una: **bisectaba**. Pidió diez rangos seguidos —`bytes=62148288-`, `63899508-`, `63533848-`,
     * `63443420-`…— leyendo unos cientos de KB de cada uno y cortando la conexión enseguida. Cada uno
     * abría su propia conexión al CDN a ~300 ms. Y los diez caían adentro de **1,8 MB** del archivo.
     *
     * Con esto, el primero de esos rangos deja una ventana en memoria y los otros nueve se contestan
     * sin tocar la red. Es lo mismo que hace la app original por otro camino: su reproductor nunca le
     * pide bytes al CDN, le avisa al motor de descarga a qué punto va (`Seek {moment, offset}`, ver
     * `yc/C6280e.java` en la decompilada) y el motor prepara la zona.
     *
     * La ventana NO se baja por adelantado, y esa es la parte importante: se llena con la conexión
     * que el reproductor ya abrió, **siguiendo después de que él corta**. Así la reproducción
     * secuencial —que nunca corta— no paga nada, ni una conexión ni un byte de más.
     */
    private class VentanaDeSalto(val inicio: Long, val buffer: BufferQueCrece) {
        /** Si [pedido] cae dentro de lo que YA hay guardado. */
        fun cubre(pedido: Long): Boolean =
            pedido >= inicio && pedido < inicio + buffer.disponible
    }

    private val saltos = ConcurrentHashMap<String, MutableList<VentanaDeSalto>>()

    /**
     * Cuánto se guarda alrededor de un salto. 4 MB cubre con margen los 1,8 MB que abarcó la
     * bisección medida, y es plata: son 4 MB de RAM en un Fire Stick.
     */
    private val VENTANA_SALTO = 4 * 1024 * 1024

    /** Cuántas ventanas por archivo. Dos: la del salto de ahora y la del anterior, nada más. */
    private val VENTANAS_POR_ARCHIVO = 2

    /**
     * Cuánto ANTES del byte estimado arranca la ventana del salto precalentado, y cuánto abarca.
     *
     * Salen de medir, no de elegir un número redondo: en dos reanudaciones reales el desvío entre el
     * byte que estima la tasa constante y los que el reproductor terminó pidiendo fue de **-2,7 MB a
     * +3,7 MB**. Arrancar 4 MB antes y cubrir 8 abarca ese rango entero con algo de aire.
     */
    private val MARGEN_SALTO = 4L * 1024 * 1024
    private val VENTANA_SALTO_PRECALENTADA = 8 * 1024 * 1024

    /**
     * Cuánto se le da a la primera conexión antes de pedir la cola por una segunda en paralelo.
     *
     * 1,2 s: más que el caso bueno del CDN (0,2-0,8 s medidos) y bastante menos que el plazo de
     * 3 s con el que se da por muerta. Ahí es donde este duplicado gana: cuando la primera va camino
     * a no contestar, no hay que esperar a que se rinda para volver a tirar los dados.
     */
    private val DUPLICAR_TRAS_MS = 1_200L

    /** Cuántas conexiones como mucho para la cola. Dos: el duplicado, no una ráfaga. */
    private val TIROS_A_LA_COLA = 2

    /**
     * Cuánto espera la cola a saber el tamaño del archivo antes de rendirse y pedir por sufijo.
     *
     * Corto porque el dato viene de la respuesta de la CABEZA, que se está bajando en paralelo y
     * cuyo primer byte es justo lo que el arranque ya estaba esperando: si a los 2 s no llegó, el
     * problema es el CDN y no este plazo.
     */
    private val ESPERA_TOTAL_MS = 2_000L

    /** Cuánto del final se guarda. Igual que la sonda de duración: 256 KB alcanzan y sobran. */
    private val COLA_CALIENTE = TsDurationProbe.PROBE_BYTES

    /**
     * El que le corta la conexión al origen que no contesta a tiempo. Ver [codigoConFechaLimite].
     *
     * Un solo hilo alcanza: solo programa `disconnect()`, que no bloquea. Daemon para que no impida
     * que el proceso muera.
     */
    private val verdugo = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "arkiv-origen-verdugo").apply { isDaemon = true }
    }

    /**
     * Cuánto se precalienta del arranque. Es el número que hay que mover si esto se vuelve lento, y
     * también el primero que hay que revisar si vuelve el negro-y-mudo.
     *
     * Empezó en 2 MB, elegido con holgura para que el player identifique programas y pistas sin
     * depender de la latencia del CDN. Medido el 2026-08-11 en el Fire TV sobre ocho arranques, esa
     * holgura pasó a ser LA fase dominante: bajar la cabeza costaba entre 917 y 9210 ms, contra
     * 246-511 ms de la cola de 256 KB en las mismas corridas — o sea que manda el tamaño.
     *
     * **Bajarlo a 512 KB ya se probó, el 2026-08-11 en el Fire TV, y NO conviene.** El razonamiento
     * era bueno —estos TS van a ~152 KB/s reales, así que 2 MB son ~13 s de video precargados solo
     * para identificar pistas— pero lo que se ahorra de un lado se paga del otro:
     *
     * | | 2 MB (9 arranques) | 512 KB (6 arranques) |
     * |---|---|---|
     * | bajar la cabeza (corridas buenas) | 917-1828 ms | 515-911 ms |
     * | VLC → primera imagen (mediana) | 795 ms | 823 ms |
     * | heartbeats con `pistas=v0/a0` | 1 de 9 | 2 de 6 |
     *
     * Y el detalle que lo decide: los DOS arranques con `v0/a0` fueron justo los dos de peor
     * apertura (1782 ms y 2018 ms, contra 513-1027 ms del resto). Con menos datos calientes libVLC
     * no terminaba de identificar el stream con lo que tenía en memoria y salía a la red en mitad
     * del arranque, que es precisamente lo que este precalentado existe para evitar. No llegó a fallar
     * —cero rescates, las dos se recuperaron— pero el final de ese camino es el negro-y-mudo
     * documentado en [precalentar], y el ahorro no lo justifica.
     *
     * Lo que sí domina cuando esto se pone lento no es el tamaño: en las corridas malas la cabeza
     * de 2 MB y la cola de 256 KB terminan en el MISMO milisegundo (5205/5212, 5264/5266), o sea
     * que el cuello está en el enlace o en el CDN, y ningún recorte de payload lo arregla.
     */
    private val ARRANQUE_CALIENTE = 2 * 1024 * 1024

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
     * Local URL the player can play. [headers] travels encoded in the URL itself because that was
     * the only thing VLC used to let through: it only understood `:http-referrer` and
     * `:http-user-agent`, and magis serves the VOD behind `Content-Auth` and `Content-License`.
     * The proxy puts them on the request to the origin.
     *
     * `h` goes BEFORE `u` on purpose: there's code that pulls out the origin with
     * `substringAfter("u=")`.
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

        // `conVentanaDesde(proxyUrl, desde)` vivía acá: armaba una URL con `w=$desde&` para que la
        // descarga a caché empezara lejos del byte 0 al reanudar. Se borró junto con el resto de la
        // caché en disco (exclusiva de archive.org, ver [VentanaDeDescarga] en el historial) — el
        // dispatcher de [serve] ya no lee `w=`, así que dejar la URL armaría un parámetro sin efecto.

        /** Loopback authority that [proxyUrl] writes, and the only thing [lanUrl] replaces. */
        private const val LOOPBACK = "http://127.0.0.1:"

        /**
         * The same proxy URL, spelled so the Chromecast can reach it: the phone's LAN [ip] instead
         * of loopback. `null` if [proxyUrl] isn't one of ours or there's no LAN ip yet.
         *
         * This is the ONLY URL a Cast receiver can be given for Magis. Its VOD sits behind
         * `Content-Auth`/`Content-License`, and the Default Media Receiver cannot send custom
         * headers (that needs a custom receiver app), so the CDN answers it 401. The proxy is what
         * puts those headers on the request to the origin, so the receiver has to come through it.
         *
         * No socket is widened here, and none needs to be: [start] opens `ServerSocket(0)` with no
         * bind address, which already listens on every interface — the same convention
         * `LiveHlsProxy` documents in `urlPara` and `LocalFileServer` relies on. Loopback keeps
         * working for the local player exactly as before.
         *
         * It rewrites an EXISTING url instead of rebuilding one from `originUrl`+`headers` so the
         * two spellings cannot drift: same port, same path, same query, byte for byte. That matters
         * more than it looks — `h=` is a [HeaderCodec] blob and `u=` is percent-encoded, so
         * re-encoding either would corrupt the headers the proxy exists to inject. It also rules
         * out a blind string replace: an origin that itself mentions 127.0.0.1 lives inside `u=`
         * and must survive untouched.
         *
         * ⚠️ The auth headers ride in the query, so this url hands them to anyone on the LAN. That
         * is the same exposure `LiveHlsProxy` covers with a random token, and the reason this is
         * built only at cast time rather than alongside the local url.
         */
        fun lanUrl(proxyUrl: String, ip: String): String? {
            if (ip.isBlank() || !proxyUrl.startsWith(LOOPBACK)) return null
            // Everything after "http://127.0.0.1:" is "<port>/<path>?<query>"; the first '/' ends
            // the authority, and the query is never touched.
            val tras = proxyUrl.substring(LOOPBACK.length)
            val corte = tras.indexOf('/')
            if (corte <= 0) return null
            val puerto = tras.substring(0, corte)
            if (puerto.any { !it.isDigit() }) return null
            return "http://$ip:$puerto${tras.substring(corte)}"
        }

        /**
         * The LAN url of the HLS PLAYLIST for the same stream -- what the Cast receiver is given.
         *
         * Same host, port and query as [lanUrl]; only the path changes from `/s` to `/hls.m3u8`.
         * The receiver refuses a bare transport stream served progressively (its own log:
         * `FFmpegDemuxer: open context failed`) but plays those identical bytes as a playlist of
         * byte ranges, which `/hls.m3u8` describes and `/s` still serves.
         */
        fun lanPlaylistUrl(proxyUrl: String, ip: String): String? =
            lanUrl(proxyUrl, ip)?.let { lan ->
                val i = lan.indexOf("/s?")
                if (i < 0) null else lan.substring(0, i) + "/hls.m3u8?" + lan.substring(i + 3)
            }
    }

    /**
     * Fracción [0..1] "buffereada" para la barra de progreso. SIEMPRE 0f: medía el avance de la
     * caché en disco de descarga única a archivo que crece, exclusiva de archive.org y borrada en
     * la poda de esta rama. El camino de magis (`directo=true`) nunca pasó por esa caché —no tenía
     * de dónde sacar esta fracción— así que para el único llamador que queda esto no cambia nada:
     * ya devolvía 0f siempre. Se deja la función (no el cálculo) para no tocar el call site de
     * PlayerScreen.
     */
    fun bufferedFraction(proxyUrl: String): Float = 0f

    private fun serve(socket: Socket) {
        // socket.use{} cierra el socket al salir; runCatching traga excepciones de red/IO (p.ej. el
        // player cierra el socket al hacer seek → escribir tira broken pipe) para no matar el thread.
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
                // Modo de servicio. Lo elige quien arma la URL (magis → directo). Es el ÚNICO camino
                // que queda: la caché en disco de archive.org (rutas `w=`/sin `d=1`) se borró en la
                // poda de esta rama junto con el resto de esa fuente.
                val directo = path.contains("d=1")
                // Ventana: servir desde esta fracción del archivo como si fuera el archivo entero,
                // para poder REANUDAR sin que el reproductor tenga que saltar. Ver VentanaDeArchivo.
                val fraccion = path.substringAfter("f=", "").substringBefore('&')
                    .toFloatOrNull()?.takeIf { it > 0f } ?: 0f
                val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }
                    ?.substringAfter(':')?.trim()

                val key = keyFor(origin)
                val out = s.getOutputStream()

                // Toda petición que entra queda registrada: el proxy es la frontera entre "el
                // reproductor no pide" y "el proxy no entrega", que desde afuera se ven igual (el
                // player buffereando al 0% para siempre).
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "← requests range=${rangeHeader ?: "(all)"} direct=$directo window=$fraccion path=${path.substringBefore('?')}",
                )

                // HLS playlist over the SAME stream, for the Cast receiver. It refuses a bare
                // transport stream served progressively -- read off the receiver's own log
                // (2026-09-12): `{"error":"FFmpegDemuxer: open context failed"}`, so it never
                // reaches a decoder -- but it plays the identical bytes offered as a playlist of
                // byte ranges. Nothing is converted; `/s` still serves the media, one range at a
                // time, exactly as it does for the phone.
                if (path.startsWith("/hls.m3u8")) {
                    servePlaylist(path, origin, extraHeaders, out)
                    return@runCatching
                }

                // One segment of that playlist, addressed by index. The index is resolved to a byte
                // range HERE rather than by the receiver, because the receiver does not implement
                // `EXT-X-BYTERANGE` -- see the KDoc of [TsSegmenter.playlist] for the measurement.
                // From its side this is an ordinary resource it GETs whole.
                if (path.startsWith("/seg")) {
                    val n = path.substringAfter("n=", "").substringBefore('&').toIntOrNull()
                    if (n == null) {
                        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        serveSegment(n, origin, extraHeaders, out)
                    }
                    return@runCatching
                }

                // Camino DIRECTO (magis): cada Range va tal cual al origen y su cuerpo se devuelve
                // sin tocar el disco.
                //
                // `d=1` es también de dónde sale el PERFIL de aguante: hoy este camino lo usa
                // solo magis (`proxyUrl(directo = true)` no tiene otro llamador), y magis y
                // archive fallan de formas opuestas — ver PoliticaOrigen.Perfil. Si algún día
                // otra fuente pide `d=1`, el perfil tiene que viajar en la URL, no deducirse.
                if (directo) {
                    if (!passthrough(
                            origin, rangeHeader, out, extraHeaders,
                            claveUnica = key, fraccion = fraccion,
                            perfil = PoliticaOrigen.Perfil.MAGIS,
                        )
                    ) {
                        android.util.Log.w("ArchiveCacheProxy", "direct: the origin didn't serve the range")
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    return@runCatching
                }

                // Sin `d=1` no hay caché a la que caer (era exclusiva de archive.org, borrada en
                // esta poda): último recurso, el mismo passthrough simple de siempre.
                if (!passthrough(origin, rangeHeader, out, extraHeaders)) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                }
            }.onFailure { e ->
                runCatching { android.util.Log.w("ArchiveCacheProxy", "serve() failed: ${e.message}") }
            }
        }
    }

    /**
     * Answers `/hls.m3u8` with a playlist of byte ranges over the same origin.
     *
     * Two facts are needed and both are cheap: the total size (one 1-byte ranged request, already
     * remembered per origin by [totalDelOrigen]) and the duration (two 256 KB ends, which
     * [TsDurationProbe] already knows how to read and which the app pays for anyway to draw the
     * progress bar). Both are cached, so a receiver that re-fetches the playlist -- it does, several
     * times -- costs nothing after the first.
     *
     * Segments are prorated, NOT cut on PCR, and that is deliberate: cutting on PCR needs a read
     * per boundary, and against this CDN a read costs 0.2 s to 20 s. A two-hour title is ~720
     * boundaries, so the exact version would take hours to answer one playlist request. The price
     * is that seeking lands approximately on a variable-bitrate title. See
     * [TsSegmenter.segmentByBitrate]; a LOCAL file, where reads are free, uses the exact path.
     */
    private fun servePlaylist(
        path: String,
        origin: String,
        headers: Map<String, String>,
        out: java.io.OutputStream,
    ) {
        val segments = segmentosDe(origin, headers)
        // Relative URIs: same host, same port, same query -- only the path and the added `n=`
        // differ. Relative keeps the LAN ip out of the playlist, so whatever URL the receiver used
        // to fetch it is the one it keeps using.
        val query = path.substringAfter('?', "")
        val body = TsSegmenter.playlist(segments) { i ->
            "/seg?n=$i" + if (query.isEmpty()) "" else "&$query"
        }
        if (body.isEmpty()) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "playlist: can't build it (no size or no duration) → 502, the receiver will show an error",
            )
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val bytes = body.toByteArray()
        android.util.Log.w(
            "ArchiveCacheProxy",
            "playlist → ${segments.size} segments, one URI each (${bytes.size}B)",
        )
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    /**
     * The segment table for an origin, remembered. The receiver asks for the playlist more than
     * once and then for ~700 segments, and every one of those would otherwise re-probe the size
     * and the duration -- two CDN round trips each, against an origin that answers between 0.2 s
     * and 20 s.
     */
    private fun segmentosDe(origin: String, headers: Map<String, String>): List<TsSegmenter.Segment> {
        segmentos[origin]?.let { return it }
        val total = totalDelOrigen(origin, headers, PoliticaOrigen.Perfil.MAGIS)
        val durMs = duracionDelOrigen(origin, headers)
        val out = TsSegmenter.segmentByBitrate(total, durMs / 1000.0, SEGMENTO_OBJETIVO_SEG)
        if (out.isNotEmpty()) {
            segmentos[origin] = out
            android.util.Log.w(
                "ArchiveCacheProxy",
                "segment table: ${out.size} of ~${SEGMENTO_OBJETIVO_SEG}s over $total bytes / ${durMs}ms",
            )
        }
        return out
    }

    /**
     * Serves segment [n] as a resource of its own: a plain 200 with its real `Content-Length`,
     * which is all the receiver knows how to consume (it ignores `EXT-X-BYTERANGE`). The range
     * itself is fetched from the origin exactly as any other ranged read.
     */
    /**
     * Serves segment [n], starting at a REAL random access point.
     *
     * This is where the "it plays in sections" bug was fixed. The playlist's boundaries are
     * prorated arithmetic, so they land mid-GOP, and a hardware decoder drops every frame until it
     * sees an IDR -- measured on the KALLEY's own decoder, which emitted 2 pictures out of every
     * 17 (`OMX_VDEC ooo … diff=333666`, one frame each 333 ms of a 23.976 fps title, so ~3 fps).
     * Chromium will not fix that and the Cast receiver is Chromium, so the segment has to arrive
     * already aligned.
     *
     * Aligning up front was priced and rejected: finding a keyframe means reading forward up to a
     * whole GOP, and at ~700 boundaries that is ~280 MB before a single playlist could be answered.
     * Instead each boundary is discovered WHILE the segment around it is served -- bytes this proxy
     * has to move anyway -- and remembered. Sequential playback therefore pays nothing beyond
     * [MARGEN_GOP] of overlap per segment; only a seek into a segment never visited costs a probe.
     */
    private fun serveSegment(
        n: Int,
        origin: String,
        headers: Map<String, String>,
        out: java.io.OutputStream,
    ) {
        val segments = segmentosDe(origin, headers)
        val seg = segments.getOrNull(n)
        if (seg == null) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n out of range (there are ${segments.size})")
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val total = totalDelOrigen(origin, headers, PoliticaOrigen.Perfil.MAGIS)
        val inicios = idrPorOrigen.getOrPut(origin) { ConcurrentHashMap() }
        // Segment 0 starts at byte 0: the file's own first packet is a random access point.
        if (n == 0) inicios.putIfAbsent(0, 0L)

        val inicio = inicios[n] ?: buscarIdr(origin, headers, seg.start, total)
        if (inicio == null) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n: no random access point near ${seg.start}")
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        inicios.putIfAbsent(n, inicio)

        // Where the NEXT segment should begin, by the playlist's arithmetic, plus a GOP of slack to
        // look for the real keyframe. The last segment simply runs to EOF.
        val esUltimo = n >= segments.size - 1
        val finEstimado = if (esUltimo) total else segments[n + 1].start
        val hasta = if (esUltimo) total else minOf(total, finEstimado + MARGEN_GOP)
        val cuerpo = rangoCrudo(origin, headers, "bytes=$inicio-${hasta - 1}")
        if (cuerpo == null || cuerpo.isEmpty()) {
            android.util.Log.w("ArchiveCacheProxy", "segment $n ($inicio-${hasta - 1}): the origin didn't serve it")
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        // Cut at the first random access point at or after the estimated boundary, and remember it
        // as where segment n+1 begins -- learned for free from bytes already in hand.
        var largo = cuerpo.size
        if (!esUltimo) {
            val desde = (finEstimado - inicio).coerceIn(0L, cuerpo.size.toLong()).toInt()
            val corte = primerIdrEn(cuerpo, desde)
            if (corte != null) {
                largo = corte
                inicios.putIfAbsent(n + 1, inicio + corte)
            } else {
                // No keyframe within the margin: serve up to the estimate and let the next segment
                // probe for itself. Rare, and better than a segment that runs long.
                largo = desde.coerceAtLeast(1)
            }
        }

        android.util.Log.w(
            "ArchiveCacheProxy",
            "segment $n → ${largo}B from $inicio (estimate was ${seg.start}, " +
                "next starts ${inicios[n + 1] ?: -1})",
        )
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Content-Length: $largo\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.write(cuerpo, 0, largo)
        out.flush()
    }

    /** First random access point at or after [desde] inside [buf], or null. */
    private fun primerIdrEn(buf: ByteArray, desde: Int): Int? {
        val base = MpegTs.alignment(buf)
        if (base < 0) return null
        // Walk packets from the first one at or after `desde`.
        var i = base + ((desde - base).coerceAtLeast(0) + MpegTs.PACKET - 1) / MpegTs.PACKET * MpegTs.PACKET
        while (i + MpegTs.PACKET <= buf.size) {
            if (MpegTs.isRandomAccess(buf, i)) return i
            i += MpegTs.PACKET
        }
        return null
    }

    /** Probes the origin for the first random access point at or after [desde]. Used only when a
     *  segment is reached without having served the one before it -- that is, after a seek. */
    private fun buscarIdr(origin: String, headers: Map<String, String>, desde: Long, total: Long): Long? {
        var at = desde.coerceIn(0L, total)
        repeat(VENTANAS_IDR) {
            val fin = minOf(total, at + MARGEN_GOP)
            if (fin <= at) return null
            val bloque = rangoCrudo(origin, headers, "bytes=$at-${fin - 1}") ?: return null
            primerIdrEn(bloque, 0)?.let { return at + it }
            at = fin
        }
        android.util.Log.w("ArchiveCacheProxy", "no random access point within ${VENTANAS_IDR} windows of $desde")
        return null
    }

    /**
     * The instant of a real keyframe at or near [objetivoMs], in ms from the start of the title.
     *
     * Clipping a remux anywhere else desynchronises the tracks: video can only begin at a keyframe
     * so the muxer moves it back to one, while audio begins at the instant asked for, and the two
     * no longer line up. Measured in the bytes of a clipped remux -- the first fragment carried
     * 8.363 s of audio against 6.792 s of video, 1.57 s of audio with no picture to go with it,
     * and it never recovered because the fragments carry no `tfdt` to re-anchor them. Starting
     * from zero was fine precisely because nothing was clipped.
     *
     * Asking to cut where a keyframe already is removes the mismatch at the source.
     *
     * Returns [objetivoMs] unchanged when the stream cannot be probed -- a slightly misaligned
     * start is better than refusing to cast.
     */
    fun msDeKeyframeCercaDe(origin: String, headers: Map<String, String>, objetivoMs: Long): Long {
        if (objetivoMs <= 0L) return 0L
        val total = totalDelOrigen(origin, headers, PoliticaOrigen.Perfil.MAGIS)
        val durMs = duracionDelOrigen(origin, headers)
        if (total <= 0L || durMs <= 0L) {
            android.util.Log.w("ArchiveCacheProxy", "keyframe search: no size or duration, using ${objetivoMs}ms as asked")
            return objetivoMs
        }
        // The clock the whole title is measured against.
        val cabeza = rangoCrudo(origin, headers, "bytes=0-${MARGEN_GOP - 1}") ?: return objetivoMs
        val pcrInicial = MpegTs.firstPcr(cabeza)?.pcr?.base90k ?: return objetivoMs

        // Aim a GOP early so the keyframe found is at or before the point asked for: starting a
        // moment early is harmless, starting late skips content.
        val byteObjetivo = (total * (objetivoMs.toDouble() / durMs)).toLong()
            .minus(MARGEN_GOP)
            .coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val idr = buscarIdr(origin, headers, byteObjetivo, total) ?: return objetivoMs

        // Its PCR is the answer: the instant that keyframe sits at.
        val bloque = rangoCrudo(origin, headers, "bytes=$idr-${minOf(total, idr + MpegTs.PACKET * 400L) - 1}")
            ?: return objetivoMs
        val pcr = MpegTs.firstPcr(bloque)?.pcr?.base90k ?: return objetivoMs
        val ms = MpegTs.deltaTicks(pcrInicial, pcr) * 1000 / MpegTs.PCR_HZ
        android.util.Log.w(
            "ArchiveCacheProxy",
            "keyframe for ${objetivoMs}ms is at ${ms}ms (byte $idr) → clipping there so the tracks line up",
        )
        return ms.coerceAtLeast(0L)
    }

    /** Duration of an origin, remembered: the receiver asks for the playlist more than once. */
    private fun duracionDelOrigen(origin: String, headers: Map<String, String>): Long {
        duraciones[origin]?.let { return it }
        val cabeza = rangoCrudo(origin, headers, "bytes=0-${TsDurationProbe.PROBE_BYTES - 1}")
        val cola = rangoCrudo(origin, headers, "bytes=-${TsDurationProbe.PROBE_BYTES}")
        if (cabeza == null || cola == null) return 0L
        val ms = TsDurationProbe.durationMs(cabeza, cola)
        if (ms > 0L) duraciones[origin] = ms
        return ms
    }

    /** One ranged read straight from the origin, for the playlist's own bookkeeping. */
    private fun rangoCrudo(origin: String, headers: Map<String, String>, range: String): ByteArray? =
        runCatching {
            val conn = (URL(origin).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", range)
                setRequestProperty("Connection", "close")
                connectTimeout = PoliticaOrigen.Perfil.MAGIS_SONDA.conectarMs
                readTimeout = PoliticaOrigen.respuestaMs(0, PoliticaOrigen.Perfil.MAGIS_SONDA)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect(); null
            } else {
                conn.inputStream.use { it.readBytes() }.also { runCatching { conn.disconnect() } }
            }
        }.getOrNull()

    /**
     * Tamaño real de un origen, leído del `Content-Range` de una petición de 1 byte.
     *
     * Hace falta para poder ventanear: la fracción que pide el reproductor solo se puede convertir
     * a byte sabiendo el total. Se recuerda por origen porque cada salto vuelve a abrir el stream
     * con otra fracción y este viaje al CDN, aunque sea de un byte, también paga su latencia.
     */
    private fun totalDelOrigen(
        origin: String,
        extraHeaders: Map<String, String>,
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.ARCHIVE,
    ): Long {
        totales[origin]?.let { return it }
        repeat(PoliticaOrigen.intentos(perfil)) { intento ->
            val total = runCatching {
                val conn = (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=0-0")
                    if (!perfil.reusaSockets) setRequestProperty("Connection", "close")
                    // Antes 8 s fijos. Es un solo byte, pero lo que se paga acá es la latencia del
                    // nodo, no el tamaño: contra los 72 s medidos, 8 s no alcanzaban nunca.
                    connectTimeout = perfil.conectarMs
                    readTimeout = PoliticaOrigen.respuestaMs(intento, perfil)
                }
                val cr = conn.getHeaderField("Content-Range")
                runCatching { conn.inputStream.use { it.readBytes() } }
                runCatching { conn.disconnect() }
                VentanaDeArchivo.totalDelContentRange(cr)
            }.getOrDefault(0L)
            if (total > 0) { totales[origin] = total; return total }
            if (intento < PoliticaOrigen.intentos(perfil) - 1) {
                Thread.sleep(PoliticaOrigen.esperaMs(intento, perfil))
            }
        }
        android.util.Log.w("ArchiveCacheProxy", "window: couldn't find out the origin's size")
        return 0L
    }

    /**
     * `conn.responseCode` con fecha límite PROPIA, distinta de la de leer el cuerpo.
     *
     * `HttpURLConnection` tiene un solo `readTimeout` y rige las dos cosas, y por eso no alcanzaba
     * con bajar el número: esperar la respuesta y aguantar un hueco a mitad del cuerpo necesitan
     * plazos opuestos (ver [PoliticaOrigen.Perfil]). Acá el plazo corto lo aplica un temporizador
     * que le corta la conexión por debajo: un `disconnect()` desde otro hilo hace que el
     * `responseCode` bloqueado tire excepción, que es exactamente lo que se busca.
     *
     * El `AtomicBoolean` es lo que evita la carrera fea —que el temporizador desconecte JUSTO
     * después de que la respuesta llegó y le rompa el stream a un pedido que había salido bien—:
     * gana el primero que lo marque, y si gana el temporizador esto devuelve
     * [PoliticaOrigen.SIN_RESPUESTA] para que el que llama reintente en vez de leer una conexión
     * ya muerta.
     */
    private fun codigoConFechaLimite(conn: HttpURLConnection, limiteMs: Int): Int {
        val resuelto = AtomicBoolean(false)
        val corte = verdugo.schedule(
            { if (resuelto.compareAndSet(false, true)) runCatching { conn.disconnect() } },
            limiteMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
        val code = runCatching { conn.responseCode }.getOrDefault(PoliticaOrigen.SIN_RESPUESTA)
        val loMatoElTemporizador = !resuelto.compareAndSet(false, true)
        corte.cancel(false)
        return if (loMatoElTemporizador) PoliticaOrigen.SIN_RESPUESTA else code
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
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.ARCHIVE,
    ): Pair<HttpURLConnection, ConexionUnica.Cerrable>? {
        var ultimoCodigo = PoliticaOrigen.SIN_RESPUESTA
        val intentos = PoliticaOrigen.intentos(perfil)
        repeat(intentos) { intento ->
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (rango != null) setRequestProperty("Range", rango)
                    // Socket nuevo para los orígenes que no toleran el pool. Ver Perfil.reusaSockets.
                    if (!perfil.reusaSockets) setRequestProperty("Connection", "close")
                    connectTimeout = perfil.conectarMs
                    // El plazo del CUERPO, que es el largo. El de la RESPUESTA —el corto, el que
                    // corta a una conexión muerta— lo aplica codigoConFechaLimite() más abajo,
                    // porque HttpURLConnection no distingue los dos y acá hacen falta distintos.
                    readTimeout = PoliticaOrigen.cuerpoMs(perfil)
                }
            }.getOrNull()
            if (conn != null) {
                val cerrable = ConexionUnica.Cerrable { runCatching { conn.disconnect() } }
                // Desde ACÁ y no desde el `return` de más abajo: entre medio está la espera de las
                // cabeceras (`codigoConFechaLimite`), que contra una red muerta se cuelga hasta 90 s.
                conexionesVivas.registrar(cerrable)
                // NO se mata la conexión anterior, y esto es lo contrario de lo que hacía antes.
                //
                // `ConexionUnica` se puso creyendo que el CDN atendía de a una conexión por archivo.
                // Medido en su momento: es falso — sirve dos simultáneas al mismo archivo sin quejarse (206 en
                // 0,77 s la segunda, con la primera todavía descargando). Y al abrir, libVLC hacía
                // VARIAS peticiones seguidas para sondear el stream (visto: bytes=0-, 216576-,
                // 1115160- en 800 ms): matarle la anterior en cada una le cortaba justo las lecturas
                // con las que identifica programas y pistas, y terminaba sin ninguna (`pistas=v0/a0`),
                // negro y mudo. O sea: la protección estaba causando el problema que decía evitar.
                //
                // Lo que sí hacía falta —que una conexión abandonada no siga drenando— ya está
                // resuelto por el `disconnect()` del finally de passthrough, que corre también
                // cuando el reproductor corta de golpe ("broken pipe").
                val vivas = claveUnica?.let { vivasPorClave.merge(it, 1) { a, b -> a + b } } ?: 1
                val etiquetaRango = "${rango ?: "(todo)"}#${intento + 1}"
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "opening ${rango ?: "(all)"} → live connections for this file: $vivas · " +
                        "in flight: ${fotoDeRangosEnVuelo()}",
                )
                rangosEnVuelo[etiquetaRango] = System.currentTimeMillis()
                val plazoMs = PoliticaOrigen.respuestaMs(intento, perfil)
                val t0Respuesta = System.currentTimeMillis()
                val code = codigoConFechaLimite(conn, plazoMs)
                val tardoMs = System.currentTimeMillis() - t0Respuesta
                anotarCodigo(origin, code)
                ultimoCodigo = code
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    // The time to the HEADER, which is what decides whether the deadline is
                    // enough. Without this only the total for the body was visible, which mixes
                    // the wait for the CDN with how long it takes to download the bytes: two
                    // different things.
                    rangosEnVuelo.remove(etiquetaRango)
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "origin answered ${rango ?: "(all)"} with $code in ${tardoMs}ms " +
                            "(deadline ${plazoMs}ms, attempt ${intento + 1}, $vivas connection(s) at once)",
                    )
                    return conn to cerrable
                }
                // `code=-1` + a time right up against the deadline = OUR timer expired, not the
                // CDN's. The distinction matters and wasn't visible: it read "origin rejected" and
                // looked like the origin's fault when it was the deadline choking a request that
                // was going to answer.
                val vencioElPlazo = code == -1 && tardoMs >= plazoMs - 150
                rangosEnVuelo.remove(etiquetaRango)
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "origin rejected ${rango ?: "(all)"} with $code in ${tardoMs}ms " +
                        "(deadline ${plazoMs}ms, attempt ${intento + 1}/$intentos, " +
                        "$vivas connection(s) at once)" +
                        (if (vencioElPlazo) " ← OUR DEADLINE EXPIRED, not the CDN" else "") +
                        " · in flight: ${fotoDeRangosEnVuelo()}",
                )
                claveUnica?.let { soltarViva(it) }
                conexionesVivas.soltar(cerrable)
                runCatching { conn.disconnect() }
                // A 404 doesn't improve by insisting: the file isn't where we have it recorded.
                // Cutting here saves two timeouts and, above all, lets the 404 arrive clean all the
                // way up, which is what triggers the metadata revalidation (see
                // CoincidenciaDeArchivo).
                if (!PoliticaOrigen.valeReintentar(code)) {
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "origin: $code isn't retried, giving up on ${rango ?: "(all)"}",
                    )
                    return null
                }
            }
            if (intento < intentos - 1) Thread.sleep(PoliticaOrigen.esperaMs(intento, perfil))
        }
        // Se acabaron los intentos contra la puerta de entrada. Antes acá había un plan B para
        // archive.org (hablarle directo al nodo que tiene el archivo, salteando `download.php` —
        // ver NodoDeArchive, borrado en la poda de esta rama junto con el resto de esa fuente):
        // magis sirve desde un CDN propio y nunca tuvo un nodo alternativo al que ir.
        return null
    }

    /**
     * Deja el arranque del stream listo en memoria ANTES de que el reproductor abra la URL.
     *
     * El porqué, medido: el CDN de magis tarda entre 0,2 s y 20 s en soltar el primer byte, y
     * cuando la primera lectura se demoraba **libVLC se rendía identificando el stream**. No falla ni
     * avisa: se queda sin pistas (`pistas=v0/a0`, ni imagen ni sonido) y desde ahí traga el archivo
     * a toda velocidad sin volver a intentarlo — la película queda negra para siempre aunque los
     * datos lleguen dos segundos después. Es la explicación de "la primera vez anda y la segunda
     * no": no era el decodificador ni la vista sin destruir, era quién ganaba esa carrera.
     *
     * Con el arranque ya en la mano, la primera lectura del player se responde al instante y siempre
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
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.MAGIS,
        /**
         * Si hay que ESPERAR a la cola antes de volver. Solo hace falta cuando la duración se saca
         * de ella ([duracionDelPrecalentado]); cuando la manda el gateway, la cola únicamente sirve
         * para los sondeos de EOF del player, que ocurren DESPUÉS de abrir y por lo tanto se pueden
         * dejar corriendo por detrás.
         *
         * Medido el 2026-08-11 en el Fire TV, y es la razón de que este parámetro exista: con la
         * cabeza ya sin bloquear, la cola pasó a ser el freno. Tres arranques del mismo capítulo,
         * los tres con la duración ya en la mano: cola de 281 ms → total 1050 ms; colas de 3398 y
         * 3446 ms → totales de 3883 y 4083 ms. Se estaban esperando 3,4 s por unos bytes que en ese
         * momento no le hacían falta a nadie.
         */
        esperarCola: Boolean = true,
        /**
         * Contenedor que declara la fuente ("ts", "mp4"…), para decidir si la cola hace falta.
         * Vacío = no se sabe, y ahí se precalienta igual. Ver [ColaCaliente.hayQuePrecalentar].
         */
        contenedor: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(originUrl)
        val inicio = if (fraccion > 0f) {
            VentanaDeArchivo.inicio(totalDelOrigen(originUrl, headers, perfil), fraccion)
        } else 0L
        val t0 = System.currentTimeMillis()
        // LAS DOS PUNTAS A LA VEZ. Iban en serie y eso era la fase más cara del arranque: medido en
        // el Fire TV sobre ocho reproducciones, `precalentado` dominaba en 6 de 8 con 1443-6647 ms.
        // Piden tramos distintos del archivo y el CDN atiende varias conexiones sin degradarse
        // (medido: con tres drenando, un rango de cola seguía contestando en 0,44-0,82 s), así que
        // el costo pasa a ser el MÁXIMO de las dos en vez de la suma.
        // ¿HACE FALTA LA COLA? En mp4 no: medido en el Fire TV, tres títulos la bajaron y no la
        // usaron ni una vez, y uno de ellos costó 8284 ms con tres rechazos del CDN en paralelo con
        // la apertura del video. Ante la duda se baja igual. Ver [ColaCaliente.hayQuePrecalentar].
        val colaHaceFalta = ColaCaliente.hayQuePrecalentar(contenedor)
        if (!colaHaceFalta) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "tail skipped: the '$contenedor' container opens without reading the end of the file",
            )
        }
        val buffer = BufferQueCrece(ARRANQUE_CALIENTE)
        calientes["$key@$inicio"] = buffer
        // El llenado NO se espera: se publica en el mapa ya mismo y sigue por su cuenta. El proxy le
        // sirve al player de este mismo buffer mientras crece (ver serveArranque).
        Thread {
            runCatching { bajarArranque(originUrl, headers, inicio, perfil, buffer) }
            buffer.cerrar()
        }.apply { isDaemon = true; name = "arkiv-precalentar" }.start()
        // La cola SIEMPRE va en un Thread, nunca en un `async`, y esto no es una preferencia de
        // estilo: `withContext` no vuelve hasta que sus hijos terminan. Un `async` es hijo, así que
        // el `withTimeoutOrNull` de abajo cancelaba la ESPERA y no el trabajo, y al salir del bloque
        // esta misma función se quedaba quieta aguardando a la cola de la que acababa de
        // desentenderse. Medido en el Fire TV el 2026-08-13 con un capítulo de serie: el log decía
        // «la cola no llegó en 2000ms» y el precalentado igual tardó 9218 ms. El hilo SÍ escapa del
        // scope, y por eso el plazo pasa a ser un plazo. Ver PrecalentadoNoBloqueaTest.
        //
        // `esperarCola` ya solo decide si alguien mira el resultado; el trabajo se lanza igual,
        // porque los sondeos de EOF del player quieren esa cola en memoria en los dos casos.
        val cola = CompletableDeferred<Unit>()
        if (colaHaceFalta) {
            Thread {
                runCatching { precalentarCola(originUrl, headers, key, perfil) }
                cola.complete(Unit)
            }.apply { isDaemon = true; name = "arkiv-precalentar-cola" }.start()
        } else {
            // Nadie la va a esperar, pero el tamaño del archivo SÍ hace falta para el precalentado
            // del salto ([precalentarSalto] lo lee de `totales`). Se pide con un rango de UN byte
            // en vez de con los 256 KB de la cola.
            cola.complete(Unit)
            Thread {
                runCatching { totalDelOrigen(originUrl, headers, perfil) }
            }.apply { isDaemon = true; name = "arkiv-tamano" }.start()
        }

        // Lo ÚNICO que se espera siempre: que el arranque haya empezado a fluir. Con eso alcanza
        // para que la primera lectura del player se responda al instante, que es lo que evitaba el
        // negro-y-mudo.
        val arranco = buffer.esperarHasta(ARRANQUE_MINIMO, ESPERA_ARRANQUE_MS)
        // La espera de la cola va ACOTADA. Medido el 2026-08-11 en el Fire TV: cuando el CDN se
        // pone denso, esos 256 KB tardan 8 s —y no es lotería por conexión, porque una segunda
        // conexión en paralelo también tardó lo mismo: es el enlace o el CDN frenando al cliente
        // entero. Dos reproducciones de seis salieron en 10,3 s y 13,8 s esperando ese dato.
        //
        // Pasado este plazo se reproduce SIN duración: la barra queda fea, pero el video arranca.
        // Al revés no — nunca frenar el video por una barra de progreso. La cola sigue bajando
        // igual por detrás, así que los sondeos de EOF del player la encuentran cuando llegue.
        if (esperarCola && withTimeoutOrNull(ESPERA_COLA_MS) { cola.await() } == null) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "the tail didn't arrive within ${ESPERA_COLA_MS}ms → playing without duration",
            )
        }
        android.util.Log.w(
            "ArchiveCacheProxy",
            "startup servable after ${System.currentTimeMillis() - t0}ms " +
                "(${buffer.disponible / 1024}KB of ${ARRANQUE_CALIENTE / 1024}KB, still downloading)",
        )
        if (!arranco && buffer.disponible == 0) {
            android.util.Log.w("ArchiveCacheProxy", "precalentar: no bytes arrived")
            calientes.remove("$key@$inicio")
            return@withContext false
        }
        true
    }

    /**
     * Vuelca los primeros [ARRANQUE_CALIENTE] bytes desde [inicio] en [destino], a medida que
     * llegan. Corre en su propio hilo: quien lo lanza NO lo espera (ver [precalentar]).
     */
    private fun bajarArranque(
        originUrl: String,
        headers: Map<String, String>,
        inicio: Long,
        perfil: PoliticaOrigen.Perfil,
        destino: BufferQueCrece,
    ) {
        val (conn, _) =
            abrirEnOrigen(originUrl, "bytes=$inicio-", headers, claveUnica = null, perfil = perfil)
                ?: run {
                    android.util.Log.w("ArchiveCacheProxy", "precalentar: the origin didn't give the startup chunk")
                    return
                }
        // El TAMAÑO del archivo sale gratis de esta misma respuesta, y hace falta enseguida: es lo
        // que le permite a [precalentarCola] pedir el final por rango ABSOLUTO en vez de por sufijo.
        // Ver ahí por qué esa diferencia vale segundos.
        anotarTotal(originUrl, conn, inicio)
        runCatching {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (total < ARRANQUE_CALIENTE) {
                    val leidos = ins.read(buf, 0, minOf(buf.size, ARRANQUE_CALIENTE - total))
                    if (leidos < 0) break
                    // Cada bloque queda disponible EN EL ACTO para quien esté sirviendo al player.
                    destino.escribir(buf, leidos)
                    total += leidos
                }
            }
        }
        runCatching { conn.disconnect() }
    }

    /**
     * Cuánto dura el archivo, deducido de lo que [precalentar] YA se bajó. 0 = no hay con qué.
     *
     * Es el mismo cálculo de PCR que hacía [TsDurationProbe.probeRemote], pero sin red: la sonda
     * pedía cabeza y cola por su cuenta —los mismos 256 KB del final que el precalentado ya tenía—
     * y las dos peticiones competían entre sí contra el mismo CDN. Medido el 2026-08-11 en el Fire
     * TV, en dos de ocho arranques la sonda perdió esa pelea y la película salió sin duración.
     */
    fun duracionDelPrecalentado(originUrl: String): Long {
        val key = keyFor(originUrl)
        // Lo que haya llegado del arranque alcanza: el PRIMER PCR está en los primeros paquetes, y
        // acá ya se esperó a que el buffer pasara ARRANQUE_MINIMO. No hace falta que esté completo.
        val cabeza = calientes["$key@0"]?.porcion(0)?.takeIf { it.isNotEmpty() } ?: return 0L
        val cola = colas[key]?.second ?: return 0L
        return TsDurationProbe.durationMs(cabeza, cola)
    }

    /**
     * Se guarda el final del archivo para que los sondeos de EOF del player no toquen la red.
     * Ver [ColaCaliente] para la medición que justifica esto.
     *
     * Va por rango-SUFIJO (`bytes=-N`) por la misma razón que la sonda de duración: no hace falta
     * preguntar antes el tamaño, y la respuesta trae el `Content-Range` con el total y el byte donde
     * arranca, que es justo lo que hay que guardar para poder responder rangos absolutos después.
     */
    private fun precalentarCola(
        originUrl: String,
        headers: Map<String, String>,
        key: String,
        perfil: PoliticaOrigen.Perfil,
    ) {
        val t0 = System.currentTimeMillis()
        // ¿YA LA TENEMOS DE OTRA SESIÓN? Es lo primero que se prueba: la cola de un archivo no
        // cambia, y traerla del disco cuesta microsegundos contra los segundos que cuesta el CDN.
        colaEnDisco.leer(key)?.let { guardada ->
            colas[key] = guardada.inicio to guardada.bytes
            totales[originUrl] = guardada.total
            android.util.Log.w(
                "ArchiveCacheProxy",
                "tail from disk: ${guardada.bytes.size / 1024}KB from ${guardada.inicio} " +
                    "(total=${guardada.total}) without touching the network",
            )
            return
        }
        // Se avisa ANTES de abrir, no después: la carrera que esto evita empieza en cuanto el player
        // abre el media, que es milisegundos después de que arranque este hilo.
        val enVuelo = java.util.concurrent.CountDownLatch(1)
        colasEnVuelo[key] = enVuelo
        try {
            precalentarColaAdentro(originUrl, headers, key, perfil, t0)
        } finally {
            colasEnVuelo.remove(key)
            enVuelo.countDown()
        }
    }

    /**
     * Pide [rango] al origen y, si a los [DUPLICAR_TRAS_MS] todavía no contestó, vuelve a pedirlo por
     * OTRA conexión y se queda con la que llegue primero.
     *
     * El porqué, medido en el Fire TV el 2026-08-13 con un capítulo nuevo: el CDN rechazó la cola dos
     * veces seguidas —sin contestar nada, que es como falla este CDN— y cada rechazo cuesta los 3 s
     * de plazo de [PoliticaOrigen.Perfil.MAGIS]. VLC, que necesitaba el final del archivo para
     * abrir, se quedó esperando **7,3 s**. El reintento en serie no ayuda: espera a que el anterior se dé
     * por vencido para recién ahí volver a tirar los dados.
     *
     * Que el CDN aguante conexiones simultáneas no es una suposición: está medido (dos al mismo
     * archivo conviven, la segunda contestó en 0,77 s con la primera descargando). Y el costo del
     * duplicado es acotado — como mucho una petición de más, y solo cuando la primera ya se está
     * demorando más de lo normal.
     *
     * La app original resuelve esto por otro lado: su motor nativo tiene VARIOS nodos de CDN con su
     * latencia medida (`Status.links`, `Status.latency`) y elige. Nosotros tenemos un solo nodo, así
     * que lo que se puede variar es la conexión, no el destino.
     */
    private fun abrirConDuplicado(
        origin: String,
        rango: String?,
        headers: Map<String, String>,
        perfil: PoliticaOrigen.Perfil,
    ): Pair<HttpURLConnection, ConexionUnica.Cerrable>? {
        val ganador = java.util.concurrent.atomic.AtomicReference<Pair<HttpURLConnection, ConexionUnica.Cerrable>?>()
        val terminados = java.util.concurrent.atomic.AtomicInteger(0)
        val listo = java.util.concurrent.CountDownLatch(1)
        repeat(TIROS_A_LA_COLA) { i ->
            Thread {
                // El duplicado sale TARDE a propósito: si la primera contesta a tiempo —el caso
                // normal— este hilo se despierta, ve que ya hay ganador y no toca la red.
                if (i > 0) runCatching { Thread.sleep(DUPLICAR_TRAS_MS) }
                if (ganador.get() == null) {
                    val r = runCatching { abrirEnOrigen(origin, rango, headers, null, perfil) }.getOrNull()
                    if (r != null) {
                        if (ganador.compareAndSet(null, r)) {
                            if (i > 0) {
                                android.util.Log.w(
                                    "ArchiveCacheProxy",
                                    "the DUPLICATE request for ${rango ?: "(all)"} won",
                                )
                            }
                            listo.countDown()
                        } else {
                            // Llegó segunda: su conexión no le sirve a nadie y hay que soltarla, o
                            // se queda drenando el archivo contra el mismo CDN que estamos apurando.
                            runCatching { r.first.disconnect() }
                        }
                    }
                }
                if (terminados.incrementAndGet() == TIROS_A_LA_COLA) listo.countDown()
            }.apply { isDaemon = true; name = "arkiv-cola-$i" }.start()
        }
        runCatching { listo.await() }
        return ganador.get()
    }

    private fun precalentarColaAdentro(
        originUrl: String,
        headers: Map<String, String>,
        key: String,
        perfil: PoliticaOrigen.Perfil,
        t0: Long,
    ) {
        // EL FINAL SE PIDE POR RANGO ABSOLUTO, NO POR SUFIJO. Esto no es una preferencia de estilo:
        // es lo más caro que se encontró midiendo. En el Fire TV, el 2026-08-13, sobre 31 peticiones
        // al CDN de magis:
        //
        //   forma del rango        rechazos   respuestas OK
        //   bytes=-262144 (sufijo)    19            0
        //   bytes=N-    (absoluto)     0           12
        //
        // Los DIECINUEVE rechazos fueron del sufijo y ninguno del absoluto. Y no es que el tramo no
        // esté: en el mismo arranque, tras seis rechazos seguidos de `bytes=-262144` —dos conexiones
        // en paralelo, tres intentos cada una, 8,8 s tirados— el reproductor pidió ese mismo final
        // por `bytes=322515168-` y el CDN lo sirvió en 267 ms. Cada rechazo cuesta los 3 s de plazo
        // del perfil MAGIS, y libVLC no abría hasta tener el final: de ahí salían colas de 7036,
        // 8485 y 9435 ms.
        //
        // El tamaño no cuesta una petición extra: lo anotó [anotarTotal] de la respuesta de la
        // cabeza, que se está bajando en paralelo. Se le da un momento para que llegue; si no llega,
        // se cae al sufijo de siempre, que es peor pero funciona a veces.
        val limite = System.currentTimeMillis() + ESPERA_TOTAL_MS
        var totalConocido = totales[originUrl] ?: 0L
        while (totalConocido <= 0L && System.currentTimeMillis() < limite) {
            Thread.sleep(50)
            totalConocido = totales[originUrl] ?: 0L
        }
        val rangoCola = if (totalConocido > COLA_CALIENTE) {
            "bytes=${totalConocido - COLA_CALIENTE}-${totalConocido - 1}"
        } else {
            android.util.Log.w("ArchiveCacheProxy", "tail: no size in time, falling back to suffix")
            "bytes=-$COLA_CALIENTE"
        }
        val (conn, _) = abrirConDuplicado(
            originUrl, rangoCola, headers, perfil,
        ) ?: run {
            android.util.Log.w("ArchiveCacheProxy", "precalentar tail: the origin didn't give it")
            return
        }
        val contentRange = conn.getHeaderField("Content-Range")
        val bytes = runCatching { conn.inputStream.use { it.readBytes() } }.getOrNull()
        runCatching { conn.disconnect() }
        // `bytes <inicio>-<fin>/<total>`: sin esto no se puede traducir un rango absoluto a un
        // offset dentro de lo guardado, y servir a ciegas sería peor que ir al origen.
        val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(contentRange.orEmpty())
        if (bytes == null || bytes.isEmpty() || m == null) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "precalentar tail: no usable Content-Range (${contentRange ?: "none"})",
            )
            return
        }
        val inicio = m.groupValues[1].toLong()
        val total = m.groupValues[3].toLong()
        totales[originUrl] = total
        colas[key] = inicio to bytes
        // Y al disco, para que la próxima vez que se abra este título no haya que volver a pedirla.
        colaEnDisco.guardar(key, inicio, total, bytes)
        android.util.Log.w(
            "ArchiveCacheProxy",
            "tail pre-warmed: ${bytes.size / 1024}KB from $inicio (total=$total) " +
                "in ${System.currentTimeMillis() - t0}ms",
        )
    }

    /**
     * Contesta [pedido] con lo que hay en [v], sin tocar la red. Devuelve false si no alcanzó y hay
     * que ir al origen como siempre.
     *
     * Solo sirve el tramo que puede entregar ENTERO, y por eso el `Content-Length` que manda es el
     * de ese tramo y no el del resto del archivo: un cuerpo más corto que el largo anunciado deja al
     * reproductor esperando bytes que no van a llegar, sin error visible — el mismo cuidado que ya
     * documenta [ColaCaliente]. Si el reproductor quiere más, lo pide con otro rango, que es
     * exactamente lo que hace al bisecar.
     *
     * OJO con quién pregunta. Eso último vale para libVLC, que bisectaba y volvía a pedir;
     * ExoPlayer NO: pide `bytes=N-` —de ahí al final— y un cuerpo más corto se lo come como fin de los datos.
     * Su ProgressiveMediaPeriod da la carga por terminada y deja de pedir, se acaba lo que tenía en
     * cola —se midieron 512000 frames de audio, 10,7 s exactos, justo el tramo servido—, para el
     * AudioTrack y detiene los renderers sin declarar BUFFERING: la imagen se congela y el reloj
     * sigue corriendo solo. Por eso un rango abierto se sirve de memoria y SE SIGUE con la red en la
     * misma respuesta, en vez de cortar. Medido: los tramos servidos de red nunca colgaron; los de
     * memoria colgaban siempre.
     */
    private fun servirDeVentana(
        v: VentanaDeSalto,
        pedido: Long,
        total: Long,
        out: java.io.OutputStream,
        rangeHeader: String?,
        origin: String,
        extraHeaders: Map<String, String>,
        claveUnica: String?,
        perfil: PoliticaOrigen.Perfil,
    ): Boolean {
        val desde = (pedido - v.inicio).toInt()
        val trozo = runCatching { v.buffer.porcion(desde) }.getOrNull() ?: return false
        if (trozo.isEmpty()) return false

        // Rango abierto (`bytes=N-`): hay que cubrir hasta el final del archivo. Se anuncia ese
        // largo y después de la memoria se sigue con la red, para no cortarle el cuerpo a un
        // cliente que no va a volver a pedir.
        val abierto = RangeHeader.parse(rangeHeader)?.let { it.end == null } ?: false
        val hasta = if (abierto) total - 1 else pedido + trozo.size - 1
        val largo = hasta - pedido + 1

        // DESDE ACÁ NO SE PUEDE VOLVER. En cuanto la cabecera sale por el socket, la respuesta está
        // comprometida: devolver false haría que el passthrough escribiera OTRA respuesta HTTP
        // encima de esta, por la misma conexión. Se descubrió por test —un sondeo servía bien y el
        // de al lado no, sin patrón— y el motivo era justo ese: el reproductor corta a mitad del
        // cuerpo (lee lo que quiere y se va), el `write` fallaba y esto caía al origen habiendo ya
        // contestado. Que el cliente se vaya no es un fallo: es lo normal cuando bisecta.
        val salioLaCabecera = runCatching {
            out.write(
                (
                    "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                        "Content-Length: $largo\r\n" +
                        "Content-Range: bytes $pedido-$hasta/$total\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(trozo)
            out.flush()
        }.isSuccess
        android.util.Log.w(
            "ArchiveCacheProxy",
            "seek window: $rangeHeader served from memory (${trozo.size / 1024}KB, no network)" +
                if (abierto) " · continuing over the network from ${pedido + trozo.size}" else "",
        )
        if (!abierto || !salioLaCabecera) return true

        // El resto del cuerpo, desde donde se acabó la memoria. Si esto falla no se puede hacer
        // nada más: la cabecera ya salió y el cliente verá un cuerpo corto, igual que antes de este
        // cambio. Se devuelve true siempre para que nadie escriba otra respuesta encima.
        val restante = largo - trozo.size
        if (restante <= 0L) return true
        val (conn, cerrable) = abrirEnOrigen(
            origin,
            "bytes=${pedido + trozo.size}-$hasta",
            extraHeaders,
            claveUnica,
            perfil,
        ) ?: return true
        var escritos = 0L
        val t0 = System.currentTimeMillis()
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    if (runCatching { out.write(buf, 0, n) }.isFailure) break
                    escritos += n
                }
            }
            runCatching { out.flush() }
        } catch (_: Throwable) {
            // El cliente cortó o el origen se cayó: lo dice el log de abajo y no hay más que hacer.
        } finally {
            claveUnica?.let { soltarViva(it) }
            conexionesVivas.soltar(cerrable)
            runCatching { conn.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "seek window: tail from the network ${escritos / 1024}KB of ${restante / 1024}KB " +
                    "in ${System.currentTimeMillis() - t0}ms",
            )
        }
        return true
    }

    /**
     * Prepara la zona a la que el reproductor va a SALTAR al reanudar, antes de que la pida.
     *
     * El porqué, medido en el Fire TV el 2026-08-13 reanudando una película en 13:29: libVLC abría
     * SIEMPRE en el byte 0 y recién después buscaba el minuto guardado. Entre una cosa y la otra se
     * bajó **2,5 MB del principio de la película que después tiró**, y eso costó 3,4 s con el
     * reproductor clavado en `pos=0` — casi un tercio de los 10,8 s que tardó en arrancar.
     *
     * Es la pieza que le faltaba a nuestra copia del diseño de la app original: ella le manda a su
     * motor de descarga `Seek {moment}` **antes** de saltar, con callback, justamente para que la
     * zona esté lista cuando el reproductor llegue (ver `yc/C6280e.java` en la decompilada).
     *
     * El byte se estima suponiendo tasa constante, y eso NO es una licencia: se comprobó contra dos
     * reanudaciones reales antes de escribirlo. Estimado 119,4 MB → pedidos en 116,7 y 120,5 MB;
     * estimado 76,1 MB → pedidos entre 76,2 y 79,8 MB. O sea un desvío de -2,7 a +3,7 MB, que es de
     * dónde salen [MARGEN_SALTO] y el tamaño de esta ventana: empezar antes del estimado y cubrir
     * para los dos lados. Si aun así se erra, no se pierde nada — la ventana reactiva de siempre
     * sigue estando.
     *
     * No bloquea a nadie: se va a un hilo y el arranque sigue. Si no llega a tiempo, el reproductor
     * pide por red como hacía antes.
     */
    fun precalentarSalto(
        originUrl: String,
        headers: Map<String, String> = emptyMap(),
        fraccion: Float,
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.MAGIS,
    ) {
        if (fraccion <= 0f || fraccion >= 1f) return
        val key = keyFor(originUrl)
        Thread {
            // El tamaño lo trae la cola, que va bajando en paralelo. Se la espera acá —en un hilo
            // que no frena nada— en vez de pedir el tamaño por separado, que sería otra petición al
            // mismo CDN al que estamos tratando de no molestar.
            val limite = System.currentTimeMillis() + ESPERA_COLA_EN_VUELO_MS
            var total = totales[originUrl] ?: 0L
            while (total <= 0L && System.currentTimeMillis() < limite) {
                Thread.sleep(100)
                total = totales[originUrl] ?: 0L
            }
            if (total <= 0L) {
                // La cola es la vía normal para saber el tamaño, pero puede no haberse pedido
                // (contenedor que no la necesita) o no haber llegado. Un rango de un byte lo
                // resuelve por su cuenta; sin esto el salto se quedaba sin precalentar en silencio.
                total = runCatching { totalDelOrigen(originUrl, headers, perfil) }.getOrDefault(0L)
            }
            if (total <= 0L) {
                android.util.Log.w("ArchiveCacheProxy", "seek: no file size, not pre-warming")
                return@Thread
            }
            val destino = (total * fraccion.toDouble()).toLong()
            val inicio = (destino - MARGEN_SALTO).coerceAtLeast(0L)
            val t0 = System.currentTimeMillis()
            val buffer = BufferQueCrece(VENTANA_SALTO_PRECALENTADA)
            registrarVentana(key, inicio, buffer)
            val abierta = abrirEnOrigen(originUrl, "bytes=$inicio-", headers, null, perfil)
            if (abierta == null) {
                buffer.cerrar()
                android.util.Log.w("ArchiveCacheProxy", "seek: the origin didn't give the range at $inicio")
                return@Thread
            }
            runCatching {
                abierta.first.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (buffer.disponible < VENTANA_SALTO_PRECALENTADA) {
                        val n = ins.read(buf); if (n < 0) break
                        buffer.escribir(buf, n)
                    }
                }
            }
            buffer.cerrar()
            runCatching { abierta.first.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "seek pre-warmed: ${buffer.disponible / 1024}KB from $inicio " +
                    "(estimated target $destino) in ${System.currentTimeMillis() - t0}ms",
            )
        }.apply { isDaemon = true; name = "arkiv-precalentar-salto" }.start()
    }

    /**
     * Guarda el tamaño del archivo leyéndolo de una respuesta que ya teníamos en la mano.
     *
     * Del `Content-Range` si vino (trae el total explícito) y si no del `Content-Length` sumado al
     * byte donde arrancaba el tramo. No pide nada: el objetivo es justamente no gastar una petición
     * de más contra este CDN.
     */
    private fun anotarTotal(originUrl: String, conn: HttpURLConnection, inicio: Long) {
        if ((totales[originUrl] ?: 0L) > 0L) return
        val total = runCatching {
            val cr = conn.getHeaderField("Content-Range")
            val m = Regex("""/(\d+)""").find(cr.orEmpty())
            if (m != null) {
                m.groupValues[1].toLong()
            } else {
                val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                if (len > 0L) inicio + len else 0L
            }
        }.getOrDefault(0L)
        if (total > 0L) totales[originUrl] = total
    }

    /** Anota una ventana nueva para [clave], tirando la más vieja si ya hay demasiadas. */
    private fun registrarVentana(clave: String, inicio: Long, buffer: BufferQueCrece) {
        val lista = saltos.computeIfAbsent(clave) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(lista) {
            lista.add(VentanaDeSalto(inicio, buffer))
            while (lista.size > VENTANAS_POR_ARCHIVO) lista.removeAt(0)
        }
    }

    /** Passthrough directo origen→reproductor (sin cachear), último recurso si no se pudo iniciar la descarga. */
    private fun passthrough(
        origin: String,
        rangeHeader: String?,
        out: java.io.OutputStream,
        extraHeaders: Map<String, String> = emptyMap(),
        claveUnica: String? = null,
        fraccion: Float = 0f,
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.ARCHIVE,
    ): Boolean {
        // Ventana: el reproductor pide en coordenadas de un archivo que empieza en 0, y acá se
        // traducen a las del archivo real. Si no se pudo saber el tamaño, `inicio` queda en 0 y
        // esto se comporta como el passthrough de siempre: sin duración es peor, pero reproduce.
        val inicio = if (fraccion > 0f) {
            VentanaDeArchivo.inicio(totalDelOrigen(origin, extraHeaders, perfil), fraccion)
        } else 0L
        val rangoCliente = RangeHeader.parse(rangeHeader)
        val rangoAlOrigen = if (inicio > 0L) {
            VentanaDeArchivo.rangoAlOrigen(rangoCliente, inicio)
        } else rangeHeader
        // SONDEO DEL FINAL: contestado desde memoria, sin tocar la red. Es la petición que se
        // llevaba el arranque — ver ColaCaliente para la medición. Solo aplica sin ventana: con
        // `f=` los bytes que ve el reproductor están corridos y estos NO son los suyos.
        if (inicio == 0L && claveUnica != null) {
            // Si la cola TODAVÍA se está bajando, se la espera en vez de abrir una conexión que le
            // compita por los mismos bytes (ver [colasEnVuelo] para la medición). Solo para rangos
            // que no empiezan en 0: `bytes=0-` es la primera lectura del player —la cabeza— y esa la
            // contesta el arranque caliente, no la cola.
            val enVuelo = colasEnVuelo[claveUnica]
            if (enVuelo != null && colas[claveUnica] == null && (rangoCliente?.start ?: 0L) > 0L) {
                val t = System.currentTimeMillis()
                val llego = enVuelo.await(ESPERA_COLA_EN_VUELO_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "tail in flight: $rangeHeader waited ${System.currentTimeMillis() - t}ms " +
                        "(${if (llego) "arrived" else "deadline expired, going to the origin"})",
                )
            }
            // SALTO YA GUARDADO: si este rango cae en una ventana de un salto anterior, se contesta
            // de memoria. Es el caso de los nueve rangos que siguen al primero de una bisección.
            val pedido = rangoCliente?.start ?: 0L
            val totalConocido = totales[origin] ?: 0L
            if (pedido > 0L && totalConocido > 0L) {
                // `synchronized` y no `firstOrNull` a secas: la lista la escribe cualquier hilo que
                // esté atendiendo otro rango del mismo archivo, y recorrerla sin el candado es
                // exactamente la carrera que hace que un salto se sirva bien y el de al lado no.
                val lista = saltos[claveUnica]
                val v = if (lista != null) synchronized(lista) { lista.firstOrNull { it.cubre(pedido) } } else null
                if (v != null && servirDeVentana(
                        v, pedido, totalConocido, out, rangeHeader,
                        origin, extraHeaders, claveUnica, perfil,
                    )
                ) return true
            }
            val guardada = colas[claveUnica]
            val total = totales[origin] ?: 0L
            val trozo = guardada?.let { (desde, cola) ->
                ColaCaliente.servir(desde, cola, rangoCliente, total)
            }
            if (trozo != null) {
                val fin = rangoCliente!!.start + trozo.size - 1
                out.write(
                    (
                        "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                            "Content-Length: ${trozo.size}\r\n" +
                            "Content-Range: bytes ${rangoCliente.start}-$fin/$total\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
                        ).toByteArray(),
                )
                out.write(trozo)
                out.flush()
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "hot tail: $rangeHeader served from memory (${trozo.size}B, no network)",
                )
                return true
            }
        }
        // El arranque precalentado sirve UNA vez y solo para la petición que empieza en el byte 0
        // (la primera que hace el reproductor al abrir): es ahí donde se juega la identificación
        // del stream. Se consume del mapa para que un salto posterior no reciba bytes del principio.
        // La clave incluye el BYTE de arranque, no solo el archivo. Sin eso, un precalentado hecho
        // para otro punto se le pegaba igual al principio del stream: 2 MB de otra parte de la
        // película empalmados en la cabecera, que es basura para el demuxer y deja al player sin
        // pistas — o sea, causando exactamente el fallo que este precalentado venía a evitar. Los offsets
        // NO siempre coinciden: la posición guardada sigue avanzando entre que se precalienta y que
        // el reproductor abre.
        val caliente = if ((rangoCliente?.start ?: 0L) == 0L && claveUnica != null) {
            calientes.remove("$claveUnica@$inicio").also {
                if (it == null && calientes.isNotEmpty()) {
                    // There WAS a pre-warmed startup but for ANOTHER point: the player opened at a
                    // different spot than the one that was prepared. It's not fatal (it's served
                    // from the origin), but it's a wasted pre-warm and needs to be visible: it used
                    // to be the silent failure.
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "hot startup DOESN'T MATCH: opened at $inicio and had ${calientes.keys}",
                    )
                }
            }
        } else null
        // Cada intento mata el tramo anterior de ESTE mismo archivo antes de hablarle al origen: si
        // la conexión vieja sigue viva, la nueva queda colgada hasta el timeout.
        val (conn, cerrable) =
            abrirEnOrigen(origin, rangoAlOrigen, extraHeaders, claveUnica, perfil)
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
        // Se guarda ventana solo para los SALTOS: `bytes=0-` es la lectura principal, esa nunca la
        // corta el reproductor y guardarla sería 4 MB de RAM a cambio de nada. Tampoco si el tramo
        // ya lo estaba sirviendo el arranque caliente, que tiene su propio buffer.
        val ventana = if (
            claveUnica != null && inicio == 0L && caliente == null && (rangoCliente?.start ?: 0L) > 0L
        ) {
            BufferQueCrece(VENTANA_SALTO).also { registrarVentana(claveUnica, rangoCliente!!.start, it) }
        } else null
        var escritos = 0L
        val t0 = System.currentTimeMillis()
        try {
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                // ARRANQUE CALIENTE: si este tramo empieza justo donde se precalentó, los bytes se
                // le entregan al player A MEDIDA QUE LLEGAN del precalentado, sin esperar a que
                // estén los 2 MB completos. Eso era lo único que le importaba a libVLC para no
                // rendirse identificando el stream (ver precalentar), y es lo que permite que el arranque
                // deje de bloquear: antes había que tener el bloque entero antes de publicar la
                // playlist, y eso costaba 0,5-5 s de spinner por reproducción.
                //
                // Los mismos bytes se descartan después del origen para no tener que tocar las
                // cabeceras ya enviadas: son 2 MB de más una vez por reproducción, a cambio de que
                // nunca quede en negro.
                if (caliente != null) {
                    var servidos = 0
                    while (true) {
                        val trozo = caliente.porcion(servidos)
                        if (trozo.isNotEmpty()) {
                            out.write(trozo); out.flush()
                            servidos += trozo.size; escritos += trozo.size
                        } else if (caliente.cerrado) {
                            break
                        } else if (!caliente.esperarHasta(servidos + 1, ESPERA_ARRANQUE_MS)) {
                            // Se cerró o dejó de fluir: lo que falte se sigue leyendo del origen,
                            // que es de donde salía todo antes de que esto existiera.
                            break
                        }
                    }
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "hot startup: ${servidos / 1024}KB served while they were downloading",
                    )
                    var porDescartar = servidos
                    while (porDescartar > 0) {
                        val n = ins.read(buf, 0, minOf(porDescartar, buf.size))
                        if (n < 0) break
                        porDescartar -= n
                    }
                }
                // VENTANA DE SALTO. Este tramo se copia a memoria MIENTRAS se le manda al
                // reproductor, y si él corta —que es lo que hace al bisecar un salto— se sigue
                // leyendo hasta llenarla. Los bytes que se guardan son los que esta conexión iba a
                // traer igual: antes se tiraban con el `disconnect()` de acá abajo. Ver
                // [VentanaDeSalto] para la medición de los diez rangos.
                var cortoElCliente = false
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    ventana?.escribir(buf, n)
                    if (!cortoElCliente) {
                        val entregado = runCatching { out.write(buf, 0, n) }.isSuccess
                        if (entregado) escritos += n else cortoElCliente = true
                    }
                    // Sin ventana no hay nada que ganar leyendo un archivo que nadie mira.
                    if (cortoElCliente && ventana == null) break
                    if (cortoElCliente && (ventana?.disponible ?: 0) >= VENTANA_SALTO) break
                }
                // Solo si de verdad hubo ventana: cortar sin ventana es lo normal en la lectura
                // principal, y anunciarlo como "0KB guardados" hacía parecer que la ventana había
                // fallado cuando ni siquiera correspondía abrir una.
                if (cortoElCliente && ventana != null) {
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "seek window at ${rangoCliente?.start}: " +
                            "${ventana.disponible / 1024}KB saved after the player cut off",
                    )
                }
            }
            out.flush()
        } finally {
            ventana?.cerrar()
            claveUnica?.let { soltarViva(it) }
            conexionesVivas.soltar(cerrable)
            // disconnect() SIEMPRE, también cuando el reproductor corta la conexión a mitad (seek →
            // "broken pipe"). Antes la excepción se saltaba esta línea y la conexión al CDN quedaba
            // viva en el pool de HttpURLConnection drenando el resto del archivo: el origen veía dos
            // conexiones a la vez y la NUEVA (la del punto al que se saltó) se quedaba sin datos.
            runCatching { conn.disconnect() }
            android.util.Log.w(
                "ArchiveCacheProxy",
                "direct ${rangeHeader ?: "(all)"}" +
                    (if (inicio > 0L) " [window from $inicio → requested $rangoAlOrigen]" else "") +
                    " → code=$code ${escritos / 1024}KB in ${System.currentTimeMillis() - t0}ms",
            )
        }
        return true
    }
}
