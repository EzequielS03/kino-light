package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFilePickerTest {

    @Test fun matchesBySameBaseNameWithLangSuffix() {
        val files = listOf(0 to "Movie.2003.mkv", 1 to "Movie.2003.es.srt", 2 to "Movie.2003.en.srt")
        assertEquals(listOf(1, 2), SubtitleFilePicker.pick(files, "Movie.2003.mkv"))
    }

    @Test fun picksCorrectEpisodeSubInMultiVideoPack() {
        val files = listOf(0 to "S01E01.mkv", 1 to "S01E02.mkv", 2 to "S01E01.srt")
        assertEquals(listOf(2), SubtitleFilePicker.pick(files, "S01E01.mkv"))
    }

    @Test fun fallsBackToSubsFolder() {
        val files = listOf(0 to "video.mkv", 1 to "Subs/spanish.srt", 2 to "Subs/english.srt")
        assertEquals(listOf(1, 2), SubtitleFilePicker.pick(files, "video.mkv"))
    }

    @Test fun singleVideoTakesAnySub() {
        val files = listOf(0 to "ep.mkv", 1 to "whatever_release_group.srt")
        assertEquals(listOf(1), SubtitleFilePicker.pick(files, "ep.mkv"))
    }

    @Test fun multiVideoNoMatchReturnsEmpty() {
        val files = listOf(0 to "a.mkv", 1 to "b.mkv", 2 to "unrelated.srt")
        assertEquals(emptyList<Int>(), SubtitleFilePicker.pick(files, "a.mkv"))
    }

    @Test fun noSubtitleFilesReturnsEmpty() {
        val files = listOf(0 to "a.mkv", 1 to "poster.jpg")
        assertEquals(emptyList<Int>(), SubtitleFilePicker.pick(files, "a.mkv"))
    }
}
