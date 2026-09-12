package com.arkiv.player.playback

import com.arkiv.player.data.gateway.CdnDeCanal
import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Local HLS proxy for Magis live TV.
 *
 * Exists because the live CDN requires `Content-Auth` and `Content-License` headers, and
 * `Content-Auth` **expires in seconds**: every segment needs a fresh signature, so handing the
 * player a static m3u8 was never going to be enough (this was originally built because libVLC
 * could only pass through `:http-referrer` and `:http-user-agent`; ExoPlayer, today's player,
 * can set arbitrary headers itself, but the proxy stays because the per-segment fresh signing
 * still has to happen somewhere).
 *
 * The proxy downloads the playlist, rewrites the `.ts` absolute URLs to point at itself, and sets
 * the headers on every request to the origin. The local player sees `127.0.0.1`; Chromecast/DLNA
 * see the phone's LAN IP (see [lanUrl]) because, from the first channel that opens, the socket
 * listens on ALL interfaces, not just loopback (see the KDoc of [start]/[urlPara]). That alone,
 * with nothing else, would leave the channel -- Magis's paid content -- visible to any device on
 * the same WiFi that scans the ephemeral port: that's why every URL the proxy hands out
 * ([urlPara], [lanUrl], and the segment URLs the proxy itself rewrites inside the m3u8) carries
 * [generarToken]'s random token as a query param, and [atender] requires it before resolving any
 * route.
 *
 * [onSesionMuerta] fires when a request gives up after two 403s in a row ([pedirAlOrigen]): that
 * means the channel's session (token/license) expired, not the signature -- see the KDoc of
 * [pedirAlOrigen] -- so whoever resolved it (`LiveController`) must ask the gateway for it again
 * next time, instead of serving the cached copy that's already known to be dead until it expires
 * on its own (up to 300s; see `LiveController.vigente`). Without this notice the channel stays
 * broken that whole time even if the user zaps away and back (a finding from "the same wave" of
 * the final review).
 */
class LiveHlsProxy(
    private val firmas: FirmaDeSegmentos,
    private val onSesionMuerta: (canal: String) -> Unit = {},
) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var sesion: LiveSession? = null

    /**
     * Token aleatorio de la sesión de reproducción actual (ver [generarToken]). Vive tanto como
     * el `ServerSocket`: nace en [start] y muere en [stop], NO en cada [urlPara] -- si cambiara
     * en cada zapeo de canal, la URL que YA quedó grabada en el reproductor local o en el media
     * cargado en Chromecast (mismo puerto, ver el KDoc de [urlPara]) empezaría a dar 403 a mitad de
     * reproducción.
     */
    @Volatile private var token: String? = null

    val port: Int get() = server?.localPort ?: -1

    /**
     * El CDN que está sirviendo este canal ahora mismo. Cambia cuando el primero rechaza y se
     * cae al siguiente (ver [servirPlaylist]).
     *
     * Los SEGMENTOS lo necesitan tanto como el playlist: sus urls salen del playlist, así que
     * apuntan al host que lo sirvió, y firmarlas con el `authBase` de otro CDN sería el mismo par
     * cruzado —token de uno, host de otro— que el CDN rechaza con 401.
     */
    @Volatile private var cdnActivo: CdnDeCanal? = null

    /**
     * Nombres de los segmentos del último playlist servido, en orden. Existe SOLO para el log.
     *
     * Sin esto, un `segmento → 404` no dice en qué parte de la ventana estaba, y esa es justo la
     * pregunta que no pudimos contestar en toda la noche del 2026-08-14: si el que falta es
     * siempre el último —el borde que el origen anuncia antes de escribir— o si son salteados. Un
     * "posición 6/6" repetido significa una cosa y un "3/6" otra completamente distinta.
     */
    @Volatile private var ultimosSegmentos: List<String> = emptyList()

    /** "4/6" si el segmento está en el último playlist servido, "?/N" si ya no. Para el log. */
    private fun posicionEnPlaylist(nombre: String): String {
        val lista = ultimosSegmentos
        val i = lista.indexOf(nombre)
        return if (i >= 0) "${i + 1}/${lista.size}" else "?/${lista.size}"
    }

    private fun cdnDe(s: LiveSession): CdnDeCanal =
        cdnActivo ?: s.cdns.firstOrNull() ?: CdnDeCanal(s.cflHost, s.authBase)

    private suspend fun contentAuth(s: LiveSession, cdn: CdnDeCanal = cdnDe(s)): String {
        val f = firmas.firmar(cdn.token)
        return "${cdn.authBase}&sign2_method=sign_o3&instance=0" +
            "&start_moment=${f.moment}&sign2=${f.sign2}"
    }

    /** Idempotente, igual que [ArchiveCacheProxy.start]. [bindLan] es para el Chromecast. */
    @Synchronized
    fun start(bindLan: Boolean = false): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = if (bindLan) ServerSocket(0) else ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = sock
        running = true
        token = generarToken()
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
        token = null
    }

    /**
     * Token aleatorio por sesión de reproducción, de fuente criptográficamente segura -no fijo,
     * no derivado de nada predecible (ni del canal, ni del puerto, ni de la hora)-. Es el único
     * control de acceso desde que [start] pasó a escuchar en toda la LAN (`0.0.0.0`) en vez de
     * solo loopback: sin esto, cualquier equipo en la misma WiFi que escanee el puerto efímero
     * mira el canal -contenido pago de Magis- sin más.
     *
     * Hex de 24 bytes de [java.security.SecureRandom] en vez de `java.util.Base64`/
     * `android.util.Base64`: ni depende de desugaring para el primero, ni revienta en los tests
     * JVM puros (que no mockean `android.util.*` salvo que algo lo atrape, como sí se hace con
     * `Log.w` en [atender]) para el segundo.
     */
    private fun generarToken(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Sets the channel session and returns the URL handed to the player.
     *
     * `bindLan = true`: the proxy becomes reachable over the LAN as soon as the FIRST channel
     * opens, not only when casting -- same criterion the torrent HTTP server used (source removed
     * in this branch's pruning), with `ServerSocket(0)` and no IP = all interfaces. The
     * alternative -- opening on loopback and "widening" to LAN only when casting starts -- would
     * change the PORT mid-playback: this URL (with TODAY's port) is already recorded as the
     * player's local media, and in the media loaded for Chromecast/DLNA (see
     * `LiveHlsProxy.lanUrl`); reopening the socket on another port would break both. 127.0.0.1
     * keeps working the same with the socket on all interfaces, so this changes nothing for local
     * playback.
     */
    fun urlPara(nueva: LiveSession): String {
        // ZAPPING starts here. It's the mark everything about live playback gets measured against:
        // from this instant to the first frame actually painted is what the user waits looking at
        // black when switching channels, and without this line the log would only start once the
        // player requests the playlist.
        val anterior = sesion?.channel
        android.util.Log.w(
            "LiveHlsProxy",
            "canal → ${nueva.channel}" + (if (anterior != null && anterior != nueva.channel) " (venía de $anterior)" else "") +
                " cdn=${nueva.cflHost}" + (if (nueva.cdns.size > 1) " (+${nueva.cdns.size - 1} de respaldo)" else ""),
        )
        sesion = nueva
        // El CDN elegido es de la sesión ANTERIOR: sus tokens no valen para este canal, y peor,
        // el host podría ni servirlo. Cada canal vuelve a elegir desde el principio.
        cdnActivo = null
        if (port <= 0) start(bindLan = true)
        return "http://127.0.0.1:$port/live.m3u8?t=$token"
    }

    /**
     * URL del canal que el proxy sirve AHORA MISMO, alcanzable por la LAN (Chromecast/DLNA) --
     * mismo host:puerto que ya usa localmente el reproductor, solo que con la IP del celu en vez de loopback
     * (ver el KDoc de [urlPara]: el socket escucha en todas las interfaces desde el primer canal
     * abierto, así que no hace falta "ensanchar" nada acá). `null` si todavía no se abrió ningún
     * canal -no hay nada que castear-.
     */
    fun lanUrl(ip: String): String? {
        if (port <= 0) return null
        val t = token ?: return null
        return "http://$ip:$port/live.m3u8?t=$t"
    }

    private fun atender(socket: Socket) = socket.use { s ->
        // Igual que ArchiveCacheProxy.serve() (mismo paquete, mismo patrón): CUALQUIER excepción
        // acá —red, o una sesión que desaparece a mitad de una petición porque el usuario salió
        // del reproductor justo cuando un segmento está a mitad de descarga (ver stop())— se
        // traga y se loguea en vez de dejarla escapar. En Android una excepción sin atrapar en
        // CUALQUIER hilo mata el proceso ENTERO, no solo esta conexión.
        runCatching {
            // Host por el que ESTE cliente llegó al proxy: la dirección local del socket ya
            // aceptado, no la cabecera `Host` del request. Se prefiere esto a parsear `Host`
            // porque `socket.localAddress` es un hecho de la conexión TCP -qué interfaz recibió
            // el paquete-, no un dato que declara el cliente: no hace falta validarlo ni
            // sanitizarlo antes de meterlo en una URL de respuesta, y no depende de que el
            // reproductor local, Chromecast o el cliente DLNA manden una cabecera Host bien
            // formada (algunos reproductores HLS no la mandan). Es lo mismo que resolvería a mano
            // leyendo cabeceras, pero sin el riesgo de header injection ni el parsing extra.
            // `hostAddress` es un tipo plataforma (String! de Java): en la práctica nunca es null
            // para una InetAddress ya resuelta como esta, pero el fallback deja el camino local
            // intacto ante cualquier corner case en vez de reventar la conexión.
            val miHost = s.localAddress.hostAddress ?: "127.0.0.1"
            val entrada = s.getInputStream().bufferedReader()
            val linea = entrada.readLine() ?: return@runCatching
            val ruta = linea.split(" ").getOrNull(1) ?: return@runCatching
            val salida = s.getOutputStream()
            // Control de acceso: desde que start() escucha en toda la LAN (ver su KDoc), CUALQUIER
            // ruta -playlist o segmento- tiene que traer el token de esta sesión ANTES de que se
            // resuelva nada. Rechazo limpio y genérico (403, sin cuerpo): no hay que darle a quien
            // escanea el puerto ninguna pista de qué rutas existen o por qué falló.
            if (!tokenValido(ruta)) {
                salida.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return@runCatching
            }
            when {
                ruta.startsWith("/live.m3u8") -> servirPlaylist(salida, miHost)
                ruta.startsWith("/seg?") -> servirSegmento(ruta, salida)
                else -> salida.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }.onFailure { e ->
            runCatching { android.util.Log.w("LiveHlsProxy", "atender() falló: ${e.message}") }
        }
    }

    /** Extrae el valor de un parámetro de query de una ruta tipo `/live.m3u8?t=...&otro=...`. */
    private fun valorDeQuery(ruta: String, clave: String): String? {
        val query = ruta.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.startsWith("$clave=") }?.substringAfter('=')
    }

    /**
     * Compara el token recibido contra el de la sesión actual. `MessageDigest.isEqual` en vez de
     * `==`: comparación en tiempo constante, para no filtrar por timing cuánto del token acertó
     * quien está probando a ciegas.
     */
    private fun tokenValido(ruta: String): Boolean {
        val esperado = token ?: return false
        val recibido = valorDeQuery(ruta, "t") ?: return false
        return java.security.MessageDigest.isEqual(recibido.toByteArray(), esperado.toByteArray())
    }

    /**
     * Pide al origen con la firma vigente y, ante un 403, refresca la firma y reintenta
     * **una** vez. Un 403 que sobrevive al reintento significa que caducó la sesión del
     * canal, no la firma: se avisa por [onSesionMuerta] para que quien reproduce re-resuelva.
     *
     * Recibe [s] ya resuelta (no relee el campo `sesion`): así toda la petición usa la MISMA
     * sesión de punta a punta aunque [stop] (u otro [urlPara]) la cambie desde otro hilo a mitad
     * de camino — es lo que cierra la ventana de carrera del hallazgo C2 (`sesion!!.license` con
     * `sesion` ya nula).
     *
     * Un solo 403 cuenta como UN rechazo, sin importar cuántos intentos de HTTP haga esta función
     * para resolverlo: antes se llamaba a `firmas.rechazada()` una vez POR INTENTO (hasta dos
     * veces acá dentro), así que un único 403 -por ejemplo el caso "caducó la sesión, no la
     * firma"- ya empujaba el contador de [FirmaConRespaldo] dos pasos de una, disparando el
     * respaldo con solo la MITAD de los rechazos reales que haría falta ver (hallazgo F1 de la
     * revisión final). [avisado] evita eso.
     */
    private fun pedirAlOrigen(url: String, s: LiveSession, cdn: CdnDeCanal = cdnDe(s)): HttpURLConnection? {
        var avisado = false
        // El QUÉ del log: de este CDN no sabemos nada todavía (el de VOD tarda entre 0,2 s y 20 s
        // por rango, medido; el de vivo nunca se midió). Sin la latencia por petición no hay forma
        // de saber si un corte es del CDN, del proxy o del reproductor -- las tres se ven igual.
        val queEs = if (url.endsWith(".m3u8")) "playlist" else "segmento"
        repeat(2) { intento ->
            val t0 = System.currentTimeMillis()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Auth", runBlocking { contentAuth(s, cdn) })
                setRequestProperty("Content-License", s.license)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("App", APP)
                setRequestProperty("App-Version", APP_VERSION)
                setRequestProperty("X-Buffer", "0")
            }
            val code = c.responseCode
            val ms = System.currentTimeMillis() - t0
            // 401 y 403 los dos: este CDN usa 401 y mirar solo el 403 dejaba la firma dada por
            // buena, el respaldo sin conmutar y el canal muerto en un 502. Ver [esRechazoDeFirma].
            if (!esRechazoDeFirma(code)) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "$queEs → $code en ${ms}ms" + (if (intento > 0) " (2do intento)" else "") +
                        // El PORQUÉ del rechazo, que hasta ahora se tiraba a la basura. Un "→ 409"
                        // pelado no distingue "la señal no existe" de "esta sesión ya no vale", y
                        // sin eso no se puede hacer más que adivinar: el 2026-08-14 un canal daba
                        // 409 en el playlist y 404 en los segmentos mientras los otros cuatro
                        // andaban perfecto, y el log no alcanzaba para decir por qué.
                        // El cuerpo de un no-200 no lo lee nadie más (el playlist corta con
                        // error502 y [pedirOk] descarta la conexión), así que consumirlo acá no le
                        // saca nada a nadie.
                        (if (code != 200) " · ${motivoDelOrigen(c)}" else ""),
                )
                // Avisa que la firma usada en ESTA petición fue aceptada: es la señal que
                // FirmaConRespaldo necesita para reiniciar su contador de rechazos seguidos.
                firmas.aceptada()
                return c
            }
            // 401/403 = la firma no sirvió. Se registra aparte porque es el fallo CARO: dos
            // intentos y después la sesión se da por muerta, o sea que el canal se corta.
            android.util.Log.w("LiveHlsProxy", "$queEs → $code FIRMA RECHAZADA en ${ms}ms (intento ${intento + 1}/2)")
            // El aviso es lo que permite a FirmaConRespaldo detectar que el algoritmo
            // dejó de servir y conmutar al gateway. Sin esto, el respaldo nunca entra.
            if (!avisado) { firmas.rechazada(); avisado = true }
            c.disconnect()
        }
        android.util.Log.w(
            "LiveHlsProxy",
            "$queEs: dos rechazos seguidos en ${cdn.cflHost} (canal=${s.channel})",
        )
        return null
    }

    /**
     * Lo que el CDN dijo al rechazar, recortado para el log.
     *
     * Se queda con el cuerpo del error si lo hay (el portal contesta JSON con su propio código de
     * error, que es el dato que sirve) y si no, con la primera cabecera que explique algo. Nunca
     * tira: esto corre en el camino de un rechazo, y romper acá cambiaría un canal que falla por
     * uno que además pierde la conexión.
     */
    private fun motivoDelOrigen(c: HttpURLConnection): String = runCatching {
        val cuerpo = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
        val dice = if (cuerpo.isNotEmpty()) {
            cuerpo.take(300).replace('\n', ' ')
        } else {
            c.responseMessage ?: "sin cuerpo"
        }
        // Y de QUIÉN salió. Los dos CDN de vivo están detrás de Cloudflare (verificado el
        // 2026-08-14: 104.18.x.x, `Server: cloudflare`), así que un rechazo puede venir del origen
        // o del borde. La diferencia decide todo: si un 404 llega `HIT`, es una respuesta negativa
        // CACHEADA y reintentar la misma URL no puede funcionar por más veces que se pida --
        // habría que esquivar el caché, no insistir.
        "$dice [cf-cache=${c.getHeaderField("cf-cache-status") ?: "-"}" +
            " age=${c.getHeaderField("age") ?: "-"} ray=${c.getHeaderField("cf-ray") ?: "-"}]"
    }.getOrDefault("no se pudo leer el motivo")

    private fun error502(salida: java.io.OutputStream, motivo: String = "") {
        // El 502 es lo ÚNICO que ve el reproductor pase lo que pase acá adentro, así que el motivo
        // tiene que quedar del lado del proxy o se pierde. Es el mismo problema que ArchiveCacheProxy
        // resolvió anotando el último código HTTP por origen.
        if (motivo.isNotEmpty()) android.util.Log.w("LiveHlsProxy", "502 al reproductor: $motivo")
        salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    private fun servirPlaylist(salida: java.io.OutputStream, miHost: String) {
        // Una sola lectura de los campos volátiles para TODA la petición: si stop() (o un
        // urlPara() nuevo) cambia `sesion`/`server` desde otro hilo a mitad de camino, esta
        // petición sigue con los valores que tenía al empezar. Ver la nota de [pedirAlOrigen].
        val t0 = System.currentTimeMillis()
        val s = sesion ?: return error502(salida, "no hay sesión de canal")
        val miPuerto = port
        val miToken = token ?: return error502(salida, "no hay token del proxy")
        // `playCode`, NO `channel`: así se llama la señal en el CDN, y no siempre son lo mismo
        // (ver el KDoc de [LiveSession.playCode] — `cyx-RCNHD` se sirve con otro nombre). Con el
        // código del canal acá, el CDN recibía un pedido por una señal distinta de la que
        // autoriza la licencia que le mandamos y contestaba 401: el canal cargaba para siempre.
        // Se prueban los CDN en orden hasta que uno sirva. Medido el 2026-08-14: el portal da
        // TRES hosts de vivo y usábamos solo el primero; ese día contestó 401 dos veces y el canal
        // se terminó teniendo otro disponible en la misma respuesta. El que gana queda como
        // [cdnActivo] para que los segmentos —cuyas urls salen de ESTE playlist— se firmen con su
        // mismo `authBase`.
        //
        // Empieza por el que ya estaba andando, si hay: reordenar en cada petición haría que un
        // hipo del primero mandara todo el canal de vuelta a él en el siguiente refresco.
        // Y se REINTENTA, igual que los segmentos. Un playlist que no llega no es una sesión
        // muerta: el 2026-08-14 el origen de `cyx-RCNHD` contestó 404 al playlist cuatro veces
        // seguidas durante 9 s —con el canal reproduciendo perfecto hasta el segundo 39— y volvió
        // solo después. La app original hace exactamente esto: su ffmpeg recarga un playlist
        // insuficiente hasta `max_reload` veces (1000 por defecto; el literal está en
        // `libijkffmpeg.so`) en vez de dar el canal por terminado al primer tropiezo.
        //
        // Y un código != 200 ahora también hace pasar al siguiente CDN. Antes no: `pedirAlOrigen`
        // devuelve la conexión ante cualquier código que no sea rechazo de firma, así que un 404
        // cortaba el bucle en el primer CDN y se contestaba 502 sin haber probado el respaldo —el
        // mismo agujero que tenían los segmentos—.
        val enOrden = (listOfNotNull(cdnActivo) + s.cdns).distinctBy { it.cflHost }
        var c: java.net.HttpURLConnection? = null
        var elegido: CdnDeCanal? = null
        var algunoContesto = false
        var ultimoCodigo = -1
        bucle@ for (vuelta in 0 until INTENTOS_PLAYLIST) {
            var contestoAlguno = false
            for (cdn in enOrden) {
                val r = pedirAlOrigen("http://${cdn.cflHost}/live/${s.playCode}.m3u8", s, cdn)
                if (r == null) {
                    if (cdn !== enOrden.last()) {
                        android.util.Log.w("LiveHlsProxy", "playlist: ${cdn.cflHost} rechazó → pruebo el siguiente CDN")
                    }
                    continue
                }
                contestoAlguno = true
                algunoContesto = true
                ultimoCodigo = r.responseCode
                if (ultimoCodigo == 200) { c = r; elegido = cdn; break@bucle }
                runCatching { r.disconnect() }
            }
            // Si NADIE contestó, fueron rechazos de firma en todos los CDN: reintentar no lo va a
            // arreglar —la credencial no mejora sola— y además inflaría el contador de rechazos de
            // [FirmaConRespaldo], que cuenta UN rechazo por petición del proxy y no por intento
            // HTTP (hallazgo F1; ver el KDoc de [pedirAlOrigen]). Se sale con el comportamiento de
            // siempre: sesión muerta.
            if (!contestoAlguno) break@bucle
            if (vuelta < INTENTOS_PLAYLIST - 1) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "playlist de ${s.channel} sin servir (último $ultimoCodigo) → " +
                        "reintento ${vuelta + 2}/$INTENTOS_PLAYLIST en ${ESPERA_SEGMENTO_MS}ms",
                )
                Thread.sleep(ESPERA_SEGMENTO_MS)
            }
        }
        if (c == null || elegido == null) {
            // La sesión se da por muerta SOLO si nadie llegó a contestar: eso es rechazo de firma
            // en todos los CDN. Un 404 es otra cosa —el CDN habló, y dijo que ahora no— y volver a
            // pedir la sesión al gateway por eso sería tratar un bache como una credencial vencida.
            if (!algunoContesto) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "playlist: los ${enOrden.size} CDN rechazaron → doy la sesión por muerta (canal=${s.channel})",
                )
                onSesionMuerta(s.channel)
            }
            return error502(salida, "ningún CDN dio el playlist de ${s.channel} (último código $ultimoCodigo)")
        }
        if (cdnActivo?.cflHost != elegido.cflHost) {
            android.util.Log.w("LiveHlsProxy", "CDN activo → ${elegido.cflHost} (canal=${s.channel})")
        }
        cdnActivo = elegido
        val urlPlaylist = "http://${elegido.cflHost}/live/${s.playCode}.m3u8"
        val base = URL(urlPlaylist)
        val crudo = c.inputStream.bufferedReader().readText()
        val cuerpo = crudo.lineSequence()
            .joinToString("\n") { ln -> reescribirLinea(ln, base, miHost, miPuerto, miToken) } + "\n"
        val bytes = cuerpo.toByteArray()
        // Cuántos segmentos anuncia el playlist es EL dato del vivo: define cuánto colchón hay antes
        // de que el reproductor alcance el borde. Si baja de 2-3, cualquier hipo del CDN corta.
        // `MEDIA-SEQUENCE` dice si la ventana avanza o si estamos releyendo la misma.
        val nombres = crudo.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringAfterLast('/').substringBefore('?') }.toList()
        ultimosSegmentos = nombres
        val segmentos = nombres.size
        val secuencia = crudo.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }?.substringAfter(':') ?: "?"
        android.util.Log.w(
            "LiveHlsProxy",
            "playlist servido canal=${s.channel} segmentos=$segmentos seq=$secuencia " +
                "${bytes.size}B en ${System.currentTimeMillis() - t0}ms",
        )
        salida.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        salida.write(bytes)
    }

    /**
     * Un segmento del vivo.
     *
     * **Nunca contesta un cuerpo vacío detrás de una cabecera de éxito.** Antes se escribía la
     * cabecera con el código del CDN tal cual (`HTTP/1.1 404 OK`) y recién después se intentaba
     * copiar `inputStream`, que en un 404 tira excepción: al reproductor le llegaban CERO bytes,
     * que es exactamente como se ve el final de un stream. Medido en el Fire TV el 2026-08-14 con
     * RCN FHD: un único 404 y VLC drenó el decoder y emitió `EndReached` a los 19 s, con el canal
     * perfectamente vivo (el playlist seguía refrescando, seq 2080 → 2082). Un 502 en cambio es un
     * ERROR, y un error el reproductor lo reintenta.
     *
     * Es la resiliencia que la app original tiene de arriba: va DIRECTO al CDN, así que un 404 le
     * llega como error y ffmpeg reconecta (`reconnect=1`, `reconnect_delay_max=5` en su
     * configuración de ijkplayer). Con un proxy en el medio, eso hay que reponerlo a mano.
     */
    private fun servirSegmento(ruta: String, salida: java.io.OutputStream) {
        val t0 = System.currentTimeMillis()
        val s = sesion ?: return error502(salida, "segmento sin sesión de canal")
        val u = URLDecoder.decode(ruta.substringAfter("u=").substringBefore("&"), "UTF-8")
        val nombre = u.substringAfterLast('/')
        val c = conseguirSegmento(u, s)
            ?: return error502(salida, "ningún CDN dio el segmento $nombre (posición ${posicionEnPlaylist(nombre)})")
        // La cabecera se escribe RECIÉN ACÁ, con un 200 en la mano. Una vez escrita ya no se puede
        // convertir en error: por eso no puede salir antes de saber que hay cuerpo.
        salida.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        // Los bytes se cuentan al copiar, no del Content-Length: el CDN puede cortar a mitad y eso
        // se ve como un segmento corto, que es justo lo que deja al reproductor sin datos.
        val copiados = runCatching { c.inputStream.copyTo(salida, 64 * 1024) }.getOrDefault(-1L)
        android.util.Log.w(
            "LiveHlsProxy",
            "segmento servido ${copiados / 1024}KB en ${System.currentTimeMillis() - t0}ms" +
                (if (copiados < 0) " (CORTADO)" else "") + " $nombre",
        )
    }

    /**
     * El segmento pedido, ya con código 200, o `null` si de verdad no está en ninguna parte.
     *
     * Dos escalones, en este orden:
     *  1. **reintentos contra el CDN activo**, que es el caso normal: en el borde del vivo el
     *     reproductor pide el segmento ANTES de que el CDN lo publique. El 2026-08-14 VLC pidió
     *     tres segmentos de ~5 s de video con 1,3 s de diferencia entre sí — venía corriendo hacia
     *     el borde — y el tercero dio 404 sencillamente porque todavía no existía;
     *  2. **los otros CDN del canal**, por si el que estamos usando se quedó atrás. El camino del
     *     playlist ya los recorre así ([servirPlaylist]); el de segmentos no lo hacía y el
     *     respaldo quedaba ahí sin usarse.
     *
     * Las esperas salen del tamaño del segmento: duran ~5 s y el playlist anuncia 6, o sea ~30 s
     * de colchón. Gastar hasta ~1 s esperando a que se publique no vacía nada; darlo por perdido
     * de una, sí.
     */
    private fun conseguirSegmento(url: String, s: LiveSession): HttpURLConnection? {
        val nombre = url.substringAfterLast('/')
        repeat(INTENTOS_SEGMENTO) { intento ->
            val c = pedirOk(url, s)
            if (c != null) return c
            if (intento < INTENTOS_SEGMENTO - 1) {
                android.util.Log.w(
                    "LiveHlsProxy",
                    "segmento $nombre (posición ${posicionEnPlaylist(nombre)} del playlist) no está " +
                        "todavía → reintento ${intento + 2}/$INTENTOS_SEGMENTO en ${ESPERA_SEGMENTO_MS}ms",
                )
                Thread.sleep(ESPERA_SEGMENTO_MS)
            }
        }
        // El CDN activo no lo tiene. Los segmentos vienen del playlist de ESE host, así que para
        // pedírselo a otro hay que cambiarle el host a la url (la firma ya va por CDN, que es el
        // parámetro `cdn` de [pedirAlOrigen]).
        //
        // Se reemplaza la AUTORIDAD entera, no el host: `cflHost` trae el puerto pegado cuando lo
        // hay (por eso el playlist se arma como "http://${cdn.cflHost}/live/..."), así que pasarlo
        // como `host` al constructor de URL deja una url con dos puertos y no resuelve.
        val otros = s.cdns.filter { it.cflHost != cdnDe(s).cflHost }
        for (cdn in otros) {
            val alterna = runCatching {
                url.replaceFirst("://${URL(url).authority}", "://${cdn.cflHost}")
            }.getOrNull() ?: continue
            android.util.Log.w("LiveHlsProxy", "segmento $nombre → pruebo el CDN ${cdn.cflHost}")
            val c = pedirOk(alterna, s, cdn)
            if (c != null) return c
        }
        return null
    }

    /**
     * [pedirAlOrigen] pero devolviendo solo respuestas 200 servibles, y sin dejar escapar
     * excepciones.
     *
     * Las dos cosas apuntan a lo mismo: que un fallo del CDN llegue al reproductor como ERROR y
     * nunca como fin de stream. `pedirAlOrigen` devuelve la conexión ante CUALQUIER código que no
     * sea rechazo de firma —404 incluido—, y un CDN caído tira excepción al conectar, que sin este
     * `runCatching` se lleva puesta la respuesta entera y el reproductor ve la conexión cortada.
     */
    private fun pedirOk(url: String, s: LiveSession, cdn: CdnDeCanal = cdnDe(s)): HttpURLConnection? {
        val c = runCatching { pedirAlOrigen(url, s, cdn) }.getOrNull() ?: return null
        if (runCatching { c.responseCode }.getOrDefault(-1) == 200) return c
        runCatching { c.disconnect() }
        return null
    }

    /**
     * Reescribe una línea del playlist para que cualquier URI que traiga (segmento o clave de
     * cifrado) pase por el proxy en vez de ir directo al CDN.
     *
     * Antes solo se tocaban líneas que empezaban literalmente con `"http"` y contenían `".ts"`.
     * Eso dejaba afuera (hallazgo I1):
     * - segmentos RELATIVOS (`c_1.ts`): el reproductor los resuelve contra `127.0.0.1`, una ruta que el
     *   proxy no maneja → 404 y la reproducción se corta;
     * - segmentos protocol-relative (`//cdn.host/c_1.ts`): se resuelven DIRECTO contra el CDN,
     *   sin firma;
     * - la URI de `#EXT-X-KEY` (si el stream viene cifrado): la línea empieza con `#`, nunca
     *   matcheaba, y la clave se hubiera pedido al CDN sin firmar.
     *
     * `URL(base, spec)` resuelve las tres formas de URI (absoluta, protocol-relative, relativa)
     * exactamente como lo haría un navegador, así que no hace falta reinventar esa lógica a mano.
     *
     * [miHost] es el host por el que ESTE cliente pidió el playlist (ver [atender]), no un
     * `127.0.0.1` fijo (hallazgo del agente anterior, Tarea 20): si las URLs de segmento SIEMPRE
     * quedaran en loopback, Chromecast/DLNA -que piden el playlist por la IP LAN del celu, ver
     * [lanUrl]- recibirían segmentos apuntando a `127.0.0.1`, que para ELLOS es su propio
     * dispositivo, no el celu. Pantalla negra sin ningún error. El reproductor local sigue
     * sirviéndose de `127.0.0.1` igual que antes porque pide el playlist por loopback (ver [urlPara]), así que
     * `miHost` le llega como `"127.0.0.1"` sin cambiar nada.
     */
    private fun reescribirLinea(ln: String, base: URL, miHost: String, miPuerto: Int, miToken: String): String {
        val t = ln.trim()
        if (t.isEmpty()) return ln
        if (t.startsWith("#EXT-X-KEY") && t.contains("URI=")) {
            return reescribirUriEnTag(ln, base, miHost, miPuerto, miToken)
        }
        if (t.startsWith("#")) return ln  // el resto de los tags no llevan URI propia
        val absoluta = runCatching { URL(base, t) }.getOrNull() ?: return ln
        // El token va DESPUÉS de u= (nunca antes): servirSegmento() extrae u con
        // `substringBefore("&")`, así que cualquier parámetro nuevo tiene que ir a continuación.
        return "http://$miHost:$miPuerto/seg?u=${URLEncoder.encode(absoluta.toString(), "UTF-8")}&t=$miToken"
    }

    /** Reescribe SOLO la URI entre comillas de un tag `#EXT-X-KEY:...,URI="..."`, dejando el resto igual. */
    private fun reescribirUriEnTag(ln: String, base: URL, miHost: String, miPuerto: Int, miToken: String): String {
        val m = Regex("URI=\"([^\"]*)\"").find(ln) ?: return ln
        val grupo = m.groups[1] ?: return ln
        val absoluta = runCatching { URL(base, grupo.value) }.getOrNull() ?: return ln
        val nueva = "http://$miHost:$miPuerto/seg?u=${URLEncoder.encode(absoluta.toString(), "UTF-8")}&t=$miToken"
        return ln.replaceRange(grupo.range, nueva)
    }

    companion object {
        const val UA = "Ranger/4.9.4-17294ac0"
        private const val APP = "com.android.msandroid"
        private const val APP_VERSION = "49902"

        /**
         * Cuántas veces se le pide el MISMO segmento al CDN activo antes de buscar en otro, y
         * cuánto se espera entre intentos.
         *
         * El tope sale de MEDIR, no del presupuesto de la app original. Ella le da a ffmpeg
         * `reconnect_delay_max=5` (ver `yc/C6276a.java` del decompilado) y por un rato esto estuvo
         * en seis intentos para igualarlo — pero ese número es para **reconectar una red que se
         * cayó**, no para esperar bytes que no existen, y acá el fallo es lo segundo.
         *
         * Lo medido en el Fire TV el 2026-08-14, con cinco segmentos que dieron 404: **ninguno**
         * llegó dentro de la ventana de 5 s. Cuatro no aparecieron nunca —pedidos de nuevo 30 s
         * después seguían en 404— y el quinto tardó 13 s, inalcanzable con cualquier tope sensato.
         * O sea que estirar la ventana no compra nada: o el segmento está enseguida, o no está.
         *
         * Quedan tres intentos porque siguen cubriendo lo único que la espera puede arreglar —un
         * segmento que se publica con un pestañeo de retraso— y porque el que de verdad rescata el
         * canal es otro: [PlayerViewModel.reabrirVivoPorCorte], que reengancha en el borde del
         * vivo en 2 s. Cuanto antes se le devuelva el control, antes vuelve la imagen.
         */
        private const val INTENTOS_SEGMENTO = 3
        private const val ESPERA_SEGMENTO_MS = 800L

        /**
         * Vueltas completas a TODOS los CDN buscando el playlist. Menos que las de un segmento a
         * propósito: el reproductor reintenta el playlist por su cuenta (VLC lo pidió cada ~3 s el
         * 2026-08-14), así que acá alcanza con cubrir el bache corto y devolverle el control antes
         * de que se canse.
         */
        private const val INTENTOS_PLAYLIST = 3
    }
}
