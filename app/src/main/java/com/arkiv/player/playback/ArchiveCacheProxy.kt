package com.arkiv.player.playback

import com.arkiv.player.data.NodoDeArchive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
     * Último código HTTP que dio cada origen. Existe porque el reproductor NO puede distinguir por
     * qué falló: pase lo que pase acá, VLC ve un 502 del proxy. Y la diferencia importa — un 404
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
     * reproducción. Ahora es un [BufferQueCrece] y se lee mientras se llena — VLC abre apenas hay
     * algo y no se queda sin datos porque el buffer sigue creciendo detrás.
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
     * A diferencia de [calientes], esta NO se consume al usarla: libVLC sondea el final VARIAS veces
     * seguidas y con offsets distintos, y todas esas son las que hay que contestar sin red.
     */
    private val colas = ConcurrentHashMap<String, Pair<Long, ByteArray>>()

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
     * El porqué, medido en el Fire TV el 2026-08-13 reanudando una película en 13:26: libVLC no salta
     * de una: **bisecta**. Pidió diez rangos seguidos —`bytes=62148288-`, `63899508-`, `63533848-`,
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
    private val initLocks = ConcurrentHashMap<String, Any>()
    private fun initLock(key: String): Any = initLocks.computeIfAbsent(key) { Any() }

    /**
     * El que le corta la conexión al origen que no contesta a tiempo. Ver [codigoConFechaLimite].
     *
     * Un solo hilo alcanza: solo programa `disconnect()`, que no bloquea. Daemon para que no impida
     * que el proceso muera.
     */
    private val verdugo = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "arkiv-origen-verdugo").apply { isDaemon = true }
    }

    // Si VLC pide un tramo más de 8 MB por delante de lo ya descargado, se asume seek/moov y se trae
    // directo del origen en vez de esperar a la descarga secuencial.
    private val AHEAD_THRESHOLD = 8L * 1024 * 1024

    // Reintentos contra el origen en el camino directo. El CDN de magis rechaza o demora peticiones
    // al azar (medido: entre 0,2 s y 20 s hasta el primer byte, y no-206 esporádicos sobre rangos
    // perfectamente válidos), así que un solo intento convierte cualquier mala racha en "la película
    // no reproduce". Ver TsDurationProbe, que ya aprendió lo mismo.
    //
    // Cuánto esperar y qué vale reintentar vive en [PoliticaOrigen], que es donde está medido el
    // porqué de cada número (resumen: archive puede tardar 72 s en soltar el primer byte).
    private val INTENTOS_ORIGEN = PoliticaOrigen.INTENTOS

    /**
     * Cuánto se precalienta del arranque. Es el número que hay que mover si esto se vuelve lento, y
     * también el primero que hay que revisar si vuelve el negro-y-mudo.
     *
     * Empezó en 2 MB, elegido con holgura para que libVLC identifique programas y pistas sin
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
     * no termina de identificar el stream con lo que tiene en memoria y sale a la red en mitad del
     * arranque, que es precisamente lo que este precalentado existe para evitar. No llegó a fallar
     * —cero rescates, las dos se recuperaron— pero el final de ese camino es el negro-y-mudo
     * documentado en [precalentar], y el ahorro no lo justifica.
     *
     * Lo que sí domina cuando esto se pone lento no es el tamaño: en las corridas malas la cabeza
     * de 2 MB y la cola de 256 KB terminan en el MISMO milisegundo (5205/5212, 5264/5266), o sea
     * que el cuello está en el enlace o en el CDN, y ningún recorte de payload lo arregla.
     */
    private val ARRANQUE_CALIENTE = 2 * 1024 * 1024

    private class Download(
        val origin: String,
        val file: File,
        val doneMarker: File,
        val total: Long,
        val headers: Map<String, String> = emptyMap(),
        /**
         * Byte del origen que corresponde al principio del archivo de caché. 0 = la descarga de
         * siempre, desde el arranque del archivo. Ver [VentanaDeDescarga] para cuándo y por qué
         * puede no serlo.
         */
        val inicio: Long = 0L,
    ) {
        /**
         * Byte ABSOLUTO hasta el que hay datos — no cuántos bytes se bajaron. Empieza valiendo
         * [inicio] justamente para que todas las comparaciones contra rangos del origen sigan
         * siendo las mismas con ventana y sin ventana.
         */
        @Volatile var downloaded: Long = inicio
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

        /**
         * La misma URL, pero pidiendo que la descarga a caché EMPIECE en [desde] en vez de en el
         * byte 0. No cambia lo que ve el reproductor —los bytes conservan su posición real— solo
         * qué pedazo del archivo se baja de corrido. Ver [VentanaDeDescarga].
         *
         * Va antes de `u=` como el resto, por el mismo motivo: hay código que saca el origen con
         * `substringAfter("u=")`.
         */
        fun conVentanaDesde(proxyUrl: String, desde: Long): String {
            if (desde <= 0L) return proxyUrl
            val limpia = proxyUrl.replace(Regex("""[?&]w=[^&]*"""), "")
            val i = limpia.indexOf("u=")
            if (i < 0) return limpia
            return limpia.substring(0, i) + "w=$desde&" + limpia.substring(i)
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
                // Byte donde arranca la descarga secuencial. Distinto de `f=`: acá los bytes NO se
                // corren, solo se baja otro pedazo. Ver VentanaDeDescarga.
                val ventanaDesde = path.substringAfter("w=", "").substringBefore('&')
                    .toLongOrNull()?.takeIf { it > 0L } ?: 0L
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
                //
                //    `d=1` es también de dónde sale el PERFIL de aguante: hoy este camino lo usa
                //    solo magis (`proxyUrl(directo = true)` no tiene otro llamador), y magis y
                //    archive fallan de formas opuestas — ver PoliticaOrigen.Perfil. Si algún día
                //    otra fuente pide `d=1`, el perfil tiene que viajar en la URL, no deducirse.
                if (directo) {
                    if (!passthrough(
                            origin, rangeHeader, out, extraHeaders,
                            claveUnica = key, fraccion = fraccion,
                            perfil = PoliticaOrigen.Perfil.MAGIS,
                        )
                    ) {
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
                // Si el origen no acepta el Range de la ventana, se reintenta desde 0: es mejor
                // reproducir sin el colchón que no reproducir.
                val dl = ensureDownload(key, origin, file, doneMarker, extraHeaders, ventanaDesde)
                    ?: if (ventanaDesde > 0L) {
                        android.util.Log.w(
                            "ArchiveCacheProxy",
                            "ventana desde $ventanaDesde rechazada por el origen → bajo desde 0",
                        )
                        ensureDownload(key, origin, file, doneMarker, extraHeaders)
                    } else {
                        null
                    }
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
        desde: Long = 0L,
    ): Download? {
        downloads[key]?.let { if (!it.failed) return it }
        return synchronized(initLock(key)) {
            downloads[key]?.let { if (!it.failed) return it }
            val conn = runCatching {
                (URL(origin).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true // archive.org 302 → nodo de datos
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    // Reanudando lejos del principio, la descarga arranca cerca del punto que se va
                    // a leer en vez de en el byte 0. Ver VentanaDeDescarga.
                    if (desde > 0L) setRequestProperty("Range", "bytes=$desde-")
                    // Un solo tiro: si esto falla no hay descarga secuencial y todo cae a
                    // passthrough/502. Por eso va con el timeout del peor caso, no con el del
                    // primer intento.
                    connectTimeout = PoliticaOrigen.CONECTAR_MS
                    readTimeout = PoliticaOrigen.cuerpoMs()
                }
            }.getOrNull() ?: return null
            val code = runCatching { conn.responseCode }.getOrDefault(PoliticaOrigen.SIN_RESPUESTA)
            anotarCodigo(origin, code)
            // Con Range, `contentLength` es lo que mide el TRAMO, no el archivo: el tamaño real sale
            // del Content-Range. Confundirlos dejaría a todo el proxy creyendo que la película mide
            // lo que falta desde el punto de reanudación.
            val total = if (desde > 0L) {
                VentanaDeArchivo.totalDelContentRange(conn.getHeaderField("Content-Range"))
            } else {
                conn.contentLengthLong
            }
            val rangoAceptado = desde == 0L || code == HttpURLConnection.HTTP_PARTIAL
            if ((code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) ||
                total <= 0 || !rangoAceptado
            ) {
                // Si el origen ignoró el Range no se puede ventanear: mejor rendirse acá y que el
                // llamador reintente desde 0 que escribir el archivo con los bytes corridos.
                runCatching { conn.disconnect() }
                return null
            }
            val inicio = if (desde > 0L) desde else 0L
            if (inicio > 0L) {
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "descarga con ventana desde $inicio de $total (reanudación lejos del inicio)",
                )
            }
            val dl = Download(origin, file, doneMarker, total, extraHeaders, inicio)
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
            dl.done = true
            // El marcador de "cacheado" SOLO si el archivo empieza en el byte 0. Una ventana llegó
            // al final del origen, pero le falta todo lo de antes: marcarla haría que la próxima
            // reproducción la sirviera entera desde disco y saliera cortada. Ver VentanaDeDescarga.
            // Sin borrar el archivo: `done` ya está en true, así que puede haber lecturas en curso
            // sirviéndose de él. Sin marcador nadie lo va a confundir con el archivo completo, y el
            // espacio lo recupera la caché por su cuenta.
            if (VentanaDeDescarga.esCacheable(dl.inicio)) {
                runCatching { dl.doneMarker.createNewFile() }
                cache.touch(key); cache.evictIfNeeded()
            }
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
        val rama = VentanaDeDescarga.rama(
            inicio = dl.inicio,
            descargado = dl.downloaded,
            completo = dl.done,
            start = start,
            end = end,
            umbralAdelante = AHEAD_THRESHOLD,
        )
        // Qué camino se tomó para este tramo: cada uno falla distinto y desde fuera se ven igual.
        android.util.Log.w(
            "ArchiveCacheProxy",
            "→ rama=$rama tramo=$start-$end descargado=${dl.downloaded}/${dl.total}" +
                if (dl.inicio > 0) " (ventana desde ${dl.inicio})" else "",
        )
        when (rama) {
            // Todo el tramo ya está en la caché en disco → lectura directa, sin red.
            VentanaDeDescarga.Rama.DISCO ->
                readFromDisk(dl.file, VentanaDeDescarga.offsetEnArchivo(dl.inicio, start), len, out)
            // Tramo MUY por delante de la descarga (seek lejano o el `moov` del final), o ANTES del
            // principio de la ventana → directo del origen: en los dos casos no está en disco.
            VentanaDeDescarga.Rama.ORIGEN ->
                fetchOriginRangeBody(dl.origin, start, end, out, dl.headers)
            // Reproducción secuencial (el playhead): se lee de disco a medida que la descarga —a
            // velocidad plena, que va por delante— lo va llenando; solo espera lo mínimo.
            VentanaDeDescarga.Rama.CRECIENDO -> streamGrowingFromDisk(dl, start, end, out)
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
                // `pos` es byte del ORIGEN; el archivo de caché puede empezar más adelante.
                raf.seek(VentanaDeDescarga.offsetEnArchivo(dl.inicio, pos))
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
                // También de un solo tiro, y encima con la cabecera 206 ya enviada: si acá se corta
                // por timeout, el reproductor queda esperando un cuerpo que no llega. Peor caso.
                connectTimeout = PoliticaOrigen.CONECTAR_MS
                readTimeout = PoliticaOrigen.cuerpoMs()
            }
        }.getOrNull() ?: run {
            android.util.Log.w("ArchiveCacheProxy", "origen: no se pudo abrir la conexión ($start-$end)")
            return
        }
        // El código del origen y los bytes entregados: sin esto, un 403/timeout acá es INVISIBLE —
        // la cabecera 206 ya salió, así que el reproductor espera un cuerpo que nunca llega y se
        // queda buffereando al 0% sin error.
        val code = runCatching { conn.responseCode }.getOrDefault(PoliticaOrigen.SIN_RESPUESTA)
        anotarCodigo(origin, code)
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
        android.util.Log.w("ArchiveCacheProxy", "ventana: no se pudo saber el tamaño del origen")
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
                val code = codigoConFechaLimite(conn, PoliticaOrigen.respuestaMs(intento, perfil))
                anotarCodigo(origin, code)
                ultimoCodigo = code
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    return conn to cerrable
                }
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "origen rechazó ${rango ?: "(todo)"} con $code (intento ${intento + 1}/$intentos)",
                )
                claveUnica?.let { soltarViva(it) }
                runCatching { conn.disconnect() }
                // Un 404 no mejora por insistir: el archivo no está donde lo tenemos anotado. Cortar
                // acá ahorra dos timeouts y, sobre todo, deja el 404 llegar limpio hasta arriba, que
                // es lo que dispara la revalidación de la metadata (ver CoincidenciaDeArchivo).
                if (!PoliticaOrigen.valeReintentar(code)) {
                    android.util.Log.w(
                        "ArchiveCacheProxy",
                        "origen: $code no se reintenta, me rindo con ${rango ?: "(todo)"}",
                    )
                    return null
                }
            }
            if (intento < intentos - 1) Thread.sleep(PoliticaOrigen.esperaMs(intento, perfil))
        }
        // Se acabaron los intentos contra la puerta de entrada. Si lo que falló es el REDIRECTOR
        // —no el contenido— todavía queda hablarle directo al servidor que tiene el archivo.
        // Ver NodoDeArchive: medido, download.php daba 500/503 mientras el nodo servía 206.
        //
        // Solo para archive: el plan B es `download.php` → nodo, geografía de archive.org. Magis
        // sirve desde un CDN propio y acá no hay nodo alternativo al que ir.
        if (perfil == PoliticaOrigen.Perfil.ARCHIVE && NodoDeArchive.valeIntentarNodo(ultimoCodigo)) {
            return abrirEnNodo(origin, rango, extraHeaders, claveUnica)
        }
        return null
    }

    /**
     * Plan B: buscar el archivo en el servidor que lo tiene, salteando `download.php`.
     *
     * Se paga una consulta extra a `/metadata/` —que es OTRO servicio de archive y sigue en pie
     * cuando el redirector no— para saber a qué nodo ir. Solo se llega acá si el camino normal ya
     * falló del todo, así que ese viaje de más no le cuesta nada al caso bueno.
     *
     * La URL resultante NO se guarda en ningún lado a propósito: los ítems se mueven de servidor y
     * una URL de nodo cacheada envejece hasta apuntar a la nada.
     */
    private fun abrirEnNodo(
        origin: String,
        rango: String?,
        extraHeaders: Map<String, String>,
        claveUnica: String?,
    ): Pair<HttpURLConnection, ConexionUnica.Cerrable>? {
        val (identifier, ruta) = NodoDeArchive.partesDeUrlDeDescarga(origin) ?: return null
        val json = leerTexto(com.arkiv.player.data.ArchiveUrls.metadata(identifier)) ?: run {
            android.util.Log.w("ArchiveCacheProxy", "nodo: /metadata/ tampoco contestó para $identifier")
            return null
        }
        val urls = NodoDeArchive.urlsDesdeMetadata(json, ruta)
        if (urls.isEmpty()) {
            android.util.Log.w("ArchiveCacheProxy", "nodo: la metadata no dice en qué servidor está")
            return null
        }
        android.util.Log.w("ArchiveCacheProxy", "nodo: pruebo ${urls.size} servidor(es) directo(s)")
        urls.forEachIndexed { i, url ->
            val conn = runCatching {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (rango != null) setRequestProperty("Range", rango)
                    connectTimeout = PoliticaOrigen.CONECTAR_MS
                    // Un solo tiro por nodo, así que va con el timeout del peor caso.
                    readTimeout = PoliticaOrigen.cuerpoMs()
                }
            }.getOrNull() ?: return@forEachIndexed
            val code = runCatching { conn.responseCode }.getOrDefault(PoliticaOrigen.SIN_RESPUESTA)
            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                // Se anota contra el origen CANÓNICO, que es por el que preguntan arriba: para el
                // resto de la app esta petición salió bien, sin importar por qué puerta entró.
                anotarCodigo(origin, code)
                val vivas = claveUnica?.let { vivasPorClave.merge(it, 1) { a, b -> a + b } } ?: 1
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "nodo #${i + 1} SIRVIÓ ${rango ?: "(todo)"} con $code (vivas: $vivas)",
                )
                return conn to ConexionUnica.Cerrable { runCatching { conn.disconnect() } }
            }
            android.util.Log.w("ArchiveCacheProxy", "nodo #${i + 1} devolvió $code")
            runCatching { conn.disconnect() }
        }
        return null
    }

    /** GET simple de un texto corto (la metadata). null si no se pudo. */
    private fun leerTexto(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
            connectTimeout = PoliticaOrigen.CONECTAR_MS
            readTimeout = PoliticaOrigen.respuestaMs(0)
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) { conn.disconnect(); return null }
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            .also { runCatching { conn.disconnect() } }
    }.getOrNull()

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
        perfil: PoliticaOrigen.Perfil = PoliticaOrigen.Perfil.MAGIS,
        /**
         * Si hay que ESPERAR a la cola antes de volver. Solo hace falta cuando la duración se saca
         * de ella ([duracionDelPrecalentado]); cuando la manda el gateway, la cola únicamente sirve
         * para los sondeos de EOF de libVLC, que ocurren DESPUÉS de abrir y por lo tanto se pueden
         * dejar corriendo por detrás.
         *
         * Medido el 2026-08-11 en el Fire TV, y es la razón de que este parámetro exista: con la
         * cabeza ya sin bloquear, la cola pasó a ser el freno. Tres arranques del mismo capítulo,
         * los tres con la duración ya en la mano: cola de 281 ms → total 1050 ms; colas de 3398 y
         * 3446 ms → totales de 3883 y 4083 ms. Se estaban esperando 3,4 s por unos bytes que en ese
         * momento no le hacían falta a nadie.
         */
        esperarCola: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = cache.keyFor(originUrl)
        val inicio = if (fraccion > 0f) {
            VentanaDeArchivo.inicio(totalDelOrigen(originUrl, headers, perfil), fraccion)
        } else 0L
        val t0 = System.currentTimeMillis()
        // LAS DOS PUNTAS A LA VEZ. Iban en serie y eso era la fase más cara del arranque: medido en
        // el Fire TV sobre ocho reproducciones, `precalentado` dominaba en 6 de 8 con 1443-6647 ms.
        // Piden tramos distintos del archivo y el CDN atiende varias conexiones sin degradarse
        // (medido: con tres drenando, un rango de cola seguía contestando en 0,44-0,82 s), así que
        // el costo pasa a ser el MÁXIMO de las dos en vez de la suma.
        val buffer = BufferQueCrece(ARRANQUE_CALIENTE)
        calientes["$key@$inicio"] = buffer
        // El llenado NO se espera: se publica en el mapa ya mismo y sigue por su cuenta. El proxy le
        // sirve a VLC de este mismo buffer mientras crece (ver serveArranque).
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
        // porque los sondeos de EOF de libVLC quieren esa cola en memoria en los dos casos.
        val cola = CompletableDeferred<Unit>()
        Thread {
            runCatching { precalentarCola(originUrl, headers, key, perfil) }
            cola.complete(Unit)
        }.apply { isDaemon = true; name = "arkiv-precalentar-cola" }.start()

        // Lo ÚNICO que se espera siempre: que el arranque haya empezado a fluir. Con eso alcanza
        // para que la primera lectura de libVLC se responda al instante, que es lo que evitaba el
        // negro-y-mudo.
        val arranco = buffer.esperarHasta(ARRANQUE_MINIMO, ESPERA_ARRANQUE_MS)
        // La espera de la cola va ACOTADA. Medido el 2026-08-11 en el Fire TV: cuando el CDN se
        // pone denso, esos 256 KB tardan 8 s —y no es lotería por conexión, porque una segunda
        // conexión en paralelo también tardó lo mismo: es el enlace o el CDN frenando al cliente
        // entero. Dos reproducciones de seis salieron en 10,3 s y 13,8 s esperando ese dato.
        //
        // Pasado este plazo se reproduce SIN duración: la barra queda fea, pero el video arranca.
        // Al revés no — nunca frenar el video por una barra de progreso. La cola sigue bajando
        // igual por detrás, así que los sondeos de EOF de VLC la encuentran cuando llegue.
        if (esperarCola && withTimeoutOrNull(ESPERA_COLA_MS) { cola.await() } == null) {
            android.util.Log.w(
                "ArchiveCacheProxy",
                "la cola no llegó en ${ESPERA_COLA_MS}ms → se reproduce sin duración",
            )
        }
        android.util.Log.w(
            "ArchiveCacheProxy",
            "arranque servible tras ${System.currentTimeMillis() - t0}ms " +
                "(${buffer.disponible / 1024}KB de ${ARRANQUE_CALIENTE / 1024}KB, sigue bajando)",
        )
        if (!arranco && buffer.disponible == 0) {
            android.util.Log.w("ArchiveCacheProxy", "precalentar: no llegaron bytes")
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
                    android.util.Log.w("ArchiveCacheProxy", "precalentar: el origen no dio el arranque")
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
                    // Cada bloque queda disponible EN EL ACTO para quien esté sirviendo a VLC.
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
        val key = cache.keyFor(originUrl)
        // Lo que haya llegado del arranque alcanza: el PRIMER PCR está en los primeros paquetes, y
        // acá ya se esperó a que el buffer pasara ARRANQUE_MINIMO. No hace falta que esté completo.
        val cabeza = calientes["$key@0"]?.porcion(0)?.takeIf { it.isNotEmpty() } ?: return 0L
        val cola = colas[key]?.second ?: return 0L
        return TsDurationProbe.durationMs(cabeza, cola)
    }

    /**
     * Se guarda el final del archivo para que los sondeos de EOF de libVLC no toquen la red.
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
        // Se avisa ANTES de abrir, no después: la carrera que esto evita empieza en cuanto libVLC
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
     * de plazo de [PoliticaOrigen.Perfil.MAGIS]. VLC, que necesita el final del archivo para abrir,
     * se quedó esperando **7,3 s**. El reintento en serie no ayuda: espera a que el anterior se dé
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
                                    "ganó el pedido DUPLICADO de ${rango ?: "(todo)"}",
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
        // del perfil MAGIS, y libVLC no abre hasta tener el final: de ahí salían colas de 7036,
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
            android.util.Log.w("ArchiveCacheProxy", "cola: sin tamaño a tiempo, cae al sufijo")
            "bytes=-$COLA_CALIENTE"
        }
        val (conn, _) = abrirConDuplicado(
            originUrl, rangoCola, headers, perfil,
        ) ?: run {
            android.util.Log.w("ArchiveCacheProxy", "precalentar cola: el origen no la dio")
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
                "precalentar cola: sin Content-Range utilizable (${contentRange ?: "ninguno"})",
            )
            return
        }
        val inicio = m.groupValues[1].toLong()
        val total = m.groupValues[3].toLong()
        totales[originUrl] = total
        colas[key] = inicio to bytes
        android.util.Log.w(
            "ArchiveCacheProxy",
            "precalentada la cola: ${bytes.size / 1024}KB desde $inicio (total=$total) " +
                "en ${System.currentTimeMillis() - t0}ms",
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
     */
    private fun servirDeVentana(
        v: VentanaDeSalto,
        pedido: Long,
        total: Long,
        out: java.io.OutputStream,
        rangeHeader: String?,
    ): Boolean {
        val desde = (pedido - v.inicio).toInt()
        val trozo = runCatching { v.buffer.porcion(desde) }.getOrNull() ?: return false
        if (trozo.isEmpty()) return false
        val fin = pedido + trozo.size - 1
        // DESDE ACÁ NO SE PUEDE VOLVER. En cuanto la cabecera sale por el socket, la respuesta está
        // comprometida: devolver false haría que el passthrough escribiera OTRA respuesta HTTP
        // encima de esta, por la misma conexión. Se descubrió por test —un sondeo servía bien y el
        // de al lado no, sin patrón— y el motivo era justo ese: el reproductor corta a mitad del
        // cuerpo (lee lo que quiere y se va), el `write` fallaba y esto caía al origen habiendo ya
        // contestado. Que el cliente se vaya no es un fallo: es lo normal cuando bisecta.
        runCatching {
            out.write(
                (
                    "HTTP/1.1 206 Partial Content\r\nAccept-Ranges: bytes\r\n" +
                        "Content-Length: ${trozo.size}\r\n" +
                        "Content-Range: bytes $pedido-$fin/$total\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(trozo)
            out.flush()
        }
        android.util.Log.w(
            "ArchiveCacheProxy",
            "ventana de salto: $rangeHeader servido de memoria (${trozo.size / 1024}KB, sin red)",
        )
        return true
    }

    /**
     * Prepara la zona a la que el reproductor va a SALTAR al reanudar, antes de que la pida.
     *
     * El porqué, medido en el Fire TV el 2026-08-13 reanudando una película en 13:29: libVLC abre
     * SIEMPRE en el byte 0 y recién después busca el minuto guardado. Entre una cosa y la otra se
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
        val key = cache.keyFor(originUrl)
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
                android.util.Log.w("ArchiveCacheProxy", "salto: sin tamaño del archivo, no se precalienta")
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
                android.util.Log.w("ArchiveCacheProxy", "salto: el origen no dio el tramo de $inicio")
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
                "salto precalentado: ${buffer.disponible / 1024}KB desde $inicio " +
                    "(destino estimado $destino) en ${System.currentTimeMillis() - t0}ms",
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

    /** Passthrough directo origen→VLC (sin cachear), último recurso si no se pudo iniciar la descarga. */
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
            // que no empiezan en 0: `bytes=0-` es la primera lectura de libVLC —la cabeza— y esa la
            // contesta el arranque caliente, no la cola.
            val enVuelo = colasEnVuelo[claveUnica]
            if (enVuelo != null && colas[claveUnica] == null && (rangoCliente?.start ?: 0L) > 0L) {
                val t = System.currentTimeMillis()
                val llego = enVuelo.await(ESPERA_COLA_EN_VUELO_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                android.util.Log.w(
                    "ArchiveCacheProxy",
                    "cola en vuelo: $rangeHeader esperó ${System.currentTimeMillis() - t}ms " +
                        "(${if (llego) "llegó" else "venció el plazo, voy al origen"})",
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
                if (v != null && servirDeVentana(v, pedido, totalConocido, out, rangeHeader)) return true
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
                    "cola caliente: $rangeHeader servido de memoria (${trozo.size}B, sin red)",
                )
                return true
            }
        }
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
                // le entregan a VLC A MEDIDA QUE LLEGAN del precalentado, sin esperar a que estén
                // los 2 MB completos. Eso es lo único que le importa a libVLC para no rendirse
                // identificando el stream (ver precalentar), y es lo que permite que el arranque
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
                        "arranque caliente: ${servidos / 1024}KB servidos mientras se bajaban",
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
                        "ventana de salto en ${rangoCliente?.start}: " +
                            "${ventana.disponible / 1024}KB guardados tras cortar el reproductor",
                    )
                }
            }
            out.flush()
        } finally {
            ventana?.cerrar()
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
