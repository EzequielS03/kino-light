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

    // --- ¿es un canal en vivo? ----------------------------------------------------------------

    @Test fun el_vivo_de_magis_es_un_canal_en_vivo() {
        assertTrue(PlayerSource.esCanalEnVivo("${PlayerSource.LIVE_PREFIX}caracoltv"))
    }

    /** El caso que faltaba: un canal de Caracol salía con la barra de avance de una película. */
    @Test fun el_vivo_de_caracol_es_un_canal_en_vivo() {
        assertTrue(PlayerSource.esCanalEnVivo("${DituVivo.PREFIX}12345"))
    }

    @Test fun el_vod_de_caracol_no_es_un_canal_en_vivo() {
        assertFalse(PlayerSource.esCanalEnVivo("ditu:12345::e1"))
    }

    @Test fun el_vod_de_magis_no_es_un_canal_en_vivo() {
        assertFalse(PlayerSource.esCanalEnVivo("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
        assertFalse(PlayerSource.esCanalEnVivo("someitem::3"))
    }
}
