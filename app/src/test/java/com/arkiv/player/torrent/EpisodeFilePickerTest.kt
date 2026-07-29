package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeFilePickerTest {

    @Test
    fun `pack SxxEyy elige el indice del episodio pedido`() {
        val names = listOf("Show S01E01.mkv", "Show S01E05.mkv", "Show S01E10.mkv")
        assertEquals(1, EpisodeFilePicker.pick(names, season = 1, episode = 5))
    }

    @Test
    fun `formato NxNN (1x05) elige el indice correcto`() {
        val names = listOf("Show 1x01.mkv", "Show 1x05.mkv")
        assertEquals(1, EpisodeFilePicker.pick(names, season = 1, episode = 5))
    }

    @Test
    fun `numero absoluto de anime elige el indice correcto`() {
        val names = listOf("One Piece 1084.mkv", "One Piece 1085.mkv")
        assertEquals(1, EpisodeFilePicker.pick(names, season = 1, episode = 1085, absoluteEpisode = 1085))
    }

    @Test
    fun `un solo archivo devuelve indice 0 sin necesitar match`() {
        val names = listOf("Movie 1080p x264.mkv")
        assertEquals(0, EpisodeFilePicker.pick(names, season = 0, episode = 0))
    }

    @Test
    fun `pack sin que el numero de episodio aparezca devuelve null`() {
        val names = listOf("Show S01E01.mkv", "Show S01E02.mkv", "Show S01E03.mkv")
        assertNull(EpisodeFilePicker.pick(names, season = 1, episode = 99))
    }

    @Test
    fun `no confunde 1080p con el numero de episodio en match suelto`() {
        val names = listOf("Show 1080p 05.mkv", "Show 1080p 06.mkv")
        assertEquals(0, EpisodeFilePicker.pick(names, season = 0, episode = 5))
    }

    @Test
    fun `no confunde codec h265 con el numero de episodio en match suelto`() {
        val names = listOf("Show 07 h265.mkv", "Show 08 h265.mkv")
        assertEquals(0, EpisodeFilePicker.pick(names, season = 0, episode = 7))
    }

    @Test
    fun `capitulo espanol Cap NEE de dos digitos`() {
        val names = listOf("Show Cap 101.mkv", "Show Cap 205.mkv")
        assertEquals(1, EpisodeFilePicker.pick(names, season = 2, episode = 5))
    }
}
