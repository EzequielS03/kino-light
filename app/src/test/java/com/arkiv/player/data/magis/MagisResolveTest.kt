package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisResolveTest {

    /**
     * `getSlbInfo`'s REAL shape (captured against the portal, see arkiv-api's
     * `tests/test_magis_live.py`): `main_addr` hangs off the `cdn` object, a sibling of `url_list`
     * -- NOT of each `url_list` entry. And `url_list[].url` isn't a url: it's a loose querystring,
     * with no scheme or `?`.
     */
    private fun realisticSlb(
        auth: String = "cdn_type=1&sign_type=cfl&token=ABC&expired=$FAR_AWAY",
        tag: String = "free",
        mainAddr: String = "https://cdn.example.com",
        invalidTime: String = "14400",
    ) = JSONObject(
        """{"invalidTime":"$invalidTime","cdn_list":[
            {"tag":"vod","main_addr":"$mainAddr","url_list":[{"tag":"$tag","url":"$auth"}]}
        ]}""",
    )

    private fun moviePlay(
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
    fun `builds the final url and headers for a movie`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb(mainAddr = "cdn.example.com")))
        val resolve = MagisResolve(fake, testSession(fake), appId = "app.id", apkVersion = "49902")

        val r = resolve.resolveVod("M1")

        val p = r.getOrNull() ?: error("expected Ok but got $r")
        assertEquals("https://cdn.example.com/vod/M1_media.mp4", p.url)
        assertEquals("LIC123", p.headers["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", p.headers["User-Agent"])
        assertEquals("app.id", p.headers["App"])
        assertEquals("49902", p.headers["App-Version"])
        assertEquals("cdn_type=1&sign_type=cfl&token=ABC&expired=$FAR_AWAY", p.headers["Content-Auth"])
        assertEquals("video/mp4", p.mime)
        assertEquals("mp4", p.container)
        assertEquals("h264", p.videoCodec)
    }

    @Test
    fun `main_addr with a scheme and trailing slash doesn't duplicate anything`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb(mainAddr = "https://cdn.example.com/")))
        val resolve = MagisResolve(fake, testSession(fake))

        val p = resolve.resolveVod("M1").getOrNull()!!

        assertEquals("https://cdn.example.com/vod/M1_media.mp4", p.url)
    }

    @Test
    fun `a main_addr with a path and http is respected as-is`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse(
            "v14/getSlbInfo",
            MagisResult.Ok(realisticSlb(mainAddr = "http://niguof.vynbszicd.com/v3/youshi/")),
        )

        val p = MagisResolve(fake, testSession(fake)).resolveVod("M1").getOrNull()!!

        assertEquals("http://niguof.vynbszicd.com/v3/youshi/vod/M1_media.mp4", p.url)
    }

    @Test
    fun `a ts is served as ts and declared as mp2t`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse(
            "v10/startPlayVOD",
            MagisResult.Ok(moviePlay(contentId = "T9", videoFormat = "ts")),
        )
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        val resolve = MagisResolve(fake, testSession(fake))

        val p = resolve.resolveVod("T9").getOrNull()!!

        assertEquals("https://cdn.example.com/vod/T9_media.ts", p.url)
        assertEquals("video/mp2t", p.mime)
        assertEquals("ts", p.container)
    }

    @Test
    fun `between h265-mp4 and h264-ts, h264 wins even though it's not mp4`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"HEVC","videoFormat":"mp4","encodeFormat":"h265","licenseList":[{"license":"L-HEVC"}]},
                {"contentId":"AVC","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L-AVC"}]}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(play))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        val resolve = MagisResolve(fake, testSession(fake))

        val p = resolve.resolveVod("X").getOrNull()!!

        assertEquals("https://cdn.example.com/vod/AVC_media.ts", p.url)
        assertEquals("h264", p.videoCodec)
        assertEquals("L-AVC", p.headers["Content-License"])
    }

    @Test
    fun `between two h264s, whichever the portal offered first wins`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"PRIMERA","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L1"}]},
                {"contentId":"SEGUNDA","videoFormat":"ts","encodeFormat":"h264","licenseList":[{"license":"L2"}]}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(play))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))

        val p = MagisResolve(fake, testSession(fake)).resolveVod("X").getOrNull()!!

        assertEquals("https://cdn.example.com/vod/PRIMERA_media.ts", p.url)
    }

    @Test
    fun `a similar-looking sign_type doesn't count as cfl`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse(
            "v14/getSlbInfo",
            MagisResult.Ok(
                JSONObject(
                    """{"cdn_list":[{"tag":"vod","main_addr":"cdn.example.com","url_list":[
                        {"tag":"free","url":"sign_type=cflx&token=ABC"}
                    ]}]}""",
                ),
            ),
        )

        val r = MagisResolve(fake, testSession(fake)).resolveVod("M1")

        assertEquals("sin_cdn_vod", (r as MagisResult.PortalError).code)
    }

    @Test
    fun `a CDN that's not from the free tier doesn't work`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb(tag = "pay")))

        val r = MagisResolve(fake, testSession(fake)).resolveVod("M1")

        assertEquals("sin_cdn_vod", (r as MagisResult.PortalError).code)
    }

    @Test
    fun `the portal's subtitles travel, and languages with no file get discarded`() = runTest {
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
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(play))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))

        val p = MagisResolve(fake, testSession(fake)).resolveVod("M1").getOrNull()!!

        assertEquals(listOf(MagisSubtitle("es", "https://s/es.srt", "srt")), p.subtitles)
    }

    @Test
    fun `the duration is understood in its three forms and anything odd is worth zero`() = runTest {
        val cases = mapOf(
            """"01:02:03"""" to 3723_000L,
            """"02:03"""" to 123_000L,
            """"7010"""" to 7_010_000L,
            "7010" to 7_010_000L,
            """"un rato"""" to 0L,
        )
        for ((raw, expected) in cases) {
            val play = JSONObject(
                """{"episodeList":[{"totalMovieList":[{"movieList":[
                    {"contentId":"M1","videoFormat":"mp4","encodeFormat":"h264","duration":$raw,
                     "licenseList":[{"license":"L"}]}
                ]}]}]}""",
            )
            val fake = FakePortalClient()
            fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(play))
            fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))

            val p = MagisResolve(fake, testSession(fake)).resolveVod("M1").getOrNull()!!

            assertEquals("duration $raw", expected, p.durationMs)
        }
    }

    @Test
    fun `a series' chapter travels with its seriesContentId`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "EP1")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))

        MagisResolve(fake, testSession(fake)).resolveVod("EP1", seriesContentId = "SERIE7")

        val (_, bean) = fake.calls.first { it.first == "v10/startPlayVOD" }
        assertEquals("EP1", bean["contentId"])
        assertEquals("SERIE7", bean["seriesContentId"])
    }

    @Test
    fun `getSlbInfo is requested only once for two titles in the same session`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "A")))
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "B")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        val resolve = MagisResolve(fake, testSession(fake))

        resolve.resolveVod("A")
        val second = resolve.resolveVod("B")

        assertEquals(1, fake.timesCalled("v14/getSlbInfo"))
        assertEquals("https://cdn.example.com/vod/B_media.mp4", second.getOrNull()?.url)
    }

    @Test
    fun `a Content-Auth about to expire isn't cached`() = runTest {
        val fake = FakePortalClient()
        val almostExpired = "sign_type=cfl&token=ABC&expired=${System.currentTimeMillis() / 1000 + 60}"
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "A")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb(auth = almostExpired)))
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "B")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        val resolve = MagisResolve(fake, testSession(fake))

        val first = resolve.resolveVod("A")
        resolve.resolveVod("B")

        // It's still served (it's all there is) but not saved: the next one asks again.
        assertEquals(almostExpired, first.getOrNull()?.headers?.get("Content-Auth"))
        assertEquals(2, fake.timesCalled("v14/getSlbInfo"))
    }

    @Test
    fun `the slb gets requested again when the session's token changes`() = runTest {
        val fake = FakePortalClient()
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u", "t-old", "", "sn"))
        val session = MagisSession(fake, store)
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "A")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay(contentId = "B")))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))
        val resolve = MagisResolve(fake, session)

        resolve.resolveVod("A")
        store.saveSession(StoredSession("u", "t-new", "", "sn"))
        resolve.resolveVod("B")

        assertEquals(2, fake.timesCalled("v14/getSlbInfo"))
    }

    @Test
    fun `getSlbInfo sends liveCodeList as a real JSON array`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(moviePlay()))
        fake.queueResponse("v14/getSlbInfo", MagisResult.Ok(realisticSlb()))

        MagisResolve(fake, testSession(fake)).resolveVod("M1")

        val (_, bean) = fake.calls.first { it.first == "v14/getSlbInfo" }
        val codes = bean["liveCodeList"] as JSONArray
        assertEquals("masnew_live", codes.getString(0))
    }

    @Test
    fun `with no licenseList, playback isn't made up`() = runTest {
        val play = JSONObject(
            """{"episodeList":[{"totalMovieList":[{"movieList":[
                {"contentId":"M1","videoFormat":"mp4","encodeFormat":"h264"}
            ]}]}]}""",
        )
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(play))

        val r = MagisResolve(fake, testSession(fake)).resolveVod("M1")

        assertEquals("sin_license", (r as MagisResult.PortalError).code)
        assertEquals(0, fake.timesCalled("v14/getSlbInfo"))
    }

    @Test
    fun `with no episodeList it returns an error and doesn't crash`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.Ok(JSONObject("""{"returnCode":"0"}""")))

        val r = MagisResolve(fake, testSession(fake)).resolveVod("M1")

        assertEquals("sin_media", (r as MagisResult.PortalError).code)
    }

    @Test
    fun `if the portal is down when asking for the track, it doesn't continue`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v10/startPlayVOD", MagisResult.RedError(java.io.IOException("sin red")))

        val r = MagisResolve(fake, testSession(fake)).resolveVod("M1")

        assertTrue("expected RedError but got $r", r is MagisResult.RedError)
        assertEquals(0, fake.timesCalled("v14/getSlbInfo"))
    }

    private companion object {
        /** Year 2286: an `expired` that doesn't run out while the tests run. */
        const val FAR_AWAY = "9999999999"
    }
}
