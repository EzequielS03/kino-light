# Reproductor unificado sobre VLC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unificar los dos reproductores (archive.org / torrent) en una sola pantalla que use libVLC como motor, conservando notificación, segundo plano, playlist/autoplay, marcadores intro/outro y caché en disco.

**Architecture:** Se escribe un `VlcPlayer : SimpleBasePlayer` que envuelve libVLC y se enchufa en el `PlaybackService` (`MediaSessionService`) existente, de modo que media3 sigue dando gratis la sesión/notificación/segundo plano/playlist. La caché de archive.org se resuelve con un proxy HTTP local cacheador (`ArchiveCacheProxy`) porque VLC no usa el `CacheDataSource` de media3. Una sola `PlayerScreen` renderiza `VLCVideoLayout` y maneja el transporte por el `MediaController`.

**Tech Stack:** Kotlin, Jetpack Compose, androidx.media3 1.5.1 (session/cast/common `SimpleBasePlayer`), org.videolan.android:libvlc-all 3.6.0, libtorrent4j, JUnit.

## Global Constraints

- **Commits con identidad lordmacu:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. Nunca coautoría de Claude en los mensajes. Verificar antes del primer commit: `git config user.name` / `git config user.email`.
- **media3 fijo en 1.5.1** (ya en `app/build.gradle`). `SimpleBasePlayer` es `@androidx.media3.common.util.UnstableApi` → anotar las clases que lo usan.
- **libVLC 3.6.0** (`org.videolan.android:libvlc-all:3.6.0`, ya presente).
- **Idioma:** comentarios y textos de UI en español rioplatense, siguiendo el estilo del código existente.
- **Ruteo de fuente:** un `episodeId` que empieza con `torrent:` es fuente torrent; cualquier otro es archive.org. (Hoy en `ArkivRoot.kt:118` y `ArkivTvRoot.kt:41`.)
- **Correr tests JVM:** `./gradlew :app:testDebugUnitTest`. Compilar: `./gradlew :app:assembleDebug`.
- **Instalar en dispositivos** (verificación manual): celular Samsung por ADB WiFi y Fire TV Stick por ADB de red — ver memorias `arkiv-adb-wifi-celular` y `arkiv-adb-firestick`.

---

## File Structure

**Nuevos:**
- `app/src/main/java/com/arkiv/player/playback/VlcPlaybackState.kt` — lógica pura de mapeo de eventos VLC → estado media3 (testeable en JVM).
- `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt` — `SimpleBasePlayer` que envuelve libVLC.
- `app/src/main/java/com/arkiv/player/playback/ArchiveCacheProxy.kt` — server HTTP local con Range + caché LRU en disco.
- `app/src/main/java/com/arkiv/player/playback/DiskLruCache.kt` — caché LRU en disco por URL (usada por el proxy).
- `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt` — modelo de fuente + tag de `MediaItem` (archive/torrent + markers).
- `app/src/test/java/com/arkiv/player/playback/VlcPlaybackStateTest.kt`
- `app/src/test/java/com/arkiv/player/playback/DiskLruCacheTest.kt`
- `app/src/test/java/com/arkiv/player/playback/RangeHeaderTest.kt`
- `app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt`

**Modificados:**
- `app/src/main/java/com/arkiv/player/playback/PlaybackService.kt` — usa `VlcPlayer` en vez de `ExoPlayer`.
- `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` — arma playlist para ambas fuentes; resuelve torrent.
- `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` — pantalla unificada (render VLC + controles + tracks + overlays).
- `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` — colapsa rutas `torrent_player`/`player`.
- `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` — idem para TV.
- `app/src/main/java/com/arkiv/player/AppGraph.kt` — expone `archiveCacheProxy` si hace falta como singleton.

**Eliminados (Task 8):**
- `app/src/main/java/com/arkiv/player/ui/torrent/TorrentPlayerScreen.kt`

---

## Task 1: Mapeo puro de estado VLC → media3 (TDD)

Aísla la lógica de "qué estado media3 corresponde a cada evento de VLC" en una unidad pura, testeable sin Android ni VLC nativo. `VlcPlayer` (Task 2) la consume.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/VlcPlaybackState.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/VlcPlaybackStateTest.kt`

**Interfaces:**
- Produces:
  - `enum class VlcEvent { Buffering, Playing, Paused, Stopped, EndReached, Error }`
  - `object VlcPlaybackState { fun mediaPlaybackState(event: VlcEvent, bufferingPercent: Float): Int }` donde el `Int` es `androidx.media3.common.Player.STATE_IDLE/BUFFERING/READY/ENDED`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class VlcPlaybackStateTest {
    @Test fun buffering_below_100_is_buffering() {
        assertEquals(Player.STATE_BUFFERING, VlcPlaybackState.mediaPlaybackState(VlcEvent.Buffering, 40f))
    }

    @Test fun buffering_at_100_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Buffering, 100f))
    }

    @Test fun playing_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Playing, 0f))
    }

    @Test fun paused_is_ready() {
        assertEquals(Player.STATE_READY, VlcPlaybackState.mediaPlaybackState(VlcEvent.Paused, 0f))
    }

    @Test fun end_reached_is_ended() {
        assertEquals(Player.STATE_ENDED, VlcPlaybackState.mediaPlaybackState(VlcEvent.EndReached, 0f))
    }

    @Test fun stopped_is_idle() {
        assertEquals(Player.STATE_IDLE, VlcPlaybackState.mediaPlaybackState(VlcEvent.Stopped, 0f))
    }

    @Test fun error_is_idle() {
        assertEquals(Player.STATE_IDLE, VlcPlaybackState.mediaPlaybackState(VlcEvent.Error, 0f))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.VlcPlaybackStateTest"`
Expected: FAIL — `VlcPlaybackState` / `VlcEvent` no existen (unresolved reference).

- [ ] **Step 3: Implementar el mínimo**

```kotlin
package com.arkiv.player.playback

import androidx.media3.common.Player

/** Evento de reproducción de libVLC normalizado (independiente del tipo nativo de VLC). */
enum class VlcEvent { Buffering, Playing, Paused, Stopped, EndReached, Error }

/** Traduce eventos de VLC al modelo de estado de media3. Pura: testeable sin Android/VLC. */
object VlcPlaybackState {
    fun mediaPlaybackState(event: VlcEvent, bufferingPercent: Float): Int = when (event) {
        VlcEvent.Buffering -> if (bufferingPercent >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
        VlcEvent.Playing, VlcEvent.Paused -> Player.STATE_READY
        VlcEvent.EndReached -> Player.STATE_ENDED
        VlcEvent.Stopped, VlcEvent.Error -> Player.STATE_IDLE
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.VlcPlaybackStateTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/VlcPlaybackState.kt \
        app/src/test/java/com/arkiv/player/playback/VlcPlaybackStateTest.kt
git commit -m "feat(player): mapeo puro de eventos VLC a estado media3"
```

---

## Task 2: `VlcPlayer : SimpleBasePlayer` (motor VLC detrás de la interfaz Player)

Envuelve `LibVLC` + `MediaPlayer` y expone la interfaz `Player` de media3 vía `SimpleBasePlayer`, incluyendo playlist. Se valida por dispositivo en Task 3 (no hay test JVM porver: requiere VLC nativo).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt`

**Interfaces:**
- Consumes: `VlcEvent`, `VlcPlaybackState.mediaPlaybackState(...)` (Task 1); `PlayerSource` tag (Task 6, opcional en este task se usa solo `MediaItem.localConfiguration.uri`).
- Produces:
  - `class VlcPlayer(context: Context, looper: Looper) : SimpleBasePlayer(looper)`
  - `fun attachVideo(layout: org.videolan.libvlc.util.VLCVideoLayout)`
  - `fun detachVideo()`
  - `fun vlcAudioTracks(): List<Pair<Int, String>>` / `fun vlcSpuTracks(): List<Pair<Int, String>>`
  - `fun currentAudioTrack(): Int` / `fun currentSpuTrack(): Int`
  - `fun setVlcAudioTrack(id: Int)` / `fun setVlcSpuTrack(id: Int)`
  - `fun addSubtitleSlave(uri: Uri)`

- [ ] **Step 1: Crear la clase con estado y ciclo de vida de VLC**

```kotlin
package com.arkiv.player.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.SimpleBasePlayer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Motor libVLC expuesto como Player de media3. Aloja un único MediaPlayer y traduce su estado
 * al modelo de SimpleBasePlayer, para que la MediaSession/notificación/segundo plano/playlist
 * de media3 funcionen sin cambios. El render (VLCVideoLayout) lo aporta la UI.
 */
@UnstableApi
class VlcPlayer(context: Context, looper: Looper) : SimpleBasePlayer(looper) {

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1500",
            "--file-caching=1500",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
        ),
    )
    private val mediaPlayer = MediaPlayer(libVlc)

    private var items: List<MediaItem> = emptyList()
    private var currentIndex = 0
    private var playWhenReady = false
    private var event: VlcEvent = VlcEvent.Stopped
    private var buffering = 0f

    init {
        mediaPlayer.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Buffering -> { event = VlcEvent.Buffering; buffering = e.buffering }
                MediaPlayer.Event.Playing -> { event = VlcEvent.Playing }
                MediaPlayer.Event.Paused -> { event = VlcEvent.Paused }
                MediaPlayer.Event.Stopped -> { event = VlcEvent.Stopped }
                MediaPlayer.Event.EncounteredError -> { event = VlcEvent.Error }
                MediaPlayer.Event.EndReached -> onEndReached()
                else -> return@setEventListener
            }
            invalidateState()
        }
    }
    // (resto en los siguientes steps)
}
```

- [ ] **Step 2: Implementar `getState()` (mapeo a `SimpleBasePlayer.State`)**

Agregar dentro de la clase:

```kotlin
    override fun getState(): State {
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item-$i" })
                .setMediaItem(item)
                .setDurationUs(if (mediaPlayer.length > 0) mediaPlayer.length * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val playbackState = if (items.isEmpty()) Player.STATE_IDLE
            else VlcPlaybackState.mediaPlaybackState(event, buffering)
        return State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs { mediaPlayer.time.coerceAtLeast(0) }
            .build()
    }

    private companion object {
        val AVAILABLE_COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE,
                Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_TRACKS,
            )
            .build()
    }
```

- [ ] **Step 3: Implementar los `handle*` (comandos)**

```kotlin
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        loadCurrent(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) mediaPlayer.play() else mediaPlayer.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && mediaPlayer.media == null) loadCurrent(0L)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != currentIndex) {
            currentIndex = mediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else if (positionMs != C.TIME_UNSET) {
            mediaPlayer.time = positionMs
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        runCatching { mediaPlayer.stop() }
        event = VlcEvent.Stopped
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
        return Futures.immediateVoidFuture()
    }

    private fun loadCurrent(startPositionMs: Long) {
        val item = items.getOrNull(currentIndex) ?: return
        val uri = item.localConfiguration?.uri ?: return
        val media = Media(libVlc, uri).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1500")
            if (startPositionMs > 0) addOption(":start-time=${startPositionMs / 1000}")
        }
        mediaPlayer.media = media
        media.release()
        if (playWhenReady) mediaPlayer.play()
    }

    private fun onEndReached() {
        // Autoplay: pasar al siguiente ítem de la playlist, o terminar.
        if (currentIndex < items.size - 1) {
            currentIndex++
            loadCurrent(0L)
        } else {
            event = VlcEvent.EndReached
        }
    }
```

- [ ] **Step 4: Render + selección de pistas (API para la UI)**

```kotlin
    fun attachVideo(layout: VLCVideoLayout) {
        runCatching { mediaPlayer.attachViews(layout, null, true, false) }
    }
    fun detachVideo() { runCatching { mediaPlayer.detachViews() } }

    fun vlcAudioTracks(): List<Pair<Int, String>> =
        runCatching { mediaPlayer.audioTracks?.map { it.id to it.name } }.getOrNull().orEmpty()
    fun vlcSpuTracks(): List<Pair<Int, String>> =
        runCatching { mediaPlayer.spuTracks?.map { it.id to it.name } }.getOrNull().orEmpty()
    fun currentAudioTrack(): Int = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
    fun currentSpuTrack(): Int = runCatching { mediaPlayer.spuTrack }.getOrDefault(-1)
    fun setVlcAudioTrack(id: Int) { runCatching { mediaPlayer.setAudioTrack(id) } }
    fun setVlcSpuTrack(id: Int) { runCatching { mediaPlayer.setSpuTrack(id) } }
    fun addSubtitleSlave(uri: Uri) {
        runCatching { mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, uri, true) }
    }
```

- [ ] **Step 5: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Si `SimpleBasePlayer` marca métodos abstractos faltantes, implementarlos con `Futures.immediateVoidFuture()` y ajustar `AVAILABLE_COMMANDS`. Iterar hasta compilar.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt
git commit -m "feat(player): VlcPlayer (SimpleBasePlayer) que envuelve libVLC"
```

---

## Task 3: Swap del motor en `PlaybackService` + verificación en dispositivo

Cambia el `ExoPlayer` del service por `VlcPlayer`, sin tocar la `MediaSession`. Verifica que archive.org reproduce con VLC usando la UI actual (aún sin unificar), de-riesgando la pieza central antes de seguir.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/PlaybackService.kt:56-83`

**Interfaces:**
- Consumes: `VlcPlayer(context, looper)` (Task 2).

- [ ] **Step 1: Reemplazar la construcción del player**

En `PlaybackService.onCreate()`, reemplazar el bloque `val player = ExoPlayer.Builder(this)...build()` (y el cache/httpFactory/cacheFactory que solo servían a ExoPlayer) por:

```kotlin
        val player = VlcPlayer(this, mainLooper)
```

Quitar los imports ahora sin uso (`ExoPlayer`, `CacheDataSource`, `SimpleCache`, `DefaultHttpDataSource`, `DefaultDataSource`, `DefaultMediaSourceFactory`, `StandaloneDatabaseProvider`, `LeastRecentlyUsedCacheEvictor`, `AudioAttributes`, `C`). Mantener `MediaSession`, `MediaSessionService`, `NowPlaying`, `ACTION_OPEN_PLAYER`, `sessionActivity`. El `mediaSession = MediaSession.Builder(this, player)...build()` no cambia.

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (sin referencias colgadas a los símbolos de ExoPlayer eliminados).

- [ ] **Step 3: Verificación manual en dispositivo (archive.org)**

Instalar en el celular (`arkiv-adb-wifi-celular`) y reproducir un episodio de archive.org con la UI actual. Verificar:
- El video reproduce (motor VLC).
- Aparece la notificación de media3 con carátula y controles (play/pausa/seek).
- Play/pausa desde la notificación y la pantalla de bloqueo funcionan.
- Al minimizar la app el audio sigue (segundo plano).
- Next/prev y autoplay al siguiente episodio funcionan (playlist).

Documentar en el commit cualquier ajuste necesario en `VlcPlayer` (p.ej. `invalidateState()` faltante en algún evento, posición que no avanza → revisar `setContentPositionMs`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/PlaybackService.kt \
        app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt
git commit -m "feat(player): PlaybackService usa VlcPlayer como motor"
```

---

## Task 4: Caché en disco (TDD) — `DiskLruCache` + parseo de Range

Piezas puras/testeables del proxy: un cache LRU en disco por clave (URL) y el parseo del header `Range`.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/DiskLruCache.kt`
- Create: `app/src/main/java/com/arkiv/player/playback/RangeHeader.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/DiskLruCacheTest.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/RangeHeaderTest.kt`

**Interfaces:**
- Produces:
  - `data class ByteRange(val start: Long, val end: Long?)` + `object RangeHeader { fun parse(header: String?): ByteRange? }`
  - `class DiskLruCache(dir: File, maxBytes: Long)` con `fun keyFor(url: String): String`, `fun file(key: String): File`, `fun touch(key: String)`, `fun evictIfNeeded()`, `fun sizeBytes(): Long`.

- [ ] **Step 1: Test de `RangeHeader` que falla**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeHeaderTest {
    @Test fun parses_open_ended() {
        val r = RangeHeader.parse("bytes=1000-")!!
        assertEquals(1000L, r.start); assertNull(r.end)
    }
    @Test fun parses_closed() {
        val r = RangeHeader.parse("bytes=0-1023")!!
        assertEquals(0L, r.start); assertEquals(1023L, r.end)
    }
    @Test fun null_when_absent() { assertNull(RangeHeader.parse(null)) }
    @Test fun null_when_malformed() { assertNull(RangeHeader.parse("kilobytes=1-2")) }
}
```

- [ ] **Step 2: Correr y ver fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.RangeHeaderTest"`
Expected: FAIL (unresolved reference `RangeHeader`).

- [ ] **Step 3: Implementar `RangeHeader`**

```kotlin
package com.arkiv.player.playback

/** Rango de bytes pedido por el cliente HTTP. `end` null = hasta el final. */
data class ByteRange(val start: Long, val end: Long?)

object RangeHeader {
    private val RE = Regex("""bytes=(\d+)-(\d*)""")
    fun parse(header: String?): ByteRange? {
        val m = header?.let { RE.matchEntire(it.trim()) } ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull()
        return ByteRange(start, end)
    }
}
```

- [ ] **Step 4: Correr y ver pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.RangeHeaderTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Test de `DiskLruCache` que falla**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskLruCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun key_is_stable_and_filesystem_safe() {
        val c = DiskLruCache(tmp.newFolder(), 1024)
        val k1 = c.keyFor("https://archive.org/a/b c.mkv")
        val k2 = c.keyFor("https://archive.org/a/b c.mkv")
        assertEquals(k1, k2)
        assertTrue(k1.all { it.isLetterOrDigit() })
    }

    @Test fun evicts_oldest_when_over_limit() {
        val c = DiskLruCache(tmp.newFolder(), 100)
        val a = c.file(c.keyFor("u/a")).apply { writeBytes(ByteArray(60)) }
        c.touch(c.keyFor("u/a"))
        Thread.sleep(5)
        val b = c.file(c.keyFor("u/b")).apply { writeBytes(ByteArray(60)) }
        c.touch(c.keyFor("u/b"))
        c.evictIfNeeded()               // 120 > 100 → borra el más viejo (a)
        assertTrue(!a.exists())
        assertTrue(b.exists())
    }
}
```

- [ ] **Step 6: Correr y ver fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.DiskLruCacheTest"`
Expected: FAIL (unresolved reference `DiskLruCache`).

- [ ] **Step 7: Implementar `DiskLruCache`**

```kotlin
package com.arkiv.player.playback

import java.io.File
import java.security.MessageDigest

/** Caché LRU en disco por clave de URL. La "recencia" es el lastModified del archivo. */
class DiskLruCache(private val dir: File, private val maxBytes: Long) {
    init { dir.mkdirs() }

    fun keyFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun file(key: String): File = File(dir, key)

    fun touch(key: String) { file(key).setLastModified(System.currentTimeMillis()) }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun evictIfNeeded() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        val byOldest = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in byOldest) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
```

Nota: el test usa `System.currentTimeMillis()` vía `setLastModified`/`Thread.sleep`, no requiere reloj inyectable.

- [ ] **Step 8: Correr y ver pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.DiskLruCacheTest"`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/DiskLruCache.kt \
        app/src/main/java/com/arkiv/player/playback/RangeHeader.kt \
        app/src/test/java/com/arkiv/player/playback/DiskLruCacheTest.kt \
        app/src/test/java/com/arkiv/player/playback/RangeHeaderTest.kt
git commit -m "feat(player): caché LRU en disco + parseo de Range para el proxy"
```

---

## Task 5: `ArchiveCacheProxy` — server HTTP local cacheador

Server local (patrón de `TorrentStreamServer`) al que VLC le pega para archive.org: baja de la URL real, cachea a disco (LRU 512 MB, Task 4) y sirve con soporte de `Range`. Verificación por dispositivo (server con sockets → no unit test JVM).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/ArchiveCacheProxy.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (exponer singleton)

**Interfaces:**
- Consumes: `DiskLruCache`, `RangeHeader`/`ByteRange` (Task 4).
- Produces:
  - `class ArchiveCacheProxy(cacheDir: File, maxBytes: Long = 512L*1024*1024)` con `fun start(): Int` (devuelve puerto), `fun stop()`, `fun proxyUrl(originUrl: String): String` (arma `http://127.0.0.1:<port>/s?u=<urlencoded>`).
- En `AppGraph`: `val archiveCacheProxy: ArchiveCacheProxy by lazy { ArchiveCacheProxy(File(appContext.cacheDir, "archive-cache")) }`.

- [ ] **Step 1: Implementar el proxy**

```kotlin
package com.arkiv.player.playback

import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Proxy HTTP local con caché en disco para archive.org. VLC pega a proxyUrl(origin); el proxy
 * baja el origen entero a un archivo de caché (una sola vez) y sirve tramos con Range desde disco.
 * Reemplaza al CacheDataSource de media3 que VLC no puede usar.
 */
class ArchiveCacheProxy(cacheDir: File, maxBytes: Long = 512L * 1024 * 1024) {
    private val cache = DiskLruCache(cacheDir, maxBytes)
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile private var running = false

    fun start(): Int {
        if (running) return port
        running = true
        Thread {
            while (running && !server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return port
    }

    fun stop() { running = false; runCatching { server.close() } }

    fun proxyUrl(originUrl: String): String =
        "http://127.0.0.1:$port/s?u=${URLEncoder.encode(originUrl, "UTF-8")}"

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val header = StringBuilder()
            val one = ByteArray(1)
            while (input.read(one) == 1) {
                header.append(one[0].toInt().toChar())
                if (header.endsWith("\r\n\r\n") || header.length > 8192) break
            }
            val lines = header.toString().split("\r\n")
            val reqLine = lines.firstOrNull().orEmpty()
            val path = reqLine.split(' ').getOrNull(1).orEmpty()
            val origin = path.substringAfter("u=", "").let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            } ?: return
            val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
            val range = RangeHeader.parse(rangeHeader)

            val key = cache.keyFor(origin)
            val file = cache.file(key)
            ensureCached(origin, file)
            cache.touch(key)
            cache.evictIfNeeded()

            val total = file.length()
            val start = range?.start ?: 0L
            val end = (range?.end ?: (total - 1)).coerceAtMost(total - 1)
            val len = (end - start + 1).coerceAtLeast(0)
            val out = s.getOutputStream()
            val status = if (range != null) "206 Partial Content" else "200 OK"
            val respHeaders = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $len\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }
            out.write(respHeaders.toByteArray())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = len
                while (remaining > 0) {
                    val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
            out.flush()
        }
    }

    /** Baja el origen completo al archivo de caché si aún no está (descarga una sola vez). */
    private fun ensureCached(origin: String, file: File) {
        if (file.exists() && file.length() > 0) return
        val tmp = File(file.parentFile, file.name + ".part")
        runCatching {
            (URL(origin).openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Arkiv/0.1 (personal)")
                connectTimeout = 15000; readTimeout = 20000
            }.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            tmp.renameTo(file)
        }.onFailure { tmp.delete() }
    }
}
```

Nota de límite conocido (documentar, no arreglar ahora): `ensureCached` baja el archivo completo antes de servir el primer byte. Para archive.org de uso personal es aceptable; si molesta la espera inicial, una iteración futura puede servir en passthrough mientras cachea. Registrar como TODO en el código.

- [ ] **Step 2: Exponer el singleton en `AppGraph`**

En `AppGraph.kt`, junto a los otros `by lazy`, agregar:

```kotlin
    val archiveCacheProxy: com.arkiv.player.playback.ArchiveCacheProxy by lazy {
        com.arkiv.player.playback.ArchiveCacheProxy(java.io.File(appContext.cacheDir, "archive-cache"))
    }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/ArchiveCacheProxy.kt \
        app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(player): proxy HTTP local cacheador para archive.org"
```

---

## Task 6: Modelo de fuente + unificación de `PlayerViewModel` (TDD para el tag)

Introduce `PlayerSource` (archive/torrent) como tag del `MediaItem` y unifica el `PlayerViewModel` para armar `PlaylistData` en ambos casos, moviendo la resolución de torrent (hoy en `TorrentPlayerScreen`) al ViewModel.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`

**Interfaces:**
- Produces:
  - `enum class SourceKind { ARCHIVE, TORRENT }`
  - `data class PlayerSourceTag(val kind: SourceKind, val openingStartMs: Long?, val openingEndMs: Long?, val endingStartMs: Long?, val castUrl: String?)`
  - `object PlayerSource { fun kindFor(episodeId: String): SourceKind }` (regla: prefijo `torrent:` → TORRENT).
  - `fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder` (guarda el tag en `setTag`).
- Consume (VM): `graph.archiveCacheProxy` (Task 5), `graph.torrentEngine`, `repo.torrentSourceForEpisode`.

- [ ] **Step 1: Test que falla**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTest {
    @Test fun torrent_prefix_is_torrent() {
        assertEquals(SourceKind.TORRENT, PlayerSource.kindFor("torrent:abc::1"))
    }
    @Test fun other_is_archive() {
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("someitem::3"))
    }
}
```

- [ ] **Step 2: Correr y ver fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.PlayerSourceTest"`
Expected: FAIL (unresolved reference).

- [ ] **Step 3: Implementar `PlayerSource.kt`**

```kotlin
package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { ARCHIVE, TORRENT }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
)

object PlayerSource {
    fun kindFor(episodeId: String): SourceKind =
        if (episodeId.startsWith("torrent:")) SourceKind.TORRENT else SourceKind.ARCHIVE
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)
```

- [ ] **Step 4: Correr y ver pasar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.playback.PlayerSourceTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Unificar `PlayerViewModel`**

En `PlayerViewModel.kt`:
1. Cambiar el constructor para recibir lo necesario del grafo: agregar `torrentEngine: TorrentEngine`, `archiveCacheProxy: ArchiveCacheProxy`. (Actualizar el `viewModelFactory` en `PlayerScreen.kt` en Task 7.)
2. Extender `load(episodeId)` para ramificar por `PlayerSource.kindFor(episodeId)`:
   - **ARCHIVE**: como hoy, pero envolviendo `mediaUrl` con `archiveCacheProxy.proxyUrl(...)` cuando sea URL http de archive (no para archivos locales `file://`), y arrancando el proxy (`archiveCacheProxy.start()`).
   - **TORRENT**: resolver `repo.torrentSourceForEpisode(episodeId)` (mover acá la lógica de `TorrentPlayerScreen.kt:103-134`), arrancar el stream (`torrentEngine.startStream(...)` / `startMagnetStream(...)` + esperar `streamReadyUrl()`), y emitir un `PlaylistData` de **un** ítem con la URL del server local.
3. En `buildData(...)` (archive) o su equivalente torrent, setear el `PlayerSourceTag` en cada `PlayerData` para propagarlo luego al `MediaItem`.

Añadir un campo `kind: SourceKind` a `PlayerData` (usado por la UI para mostrar overlay de torrent). Firma nueva:

```kotlin
data class PlayerData(
    val episodeId: String,
    val itemId: String,
    val title: String,
    val subtitle: String,
    val mediaUrl: String,
    val castUrl: String?,
    val artworkUrl: String,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val kind: SourceKind,          // NUEVO
)
```

Añadir la función de resolución torrent dentro del VM:

```kotlin
    private suspend fun loadTorrent(episodeId: String) {
        val src = repo.torrentSourceForEpisode(episodeId)
        val (title, url) = resolveTorrentUrl(episodeId, src) ?: run {
            _error.value = "No se pudo iniciar el streaming"; return
        }
        val item = PlayerData(
            episodeId = episodeId, itemId = episodeId.substringBefore("::"),
            title = title, subtitle = "", mediaUrl = url, castUrl = null,
            artworkUrl = "", openingStartMs = null, openingEndMs = null,
            endingStartMs = null, kind = SourceKind.TORRENT,
        )
        val startPos = repo.getPlayback(episodeId)?.positionMs ?: 0L
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
    }
```

(La implementación de `resolveTorrentUrl` traslada el `when (src)` de `TorrentPlayerScreen.kt:103-134` — Bytes/Magnet, hint de episodio, espera de `streamReadyUrl()` — al VM. Añadir un `_error: MutableStateFlow<String?>` expuesto para que la pantalla muestre errores.)

- [ ] **Step 6: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`PlayerScreen.kt` puede romper por la firma de `PlayerData`/constructor del VM; se arregla en Task 7. Si el subagente ejecuta tasks aislados, dejar `PlayerScreen.kt` compilando con un TODO temporal o hacer Task 6+7 juntos.)

- [ ] **Step 7: Correr tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (todos, incluyendo `PlayerSourceTest`).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/PlayerSource.kt \
        app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt \
        app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt
git commit -m "feat(player): fuente unificada (archive/torrent) en PlayerViewModel"
```

---

## Task 7: `PlayerScreen` unificada (render VLC + controles + tracks + overlays)

Fusiona ambas UIs en una sola pantalla: render `VLCVideoLayout`, transporte por `MediaController`, controles Compose custom (los del torrent), editor de marcadores + saltar intro/outro + zoom (de archive), diálogo de audio/subtítulos (del torrent, vía la API de tracks de `VlcPlayer`), overlay de descarga (solo torrent), cast + DLNA.

**Files:**
- Modify (reescritura mayor): `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `VlcPlayer.attachVideo/detachVideo/vlcAudioTracks/vlcSpuTracks/setVlcAudioTrack/setVlcSpuTrack/addSubtitleSlave/currentAudioTrack/currentSpuTrack` (Task 2); `PlayerViewModel` unificado + `PlayerData.kind` (Task 6); `rememberMediaController()` (ya existe en el archivo).
- Nota: el acceso a la API VLC-específica requiere el `VlcPlayer` real, no solo el `MediaController`. Obtenerlo del grafo/servicio: exponer la instancia viva. **Decisión:** el `PlaybackService` guarda su `VlcPlayer` en un singleton accesible (`object PlaybackEngine { @Volatile var vlc: VlcPlayer? = null }`), seteado en `onCreate`/`onDestroy`. La pantalla usa el `MediaController` para transporte y `PlaybackEngine.vlc` para render + tracks.

- [ ] **Step 1: Exponer el `VlcPlayer` vivo desde el service**

En `PlaybackService.kt`: crear `object PlaybackEngine { @Volatile var vlc: VlcPlayer? = null }`, asignar `PlaybackEngine.vlc = player as VlcPlayer` tras crear el player, y `PlaybackEngine.vlc = null` en `onDestroy`.

- [ ] **Step 2: Reescribir `PlayerScreen` — esqueleto y render**

Reemplazar el `AndroidView` de `PlayerView` por `VLCVideoLayout`, atachándolo al `VlcPlayer` vivo; conservar el `rememberMediaController()` para transporte/estado. Estructura:

```kotlin
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(episodeId: String, onBack: () -> Unit, onOpenEpisodes: () -> Unit, isTv: Boolean = false) {
    val controller = rememberMediaController()
    if (controller == null) { /* spinner como hoy */ ; return }
    PlayerContent(episodeId, onBack, onOpenEpisodes, controller, isTv)
}
```

En `PlayerContent`:
- Instanciar el VM con la factory nueva (pasando `graph.torrentEngine`, `graph.archiveCacheProxy`).
- `val vlc = PlaybackEngine.vlc` para render/tracks (puede ser null si el service aún no está listo → mostrar spinner).
- El `AndroidView` crea `VLCVideoLayout(ctx)` y llama `vlc?.attachVideo(layout)`; en `onDispose`, `vlc?.detachVideo()`.
- Transporte (play/pausa/seek/next/prev), posición, buffering, playlist: **todo por `controller`** (idéntico patrón a como hoy `PlayerContent` usa el `Player`).
- La carga de la playlist en el `controller` (setMediaItems con `PlayerData` → `MediaItem` + tag) reutiliza el helper `localMediaItems(...)`, extendido para setear `setPlayerSourceTag(...)`.

- [ ] **Step 3: Portar controles + markers + tracks + overlay**

- **Controles Compose** (transporte central -10s/play/+10s, barra inferior con slider, fade auto-ocultar): portar de `TorrentPlayerScreen` (`TorrentVideoPlayer`, líneas 516-681), cableados a `controller` en vez de `mediaPlayer` de VLC.
- **Marcadores intro/outro + saltar intro/outro + zoom pinch + swipe-down**: conservar de la `PlayerScreen` actual (líneas 415-639), operando sobre `controller`.
- **Diálogo audio/subtítulos**: portar de `TorrentPlayerScreen` (`subPickerOpen`, 757-813), pero leyendo/seteando pistas vía `vlc.vlcAudioTracks()/vlcSpuTracks()/setVlcAudioTrack()/setVlcSpuTrack()/addSubtitleSlave()`. OpenSubtitles usa `graph.subtitleApi` como hoy.
- **Overlay de descarga** (peers/%/velocidad): mostrar solo si `currentData.kind == SourceKind.TORRENT`, con `graph.torrentEngine.streamStatus()` como hoy.
- **Cast + DLNA**: conservar de ambas pantallas (son equivalentes). Para torrent, URL LAN (`graph.torrentEngine.lanStreamUrl()`); para archive, `castUrl`/proxy.
- **TV D-pad**: unificar el `setOnKeyListener` (izq/der = seek, centro = play/pausa, arriba/menú = tracks o controles) sobre el `VLCVideoLayout`.

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verificación manual en dispositivo (ambas fuentes)**

Celular + Fire TV. Verificar en **archive.org** y en **torrent**:
- Reproduce, con controles, seek, play/pausa.
- Notificación + segundo plano + pantalla de bloqueo (ambas fuentes, ya que ambas van por VlcPlayer/MediaSession).
- Marcadores intro/outro y botones saltar (archive; en torrent no hay markers → no aparecen).
- Selección de audio/subtítulos embebidos + OpenSubtitles (ambas).
- Overlay de descarga solo en torrent.
- Cast Chromecast + DLNA (ambas).
- TV: D-pad (seek/play/menú de tracks).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt \
        app/src/main/java/com/arkiv/player/playback/PlaybackService.kt
git commit -m "feat(player): pantalla unificada VLC (archive + torrent) con controles, tracks y markers"
```

---

## Task 8: Unificar navegación + eliminar `TorrentPlayerScreen`

Colapsa las rutas `torrent_player/{episodeId}` y `player/{episodeId}` en una sola (`player/{episodeId}`, que ya maneja ambas fuentes por el prefijo del id), y borra la pantalla vieja.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt:118-119, 293-296`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt:41, 132-135`
- Delete: `app/src/main/java/com/arkiv/player/ui/torrent/TorrentPlayerScreen.kt`

- [ ] **Step 1: `ArkivRoot.kt` — un solo destino**

En la función que decide la ruta (línea 118-119), reemplazar por navegación única:

```kotlin
        navController.navigate("player/${Uri.encode(id)}")
```

Eliminar el bloque `composable("torrent_player/{episodeId}") { ... }` (293-296). El `composable("player/{episodeId}")` no cambia (ya sirve ambas fuentes).

- [ ] **Step 2: `ArkivTvRoot.kt` — un solo destino**

Reemplazar la línea 41 (`val route = if (id.startsWith("torrent:")) "torrent_player" else "player"`) por navegar siempre a `"player"`. Eliminar el `composable("torrent_player/{episodeId}") { ... }` (132-135).

- [ ] **Step 3: Borrar la pantalla vieja**

```bash
git rm app/src/main/java/com/arkiv/player/ui/torrent/TorrentPlayerScreen.kt
```

Quitar sus imports en `ArkivRoot.kt` (línea 72) y `ArkivTvRoot.kt`.

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (sin referencias a `TorrentPlayerScreen`).

- [ ] **Step 5: Verificación manual — entrada por ambos flujos**

Reproducir un ítem de archive.org y un ítem de torrent desde sus pantallas de detalle; confirmar que ambos abren la pantalla unificada y funcionan (celular + TV).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt \
        app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
git commit -m "refactor(player): una sola ruta de reproductor; elimina TorrentPlayerScreen"
```

---

## Task 9: Limpieza final + verificación completa

- [ ] **Step 1: Correr toda la suite de tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (todos, incluyendo los nuevos: VlcPlaybackState, DiskLruCache, RangeHeader, PlayerSource).

- [ ] **Step 2: Revisar imports/deps sin uso**

Buscar restos de ExoPlayer en `PlaybackService.kt` y de `media3-ui` `PlayerView`:

```bash
grep -rn "ExoPlayer\|PlayerView\|CacheDataSource\|SimpleCache" app/src/main --include="*.kt"
```

Si `PlayerView`/`R.layout.arkiv_player_view` ya no se referencia, se puede borrar el layout y (opcionalmente) quitar `media3-ui` de `app/build.gradle:90`. **No** quitar `media3-session` ni `media3-cast`. Si hay duda, dejar la dep y solo remover el uso.

- [ ] **Step 3: Build de release-debug limpio**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificación integral en dispositivo**

Checklist final (celular + Fire TV), archive + torrent:
- Reproducción, seek, next/prev, autoplay.
- Notificación, segundo plano, pantalla de bloqueo.
- Marcadores intro/outro (archive).
- Caché: hacer seek hacia atrás en archive y confirmar que no re-descarga (mirar que no reaparezca buffering largo).
- Audio/subtítulos embebidos + OpenSubtitles.
- Cast Chromecast + DLNA.
- Overlay de descarga (torrent).

- [ ] **Step 5: Commit final (si hubo limpieza)**

```bash
git add -A
git commit -m "chore(player): limpieza de restos de ExoPlayer tras unificación en VLC"
```

---

## Self-Review (cobertura del spec)

- **VlcPlayer dentro de media3** → Tasks 1-3. ✓
- **PlaybackService motor VLC** → Task 3. ✓
- **ArchiveCacheProxy (caché disco, Range, LRU)** → Tasks 4-5. ✓
- **PlayerScreen unificada (render VLC, controles, markers, tracks, overlays, cast/DLNA, TV)** → Task 7. ✓
- **Fuentes → MediaItem tag + ViewModel unificado (torrent movido al VM)** → Task 6. ✓
- **Navegación colapsada + borrar TorrentPlayerScreen** → Task 8. ✓
- **Tests** (VlcPlaybackState, DiskLruCache, RangeHeader, PlayerSource) → Tasks 1,4,6. ✓
- **Migración/limpieza deps** → Task 9. ✓
- **Playlist/autoplay, segundo plano+notificación** → gratis vía media3 (Tasks 2-3), verificado en 3/7/9. ✓
- **Marcadores intro/outro** → Task 7 (portados de la PlayerScreen actual). ✓
- **Caché en disco** → Tasks 4-5, verificada en 9. ✓
