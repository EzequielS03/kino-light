package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTagTest {

    private fun tag(
        referer: String? = null,
        userAgent: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = PlayerSourceTag(
        kind = SourceKind.UNKNOWN,
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

    /**
     * GUARDIA DEL IPC. El tag NO cruza de `MediaController` a `MediaSession`: se desarma en los
     * extras que arma `PlayerScreen.localMediaItems` y se rearma en
     * `PlaybackService.MediaItemResolverCallback`. Un campo que se agregue acá y no en esos dos
     * lugares llega del otro lado con su valor por defecto, EN SILENCIO — sin error, sin log, sin
     * nada. Ya pasó una vez: `preferirSoftware` se quedaba en false y los HEVC de magis seguían
     * abriendo por hardware, que es exactamente lo que ese campo venía a evitar.
     *
     * Si este test falla es porque agregaste (o sacaste) un campo. Lo que hay que hacer NO es
     * actualizar la lista de acá y seguir: es cablearlo en los DOS lugares de arriba y recién
     * después sumarlo a esta lista.
     */
    @Test
    fun `todo campo del tag tiene que viajar por el IPC`() {
        val cableados = setOf(
            "kind", "openingStartMs", "openingEndMs", "endingStartMs", "castUrl",
            "referer", "userAgent", "proxyUrl", "extraHeaders", "preferirSoftware",
        )
        val declarados = PlayerSourceTag::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(
            "Campo del tag sin cablear en el IPC (ver el KDoc de este test)",
            cableados,
            declarados,
        )
    }
}
