# Arkiv Light — Remove VLC (local playback on ExoPlayer)

Branch `light-magis` (permanent fork; never merged to `main`). Rule: everything runs inside the app, no own server.

## Context

The app plays everything with media3/ExoPlayer except one thing: files already downloaded to the phone. Those go through `VlcPlayer` (libVLC wrapped in a media3 `SimpleBasePlayer`), hosted by `PlaybackService` (a `MediaSessionService`) and driven from `PlayerScreen` through a `MediaController` (`controller`) plus a raw `VlcPlayer` handle (`PlaybackEngine.vlc`) for rendering, tracks, speed, zoom and volume. libVLC is also used by `CastTranscoder` to re-encode audio for the Chromecast.

Why remove it (measured 2026-09-11):
- libVLC's native libraries are ~85 MB of the 118 MB APK (arm64 `libvlc.so` 43.2 MB + armeabi-v7a ~40 MB).
- Android 16 (the owner's S24+, SDK 36) warns that `libvlc.so`, `libvlcjni.so` and `libc++_shared.so` are not 16 KB-page aligned; on 16 KB-page devices they would not load.
- The phone log showed a native leak: `VLCObject (org.videolan.libvlc.Media) finalized but not natively released`.
- Downloaded files are Magis MPEG-TS (or MP4) with HEVC video and AAC-LC stereo audio (ffprobe of a real download: HEVC Main 1280x534 + two AAC-LC 48 kHz 2ch tracks) — the same format `MagisExoPlayer` already plays when streaming.

The owner chose the **complete** option: remove VLC entirely while keeping every behavior local playback has today — including **playing in the background with the media notification**.

## What VLC does today (inventory, verified in code)

- VLC plays **only** completed on-disk Magis downloads (`PlayerViewModel.load()` → `localLibrary.fileFor()` → `loadLocal()` with `mediaUrl = file://…`, `kind = SourceKind.LOCAL`, `castUrl` from `LocalFileServer`). UNKNOWN ids never reach it; LIVE/MAGIS/DITU play on in-screen ExoPlayers. The TV never plays local files (no download UI on the TV), so **local playback is phone-only**.
- Downloads carry no subtitle sidecars (the download stack fetches one file); `loadLocal()` sets no `webExtras`, so the VLC path's portal-subtitle labeling (`clasificarSpuConFuente`, `addSubtitleSlave`) is already inert for local files.
- Dead for local (MAGIS-streaming era, gated by `SourceKind.MAGIS` or never reached): window seeks / unknown-length probing inside `VlcPlayer` (`UnknownLengthPolicy`, `VentanaDeArchivo`, avformat forcing), seek pre-warm (`precalentarSalto` / `AvisoDeSalto` — requires proxy URLs), `cortesEnVivo` (LIVE only), `VlcPlaybackState`, `VoutTracker`.
- Live behaviors the local ExoPlayer must keep: rendering; background playback + notification + MediaSession transport (`PausaAlSalir`: on the phone the `controller` path keeps playing when the app goes to the background, in-screen ExoPlayers pause); resume position; seek; audio/subtitle track choice by language preference; speed; volume; zoom; first-frame detection (`esperandoPrimeraImagen`); frame capture for thumbnails (`FrameCapturer` needs a `TextureView`); error reporting; cast from a local file (`castUrl` via `LocalFileServer`).
- Engine-agnostic and kept as-is: `PlaybackService.MediaItemResolverCallback.onAddMediaItems`, `onTaskRemoved`/`onDestroy`/`releaseNetworkResources()` (they stop the Magis/live proxies `archiveCacheProxy`/`liveHlsProxy`/`liveController`), `TrackLanguage`/`TrackSelector`, `SubtitleDecision`, `FrameCapturer`, `LocalFileServer`, the proxies.
- ExoPlayer twins already exist for most live behaviors: `EstadoDePistas` has a full Exo path (`setExoPlayer`, `autoElegirIdiomaExo`, `TrackSelectionOverride`); `EstadoDeGestos` has Exo branches for speed and volume and a TextureView-transform zoom (`zoomParaExo` + `ajustarAlAspecto`); first frame via `onRenderedFirstFrame`; `MagisExoPlayer` exposes its `TextureView` for `FrameCapturer`.

## Design

1. **`PlaybackService` hosts an `ExoPlayer`** instead of `VlcPlayer`, still behind the same `MediaSession` (so the notification, background playback and `MediaController` transport keep working). It uses a `DefaultDataSource.Factory` (so `file://` resolves; the in-screen players use `DefaultHttpDataSource`), proper audio attributes with audio focus, `handleAudioBecomingNoisy`, and `DefaultRenderersFactory.setEnableDecoderFallback(true)`. The VLC-only wiring in `onCreate` (`precalentarSalto`, the `langPrefs` re-apply) goes; `MediaItemResolverCallback`, `onTaskRemoved`, `onDestroy`, `releaseNetworkResources()` stay unchanged.
2. **`PlayerScreen` keeps local playback behind `controller`.** `activePlayer` still resolves to `controller` for local files, so `PausaAlSalir`'s `jugador !== controller` test keeps "local keeps playing in the background on the phone" without change. The render surface becomes a `TextureView` bound to the session's player (`setVideoTextureView`) instead of `VLCVideoLayout`; attach/detach on foreground/background (`VideoAttachPolicy`) moves to the same `TextureView`. Tracks, speed, volume and zoom go through the existing Exo paths of `EstadoDePistas` / `EstadoDeGestos`; first frame through `onRenderedFirstFrame`; frame capture through the `TextureView`.
   - **Early spike (first task):** check whether `MediaController` (same process) proxies everything needed — `setVideoTextureView`, `trackSelectionParameters`, `volume`, `setPlaybackSpeed`, `onRenderedFirstFrame`, `currentTracks`. If yes, the raw-instance handle disappears and the controller is passed where the Exo paths expect a `Player`/`ExoPlayer`. If something isn't proxied, keep a process-wide handle that exposes the service's `ExoPlayer` (the successor of `PlaybackEngine.vlc`), used only for that.
3. **Decoder robustness.** `VlcPlayer` rescues "the hardware decoder accepts the HEVC stream but paints nothing" by retrying in software. On ExoPlayer: decoder fallback enabled, plus a no-first-frame watchdog for local playback (if audio advances but no frame renders within a bounded time, reload once preferring a non-hardware decoder, logged). Verified on the phone.
4. **Cast.** Remove `CastTranscoder`, `CastSoutChain`, `StreamReadiness` and their `AppGraph` / `PlayerScreen` wiring (the transcode branches of `castRequestFor`, the periodic re-check, the "is transcoding" seek branch). A local file casts directly through `castUrl` (AAC is Chromecast-decodable). Keep a small audio gate based on ExoPlayer's track `Format` (`sampleMimeType`, `channelCount`) replacing the libVLC fourcc one: if a future download's audio isn't Chromecast-decodable, cast anyway and show a Spanish notice that it may play without sound (no transcoding exists any more).
5. **Language preference re-apply.** Local playback applies the preferred audio/subtitle language at start (same as Magis/Caracol today via `autoElegirIdiomaExo`). The VLC-only "change Settings mid-playback and the current item re-applies it" behavior is dropped (downloads have no labeled subtitle tracks to switch; audio can still be switched by hand in the tracks panel).
6. **Delete** `VlcPlayer`, `VlcPlaybackState`, `VoutTracker`, `AudioTrackFormat`, `CastTranscoder`, `CastSoutChain`, `StreamReadiness`, and every helper whose only callers were those (each deletion gated by a zero-reference check; helpers still used by the Magis/live proxies stay — e.g. `PoliticaOrigen`, `CachingDeRed`, `AguanteDeBuffering`, `TsDurationProbe` if still referenced), with their VLC-only tests. Remove `org.videolan.android:libvlc-all:3.6.0` and its stale comment from `app/build.gradle.kts`.
7. **Branch rules.** Update the VLC paragraph of the branch `CLAUDE.md` (it says VLC stays alive for local playback): VLC is removed; local files play on ExoPlayer in `PlaybackService`.

## Non-goals

- No change to the Magis / live / Caracol in-screen players, their proxies, or the TV UI.
- No new subtitle features for downloads.
- No Room schema change.

## Verification

- Unit tests: suite green; new pure pieces tested (e.g. the watchdog decision, the Format-based cast audio gate).
- Phone (S24+, with the owner's OK; never while he's using it): a downloaded movie plays in airplane mode; both audio tracks selectable; resume from the saved position; keeps playing in the background with the notification and its controls; seek, speed, zoom; frame thumbnail saved on pause/exit; no "16 KB" compatibility warning on launch; APK size reported (expected ~33 MB).
- TV (KALLEY): Magis VOD + trivia, Magis live pause/resume, Caracol VOD + live — unchanged.
- If a Chromecast is available: cast a local file (direct, with sound).

## Risks

- ExoPlayer has no built-in equivalent of VLC's specific HW→SW rescue; mitigated by decoder fallback + the watchdog; measured on the phone.
- `MediaController` might not proxy a capability; the spike decides, with the process-wide handle as fallback.
- Background behavior depends on `activePlayer` still resolving to `controller` for local files; a test pins `PausaAlSalir`'s decision and the implementation must not route local files to an in-screen player.
