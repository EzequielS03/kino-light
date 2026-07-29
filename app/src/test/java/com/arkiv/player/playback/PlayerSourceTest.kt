package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTest {
    @Test fun torrent_prefix_is_torrent() {
        assertEquals(SourceKind.TORRENT, PlayerSource.kindFor("torrent:abc::1"))
    }
    @Test fun other_is_archive() {
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("someitem::3"))
    }
    @Test fun web_prefix_is_web() {
        assertEquals(SourceKind.WEB, PlayerSource.kindFor("web:https://x/pelicula/y"))
    }
}
