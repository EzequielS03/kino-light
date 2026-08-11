package com.arkiv.player.playback

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
 * las cabeceras en cada petición al origen. VLC solo ve `127.0.0.1`.
 */
class LiveHlsProxy(private val firmas: FirmaDeSegmentos) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var sesion: LiveSession? = null

    val port: Int get() = server?.localPort ?: -1

    private suspend fun contentAuth(s: LiveSession): String {
        val f = firmas.firmar(s.token)
        return "${s.authBase}&sign2_method=sign_o3&instance=0" +
            "&start_moment=${f.moment}&sign2=${f.sign2}"
    }

    /** Idempotente, igual que [ArchiveCacheProxy.start]. [bindLan] es para el Chromecast. */
    @Synchronized
    fun start(bindLan: Boolean = false): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = if (bindLan) ServerSocket(0) else ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = sock
        running = true
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
    }

    /** Fija la sesión del canal y devuelve la URL que se le pasa a VLC. */
    fun urlPara(nueva: LiveSession): String {
        sesion = nueva
        if (port <= 0) start()
        return "http://127.0.0.1:$port/live.m3u8"
    }

    private fun atender(socket: Socket) = socket.use { s ->
        // Igual que ArchiveCacheProxy.serve() (mismo paquete, mismo patrón): CUALQUIER excepción
        // acá —red, o una sesión que desaparece a mitad de una petición porque el usuario salió
        // del reproductor justo cuando un segmento está a mitad de descarga (ver stop())— se
        // traga y se loguea en vez de dejarla escapar. En Android una excepción sin atrapar en
        // CUALQUIER hilo mata el proceso ENTERO, no solo esta conexión.
        runCatching {
            val entrada = s.getInputStream().bufferedReader()
            val linea = entrada.readLine() ?: return@runCatching
            val ruta = linea.split(" ").getOrNull(1) ?: return@runCatching
            val salida = s.getOutputStream()
            when {
                ruta.startsWith("/live.m3u8") -> servirPlaylist(salida)
                ruta.startsWith("/seg?") -> servirSegmento(ruta, salida)
                else -> salida.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }.onFailure { e ->
            runCatching { android.util.Log.w("LiveHlsProxy", "atender() falló: ${e.message}") }
        }
    }

    /**
     * Pide al origen con la firma vigente y, ante un 403, refresca la firma y reintenta
     * **una** vez. Un 403 que sobrevive al reintento significa que caducó la sesión del
     * canal, no la firma: quien reproduce debe re-resolver (ver [PlayerViewModel]).
     *
     * Recibe [s] ya resuelta (no relee el campo `sesion`): así toda la petición usa la MISMA
     * sesión de punta a punta aunque [stop] (u otro [urlPara]) la cambie desde otro hilo a mitad
     * de camino — es lo que cierra la ventana de carrera del hallazgo C2 (`sesion!!.license` con
     * `sesion` ya nula).
     */
    private fun pedirAlOrigen(url: String, s: LiveSession): HttpURLConnection? {
        repeat(2) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Auth", runBlocking { contentAuth(s) })
                setRequestProperty("Content-License", s.license)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("App", APP)
                setRequestProperty("App-Version", APP_VERSION)
                setRequestProperty("X-Buffer", "0")
            }
            if (c.responseCode != 403) return c
            // El aviso es lo que permite a FirmaConRespaldo detectar que el algoritmo
            // dejó de servir y conmutar al gateway. Sin esto, el respaldo nunca entra.
            firmas.rechazada()
            c.disconnect()
        }
        return null
    }

    private fun error502(salida: java.io.OutputStream) {
        salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    private fun servirPlaylist(salida: java.io.OutputStream) {
        // Una sola lectura de los campos volátiles para TODA la petición: si stop() (o un
        // urlPara() nuevo) cambia `sesion`/`server` desde otro hilo a mitad de camino, esta
        // petición sigue con los valores que tenía al empezar. Ver la nota de [pedirAlOrigen].
        val s = sesion ?: return error502(salida)
        val miPuerto = port
        val urlPlaylist = "http://${s.cflHost}/live/${s.channel}.m3u8"
        val c = pedirAlOrigen(urlPlaylist, s)
        if (c == null || c.responseCode != 200) return error502(salida)
        val base = URL(urlPlaylist)
        val cuerpo = c.inputStream.bufferedReader().readText().lineSequence()
            .joinToString("\n") { ln -> reescribirLinea(ln, base, miPuerto) } + "\n"
        val bytes = cuerpo.toByteArray()
        salida.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        salida.write(bytes)
    }

    private fun servirSegmento(ruta: String, salida: java.io.OutputStream) {
        val s = sesion ?: return error502(salida)
        val u = URLDecoder.decode(ruta.substringAfter("u=").substringBefore("&"), "UTF-8")
        val c = pedirAlOrigen(u, s) ?: return error502(salida)
        salida.write("HTTP/1.1 ${c.responseCode} OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        runCatching { c.inputStream.copyTo(salida, 64 * 1024) }
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
     */
    private fun reescribirLinea(ln: String, base: URL, miPuerto: Int): String {
        val t = ln.trim()
        if (t.isEmpty()) return ln
        if (t.startsWith("#EXT-X-KEY") && t.contains("URI=")) {
            return reescribirUriEnTag(ln, base, miPuerto)
        }
        if (t.startsWith("#")) return ln  // el resto de los tags no llevan URI propia
        val absoluta = runCatching { URL(base, t) }.getOrNull() ?: return ln
        return "http://127.0.0.1:$miPuerto/seg?u=${URLEncoder.encode(absoluta.toString(), "UTF-8")}"
    }

    /** Reescribe SOLO la URI entre comillas de un tag `#EXT-X-KEY:...,URI="..."`, dejando el resto igual. */
    private fun reescribirUriEnTag(ln: String, base: URL, miPuerto: Int): String {
        val m = Regex("URI=\"([^\"]*)\"").find(ln) ?: return ln
        val grupo = m.groups[1] ?: return ln
        val absoluta = runCatching { URL(base, grupo.value) }.getOrNull() ?: return ln
        val nueva = "http://127.0.0.1:$miPuerto/seg?u=${URLEncoder.encode(absoluta.toString(), "UTF-8")}"
        return ln.replaceRange(grupo.range, nueva)
    }

    companion object {
        const val UA = "Ranger/4.9.4-17294ac0"
        private const val APP = "com.android.msandroid"
        private const val APP_VERSION = "49902"
    }
}
