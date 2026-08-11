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
        RecentTitleEntity::class,
        EpisodeStillEntity::class,
        NucLibraryItemEntity::class,
        SeriesPlaybackPrefEntity::class,
        LocalActiveJobEntity::class,
        LiveFavoriteEntity::class,
        LiveRecentEntity::class,
        LiveChannelCacheEntity::class,
    ],
    version = 20,
    exportSchema = false,
)
abstract class ArkivDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun skipMarkerDao(): SkipMarkerDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun recentTitleDao(): RecentTitleDao
    abstract fun episodeStillDao(): EpisodeStillDao
    abstract fun nucLibraryItemDao(): NucLibraryItemDao
    abstract fun seriesPlaybackPrefDao(): SeriesPlaybackPrefDao
    abstract fun localActiveJobDao(): LocalActiveJobDao
    abstract fun liveFavoriteDao(): LiveFavoriteDao
    abstract fun liveRecentDao(): LiveRecentDao
    abstract fun liveChannelCacheDao(): LiveChannelCacheDao

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

        /**
         * v12 -> v13: registro local de qué `job_id` de arkiv-offline disparó este dispositivo
         * (Task 9, pantalla de Descargas) -- ver [LocalActiveJobEntity].
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_active_jobs (" +
                        "jobId INTEGER NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v13 -> v14: `seriesId` en los jobs locales. Sin esta columna, cuando un job termina no
         * hay forma de saber de qué serie era (arkiv-offline no lo devuelve en `GET /jobs/<id>`) y
         * la sección "Terminados" solo se podía llenar visitando el detalle de la serie.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_active_jobs ADD COLUMN seriesId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v14 -> v15: `sourceRef` (la pageUrl de origen) en la caché de biblioteca de la NUC. Sin
         * esta columna el "ya descargado" solo se podía comparar por (temporada, capítulo), y como
         * la NUC guarda un único archivo por episodio, el tilde verde aparecía en los packs de los
         * tres sitios a la vez aunque la copia viniera de uno solo.
         *
         * NULL (sin DEFAULT) a propósito: las filas que ya estaban en caché no saben de dónde
         * salieron, y marcarlas con un valor inventado haría que matchearan un sitio equivocado.
         * Con NULL simplemente no matchean, y el próximo refresh de biblioteca las rellena con el
         * `source_ref` real que devuelve `GET /library`.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nuc_library_items ADD COLUMN sourceRef TEXT")
            }
        }

        /**
         * v15 -> v16: `tmdbId` del ítem y (`season`, `episode`) de cada capítulo, para poder
         * mostrar el título real del episodio en vez del nombre del archivo ("s01e03").
         *
         * Ni archive.org ni el mirror guardan el nombre del episodio, solo su número: el nombre
         * hay que pedírselo a TMDB, y para eso hacen falta las dos cosas — a qué serie pertenece
         * el ítem y qué número es cada archivo.
         *
         * Las tres van NULL sin DEFAULT a propósito: las filas que ya estaban no saben su número
         * ni su serie, y rellenarlas con un valor inventado haría que la UI muestre el título de
         * OTRO capítulo. Con NULL simplemente caen al nombre del archivo, como hasta ahora, y se
         * completan solas la próxima vez que se refresque el ítem.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN tmdbId INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN season INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN episode INTEGER")
                db.execSQL("ALTER TABLE episode_still ADD COLUMN title TEXT")
                // La caché de stills se llenó repartiendo capítulos por conteo (ver
                // ensureEpisodeStills), un reparto que se desalinea si hay OVAs o recaps. Ahora que
                // hay temporada/capítulo exactos conviene rehacerla: se borra en vez de arrastrar
                // asignaciones posiblemente equivocadas -- es caché derivable, se repuebla sola.
                db.execSQL("DELETE FROM episode_still")
            }
        }

        /**
         * v16 -> v17: la tabla `downloads` deja de ser exclusiva de archive.org y pasa a servir a las
         * tres fuentes (archive, torrent, web).
         *
         * `source` va con DEFAULT 'archive' a propósito: todas las filas que ya existen vienen del
         * único camino que había, así que ese default las clasifica bien sin tocar datos.
         *
         * `filePath` va NULL sin DEFAULT: las filas viejas guardaron la ruta como un `file://` en
         * `localUri` (lo que devolvía el DownloadManager del sistema). Inventarles un filePath las
         * rompería; con NULL, `LocalLibrary` cae a `localUri` y lo ya descargado sigue reproduciéndose.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN source TEXT NOT NULL DEFAULT 'archive'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN stagingItemId INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN sizeConfirmed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_titles (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "kind TEXT NOT NULL, " +
                        "tmdbId INTEGER, " +
                        "anilistId INTEGER, " +
                        "title TEXT NOT NULL, " +
                        "posterUrl TEXT NOT NULL, " +
                        "year TEXT NOT NULL, " +
                        "atMs INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Badge de "hay capítulos nuevos": cuántos episodios tenía la serie la última vez que se
         * abrió su detalle.
         *
         * Nullable a propósito, y sin DEFAULT: en las filas que ya existen queda NULL, que
         * significa "nunca se miró" y NO pinta badge. Con un default de 0, el día que esto se
         * estrene cada serie de la biblioteca aparecería marcada con todos sus capítulos como si
         * fueran novedad. Ver [com.arkiv.player.data.nuevos.ContadorDeNuevos].
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN episodiosVistosEnLista INTEGER")
            }
        }

        /**
         * v19 -> v20: favoritos y recientes de canales de TV en vivo (Task 10). Favoritos
         * sincroniza LWW con tombstone -- mismo esquema que `skip_markers` -- y recientes LWW sin
         * tombstone (se poda por antigüedad, no se borra a mano). La caché del catálogo
         * (`live_channels_cache`) es local y NO se sincroniza (ver [LiveChannelCacheEntity]): no
         * lleva `updatedAt`/`deleted` porque nunca pasa por [SyncTriggers] ni por el merge.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_favorites (" +
                        "code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, numero INTEGER NOT NULL, " +
                        "logo TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_recents (" +
                        "code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, vistoAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_channels_cache (" +
                        "code TEXT NOT NULL PRIMARY KEY, categoria INTEGER NOT NULL, nombre TEXT NOT NULL, " +
                        "numero INTEGER NOT NULL, logo TEXT, guardadoAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Deja los triggers de `updatedAt` puestos en CADA apertura, y sella lo que haya quedado
         * sin reloj.
         *
         * Va acá y no en una migración porque el que instala la app de cero **no corre ninguna
         * migración**: Room le crea las tablas desde su esquema generado, y los triggers no son
         * parte de ese esquema. Así fue como el Fire TV terminó sin ninguno, con toda su biblioteca
         * en `updatedAt = 0` y por lo tanto invisible para el push a la nube (`updatedAt > cursor`).
         * Ver [SyncTriggers].
         */
        private val SELLAR_UPDATED_AT = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Los triggers primero: sellar después no los dispara (la fila cambia de 0 a
                // `ahora`, o sea NEW.updatedAt != OLD.updatedAt, que es la guarda del trigger).
                SyncTriggers.ddl().forEach { db.execSQL(it) }
                SyncTriggers.sellarFilasSinReloj().forEach { db.execSQL(it) }
            }
        }

        fun get(context: Context): ArkivDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArkivDatabase::class.java,
                    "arkiv.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                    .addCallback(SELLAR_UPDATED_AT)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
