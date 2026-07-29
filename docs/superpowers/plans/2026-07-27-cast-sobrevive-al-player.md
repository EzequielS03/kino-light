# El cast sobrevive al reproductor — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que salir del reproductor deje de terminar la sesión de Chromecast, y que estando casteando todo lo que se reproduzca vaya a la TV.

**Architecture:** El `CastPlayer` y su listener se mudan del composable a un `CastSessionManager` colgado del `AppGraph`, creado una vez y nunca liberado. La pantalla pasa a consumirlo, y la carga de reproducción se bifurca: casteando va al Chromecast (cebando además el player local en pausa), si no va al local como hoy.

**Tech Stack:** Kotlin, Jetpack Compose, media3 (`CastPlayer`, `MediaController`), Google Cast SDK.

**Spec:** `docs/superpowers/specs/2026-07-27-cast-sobrevive-al-player-design.md`

## Global Constraints

- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. **Nunca** `Co-Authored-By`.
- **Nunca `git add -A`.** Este repo trackea artefactos en `app/build/`, y **otra sesión de Claude edita `PlayerScreen.kt`**. `git add <archivo>` stagea el archivo ENTERO: revisar el diff línea por línea antes de commitear. El `git` de este shell está envuelto por `rtk`, que **trunca diffs largos** — usar `/usr/bin/git diff` para una revisión honesta. Si aparece algo ajeno, NO commitear: reportarlo.
- **Comando de compilación:** `./gradlew :app:assembleDebug` — debe pasar en cada tarea.
- **Suite existente:** `./gradlew :app:testDebugUnitTest` debe seguir verde.
- **Tests JVM puros**, sin Robolectric ni dependencias de Android, en `app/src/test/java/com/arkiv/player/...`.
- **Comentarios y textos de UI en español.**
- **`CastPlayer` y `CastContext` exigen el hilo principal.** Construirlos fuera de él revienta en runtime. Ver la Task 2, paso 4.
- **Los números de línea son de referencia y han corrido.** Ubicar cada edición por el texto que la rodea, no por el número. Leer el archivo antes de editarlo.

---

## Estructura

| Archivo | Responsabilidad |
|---|---|
| `cast/CastRequest.kt` (nuevo) | Qué mandarle al receptor, y cómo derivarlo de un `PlayerData`. Puro |
| `cast/CastSessionManager.kt` (nuevo) | Dueño del `CastPlayer`, del listener, del estado `casting` y del guardado de progreso |
| `AppGraph.kt` | Wiring, con la construcción forzada al hilo principal |
| `ui/player/PlayerScreen.kt` | Consume el manager; bifurca la carga; observa `casting` |

---

## Task 1: `CastRequest` y su constructor puro

La única pieza genuinamente aislable: derivar **qué URL y qué MIME** se le manda al receptor según la fuente. Es donde un error manda el capítulo al aparato equivocado o con el formato mentido.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/cast/CastRequest.kt`
- Test: `app/src/test/java/com/arkiv/player/cast/CastRequestBuilderTest.kt`

**Interfaces:**
- Produces: `data class CastRequest(uri, mimeType, episodeId, title, subtitle, artworkUrl, startPositionMs)`, `CastRequestBuilder.build(...): CastRequest?`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/cast/CastRequestBuilderTest.kt`:

```kotlin
package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRequestBuilderTest {

    @Test
    fun `torrent usa la url de la LAN y el mime real del stream`() {
        val r = CastRequestBuilder.build(
            episodeId = "torrent:abc::0", title = "Avatar", subtitle = "T1E1",
            artworkUrl = "https://p.jpg", mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
            isTorrent = true, lanUrl = "http://192.168.3.20:34045/video",
            lanMime = "video/x-matroska", startPositionMs = 3000,
        )!!
        assertEquals("http://192.168.3.20:34045/video", r.uri)
        assertEquals("video/x-matroska", r.mimeType)
        assertEquals(3000, r.startPositionMs)
    }

    @Test
    fun `torrent sin url de LAN no se puede castear`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "torrent:abc::0", title = "t", subtitle = "s", artworkUrl = "",
                mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
                isTorrent = true, lanUrl = null, lanMime = "video/mp4", startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `torrent sin mime conocido cae a mp4`() {
        val r = CastRequestBuilder.build(
            episodeId = "torrent:abc::0", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
            isTorrent = true, lanUrl = "http://192.168.3.20:1/video", lanMime = null,
            startPositionMs = 0,
        )!!
        assertEquals("video/mp4", r.mimeType)
    }

    @Test
    fun `archive prefiere castUrl sobre mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "Doc", subtitle = "", artworkUrl = "https://p.jpg",
            mediaUrl = "https://archive.org/x.mkv", castUrl = "https://archive.org/x.mp4",
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 5000,
        )!!
        assertEquals("https://archive.org/x.mp4", r.uri)
        assertEquals("video/mp4", r.mimeType)
        assertEquals("Doc", r.title)
        assertEquals(5000, r.startPositionMs)
    }

    @Test
    fun `archive sin castUrl usa mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.mp4", castUrl = null,
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.mp4", r.uri)
    }

    @Test
    fun `sin ninguna url no se puede castear`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
                mediaUrl = "", castUrl = null,
                isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `una posicion negativa se lleva a cero`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = -500,
        )!!
        assertEquals(0, r.startPositionMs)
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.cast.CastRequestBuilderTest"`
Expected: FAIL — `CastRequest` no existe.

- [ ] **Step 3: Implementar**

Crear `app/src/main/java/com/arkiv/player/cast/CastRequest.kt`:

```kotlin
package com.arkiv.player.cast

/** Lo que se le manda al receptor de Chromecast. */
data class CastRequest(
    val uri: String,
    val mimeType: String,
    val episodeId: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val startPositionMs: Long,
)

/**
 * Deriva la petición de cast según la fuente. Pura: testeable sin Android.
 *
 * Torrent: la URL es la del servidor HTTP del PROPIO celu en la LAN, porque el receptor tiene que
 * poder descargarla; y se manda el MIME real del stream, no uno inventado.
 * Archive/web: se prefiere `castUrl` (mp4 h.264, compatible con el receptor) sobre `mediaUrl`.
 */
object CastRequestBuilder {

    private const val MIME_MP4 = "video/mp4"

    @Suppress("LongParameterList")
    fun build(
        episodeId: String,
        title: String,
        subtitle: String,
        artworkUrl: String,
        mediaUrl: String,
        castUrl: String?,
        isTorrent: Boolean,
        lanUrl: String?,
        lanMime: String?,
        startPositionMs: Long,
    ): CastRequest? {
        val uri = if (isTorrent) lanUrl else castUrl?.takeIf { it.isNotBlank() } ?: mediaUrl
        if (uri.isNullOrBlank()) return null
        return CastRequest(
            uri = uri,
            mimeType = if (isTorrent) (lanMime ?: MIME_MP4) else MIME_MP4,
            episodeId = episodeId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            startPositionMs = startPositionMs.coerceAtLeast(0),
        )
    }
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.cast.CastRequestBuilderTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Revisar el diff y commitear**

Run: `/usr/bin/git diff --stat`; confirmar que solo aparecen los dos archivos nuevos.

```bash
git add app/src/main/java/com/arkiv/player/cast/CastRequest.kt app/src/test/java/com/arkiv/player/cast/CastRequestBuilderTest.kt
git commit -m "feat(cast): CastRequest y su constructor puro por fuente"
```

---

## Task 2: `CastSessionManager` y su wiring

Se crea y se cablea, pero **todavía no lo consume nadie**: la pantalla sigue con su propio `CastPlayer` hasta la Task 3. Es a propósito, para que cada tarea compile y se revise sola.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `CastRequest`, `CastRequestBuilder` (Task 1)
- Produces: `CastSessionManager` con `player: CastPlayer`, `casting: StateFlow<Boolean>`, `setMedia(request: CastRequest)`; `AppGraph.castSession: CastSessionManager?`

- [ ] **Step 1: Implementar el manager**

Crear `app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt`:

```kotlin
package com.arkiv.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arkiv.player.data.ArkivRepository
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dueño del [CastPlayer] y de la sesión de Chromecast, con vida de aplicación.
 *
 * Vive acá y no en el composable del reproductor porque `CastPlayer.release()` llama a
 * `SessionManager.endCurrentSession(false)` (verificado en el bytecode de media3-cast 1.5.1): al
 * liberarlo se corta el casteo. Mientras estuvo dentro de la pantalla, salir del reproductor
 * mataba la sesión.
 *
 * Como el CastPlayer se construye UNA sola vez, el listener recibe todas las sesiones y desaparece
 * de paso un problema viejo: media3 no re-dispara `onCastSessionAvailable` para una sesión que ya
 * estaba abierta al construirlo.
 */
class CastSessionManager(
    castContext: CastContext,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
) {
    val player: CastPlayer = CastPlayer(castContext)

    private val _casting = MutableStateFlow(false)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    /** Lo último que se pidió castear; se (re)carga en cuanto haya sesión. */
    @Volatile private var pending: CastRequest? = null

    init {
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _casting.value = true
                pending?.let { scope.launch { load(it) } }
            }

            override fun onCastSessionUnavailable() {
                _casting.value = false
            }
        })
        // El único caso que el listener NO cubre: arrancar la app con una sesión ya viva (se mató y
        // se reabrió casteando). Ocurrió antes de que existiéramos, así que se adopta a mano.
        if (runCatching { castContext.sessionManager.currentCastSession?.isConnected }.getOrNull() == true) {
            _casting.value = true
        }
        startProgressLoop()
    }

    /** Pide castear esto. Si ya hay sesión se carga ya; si no, queda pendiente para cuando la haya. */
    fun setMedia(request: CastRequest) {
        pending = request
        if (_casting.value) scope.launch { load(request) }
    }

    private suspend fun load(r: CastRequest) = withContext(Dispatchers.Main) {
        android.util.Log.i(TAG, "cargando en el receptor · mime=${r.mimeType} · desde=${r.startPositionMs}ms · ${r.uri}")
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(r.uri)
                .setMimeType(r.mimeType)
                .setMediaId(r.episodeId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.subtitle)
                        .apply { if (r.artworkUrl.isNotBlank()) setArtworkUri(android.net.Uri.parse(r.artworkUrl)) }
                        .build(),
                )
                .build(),
            r.startPositionMs,
        )
        player.playWhenReady = true
        player.prepare()
    }

    /**
     * Persiste el progreso mientras se castea, INCLUSO con el reproductor cerrado. Sin esto, ver un
     * capítulo entero desde el home guardaría la posición solo hasta el instante en que se salió de
     * la pantalla: la misma pérdida silenciosa que el resto del feature vino a evitar.
     */
    private fun startProgressLoop() {
        scope.launch {
            while (true) {
                delay(PROGRESS_MS)
                if (!_casting.value) continue
                val epId = pending?.episodeId ?: continue
                val (pos, dur) = withContext(Dispatchers.Main) {
                    player.currentPosition to player.duration
                }
                if (dur > 0 && pos in 0 until dur) {
                    runCatching { repository.savePlayback(epId, pos, dur) }
                        .onFailure { android.util.Log.w(TAG, "no se pudo guardar el progreso: ${it.message}") }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ArkivCast"
        const val PROGRESS_MS = 5_000L
    }
}
```

- [ ] **Step 2: Cablearlo en `AppGraph`**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, junto al `castContext` existente (busca el comentario `/** CastContext de Chromecast, o null si Google Play Services no está disponible. */`), agregar debajo:

```kotlin
    /** Dueño del CastPlayer con vida de app: sin esto, salir del reproductor corta el casteo. */
    val castSession: com.arkiv.player.cast.CastSessionManager? by lazy {
        castContext?.let { com.arkiv.player.cast.CastSessionManager(it, repository, applicationScope) }
    }
```

- [ ] **Step 3: Forzar su construcción en el hilo principal**

`CastPlayer` y `CastContext` **exigen el hilo principal**: construirlos desde otro hilo revienta en runtime. El `by lazy` se resolvería en el primer hilo que lo toque, y el `init` del `AppGraph` lanza corrutinas en IO.

En el bloque `init` del `AppGraph`, **fuera** de cualquier `applicationScope.launch`, agregar:

```kotlin
        // CastPlayer/CastContext exigen el hilo principal. Forzamos su construcción ahí para que el
        // manager exista desde el arranque (y adopte una sesión ya viva) sin depender de quién lo
        // toque primero.
        android.os.Handler(android.os.Looper.getMainLooper()).post { castSession }
```

- [ ] **Step 4: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Revisar el diff y commitear**

Run: `/usr/bin/git diff -- app/src/main/java/com/arkiv/player/AppGraph.kt`

Confirmar que solo aparecen las dos adiciones de esta tarea.

```bash
git add app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(cast): CastSessionManager con vida de app (el cast deja de morir con la pantalla)"
```

---

## Task 3: La pantalla consume el manager

Acá desaparece la causa del bug: el `CastPlayer` propio de la pantalla y su `release()`.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `AppGraph.castSession`, `CastSessionManager.player`, `.casting`, `.setMedia`, `CastRequestBuilder.build` (Tasks 1-2)

- [ ] **Step 1: Reemplazar el `CastPlayer` propio y el estado `casting`**

Ubicar `val castPlayer = remember { castContext?.let { CastPlayer(it) } }` y la línea `var casting by remember { mutableStateOf(false) }` (están a pocas líneas una de otra). Reemplazar ambas por:

```kotlin
    // El CastPlayer vive en el AppGraph, no acá: liberarlo termina la sesión de Chromecast, así que
    // mientras fue de la pantalla, salir del reproductor mataba el casteo.
    val castSession = remember { graph.castSession }
    val castPlayer = castSession?.player
    // El `remember` del flujo de respaldo es necesario: sin él se crearía un MutableStateFlow nuevo
    // en cada recomposición y el colector se reiniciaría una y otra vez.
    val castingFlow = remember(castSession) {
        castSession?.casting ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val casting by castingFlow.collectAsStateWithLifecycle()
```

Verificar que `collectAsStateWithLifecycle` ya esté importado en el archivo; si no, agregar
`import androidx.lifecycle.compose.collectAsStateWithLifecycle`.

- [ ] **Step 2: Eliminar el `DisposableEffect` del listener**

Ubicar el `DisposableEffect(castPlayer)` precedido por el comentario que empieza `// Chromecast: el CastPlayer es independiente del VlcPlayer.` y **borrar el bloque entero**, incluido su comentario, su `SessionAvailabilityListener` (con `onCastSessionAvailable` y `onCastSessionUnavailable`), el `errorListener` de diagnóstico, y el `onDispose` con `cp.release()`.

Toda esa responsabilidad está ahora en `CastSessionManager`.

- [ ] **Step 3: Pausar y reanudar el local al cambiar `casting`**

Lo que hacía `onCastSessionAvailable`/`Unavailable` con el player local se mueve a un efecto de la pantalla. Agregar, justo después del bloque que acabás de borrar:

```kotlin
    // Lo que antes hacía el listener con el reproductor LOCAL. Al empezar a castear se pausa; al
    // terminar se adelanta hasta donde llegó el receptor antes de reanudar — si no, el sondeo
    // persiste la posición vieja encima de la buena en ≤5s.
    var casteabaAntes by remember { mutableStateOf(false) }
    LaunchedEffect(casting) {
        if (casting) {
            runCatching { controller.pause() }
        } else if (casteabaAntes) {
            val castPos = runCatching { castPlayer?.currentPosition ?: 0L }.getOrDefault(0L)
            if (castPos > 0L) runCatching { controller.seekTo(castPos) }
            runCatching { controller.play() }
        }
        casteabaAntes = casting
    }
```

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Si el compilador se queja de `cp` o `castContext` sin usar, eliminar lo que quedó huérfano del bloque borrado — pero **no** toques `castContext` si lo sigue usando el `MediaRouteButton`.

- [ ] **Step 5: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Revisar el diff y commitear**

Run: `/usr/bin/git diff -- app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

Confirmar que **no** aparece nada de la otra sesión.

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(cast): la pantalla usa el CastPlayer del AppGraph y ya no lo libera"
```

---

## Task 4: A dónde va la reproducción

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

**Interfaces:**
- Consumes: `CastRequestBuilder.build` (Task 1), `castSession.setMedia` (Task 2)

- [ ] **Step 1: Bifurcar la carga de la playlist**

Ubicar el `LaunchedEffect(playlist)` precedido por `// Carga inicial de la playlist en el controller`. Dentro, después de la línea `loaded = true` y de `positionMs = pl.startPositionMs`, y **antes** del `when { … }` que decide entre `sameEpisodePlaying` / `samePlaylist` / nuevo, insertar:

```kotlin
        if (casting && castSession != null) {
            val idx = pl.items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
            val data = pl.items.getOrNull(idx)
            val req = data?.let {
                com.arkiv.player.cast.CastRequestBuilder.build(
                    episodeId = it.episodeId,
                    title = it.title,
                    subtitle = it.subtitle,
                    artworkUrl = it.artworkUrl,
                    mediaUrl = it.mediaUrl,
                    castUrl = it.castUrl,
                    isTorrent = it.kind == SourceKind.TORRENT,
                    lanUrl = graph.torrentEngine.lanStreamUrl(),
                    lanMime = graph.torrentEngine.streamMime(),
                    startPositionMs = pl.startPositionMs,
                )
            }
            if (req == null) {
                android.util.Log.w("ArkivCast", "casteando pero no hay URL que mandarle al receptor")
            } else {
                android.util.Log.w("ArkivPlay", "rama=CAST → el capítulo va al Chromecast, el local queda cebado en pausa")
                // El local se carga IGUAL, en pausa: si no, seguiría conteniendo el capítulo
                // anterior y al desconectar reanudaría ése en vez del que se estaba viendo.
                currentIndex = idx
                controller.setMediaItems(localMediaItems(pl.items), idx, pl.startPositionMs)
                controller.playWhenReady = false
                controller.prepare()
                castSession.setMedia(req)
            }
            return@LaunchedEffect
        }
```

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Si `localMediaItems` no está en alcance en ese punto, buscar cómo lo llama la rama "nuevo" del `when` de abajo y usar exactamente la misma expresión.

- [ ] **Step 3: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Revisar el diff y commitear**

Run: `/usr/bin/git diff -- app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(cast): casteando, el capitulo nuevo va a la TV y el local queda cebado"
```

---

## Task 5: Verificación en dispositivo

**No ejecutable sin el celu y un Chromecast.** Es tarea del usuario; ningún agente debe marcarla como hecha ni simularla.

- [ ] **Step 1: Instalar**

```bash
./gradlew :app:assembleDebug
```

Instalar el APK en el celu por ADB (`~/Library/Android/sdk/platform-tools/adb -s <serie> install -r app/build/outputs/apk/debug/app-debug.apk`).

- [ ] **Step 2: Recorrido**

1. Castear y **salir al home**: la TV sigue reproduciendo y el ícono de cast sigue encendido. *(Es el bug original.)*
2. Estando casteando, abrir **otro capítulo**: también va a la TV, y el celu **no** empieza a sonar.
3. **Volver a entrar** al reproductor: la app sabe que seguís casteando — controles del cast, no del local.
4. **Desconectar** desde el botón de cast: el local reanuda **donde llegó el receptor**, no donde empezaste a castear, y con **el capítulo correcto** (el último, no el primero).
5. Ver un capítulo **entero fuera del reproductor** y comprobar que **el progreso quedó guardado**, reabriéndolo.
6. **Sin** sesión de cast, el reproductor se comporta exactamente como antes.

- [ ] **Step 3: Revisar crashes**

```bash
adb -s <serie> logcat -d | grep -iE "FATAL EXCEPTION|E AndroidRuntime"
```

Expected: sin resultados.
