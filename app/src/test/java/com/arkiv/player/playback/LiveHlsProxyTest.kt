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
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        // Hay que usar la URL que devuelve urlPara() (con el token de sesión, ver Tarea 19), no
        // reconstruirla a mano con el puerto: sin el token, atender() la rechazaría con 403 ANTES
        // de llegar siquiera a la ventana de carrera que este test quiere reproducir.
        val url = proxy.urlPara(sesion)

        val excepciones = CopyOnWriteArrayList<Throwable>()
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> excepciones.add(e) }
        try {
            runCatching { leer(url) }
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

    /**
     * Tarea 18 (Chromecast/DLNA para vivo): sin ningún canal abierto todavía no hay nada que
     * castear -- `lanUrl` no debe inventar una URL con un puerto que ni siquiera existe.
     */
    @Test
    fun `lanUrl sin ningun canal abierto devuelve null`() {
        val proxy = LiveHlsProxy(FirmasFalsas())
        assertEquals(null, proxy.lanUrl("192.168.1.50"))
    }

    /**
     * `urlPara` (el camino real por el que se abre un canal) tiene que dejar el proxy alcanzable
     * por la LAN desde el primer canal -- `lanUrl` refleja el MISMO puerto que ya quedó grabado en
     * la URL local que consume VLC (ver el KDoc de `urlPara`: no hay "ensanchar" a mitad de
     * reproducción, cambiaría el puerto y rompería lo que ya está reproduciendo).
     */
    @Test
    fun `lanUrl coincide en puerto con la url local que ya usa VLC`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlPara(sesion)

        // El token (Tarea 19) es el mismo en ambas URLs -misma sesión de proxy-, solo cambia el
        // host: se extrae de `local` en vez de fijarlo a mano, porque es aleatorio por corrida.
        val token = local.substringAfter("?t=")
        val lan = proxy.lanUrl("192.168.1.50")
        assertEquals("http://192.168.1.50:${proxy.port}/live.m3u8?t=$token", lan)
        assertTrue(
            "misma ruta, puerto y token que la URL local, solo cambia el host",
            local.endsWith(":${proxy.port}/live.m3u8?t=$token"),
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * El socket que abre `urlPara` (bindLan=true, ver su KDoc) tiene que seguir aceptando
     * conexiones por loopback igual que antes -- 127.0.0.1 conecta igual con el socket escuchando
     * en todas las interfaces, así que esto no le cambia nada a la reproducción local.
     */
    @Test
    fun `urlPara sigue siendo alcanzable por loopback tras pasar a escuchar en toda la LAN`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        proxy.stop(); upstream.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Tarea 19 (mitigación del hallazgo de exposición en LAN): desde que start() escucha en
    // toda la LAN en vez de solo loopback (Tarea 18, Chromecast/DLNA), el token de sesión es el
    // único control de acceso. Estos tests cubren: 403 sin token / con token equivocado, 200 con
    // el token correcto (tanto en el playlist como en el segmento reescrito), y que el rechazo no
    // tira ninguna excepción sin atrapar.
    // ---------------------------------------------------------------------------------------

    /** Pedir el playlist sin el query param `t` tiene que rebotar con 403, no servir nada. */
    @Test
    fun `playlist sin token responde 403`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        proxy.urlPara(sesion) // arranca el server y fija la sesión; se ignora la URL con token

        val (codigo, cuerpo) = leer("http://127.0.0.1:${proxy.port}/live.m3u8")

        assertEquals(403, codigo)
        assertTrue("el 403 no debe filtrar nada en el cuerpo", cuerpo.isEmpty())
        proxy.stop(); upstream.shutdown()
    }

    /** Un token que NO es el de la sesión actual (adivinado, viejo, de otro proceso) también rebota. */
    @Test
    fun `playlist con token equivocado responde 403`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        proxy.urlPara(sesion)

        val (codigo, _) = leer("http://127.0.0.1:${proxy.port}/live.m3u8?t=token-que-no-es")

        assertEquals(403, codigo)
        proxy.stop(); upstream.shutdown()
    }

    /** Pedir un segmento sin token tampoco debe pasar, aunque la URL del segmento sea válida. */
    @Test
    fun `segmento sin token responde 403 aunque la URL del segmento exista`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (_, cuerpo) = leer(proxy.urlPara(sesion))
        // saca la ruta /seg?u=...&t=... que el propio proxy generó, y le quita el token a mano
        val rutaSegmentoConToken = cuerpo.lineSequence().first { it.startsWith("http://127.0.0.1") }
        val sinToken = rutaSegmentoConToken.substringBefore("&t=")

        val (codigo, _) = leer(sinToken)

        assertEquals(403, codigo)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Camino feliz de punta a punta: el playlist reescribe los segmentos CON el token, y pedir
     * esa URL reescrita (tal cual la entrega el proxy, sin tocarla) sirve el segmento real.
     */
    @Test
    fun `el token correcto sirve tanto el playlist como el segmento reescrito`() = runBlocking {
        val upstream = MockWebServer()
        upstream.start()
        // El segmento "absoluto" del playlist apunta al MISMO upstream (no a un CDN inventado):
        // así, cuando el proxy vuelva a pedirle al origen la URL que decodificó de `u=`, es una
        // petición real que el MockWebServer puede responder con el segundo enqueue.
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://${upstream.hostName}:${upstream.port}/live/c/c_1.ts\n"
        ))
        upstream.enqueue(MockResponse().setBody("contenido-del-segmento"))

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigoPlaylist, cuerpo) = leer(proxy.urlPara(sesion))
        assertEquals(200, codigoPlaylist)

        val rutaSegmento = cuerpo.lineSequence().first { it.startsWith("http://127.0.0.1") }
        val (codigoSegmento, cuerpoSegmento) = leer(rutaSegmento)

        assertEquals(200, codigoSegmento)
        assertEquals("contenido-del-segmento", cuerpoSegmento)
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Hallazgo de la revisión (Tarea 19): los tests de "LAN" que ya existían (`lanUrl coincide
     * en puerto...`, `urlPara sigue siendo alcanzable por loopback...`) solo prueban el FORMATO
     * del string de `lanUrl()` o que loopback sigue andando -- ninguno de los dos demuestra que
     * el `ServerSocket` esté REALMENTE escuchando en una interfaz que no sea loopback. El propio
     * revisor lo demostró: forzó el bind a loopback SIEMPRE (ignorando `bindLan`) y la suite
     * completa (1068 tests) siguió en verde.
     *
     * Este test pide el playlist por la IP real de una interfaz NO-loopback de la máquina
     * (`NetworkInterface`, la misma fuente que consultaría un Chromecast/DLNA de la LAN) en vez
     * de por `127.0.0.1`. Si el bind fuera solo a loopback, esta conexión se cae con
     * "Connection refused" -el puerto existe, pero no escucha en esa interfaz-. Si la máquina no
     * tiene ninguna interfaz no-loopback activa (algunos sandboxes de CI), el test se salta: no
     * hay red real contra la que probar nada, y fallar por eso sería un motivo ajeno a lo que
     * se quiere verificar.
     */
    @Test
    fun `el proxy es alcanzable por una IP de LAN real, no solo por el string de lanUrl`() = runBlocking {
        val ipLan = direccionNoLoopbackAlcanzable() ?: run {
            // android.util.Log revienta en este entorno de test JVM puro (por eso atender() lo
            // atrapa con runCatching); un println alcanza para dejar rastro de que el test se
            // saltó por falta de red, sin arriesgar una excepción sin atrapar acá.
            println("LiveHlsProxyTest: sin interfaz de LAN alcanzable en esta maquina, se salta el test de alcanzabilidad real")
            return@runBlocking
        }

        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlPara(sesion) // bindLan=true adentro, ver su KDoc

        val urlPorLan = local.replaceFirst("127.0.0.1", ipLan.hostAddress!!)
        val (codigo, _) = leer(urlPorLan)

        assertEquals(
            "el proxy tiene que ser alcanzable por una IP no-loopback (para Chromecast/DLNA " +
                "en la LAN), no solo por el string que arma lanUrl()",
            200, codigo,
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * Hallazgo del agente anterior (Tarea 20), confirmado acá: `reescribirLinea` fijaba el host
     * de las URLs de segmento a `127.0.0.1` SIEMPRE, sin importar por qué interfaz llegó la
     * petición del playlist. Eso rompe Chromecast/DLNA: al castear, el receptor pide el playlist
     * por la IP LAN del celu (`lanUrl`), pero las URLs de segmento que recibe adentro apuntan a
     * `127.0.0.1` -- que para el Chromecast es EL PROPIO CHROMECAST, no el celu. Pantalla negra,
     * sin ningún error que lo explique.
     *
     * Este test pide el playlist por una IP de LAN real (no loopback, mismo helper que el test de
     * alcanzabilidad de arriba) y comprueba que las URLs de segmento reescritas usan ESA IP, no
     * `127.0.0.1`. Con el bug, esta aserción falla: las URLs siguen apuntando a loopback aunque la
     * petición haya entrado por la LAN.
     */
    @Test
    fun `las URLs de segmento apuntan al host por el que se pidio el playlist, no siempre a loopback`() = runBlocking {
        val ipLan = direccionNoLoopbackAlcanzable() ?: run {
            println("LiveHlsProxyTest: sin interfaz de LAN alcanzable en esta maquina, se salta el test de host por LAN")
            return@runBlocking
        }

        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://cdn.key/live/c/key.bin\"\n" +
                "#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val local = proxy.urlPara(sesion) // bindLan=true adentro, ver su KDoc

        val urlPorLan = local.replaceFirst("127.0.0.1", ipLan.hostAddress!!)
        val (codigo, cuerpo) = leer(urlPorLan)

        assertEquals(200, codigo)
        assertTrue(
            "el segmento reescrito deberia apuntar a la IP LAN por la que se pidio el playlist, " +
                "no a loopback (rompe Chromecast/DLNA): $cuerpo",
            cuerpo.contains("http://${ipLan.hostAddress}:${proxy.port}/seg?u="),
        )
        assertTrue(
            "no deberia quedar ninguna URL de segmento fija a 127.0.0.1 cuando se pidio por LAN: $cuerpo",
            !cuerpo.contains("http://127.0.0.1"),
        )
        val lineaKey = cuerpo.lines().first { it.startsWith("#EXT-X-KEY") }
        assertTrue(
            "la URI de EXT-X-KEY tiene el mismo problema: tambien deberia usar la IP LAN: $lineaKey",
            lineaKey.contains("URI=\"http://${ipLan.hostAddress}:${proxy.port}/seg?u="),
        )
        proxy.stop(); upstream.shutdown()
    }

    /**
     * IPv4 no-loopback REALMENTE alcanzable de esta máquina (no simplemente "la primera que
     * enumera `NetworkInterface`"). Máquinas de desarrollo o CI suelen tener de más: bridges de
     * Docker/VMs, túneles VPN (`utunN`), interfaces con la dirección DE RED en vez de una de
     * host (p.ej. `172.20.0.0/16` reportando `172.20.0.0`) -- todas aparecen "up" y "no loopback"
     * pero un `connect()` real contra ellas revienta con `BindException: Can't assign requested
     * address`, que NO tiene nada que ver con lo que este test quiere probar (si el proxy
     * escucha en 0.0.0.0 o no). Por eso cada candidata se prueba con una conexión real corta
     * contra un socket de prueba (mismo patrón `ServerSocket(0)` sin IP que usa el proxio real) y
     * se descarta si falla, en vez de confiar a ciegas en el orden de enumeración del SO.
     */
    private fun direccionNoLoopbackAlcanzable(): java.net.Inet4Address? {
        val candidatas = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
        val prueba = java.net.ServerSocket(0)
        return try {
            candidatas.firstOrNull { candidata ->
                runCatching {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(candidata, prueba.localPort), 300)
                    }
                }.isSuccess
            }
        } finally {
            prueba.close()
        }
    }
    /**
     * EL BUG DEL 2026-08-14. La señal no siempre se llama en el CDN como el canal: `cyx-RCNHD` se
     * sirve como `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`. Pidiéndole al CDN el código del canal, la
     * ruta no correspondía a la señal que autoriza la licencia que le mandábamos, y contestaba
     * 401 — el canal se quedaba cargando para siempre. Los que funcionaban eran justamente
     * aquellos donde `playCode` y `channel` coinciden, que es lo que lo disimuló.
     */
    @Test
    fun `el playlist se le pide al CDN por playCode, no por el codigo del canal`() {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:6,\nseg1.ts\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "cyx-RCNHD", expiresAt = 0,
            playCode = "cyx-2EF7E10E40C1ac19D6A9F3ED4CD2",
        )
        leer(proxy.urlPara(sesion))

        assertEquals(
            "/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8",
            upstream.takeRequest().path,
        )
        proxy.stop(); upstream.shutdown()
    }

}
