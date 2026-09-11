# Arkiv Light — Remove VLC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Play downloaded files on an ExoPlayer hosted in `PlaybackService` (background playback, notification and MediaSession kept) and remove libVLC completely: `VlcPlayer`, `CastTranscoder` and the `libvlc-all` dependency.

**Architecture:** `PlaybackService` swaps its `VlcPlayer` for an `ExoPlayer` behind the same `MediaSession`. `PlayerScreen` keeps local playback behind `controller` (so the phone's "keep playing in the background" rule is unchanged), renders it through a `TextureView`, and reuses the existing ExoPlayer paths for tracks, speed, volume, zoom, first frame and frame capture. The cast transcoder goes; local files cast directly.

**Tech Stack:** Kotlin, Jetpack Compose, media3 1.5.1 (exoplayer, session, datasource, ui), JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-11-arkiv-light-remove-vlc-design.md`

## Global Constraints

- No behavior change for Magis / live / Caracol (in-screen ExoPlayers, proxies), the TV UI, or Room (no schema change).
- Local playback keeps: background playback + media notification on the phone, resume, seek, audio/subtitle track choice, speed, volume, zoom, first-frame detection, frame capture, error reporting, cast via `castUrl`.
- Language: identifiers, comments, KDoc, logs and commit messages in English; user-visible text in Spanish.
- Gradle only in the foreground: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug` before each commit (`--rerun` is mandatory).
- `grep`/`cat` are intercepted by a hook that can truncate output: use `command grep`, `command cat` or Read. The graft index is stale for this branch — don't trust it.
- Subagents never use `adb` and never dispatch subagents.
- Commits: `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "…"`, NO footer (no `Co-Authored-By`, `Claude-Session`, "Generated with"), explicit `git add`/`git rm` paths, `git status --short` empty at the end of each task.
- Line numbers below are approximate (measured 2026-09-11): find code by content.

---

### Task 1: Service hosts an ExoPlayer (+ capability spike)

**Files:** Modify `playback/PlaybackService.kt`; possibly a new small `playback/LocalExoPlayer.kt` (builder) and a test.

- [ ] **Step 1 — Spike (write the result in the report before changing anything):** confirm in media3 1.5.1 sources/docs whether an in-process `MediaController` connected to a `MediaSession` hosting an `ExoPlayer` proxies: `setVideoTextureView`/`clearVideoTextureView`, `trackSelectionParameters` (+ `TrackSelectionOverride`), `currentTracks`, `volume`, `setPlaybackSpeed`, and delivers `onRenderedFirstFrame` / `onTracksChanged` to listeners. Decide: (a) controller-only, or (b) keep a process-wide handle exposing the service's `ExoPlayer` (successor of `PlaybackEngine.vlc`) for whatever isn't proxied. Record the decision.
- [ ] **Step 2:** Build the service player: `ExoPlayer.Builder(this)` with `DefaultMediaSourceFactory(DefaultDataSource.Factory(this))` (so `file://` resolves), `DefaultRenderersFactory(this).setEnableDecoderFallback(true)`, audio attributes `USAGE_MEDIA`/`AUDIO_CONTENT_TYPE_MOVIE` with `handleAudioFocus = true`, `setHandleAudioBecomingNoisy(true)`. Put the pure/config part where it can be tested if practical.
- [ ] **Step 3:** In `onCreate`, replace `VlcPlayer(this, mainLooper)` with it; build the same `MediaSession` with the same `MediaItemResolverCallback`. Remove the VLC-only wiring (`precalentarSalto`, the `subtitlePrefs` → `langPrefs` re-apply job and its cancel in `onDestroy`). Keep `onTaskRemoved`, `onDestroy`, `releaseNetworkResources()` and `isCasting()` unchanged. Expose the handle per Step 1.
- [ ] **Step 4:** Compile will break where `PlaybackEngine.vlc`/`VlcPlayer` are consumed (`PlayerScreen`): adapt only as much as needed to compile in this task **without** deleting `VlcPlayer.kt` yet (Task 5 deletes). If the screen can't compile without Task 2, merge Tasks 1 and 2 into one dispatch and say so.
- [ ] **Step 5:** `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`; commit `refactor(playback): host an ExoPlayer in PlaybackService instead of VlcPlayer`.

### Task 2: PlayerScreen renders and controls local playback through ExoPlayer

**Files:** Modify `ui/player/PlayerScreen.kt`, `ui/player/PlayerPistas.kt`, `ui/player/PlayerGestos.kt`, `playback/VideoAttachPolicy.kt` (if its API is VLC-shaped), and tests.

- [ ] **Step 1:** Render: replace the `VLCVideoLayout` `AndroidView` (~l.1800-1890) with a `TextureView` bound to the session player (`setVideoTextureView` via controller or handle, per Task 1). Attach/detach on `ON_START`/`ON_STOP` through `VideoAttachPolicy` without stopping audio. Aspect/zoom through the same TextureView transform the in-screen players use (`ajustarAlAspecto`, `zoomParaExo`).
- [ ] **Step 2:** Tracks: route `EstadoDePistas` through its Exo path for local playback (`setExoPlayer`/equivalent with the controller or handle); auto-pick the preferred audio/subtitle language at start (`autoElegirIdiomaExo`). Remove the VLC branch (`vlcAudioTracks`/`vlcSpuTracks`/`setVlc*Track`) and the portal-subtitle labeling for the VLC path (`clasificarSpuConFuente`, `addSubtitleSlave` LaunchedEffect) if it has no remaining non-VLC use (it is inert for local files — verify).
- [ ] **Step 3:** Gestures: speed and volume through the existing Exo branches of `EstadoDeGestos`; zoom through the TextureView transform (replace `vlc.setScale`).
- [ ] **Step 4:** First frame: `sinPrimeraImagen` for local uses `onRenderedFirstFrame` (like `exoYaPintoAlgo`) instead of `vlc.esperandoPrimeraImagen()`. Frame capture: pass the local `TextureView` to `FrameCapturer`. Drop `vlc.cortesEnVivo` reads (LIVE never plays on this path).
- [ ] **Step 5:** Background rule: `activePlayer` must still resolve to `controller` for local files so `PausaAlSalir` keeps SEGUIR on the phone. Add/keep a test that pins `alIrseAlFondo(esTv=false, esExoPlayer=false, casteando=false, enVivo=false) == SEGUIR` and a comment at the call site explaining that the service-hosted player is the `controller` one.
- [ ] **Step 6:** Decoder watchdog for local playback: if the position advances with audio but no frame has rendered within a bounded time (reuse the `EsperaDePrimeraImagen`-style constants if suitable), reload once preferring software decoding (log it). Pure decision + unit test.
- [ ] **Step 7:** Remove the `vlc: VlcPlayer` parameter and every remaining `vlc.` use in the screen (the inventory lists ~23). `./gradlew …`; commit `refactor(player): play local files through the service ExoPlayer`.

### Task 3: Remove the cast transcoder

**Files:** Delete `cast/CastTranscoder.kt`, `cast/CastSoutChain.kt`, `cast/StreamReadiness.kt` (+ their tests); modify `AppGraph.kt` (castSession wiring ~l.502-536), `ui/player/PlayerScreen.kt` (`castRequestFor` transcode branches ~l.712-798, the re-check `LaunchedEffect` ~l.840-880, the "is transcoding" seek branch ~l.1712-1722), `cast/CastAudioSupport.kt`.

- [ ] **Step 1:** Replace the libVLC-fourcc audio gate with a `Format`-based one (`sampleMimeType`, `channelCount` from the current audio track). Test first: AAC ≤2ch decodable; AC-3 / DTS / >2ch AAC not.
- [ ] **Step 2:** `castRequestFor` always sends the direct URL (`castUrl` for local files; existing URLs for Magis/live/Caracol unchanged). If the gate says the audio isn't Chromecast-decodable, still cast and show a Spanish notice (e.g. "Este audio podría no sonar en el Chromecast").
- [ ] **Step 3:** Delete the transcoder files, their `AppGraph` wiring and tests; remove `baseOffsetMs`-style transcode offsets if only the transcoder set them (verify `CastProgress` stays correct). `./gradlew …`; commit `refactor(cast): drop the libVLC audio transcoder; cast local files directly`.

### Task 4: Delete VlcPlayer and VLC-only helpers; drop libVLC

**Files:** Delete `playback/VlcPlayer.kt`, `playback/VlcPlaybackState.kt`, `playback/VoutTracker.kt` and any helper whose ONLY callers were VLC (gate each with `command grep -rnw <name> app/src`): candidates `UnknownLengthPolicy`, `VentanaDeArchivo`, `AvisoDeSalto`, `OpcionesDeIdioma` (VLC `:audio-language` hint), `RachaSinVideo`/`DeteccionDeEstancamiento` (keep if still used), `AudioTrackFormat`. Keep anything the Magis/live proxies still use (`PoliticaOrigen`, `CachingDeRed`, `AguanteDeBuffering`, `TsDurationProbe`, `ArchiveCacheProxy.precalentarSalto` if referenced). Modify `app/build.gradle.kts` (remove `org.videolan.android:libvlc-all:3.6.0` and its comment ~l.151-153), the branch `CLAUDE.md` (VLC paragraph).

- [ ] **Step 1:** For every candidate, record the grep evidence; delete only zero-reference ones, with their VLC-only tests.
- [ ] **Step 2:** Remove the dependency; `command grep -rn "org.videolan\|VLCVideoLayout\|libvlc\|VlcPlayer" app/src` → nothing.
- [ ] **Step 3:** Rewrite the branch `CLAUDE.md` VLC paragraph (Spanish, it's the owner's rule doc): VLC was removed; local files play on ExoPlayer inside `PlaybackService`.
- [ ] **Step 4:** `./gradlew …`; report the APK size (`app/build/outputs/apk/debug/app-debug.apk`) and the `lib/` contents (`unzip -l`); commit `build: remove libVLC`.

### Task 5: Device verification (controller)

- [ ] Phone (S24+), only with the owner's OK and never while he's using it; guard every tap with `mCurrentFocus` containing `com.arkiv.player.light/`; use only `~/Library/Android/sdk/platform-tools/adb`: install; no 16 KB warning; play the downloaded movie in airplane mode; switch audio track; resume; background keeps playing + notification controls; seek/speed/zoom; frame thumbnail saved; APK size.
- [ ] TV (KALLEY): Magis VOD + trivia, Magis live pause/resume, Caracol VOD + live — unchanged; never press "Actualizar ahora".
- [ ] Chromecast (if available): cast the local file, with sound.
