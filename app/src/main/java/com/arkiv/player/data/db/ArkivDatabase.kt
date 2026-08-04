package com.arkiv.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ItemEntity::class,
        EpisodeEntity::class,
        PlaybackEntity::class,
        DownloadEntity::class,
        SkipMarkerEntity::class,
        ArtworkEntity::class,
        SearchHistoryEntity::class,
        EpisodeStillEntity::class,
        NucLibraryItemEntity::class,
        SeriesPlaybackPrefEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class ArkivDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun skipMarkerDao(): SkipMarkerDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun episodeStillDao(): EpisodeStillDao
    abstract fun nucLibraryItemDao(): NucLibraryItemDao
    abstract fun seriesPlaybackPrefDao(): SeriesPlaybackPrefDao

    companion object {
        @Volatile
        private var instance: ArkivDatabase? = null

        /** v1 -> v2: agrega la tabla de marcadores de opening/ending (preserva datos). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skip_markers (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, " +
                        "openingStartMs INTEGER, openingEndMs INTEGER, endingStartMs INTEGER)",
                )
            }
        }

        /** v2 -> v3: timestamp en marcadores (para sincronización last-write-wins). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skip_markers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 -> v4: override manual de categoría (película/serie) por ítem. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN categoryOverride TEXT")
            }
        }

        /** v4 -> v5: soporte de torrents (origen + datos del .torrent + índice de archivo). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN source TEXT NOT NULL DEFAULT 'archive'")
                db.execSQL("ALTER TABLE items ADD COLUMN torrentData TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN torrentFileIndex INTEGER")
            }
        }

        /** v5 -> v6: torrent propio por episodio (series donde cada capítulo es su torrent, ej. anime). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN torrentData TEXT")
            }
        }

        /**
         * v6 -> v7: metadatos de sync. Añade `updatedAt` (reloj LWW) + `deleted` (tombstone) a
         * items/episodes/playback (skip_markers ya tenía updatedAt; solo +deleted).
         *
         * Triggers: auto-setean `updatedAt` en toda escritura LOCAL (así ningún write "se olvida"
         * de marcarse dirty), pero RESPETAN un valor explícito (el merge de la nube escribe el
         * updatedAt remoto ≠ 0, y el trigger lo deja). Room no valida triggers, así que no
         * interfieren con su esquema.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("items", "episodes", "playback")) {
                    db.execSQL("ALTER TABLE $t ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $t ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE skip_markers ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")

                val now = "CAST(strftime('%s','now') AS INTEGER)*1000"
                // Sellar las filas EXISTENTES con la hora actual para que se suban en el primer sync
                // (si quedaran en updatedAt=0, el push por `updatedAt > cursor` nunca las tomaría).
                // Se hace ANTES de crear los triggers para no dispararlos en masa.
                for (t in listOf("items", "episodes", "playback", "skip_markers")) {
                    db.execSQL("UPDATE $t SET updatedAt = $now")
                }
                // (tabla, columna PK)
                for ((t, pk) in listOf("items" to "identifier", "episodes" to "id", "playback" to "episodeId", "skip_markers" to "itemId")) {
                    // INSERT local (updatedAt quedó en 0) -> sellar con la hora.
                    db.execSQL(
                        "CREATE TRIGGER trg_${t}_ins AFTER INSERT ON $t WHEN NEW.updatedAt = 0 " +
                            "BEGIN UPDATE $t SET updatedAt = $now WHERE $pk = NEW.$pk; END",
                    )
                    // UPDATE local (el writer no movió updatedAt) -> sellar. El merge de nube sí lo mueve => se salta.
                    db.execSQL(
                        "CREATE TRIGGER trg_${t}_upd AFTER UPDATE ON $t WHEN NEW.updatedAt = OLD.updatedAt " +
                            "BEGIN UPDATE $t SET updatedAt = $now WHERE $pk = NEW.$pk; END",
                    )
                }
            }
        }

        /**
         * v7 -> v8: sella (updatedAt = ahora) las filas que quedaron en updatedAt=0. Necesario para
         * dispositivos que migraron a v7 ANTES de que v6->v7 sellara: sin esto, sus filas existentes
         * (updatedAt=0) nunca las tomaría el push (`updatedAt > cursor`) y la biblioteca no se subiría.
         * Idempotente (solo toca updatedAt=0).
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = "CAST(strftime('%s','now') AS INTEGER)*1000"
                for (t in listOf("items", "episodes", "playback", "skip_markers")) {
                    db.execSQL("UPDATE $t SET updatedAt = $now WHERE updatedAt = 0")
                }
            }
        }

        /** v8 -> v9: tabla local de arte de TMDB (backdrops por ítem). No se sincroniza. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS artwork (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, " +
                        "tmdbId INTEGER, tmdbType TEXT, " +
                        "backdropsJson TEXT NOT NULL DEFAULT '[]', " +
                        "fetchedAt INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /**
         * v9 -> v10: historial de búsquedas del catálogo (anime/películas). PK compuesta
         * (query, kind): el mismo texto en pestañas distintas no se pisa entre sí.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS search_history (" +
                        "query TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "atMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`, `kind`))",
                )
            }
        }

        /** v10 -> v11: caché local de stills de TMDB por capítulo (no se sincroniza). */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS episode_still (" +
                        "episodeId TEXT NOT NULL, " +
                        "stillUrl TEXT, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`))",
                )
            }
        }

        /**
         * v11 -> v12: caché local de la biblioteca de arkiv-offline (qué episodios ya están
         * descargados en la NUC) + preferencia de reproducción por serie (NUC vs LIVE).
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS nuc_library_items (" +
                        "itemId INTEGER NOT NULL PRIMARY KEY, seriesId TEXT NOT NULL, " +
                        "season INTEGER NOT NULL, episode INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, syncedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_playback_prefs (" +
                        "seriesId TEXT NOT NULL PRIMARY KEY, preference TEXT NOT NULL, " +
                        "asked INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): ArkivDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArkivDatabase::class.java,
                    "arkiv.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
