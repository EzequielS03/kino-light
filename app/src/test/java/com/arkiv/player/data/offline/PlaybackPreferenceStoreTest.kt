package com.arkiv.player.data.offline

import com.arkiv.player.data.db.NucLibraryItemEntity
import com.arkiv.player.data.db.NucLibraryItemDao
import com.arkiv.player.data.db.SeriesPlaybackPrefEntity
import com.arkiv.player.data.db.SeriesPlaybackPrefDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPreferenceStoreTest {
    private fun mockNucLibraryItemDao(): NucLibraryItemDao = object : NucLibraryItemDao {
        private val items = mutableMapOf<Triple<String, Int, Int>, NucLibraryItemEntity>()

        override suspend fun find(seriesId: String, season: Int, episode: Int): NucLibraryItemEntity? {
            return items[Triple(seriesId, season, episode)]
        }

        override suspend fun forSeries(seriesId: String): List<NucLibraryItemEntity> {
            return items.values.filter { it.seriesId == seriesId }
        }

        override suspend fun upsertAll(items: List<NucLibraryItemEntity>) {
            items.forEach { item ->
                this.items[Triple(item.seriesId, item.season, item.episode)] = item
            }
        }

        override suspend fun clearForSeries(seriesId: String) {
            items.entries.removeAll { it.value.seriesId == seriesId }
        }

        // Mismo orden que la @Query real (seriesId, season, episode) para que el fake no dependa
        // del orden de inserción del mapa.
        override suspend fun getAll(): List<NucLibraryItemEntity> =
            items.values.sortedWith(compareBy({ it.seriesId }, { it.season }, { it.episode }))
    }

    private fun mockSeriesPlaybackPrefDao(): SeriesPlaybackPrefDao = object : SeriesPlaybackPrefDao {
        private val prefs = mutableMapOf<String, SeriesPlaybackPrefEntity>()

        override suspend fun get(seriesId: String): SeriesPlaybackPrefEntity? {
            return prefs[seriesId]
        }

        override suspend fun upsert(pref: SeriesPlaybackPrefEntity) {
            prefs[pref.seriesId] = pref
        }
    }

    @Test
    fun noDownloadedCopyAlwaysLiveNoAsk() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        val decision = store.decide("tt123", season = 1, episode = 1)
        assertEquals(PlaybackDecision.Play(PlaybackChoice.LIVE, null), decision)
    }

    @Test
    fun firstTimeWithDownloadedCopyAskFirst() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        libraryDao.upsertAll(
            listOf(
                NucLibraryItemEntity(
                    itemId = 42L,
                    seriesId = "tt123",
                    season = 1,
                    episode = 1,
                    status = "done",
                    sizeBytes = 1024L,
                    syncedAt = System.currentTimeMillis(),
                )
            )
        )
        val decision = store.decide("tt123", season = 1, episode = 1)
        assertEquals(PlaybackDecision.AskFirst(42L), decision)
    }

    @Test
    fun preferenceNucAndEpisodeDownloadedPlayNucWithItemId() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        libraryDao.upsertAll(
            listOf(
                NucLibraryItemEntity(
                    itemId = 42L,
                    seriesId = "tt123",
                    season = 1,
                    episode = 1,
                    status = "done",
                    sizeBytes = 1024L,
                    syncedAt = System.currentTimeMillis(),
                )
            )
        )
        prefDao.upsert(
            SeriesPlaybackPrefEntity(
                seriesId = "tt123",
                preference = "NUC",
                asked = true,
            )
        )
        val decision = store.decide("tt123", season = 1, episode = 1)
        assertEquals(PlaybackDecision.Play(PlaybackChoice.NUC, 42L), decision)
    }

    @Test
    fun preferenceNucButThisEpisodeNotDownloadedPlayLiveNoReask() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        prefDao.upsert(
            SeriesPlaybackPrefEntity(
                seriesId = "tt123",
                preference = "NUC",
                asked = true,
            )
        )
        val decision = store.decide("tt123", season = 1, episode = 1)
        assertEquals(PlaybackDecision.Play(PlaybackChoice.LIVE, null), decision)
    }

    @Test
    fun preferenceLiveAlwaysPlayLiveRegardlessOfDownloadedStatus() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        libraryDao.upsertAll(
            listOf(
                NucLibraryItemEntity(
                    itemId = 42L,
                    seriesId = "tt123",
                    season = 1,
                    episode = 1,
                    status = "done",
                    sizeBytes = 1024L,
                    syncedAt = System.currentTimeMillis(),
                )
            )
        )
        prefDao.upsert(
            SeriesPlaybackPrefEntity(
                seriesId = "tt123",
                preference = "LIVE",
                asked = true,
            )
        )
        val decision = store.decide("tt123", season = 1, episode = 1)
        assertEquals(PlaybackDecision.Play(PlaybackChoice.LIVE, null), decision)
    }

    @Test
    fun rememberPersistsTheChoiceAndSetsAskedTrue() = runBlocking {
        val libraryDao = mockNucLibraryItemDao()
        val prefDao = mockSeriesPlaybackPrefDao()
        val store = PlaybackPreferenceStore(prefDao, libraryDao)

        store.remember("tt123", PlaybackChoice.NUC)
        val stored = prefDao.get("tt123")
        assertEquals("tt123", stored?.seriesId)
        assertEquals("NUC", stored?.preference)
        assertEquals(true, stored?.asked)
    }
}
