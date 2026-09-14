package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSourceTest {
    @Test fun `an id with no known prefix is an unknown source`() {
        assertEquals(SourceKind.UNKNOWN, PlayerSource.kindFor("some-old-archive-identifier"))
    }

    @Test fun ditu_prefix_is_ditu() {
        assertEquals(SourceKind.DITU, PlayerSource.kindFor("ditu:12345::e1"))
    }

    @Test fun magis_sigue_siendo_magis() {
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }

    // --- is it a live channel? ------------------------------------------------------------------

    @Test fun el_vivo_de_magis_es_un_canal_en_vivo() {
        assertTrue(PlayerSource.isLiveChannel("${PlayerSource.LIVE_PREFIX}caracoltv"))
    }

    /** The case that was missing: a Caracol channel came out with a movie's progress bar. */
    @Test fun el_vivo_de_caracol_es_un_canal_en_vivo() {
        assertTrue(PlayerSource.isLiveChannel("${DituLive.PREFIX}12345"))
    }

    @Test fun el_vod_de_caracol_no_es_un_canal_en_vivo() {
        assertFalse(PlayerSource.isLiveChannel("ditu:12345::e1"))
    }

    @Test fun el_vod_de_magis_no_es_un_canal_en_vivo() {
        assertFalse(PlayerSource.isLiveChannel("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
        assertFalse(PlayerSource.isLiveChannel("someitem::3"))
    }
}
