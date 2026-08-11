package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class LiveHlsProxyTest {
    /** Firma predecible, para poder afirmar qué `Content-Auth` salió en cada petición. */
    private class FirmasFalsas : FirmaDeSegmentos {
        var entregadas = 0
        var rechazos = 0
        var aceptaciones = 0
        override suspend fun firmar(token: String): LiveSignature {
            entregadas++
            return LiveSignature(1000L, "firma%02d".format(entregadas))
        }
        override fun rechazada() { rechazos++ }
        override fun aceptada() { aceptaciones++ }
    }

    private fun leer(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val cuerpo = runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
        return c.responseCode to cuerpo
    }

    @Test
    fun `reescribe los segmentos absolutos hacia el propio proxy`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n#EXTINF:6,\nhttp://seg2.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val puerto = proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (codigo, cuerpo) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertTrue(cuerpo.contains("http://127.0.0.1:$puerto/seg?u="))
        assertTrue("no debe quedar ninguna URL del CDN sin reescribir", !cuerpo.contains("seg1.cdn/live"))
        assertEquals(2, cuerpo.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `pone las tres cabeceras al pedir el playlist`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        leer(proxy.urlPara(sesion))

        val req = upstream.takeRequest()
        assertEquals("LIC", req.getHeader("Content-License"))
        assertEquals("Ranger/4.9.4-17294ac0", req.getHeader("User-Agent"))
        val auth = req.getHeader("Content-Auth")!!
        assertTrue(auth.contains("sign2_method=sign_o3"))
        assertTrue(auth.contains("start_moment=1000"))
        assertTrue(auth.contains("sign2=firma01"))
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `ante un 403 pide firma fresca y reintenta exactamente una vez`() = runBlocking {
        val upstream = MockWebServer()
        var pedidos = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos++
                return if (pedidos == 1) MockResponse().setResponseCode(403)
                else MockResponse().setBody("#EXTM3U\n")
            }
        }
        upstream.start()

        val firmas = FirmasFalsas()
        val proxy = LiveHlsProxy(firmas)
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertEquals("un 403 y su reintento, nada mas", 2, pedidos)
        assertEquals("el 403 se le avisa a la fuente de firmas", 1, firmas.rechazos)
        assertEquals("el reintento que si funciono se avisa como aceptado", 1, firmas.aceptaciones)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Hallazgo F1 de la revisión final: antes, `pedirAlOrigen` llamaba a `firmas.rechazada()` UNA
     * VEZ POR INTENTO HTTP (hasta dos, acá dentro) -- así que un solo 403 "de verdad" (el caso
     * "caducó la sesión, no la firma", donde el reintento también 403 sin que el algoritmo esté
     * roto) ya empujaba el contador de `FirmaConRespaldo` DOS pasos de una, la mitad del umbral
     * real. `dos 403 seguidos` acá son UNA sola petición de `LiveHlsProxy` (con su reintento
     * interno) rindiéndose: eso tiene que contar como UN rechazo, no dos.
     */
    @Test
    fun `dos 403 seguidos se rinden en vez de reintentar para siempre, y cuentan como UN solo rechazo`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val firmas = FirmasFalsas()
        val proxy = LiveHlsProxy(firmas)
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))
        assertEquals(502, codigo)
        assertEquals("un 403 y su reintento -tambien 403- cuentan como UN solo rechazo", 1, firmas.rechazos)
        assertEquals(0, firmas.aceptaciones)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * "En la misma ola" de la revisión final: un 403 que sobrevive al reintento significa que
     * caducó la SESIÓN del canal (token/license), no la firma -ver el KDoc de `pedirAlOrigen`-.
     * Antes nadie avisaba de esto: `LiveController` seguía sirviendo esa sesión cacheada hasta
     * 300s más, así que zapear e ir y volver a un canal roto lo dejaba roto todo ese rato.
     */
    @Test
    fun `dos 403 seguidos avisan que la sesion del canal murio`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val canalesMuertos = CopyOnWriteArrayList<String>()
        val proxy = LiveHlsProxy(FirmasFalsas(), onSesionMuerta = { canalesMuertos.add(it) })
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "canal-x", 0)
        leer(proxy.urlPara(sesion))

        assertEquals(listOf("canal-x"), canalesMuertos)
        proxy.stop(); upstream.shutdown()
    }

    /** El camino feliz (sin 403) no debe avisar sesión muerta -sería un false positive. */
    @Test
    fun `sin 403 no avisa sesion muerta`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val canalesMuertos = CopyOnWriteArrayList<String>()
        val proxy = LiveHlsProxy(FirmasFalsas(), onSesionMuerta = { canalesMuertos.add(it) })
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        leer(proxy.urlPara(sesion))

        assertTrue(canalesMuertos.isEmpty())
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Hallazgo C2 de la revisión: `atender()` no atrapaba excepciones, y `stop()` puede poner
     * `sesion = null` mientras OTRA conexión ya en curso todavía la está usando (el usuario sale
     * del reproductor justo cuando un segmento está a mitad de descarga). Antes de este test, eso
     * terminaba en un NPE (`sesion!!.license`) que escapaba del hilo de la conexión — y en
     * Android una excepción sin atrapar en CUALQUIER hilo mata el proceso entero.
     *
     * Para no depender de timing real (que sería un test frágil), la propia fuente de firmas se
     * usa de gancho: `firmar()` es lo último que corre DENTRO de `contentAuth()` antes de que
     * `pedirAlOrigen` vuelva a leer `sesion` para `Content-License` — así que llamar `stop()`
     * justo ahí reproduce la ventana exacta que señaló la revisión, siempre, sin azar.
     */
    @Test
    fun `una sesion que desaparece a mitad de una peticion no revienta el hilo con NPE`() {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        lateinit var proxy: LiveHlsProxy
        val firmaQueMataLaSesion = object : FirmaDeSegmentos {
            override suspend fun firmar(token: String): LiveSignature {
                proxy.stop()  // simula: el usuario sale del reproductor a mitad de esta petición
                return LiveSignature(1000L, "firma-evil")
            }
        }
        proxy = LiveHlsProxy(firmaQueMataLaSesion)
        val puerto = proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        proxy.urlPara(sesion)

        val excepciones = CopyOnWriteArrayList<Throwable>()
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> excepciones.add(e) }
        try {
            runCatching { leer("http://127.0.0.1:$puerto/live.m3u8") }
            // atender() corre en un hilo daemon aparte: darle margen a que termine (con o sin
            // excepción) antes de revisar qué quedó capturado.
            Thread.sleep(300)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previo)
        }
        assertTrue(
            "una sesion que desaparece a mitad de una peticion no deberia tirar una excepcion " +
                "sin atrapar (mataria el proceso en Android): $excepciones",
            excepciones.isEmpty(),
        )
        upstream.shutdown()
    }

    /**
     * Hallazgo I1 de la revisión: la reescritura original solo tocaba líneas que empezaban
     * literalmente con `"http"` y contenían `".ts"`. Una URL relativa (`c_1.ts`) se hubiera
     * resuelto contra el proxy en una ruta que no maneja (404), y una protocol-relative
     * (`//otro.cdn/...`) se hubiera ido derecho al CDN sin firma.
     */
    @Test
    fun `reescribe tambien segmentos relativos y protocol-relative`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nc_1.ts\n#EXTINF:6,\n//otro.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (codigo, cuerpo) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        // la relativa se resuelve contra el directorio del playlist (/live/) y sale reescrita
        val relativaEsperada = "http://${upstream.hostName}:${upstream.port}/live/c_1.ts"
        assertTrue(
            "la URL relativa deberia resolverse contra /live/ y reescribirse: $cuerpo",
            cuerpo.contains("seg?u=" + URLEncoder.encode(relativaEsperada, "UTF-8")),
        )
        // la protocol-relative nunca debe quedar apuntando DIRECTO al CDN, sin firma — "otro.cdn"
        // sí puede aparecer codificado DENTRO del parámetro u= del proxy, así que lo que importa
        // es que ninguna línea empiece apuntando directo ahí.
        assertTrue(
            "la protocol-relative no debe quedar sin reescribir: $cuerpo",
            cuerpo.lines().none { it.trim().let { l -> l.startsWith("//otro.cdn") || l.startsWith("http://otro.cdn") } },
        )
        assertEquals(2, cuerpo.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    /** Una `#EXT-X-KEY` con URI absoluta también tiene que salir reescrita hacia el proxy. */
    @Test
    fun `reescribe la URI de EXT-X-KEY para que tambien salga firmada`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://cdn.key/live/c/key.bin\"\n" +
                "#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (codigo, cuerpo) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        val lineaKey = cuerpo.lines().first { it.startsWith("#EXT-X-KEY") }
        assertTrue(
            "la URI de la clave deberia salir reescrita hacia el proxy, no directo al CDN: $lineaKey",
            lineaKey.contains("URI=\"http://127.0.0.1"),
        )
        // igual que arriba: "cdn.key" puede aparecer codificado dentro del u= del proxy, lo que
        // no debe pasar es que la URI del tag siga apuntando DIRECTO ahí.
        assertTrue(!lineaKey.contains("URI=\"http://cdn.key"))
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Fuga de la Tarea 14: `AppGraph` creaba `liveHlsProxy` pero nada lo cerraba al salir del
     * canal en vivo, así que el `ServerSocket` en 127.0.0.1 (y su hilo `accept()`) quedaban vivos
     * el resto del proceso. El fix real vive en `PlaybackService.releaseNetworkResources()` (no
     * testeable sin Robolectric: es un `android.app.Service`), así que este test verifica la
     * parte que SÍ se puede probar en JVM pura: que `stop()` de verdad suelta el socket, no solo
     * que pone en null una referencia.
     */
    @Test
    fun `stop cierra el ServerSocket y libera el puerto`() {
        val proxy = LiveHlsProxy(FirmasFalsas())
        val puerto = proxy.start()
        assertEquals(puerto, proxy.port)

        proxy.stop()

        assertEquals("port debe volver a -1: el ServerSocket ya no existe", -1, proxy.port)
        // El puerto viejo ya no debe aceptar conexiones: si el hilo accept() siguiera vivo
        // (el ServerSocket no se cerró de verdad) esta conexión se establecería igual.
        val seConectaTodavia = runCatching { Socket("127.0.0.1", puerto).close(); true }.getOrDefault(false)
        assertTrue("el puerto viejo no debería aceptar conexiones tras stop()", !seConectaTodavia)
    }

    /** `stop()` sin haber llamado `start()`, y llamarlo dos veces seguidas, no deben tirar. */
    @Test
    fun `stop es idempotente`() {
        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.stop() // nunca arrancó: no debe explotar
        assertEquals(-1, proxy.port)

        val puerto = proxy.start()
        assertTrue(puerto > 0)
        proxy.stop()
        proxy.stop() // segunda vez sobre un server ya cerrado: tampoco debe explotar
        assertEquals(-1, proxy.port)
    }
}
