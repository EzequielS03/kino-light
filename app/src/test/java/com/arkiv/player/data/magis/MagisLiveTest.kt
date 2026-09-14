package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisLiveTest {

    /** `getSlbInfo`'s real shape in live mode: `main_addr` on the `cdn` object, `url` = querystring. */
    private fun liveSlb(
        vararg hosts: Pair<String, String>,
        invalidTime: String = "14400",
    ): JSONObject {
        val cdns = hosts.joinToString(",") { (host, auth) ->
            """{"tag":"live","main_addr":"$host","url_list":[{"url":"$auth"}]}"""
        }
        return JSONObject("""{"invalidTime":"$invalidTime","cdn_list":[$cdns]}""")
    }

    private val validAuth = "cdn_type=1&sign_type=cfl&token=${"a".repeat(32)}"

    @Test
    fun `takes playCode and license from the SAME entry, doesn't mix`() = runTest {
        val playLive = JSONObject(
            """{"liveAddressList":[
                {"playCode":"cyx-2EF7E10E40C1ac19D6A9F3ED4CD2","license":"LIC-CORRECTO"},
                {"playCode":"cyx-OTRO","license":"LIC-SENUELO"}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse("v4/startPlayLive", MagisResult.Ok(playLive))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(liveSlb("http://live.example.com" to validAuth)))

        val s = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("cyx-RCNHD").getOrNull()!!

        assertEquals("LIC-CORRECTO", s.license)
        assertEquals("cyx-2EF7E10E40C1ac19D6A9F3ED4CD2", s.playCode)
        assertEquals("live.example.com", s.cflHost)
        assertEquals("cyx-RCNHD", s.channel)
    }

    @Test
    fun `with no linked account it returns an error without calling the portal`() = runTest {
        val fake = FakePortalClient()

        val r = MagisLive(fake, testSessionWithoutAccount(fake)).resolveChannel("cyx-RCNHD")

        assertTrue(fake.calls.isEmpty())
        assertEquals(MagisLive.NO_ACCOUNT, (r as MagisResult.PortalError).code)
    }

    @Test
    fun `getSlbInfo asks for the CHANNEL's code, not the playCode`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"cyx-INTERNO","license":"L"}]}""")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(liveSlb("live.example.com" to validAuth)))

        MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("cyx-RCNHD")

        val (_, bean) = fake.calls.first { it.first == "v14/getSlbInfo" }
        assertEquals("cyx-RCNHD", (bean["liveCodeList"] as JSONArray).getString(0))
    }

    @Test
    fun `if the first address carries no playCode, the first complete one wins`() = runTest {
        val playLive = JSONObject(
            """{"liveAddressList":[
                {"playCode":"","license":"LIC-SIN-CODIGO"},
                {"playCode":"cyx-COMPLETA","license":"LIC-COMPLETA"}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse("v4/startPlayLive", MagisResult.Ok(playLive))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(liveSlb("live.example.com" to validAuth)))

        val s = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("cyx-RCNHD").getOrNull()!!

        assertEquals("cyx-COMPLETA", s.playCode)
        assertEquals("LIC-COMPLETA", s.license)
    }

    @Test
    fun `a channel whose playCode the portal doesn't send is served with the channel's code`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"license":"LIC-UNICA"}]}""")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(liveSlb("live.example.com" to validAuth)))

        val s = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("cyx-RCNHD").getOrNull()!!

        assertEquals("cyx-RCNHD", s.playCode)
        assertEquals("LIC-UNICA", s.license)
    }

    @Test
    fun `returns ALL of the live CDNs, each with its own authBase`() = runTest {
        val secondAuth = "sign_type=cfl&token=${"b".repeat(32)}"
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.queueResponse(
            "v14/getSlbInfo",
            MagisResult.Ok(
                liveSlb(
                    "http://primero.cdn/v3/youshi/" to validAuth,
                    "https://segundo.cdn" to secondAuth,
                ),
            ),
        )

        val s = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c").getOrNull()!!

        assertEquals(2, s.cdns.size)
        // The main_addr's path is discarded: the proxy builds http://<host>/live/<playCode>.m3u8.
        assertEquals("primero.cdn", s.cdns[0].cflHost)
        assertEquals(validAuth, s.cdns[0].authBase)
        assertEquals("segundo.cdn", s.cdns[1].cflHost)
        assertEquals(secondAuth, s.cdns[1].authBase)
        assertEquals("primero.cdn", s.cflHost)
    }

    @Test
    fun `CDNs that aren't for live or don't ask for cfl don't count`() = runTest {
        val slb = JSONObject(
            """{"cdn_list":[
                {"tag":"vod","main_addr":"http://vod.cdn","url_list":[{"url":"$validAuth"}]},
                {"tag":"live","main_addr":"http://nocfl.cdn","url_list":[{"url":"sign_type=cflx&token=x"}]},
                {"tag":"live","main_addr":"http://si.cdn","url_list":[{"url":"$validAuth"}]}
            ]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(slb))

        val s = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c").getOrNull()!!

        assertEquals(listOf("si.cdn"), s.cdns.map { it.cflHost })
    }

    @Test
    fun `with no servable live CDN it returns an error`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(JSONObject("""{"cdn_list":[]}""")))

        val r = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c")

        assertEquals(MagisLive.NO_CDN, (r as MagisResult.PortalError).code)
    }

    @Test
    fun `an empty license isn't let through to the proxy`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":""}]}""")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(liveSlb("live.cdn" to validAuth)))

        val r = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c")

        assertEquals(MagisLive.NO_LICENSE, (r as MagisResult.PortalError).code)
    }

    @Test
    fun `an authBase with no 32-hex token isn't let through to the proxy`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v4/startPlayLive",
            MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
        )
        fake.queueResponse(
            "v14/getSlbInfo",
            MagisResult.Ok(liveSlb("live.cdn" to "sign_type=cfl&token=corto")),
        )

        val r = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c")

        assertEquals(MagisLive.NO_TOKEN, (r as MagisResult.PortalError).code)
    }

    @Test
    fun `the validity comes from the portal's invalidTime, and without it from the conservative value`() = runTest {
        suspend fun validityWith(invalidTime: String): Long {
            val fake = FakePortalClient()
            fake.queueResponse(
                "v4/startPlayLive",
                MagisResult.Ok(JSONObject("""{"liveAddressList":[{"playCode":"pc","license":"L"}]}""")),
            )
            fake.queueResponse(
                "v14/getSlbInfo",
                MagisResult.Ok(liveSlb("live.cdn" to validAuth, invalidTime = invalidTime)),
            )
            val live = MagisLive(fake, testSessionWithAccount(fake), nowMs = { 1_000_000_000_000L })
            return live.resolveChannel("c").getOrNull()!!.expiresAt
        }

        assertEquals(1_000_000_000L + 14400, validityWith("14400"))
        assertEquals(1_000_000_000L + MagisLive.CHANNEL_TTL_S, validityWith(""))
        assertEquals(1_000_000_000L + MagisLive.CHANNEL_TTL_S, validityWith("basura"))
    }

    @Test
    fun `the portal's code survives so the UI knows it has to re-link`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v4/startPlayLive", MagisResult.PortalError("aaa100028", "未登录！"))
        // withValidSession's retry relogs in and fails again: the error that arrives is the real one.
        fake.queueResponse("v8/login", portalOk("userId" to "u", "userToken" to "t2"))
        fake.queueResponse("v4/startPlayLive", MagisResult.PortalError("aaa100028", "未登录！"))

        val r = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c")

        assertEquals("aaa100028", (r as MagisResult.PortalError).code)
        assertEquals(0, fake.timesCalled("v14/getSlbInfo"))
    }

    @Test
    fun `with no liveAddressList it returns an error and doesn't ask for a CDN`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v4/startPlayLive", MagisResult.Ok(JSONObject("""{"returnCode":"0"}""")))

        val r = MagisLive(fake, testSessionWithAccount(fake)).resolveChannel("c")

        assertEquals(MagisLive.NO_ADDRESSES, (r as MagisResult.PortalError).code)
        assertEquals(0, fake.timesCalled("v14/getSlbInfo"))
    }
}
