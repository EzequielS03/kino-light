# Arkiv Light — Orphan cleanup after removing the server-backed features

Status: design approved in conversation (2026-09-10). Branch: `light-magis` (permanent fork, never merged into `main`).

## Context

Sub-projects 1, 2A, 2B, 3A and 4 turned this branch into an app that talks to no server of its own (see `CLAUDE.md`, "Regla del branch"). Along the way, whole features were removed:

- torrent streaming and search (jackett, providers);
- the web resolver;
- archive.org;
- the NUC / arkiv-offline downloads;
- cloud sync;
- phone↔TV pairing and remote control;
- PocketBase login and accounts;
- the `arkiv-api` gateway;
- Simkl, OpenSubtitles and crash upload.

Each removal deleted the obvious code. What stayed behind is spread across the tree: code that only served those features, stale comments, live code that still carries the removed system's name, and database columns that are always empty.

What is **alive and must keep working**:

- Magis (VOD, series, live channels);
- Caracol/Ditu (VOD with Widevine, live channels);
- local downloads of Magis content (`data/local`), played back offline by **VLC** (`VlcPlayer`, `PlaybackService`, `loadLocal`);
- DLNA and Chromecast;
- the OTA check;
- TMDB and AniList;
- the AI module (`data/ia`, the trivia and "Para ti").

## Language rule (decided 2026-09-10)

Everything a **developer** reads goes in English: class, function and package names; comments; KDoc; log messages; commit messages; and development docs such as this spec and its plans.

Everything the **app user** sees stays in Spanish (Bogotá, tuteo, never voseo): UI text, notifications, error messages shown on screen, and prompts that ask a model to answer in Spanish.

This cleanup applies the rule **only to what it touches**. Translating the rest of the project is the next, separate project.

## Inventory (measured 2026-09-10, before the cleanup)

### Mentions of removed features: code vs comments

Lines in `app/src/main/java`, split by whether the line is a comment:

| Feature | Code lines | Comment lines | Files with code |
|---|---|---|---|
| torrent | 52 | 151 | 18 |
| archive.org | 8 | 97 | 5 |
| `ArchiveCache*` | 36 | 13 | 3 |
| `SourceKind.NUC` / `SourceKind.ARCHIVE` | 8 | 1 | 4 |
| CloudSync / cloudsync | 0 | 10 | 0 |
| PocketBase | 0 | 22 | 0 |
| remote / remoto | 2 | 37 | 2 |
| `arkiv-api` / `ArkivApiClient` | 0 | 10 | 0 |
| `loadWeb` / `loadArchive` | 6 | 10 | 1 |

Most of what remains is prose, not code. Many of those comments now state things that are false, which the branch rule forbids.

### Confirmed dead

Nobody reaches these any more, or they only serve a removed feature:

- `ArkivRepository.torrentDataOf` and `torrentSourceForEpisode` (0 callers), and the `EpisodeTorrent` type.
- `DescargasPorFuente.deTorrent` / `deArchive` and `MotivoDelGateway.motivoDelGateway` (0 callers).
- `MetadataParser`, `VideoVariant` and the archive.org domain fields (`original`, `derivative`, `playbackVariant`, `castVariant`), with `Mappers.toEntity` / `toItemEntity`.
- `AddScreen`, `AddViewModel` and `TvAddScreen`: unreachable.
- `SourceSection` (`ui/catalog/PlaySources.kt`).
- The source fillers in `PlayerViewModel`: `loadWeb`, `loadArchive`, and the `SourceKind.NUC` / `SourceKind.LOCAL` / `SourceKind.ARCHIVE` branches. `kindFor` maps unknown ids to `ARCHIVE` today; that fallback gets an explicit "unknown source" case instead.
- The Gradle dependencies `com.google.zxing:core` (0 uses) and `androidx.media3:media3-database` (0 direct uses; confirm by compiling).
- The resources `drawable/tv_focus_highlight` and `layout/arkiv_player_view` (no reference).
- The two `episode_frame` queries in `Daos.kt` that read and clear `remoteUrl`: they served the frame upload to the cloud.

The simple zero-reference scan found 14 top-level declarations. That scan cannot see **clusters** of dead code that only call each other, such as the torrent functions above, so Phase 1 works from the call graph, not from name counts.

### Alive, but named after a removed system

| Today | Refs / files |
|---|---|
| parameter or field `gateway` | 329 / 84 |
| package `data.gateway` | imported by 33 files |
| `ArchiveCacheProxy` | 48 / 9 |
| `GatewayResult` | 44 / 14 |
| `GatewayEpisode` | 43 / 13 |
| `GatewaySerie` | 43 / 12 |
| `GatewayException` | 20 / 7 |
| `GatewayPlayable` | 16 / 8 |
| `GatewaySearchQuery` | 11 / 7 |
| `LiveCatalogGateway` | 11 / 5 |
| `SyncTriggers` | 11 / 6 |
| `NucDownloadedGreen` | 7 / 3 |
| `deRefDelGateway` | 3 / 2 |

A few names with "remote" in them mean "from the network" and are correct: `UpdateChecker.remote` (the OTA) and `TsDurationProbe.probeRemote`. They stay.

### Database (Room, version 29)

| Table | Column | Reality |
|---|---|---|
| `items` | `torrentData` | alive: holds the Magis/Caracol series `ref`. The domain already calls it `sourceRef` (`Mappers`, `Daos.kt` alias). |
| `episodes` | `torrentData` | alive: holds the chapter `ref`. |
| `episodes` | `torrentFileIndex` | always `null` (Magis and Caracol write `null`); only the duplicate-download key and dead torrent code read it. |
| `episodes` | `thumbPath`, `originalPath`, `originalFormat`, `originalSize`, `derivativePath`, `derivativeFormat`, `derivativeSize` | always `null` / `0`; read only by the dead archive.org mappers and projected by two DAO queries. |
| `episode_frame` | `remoteUrl` | always `null`; it was the uploaded frame's URL for cloud sync. |

Facts that shape the migration:

- `minSdk = 26`. SQLite gained `ALTER TABLE … RENAME COLUMN` in 3.25 and `DROP COLUMN` in 3.35. Android 8–10 ships older versions, so **renaming or dropping a column means recreating the table**.
- `exportSchema = false` and there is no `schemas/` folder, so no auto-migrations. The 28 existing migrations are hand-written (`ArkivDatabase.kt`).
- `fallbackToDestructiveMigration()` is on. If a migration path is **missing**, Room wipes the whole database: library, history, favorites, "Para ti". If a migration leaves a schema Room does not expect, the app **crashes on open**.
- No entity declares foreign keys. `episodes` has one index (`Index("itemId")`).
- The `updatedAt` triggers are re-created on every open by the `SELLAR_UPDATED_AT` callback, so recreating a table does not lose them for good.
- Real-SQLite tests already exist (`SyncTriggersTest`, `RecomendacionQueryTest`, with `org.xerial:sqlite-jdbc`).
- All 13 DAOs are used outside `data/db`, so no whole table is orphaned.

## Goals

1. Delete everything that only existed for a removed feature, including its tests.
2. Correct or delete every comment that states something false about the current code. Keep comments that explain a reason that still justifies the code, even if they mention history.
3. Rename live code that is named after a removed system, in English.
4. Rename the live `torrentData` columns and drop the dead columns, with a migration that cannot lose data.
5. **No behavior change.** No text the user sees changes. No persisted string changes: preference keys, JSON, stored refs.

## Non-goals

- Removing VLC or moving local playback to ExoPlayer. VLC is alive.
- Translating the rest of the project to English (next project).
- New features, the OTA trap, RCN (3B), and the minors deferred by sub-project 4.
- Dropping whole tables.

## Phase 1 — Dead code

**Method.** Work one removed feature at a time: torrent, archive.org, NUC and web, the gateway leftovers, remote control and pairing, cloud sync and PocketBase.

For each one:

1. Find everything that exists only for it, starting from the confirmed list and following the call graph (`graft callers`, compiler errors), including code whose only callers are also dead.
2. Delete that cluster and its tests.
3. Run the full suite and `assembleDebug`.
4. Commit.

The dead Gradle dependencies and resources go in their own commit.

**Comments.** In every file the phase touches, and in a final sweep over the stale-mention counts above, rewrite or delete each comment that became false. Any comment that gets rewritten is written in English.

**Not in this phase:**

- anything alive with an old name (Phase 2);
- any column (Phase 3);
- VLC, downloads, DLNA, Chromecast, the OTA check.

**Done when:**

- every item in "Confirmed dead" is gone, or is explicitly shown to be alive (then it moves to Phase 2);
- the suite and `assembleDebug` are green;
- the TV smoke test passes (see Verification).

## Phase 2 — Renames without touching the database

**Rule.** Rename only what names a system that no longer exists or misleads about what it does today. While moving or touching these names anyway, they become English.

| Today | New name |
|---|---|
| package `data.gateway` | `data.sources` |
| `FuenteDeContenido` / `FuenteCompuesta` (moved with the package) | `ContentSource` / `CompositeSource` |
| `GatewayResult` | `SourceResult` |
| `GatewayEpisode` | `SourceEpisode` |
| `GatewaySerie` | `SourceSeries` |
| `GatewayPlayable` | `Playable` |
| `GatewaySearchQuery` | `SearchQuery` |
| `GatewayException` | `SourceException` |
| `GatewaySubtitle` | `SourceSubtitle` |
| `LiveCatalogGateway` | `LiveCatalog` |
| parameter or field `gateway` | `source` |
| `ArchiveCacheProxy` | `VideoCacheProxy` |
| `SyncTriggers` | `UpdatedAtTriggers` |
| `NucDownloadedGreen` | `DownloadedGreen` |
| `deRefDelGateway` | `fromLegacyRef` (logic unchanged: it still reads refs saved in the gateway era) |

All of these names were checked against the tree on 2026-09-10 and are free.

**Anything else with an old name that Phase 1 did not delete** gets renamed here after what it does today, for example `isTorrent`, `ArchiveItem`, `WebExtras`, `revisarArchive`, `torrentDirName` and `applyFromRemote`.

**How:**

- One rename group per commit, done mechanically with the compiler as the safety net. The suite runs green on every commit.
- No persisted or user-visible string changes.
- The on-disk cache folder `archive-cache` becomes `video-cache`. On first start the old folder is deleted once, so it does not keep hundreds of MB orphaned.

## Phase 3 — Database v29 → v30

**Changes:**

- `items.torrentData` → `sourceRef`
- `episodes.torrentData` → `sourceRef`
- Drop `episodes.torrentFileIndex`. The duplicate-download key stops using it.
- Drop `episodes.thumbPath`, `originalPath`, `originalFormat`, `originalSize`, `derivativePath`, `derivativeFormat` and `derivativeSize`. The two DAO projections and their row classes follow.
- Drop `episode_frame.remoteUrl`.

Before the plan is written, an audit of the remaining columns and stored values applies the same rule: a column is removed only with evidence that it is dead. Candidates to look at are the `"archive"` default of `items.source` and stale kinds in `search_history` / `recent_titles`. Anything found is added here or explicitly left alone.

**Migration.** One hand-written `MIGRATION_29_30`. For each affected table:

1. Create the new table with the v30 shape.
2. `INSERT … SELECT` the kept columns, mapping `torrentData` to `sourceRef`.
3. `DROP` the old table.
4. `ALTER TABLE … RENAME TO` the original name.
5. Recreate `index_episodes_itemId`.

It runs inside the migration's transaction. There are no foreign keys to order around.

**Safety nets, all three required:**

1. **JVM migration test on real SQLite.**
   - *Setup.* Capture the exact v29 `CREATE` statements Room generates today, from the generated `ArkivDatabase_Impl`, as a test fixture **before** the entities change. Build a v29 database from that fixture and fill it with representative rows:
     - a Magis series with chapters and a `ref`;
     - a Caracol chapter;
     - soft-deleted rows;
     - playback history;
     - frames.
   - *Run.* Apply `MIGRATION_29_30`.
   - *Asserts.*
     - Every kept value survives, and `torrentData` arrives in `sourceRef`.
     - The resulting schema (`PRAGMA table_info` and `index_list`) matches what Room expects for v30, taken from the new generated code.
2. **Schema export from v30 on.** Set `exportSchema = true`, with `room.schemaLocation` pointing to a committed `schemas/` folder, so future migrations can be validated.
3. **On-device check on the KALLEY R3** with the real data already there (library, history, favorites, "Para ti").
   - Copy `arkiv.db` (plus `-wal` and `-shm`) with `adb exec-out "run-as com.arkiv.player.light cat …"`.
   - Install v30 on top, copy the database again, and compare row counts and key values table by table.
   - Then Magis, Caracol and a downloaded file must play.

**Isolation.** Phase 3 goes last, alone, in its own commits, so it can be reverted without dragging Phases 1 and 2 back.

## Verification

- **Every commit:**

  ```
  ./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug
  ```

  In the foreground.
- **End of each phase, on the KALLEY R3 (debug build):**
  - Magis VOD;
  - a Magis live channel;
  - Caracol VOD and live;
  - a downloaded file played with VLC;
  - the trivia card;
  - the "Para ti" row;
  - the library and "Continuar viendo".
- **End of Phase 3:** additionally, the before/after database comparison above.
- **The branch's host sweep** (`CLAUDE.md`) still lists only the eight allowed hosts.

## Sequencing

- The cleanup starts after sub-project 4 (AI with Kilo) is closed on the branch.
- Phases run 1 → 2 → 3. Each phase gets its own implementation plan, written just before it starts. Phase 1's deletions change the inventory Phase 2 works from, and Phase 2's renames change the names Phase 3 touches.

## Risks

- **A deleted piece turns out to be reachable** through reflection, the manifest, or navigation by string route. *Mitigation:* the compiler catches normal references; the manifest and navigation routes are checked explicitly before deleting any screen, service or receiver; and the TV smoke test runs at the end of each phase.
- **A mechanical rename also rewrites a persisted string.** *Mitigation:* renames target identifiers, not string literals; preference keys, JSON keys and stored refs are listed and left untouched.
- **The migration loses or mangles data, or crashes on open.** *Mitigation:* the three safety nets in Phase 3, and Phase 3 isolated at the end.
- **Comment rewrites drift into a full translation.** *Mitigation:* only comments in touched code, or ones that are false, are rewritten in this project.
