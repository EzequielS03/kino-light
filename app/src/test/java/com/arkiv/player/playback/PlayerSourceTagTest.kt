package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTagTest {

    private fun tag(
        referer: String? = null,
        userAgent: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = PlayerSourceTag(
        kind = SourceKind.WEB,
        openingStartMs = null, openingEndMs = null, endingStartMs = null, castUrl = null,
        referer = referer, userAgent = userAgent, extraHeaders = extra,
    )

    @Test
    fun `allHeaders junta referer user-agent y los extra`() {
        val t = tag(
            referer = "https://serieskao.top/",
            userAgent = "Ranger/4.9.4-17294ac0",
            extra = mapOf("Content-Auth" to "A", "Content-License" to "L"),
        )
        assertEquals(
            mapOf(
                "Referer" to "https://serieskao.top/",
                "User-Agent" to "Ranger/4.9.4-17294ac0",
                "Content-Auth" to "A",
                "Content-License" to "L",
            ),
            t.allHeaders,
        )
    }

    @Test
    fun `allHeaders omite los vacios`() {
        assertEquals(emptyMap<String, String>(), tag(referer = "", userAgent = null).allHeaders)
    }

    @Test
    fun `sin extras el comportamiento es el de antes`() {
        assertEquals(mapOf("Referer" to "https://x/"), tag(referer = "https://x/").allHeaders)
    }
}
