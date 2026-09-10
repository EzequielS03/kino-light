package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTest {
    @Test fun other_is_archive() {
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("someitem::3"))
    }

    @Test fun ditu_prefix_is_ditu() {
        assertEquals(SourceKind.DITU, PlayerSource.kindFor("ditu:12345::e1"))
    }

    @Test fun magis_sigue_siendo_magis() {
        assertEquals(SourceKind.MAGIS, PlayerSource.kindFor("magis:2AD2591D4242471D96B68FF04FFD2784::e6"))
    }
}
