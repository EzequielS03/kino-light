# English translation sweep — status

Standing directive (from the user, 2026-09-13/14): sweep the whole `app/` codebase module by
module, lowest risk to highest risk, translating every developer-facing Spanish identifier,
comment, and KDoc to English. User-facing strings (Bogotá Spanish, tuteo, never voseo) are **out
of scope** and must stay exactly as they are. This supersedes the older "only new code must be
English" line in `.claude/reglas.md`.

Order chosen by the user: **módulo por módulo, de menor a mayor riesgo** (module by module,
lowest to highest risk).

## Overall completion (updated 2026-09-14, latest): **every deliberate deferral is closed.**

`ui/`, `data/`, `crash/`, `app/src/debug/` are all done. `MagisFuente`/`DituFuente` →
`MagisSource`/`DituSource` (commit `fc49028d`) and `LiveModels.kt`'s data-class field names
(`LiveChannel.nombre` etc., 27 files, commit `5a0eb7ab`) are translated. The
`AppGraph.kt`/`ArkivApp.kt`/`MainActivity.kt` deferral is closed: comments (`f39526d6`), then
`AppGraph.kt`'s whole property/function surface (commit `97e73d73`, 21-file ripple:
`fuenteDeContenido`→`contentSource`, `catalogoDeVivo`→`liveCatalog`, `hayInternet`→`hasInternet`,
`httpDelPortal`→`portalHttp`, `generadorParaTi`→`forYouGenerator`,
`agregadorDeRecomendaciones`→`recommendationAggregator`, `almacenDeCaracol`→`caracolStore`,
`almacenDeFrames`→`frameStore`, `destructorDeFrames`→`frameDestroyer`,
`iniciarMonitorDeRed`→`startNetworkMonitor`, `monitorDeRed`→`networkMonitor`,
`vigilanteDeRed`→`networkWatchdog`, `clienteDeIa`→`aiClient`, `datosCuriosos`→`triviaFacts`,
`buscadorDeCapitulos`→`newChapterFinder`, `buscarCapitulosNuevos`→`lookForNewChapters`, plus
locals/lambda params and `HORAS_ENTRE_BUSQUEDAS`/`KEY_ULTIMA_BUSQUEDA`'s constant names — the
latter's frozen SharedPreferences string value untouched), and `ArkivApp.kt`'s private
`reportar`/`etiqueta` (commit `7a392dd3`). `MainActivity.kt`'s identifiers were already English.
**`SettingsStore.kt`'s public API is now ALSO translated** (commit `d1b68788`, 11-file ripple —
see "Deliberately deferred" below for the details and a near-miss worth remembering).

A fresh full-codebase accented-character sweep after all of the above (`app/src/main/java` +
`app/src/debug/java` + `app/src/test/java`) still sits at exactly **138 files, unchanged from the
last verification** — expected, since these last few passes translated non-accented Spanish
identifiers, not accented prose. Every one of those 138 was already individually confirmed a false
positive (see the "138 files" note further down and the 151-file triage commit `139df024`).

A final full-codebase accented-character sweep (`app/src/main/java` + `app/src/debug/java` +
`app/src/test/java`) was run repeatedly, most recently after a 151-file triage pass (commit
`139df024`, 24 files genuinely fixed) — **138 files remain with an accented character, and every
one has been read and independently verified as a legitimate false positive**: user-facing
Bogotá-Spanish UI strings, test assertions against that UI text, real-world data (country/genre
names, TMDB fixtures), LLM prompt/data content, frozen exception-classification strings, or
verbatim historical log quotes. Re-run the sweep command below before trusting this number again —
the lesson below about "done" being wrong has held five separate times this session, so verify,
don't assume.

**One explicitly out-of-scope item remains, discovered but NOT started**: hundreds of
`@Test fun spanish_snake_case_name()` / `` `spanish backtick name` `` test method names exist
throughout the test suite with no accented characters, so the accented-character sweep never
surfaces them. Renaming those is a much larger, separate undertaking (potentially hundreds of
names across dozens of files) that no session has scoped or attempted. If asked to continue the
sweep further, this is the next real body of work — but treat it as its own project, not a
"gap-closing" pass, and confirm with the user before committing to it given the scale.

- `playback/`, `security/`, `dlna/`, `cast/`, `thumbnails/`: **100% done.**
- `ui/` (159 main files across 14 subpackages, plus 6 top-level files, plus tests): **100% done.**
  `PlayerScreen.kt` (the single largest file in the entire codebase, ~4038 lines after translation)
  was the last file in the entire `ui/` tree — done and committed at `4e5ce086`.
- `data/`'s three known gaps — `MagisEntities.kt` (`791a5988`), `DituEntities.kt` (`a2a1d073`),
  `LibraryGrouping.kt` (`5900c67d`) — are **all done.**
- **`data/nuevos/` package and `data/gateway/ReparacionDeMagis.kt`** — **done, `f127153b`.**
  `BuscadorDeCapitulos.kt`→`NewChapterFinder.kt`, `SeriesPorRevisar.kt`→`SeriesToCheck.kt`,
  `ReparacionDeMagis.kt`→`MagisIdentityRepair.kt`. `SerieConProgresoRow` (in `Daos.kt`) and its
  fields (`episodios`/`ultimoVistoMs`) were deliberately left untouched — a Room DAO row bound to
  `SELECT ... AS alias`, same frozen-DTO rule as `ProgresoConSiguienteRow`/`FilaDeHistorial`.
- **The rest of `data/gateway/`** — `GatewayModels.kt` (`2b413184`), `LiveModels.kt` comments
  (`1da03944`), `FuenteCompuesta.kt`→`CompositeSource.kt` (`d3e872b0`) — **done**, modulo two
  explicit, documented deferrals (see "Deliberately deferred" below): `MagisFuente`/`DituFuente`'s
  class names (exception f) and `LiveModels.kt`'s data-class field names (`LiveChannel.nombre`
  etc. — large ripple into already-"done" `ui/live/`/`ui/tv/`/`ui/player/`/`playback/`).
- **`SeriesItemIds.kt`, `SettingsStore.kt`** — comments translated (`e1176a60`).
  `SettingsStore.kt`'s public API (`adultosDesbloqueado`, `setDimLevel`, etc.) and every
  SharedPreferences `KEY_*` constant are deliberately left untouched this pass — see "Deliberately
  deferred".
- **`SearchHistoryRepo.kt`, `SearchHistoryPolicy.kt`** — **done, `cfe4fb51`.** Locals translated
  (`filas`→`rows`, `limpio`/`texto`→`clean`/`text`); `KIND`'s literal value `"buscar"` left frozen
  (persisted in the `search_history` table). Test file's Spanish test names rewritten to English.
- **`DownloadConfirmation.kt`, `RecommendationAggregator.kt`** — **done, `e38c9e04`.** One genuine
  leftover dev comment each, fixed.
- **The rest of the ~27-file list found by the full sweep — all individually read and confirmed to
  need NO changes**, commit `e38c9e04`'s message has the full list. Every remaining hit is one of:
  UI-facing strings (`CaracolFailure.kt`'s whole job IS translating errors for the user;
  `DownloadOutcome.Failed`/`DownloadDisplayState.Failed` messages shown in the Downloads screen;
  `DituEntitlement`'s block reasons which "already come written for the person" per
  `CaracolFailure.kt`'s own KDoc; `LibrarySection.MOVIES("Películas")`'s enum label;
  `DownloadNotificationText.kt`'s Android notification text), LLM prompt/data text
  (`ForYouVerification.kt`, `ForYouGenerator.kt`, `TriviaFacts.kt`'s `TriviaPrompt`,
  `WorkSheet.kt`'s TMDB-fact lines fed into that prompt — exception e), or exception messages that
  surface to the user unfiltered by Magis's own convention (`MagisFuente.kt`, `MagisSession.kt`,
  `MagisResolve.kt`, `MagisLive.kt`, `MagisAccount.kt`) or feed `CaracolFailure.classify`'s text
  matching (`DituFuente.kt`, `DituClient.kt`, `DituResolve.kt`, `DituDownloadStrategy.kt`,
  `MagisDownloadStrategy.kt` — note `DituClient.kt`'s `"Caracol respondió ${it.code}"` format is
  literally required to match `CaracolFailure.SERVER_DOWN`'s regex, so it's frozen by coupling,
  not just convention). `ArkivRepository.kt`'s one hit is a filename-noise regex matching real
  release-tag text (`latino`/`castellano`/`español`), also exception-e-shaped data.
- **`CacheConVencimiento`→`ExpiringCache`** (commit `412bae87`) — found by a NON-accented
  identifier sweep (`\b(fun|val|var|class|object|interface) \w*(De[A-Z]|Con[A-Z]|...)`) run across
  all of `data/` after the accented-char sweep came up clean, per the established lesson that
  accents alone miss identifiers like this. `internal class`, two named-arg call sites in
  `MagisFuente.kt`/`MagisLiveCatalog.kt` (`tope`→`cap`). A second, broader suffix sweep
  (`cion|dad|miento|torio|ador|able|ible`) after this rename turned up only false positives
  (English words like "saveable", "Playable", "reproducible" — the last already a known deferral).
  **`data/` is now considered genuinely, thoroughly done** — every accented hit reviewed
  individually, plus two rounds of non-accented identifier heuristics with no further gaps found.

## Hard-won lesson this session: bare-name imports break silently after a rename

When a top-level (non-member) `fun`/`val` gets renamed, `sed` patterns anchored on the call-site
syntax (e.g. `\.etiqueta()` to catch `lang.etiqueta()`) do **not** match a bare import line like
`import com.arkiv.player.ui.settings.etiqueta` (no trailing parens, no receiver). This produced a
real compile break that briefly went undetected because a background gradle run's task-notification
reported "completed" even though the actual build had failed (piping gradle's output through `tail`
masks gradle's own exit code with `tail`'s). **After any bare top-level rename, grep specifically
for `^import .*\.<oldName>$` in addition to the usual call-site grep, and always read the tail of a
background build's output file directly rather than trusting the notification's summary alone when
the change was large.**

## What's fully done and committed (verified: compiles, full suite green at 1704/0/0/0)

### `data/` — the three known gaps are now DONE

- **`data/MagisEntities.kt`** (commit `791a5988`) — `CapituloDeTemporada` → `SeasonChapter`;
  `itemIdDe`→`itemIdFor`, `idLegacyDeCapitulo`→`legacyChapterId`, `episodioIdDe`→`episodeIdFor`,
  `episodioIdDePelicula`→`movieEpisodeId`, `capituloDe`→`chapterEntity` (private),
  `refParaReparar`→`refToRepair`, `stillsDeTemporada`→`seasonStills`,
  `nombreCanonico`→`canonicalTitle` (private); locals `capitulos`→`chapters`, `ahora`→`now`,
  `existente`→`existing`, `esCapitulo`→`isChapter`. `episodiosVistosEnLista`/`tituloCanonico`/`tipo`
  kept as-is throughout (including as function *parameter* names, not just entity fields) since
  they're literal SQL column names on `ItemEntity` — decided to keep the parameter name matching
  the frozen field it feeds, to avoid adding a translation seam with zero benefit. Rippled into
  `ArkivRepository.kt`, `RecommendationSaving.kt`, `SearchPlayback.kt`, `ReparacionDeMagis.kt`
  (minimal, not yet processed itself — see below), `MagisEntitiesTest.kt` (full rewrite, Spanish
  snake_case test names → English backtick names, matching the rest of the sweep).
- **`data/DituEntities.kt`** (commit `a2a1d073`) — `CapituloDeCaracol`→`CaracolChapter`,
  `SerieDeCaracol`→`CaracolSeries` (`episodios`→`episodes`, `idDelElegido`→`chosenId`); every
  function renamed in parallel with Magis's (`itemIdFor`/`episodeIdFor`/`movieEpisodeId`/
  `chapterEpisodeId`/`savedSeason`/`seasonForChapter`/`caracolChapter`/`chapterId`/
  `itemContentId`/`buildSeries`/`saveableChapters`/`chosenAmong`, plus private `itemFor`/
  `chapterEntity`); `ORDEN_POR_TEMPORADA`→`ORDER_PER_SEASON`. Much larger ripple than Magis's:
  `ArkivRepository.kt`, `SearchPlayback.kt`, `RecommendationSaving.kt`,
  `RecommendationAggregator.kt`, `BuscadorDeCapitulos.kt` (minimal, not yet processed — see
  below), plus six test files including a full rewrite of `DituEntitiesTest.kt` and renaming
  `TemporadaDelCapituloTest.kt`→`SeasonForChapterTest.kt` (class + body translated).
- **`data/LibraryGrouping.kt`** (commit `5900c67d`) — mostly already-English identifiers
  (`groupKeyOf`/`group`/`resolveMembers`/`shouldRefetchArtwork`/`groupsFlow`) with dense Spanish
  KDoc, now fully translated. The one real rename: `LibraryGroup.nuevos`→`newEpisodes`, rippled
  into its sole caller `TvLibraryScreen.kt`. Two test files: `LibraryGroupingNuevosTest.kt`→
  `LibraryGroupingNewEpisodesTest.kt` (renamed + translated) and the much larger
  `LibraryGroupingTest.kt` (fully Spanish test names/locals, rewritten in full).

All three were **missed by the earlier "data/ complete" declaration** (caught mid-`ui/`-sweep
while rippling an unrelated rename into `ArkivRepository.kt`). **Lesson, reconfirmed a third
time: a package being "done" needs a final broad grep across every top-level file in it, not
just the subpackages that were the original focus.**

**Gaps discovered while closing these three, also now done (commit `f127153b`):**
`data/nuevos/` package (`BuscadorDeCapitulos.kt`→`NewChapterFinder.kt`,
`SeriesPorRevisar.kt`→`SeriesToCheck.kt`; `MissingChapters.kt`/`NewEpisodeCounter.kt` were already
fully English) and `data/gateway/ReparacionDeMagis.kt`→`MagisIdentityRepair.kt`
(`repararIdentidadDeMagis`→`repairMagisIdentity`, rippled into its two call sites in
`DetailScreen.kt`/`TvDetailScreen.kt`). `AppGraph.kt` itself is explicitly NOT translated — it's a
much larger not-yet-processed file (the whole DI graph, `fuenteDeContenido`/`catalogoDeVivo`/
`almacenDeCaracol` all live there); only its two call sites referencing renamed symbols got
minimal targeted fixes.

Everything else in `data/` (all subpackages, `data/local/`, `data/magis/`, `data/ditu/`,
`data/db/`, `ArkivRepository.kt`, `ContinueWatchingRule.kt` (was `PorDondeVas.kt`),
`StillMerge.kt` (was `MezclaDeStills.kt`), `EncodedNumbering.kt` (was `NumeracionCodificada.kt`),
`EpisodeNavigation.kt`, `WatchedThreshold.kt`) is translated and verified. See git log for the
full commit trail (`data/db/` through `ArkivRepository.kt` finishing at `c805220c`, then the gap
closures at `cad8b4af`, `6eb17c8f`, `791a5988`/`a2a1d073`/`5900c67d`, and `f127153b`).

**`data/gateway/` is now fully addressed** (commits `2b413184`, `1da03944`, `d3e872b0`): all six
files (`ContentSource.kt`, `GatewayMapper.kt`, `GatewayModels.kt`, `LiveModels.kt`,
`CompositeSource.kt` (was `FuenteCompuesta.kt`), `MagisIdentityRepair.kt`) are either fully
English or have every translatable comment translated, modulo the two documented deferrals in
"Deliberately deferred" below.

**Correction — `data/` is STILL not 100% done.** Closing `data/gateway/` prompted a full
accented-character sweep of literally every `.kt` file under `app/src/main/java/.../data/` (not
just one subpackage) — the check that should have run before `data/` was EVER declared "done" in
an earlier session. It found **~27 more files**, none touched yet except `SeriesItemIds.kt` and
`SettingsStore.kt` (commit `e1176a60`, comments only — see "Deliberately deferred" for what was
left alone in `SettingsStore.kt`). Remaining, by hit count (re-run the command in "Next steps" to
refresh — files may have shifted since this was written):

`ditu/CaracolFailure.kt` (11), `magis/MagisFuente.kt` (9, comments only — its identifiers were
already fixed by the `GatewayModels.kt`/`titulo`→`title` ripple this session),
`recomendaciones/ForYouVerification.kt` (8), `SearchHistoryRepo.kt` (8), `SearchHistoryPolicy.kt`
(7), `trivia/TriviaFacts.kt` (6), `local/LocalDownloadWorker.kt` (6),
`recomendaciones/ForYouGenerator.kt` (5), `local/DituDownloadStrategy.kt` (5),
`ditu/DituResolve.kt` (5), `local/DownloadConfirmation.kt` (4), `trivia/WorkSheet.kt` (3),
`recomendaciones/RecommendationAggregator.kt` (3, comments only — its code was already fixed this
session), `local/MagisDownloadStrategy.kt` (3), `local/DownloadNotificationText.kt` (3),
`ditu/DituClient.kt` (3, comments only — fixed this session's `CompositeSource` ripple, these are
separate leftover hits), `magis/MagisSession.kt` (2), `magis/MagisResolve.kt` (2),
`magis/MagisLive.kt` (2), `magis/MagisAccount.kt` (2), `local/DuplicateDownloadPolicy.kt` (2),
`local/DownloadLabel.kt` (2), `ditu/DituFuente.kt` (2, comments only), `biblioteca/LibraryWatched.kt`
(2), `ditu/DituEntitlement.kt` (1), `biblioteca/LibrarySection.kt` (1), `ArkivRepository.kt` (1,
comments only — its code is fully translated).

**This is now the FIFTH time in this session alone that a "done" declaration turned out to be
wrong** — first the three `MagisEntities`/`DituEntities`/`LibraryGrouping` gaps, then
`data/nuevos/`+`ReparacionDeMagis.kt`, then the three other `data/gateway/` files, and now this
much longer tail. **The pattern holding across all five: checking only the subpackages/files that
were the original focus, never a full sweep of literally everything nearby.** See the recurring
lesson below and "Next steps" for how to actually close this out.

## Deliberately deferred (not gaps — verified and consciously left in Spanish, with a reason)

- ~~`MagisFuente`/`DituFuente` class names~~ — **CLOSED**, commit `fc49028d`.
- ~~`LiveModels.kt`'s data-class field names~~ — **CLOSED**, commit `5a0eb7ab` (27 files).
- ~~`AppGraph.kt`/`ArkivApp.kt`/`MainActivity.kt`'s identifiers~~ — **CLOSED**, commits `97e73d73`
  (`AppGraph.kt`, 21-file ripple) and `7a392dd3` (`ArkivApp.kt`'s private `reportar`/`etiqueta`).
  `MainActivity.kt` was already English.
- ~~`SettingsStore.kt`'s public API and `KEY_*`/`ARCHIVO_*` constant NAMES~~ — **CLOSED**, commit
  `d1b68788` (11-file ripple + one test file renamed). The SharedPreferences key STRING VALUES stay
  permanently frozen, byte-for-byte verified against HEAD after the rename. **Near-miss worth
  remembering**: a blanket word-boundary regex rename briefly corrupted two of those frozen values
  anyway (`KEY_ADULTOS_DESBLOQUEADO`'s value `"adultosDesbloqueado"` and `KEY_CODIGO_ADULTOS`'s
  value `"codigoAdultos"` both got rewritten to the new name, because the string literal's CONTENT
  happened to equal the identifier text being renamed — `\bword\b` regex has no concept of "inside
  a string literal", so it matched there too). Caught by diffing every string literal in the file
  against `git show HEAD:<path>` before compiling — see the new lesson in the memory file. No other
  Room/prefs rename this sweep had this exact shape (identifier text == its own frozen string
  value), but it's now a required check whenever one does.

**No remaining deliberate deferrals.** Every item ever documented in this section is closed.

### `ui/` — fully done packages

- **`ui/detail/`** (3/3 files): `DetailScreen.kt` (`FichaDelItem`→`ItemHero`,
  `dosPaneles`→`twoPanels`, `estadosDeDescarga`→`downloadStates`, `sePuedeBajar`→`canDownload`,
  `porConfirmar`→`pendingConfirmation`), `DetailViewModel.kt`, `MarkersDialog.kt`.
- **`ui/downloads/`** (2/2): `DownloadsScreen.kt`, `DownloadsViewModel.kt`.
- **`ui/components/`** (2/2): `Components.kt`, `DownloadControl.kt` (was `ControlDeDescarga.kt` —
  `DescargaDeFila`→`RowDownload`, `ControlDeDescarga`→`DownloadControl`,
  `BarraDeDescarga`→`DownloadBar`, `LineaDeEstadoDeDescarga`→`DownloadStatusLine`,
  `DialogoDeDescarga`→`DownloadConfirmDialog`, `AnilloConEquis`→`RingWithX` — this is a **shared
  component used by `catalog/`**, so its ripple already touched `AnimeShowDetailScreen.kt`,
  `CineDetailScreen.kt`, `PlaySources.kt` with minimal named-arg fixes only, not full translation).
- **`ui/offline/`** (2/2): `DuplicateDownloadNotice.kt`, `NotificationPermission.kt`.
- **`ui/library/`** (1/1): `LibraryScreen.kt`.
- **`ui/theme/`** (1/1): `Theme.kt`.
- **`ui/update/`** (1/1): `UpdateDialog.kt`.
- **All 6 top-level `ui/*.kt` files**: `Graph.kt`, `ScreenFormat.kt` (was `FormatoDePantalla.kt` —
  `esTabletHorizontal`→`isLandscapeTablet`, `columnasDeGrilla`→`gridColumns`,
  `anchoDeLectura`→`readingWidth`; this is used EVERYWHERE so its rename rippled into
  `home/CategoriasScreen.kt`/`HomeScreen.kt`/`RowBrowseScreen.kt`, `catalog/CineCatalogScreen.kt`/
  `AnimeSection.kt`/`CaracolScreen.kt`, `library/LibraryScreen.kt`, `search/SearchScreen.kt`,
  `detail/DetailScreen.kt`, `live/LiveScreen.kt`, `ArkivRoot.kt`, `settings/SettingsScreen.kt`,
  `downloads/DownloadsScreen.kt` — import/call-site fixes only in those not-yet-processed files),
  `Format.kt`, `ChapterLabel.kt` (was `EtiquetaDeCapitulo.kt` — `numero`→`number`,
  `conNombre`→`withName`, `avance`→`progressSummary`, `botonReproducir`→`playButtonLabel`,
  `lineaDeHeroe`→`heroLine`; also shared everywhere, same minimal-ripple treatment applied to
  `home/HomeScreen.kt`, `tv/TvEpisodeChip.kt`/`TvDetailScreen.kt`/`TvHomeScreen.kt`,
  `detail/DetailScreen.kt`, `data/db/Daos.kt`), `ArkivRoot.kt` (`irA`→`goToTab`,
  `ancho`→`isWide`), `ArkivSplash.kt` (full translation of the boot-intro geometry/animation code;
  `DURACION_DE_LA_INTRO_MS`→`INTRO_DURATION_MS`, rippled into `MainActivity.kt`).

### `ui/` — more fully done packages (added after the above was first written)

- **`ui/settings/`** (8/8 + 1/1 test): `CandadoDeAdultos.kt`→`AdultsLock.kt`,
  `SeccionDeAdultos.kt`→`AdultsSection.kt`, `SubtitulosTab.kt`→`SubtitlesTab.kt`,
  `TabDeAjustes`→`SettingsTab`, `IDIOMAS_AUDIO`/`IDIOMAS_SUBTITULO`→
  `AUDIO_LANGUAGES`/`SUBTITLE_LANGUAGES`, `TrackLang.etiqueta()`→`.label()`. Ripples into
  `tv/TvSettingsApp.kt`, `tv/TvSettingsSubtitulos.kt`, `tv/TvLanguageOrderEditor.kt`,
  `ui/player/PlayerPistas.kt`, `data/SettingsStore.kt`, `data/gateway/LiveModels.kt`.
  **Caught and fixed a real compile break from this batch**: the `.etiqueta()`→`.label()` sed only
  matched call sites (`lang.etiqueta()`), not the bare `import ...settings.etiqueta` lines in
  `PlayerPistas.kt`/`TvLanguageOrderEditor.kt` — see the lesson noted above.
- **`ui/home/`** (8/8 + 4/4 test): `CategoriasScreen.kt`→`CategoriesScreen.kt`,
  `CategoriasViewModel.kt`→`CategoriesViewModel.kt`, `ANIME_GENRE_ES`→`ANIME_GENRE_LABELS`,
  `MedidasDelHome`→`HomeSizes`, `TopSectionsSignature`'s Spanish fields→English,
  `HomeViewModel.bibliotecaOrdenada`→`orderedLibrary`. Ripples into `ArkivRoot.kt`,
  `tv/TvCategoriasScreen.kt`, `ui/library/LibraryScreen.kt`.
- **`ui/search/`** (8/8 + 8/8 test) — the last package finished before this doc's refresh:
  `FuentesBuscando.kt`→`SearchingSources.kt`, `FuentesCaidas.kt`→`DownSources.kt`
  (`EstadoDeLasFuentes`→`SourcesState`), `tabDeFuente`→`tabForSource`,
  `filasVisibles`→`visibleRows` (in `SourceTab.kt`), `cardDeTextoLibre`→`freeTextCard`,
  `sinRepetidos`→`withoutDuplicates` (in `CardContext.kt`), and `SearchViewModel.kt`/
  `SearchScreen.kt`/`SearchPlayback.kt` fully translated (their whole Magis/Caracol
  save-and-play API: `magisEpisodeIdDe`→`magisEpisodeIdFor`,
  `encolarDescargaDeCaracol`→`enqueueCaracolDownload`, `dituEpisodeIdDe`→`dituEpisodeIdFor`,
  etc). Ripples into `tv/TvSearchScreen.kt`, `ui/catalog/CaracolScreen.kt`,
  `ui/catalog/MagisSeasonDialog.kt`, `data/recomendaciones/RecommendationSaving.kt`,
  `data/MagisEntities.kt`, and `app/src/debug/.../PruebaDeDescargaDeCaracol.kt`.

### `ui/` — more fully done packages (catalog/ and live/)

- **`catalog/`** (10/10 + 4/4 test, commit `1a12c7f1`): `EstadoDeCanales.kt`→`ChannelsState.kt`,
  `CapitulosPorTemporada.kt`→`ChaptersBySeason.kt`, `PlaySources.kt`, `MagisSeasonDialog.kt`,
  `CineDetailScreen.kt`, `AnimeShowDetailScreen.kt`, `CaracolScreen.kt`, `CineCatalogScreen.kt` all
  translated. `AnimeSection.kt` was already fully English.
- **`live/`** (9/9 + 9/9 test) — fully translated across several commits:
  `IndiceDelCajon.kt`→`DrawerIndex.kt`, `LiveZapping.kt`/`LiveZappingSource`,
  `RecentLiveChannels.kt`, `CanalesDelPais.kt`→`CountryChannels.kt`,
  `DpadDelDrawer.kt`→`DrawerDpad.kt` (with `DrawerFocus`/`DrawerAction`), `LiveController.kt`
  (`open`/`preheat`/`invalidate`/`close`, was `abrir`/`precalentar`/`invalidar`/`cerrar`),
  `LiveGuideList.kt` (`currentProgram`/`progressOf`, was `enCurso`/`avance`), `LiveViewModel.kt`
  (`LiveUiState`/`LiveViewModel` fully translated: `channels`/`categories`/`search`/`loading`/
  `chooseCategory`/`requestEpg`/`toggleFavorite`, was `canales`/`categorias`/`busqueda`/
  `cargando`/`elegirCategoria`/`pedirEpgDe`/`alternarFavorito`), and finally `LiveScreen.kt`
  itself (`LocalView`/`CategoryChip`/`ErrorWithRetry`/`ChannelGrid`/`ChannelCard`, `onOpenChannel`
  param, local `open()`/`favorite()`). Rippled into `TvCajonDeCanales.kt`, `PlayerScreen.kt`,
  `PlayerVivo.kt`, `TriviaDelPlayer.kt`, `SpinnerDelPlayer.kt`, `AppGraph.kt`,
  `PlaybackService.kt`, `LiveHlsProxy.kt`, `PlayerSource.kt`, `TvLiveGuideScreen.kt`,
  `CaracolScreen.kt`, `HomeScreen.kt`, `ArkivRoot.kt`, `data/db/Daos.kt`, `data/gateway/LiveModels.kt`
  (comment-only fixes in the last two — those files are not yet processed themselves).
  `LiveChannel`/`LiveRecentEntity`/`LiveChannelCacheEntity`/`LiveProgram` field names
  (`nombre`/`numero`/`logo`/`titulo`/`inicio`/`fin`/etc.) deliberately kept as-is: gateway-bound.
  `LiveCatalogGateway`'s own interface method names (`categorias`/`canales`/`epg`) also kept as-is:
  that interface lives in `data/gateway/LiveApi.kt`, not yet processed.

### `ui/tv/` — **100% DONE** (finished this session)

All 26 main files + tests translated (main + matching tests where they exist):

- `NavSound.kt`, `TvButtonStyle.kt` (comments only).
- `TvSettingsWidgets.kt` (`tvButtonColors`/`tvButtonBorder`, was `tvBotonColors`/`tvBotonBorder`).
- `TvTab.kt` (`label`/`selected`, was `etiqueta`/`seleccionada`; `TAB_HEIGHT`, was `ALTO_TAB`) —
  rippled into `TvSeccionesDeCatalogo.kt`, `TvSettingsScreen.kt`, `TvCaracolScreen.kt` (named-arg
  call sites).
- `TvSourceRows.kt` (`tvSourceRow`, was `tvFilaDeFuente`; `CARD_HEIGHT`/`MARGIN`, was
  `ALTO_TARJETA`/`MARGEN`) — rippled into `TvSearchScreen.kt`'s named-arg call site.
- `TvSettingsCuenta.kt` (`account`/`onLinkMagis`/`TvLinkedSection`, was
  `cuenta`/`onVincularMagis`/`TvVinculadaSection`) — rippled into `TvSettingsScreen.kt` and
  `AccountSection.kt`'s comment.
- `TvPosterCard.kt` (comments only).
- `TvSettingsSubtitulos.kt`→`TvSettingsSubtitles.kt` (`TvSettingsSubtitles` fun).
- `TvLanguageOrderEditor.kt` (`TvLangButton`, was `TvLangBoton`).
- `TvSettingsScreen.kt` (`TvSettingsTab` enum with `SUBTITLES`/`ACCOUNT`/`APP` and `label`, was
  `TabDeAjustesTv` with `etiqueta`; `magisAccount`/`linkingMagis`/`magisState`/`firstTabFocus`
  locals).
- `library/TvLibraryViewModel.kt` (`groups`/`watched`/`removeGroup`, was
  `grupos`/`vistos`/`quitarGrupo`) — rippled into `library/TvLibraryScreen.kt`'s call sites (that
  screen itself is NOT yet translated).
- `TvSettingsApp.kt` (`TvAdultsSection`/`TvChangeAdultsCode`, was
  `TvSeccionAdultos`/`TvCambiarCodigoDeAdultos`).
- `TvComponents.kt` (`TvLandscapeCard`'s `newEpisodes` param, was `nuevos`).
- `TvEpisodeChip.kt` (comments only; identifiers were already English).
- `TvFormularioConTeclado.kt`→`TvKeyboardAndFields.kt` and `TvOfertaVincularMagis.kt`→
  `TvMagisLinkOffer.kt`, translated together since the second is the sole caller of the first:
  `TvFocusedFields`/`rememberTvFocusedFields`/`TvKeyboardAndFields`/`TvFieldChip`/
  `TvPasswordVisibilityButton`/`KEYBOARD_WEIGHT`/`FIELDS_WEIGHT` (was `TvCamposConFoco`/
  `rememberTvCamposConFoco`/`TvTecladoYCampos`/`CampoTvChip`/`TvBotonMostrarPassword`/
  `PESO_TECLADO`/`PESO_CAMPOS`), and `shouldOfferMagisLink`/`TvMagisLinkOffer(account, onNotNow)`
  (was `debeOfrecerVincularMagis`/`TvOfertaVincularMagis(cuenta, onAhoraNo)`) — rippled into
  `ArkivTvRoot.kt`, `TvSettingsScreen.kt`, `SettingsStore.kt`, `MagisAccount.kt`,
  `TvHomeScreenParaTiTest.kt`'s comment references (those files are not yet fully processed).

Also fully translated (added later in the session):

- `library/TvLibraryScreen.kt` (`TvMenuItem`/`seriesSubtitle`/`TvPosterGrid`/`TvLibraryItemDialog`
  with English params; `LibraryGroup.nuevos` left as-is — belongs to `data/LibraryGrouping.kt`,
  not yet processed, a third `data/` gap alongside `MagisEntities.kt`/`DituEntities.kt`).
- `library/TvDownloadsSection.kt` (`DownloadConfirmation` enum with `STOP`/`REMOVE`,
  `TvDownloadActionsDialog` with English params) — **this completes `ui/tv/library/` fully.**
- `TvKeyboard.kt` + test (`TvKeyboardMode.UPPER`/`LOWER`/`SYMBOLS`, was `MAYUS`/`MINUS`/`SIMBOLOS`;
  `TvKey.Mode`, was `TvKey.Modo`; `onMode` param, was `onModo`) — rippled into
  `TvKeyboardAndFields.kt`/`TvMagisLinkOffer.kt`.
- `TvRowBrowseScreen.kt` (`BROWSE_HERO_SCALE`/`BROWSE_HERO_DRIFT_MS`, comments) —
  `TraerConScrollMinimo`/`Featured` (from `TvHomeScreen.kt`) left as-is.
- `TvCategoriasScreen.kt` (`HERO_SCALE`/`HERO_DRIFT_MS`, locals, comments) — same not-yet-processed
  externals left as-is.
- `TvCaracolScreen.kt` (`TvCaracolContent`/`TitleRow`/`HeroText`/`Message`/`Focused`/`CaracolRow`,
  was `TvCaracolContenido`/`FilaDeTitulos`/`TextoDelHero`/`Mensaje`/`Enfocado`/`FilaDeCaracol`, plus
  all locals) — `TvCapitulosDeCaracol`/`PivotoDeTv`/`TraerConScrollMinimo` left as-is (belong to
  `TvSearchScreen.kt`/`TvHomeScreen.kt`).
- `ArkivTvRoot.kt` (`magisConfirmed`/`showOffer`/`magisState`/`unlocked`/`scope` locals, full
  comment translation) — named args for not-yet-processed screens kept matching their real
  Spanish param names.
- `TvCajonDeCanales.kt`→`TvChannelDrawer.kt` (`TvChannelDrawer` with `focus`/`onFocus`/
  `onChooseChannel`/`currentChannel` params, `DrawerItem`/`DrawerChannelRow`/`DrawerMessage`) —
  rippled into `PlayerVivo.kt`'s import and call site.
- `TvLiveGuideScreen.kt` (`onWatchChannel`/`onBack` params, `TvLocalView`/`TvCategoryChip`/
  `TvGuideMessage`/`TvChannelRow`) — rippled into `ArkivTvRoot.kt`'s call site.
- `TvDetailScreen.kt`'s remaining dense comment blocks (identifiers were already English from the
  earlier `ChapterLabel` ripple).
- `TvSeccionesDeCatalogo.kt`→`TvCatalogSections.kt` (`TvCatalogSections` with `includeAdults`/
  `onPlay`/`onBack` params, `HeroBackground`/`HeroText`/`rowsOf`/`metadataFor`/`ItemsRow`/
  `Message`/`CatalogRow`) — `ItemDeCatalogo`/`SeccionDeCatalogo` (from `data/gateway/`, not yet
  processed) and their fields left as-is. Rippled into `ArkivTvRoot.kt`'s call site and stale
  comment references in `TvTab.kt`/`TvHomeScreen.kt`/`TvSettingsScreen.kt`/`TvCaracolScreen.kt`.

Also fully translated (finished the package):

- `TvHomeScreen.kt` (58K, the largest file in the package until this): `TvPivot`/
  `MinimalScrollBringIntoView` (was `PivotoDeTv`/`TraerConScrollMinimo`), `showForYouRow` (was
  `mostrarFilaParaTi`), `TvSeeMoreChannelsCard`/`TvSeeMoreRowCard` (was `TvVerMasCanalesCard`/
  `TvVerMasFilaCard`), `HERO_SCALE`/`HERO_DRIFT_MS`, and every local (`hasInternet`/
  `recommendationDao`/`aggregator`/`recommendations`/`recentChannels`/`countryChannels`/
  `channelsRow`/`playChannel`/`heroDrift`/`barFocus`/etc). `Featured`/`TvHomeScreen`/`TvRowLabel`/
  `recommendationFeatured` were already English. Rippled `TvPivot`/`MinimalScrollBringIntoView`
  into `TvRowBrowseScreen.kt`, `TvCatalogSections.kt`, `TvCategoriasScreen.kt`, `TvCaracolScreen.kt`
  (KDoc + `CompositionLocalProvider` call sites), and `showForYouRow` into
  `TvHomeScreenParaTiTest.kt` (fully translated too).
- `TvSearchScreen.kt` (72K, **the single largest file in all of `ui/`**): `TvCaracolChapters`/
  `TvWhatToDoWithCardDialog` (was `TvCapitulosDeCaracol`/`TvQueHacerConLaCardDialog`, with
  `series`/`onChoose`/`title`/`isSeries`/`onUseName`/`onFullSeries`/`onBySeason` params),
  `searchingSources`/`sourcesState` state (was `fuentesBuscando`/`estadoDeFuentes`), and every
  local in `TvSearchScreen` itself (`newSearch`/`recordQuery`/`searchTitles`/`searchSources`/
  `useName`/`askModeFor`/`searchNumber`/`titlesGrid`/`gridTouched`/etc, was `nuevaBusqueda`/
  `recordarConsulta`/`buscarTitulos`/`buscarFuentes`/`usarNombre`/`preguntarModo`/`busquedaNro`/
  `gridTitulos`/`grillaTocada`/etc), `TvMagisSeasonContent`'s `label` param (was `etiqueta`) plus
  `chapters`/`series`/`expected`/`ordered`/`multipleSeasons` locals, `TvMagisEpisodeRow`'s
  `chapter`/`label` params. `sourceKey`/`TvSearchScreen`/`TvRefineContent`/`TvResultsContent`/
  `TvSourceTabRow`/`TvSeasonChip`/`TvRefineRow`/`TvMagisSeasonContent`/`TvMagisEpisodeRow` were
  already English. Rippled `TvCaracolChapters`'s rename into `TvCaracolScreen.kt`'s call site and
  KDoc reference.

**`ui/tv/` is now 100% done — every one of its 26 main files (+ tests) is translated.**

### `ui/player/` — 22 of 23 main files done; only `PlayerScreen.kt` remains

Every file except `PlayerScreen.kt` is now translated, each rippled with minimal targeted fixes
into that one remaining giant as they were processed:

- `PlayerFoco.kt`→`OverlayFocusPoints.kt` (`OverlayFocusPoints` class, `rememberOverlayFocusPoints`).
- `PlayerSaltos.kt`→`OutroSkip.kt` (`OutroSkip.Action`, `SkipButtonKind` — named `Kind` not
  `Button` to avoid a real collision with `PlayerScreen.kt`'s own `private fun SkipButton(...)` —
  `SkipButtonFocus`). Test → `OutroSkipTest.kt`.
- `PlayerCabecera.kt`→`HeaderState.kt` (`HeaderState` class, `rememberHeaderState`/`HeaderEffect`).
- `PlayerControles.kt`→`ControlsState.kt` (`ControlsState` class with `bump()`/`keepAlive()`/
  `hide()`/`toggle()`, `AutoHideEffect`).
- `PlayerMarcadores.kt`→`MarkersState.kt` (`MarkMode` enum, `MarkersState` class).
- `PlayerEspejo.kt`→`PlayerMirror.kt` (`PlayerMirror` class — its `cambioElBuffering`/
  `cambioElPlaying`/`cambioLaIntencion` methods became `updateBuffering`/`updatePlaying`/
  `updateWantsToPlay`, **not** `setX()`, to avoid a real JVM signature clash with the
  `mutableStateOf` properties' own synthesized setters — a genuine `Platform declaration clash`
  compile error, not just a style choice). Rippled into `LiveExoPlayer.kt`/`DituExoPlayer.kt`/
  `MagisExoPlayer.kt` too, since all three feed this same mirror.
- `PlayerVideoLocal.kt` + test — found **already fully English**, no changes needed.
- `PausaAlSalir.kt`→`PauseOnExit.kt` (`OnBackground`/`OnReturnToLive` enums). Test →
  `PauseOnExitTest.kt`.
- `ArranqueConLaPrimeraImagen.kt`→`StartOnFirstFrame.kt` (`StartOnFirstFrame` class). Test →
  `StartOnFirstFrameTest.kt`.
- `PlayerSeek.kt`→`SeekState.kt` (`seekTarget()`, `SeekState` class). Test → `SeekStateTest.kt`.
- `EstadoDeDitu.kt`→`DituState.kt` (`DituState` class — `DituReproducible` itself, a data class
  owned by `PlayerViewModel.kt`, deliberately left untouched since that file isn't processed yet).
  Test → `DituStateTest.kt`.
- `SpinnerDelPlayer.kt`→`PlayerSpinner.kt` (`shouldShowSpinner()`). Test → `PlayerSpinnerTest.kt`.
- `PlayerGestos.kt`→`PlayerGestures.kt` (`GesturesState` class, `rememberGesturesState()`,
  `BrightnessHudEffect()`). No test file existed.
- `PlayerCapitulos.kt`→`ChapterCarousel.kt` (`ChaptersState` class, `ChaptersEffects()`,
  `ChapterCarousel()` composable). No test file existed.
- `PlayerDlna.kt` (filename kept, already English enough): `DlnaState` class (was `EstadoDlna`) —
  its `marcarActivo()` became `markActive()`, **not** `setActive()`, same JVM-clash avoidance as
  `PlayerMirror.kt`'s `updateX()` methods; `sendToRenderer()`/`ActiveDlnaBar()`/
  `DlnaDevicesDialog()` (was `mandarAlRenderer()`/`BarraDlnaActiva()`/`DialogoDispositivosDlna()`).
  No test file existed.
- `TriviaDelPlayer.kt`→`PlayerTrivia.kt` (`PlayerTrivia` object, `TriviaState` class). "Dato
  curioso" user-facing text left untouched. Test → `PlayerTriviaTest.kt`. Left `WorkKind.kt`'s
  verbatim historical note ("Lived in `TriviaDelPlayer.tipoDe` until `e161b231`...") untouched —
  documents a past commit's real class name, not a live reference.
- `PlayerVivo.kt`→`PlayerLive.kt` (`LiveState` class, `LiveBanner()`/`ChannelCard()`/
  `LiveChannelDrawer()` composables). `LiveChannel`/`LiveProgram` field accesses
  (`.nombre`/`.numero`/`.logo`/`.titulo`/`.inicio`/`.fin`) left untouched — gateway-bound. No test
  file existed for the main class (only `MensajeErrorVivoTest.kt`, which tests a *different*
  function living in `PlayerViewModel.kt` — see below).
- `PlayerPistas.kt`→`PlayerTracks.kt` (`TracksState` class, `AudioAndSubtitlesDialog()`,
  `spuLabel()`, `.realTracks()`/`.realNames()`). Test → `PlayerTracksTest.kt`.
- `LiveExoPlayer.kt` (filename kept): `onFirstFrame` param (was `onPrimeraImagen` — this
  composable's OWN param; `MagisExoPlayer.kt`'s and `DituExoPlayer.kt`'s own same-named params
  are separate declarations, translated only when each of those files got its own turn), every
  local translated. No test file existed.
- `DituExoPlayer.kt` (filename kept): `isRecoverable()` (was `esRecuperable()`);
  `localDownload`/`store`/`autoStart`/`requestReprepare`/`onPosition`/`onFirstFrame` params (was
  `descargaLocal`/`almacen`/`arrancarSolo`/`pedirRepreparado`/`onPosicion`/`onPrimeraImagen`);
  every local translated. `DituReproducible`'s own `descargaLocal`/`arrancarSolo` fields (owned by
  `PlayerViewModel.kt`) stay untouched. No test file existed.
- `MagisExoPlayer.kt` (filename kept): `onFirstFrame`/`onChapterEnd` params (was
  `onPrimeraImagen`/`onFinDelCapitulo`); `TextureView.fitAspect()` (was `.ajustarAlAspecto()`,
  kept `internal` because `LiveExoPlayer.kt` reuses it — rippled the rename there too);
  `subtitleMimeType()` (was `mimeDeSubtitulo()`); every local translated. No test file existed.

**New lesson this batch: watch for real JVM signature clashes, not just naming collisions.**
Renaming a `cambioElX(valor)`-style setter method to `setX(value)` can silently collide with
Kotlin's own auto-generated JVM setter for a `var x by mutableStateOf(...)` property of the same
name — `Platform declaration clash: ... have the same JVM signature`. This is a real compile
error, not a style nitpick, and it only surfaces when the renamed property and the renamed method
happen to share a name. Fix: prefix these methods `update`/`mark`/etc. instead of `set`. Hit twice
this batch (`PlayerMirror.kt`'s buffering/playing/wantsToPlay setters, `PlayerDlna.kt`'s
`markActive`).

**Another lesson: when a rename's sed pattern matches text that's byte-identical across multiple
call sites to DIFFERENT functions, a blanket sed can rename the wrong one.** Renaming
`LiveExoPlayer`'s `onPrimeraImagen` param to `onFirstFrame` via a sed matching the lambda body
`{ hay -> exoYaPintoAlgo = hay },` also matched the textually-identical call sites for
`MagisExoPlayer`/`DituExoPlayer` (not yet processed at the time), breaking the build with "No
parameter with name 'onFirstFrame' found". Fixed by reverting those two and later using
line-number-targeted `sed 'N s/.../.../'  ` instead of a global replace once contents diverge
across near-duplicate call sites.

- `PlayerViewModel.kt` (65K) — **DONE.** The whole file translated: data classes `PlayerData`
  (`.adult`/`.prefersSoftware`, was `.adulto`/`.preferirSoftware`), `PlaylistData` (`.requested`,
  was `.pedido`), `DituReproducible` (`.generation`/`.autoStart`/`.localDownload`, was
  `.generacion`/`.arrancarSolo`/`.descargaLocal`); top-level `shouldLogHistory()`/
  `shouldMarkInProgress()`/`liveErrorMessage()` (was `hayQueAnotarHistorial()`/
  `hayQueMarcarEnCurso()`/`mensajeErrorVivo()`); constructor params `isTv`/`source`/
  `hasMagisAccount`/`triviaFacts` (was `esTelevision`/`fuente`/`hayCuentaDeMagis`/
  `datosCuriosos` — `dituFuente` kept, parallel to the deliberately-Spanish `DituFuente` class
  name); live-mode `liveChannel`/`liveGeneration` StateFlows and `openCurrentChannel()`/
  `zapNext()`/`zapPrevious()`/`reopenLiveAfterCut()`/`liveIsPlaying()`/`goToChannel()`/
  `preheatNeighbors()` methods (was `liveCanal`/`generacionVivo`/`abrirCanalActual()`/
  `zapSiguiente()`/`zapAnterior()`/`reabrirVivoPorCorte()`/`vivoAndando()`/`irACanal()`/
  `precalentarVecinos()`); `captureFrame()`/`dituCanReprepare()`/`dituAdvanced()`/
  `onPlaybackHealthy()`/`editMarker()` and every local variable. Rippled the constructor call site
  and ~30 `vm.<method>()` call sites plus every data-class field access into `PlayerScreen.kt`
  (minimal targeted ripple, not yet processed itself), and fixed stale KDoc cross-references in
  `LiveExoPlayer.kt`, `PauseOnExit.kt`, `LiveHlsProxy.kt`, `HistorySignals.kt`, `DrawerDpad.kt`/
  `DrawerDpadTest.kt`, `MediaReusePolicy.kt`, `DituState.kt`/`DituStateTest.kt`. Left two verbatim
  historical purge notes in `ArkivApp.kt`/`Daos.kt` referencing the old `abrirCanalActual` name
  untouched (they document a specific 2026-08-14 commit's real function name). Rewrote the three
  deferred test files: `MensajeErrorVivoTest.kt`→`LiveErrorMessageTest.kt`,
  `ProgresoDeAdultosTest.kt`→`AdultProgressTest.kt`,
  `VivoDeCaracolNoSeAnotaTest.kt`→`LiveCaracolNotLoggedTest.kt`.

- `PlayerScreen.kt` (234K on disk, ~4038 lines after translation, by far the largest file in the
  entire codebase) — **DONE, commit `4e5ce086`.** Translated top-to-bottom via ~80 sequential
  `Edit` calls (not a single `Write` rewrite — deliberately chosen given the file's size, to use
  `Edit`'s exact-match-or-fail semantics as a safety net against silent corruption). Renamed
  throughout: constants (`ZAP_THRESHOLD_PX`/`SHOW_MARKERS_ON_PHONE`/
  `SHOW_SPEED_AND_ZOOM_ON_PHONE`/`SCREEN_SEQ`), dozens of locals (`isLive`/`isMagisLive`/
  `currentEpisode`/`header`/`focusPoints`/`chaptersState`/`mirror`/`triviaState`/
  `castToReceiver`/`remuxFailed`/`controls`/`liveState`/`liveChannel`/`markers`/`tracksState`/
  `dlnaState`/`gestures`/`chapterMarkers`/`currentMarker`/`outroAction`/`skipButton`/
  `skipHadFocus`/`skipFocused`/`skipButtonFocus`/`scrim`/`isPortrait`/`hasSecondaryButtons`/
  `secondaryIcons`/`chapterName`/`drag`/`displayedPosition`/etc), functions (`magisIsTs`/
  `remuxOffset`/`positionBelongsToThisScreen`/`onEndOfChapter`/`markTime`/
  `removeChapterMarkers`/`videoTextureView`/`castIsLive`), and the private composable
  `MenuDeMarcadoresDelCapitulo`→`ChapterMarkersMenu` (params `estado`→`state`,
  `onFinDelOpening`→`onEndOfOpening`, `onInicioDelEnding`→`onStartOfEnding`,
  `onQuitar`→`onRemove`). `SoftwareReloadTest.kt` was checked earlier and found already fully
  English — no action needed.
  Also caught and fixed, while rippling `espejo`→`mirror` into the three ExoPlayer wrapper call
  sites: `MagisExoPlayer.kt`/`DituExoPlayer.kt`/`LiveExoPlayer.kt` (all already "done" earlier this
  sweep) still declared their own composable parameter as `espejo: PlayerMirror` instead of
  `mirror: PlayerMirror` — an oversight from that earlier work, fixed opportunistically.
  Left untouched (exception categories d/c): `graph.fuenteDeContenido`/`graph.catalogoDeVivo`/
  `graph.almacenDeCaracol` (owned by `AppGraph.kt`, not yet processed), `liveChannel?.nombre`
  (owned by `data/gateway/LiveModels.kt`, not yet processed), and the KDoc's verbatim historical
  reference to libVLC's old `superficieDistintaALaDelVideo` method (documents dead, removed code).

**`ui/player/` is now 100% done — all 23 main files (+ tests) translated. This finishes `ui/`
in its entirety.**

## Workflow to follow (established over 45+ commits this session)

1. Read the file(s) fully before touching anything.
2. For every rename: grep ALL real usages first — **including `app/src/debug/`**, which has
   caused real compile breaks from being forgotten — verify receiver types before assuming a match
   (two unrelated types can coincidentally share a method/property name).
3. Rename + ripple with Edit/targeted `sed`. **BSD/macOS `sed` does not support `\b` word-boundary
   syntax reliably** (confirmed to fail silently, not error) — use literal substrings instead.
4. Kotlin named-argument risk: renaming a function/constructor parameter breaks every call site
   using it as a named argument. Grep for `paramName =` specifically, not just the bare identifier.
   This bit twice this session (`StillMerge.merge(previa = ...)` compile failure,
   `DownloadConfirmDialog(accion = ...)` call sites in two not-yet-processed `catalog/` files).
5. When a shared/low-level symbol used by not-yet-processed files gets renamed, ripple with
   **minimal, targeted fixes only** into those files (import statements, the renamed call/named
   args) — do NOT fully translate an out-of-scope file just because one line in it had to change.
   Leave a note (like this doc does above) about what's left untouched there.
6. Compile: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, fix everything.
7. Full test: `./gradlew :app:assembleDebug :app:testDebugUnitTest`, then verify the **exact**
   count via:
   ```
   command grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml | \
     command grep -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' | \
     awk -F'"' '{tests+=$2; skipped+=$4; failures+=$6; errors+=$8} END {print "tests="tests, "skipped="skipped, "failures="failures, "errors="errors}'
   ```
   Current baseline: **1704 tests, 0 skipped, 0 failures, 0 errors.** Must stay exactly there (or
   higher, never lower) after every commit.
8. Stage only the specific files touched — **never `git add -A`**, other sessions may share this
   working tree. When a file was renamed with `git mv` and then edited, `git add` the *new* path to
   pick up both the rename and the content diff.
9. Commit as `lordmacu <10134930+lordmacu@users.noreply.github.com>` (already the repo's git
   identity), message style `refactor(data): ...` / `refactor(ui): ...`, imperative, short. **Never
   add a `Co-Authored-By: Claude` line or any attribution footer** — the repo's root `CLAUDE.md`
   forbids it, which overrides the harness's own default attribution reminder.

## Established exception categories — what stays in Spanish, always

a. **UI-facing strings/labels/dialog text** shown directly to the end user (Bogotá Spanish, tuteo,
   never voseo) — e.g. "Bajando 42%", "¿Cancelar la descarga?", library section labels,
   `contentDescription`, snackbar text, dialog titles/bodies.
b. **Verbatim historical log-line quotes** documenting a measured bug (quoted exactly as observed).
c. **On-disk/serialized identifiers**: Room entity bare properties (every entity in this schema has
   zero `@ColumnInfo` annotations, so the bare Kotlin property name IS the SQL column name —
   `.tipo`, `.titulo`, `.origen`, `.tituloCanonico`, `.episodiosVistosEnLista`, `.porque`, `.orden`,
   `.generadoAt`, `.nombre`, `.numero`, `.categoria`, `.origenRemoto`, etc. — never rename these),
   DAO Row/DTO fields bound to `SELECT ... AS alias` (Room fails **silently** on a mismatch — e.g.
   `ProgresoConSiguienteRow.siguienteEpisodeId`, `FilaDeHistorial`'s fields and class name),
   SharedPreferences key/value literals, JSON field-name literals persisted across app versions —
   only the Kotlin *constant names* holding these literals may translate, never the literal values.
   Regex literals matching stored text (e.g. `EncodedNumbering`'s `"^Temporada (\d+)$"`) are also
   frozen for the same reason.
d. **Symbols in files not yet processed** are referenced by their old Spanish name until that
   file's own turn; ripple into consumer files uses **minimal, targeted fixes** only.
e. **Natural-language content sent to/from an LLM as prompt or data** (Kilo AI prompts, status
   strings fed into a prompt, filename-noise-pattern regexes like the one in
   `ArkivRepository.cleanTitleForSearch`) — this is data, not code.
f. ~~Two `ContentSource` implementations — `MagisFuente`/`DituFuente` — kept their Spanish class
   names~~ — **CLOSED**: renamed to `MagisSource`/`DituSource`, commit `fc49028d`.

## Next steps

**The accented-character sweep is done.** `ui/`, `data/`, `crash/`, `app/src/debug/` are fully
translated; both prior deferrals (`MagisFuente`/`DituFuente`, `LiveModels.kt` fields) are closed;
`AppGraph.kt`/`ArkivApp.kt`/`MainActivity.kt` have translated comments with identifiers
deliberately deferred (DI-graph ripple, see "Deliberately deferred"). The final sweep
(`app/src/main/java` + `app/src/debug/java` + `app/src/test/java`) sits at **138 files, every one
individually verified as a legitimate false positive** (see "Overall completion" at the top).
Re-run this before trusting the number again:
```
find app/src/main/java app/src/debug/java app/src/test/java -name "*.kt" | while read -r f; do n=$(command grep -c "[áéíóúñÁÉÍÓÚÑ]" "$f"); [ "$n" != "0" ] && echo "$n $f"; done | sort -rn
```

**What's left, in priority order if this is picked back up:**

1. **Spanish test method names with no accented characters** (`fun algo_en_español()` without the
   accent, or plain Spanish words like `guarda`/`falla`/`vacio`) — discovered but explicitly
   NOT started; the accented-character sweep structurally cannot find these. Potentially hundreds
   of names across dozens of test files. Confirm scope with the user before starting — this is a
   materially different, larger kind of task than anything closed so far, closer to a rename
   project than a gap-closing pass. **This is now the only known remaining body of work.**
2. Do one more full-codebase accented-character sweep before declaring anything "100% done" —
   every previous "done" declaration this session turned out to be wrong on the first check, six
   separate times now. Read every hit before judging it — most will be legitimate UI-facing text,
   LLM prompt data, or Room/DAO-frozen fields; a hit is a thing to check, not automatically a gap.
3. **Before any blanket regex rename touching a file with `const val KEY_* = "literal"`-style
   frozen string constants, diff every string literal in the file against `git show HEAD:<path>`
   after the rename, before compiling.** A `\bidentifier\b` regex has no concept of "inside a
   string literal" — if a frozen SharedPreferences/JSON/Room literal's CONTENT happens to equal the
   identifier text being renamed (e.g. `KEY_ADULTOS_DESBLOQUEADO = "adultosDesbloqueado"`), a
   blanket rename corrupts the literal too, silently, with no compile error (it's still valid
   Kotlin, just now writes to a different, wrong prefs key on next launch). Caught once in the
   `SettingsStore.kt` pass via `diff <(git show HEAD:<path> | grep -oE '"[^"]*"') <(grep -oE
   '"[^"]*"' <path>)` — run that check after every bulk rename near a `const val`.
4. **Whenever a bare top-level `fun`/`val` gets renamed** (not a class/object member), grep
   separately for `^import .*\.<oldName>$` — a call-site-anchored sed pattern will not catch a bare
   import line, and that's a real, previously-hit compile break (see the lesson noted near the top
   of this doc).
5. **When a rename's sed pattern could match multiple near-identical call sites to DIFFERENT
   functions**, a blanket sed will rename the wrong one too. Check the compile error carefully (it
   names the exact line) and use a line-number-targeted `sed 'N s/.../.../''` to fix only the
   intended call site.
7. **After every rename, grep the WHOLE repo (not just the package) for stale KDoc/comment
   cross-references** — this session repeatedly found stale mentions in already-processed files
   several packages away (`playback/LiveHlsProxy.kt`, `data/recomendaciones/HistorySignals.kt`,
   `ui/live/DrawerDpad.kt`) that a package-scoped grep would have missed. Two exceptions:
   verbatim historical notes tied to a specific past commit (e.g. `ArkivApp.kt`/`Daos.kt`'s
   2026-08-14 purge comments naming `abrirCanalActual`) document what the code was ACTUALLY called
   at that commit and should stay as-is.
8. **On a giant file (`PlayerScreen.kt`'s ~4000 lines proved this out), even after a full read and
   careful pass, a final accented-character + common-Spanish-word grep over the WHOLE file still
   turned up ~10 missed spots** — mostly stray leftover comment lines and stale identifier
   references from earlier ripple work that predated the file's own translation turn (e.g.
   `estadoDlna`/`estadoVivo`/`estadoTrivia`/`estadoPistas`/`marcadorVigente` still appearing dozens
   of lines past where the equivalent local was first renamed). **Always run that final sweep after
   finishing a large file, even one done carefully via sequential `Edit` calls** — it catches
   things a top-to-bottom pass alone misses when the same identifier appears far apart in the file.
9. Update this document and the auto-memory file `code-must-be-english.md` again at the next
   natural pause point.
