# Barra de fuente única (TV o Chromecast) y botón de parar — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la barra del miniplayer desaparezca cuando el TV deja de reproducir, refleje y controle el Chromecast cuando se está casteando, y tenga un botón para parar lo que suene donde suene.

**Architecture:** Un coordinador en el `AppGraph` observa las dos fuentes (Fire TV por PocketBase, Chromecast local) y expone **un solo** flujo normalizado; la barra dibuja lo que le den sin saber de dónde viene. El publisher del TV deja de inferir "hay algo sonando" de un valor que nadie limpia y pasa a tener su propia señal.

**Tech Stack:** Kotlin, Jetpack Compose, media3 (`CastPlayer`, `MediaController`), PocketBase.

**Spec:** `docs/superpowers/specs/2026-07-28-barra-fuente-unica-y-stop-design.md`

## Global Constraints

- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. **Nunca** `Co-Authored-By`.
- **Nunca `git add -A`.** Este repo trackea artefactos en `app/build/`: listar archivos explícitamente.
- **El `git` de este shell está envuelto por `rtk`, que TRUNCA diffs largos.** Usar `/usr/bin/git diff` para revisar.
- **Comando de compilación:** `./gradlew :app:assembleDebug`. **Suite:** `./gradlew :app:testDebugUnitTest`. Ambos deben pasar.
- **Tests JVM puros**, sin Robolectric ni dependencias de Android, en `app/src/test/java/com/arkiv/player/...`.
- **Comentarios y textos de UI en español.**
- **Los números de línea son de referencia.** Ubicar cada edición por el texto que la rodea; leer el archivo antes de editarlo.
- **Algunos pasos de cableado de UI describen el cambio en prosa en vez de dar el bloque completo** (Task 3 pasos 4-5, Task 4 paso 1, Task 5 pasos 4 y 8). Es deliberado: esas funciones se reescribieron varias veces hoy y un bloque copiado acá quedaría desactualizado y te haría pisar código bueno. La prosa nombra los identificadores exactos y la semántica exacta — **leé la función primero y adaptá**, no improvises una versión distinta. Si al leerla la instrucción no encaja con lo que ves, pará y reportalo en vez de adivinar.

## Contexto que ya existe y hay que reusar, no reinventar

- `CastSessionManager` (`cast/`) expone `player: CastPlayer`, `casting: StateFlow<Boolean>`, `setMedia(CastRequest)`, y los públicos `baseOffsetMs: Long` y `knownDurationMs: Long`.
- `CastProgress` (`cast/`) ya traduce lo que reporta el receptor a lo que hay que mostrar: `contentPosition(receiverPosMs, baseOffsetMs)` y `contentDuration(receiverDurMs, knownDurationMs)`. Con el audio transcodificado el receptor cuenta desde cero y no sabe la duración, así que **hay que pasar por ahí siempre**.
- `TvNowPlaying` / `TvSnapshot` / `TvPlaybackState` (`remote/NowPlayingModels.kt`) son el formato que la barra ya sabe dibujar.
- `MiniPlayerBar` y `rememberMiniPlayerRender` (`ui/remote/MiniPlayerBar.kt`) ya aplican extrapolación y overlay optimista.

---

## Estructura

| Archivo | Responsabilidad |
|---|---|
| `playback/PlaybackService.kt` | Señal `NowPlaying.playerOpen` |
| `ui/tv/ArkivTvRoot.kt` | Encender/apagar esa señal; aplicar el comando `stop` |
| `remote/NowPlayingPublisher.kt` | Respetar la señal |
| `remote/BarSource.kt` (nuevo) | Elegir fuente y normalizar. **Puro** |
| `remote/NowPlayingCoordinator.kt` (nuevo) | Observar ambas fuentes y exponer un flujo |
| `cast/CastSessionManager.kt` | Exponer la petición actual; parada intencional |
| `remote/TransportCommand.kt` | Tipo `stop` |
| `ui/remote/MiniPlayerBar.kt`, `ui/remote/NowPlayingScreen.kt` | Botón de parar; consumir el coordinador |
| `ui/ArkivRoot.kt` | La barra lee del coordinador |
| `docs/pocketbase/collections.md` | Documentar el tipo nuevo |

---

## Task 1: La barra desaparece cuando el TV sale del reproductor

Arregla el bug que motivó todo: la barra se quedaba mostrando el último capítulo indefinidamente. Es independiente del resto y se puede shipear sola.

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/PlaybackService.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`
- Modify: `app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt`

**Interfaces:**
- Produces: `NowPlaying.playerOpen: Boolean`

- [ ] **Step 1: Agregar el campo**

En `playback/PlaybackService.kt`, ubicar `object NowPlaying` (tras el comentario `/** Referencia al capítulo que se está reproduciendo (para el deep-link de la notificación). */`) y reemplazarlo por:

```kotlin
/** Referencia al capítulo que se está reproduciendo (para el deep-link de la notificación). */
object NowPlaying {
    @Volatile
    var episodeId: String? = null

    /**
     * ¿Está abierta la pantalla del reproductor en ESTE dispositivo?
     *
     * Existe aparte de [episodeId] a propósito: ese valor no se limpia nunca —lo leen el deep-link
     * de la notificación y la resolución de siguiente/anterior, que lo necesitan DESPUÉS de cerrar
     * el reproductor—. Sin esta señal el publisher del TV seguía anunciando el último capítulo en
     * pausa para siempre, y la barra del celu mostraba algo que hacía rato no sonaba.
     */
    @Volatile
    var playerOpen: Boolean = false
}
```

- [ ] **Step 2: Encender y apagar la señal en la ruta del reproductor del TV**

En `ui/tv/ArkivTvRoot.kt`, ubicar `composable("player/{episodeId}") { entry ->` (~línea 188). Después de la línea `val episodeId = Uri.decode(...)` y **antes** de `PlayerScreen(`, insertar:

```kotlin
            // El publisher usa esto para saber si de verdad hay algo reproduciéndose acá. Sin esta
            // señal se guiaba por NowPlaying.episodeId, que no se limpia nunca, así que el TV
            // seguía anunciando el último capítulo en pausa y la barra del celu no se iba jamás.
            androidx.compose.runtime.DisposableEffect(Unit) {
                com.arkiv.player.playback.NowPlaying.playerOpen = true
                onDispose { com.arkiv.player.playback.NowPlaying.playerOpen = false }
            }
```

Va acá y no en `PlayerScreen` porque el publisher solo corre en el TV: la señal pertenece a la raíz del TV, no a una pantalla que el celu comparte.

- [ ] **Step 3: Que el publisher la respete**

En `remote/NowPlayingPublisher.kt`, ubicar `private suspend fun readPlayer()` y su línea `val epId = NowPlaying.episodeId ?: return@withContext null`. Insertar **antes**:

```kotlin
        // Con el reproductor cerrado no hay nada que anunciar, aunque el player siga vivo y en
        // pausa: NowPlaying.episodeId no se limpia nunca.
        if (!NowPlaying.playerOpen) return@withContext null
```

- [ ] **Step 4: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

- [ ] **Step 5: Revisar el diff y commitear**

Run: `/usr/bin/git diff -- app/src/main/java/com/arkiv/player/playback/PlaybackService.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt`

```bash
git add app/src/main/java/com/arkiv/player/playback/PlaybackService.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt
git commit -m "fix(miniplayer): la barra desaparece cuando el TV sale del reproductor"
```

---

## Task 2: Elegir fuente y normalizar (puro)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/BarSource.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/BarSourceTest.kt`

**Interfaces:**
- Consumes: `TvNowPlaying`, `TvSnapshot` (`remote/NowPlayingModels.kt`)
- Produces: `data class BarState(nowPlaying, receivedAtMs, extrapolar, fuente)`, `enum class BarFuente { TV, CAST }`, `BarSource.pick(...)`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/remote/BarSourceTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BarSourceTest {

    private fun foto(episodeId: String = "ep-tv", positionMs: Long = 1_000) = TvNowPlaying(
        episodeId = episodeId, itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = 60_000,
        state = TvPlaybackState.PLAYING, hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `sin nada activo no hay barra`() {
        assertNull(BarSource.pick(castActivo = false, cast = null, tv = null, ahoraMs = 100))
    }

    @Test
    fun `solo el TV manda cuando no hay cast`() {
        val r = BarSource.pick(
            castActivo = false, cast = null,
            tv = TvSnapshot(foto(), receivedAtMs = 50), ahoraMs = 100,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
        assertEquals("ep-tv", r.nowPlaying.episodeId)
        assertEquals(50, r.receivedAtMs)
    }

    @Test
    fun `el TV se extrapola`() {
        val r = BarSource.pick(
            castActivo = false, cast = null,
            tv = TvSnapshot(foto(), receivedAtMs = 50), ahoraMs = 100,
        )!!
        assertTrue(r.extrapolar)
    }

    @Test
    fun `casteando manda el cast aunque el TV tambien reproduzca`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"),
            tv = TvSnapshot(foto(episodeId = "ep-tv"), receivedAtMs = 50), ahoraMs = 100,
        )!!
        assertEquals(BarFuente.CAST, r.fuente)
        assertEquals("ep-cast", r.nowPlaying.episodeId)
    }

    @Test
    fun `el cast NO se extrapola porque el player esta en el celu`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), tv = null, ahoraMs = 100,
        )!!
        assertFalse(r.extrapolar)
    }

    @Test
    fun `el cast lleva el instante actual porque se lee al momento`() {
        val r = BarSource.pick(
            castActivo = true, cast = foto(episodeId = "ep-cast"), tv = null, ahoraMs = 777,
        )!!
        assertEquals(777, r.receivedAtMs)
    }

    @Test
    fun `sesion de cast activa pero sin medio cargado cae al TV`() {
        val r = BarSource.pick(
            castActivo = true, cast = null,
            tv = TvSnapshot(foto(), receivedAtMs = 50), ahoraMs = 100,
        )!!
        assertEquals(BarFuente.TV, r.fuente)
    }

    @Test
    fun `sesion de cast activa sin medio y sin TV no da barra`() {
        assertNull(BarSource.pick(castActivo = true, cast = null, tv = null, ahoraMs = 100))
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.BarSourceTest"`
Expected: FAIL — `BarSource` no existe.

- [ ] **Step 3: Implementar**

Crear `app/src/main/java/com/arkiv/player/remote/BarSource.kt`:

```kotlin
package com.arkiv.player.remote

/** De dónde viene lo que muestra la barra. */
enum class BarFuente { TV, CAST }

/**
 * Lo que la barra debe dibujar, ya normalizado: viene del Fire TV o del Chromecast y la barra no
 * se entera de cuál.
 */
data class BarState(
    val nowPlaying: TvNowPlaying,
    val receivedAtMs: Long,
    /** Si la posición hay que avanzarla con el reloj local entre lecturas. */
    val extrapolar: Boolean,
    val fuente: BarFuente,
)

/**
 * Elige la fuente y la normaliza. Pura: testeable sin Android.
 *
 * La regla es deliberadamente simple —si hay cast con medio cargado manda el Chromecast, si no
 * manda el Fire TV—. Se consideró "gana el más reciente", pero tener las dos cosas a la vez no es
 * un escenario real en este uso y esa regla exigía llevar marcas de arranque por fuente.
 */
object BarSource {

    fun pick(
        castActivo: Boolean,
        cast: TvNowPlaying?,
        tv: TvSnapshot?,
        ahoraMs: Long,
    ): BarState? {
        // El cast se lee del CastPlayer, que está en este teléfono: la posición es de ahora mismo,
        // no hay nada que extrapolar ni latencia que disimular.
        if (castActivo && cast != null) {
            return BarState(cast, ahoraMs, extrapolar = false, fuente = BarFuente.CAST)
        }
        // El TV llega por fotos cada 3s: entre una y otra la posición se avanza localmente.
        if (tv != null) {
            return BarState(tv.nowPlaying, tv.receivedAtMs, extrapolar = true, fuente = BarFuente.TV)
        }
        return null
    }
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.BarSourceTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Revisar el diff y commitear**

```bash
git add app/src/main/java/com/arkiv/player/remote/BarSource.kt app/src/test/java/com/arkiv/player/remote/BarSourceTest.kt
git commit -m "feat(barra): eleccion de fuente y normalizacion, pura y testeada"
```

---

## Task 3: El coordinador y la barra que lo consume

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/NowPlayingCoordinator.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt`

**Interfaces:**
- Consumes: `BarSource.pick`, `BarState`, `BarFuente` (Task 2)
- Produces: `CastSessionManager.currentRequest: CastRequest?`, `NowPlayingCoordinator.state: StateFlow<BarState?>`, `AppGraph.nowPlayingCoordinator`

- [ ] **Step 1: Exponer la petición actual del cast**

En `cast/CastSessionManager.kt`, junto a los accesores públicos `baseOffsetMs` y `knownDurationMs` (que ya derivan de `pending`), agregar en el mismo estilo:

```kotlin
    /** Lo que se le pidió al receptor: de acá salen título, carátula y episodio para la barra. */
    val currentRequest: CastRequest? get() = pending
```

- [ ] **Step 2: Implementar el coordinador**

Crear `app/src/main/java/com/arkiv/player/remote/NowPlayingCoordinator.kt`:

```kotlin
package com.arkiv.player.remote

import com.arkiv.player.cast.CastProgress
import com.arkiv.player.cast.CastSessionManager
import com.arkiv.player.data.ArkivRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Única fuente de verdad de la barra: observa el Fire TV y el Chromecast y expone lo que hay que
 * dibujar. La barra queda tonta — dibuja lo que le den y no sabe de dónde viene.
 */
class NowPlayingCoordinator(
    private val tv: TvNowPlayingRepository,
    private val cast: CastSessionManager?,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<BarState?>(null)
    val state: StateFlow<BarState?> = _state.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                _state.value = BarSource.pick(
                    castActivo = cast?.casting?.value == true,
                    cast = leerCast(),
                    tv = tv.state.value,
                    ahoraMs = System.currentTimeMillis(),
                )
                delay(TICK_MS)
            }
        }
    }

    /**
     * Foto del Chromecast. La posición y la duración pasan por [CastProgress] a propósito: con el
     * audio transcodificado el receptor arranca en cero y no conoce la duración, así que en crudo
     * mentiría.
     */
    private suspend fun leerCast(): TvNowPlaying? {
        val c = cast ?: return null
        val req = c.currentRequest ?: return null
        val (pos, dur, playing, buffering) = withContext(Dispatchers.Main) {
            Lectura(
                CastProgress.contentPosition(c.player.currentPosition, c.baseOffsetMs),
                CastProgress.contentDuration(c.player.duration, c.knownDurationMs),
                c.player.isPlaying,
                c.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
            )
        }
        // Siguiente/anterior los sabe el repositorio, no el receptor: el Chromecast tiene un solo
        // ítem cargado y no sabe nada de la serie. Es la misma fuente que usa el publisher del TV,
        // así que celu y TV nunca ofrecen un salto distinto.
        val hasNext = repository.nextEpisode(req.episodeId) != null
        val hasPrev = repository.previousEpisode(req.episodeId) != null
        return TvNowPlaying(
            episodeId = req.episodeId,
            itemId = "",
            kind = "CAST",
            title = req.title,
            subtitle = req.subtitle,
            posterUrl = req.artworkUrl,
            positionMs = pos,
            durationMs = dur,
            state = when {
                buffering -> TvPlaybackState.BUFFERING
                playing -> TvPlaybackState.PLAYING
                else -> TvPlaybackState.PAUSED
            },
            hasNext = hasNext,
            hasPrev = hasPrev,
            at = "",
        )
    }

    private data class Lectura(
        val posicionMs: Long,
        val duracionMs: Long,
        val reproduciendo: Boolean,
        val buffereando: Boolean,
    )

    private companion object {
        const val TICK_MS = 500L
    }
}
```

- [ ] **Step 3: Cablearlo en `AppGraph`**

En `AppGraph.kt`, después del bloque `tvNowPlaying`, agregar:

```kotlin
    /** Única fuente de verdad de la barra del miniplayer (TV o Chromecast). */
    val nowPlayingCoordinator: com.arkiv.player.remote.NowPlayingCoordinator by lazy {
        com.arkiv.player.remote.NowPlayingCoordinator(tvNowPlaying, castSession, repository, applicationScope)
    }
```

Y en el `init`, en la rama del celu (donde hoy se hace `tvNowPlaying.start()`), agregar debajo:

```kotlin
                nowPlayingCoordinator.start()
```

- [ ] **Step 4: Que la barra lea del coordinador**

En `ui/ArkivRoot.kt`, reemplazar la línea `val tvSnapshot by graph.tvNowPlaying.state.collectAsStateWithLifecycle()` por:

```kotlin
    val barState by graph.nowPlayingCoordinator.state.collectAsStateWithLifecycle()
```

Y en el `bottomBar`, cambiar la llamada a `rememberMiniPlayerRender(snapshot = tvSnapshot, …)` por `rememberMiniPlayerRender(bar = barState, …)`.

- [ ] **Step 5: Que `rememberMiniPlayerRender` respete la marca de extrapolación**

En `ui/remote/MiniPlayerBar.kt`, cambiar la firma de `rememberMiniPlayerRender` para que reciba `bar: BarState?` en vez de `snapshot: TvSnapshot?`, y dentro:

- construir el `TvSnapshot` que el reloj necesita como `TvSnapshot(bar.nowPlaying, bar.receivedAtMs)`;
- pasar `scrubbing = scrubbing || !bar.extrapolar` a `ExtrapolatedClock.positionAt`, porque congelar la extrapolación es exactamente lo que hace falta cuando la posición ya viene fresca del Chromecast;
- calcular `stale` y `hidden` **solo** cuando `bar.extrapolar` es true. Con el Chromecast el estado es local y `casting` se apaga solo si la sesión cae, así que la cota de rancio no aplica: dejar `stale = false` y `hidden = false`.

- [ ] **Step 6: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

- [ ] **Step 7: Revisar el diff y commitear**

```bash
git add app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt app/src/main/java/com/arkiv/player/remote/NowPlayingCoordinator.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt
git commit -m "feat(barra): un coordinador elige entre TV y Chromecast y la barra lo consume"
```

---

## Task 4: Los comandos de la barra según la fuente

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`

**Interfaces:**
- Consumes: `BarFuente` (Task 2), `BarState.fuente`, `CastSessionManager`

- [ ] **Step 1: Enrutar cada comando a su destino**

En `ui/ArkivRoot.kt`, el `onCommand` de `MiniPlayerBar` hoy siempre llama a `graph.remoteController.sendTransport(cmd)`. Cambiarlo para que enrute según `barState?.fuente`:

- **`BarFuente.TV`** → como hoy, `graph.remoteController.sendTransport(cmd)`.
- **`BarFuente.CAST`** → actuar sobre `graph.castSession?.player` en el hilo principal:
  - `Pause` → `pause()`
  - `Resume` → `play()`
  - `Seek` → `seekTo(...)`. **Ojo:** la posición que maneja la barra es la del CONTENIDO, y el receptor cuenta desde su propio cero, así que hay que restarle `graph.castSession.baseOffsetMs` antes de pasarla, capando en 0.
  - `Next` / `Prev` → resolver el vecino con `graph.repository.nextEpisode` / `previousEpisode` sobre el `episodeId` del `BarState`, y mandarlo con el mismo camino que ya usa el reproductor para castear un episodio.

El overlay optimista se aplica solo para `BarFuente.TV`: con el Chromecast la respuesta es inmediata y fijar un estado que ya llegó solo agrega parpadeo.

- [ ] **Step 2: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

- [ ] **Step 3: Revisar el diff y commitear**

```bash
git add app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(barra): los botones actuan sobre la fuente que se este mostrando"
```

---

## Task 5: El botón de parar

**Files:**
- Modify: `docs/pocketbase/collections.md`
- Modify: `app/src/main/java/com/arkiv/player/remote/TransportCommand.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`
- Modify: `app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/remote/NowPlayingScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`

**Interfaces:**
- Produces: `TransportCommand.Stop`, `CastSessionManager.stopIntentionally()`

- [ ] **Step 1: El paso manual en el servidor**

**Este paso lo ejecuta el usuario o el controlador con acceso SSH, no un implementer.** En el admin de PocketBase (`https://db.comparadorinternet.co`), colección `commands`, campo `type`: agregar el valor `stop` al select.

Sin esto el server **rechaza el record y el comando falla en silencio** — exactamente cómo `subprefs` y `webquality` estuvieron rotos durante meses (ver el aviso en `docs/pocketbase/collections.md`).

Actualizar en ese doc la línea de valores de `type` para incluir `stop`.

- [ ] **Step 2: Escribir el test que falla**

En `app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt`, agregar:

```kotlin
    @Test
    fun `stop va y vuelve como los demas`() {
        val type = TransportCommandCodec.typeOf(TransportCommand.Stop)
        assertEquals("stop", type)
        assertEquals(
            TransportCommand.Stop,
            TransportCommandCodec.parse(type, TransportCommandCodec.payloadOf(TransportCommand.Stop)),
        )
    }

    @Test
    fun `stop esta entre los tipos que el select debe admitir`() {
        assertTrue(TransportCommandCodec.TYPES.contains("stop"))
    }
```

Agregar el import `org.junit.Assert.assertTrue` si no está.

- [ ] **Step 3: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TransportCommandTest"`
Expected: FAIL — `TransportCommand.Stop` no existe.

- [ ] **Step 4: Agregar el tipo**

En `remote/TransportCommand.kt`: agregar `data object Stop : TransportCommand` junto a los otros objetos; `const val STOP = "stop"`; incluirlo en `TYPES`; y su rama en `typeOf` y en `parse`. `payloadOf` ya devuelve cadena vacía para todo lo que no sea `Seek`.

- [ ] **Step 5: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TransportCommandTest"`
Expected: PASS

- [ ] **Step 6: Que el TV lo aplique**

En `ui/tv/ArkivTvRoot.kt`, en el `when (cmd)` del colector de `incomingTransport`, agregar la rama:

```kotlin
                com.arkiv.player.remote.TransportCommand.Stop -> {
                    // Parar de verdad: cortar y salir del reproductor, para que la señal
                    // NowPlaying.playerOpen se apague y la barra del celu desaparezca sola.
                    com.arkiv.player.playback.PlaybackEngine.vlc?.stop()
                    navController.popBackStack()
                }
```

- [ ] **Step 7: La parada intencional del cast**

En `cast/CastSessionManager.kt` agregar:

```kotlin
    /**
     * ¿La última sesión terminó porque el usuario pulsó "parar"?
     *
     * Existe porque el camino de desconexión reanuda la reproducción local donde llegó el receptor
     * —lo correcto al desconectar desde el botón de cast— pero sería absurdo tras pulsar parar: el
     * usuario pidió silencio y el teléfono se pondría a reproducir. Se consume una sola vez.
     */
    @Volatile private var paradaIntencional = false

    fun stopIntentionally() {
        paradaIntencional = true
        pending = null
        scope.launch {
            withContext(Dispatchers.Main) {
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        }
    }

    /** Consume la marca: la siguiente desconexión vuelve a reanudar normalmente. */
    fun consumirParadaIntencional(): Boolean {
        val v = paradaIntencional
        paradaIntencional = false
        return v
    }
```

`player.release()` es lo que termina la sesión — está verificado en el bytecode de media3 que llama a `endCurrentSession(false)`.

En `ui/player/PlayerScreen.kt`, en el efecto que reanuda el reproductor local al apagarse `casting`, consultar `castSession?.consumirParadaIntencional() == true` y **saltear la reanudación** en ese caso.

- [ ] **Step 8: El botón en la barra y en la pantalla grande**

En `ui/remote/MiniPlayerBar.kt`, agregar un sexto control al final de la fila, separado del resto con `Spacer(Modifier.width(8.dp))` y con `Icons.Filled.Stop`, `contentDescription = "Parar"`. En `ui/remote/NowPlayingScreen.kt`, lo mismo con el tamaño de esa pantalla.

En `ui/ArkivRoot.kt`, enrutar el comando nuevo: con `BarFuente.TV` mandar `TransportCommand.Stop` por `sendTransport`; con `BarFuente.CAST` llamar a `graph.castSession?.stopIntentionally()`.

- [ ] **Step 9: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
Run: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL

- [ ] **Step 10: Revisar el diff y commitear**

```bash
git add docs/pocketbase/collections.md app/src/main/java/com/arkiv/player/remote/TransportCommand.kt app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt app/src/main/java/com/arkiv/player/cast/CastSessionManager.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt app/src/main/java/com/arkiv/player/ui/remote/NowPlayingScreen.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(barra): boton de parar, que corta el TV o termina la sesion de cast"
```

---

## Task 6: Verificación en dispositivo

**No ejecutable sin el Fire Stick, el celu y un Chromecast.** Es tarea del usuario.

- [ ] **Step 1: Instalar en ambos aparatos**

- [ ] **Step 2: Recorrido**

1. Reproducir en el Fire TV → la barra aparece en el celu.
2. **Salir del reproductor en el Fire TV** → la barra desaparece en ≤3s. *(Es el bug original.)*
3. Con el TV reproduciendo, pausar desde la barra → sigue visible, en pausa. *(Pausado no es cerrado.)*
4. Castear desde el celu → la barra muestra lo que se castea, y **su posición avanza sin saltos**.
5. Sus botones mueven el Chromecast: pausa, ±10s, siguiente capítulo.
6. **Parar** con el Chromecast → la TV vuelve a lo suyo y **el celu no empieza a reproducir**.
7. **Parar** con el Fire TV → corta, sale del reproductor, la barra desaparece.
8. Desconectar desde el botón de cast (no desde parar) → el celu **sí** reanuda donde llegó el receptor.

- [ ] **Step 3: Revisar crashes**

```bash
adb -s <serie> logcat -d | grep -iE "FATAL EXCEPTION|E AndroidRuntime"
```
