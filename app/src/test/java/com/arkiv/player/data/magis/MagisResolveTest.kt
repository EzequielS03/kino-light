package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisResolveTest {

    /**
     * La forma REAL de `getSlbInfo` (capturada contra el portal, ver `tests/test_magis_live.py` de
     * arkiv-api): `main_addr` cuelga del objeto `cdn`, hermano de `url_list` -- NO de cada entrada
     * de `url_list`. Y `url_list[].url` no es una url: es un querystring suelto, sin esquema ni `?`.
     */
    private fun slbRealista(
        auth: String = "cdn_type=1&sign_type=cfl&token=ABC&expired=$LEJANO",
        tag: String = "free",
        mainAddr: String = "https://cdn.example.com",
        invalidTime: String = "14400",
    ) = JSONObject(
        """{"invalidTime":"$invalidTime","cdn_list":[
            {"tag":"vod","main_addr":"$mainAddr","url_list":[{"tag":"$tag","url":"$auth"}]}
        ]}""",
    )

    private fun playDeUnaPelicula(
        contentId: String = "M1",
        videoFormat: String = "mp4",
        encodeFormat: String = "h264",
    ) = JSONObject(
        """{"episodeList":[{"totalMovieList":[{"movieList":[
            {"contentId":"$contentId","videoFormat":"$videoFormat","encodeFormat":"$encodeFormat",
             "licenseList":[{"license":"LIC123"}]}
        ]}]}]}""",
    )

    @Test
    fun `arma la url y headers finales para una pelicula`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista(mainAddr = "cdn.example.com")))
        val resolve = MagisResolve(fake, sesionDeTest(fake), appId = "app.id", apkVersion = "49902")

        val r = resolve.resolveVod("M1")

        val p = r.dato() ?: error("esperaba Ok y fue $r")
        assertEquals("https://cdn.example.com/vod/M1_media.mp4", p.url)
        assertEquals("LIC123", p.headers["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", p.headers["User-Agent"])
        assertEquals("app.id", p.headers["App"])
        assertEquals("49902", p.headers["App-Version"])
        assertEquals("cdn_type=1&sign_type=cfl&token=ABC&expired=$LEJANO", p.headers["Content-Auth"])
        assertEquals("video/mp4", p.mime)
        assertEquals("mp4", p.container)
        assertEquals("h264", p.videoCodec)
    }

    @Test
    fun `main_addr con esquema y barra final no duplica nada`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista(mainAddr = "https://cdn.example.com/")))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        val p = resolve.resolveVod("M1").dato()!!

        assertEquals("https://cdn.example.com/vod/M1_media.mp4", p.url)
    }

    @Test
    fun `un main_addr con path y http se respeta tal cual`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta(
            "v14/getSlbInfo",
            MagisResult.Ok(slbRealista(mainAddr = "http://niguof.vynbszicd.com/v3/youshi/")),
        )

        val p = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1").dato()!!

        assertEquals("http://niguof.vynbszicd.com/v3/youshi/vod/M1_media.mp4", p.url)
    }

    @Test
    fun `un ts se sirve como ts y se declara como mp2t`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v10/startPlayVOD",
            MagisResult.Ok(playDeUnaPelicula(contentId = "T9", videoFormat = "ts")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        val p = resolve.resolveVod("T9").dato()!!

        assertEquals("https://cdn.example.com/vod/T9_media.ts", p.url)
        assertEquals("video/mp2t", p.mime)
        assertEquals("ts", p.container)
    }

    @Test
    fun `entre h265-mp4 y h264-ts gana el h264 aunque no sea mp4`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"HEVC","videoFormat":"mp4","encodeFormat":"h265","licenseList":[{"license":"L-HEVC"}]},
                {"contentId":"AVC","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L-AVC"}]}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(play))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        val p = resolve.resolveVod("X").dato()!!

        assertEquals("https://cdn.example.com/vod/AVC_media.ts", p.url)
        assertEquals("h264", p.videoCodec)
        assertEquals("L-AVC", p.headers["Content-License"])
    }

    @Test
    fun `entre dos h264 gana la que el portal ofrecio primero`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"PRIMERA","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L1"}]},
                {"contentId":"SEGUNDA","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L2"}]}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(play))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))

        val p = MagisResolve(fake, sesionDeTest(fake)).resolveVod("X").dato()!!

        assertEquals("https://cdn.example.com/vod/PRIMERA_media.ts", p.url)
    }

    @Test
    fun `sign_type parecido no cuenta como cfl`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta(
            "v14/getSlbInfo",
            MagisResult.Ok(
                JSONObject(
                    """{"cdn_list":[{"tag":"vod","main_addr":"cdn.example.com","url_list":[
                        {"tag":"free","url":"sign_type=cflx&token=ABC"}
                    ]}]}""",
                ),
            ),
        )

        val r = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        assertEquals("sin_cdn_vod", (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `un CDN que no es del tier libre no sirve`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista(tag = "pay")))

        val r = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        assertEquals("sin_cdn_vod", (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `los subtitulos del portal viajan, y los idiomas sin archivo se descartan`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"M1","videoFormat":"mp4","encodeFormat":"h264","licenseList":[{"license":"L"}]}
            ]}],"subtitleList":[
                {"language":"es","file":[{"url":"https://s/es.srt","fileType":"srt"}]},
                {"language":"pt","file":[]},
                {"language":"en","file":[{"url":"","fileType":"vtt"}]}
            ]}]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(play))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))

        val p = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1").dato()!!

        assertEquals(listOf(MagisSubtitulo("es", "https://s/es.srt", "srt")), p.subtitulos)
    }

    @Test
    fun `la duracion se entiende en sus tres formas y lo raro vale cero`() = runTest {
        val casos = mapOf(
            """"01:02:03"""" to 3723_000L,
            """"02:03"""" to 123_000L,
            """"7010"""" to 7_010_000L,
            "7010" to 7_010_000L,
            """"un rato"""" to 0L,
        )
        for ((crudo, esperado) in casos) {
            val play = JSONObject(
                """{"episodeList":[{"totalMovieList":[{"movieList":[
                    {"contentId":"M1","videoFormat":"mp4","encodeFormat":"h264","duration":$crudo,
                     "licenseList":[{"license":"L"}]}
                ]}]}]}""",
            )
            val fake = FakePortalClient()
            fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(play))
            fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))

            val p = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1").dato()!!

            assertEquals("duracion $crudo", esperado, p.durationMs)
        }
    }

    @Test
    fun `el capitulo de una serie viaja con su seriesContentId`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "EP1")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))

        MagisResolve(fake, sesionDeTest(fake)).resolveVod("EP1", seriesContentId = "SERIE7")

        val (_, bean) = fake.llamadas.first { it.first == "v10/startPlayVOD" }
        assertEquals("EP1", bean["contentId"])
        assertEquals("SERIE7", bean["seriesContentId"])
    }

    @Test
    fun `getSlbInfo se pide una sola vez para dos titulos de la misma sesion`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "A")))
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "B")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        resolve.resolveVod("A")
        val segunda = resolve.resolveVod("B")

        assertEquals(1, fake.vecesLlamado("v14/getSlbInfo"))
        assertEquals("https://cdn.example.com/vod/B_media.mp4", segunda.dato()?.url)
    }

    @Test
    fun `un Content-Auth a punto de vencer no se cachea`() = runTest {
        val fake = FakePortalClient()
        val casiVencido = "sign_type=cfl&token=ABC&expired=${System.currentTimeMillis() / 1000 + 60}"
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "A")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista(auth = casiVencido)))
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "B")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        val resolve = MagisResolve(fake, sesionDeTest(fake))

        val primera = resolve.resolveVod("A")
        resolve.resolveVod("B")

        // Se sirve igual (es lo único que hay) pero no se guarda: la próxima vuelve a preguntar.
        assertEquals(casiVencido, primera.dato()?.headers?.get("Content-Auth"))
        assertEquals(2, fake.vecesLlamado("v14/getSlbInfo"))
    }

    @Test
    fun `el slb se vuelve a pedir cuando cambia el token de la sesion`() = runTest {
        val fake = FakePortalClient()
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u", "t-viejo", "", "sn"))
        val session = MagisSession(fake, store)
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "A")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula(contentId = "B")))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))
        val resolve = MagisResolve(fake, session)

        resolve.resolveVod("A")
        store.guardarSesion(SesionGuardada("u", "t-nuevo", "", "sn"))
        resolve.resolveVod("B")

        assertEquals(2, fake.vecesLlamado("v14/getSlbInfo"))
    }

    @Test
    fun `getSlbInfo manda liveCodeList como arreglo JSON de verdad`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(playDeUnaPelicula()))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbRealista()))

        MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        val (_, bean) = fake.llamadas.first { it.first == "v14/getSlbInfo" }
        val codigos = bean["liveCodeList"] as JSONArray
        assertEquals("masnew_live", codigos.getString(0))
    }

    @Test
    fun `sin licenseList no se inventa una reproduccion`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"M1","videoFormat":"mp4","encodeFormat":"h264"}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(play))

        val r = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        assertEquals("sin_license", (r as MagisResult.PortalError).codigo)
        assertEquals(0, fake.vecesLlamado("v14/getSlbInfo"))
    }

    @Test
    fun `sin episodeList devuelve error y no revienta`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.Ok(JSONObject("""{"returnCode":"0"}""")))

        val r = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        assertEquals("sin_media", (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `si el portal esta caido al pedir la pista, no sigue`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v10/startPlayVOD", MagisResult.RedError(java.io.IOException("sin red")))

        val r = MagisResolve(fake, sesionDeTest(fake)).resolveVod("M1")

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
        assertEquals(0, fake.vecesLlamado("v14/getSlbInfo"))
    }

    private companion object {
        /** Año 2286: un `expired` que no se vence mientras corren los tests. */
        const val LEJANO = "9999999999"
    }
}
