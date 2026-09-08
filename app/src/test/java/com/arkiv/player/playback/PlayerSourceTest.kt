package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTest {
    @Test fun other_is_archive() {
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("someitem::3"))
    }
}
