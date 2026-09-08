# Arkiv Light — Sub-proyecto 1 (Poda Estructural) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reducir la rama `light-magis` a: Magis (VOD + canal en vivo, vía el gateway `arkiv-api` sin cambiar protocolo) + TMDB (vía gateway, sin cambios) + biblioteca/progreso/miniaturas 100% locales en Room (sin sync) + login/PocketBase mínimo (el que Magis necesita para autenticarse). Todo lo demás (torrent, web, archive.org, Ditu, cloud-sync, control remoto TV↔celu, pareo QR, búsqueda multi-fuente, licencias/registro, NUC offline) se borra del árbol de verdad, no se comenta ni se pone detrás de un flag.

**Architecture:** Cada tarea borra un subsistema completo (archivos exclusivos) y en el mismo movimiento arregla todos sus consumidores (imports, referencias, rutas de navegación, wiring de `AppGraph`) para que el proyecto compile al final de la tarea — ninguna tarea deja el árbol en un estado intermedio roto. El orden importa: primero se resuelve si VLC sigue existiendo (gatea si hay que tocar `VlcPlayer.kt` en tareas posteriores), después se borran las fuentes de contenido (torrent+web, archive), después las features transversales que dependen de varias fuentes (Ditu, cloud-sync/pareo, búsqueda, licencias, NUC offline), y al final limpieza de Gradle + verificación completa en dispositivo.

**Tech Stack:** Kotlin, Jetpack Compose (mobile + Compose for TV), Room, OkHttp, ExoPlayer/media3, libVLC (condicional, ver Task 1), Gradle KSP.

**Spec:** `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md`

## Global Constraints

- No comentar código muerto ni dejarlo detrás de un flag — se borra el archivo o las líneas.
- No tocar el protocolo/endpoints de Magis ni TMDB en este sub-proyecto — siguen vía el gateway `arkiv-api`, sin cambios (eso es sub-proyecto 2).
- No sacar login/PocketBase todavía (ver spec, sección "Por qué el login NO se saca todavía").
- Cada tarea debe dejar el proyecto compilando (`./gradlew :app:compileDebugKotlin`) antes del commit de esa tarea.
- Identidad de git para todos los commits: `user.name=lordmacu`, sin línea de coautoría (ya configurado en este worktree).
- Todos los comandos de shell se ejecutan desde `/Users/cristian/archive/.claude/worktrees/light-magis` (este worktree), nunca desde `/Users/cristian/archive`.

---

### Task 1: Canal en vivo de Magis a ExoPlayer (gate de VLC)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`loadLive`/`abrirCanalActual`, líneas ~379-440 — dejar de publicar `PlaylistData` para VLC y publicar el estado que consume un reproductor ExoPlayer, siguiendo el mismo patrón que `MagisExoPlayer`/`isMagis`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (líneas 1163-1186 `isDitu`/`isMagis`/`isExo` — agregar `isLive`/`isLiveExo` al mismo patrón; línea ~1978-1990 wiring de `MagisExoPlayer` — agregar un composable equivalente para vivo, o reusar `MagisExoPlayer` si el `MediaItem` que arma acepta HLS sin cambios)
- Read (no modificar sin necesidad): `app/src/main/java/com/arkiv/player/playback/LiveHlsProxy.kt`, `app/src/main/java/com/arkiv/player/data/gateway/LiveApi.kt`, `app/src/main/java/com/arkiv/player/ui/player/MagisExoPlayer.kt` (para entender la firma exacta a reusar/imitar)

**Interfaces:**
- Consumes: `LiveHlsProxy` ya expone una URL local (`lanUrl`/lo que arme `abrirCanalActual`) con el manifest HLS + headers ya inyectados — no cambia.
- Produces: si la migración funciona, un nuevo estado análogo a `magisItem`/`dituDrmItem` (ej. `liveItem: MediaItem?`) que `PlayerScreen` usa para decidir `isExo`. Las tareas siguientes (2-9) asumen que **VLC se borra del todo** si este task se completa con éxito; si no, dejan `VlcPlayer.kt` y sus proxies vivos, acotados a `SourceKind.LIVE`.

- [ ] **Step 1: Reproducir hoy el canal en vivo en `main` (o en este worktree tal cual está) y anotar el comportamiento base** — abrir un canal, medir tiempo hasta imagen, zapear a otro canal 3 veces seguidas, anotar si corta o traba. Esto es la referencia para comparar después.

- [ ] **Step 2: Adaptar `abrirCanalActual()` para exponer un `MediaItem` de ExoPlayer** en vez de (o además de, temporalmente) la `PlaylistData` de VLC. Seguir el mismo patrón que ya usa Magis VOD (`magisItem`, ver `PlayerScreen.kt:1981-1990` y su construcción en `PlayerViewModel`): headers de `LiveHlsProxy` van al `MediaItem` como se hace hoy para Magis VOD.

- [ ] **Step 3: Compilar** — `./gradlew :app:compileDebugKotlin`. Esperado: sin errores (el path VLC sigue existiendo en paralelo todavía, no se borra en este step).

- [ ] **Step 4: Instalar en dispositivo real y probar el canal en vivo por el nuevo path ExoPlayer** — mismo protocolo de prueba del Step 1 (tiempo hasta imagen, zapeo x3, dejar 5 min reproduciendo sin cortes). Si hay canales con DRM o headers especiales, probar al menos uno de cada tipo si existen.

- [ ] **Step 5: Decisión.**
  - Si el playback es igual o mejor que con VLC: eliminar el path VLC del canal en vivo (`PlaylistData` para `SourceKind.LIVE`, cualquier `if`/`when` que todavía la arme), dejar solo el path ExoPlayer. Anotar en el plan (editar este archivo) que VLC se borra en Task 2.
  - Si aparece un bloqueo real (formato no soportado, DRM que ExoPlayer no maneja, latencia de zapeo inaceptable): revertir el Step 2 (VLC sigue sirviendo el canal en vivo) y anotar en este archivo que Task 2 NO borra VLC, solo poda las ramas torrent/web/archive de `VlcPlayer.kt`.

- [ ] **Step 6: Commit**

```bash
command git add -A
command git commit -m "feat(live): migrar canal en vivo de Magis a ExoPlayer"
```
(o, si Step 5 fue negativo: `command git commit -m "docs(live): VLC se mantiene para el canal en vivo, bloqueo tecnico: <razon>"` sin cambios de código más que la anotación en el plan)

---

### Task 2: Borrar torrent + web + mirror (VLC se mantiene)

**Resuelto tras Task 1 (2026-09-08):** el canal en vivo de Magis se migró a ExoPlayer
(`LiveExoPlayer.kt`, commits `537dadbb`..`4c3b846a`), pero **no hubo dispositivo disponible para
verificarlo en la práctica** (`adb devices` vacío durante toda la sesión). Decisión: **VLC NO se
borra en esta tarea.** `VlcPlayer.kt` y la dependencia `libvlc-all` quedan, formalmente sin uso
real desde ningún `SourceKind` conocido tras esta tarea (torrent/web/archive se borran acá; live ya
migró a ExoPlayer) — se dejan intactos de todos modos hasta que un humano verifique en dispositivo
real que el canal en vivo funciona bien por ExoPlayer y autorice explícitamente borrar VLC. Esa
verificación y el borrado quedan anotados como pendientes para cuando haya dispositivo disponible
(no es parte de este sub-proyecto de poda salvo que se retome explícitamente).

**Files — NO borrar en esta tarea:** `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt`,
dependencia `org.videolan.android:libvlc-all` en `build.gradle.kts`. Si al auditar los consumidores
de `VlcPlayer.kt` (`grep -rl VlcPlayer app/src/main`) resulta que ya no le queda NINGÚN caller real
(ni siquiera para torrent/web/archive) tras borrar esas fuentes, no lo borres igual — dejalo como
código sin uso y anotalo en el reporte; el borrado requiere la verificación en dispositivo
mencionada arriba, no solo ausencia de callers.

**Files — Delete (Torrent):**
- `app/src/main/java/com/arkiv/player/torrent/` completo: `EpisodeFilePicker.kt`, `PackFileParser.kt`, `PersistentTorrentDownload.kt`, `SampleFilter.kt`, `StreamBuffering.kt`, `SubtitleFilePicker.kt`, `TorrentEngine.kt`, `TorrentServingService.kt`, `TorrentStreamServer.kt`, `TrackerListProvider.kt`, `TrackerScraper.kt`
- `app/src/main/java/com/arkiv/player/ui/torrent/TorrentScreen.kt`, `TorrentViewModel.kt`
- `app/src/main/java/com/arkiv/player/data/local/TorrentDownloadStrategy.kt`, `TorrentSizeGate.kt`
- Tests: `app/src/test/java/com/arkiv/player/torrent/*` (8 archivos), `app/src/test/java/com/arkiv/player/data/local/TorrentSizeGateTest.kt`

**Files — Delete (Web):**
- `app/src/main/java/com/arkiv/player/data/catalog/web/` completo (14 archivos: `CfClearanceStore.kt`, `CloudflareSolver.kt`, `FieldRule.kt`, `HtmlParser.kt`, `HttpFetcher.kt`, `SeriesEpisodeParser.kt`, `WebHtmlParser.kt`, `WebJsonBackend.kt`, `WebModels.kt`, `WebResolverApi.kt`, `WebSourceBackend.kt`, `WebSourceDefinition.kt`, `WebSourceEngine.kt`, `WebSourceRegistry.kt`, `WebTmdbMatcher.kt`)
- `app/src/main/java/com/arkiv/player/ui/catalog/WebPackDialog.kt`
- Tests: `app/src/test/java/com/arkiv/player/data/catalog/web/*Test.kt` (11 archivos)

**Files — Delete (Mirror, compartido torrent+web):**
- `app/src/main/java/com/arkiv/player/data/catalog/mirror/` completo: `MirrorApiClient.kt`, `MirrorFilter.kt`, `MirrorMapper.kt`, `MirrorWebFilter.kt`, `MirrorWebMapper.kt`, `WebMirrorModels.kt`, `WebSourceEpisode.kt`, `WebSourceSeason.kt`
- `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` (fachada única de Mirror, sirve torrent Y web — confirmar primero con `grep -rl TorrentSearchApi app/src/main` que ningún consumidor de Magis/TMDB lo necesita)
- Tests: `app/src/test/java/com/arkiv/player/data/catalog/mirror/*Test.kt` (10 archivos)

**Files — Delete (packs, exclusivos de torrent/web):**
- `app/src/main/java/com/arkiv/player/data/catalog/PackContents.kt` (clase `PackResolver`; si `PackRowBuilder` en el mismo archivo tiene algún uso desde Magis, extraerla a un archivo propio en vez de borrar el archivo entero — confirmar con `grep -rn PackRowBuilder app/src/main`)
- `app/src/main/java/com/arkiv/player/data/catalog/PackDetector.kt` (confirmar antes con `grep -rl PackDetector app/src/main` que no lo usa nada de Magis)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/PackDetectorTest.kt`

**Files — Modify:**
- `app/build.gradle.kts`: quitar líneas 159-161 (`libtorrent4j`, `libtorrent4j-android-arm64`, `libtorrent4j-android-arm`), línea 167 (`jsoup:1.17.2`). NO tocar la línea 156 (`libvlc-all`) — VLC se mantiene (ver nota "Resuelto tras Task 1" arriba).
- `app/src/main/java/com/arkiv/player/data/AppGraph.kt`: quitar `TorrentEngine` (línea ~330), `TrackerListProvider` (línea ~329), `MirrorApiClient` (línea ~396), `TorrentSearchApi` (línea ~412), `PackResolver(torrentSearchApi, torrentEngine)` (línea ~417), `WebSourceEngine` (línea ~430), y el import de la línea ~34. La línea ~288 (`LocalFileServer(lanIp = { torrentEngine.lanIp() })`) — reemplazar `lanIp` por una implementación que no dependa de `TorrentEngine` (ver qué otro componente ya conoce la IP LAN del dispositivo, ej. `NsdHelper`/`sync` antes de que se borre en Task 5 — si no hay otro, usar `NetworkInterface` directo).
- `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`: quitar imports de torrent (línea ~96) y de lo que quede de web, el botón de torrent (líneas ~286-287), la ruta `composable("torrent")` (líneas ~566-572).
- `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`: quitar la ruta `composable("torrent")` (líneas ~348-353).
- `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt`: quitar `SourceKind.TORRENT` y `SourceKind.WEB` (si existe) del enum y su rama en `kindFor()` (línea ~74) — dejar el `when` exhaustivo solo con los kinds que sobreviven.
- `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` y `PlayerViewModel.kt`: quitar el estado `isTorrent`/`graph.torrentEngine.streamStatus()` (`PlayerScreen.kt:1239`) y cualquier rama de `when(SourceKind)` para `TORRENT`/`WEB`.
- `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`: quitar las variantes `Torrent`, `Web`, `WebPack` de la sealed class `PlaySource` (dejar `Magis` y lo que sobreviva de Ditu, que se borra recién en Task 4 — si Ditu todavía no se tocó, dejar su variante intacta por ahora).
- `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`, `AnimeShowDetailScreen.kt`, `CineCatalogScreen.kt`, `ui/detail/DetailScreen.kt`: quitar referencias a `PlaySource.Web`/`WebPackDialog` y a resultados de torrent.
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`: revisar si depende de `TorrentSearchApi`/Mirror para anime — si sí, quitar esa rama y dejar solo lo que sirva a Magis/TMDB.
- `app/src/main/java/com/arkiv/player/ui/theme/Theme.kt`: revisar la referencia a `data.catalog.web` encontrada en el inventario (probablemente solo un color/ícono de la fuente "web") y quitarla.

**Nota — búsqueda:** `SearchViewModel.kt`/`ArkivApiClient.kt` tienen mucho código de fan-out que toca torrent/web/archive en la misma función que Magis (`runSourceSearch`, `search`). **No los toques en esta tarea** salvo lo estrictamente necesario para que compile (por ejemplo, si una función deja de compilar porque llamaba a `torrentSearchApi` directamente, comentá esa línea puntual con un `// TODO(task 6)` — la limpieza completa y prolija de búsqueda es la Task 6, dedicada). Si preferís no dejar ningún `TODO`, adelantá aquí las líneas mínimas de `SearchViewModel.kt` que referencian símbolos borrados en este task (bloques torrent/web de `runSourceSearch`, ver Task 6 para los rangos exactos) — pero no toques `buscarPorFrase` ni el bloque Magis todavía.

- [ ] **Step 1: Borrar los archivos listados arriba** (VLC condicional, torrent, web, mirror, packs) con `rm`/`git rm`.

- [ ] **Step 2: Quitar las dependencias de `app/build.gradle.kts`** listadas arriba.

- [ ] **Step 3: Arreglar cada archivo de la lista "Modify"** uno por uno, quitando imports y código muerto que ya no compila.

- [ ] **Step 4: Compilar** — `./gradlew :app:compileDebugKotlin`. Repetir Step 3 hasta que no queden errores de símbolos no resueltos.

- [ ] **Step 5: Correr los tests que sobreviven** — `./gradlew :app:testDebugUnitTest`. Esperado: los tests de torrent/web/mirror/pack ya no existen (se borraron con el código); el resto pasa. Si algún test que no tocaste falla por una referencia indirecta, arreglarlo ahí mismo.

- [ ] **Step 6: Commit**

```bash
command git add -A
command git commit -m "chore(light): borrar torrent, web, mirror y (si aplica) VLC"
```

---

### Task 3: Partir `ArchiveCacheProxy.kt` y borrar archive.org

**Files — Delete (exclusivos de archive.org):**
- `app/src/main/java/com/arkiv/player/data/ArchiveApi.kt`, `ArchiveUrls.kt`, `NodoDeArchive.kt`, `CoincidenciaDeArchivo.kt`
- `app/src/main/java/com/arkiv/player/playback/DiskLruCache.kt`
- Tests: `app/src/test/java/com/arkiv/player/data/ArchiveLibraryTest.kt`, `ArchiveSearchRankTest.kt`, `CoincidenciaDeArchivoTest.kt`, `NodoDeArchiveTest.kt`, `app/src/test/java/com/arkiv/player/playback/DiskLruCacheTest.kt`

**Files — Modify (partir, no borrar):**
- `app/src/main/java/com/arkiv/player/playback/ArchiveCacheProxy.kt` (1867 líneas): borrar `ensureDownload`/`runDownload`/`serveGrowing`/`readFromDisk`/`streamGrowingFromDisk`/`serveFromFile` y la clase `Download` (bloque ~545-822), `abrirEnNodo`/`leerTexto` (bloque ~1005-1081). Mantener intactos: `start()/stop()/proxyUrl()/bufferedFraction()` (341-431), el dispatcher de `serve()` quitándole solo las ramas de disk-cache (433-538), `precalentar*` (1100-1427, "Solo lo usa Magis"), `servirDeVentana`/`precalentarSalto`/`registrarVentana` (1448-1643), `passthrough()` (1646-1865, dejar el perfil `directo=true`/MAGIS como único camino real; el fallback ARCHIVE puede quedar como código muerto seguro o borrarse si ya no hay caller que pase `directo=false` tras borrar el resto — confirmar antes de decidir). Considerar renombrar el archivo (ya no es solo de archive) — si se renombra, actualizar todos los imports.
- `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`/`SearchViewModel.kt`: quitar las llamadas a `archiveApi.search(...)` (`SearchViewModel.kt` línea ~303-310 y ~630) y `mirrorApiClient.libraryItem(id)` si sobrevivió Task 2 solo por esto (si Task 2 ya borró `MirrorApiClient` del todo, esta llamada ya no compila — confirmar que quedó cubierta ahí; si no, bórrala acá).
- `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`: quitar la variante `Archive` de `PlaySource`.
- Tests que se mantienen pero pueden necesitar ajuste tras partir la clase (correr y ver cuáles rompen): `ArchiveCacheProxyHeadersTest.kt`, `ArchiveCacheProxyLifecycleTest.kt`, `ArchiveCacheProxyResumeTest.kt`, `ColaCalienteTest.kt`, `ColaEnDiscoTest.kt`, `ColaDuplicadaTest.kt`, `ColaEnVueloTest.kt`, `ColaPorRangoAbsolutoTest.kt`, `PrecalentadoConDuracionTest.kt`, `PrecalentadoNoBloqueaTest.kt`, `PrecalentarSaltoTest.kt`, `VentanaDeSaltoTest.kt`, `ConexionUnicaTest.kt`, `ConexionesVivasTest.kt`, `RangeHeaderTest.kt`, `TsDurationProbeTest.kt`.
- `VentanaDeArchivoTest.kt`, `VentanaDeDescargaTest.kt`: revisar si `VentanaDeArchivo.kt`/`VentanaDeDescarga.kt` (resume-por-fracción, usado solo por archive vía `fraccion` en `passthrough()`/`serve()`) quedan sin caller tras esta tarea — si Magis siempre llama con `fraccion=0`, borrar esos dos archivos y sus tests; si no, dejarlos.

- [ ] **Step 1: Borrar los archivos exclusivos de archive.org** listados arriba.

- [ ] **Step 2: Editar `ArchiveCacheProxy.kt`** quitando los bloques listados, dejando el resto intacto línea por línea (no reformatear lo que se mantiene).

- [ ] **Step 3: Arreglar consumidores** (`SearchViewModel.kt`, `PlaySources.kt`) según la lista de arriba.

- [ ] **Step 4: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 5: Correr tests** — `./gradlew :app:testDebugUnitTest`, resolver los que dependían de las ramas borradas (adaptar o borrar caso por caso, no dejar ninguno rojo).

- [ ] **Step 6: Smoke test en dispositivo** — reproducir una película/capítulo de Magis de punta a punta (play, pausa, seek, salir y reanudar) para confirmar que `precalentar`/`passthrough` siguen funcionando igual que antes de partir el archivo.

- [ ] **Step 7: Commit**

```bash
command git add -A
command git commit -m "chore(light): borrar archive.org, partir ArchiveCacheProxy (queda solo el camino de Magis)"
```

---

### Task 4: Borrar Ditu (Caracol Play)

**Files — Delete:**
- `app/src/main/java/com/arkiv/player/ui/catalog/CaracolScreen.kt`
- `app/src/main/java/com/arkiv/player/ui/live/DituLivePlayerScreen.kt`
- `app/src/main/java/com/arkiv/player/ui/player/DituExoPlayer.kt`
- `app/src/main/java/com/arkiv/player/data/DituEntities.kt`
- `app/src/main/java/com/arkiv/player/data/local/DituDownloadStrategy.kt`
- Test: la porción de `app/src/test/java/com/arkiv/player/data/gateway/GatewayMapperTest.kt` que cubre casos Ditu (no borrar el archivo entero si también prueba Magis — solo los tests/casos Ditu).

**Files — Modify:**
- `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt`: borrar `dituCatalog()`, `dituResolveLive()`, `dituChannels()`.
- `app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt`: borrar los tipos `Ditu*` (`DituCatalogResponse`, `DituSerieItem`, `DituChannel`, etc. — confirmar la lista completa con `grep -n "^.*Ditu" app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt`).
- `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`: quitar `isDitu`/`dituDrmItem` (líneas ~337, 589, 1163-1186, 1222) y el composable `DituExoPlayer(...)` (línea ~1962).
- `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`: quitar imports (~79-80), `CaracolScreen(...)` (~431), `DituLivePlayerScreen(...)` (~450).
- `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`: quitar `CaracolScreen(...)` (~292), `DituLivePlayerScreen(...)` (~311).
- `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`: quitar la variante `Ditu` de `PlaySource`.
- `app/src/main/java/com/arkiv/player/data/local/FuenteDeDescarga.kt`: si tiene un caso Ditu mezclado con otros, borrar solo esa rama (no el archivo si sirve también a Magis).

- [ ] **Step 1: Borrar los archivos exclusivos de Ditu.**

- [ ] **Step 2: Arreglar los consumidores** listados arriba.

- [ ] **Step 3: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 4: Correr tests** — `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 5: Commit**

```bash
command git add -A
command git commit -m "chore(light): borrar Ditu/Caracol Play (vuelve en sub-proyecto 3 con cliente directo)"
```

---

### Task 5: Borrar cloud-sync, pareo QR, control remoto TV↔celu, descubrimiento LAN, presencia, servicio de TV

**Files — Delete:**
- `app/src/main/java/com/arkiv/player/cloudsync/` completo: `CloudSyncManager.kt`, `PbSyncClient.kt`, `LwwMerge.kt`, `PushFrontier.kt`, `SyncCursors.kt`, `SyncMappers.kt`, `SyncQuarantine.kt`
- `app/src/main/java/com/arkiv/player/pairing/` completo: `PairCode.kt`, `PairCrypto.kt`, `PairingConfig.kt`, `PairingManager.kt`, `QrBitmap.kt`, `QrPayload.kt`
- `app/src/main/java/com/arkiv/player/ui/pairing/ConnectionSheet.kt`, `QrScannerScreen.kt`
- `app/src/main/java/com/arkiv/player/ui/tv/TvPairingScreen.kt`
- `app/src/main/java/com/arkiv/player/remote/` completo (17 archivos): `BarSource.kt`, `CloudTransport.kt`, `ExtrapolatedClock.kt`, `LanTransport.kt`, `NowPlayingCodec.kt`, `NowPlayingCoordinator.kt`, `NowPlayingModels.kt`, `NowPlayingPublisher.kt`, `OptimisticOverlay.kt`, `RemoteController.kt`, `RemoteModels.kt`, `RemoteTransport.kt`, `SeqTracker.kt`, `TransportCommand.kt`, `TransportRouter.kt`, `TvDeviceSelector.kt`, `TvNowPlayingRepository.kt`, `TvTarget.kt`
- `app/src/main/java/com/arkiv/player/ui/remote/` completo: `BarCommands.kt`, `MiniPlayerBar.kt`, `NowPlayingScreen.kt`, `RemoteScreen.kt`
- `app/src/main/java/com/arkiv/player/sync/` completo: `SyncManager.kt`, `SyncMerge.kt`, `SyncSnapshot.kt`, `SyncStatus.kt`
- `app/src/main/java/com/arkiv/player/presence/PresenceManager.kt`
- `app/src/main/java/com/arkiv/player/tvservice/TvConnectionService.kt`, `TvKeepAliveWorker.kt`
- `app/src/main/java/com/arkiv/player/miniaturas/BajadorDeFrames.kt` (el resto de `miniaturas/` NO se toca)
- Data class `Adopcion` y método `adoptarAparato()` en `app/src/main/java/com/arkiv/player/data/gateway/CuentaApi.kt` (líneas ~22, ~271-281 — solo los llama `PairingManager`)
- Tests: `cloudsync/*Test.kt` (10), `pairing/*Test.kt` (7), `remote/*Test.kt` (12), `sync/SubnetHostsTest.kt`, `SyncLiveFavoritesTest.kt`, `SyncMergeTest.kt`, `SyncSnapshotTest.kt`, `TvPresenceTest.kt`, `miniaturas/BajadorDeFramesTest.kt`, `pocketbase/DeviceAuthManagerAplicarAccountIdAdoptadoTest.kt`, `DeviceAuthManagerReconciliacionAccountIdTest.kt` (confirmar antes de borrar estos dos que de verdad solo cubren `accountId` adoptado vía pareo y no algo que el login mínimo siga necesitando).

**No borrar** (casteo DLNA/UPnP genérico, sin acoplamiento con lo de arriba): `dlna/DlnaController.kt`, `dlna/DlnaProxyServer.kt`, `ui/player/PlayerDlna.kt`.

**Files — Modify:**
- `app/build.gradle.kts`: quitar líneas 147-152 (`zxing`, `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, `mlkit:barcode-scanning` — usadas solo para QR de pareo).
- `app/src/main/java/com/arkiv/player/MainActivity.kt`: quitar líneas ~87-88 (`TvConnectionService.start`/`TvKeepAliveWorker.schedule`); en las líneas ~144-148, la rama TV construye `TvPantallaDeEntrada(graph.pairing, ...)` — pasa a construirse sin el parámetro de pareo (ver siguiente ítem).
- `app/src/main/java/com/arkiv/player/ui/tv/TvPantallaDeEntrada.kt`: rediseñar de 3 pestañas (`DESCARGA, PAREO, LOGIN`, línea ~51) a 2 (`DESCARGA, LOGIN`) — quitar el enum `PAREO`, el composable/tab de pareo, el import de `pairing/` (líneas ~39-40), el parámetro de constructor que recibía `PairingManager` (línea ~74). Mantener `registrar()` (línea ~267) intacto por ahora — se borra en Task 7.
- `app/src/main/java/com/arkiv/player/data/AppGraph.kt`: quitar `SyncManager` (~328), `PairingManager` (~584), `RemoteController` (~597), `CloudSyncManager` (~606), `NowPlayingPublisher` (~705), `NowPlayingCoordinator` (~717).
- `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`: quitar `cloudSync.syncNow()` (~307), `QrScannerScreen` (~560), lo que quede de `remote/PlayKind.TORRENT` si sobrevivió (~651-654, si Task 2 no lo cubrió del todo), `ConnectionSheet` (~691).
- `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`: quitar `graph.syncManager.syncNow()`/`.status` (~400, ~403), `graph.syncManager.syncNow()` + `graph.cloudSync.syncNow()` (~613-614), y el comentario de la línea ~286.
- `app/src/main/java/com/arkiv/player/ui/live/RecentLiveChannels.kt`: no requiere cambio de código (la tabla Room sigue funcionando 100% local) — solo confirmar al correr que no importa nada de `cloudsync/` directamente.

- [ ] **Step 1: Borrar los archivos listados arriba.**

- [ ] **Step 2: Rediseñar `TvPantallaDeEntrada.kt`** a 2 pestañas.

- [ ] **Step 3: Arreglar el resto de consumidores** listados arriba.

- [ ] **Step 4: Quitar las dependencias de QR/cámara de `build.gradle.kts`.**

- [ ] **Step 5: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 6: Correr tests** — `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 7: Smoke test en dispositivo (celular Y TV)** — confirmar login, entrar a Home, biblioteca local, reproducir Magis. Confirmar que en TV la pantalla de entrada muestra solo 2 pestañas y no hay ningún botón de pareo/control remoto visible en ninguna pantalla.

- [ ] **Step 8: Commit**

```bash
command git add -A
command git commit -m "chore(light): borrar cloud-sync, pareo QR, control remoto TV↔celu y descubrimiento LAN"
```

---

### Task 6: Simplificar búsqueda a solo-Magis

**Files — Modify:**
- `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt`: borrar `buscarPorFrase()` (líneas 287-321) y las data classes `FraseInterpretada` (40-48), `GatewayObraDeFrase` (51-57), `BusquedaPorFrase` (59-62). Mantener `search()` (103-131) tal cual — sigue sirviendo para Magis. **NO TOCAR `trivia()` (~línea 266, justo antes de `buscarPorFrase`)** — es la feature de "dato curioso" del reproductor; se mantiene llamando al gateway indefinidamente, es una excepción permanente a la regla "cero servidor" del branch (decisión explícita del usuario). Confirmar con `grep -n "fun trivia"` antes de borrar el bloque de frase para no arrastrarla por estar físicamente cerca.
- `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt`:
  - Borrar `buscarPorFrase()` (213-226) y el estado `_frase/frase/_loadingFrase/fraseJob` (196, 199-203).
  - En `search()` (248-317): mantener `tmdbJob`/`animeJob` (277-288); borrar `torrentJob`/`archiveJob` (290-315) y el estado `_directResults`/`directResults`/`_loadingDirect`/`loadingDirect` si quedaron sin otro uso.
  - En `runSourceSearch()` (380-636): dejar solo el bloque gateway-Magis (449-508), cambiando la línea 465 de `sources = if (gatewayCubreTodo) "torrent,web,archive,magis,ditu" else "magis,ditu"` a `sources = "magis"` fijo, y borrando toda la rama `gatewayCubreTodo`/`apagarSpinner` para fuentes que ya no existen. Borrar el bloque torrent (510-535), el bloque web/mirror (536-605), el bloque archive/biblioteca (606-634).
  - Borrar `processNow()` (642-662) y su estado `_processingNow`/`_processNowMessage` (177-181) — es 100% torrent/web (`torrentSearchApi.refreshTitle`), sin equivalente en Magis.
- `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt`: confirmar que la sealed class `PlaySource` quedó solo con `Magis` (las demás variantes ya se borraron en tasks 2-4; si alguna sigue por un descuido, borrarla ahora).

**Files — Delete:**
- Test: `app/src/test/java/com/arkiv/player/ui/search/FraseUiTest.kt`, `OrdenarTorrentsTest.kt`

**Files — Modify (tests):**
- `app/src/test/java/com/arkiv/player/ui/search/SourceTabTest.kt`, `CardContextTest.kt`, `SinRepetidosTest.kt`, `HandoffRouteTest.kt`, `CardDeTextoLibreTest.kt` — adaptar los casos que asumían múltiples fuentes a que solo exista Magis.

- [ ] **Step 1: Borrar el código de `ArkivApiClient.kt`** listado arriba.

- [ ] **Step 2: Reescribir `SearchViewModel.kt`** siguiendo las líneas de arriba, dejando `runSourceSearch()` con `sources = "magis"` fijo.

- [ ] **Step 3: Borrar `FraseUiTest.kt` y `OrdenarTorrentsTest.kt`; adaptar el resto de tests de `ui/search/`.**

- [ ] **Step 4: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 5: Correr tests** — `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 6: Smoke test en dispositivo** — buscar un título de Magis por texto, confirmar que aparece y reproduce. Confirmar que no queda ningún selector de fuente (torrent/web/archive/ditu) en la UI de búsqueda.

- [ ] **Step 7: Commit**

```bash
command git add -A
command git commit -m "refactor(light): simplificar busqueda a solo Magis, sacar busqueda por frase y multi-fuente"
```

---

### Task 7: Sacar licencias/registro

**Files — Modify:**
- `app/src/main/java/com/arkiv/player/data/gateway/CuentaApi.kt`: borrar `registrar()` (232-239), la data class `Registro` (15), los errores `LicenciaNoVigente` (62), `LicenciaInvalida` (71), `EmailEnUso` (74), `DeviceYaRegistrado` (92) y sus ramas en `desde()` (121, 124, 125, 129). Mantener `altaAparato()` (214-230), `entrar()` (253-269), `listarAparatos()`/`sacarAparato()` (283-303).
- `app/src/main/java/com/arkiv/player/pocketbase/AccountManager.kt`: borrar el wrapper `registrar()` (línea ~166) y cualquier estado asociado exclusivo a registro.
- `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt`: quitar la llamada a `account.registrar(...)` (línea ~112) y el formulario de registro (dejar solo login).
- `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsCuenta.kt`: quitar `account.registrar(...)` (línea ~131) y su formulario.
- `app/src/main/java/com/arkiv/player/ui/tv/TvPantallaDeEntrada.kt`: quitar `account.registrar(...)` (línea ~267) del tab LOGIN, dejando solo el formulario de login.
- `app/src/main/java/com/arkiv/player/ui/entrada/PantallaDeEntrada.kt`, `EntradaViewModel.kt`: quitar el formulario/estado de registro si está mezclado con login (confirmar leyendo el archivo primero — el inventario no llegó a auditar esto en detalle).
- `app/src/main/java/com/arkiv/player/ui/entrada/MascaraDeLicencia.kt`: borrar el archivo completo (máscara de formato del código de licencia, sin uso fuera de registro) y su test `MascaraDeLicenciaTest.kt`.

**Files — Delete:**
- Test: `app/src/test/java/com/arkiv/player/pocketbase/AccountManagerRegistroTest.kt`

**Files — Modify (tests):**
- `app/src/test/java/com/arkiv/player/data/gateway/CuentaApiTest.kt`, `pocketbase/AccountManagerTest.kt`, `ui/entrada/EntradaViewModelTest.kt`, `ui/tv/TvPantallaDeEntradaTest.kt` — quitar los casos de registro, dejar los de login.

- [ ] **Step 1: Editar `CuentaApi.kt`** quitando lo listado arriba.

- [ ] **Step 2: Editar `AccountManager.kt`, `AccountSection.kt`, `TvSettingsCuenta.kt`, `TvPantallaDeEntrada.kt`, `PantallaDeEntrada.kt`, `EntradaViewModel.kt`** quitando el flujo de registro.

- [ ] **Step 3: Borrar `MascaraDeLicencia.kt` y su test.**

- [ ] **Step 4: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 5: Correr tests** — `./gradlew :app:testDebugUnitTest`, adaptando los listados.

- [ ] **Step 6: Smoke test en dispositivo** — confirmar que login sigue funcionando con una cuenta existente, y que no aparece ningún botón/formulario de "crear cuenta" o campo de licencia en ninguna pantalla (celular y TV).

- [ ] **Step 7: Commit**

```bash
command git add -A
command git commit -m "chore(light): sacar registro/licencias, dejar solo login"
```

---

### Task 8: Sacar NUC offline

**Files — Delete:**
- `app/src/main/java/com/arkiv/player/data/offline/ArkivOfflineApi.kt`, `NucDownloadCheckWorker.kt`, `NucDownloads.kt`, `NucJobEvents.kt`, `PlaybackPreferenceStore.kt`
- `app/src/main/java/com/arkiv/player/ui/offline/` completo
- `app/src/main/java/com/arkiv/player/ui/downloads/` completo
- Tests: `data/offline/ArkivOfflineApiTest.kt`, `NucJobEventsTest.kt`, `PlaybackPreferenceStoreTest.kt`

**Files — Modify:**
- `app/build.gradle.kts`: quitar `buildConfigField("String", "NUC_API_KEY", ...)` (línea ~30) y el `readEnv("NUC_API_KEY")` asociado.
- `app/src/main/java/com/arkiv/player/data/AppGraph.kt`: quitar `NucJobEvents`/`arkivOfflineApi` (líneas ~444, ~451) y cualquier wiring de `PlaybackPreferenceStore`.
- Cualquier pantalla que enlace a `ui/offline`/`ui/downloads` (buscar con `grep -rl "ui.offline\|ui.downloads" app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`) — quitar la ruta y el botón de navegación.

- [ ] **Step 1: Borrar los archivos listados arriba.**

- [ ] **Step 2: Arreglar `build.gradle.kts`, `AppGraph.kt` y las rutas de navegación.**

- [ ] **Step 3: Compilar** — `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 4: Correr tests** — `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 5: Commit**

```bash
command git add -A
command git commit -m "chore(light): sacar NUC offline (descargas locales al NUC)"
```

---

### Task 9: Verificación final de punto de control

**Files:** ninguno nuevo — esta tarea es de verificación, no de código.

- [ ] **Step 1: Compilación completa** — `./gradlew :app:assembleDebug`. Esperado: build verde, sin warnings de símbolos no resueltos.

- [ ] **Step 2: Confirmar que no quedó código muerto** — `grep -rn "TorrentEngine\|MirrorApiClient\|WebResolverApi\|ArchiveApi\|CaracolScreen\|DituExoPlayer\|PairingManager\|CloudSyncManager\|licencias\|Registro(" app/src/main/java` debe devolver **cero** resultados (fuera de comentarios históricos en archivos que ya no existen, lo cual es imposible si de verdad se borraron).

- [ ] **Step 3: Instalar en dispositivo (celular) y correr el checklist del spec** (`docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md`, sección "Criterio de éxito"): login, vínculo de Magis, catálogo, búsqueda, reproducción VOD, canal en vivo, biblioteca/progreso/miniaturas locales sin sync.

- [ ] **Step 4: Repetir el Step 3 en Fire TV / Android TV** — pantalla de entrada de 2 pestañas, mismo checklist.

- [ ] **Step 5: Medir el tamaño del APK** (`ls -la app/build/outputs/apk/debug/`) y compararlo contra un build de `main` en el mismo commit base, para tener el número real de cuánto se redujo (informativo, no bloqueante).

- [ ] **Step 6: Commit final (si el Step 2 encontró algo para limpiar)**

```bash
command git add -A
command git commit -m "chore(light): limpieza final tras verificacion de punto de control"
```
