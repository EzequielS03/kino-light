package com.arkiv.player.data.offline

import com.arkiv.player.data.db.NucLibraryItemDao
import com.arkiv.player.data.db.SeriesPlaybackPrefDao
import com.arkiv.player.data.db.SeriesPlaybackPrefEntity

enum class PlaybackChoice { NUC, LIVE }

sealed class PlaybackDecision {
    data class Play(val choice: PlaybackChoice, val itemId: Long?) : PlaybackDecision()
    data class AskFirst(val itemId: Long) : PlaybackDecision()
}

class PlaybackPreferenceStore(
    private val prefDao: SeriesPlaybackPrefDao,
    private val libraryDao: NucLibraryItemDao,
) {
    suspend fun decide(seriesId: String, season: Int, episode: Int): PlaybackDecision {
        val downloaded = libraryDao.find(seriesId, season, episode)
        val pref = prefDao.get(seriesId)
        if (downloaded == null) return PlaybackDecision.Play(PlaybackChoice.LIVE, null)
        if (pref == null || !pref.asked) return PlaybackDecision.AskFirst(downloaded.itemId)
        return when (pref.preference) {
            "NUC" -> PlaybackDecision.Play(PlaybackChoice.NUC, downloaded.itemId)
            else -> PlaybackDecision.Play(PlaybackChoice.LIVE, null)
        }
    }

    suspend fun remember(seriesId: String, choice: PlaybackChoice) {
        prefDao.upsert(SeriesPlaybackPrefEntity(seriesId, choice.name, asked = true))
    }
}
