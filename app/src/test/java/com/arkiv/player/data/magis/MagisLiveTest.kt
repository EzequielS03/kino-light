package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisLiveTest {

    /** Forma real de `getSlbInfo` en modo vivo: `main_addr` en el objeto `cdn`, `url` = querystring. */
    private fun slbDeVivo(
        vararg hosts: Pair<String, String>,
        invalidTime: String = "14400",
    ): JSONObject {
        val cdns = hosts.joinToString(",") { (host, auth) ->
            """{"tag":"live","main_addr":"$host","url_list":[{"url":"$auth"}]}"""
        }
        return JSONObject("""{"invalidTime":"$invalidTime","cdn_list":[$cdns]}""")
    }

    private val authValido = "cdn_type=1&sign_type=cfl&token=${"a".repeat(32)}"

    @Test
    fun `toma playCode y license de la MISMA entrada, no mezcla`() = runTest {
        val playLive = JSONObject(
            """{"liveAddressList":[
                {"playCode":"cyx-2EF7E10E40C1ac19D6A9F3ED4CD2","license":"LIC-CORRECTO"},
                {"playCode":"cyx-OTRO","license":"LIC-SENUELO"}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.Ok(playLive))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbDeVivo("http://live.example.com" to authValido)))

        val s = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("cyx-RCNHD").dato()!!

        assertEquals("LIC-CORRECTO", s.license)
        assertEquals("cyx-2EF7E10E40C1ac19D6A9F3ED4CD2", s.playCode)
        assertEquals("live.example.com", s.cflHost)
        assertEquals("cyx-RCNHD", s.channel)
    }

    @Test
    fun `sin cuenta vinculada devuelve error sin llamar al portal`() = runTest {
        val fake = FakePortalClient()

        val r = MagisLive(fake, sesionDeTestSinCuenta(fake)).resolveChannel("cyx-RCNHD")

        assertTrue(fake.llamadas.isEmpty())
        assertEquals(MagisLive.SIN_CUENTA, (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `getSlbInfo pide el codigo del CANAL, no el playCode`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"cyx-INTERNO","license":"L"}]}""")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbDeVivo("live.example.com" to authValido)))

        MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("cyx-RCNHD")

        val (_, bean) = fake.llamadas.first { it.first == "v14/getSlbInfo" }
        assertEquals("cyx-RCNHD", (bean["liveCodeList"] as JSONArray).getString(0))
    }

    @Test
    fun `si la primera direccion no trae playCode, gana la primera completa`() = runTest {
        val playLive = JSONObject(
            """{"liveAddressList":[
                {"playCode":"","license":"LIC-SIN-CODIGO"},
                {"playCode":"cyx-COMPLETA","license":"LIC-COMPLETA"}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.Ok(playLive))
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbDeVivo("live.example.com" to authValido)))

        val s = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("cyx-RCNHD").dato()!!

        assertEquals("cyx-COMPLETA", s.playCode)
        assertEquals("LIC-COMPLETA", s.license)
    }

    @Test
    fun `un canal cuyo playCode el portal no manda se sirve con el codigo del canal`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"license":"LIC-UNICA"}]}""")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbDeVivo("live.example.com" to authValido)))

        val s = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("cyx-RCNHD").dato()!!

        assertEquals("cyx-RCNHD", s.playCode)
        assertEquals("LIC-UNICA", s.license)
    }

    @Test
    fun `devuelve TODOS los CDN de vivo, cada uno con su propio authBase`() = runTest {
        val authSegundo = "sign_type=cfl&token=${"b".repeat(32)}"
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.encolarRespuesta(
            "v14/getSlbInfo",
            MagisResult.Ok(
                slbDeVivo(
                    "http://primero.cdn/v3/youshi/" to authValido,
                    "https://segundo.cdn" to authSegundo,
                ),
            ),
        )

        val s = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c").dato()!!

        assertEquals(2, s.cdns.size)
        // El path del main_addr se descarta: el proxy arma http://<host>/live/<playCode>.m3u8.
        assertEquals("primero.cdn", s.cdns[0].cflHost)
        assertEquals(authValido, s.cdns[0].authBase)
        assertEquals("segundo.cdn", s.cdns[1].cflHost)
        assertEquals(authSegundo, s.cdns[1].authBase)
        assertEquals("primero.cdn", s.cflHost)
    }

    @Test
    fun `los CDN que no son de vivo o no piden cfl no cuentan`() = runTest {
        val slb = JSONObject(
            """{"cdn_list":[
                {"tag":"vod","main_addr":"http://vod.cdn","url_list":[{"url":"$authValido"}]},
                {"tag":"live","main_addr":"http://nocfl.cdn","url_list":[{"url":"sign_type=cflx&token=x"}]},
                {"tag":"live","main_addr":"http://si.cdn","url_list":[{"url":"$authValido"}]}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slb))

        val s = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c").dato()!!

        assertEquals(listOf("si.cdn"), s.cdns.map { it.cflHost })
    }

    @Test
    fun `sin ningun CDN de vivo servible devuelve error`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(JSONObject("""{"cdn_list":[]}""")))

        val r = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c")

        assertEquals(MagisLive.SIN_CDN, (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `una licencia vacia no se deja pasar al proxy`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":""}]}""")),
        )
        fake.encolarRespuesta("v14/getSlbInfo", MagisResult.Ok(slbDeVivo("live.cdn" to authValido)))

        val r = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c")

        assertEquals(MagisLive.SIN_LICENSE, (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `un authBase sin token de 32 hex no se deja pasar al proxy`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.encolarRespuesta(
            "v14/getSlbInfo",
            MagisResult.Ok(slbDeVivo("live.cdn" to "sign_type=cfl&token=corto")),
        )

        val r = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c")

        assertEquals(MagisLive.SIN_TOKEN, (r as MagisResult.PortalError).codigo)
    }

    @Test
    fun `la vigencia sale del invalidTime del portal, y sin el del valor conservador`() = runTest {
        suspend fun vigenciaCon(invalidTime: String): Long {
            val fake = FakePortalClient()
            fake.encolarRespuesta(
                "v4/startPlayLive",
                MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
            )
            fake.encolarRespuesta(
                "v14/getSlbInfo",
                MagisResult.Ok(slbDeVivo("live.cdn" to authValido, invalidTime = invalidTime)),
            )
            val live = MagisLive(fake, sesionDeTestConCuenta(fake), ahoraMs = { 1_000_000_000_000L })
            return live.resolveChannel("c").dato()!!.expiresAt
        }

        assertEquals(1_000_000_000L + 14400, vigenciaCon("14400"))
        assertEquals(1_000_000_000L + MagisLive.TTL_CANAL_S, vigenciaCon(""))
        assertEquals(1_000_000_000L + MagisLive.TTL_CANAL_S, vigenciaCon("basura"))
    }

    @Test
    fun `el codigo del portal sobrevive para que la UI sepa que hay que revincular`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.PortalError("aaa100028", "未登录！"))
        // El reintento de conSesionValida relogea y vuelve a fallar: el error que llega es el real.
        fake.encolarRespuesta("v8/login", portalOk("userId" to "u", "userToken" to "t2"))
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.PortalError("aaa100028", "未登录！"))

        val r = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c")

        assertEquals("aaa100028", (r as MagisResult.PortalError).codigo)
        assertEquals(0, fake.vecesLlamado("v14/getSlbInfo"))
    }

    @Test
    fun `sin liveAddressList devuelve error y no pide CDN`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v4/startPlayLive", MagisResult.Ok(JSONObject("""{"returnCode":"0"}""")))

        val r = MagisLive(fake, sesionDeTestConCuenta(fake)).resolveChannel("c")

        assertEquals(MagisLive.SIN_DIRECCIONES, (r as MagisResult.PortalError).codigo)
        assertEquals(0, fake.vecesLlamado("v14/getSlbInfo"))
    }
}
