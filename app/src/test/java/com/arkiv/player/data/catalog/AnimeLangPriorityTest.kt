package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeLangPriorityTest {
    @Test
    fun `orden de prioridad es Latino Castellano Dual Jap Otros Ingles`() {
        val ordered = listOf(
            TorrentLang.ENGLISH, TorrentLang.OTHER, TorrentLang.JAP_SUB,
            TorrentLang.DUAL, TorrentLang.CASTELLANO, TorrentLang.LATINO,
        ).sortedBy { animeLangPriority(it) }
        assertEquals(
            listOf(
                TorrentLang.LATINO, TorrentLang.CASTELLANO, TorrentLang.DUAL,
                TorrentLang.JAP_SUB, TorrentLang.OTHER, TorrentLang.ENGLISH,
            ),
            ordered,
        )
    }
}
