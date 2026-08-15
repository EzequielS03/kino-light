package com.arkiv.player.data.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveApiTest {
    private fun api(server: MockWebServer) = LiveApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        http = OkHttpClient(),
    )

    @Test
    fun `canales sin logo quedan en null`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":501,"logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(76206)
        assertEquals("ESPN", canales[0].nombre)
        assertEquals(501, canales[0].numero)
        assertNull(canales[0].logo)
        server.shutdown()
    }

    // Task 8 (Paso 3): `X-Arkiv-Key` salió del todo -- confirma que el corte fue real.
    @Test
    fun `nunca manda X-Arkiv-Key`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[]}"""))
        server.start()
        api(server).categorias()
        assertNull(server.takeRequest().getHeader("X-Arkiv-Key"))
        server.shutdown()
    }

    // --- Task 8 (Paso 3): Authorization + X-Arkiv-Device son la ÚNICA credencial ---

    @Test
    fun `manda Authorization y X-Arkiv-Device cuando hay sesion`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[]}"""))
        server.start()
        val conSesion = LiveApi(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
            personToken = { "person-tok" },
            deviceToken = { "device-tok" },
        )
        conSesion.categorias()
        val req = server.takeRequest()
        assertEquals("person-tok", req.getHeader("Authorization"))
        assertEquals("device-tok", req.getHeader("X-Arkiv-Device"))
        server.shutdown()
    }

    @Test
    fun `sin sesion no manda Authorization ni X-Arkiv-Device`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[]}"""))
        server.start()
        api(server).categorias()   // api() del helper de arriba no pasa personToken/deviceToken
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Arkiv-Device"))
        server.shutdown()
    }

    @Test
    fun `epg separa lo que llego de lo que falta`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"epg":{"c1":[{"titulo":"Partido","inicio":100,"fin":200,"sinopsis":"s"}]},"missing":["c2"]}"""
        ))
        server.start()
        val (epg, faltan) = api(server).epg(listOf("c1", "c2"))
        assertEquals("Partido", epg["c1"]!![0].titulo)
        assertEquals(listOf("c2"), faltan)
        server.shutdown()
    }

    @Test
    fun `firmar pide el lote y devuelve los pares`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"firmas":[{"moment":1,"sign2":"aa"},{"moment":2,"sign2":"bb"}]}"""
        ))
        server.start()
        val firmas = api(server).firmar("t", 2, 0)
        assertEquals(2, firmas.size)
        assertEquals("bb", firmas[1].sign2)
        server.shutdown()
    }

    // --- JSON hostil: el portal ya nos sorprendió con campos ausentes, tipos raros y nulls.
    // Para categorías/canales/EPG/firmas el contrato es que ESO se degrada a valores por defecto
    // (o se descarta el elemento puntual), nunca una excepción cruda que tumbe la pantalla.
    // resolver() es la excepción a esa regla: ver el bloque de comentario más abajo, junto a
    // sus tests. ---

    @Test
    fun `canales sin el campo canales en la respuesta no explota`() = runBlocking {
        val server = MockWebServer()
        // El gateway respondió 200 pero sin la clave "canales" (p.ej. una categoría vacía mal armada).
        server.enqueue(MockResponse().setBody("""{}"""))
        server.start()
        val canales = api(server).canales(1)
        assertTrue(canales.isEmpty())
        server.shutdown()
    }

    @Test
    fun `un canal sin la clave logo (ni siquiera null) tambien queda en null`() = runBlocking {
        val server = MockWebServer()
        // Acá "logo" ni siquiera está presente, a diferencia del test del brief donde viene null.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":501}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertNull(canales[0].logo)
        server.shutdown()
    }

    @Test
    fun `un canal con numero de tipo inesperado no explota`() = runBlocking {
        val server = MockWebServer()
        // "numero" llega como texto no numérico en vez de int.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":"desconocido","logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertEquals(0, canales[0].numero)
        server.shutdown()
    }

    @Test
    fun `un canal sin code se descarta en vez de reventar`() = runBlocking {
        val server = MockWebServer()
        // Sin "code" el canal es inservible (no se puede resolver ni pedir EPG): se descarta.
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"nombre":"Fantasma","numero":1},{"code":"c2","nombre":"OK","numero":2,"logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(1)
        assertEquals(1, canales.size)
        assertEquals("c2", canales[0].code)
        server.shutdown()
    }

    @Test
    fun `una categoria sin nombre no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[{"id":5}]}"""))
        server.start()
        val categorias = api(server).categorias()
        assertEquals(5, categorias[0].id)
        assertEquals("", categorias[0].nombre)
        server.shutdown()
    }

    @Test
    fun `un elemento de categorias que no es un objeto se descarta`() = runBlocking {
        val server = MockWebServer()
        // Un string suelto en el array en vez de un objeto: no debe tirar JSONException.
        server.enqueue(MockResponse().setBody("""{"categorias":["basura",{"id":1,"nombre":"Deportes"}]}"""))
        server.start()
        val categorias = api(server).categorias()
        assertEquals(1, categorias.size)
        assertEquals("Deportes", categorias[0].nombre)
        server.shutdown()
    }

    @Test
    fun `epg sin el campo missing no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"epg":{}}"""))
        server.start()
        val (epg, faltan) = api(server).epg(listOf("c1"))
        assertTrue(epg.isEmpty())
        assertTrue(faltan.isEmpty())
        server.shutdown()
    }

    @Test
    fun `un programa del epg con campos ausentes no explota`() = runBlocking {
        val server = MockWebServer()
        // Sin "sinopsis" ni "fin": no debe tirar, cae a los valores por defecto.
        server.enqueue(MockResponse().setBody(
            """{"epg":{"c1":[{"titulo":"Partido","inicio":100}]},"missing":[]}"""
        ))
        server.start()
        val (epg, _) = api(server).epg(listOf("c1"))
        assertEquals("Partido", epg["c1"]!![0].titulo)
        assertEquals(0L, epg["c1"]!![0].fin)
        assertEquals("", epg["c1"]!![0].sinopsis)
        server.shutdown()
    }

    // --- resolver(): acá el criterio cambia. Un LiveSession con cflHost/authBase/license/token
    // vacío no es un dato degradado e inofensivo: es una sesión que el proxy de la Tarea 8 va a
    // usar tal cual contra el CDN real (ver LiveHlsProxy, que manda Content-License sin
    // chequearlo). Si eso falla, falla LEJOS —como un 403/400 opaco del CDN— y sin decir qué
    // faltaba. Acá todavía tenemos la respuesta cruda del gateway, así que es el único lugar
    // donde se puede señalar el campo exacto que vino mal. Por eso resolver() valida y lanza
    // GatewayException ni bien arma la sesión, en vez de devolverla incompleta. ---

    @Test
    fun `resolver sin cflHost lanza nombrando el campo que falto`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"authBase":"http://x/?token=${"A".repeat(32)}","license":"LIC","channel":"c1"}"""
        ))
        server.start()
        val error = runCatching { api(server).resolver("c1") }.exceptionOrNull()
        assertTrue(error is GatewayException)
        assertTrue(error!!.message!!.contains("cflHost"))
        server.shutdown()
    }

    @Test
    fun `resolver sin authBase lanza nombrando el campo que falto`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"cflHost":"h","license":"LIC","channel":"c1"}"""
        ))
        server.start()
        val error = runCatching { api(server).resolver("c1") }.exceptionOrNull()
        assertTrue(error is GatewayException)
        assertTrue(error!!.message!!.contains("authBase"))
        server.shutdown()
    }

    @Test
    fun `resolver sin license lanza nombrando el campo que falto`() = runBlocking {
        val server = MockWebServer()
        // El servidor SÍ puede mandar license vacío de forma legítima (el portal no siempre
        // trae ese campo), pero el CDN lo exige igual (ver LiveHlsProxy): del lado de la app
        // es un dato esencial, no opcional.
        server.enqueue(MockResponse().setBody(
            """{"cflHost":"h","authBase":"http://x/?token=${"A".repeat(32)}","channel":"c1"}"""
        ))
        server.start()
        val error = runCatching { api(server).resolver("c1") }.exceptionOrNull()
        assertTrue(error is GatewayException)
        assertTrue(error!!.message!!.contains("license"))
        server.shutdown()
    }

    @Test
    fun `resolver con authBase sin token valido lanza nombrando el problema`() = runBlocking {
        val server = MockWebServer()
        // authBase presente y no vacío, pero sin el patrón token=<32 hex> que la firma necesita.
        server.enqueue(MockResponse().setBody(
            """{"cflHost":"h","authBase":"http://x/?a=1","license":"LIC","channel":"c1"}"""
        ))
        server.start()
        val error = runCatching { api(server).resolver("c1") }.exceptionOrNull()
        assertTrue(error is GatewayException)
        assertTrue(error!!.message!!.contains("token"))
        server.shutdown()
    }

    @Test
    fun `resolver en el caso feliz extrae el token del authBase`() = runBlocking {
        val server = MockWebServer()
        val tok = "AB12CD34AB12CD34AB12CD34AB12CD34".take(32)
        server.enqueue(MockResponse().setBody(
            """{"cflHost":"h","authBase":"http://x/?a=1&token=$tok","license":"LIC","channel":"c1","expiresAt":99}"""
        ))
        server.start()
        val sesion = api(server).resolver("c1")
        assertEquals(tok, sesion.token)
        assertEquals("c1", sesion.channel)
        server.shutdown()
    }

    @Test
    fun `firmar con una firma sin sign2 no explota`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"firmas":[{"moment":1}]}"""))
        server.start()
        val firmas = api(server).firmar("t", 1, 0)
        assertEquals(1L, firmas[0].moment)
        assertEquals("", firmas[0].sign2)
        server.shutdown()
    }
    /**
     * MEDIDO EN PRODUCCIÓN EL 2026-08-14: en el Google TV unos canales abrían y otros se
     * quedaban cargando. El portal resolvía bien los dos y el CDN contestaba 401 solo a unos:
     *
     * ```
     * LiveHlsProxy: playlist → 401 FIRMA RECHAZADA (canal=cyx-RCNHD)
     * LiveHlsProxy: playlist servido canal=cyx_9881490555304164628541864337 segmentos=6
     * ```
     *
     * La señal no siempre se llama en el CDN como el canal: `cyx-RCNHD` se sirve como
     * `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`. Los que andaban eran justo aquellos donde los dos
     * coinciden. El proxy arma `/live/{codigo}.m3u8`, así que con el código equivocado le pedía
     * al CDN una señal distinta de la que autoriza la licencia que le mandaba — de ahí el 401.
     */
    @Test
    fun `resolver trae el playCode, que es como se llama la señal en el CDN`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"cflHost":"h","authBase":"http://x/?token=${"A".repeat(32)}","license":"L",""" +
                    """"channel":"cyx-RCNHD","playCode":"cyx-2EF7E10E40C1ac19D6A9F3ED4CD2","expiresAt":9}""",
            ),
        )

        server.start()
        val s = api(server).resolver("cyx-RCNHD")

        assertEquals("cyx-2EF7E10E40C1ac19D6A9F3ED4CD2", s.playCode)
        // El código pedido no se pierde: es la clave con la que el proxy invalida la sesión.
        assertEquals("cyx-RCNHD", s.channel)
        server.shutdown()
    }

    /** Un gateway que todavía no manda `playCode` tiene que seguir andando igual que antes. */
    @Test
    fun `sin playCode en la respuesta, se cae al codigo del canal`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"cflHost":"h","authBase":"http://x/?token=${"A".repeat(32)}","license":"L",""" +
                    """"channel":"cyx_abc","expiresAt":9}""",
            ),
        )

        server.start()
        assertEquals("cyx_abc", api(server).resolver("cyx_abc").playCode)
        server.shutdown()
    }

    @Test
    fun `arbol parsea secciones con sus items y marca los de adultos`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"secciones":[{"id":1,"nombre":"Recentes","adulto":true,"items":[""" +
                    """{"id":"A1","titulo":"Uno","poster":"http://p/1.jpg","duracionS":600},""" +
                    """{"id":"","titulo":"sin id"}]}]}""",
            ),
        )
        server.start()

        val secciones = api(server).arbol("adultos", incluirAdultos = true)

        assertEquals(1, secciones.size)
        assertEquals("Recentes", secciones[0].nombre)
        // El ítem sin id se descarta: no hay con qué reproducirlo.
        assertEquals(1, secciones[0].items.size)
        assertEquals("Uno", secciones[0].items[0].titulo)
        // La marca baja de la sección a CADA ítem: el ítem viaja solo hasta el reproductor.
        assertTrue(secciones[0].items[0].adulto)
        assertEquals("adultos", server.takeRequest().requestUrl?.queryParameter("raiz"))
        server.shutdown()
    }

    /**
     * El `ref` es lo ÚNICO con lo que se puede reproducir: `/v1/resolve` no toma el `id` del
     * ítem (que es el contentId del portal), toma un token firmado que solo acuña el gateway.
     * Y `tipo` decide el camino sin abrir el ref, que la app trata como opaco.
     */
    @Test
    fun `arbol trae el ref y el tipo de cada item`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"secciones":[{"id":2,"nombre":"Telenovelas","adulto":false,"items":[""" +
                    """{"id":"P1","titulo":"Peli","ref":"abc.def","tipo":"movie"},""" +
                    """{"id":"S1","titulo":"Serie","ref":"ghi.jkl","tipo":"teleplay"}]}]}""",
            ),
        )
        server.start()

        val items = api(server).arbol("series")[0].items

        assertEquals("abc.def", items[0].ref)
        assertFalse(items[0].esSerie)
        assertEquals("ghi.jkl", items[1].ref)
        assertTrue(items[1].esSerie)
        server.shutdown()
    }

    /**
     * Un gateway sin llave de firma sirve el catálogo SIN refs (degrada a propósito, en vez de
     * mandar uno roto). Esos ítems se siguen listando —se pueden ver— pero no se reproducen: sin
     * ref no hay nada que resolver, y quedarse callado es mejor que un error al tocar play.
     */
    @Test
    fun `un item sin ref se lista pero no es reproducible`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"secciones":[{"id":2,"nombre":"X","adulto":false,"items":[""" +
                    """{"id":"P1","titulo":"Peli"}]}]}""",
            ),
        )
        server.start()

        val item = api(server).arbol("series")[0].items.single()

        assertEquals("P1", item.id)
        assertFalse(item.reproducible)
        // Sin `tipo` del gateway cae a película, igual que la búsqueda.
        assertFalse(item.esSerie)
        server.shutdown()
    }

    @Test
    fun `arbol sin incluirAdultos no manda el parametro`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"secciones":[]}"""))
        server.start()

        api(server).arbol("series")

        assertNull(server.takeRequest().requestUrl?.queryParameter("adultos"))
        server.shutdown()
    }

}
