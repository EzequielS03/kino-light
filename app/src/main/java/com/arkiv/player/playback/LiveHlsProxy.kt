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
 * Proxy HLS local para la TV en vivo de Magis.
 *
 * Existe porque VLC solo deja pasar `:http-referrer` y `:http-user-agent`, y el CDN del vivo
 * exige `Content-Auth` y `Content-License`. Además el `Content-Auth` **caduca en segundos**:
 * cada segmento necesita una firma fresca, así que no alcanza con entregarle a VLC un m3u8
 * estático.
 *
 * El proxy baja el playlist, reescribe las URLs absolutas de los `.ts` hacia sí mismo y pone
 * las cabeceras en cada petición al origen. VLC ve `127.0.0.1`; Chromecast/DLNA ven la IP LAN
 * del celu (ver [lanUrl]) porque, desde el primer canal que se abre, el socket escucha en TODAS
 * las interfaces, no solo loopback (ver el KDoc de [start]/[urlPara]). Eso solo, sin nada más,
 * dejaría el canal -contenido pago de Magis- a la vista de cualquier equipo en la misma WiFi que
 * escanee el puerto efímero: por eso cada URL que entrega el proxy ([urlPara], [lanUrl], y las
 * URLs de segmento que el propio proxy reescribe dentro del m3u8) lleva el token aleatorio de
 * [generarToken] como query param, y [atender] lo exige antes de resolver cualquier ruta.
 *
 * [onSesionMuerta] avisa cuando una petición se rinde tras dos 403 seguidos ([pedirAlOrigen]):
 * eso significa que caducó la sesión del canal (token/license), no la firma -ver el KDoc de
 * [pedirAlOrigen]-, así que quien la resolvió (`LiveController`) debe volver a pedirla al gateway
 * la próxima vez, en vez de servir la copia cacheada que ya sabemos muerta hasta que expire sola
 * (hasta 300s; ver `LiveController.vigente`). Sin este aviso el canal queda roto todo ese rato
 * aunque el usuario zapee y vuelva (hallazgo "en la misma ola" de la revisión final).
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
     * en cada zapeo de canal, la URL que YA quedó grabada en VLC o en el media cargado en
     * Chromecast (mismo puerto, ver el KDoc de [urlPara]) empezaría a dar 403 a mitad de
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
     * Fija la sesión del canal y devuelve la URL que se le pasa a VLC.
     *
     * `bindLan = true`: el proxy queda alcanzable por la LAN desde que se abre el PRIMER canal,
     * no solo cuando se castea -- mismo criterio que ya usa el servidor HTTP del torrent
     * (`TorrentStreamServer`, `ServerSocket(0)` sin IP = todas las interfaces). La alternativa
     * -abrir en loopback y "ensanchar" a LAN recién al castear- le cambiaría el PUERTO a mitad de
     * reproducción: esta URL (con el puerto de HOY) ya quedó grabada como el media local de VLC,
     * y en el media cargado para Chromecast/DLNA (ver `LiveHlsProxy.lanUrl`); reabrir el socket en
     * otro puerto rompería ambos. 127.0.0.1 sigue funcionando igual con el socket en todas las
     * interfaces, así que esto no cambia nada para la reproducción local.
     */
    fun urlPara(nueva: LiveSession): String {
        // El ZAPPING empieza acá. Es la marca contra la que se mide todo lo del vivo: de este
        // instante al primer `Vout` de VlcPlayer es lo que el usuario espera mirando negro al
        // cambiar de canal, y sin esta línea el log arranca recién cuando VLC pide el playlist.
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
     * mismo host:puerto que ya usa VLC en local, solo que con la IP del celu en vez de loopback
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
            // sanitizarlo antes de meterlo en una URL de respuesta, y no depende de que VLC,
            // Chromecast o el cliente DLNA manden una cabecera Host bien formada (algunos
            // reproductores HLS no la mandan). Es lo mismo que resolvería a mano leyendo
            // cabeceras, pero sin el riesgo de header injection ni el parsing extra.
            // `hostAddress` es un tipo plataforma (String! de Java): en la práctica nunca es null
            // para una InetAddress ya resuelta como esta, pero el fallback deja el camino local
            // (VLC) intacto ante cualquier corner case en vez de reventar la conexión.
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
                    "$queEs → $code en ${ms}ms" + (if (intento > 0) " (2do intento)" else ""),
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
        val enOrden = (listOfNotNull(cdnActivo) + s.cdns).distinctBy { it.cflHost }
        var c: java.net.HttpURLConnection? = null
        var elegido: CdnDeCanal? = null
        for (cdn in enOrden) {
            c = pedirAlOrigen("http://${cdn.cflHost}/live/${s.playCode}.m3u8", s, cdn)
            if (c != null) { elegido = cdn; break }
            if (cdn !== enOrden.last()) {
                android.util.Log.w("LiveHlsProxy", "playlist: ${cdn.cflHost} rechazó → pruebo el siguiente CDN")
            }
        }
        if (c == null || elegido == null) {
            // Recién ACÁ la sesión está muerta: se agotaron todos los CDN, no solo uno.
            android.util.Log.w(
                "LiveHlsProxy",
                "playlist: los ${enOrden.size} CDN rechazaron → doy la sesión por muerta (canal=${s.channel})",
            )
            onSesionMuerta(s.channel)
            return error502(salida, "el CDN no dio el playlist de ${s.channel}")
        }
        if (cdnActivo?.cflHost != elegido.cflHost) {
            android.util.Log.w("LiveHlsProxy", "CDN activo → ${elegido.cflHost} (canal=${s.channel})")
        }
        cdnActivo = elegido
        val urlPlaylist = "http://${elegido.cflHost}/live/${s.playCode}.m3u8"
        if (c.responseCode != 200) return error502(salida, "playlist con código ${c.responseCode}")
        val base = URL(urlPlaylist)
        val crudo = c.inputStream.bufferedReader().readText()
        val cuerpo = crudo.lineSequence()
            .joinToString("\n") { ln -> reescribirLinea(ln, base, miHost, miPuerto, miToken) } + "\n"
        val bytes = cuerpo.toByteArray()
        // Cuántos segmentos anuncia el playlist es EL dato del vivo: define cuánto colchón hay antes
        // de que el reproductor alcance el borde. Si baja de 2-3, cualquier hipo del CDN corta.
        // `MEDIA-SEQUENCE` dice si la ventana avanza o si estamos releyendo la misma.
        val segmentos = crudo.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
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

    private fun servirSegmento(ruta: String, salida: java.io.OutputStream) {
        val t0 = System.currentTimeMillis()
        val s = sesion ?: return error502(salida, "segmento sin sesión de canal")
        val u = URLDecoder.decode(ruta.substringAfter("u=").substringBefore("&"), "UTF-8")
        val c = pedirAlOrigen(u, s) ?: return error502(salida, "el CDN no dio el segmento")
        salida.write("HTTP/1.1 ${c.responseCode} OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        // Los bytes se cuentan al copiar, no del Content-Length: el CDN puede cortar a mitad y eso
        // se ve como un segmento corto, que es justo lo que deja al reproductor sin datos.
        val copiados = runCatching { c.inputStream.copyTo(salida, 64 * 1024) }.getOrDefault(-1L)
        android.util.Log.w(
            "LiveHlsProxy",
            "segmento servido ${copiados / 1024}KB en ${System.currentTimeMillis() - t0}ms" +
                (if (copiados < 0) " (CORTADO)" else "") + " ${u.substringAfterLast('/')}",
        )
    }

    /**
     * Reescribe una línea del playlist para que cualquier URI que traiga (segmento o clave de
     * cifrado) pase por el proxy en vez de ir directo al CDN.
     *
     * Antes solo se tocaban líneas que empezaban literalmente con `"http"` y contenían `".ts"`.
     * Eso dejaba afuera (hallazgo I1):
     * - segmentos RELATIVOS (`c_1.ts`): VLC los resuelve contra `127.0.0.1`, una ruta que el
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
     * dispositivo, no el celu. Pantalla negra sin ningún error. VLC sigue sirviéndose de
     * `127.0.0.1` igual que antes porque pide el playlist por loopback (ver [urlPara]), así que
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
    }
}
