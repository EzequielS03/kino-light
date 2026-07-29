package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class PackFileParserTest {
    private fun p(s: String) = PackFileParser.parse(s)

    @Test fun `SxxEyy`() = assertEquals(PackFileInfo(1, 2, null), p("House.of.the.Dragon.S01E02.1080p.x264-GRP.mkv"))
    @Test fun `NxNN`() = assertEquals(PackFileInfo(1, 5, null), p("Show 1x05 [720p].mkv"))
    @Test fun `Cap NEE espanol`() = assertEquals(PackFileInfo(1, 2, null), p("Serie Cap.102 Latino.mkv"))
    @Test fun `absoluto anime`() = assertEquals(PackFileInfo(null, null, 1085), p("One Piece - 1085 [1080p].mkv"))
    @Test fun `no confunde resolucion ni año`() = assertEquals(PackFileInfo(1, 2, null), p("The.Show.S01E02.1080p.h265.2019.mkv"))
    @Test fun `basura sin numero de episodio`() = assertEquals(PackFileInfo(null, null, null), p("readme.txt"))
}
