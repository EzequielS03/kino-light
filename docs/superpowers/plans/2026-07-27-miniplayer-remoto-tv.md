# Miniplayer remoto del TV — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el celu muestre una barra fija sobre la navegación con la carátula, el título, el progreso y los controles de lo que el TV está reproduciendo, y una pantalla "Reproduciendo ahora" al tocarla.

**Architecture:** El TV publica fotos de su estado en un campo json del record `devices` de PocketBase; el celu hace poll cada 3s y extrapola la posición con su reloj local entre fotos. Los comandos (pausa, seek absoluto, siguiente/anterior) viajan por la colección `commands` que ya existe, usando un tipo de transporte genérico nuevo.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), media3 (`SimpleBasePlayer` sobre libVLC), PocketBase, Room, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-27-miniplayer-remoto-tv-design.md`

## Global Constraints

- **Identidad de git:** todo commit va con `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. **Nunca** agregar `Co-Authored-By`.
- **Nunca `git add -A`.** Varias sesiones comparten este working tree: en cada commit se listan los archivos explícitamente.
- **Tests:** JUnit4 puro en `app/src/test/java/com/arkiv/player/…`, sin Robolectric ni dependencias de Android. `org.json` **sí** está disponible en tests (`testImplementation("org.json:json:20240303")`, `app/build.gradle.kts:145`).
- **Comando de test:** `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.<paquete>.<Clase>"`
- **Comando de compilación:** `./gradlew :app:assembleDebug`
- **Hilo del player:** `PlaybackEngine.vlc` es un `SimpleBasePlayer` construido con `mainLooper` (`playback/PlaybackService.kt:44`). Toda lectura o escritura sobre él va en `Dispatchers.Main`.
- **Idioma:** comentarios y textos de UI en español, como el resto del repo.

---

## Estructura de archivos

**Nuevos**

| Archivo | Responsabilidad |
|---|---|
| `data/EpisodeNavigation.kt` | Lógica pura de siguiente/anterior episodio dentro de una sección |
| `remote/NowPlayingModels.kt` | `TvNowPlaying`, `TvPlaybackState`, `TvSnapshot` |
| `remote/NowPlayingCodec.kt` | Serialización del payload + `eventKey` para detectar cambios |
| `remote/ExtrapolatedClock.kt` | Posición mostrada y detección de foto rancia. Puro |
| `remote/OptimisticOverlay.kt` | Fija el efecto de un comando hasta que el TV lo confirme. Puro |
| `remote/TransportCommand.kt` | Comandos de transporte + su codec. Puro |
| `remote/TvDeviceSelector.kt` | Elige el record de TV entre varios. Puro |
| `remote/NowPlayingPublisher.kt` | (TV) Publica las fotos |
| `remote/TvNowPlayingRepository.kt` | (celu) Poll + estado observable |
| `ui/remote/MiniPlayerBar.kt` | La barra compacta |
| `ui/remote/NowPlayingScreen.kt` | La pantalla completa |
| `DeviceType.kt` | `isTelevision(context)` compartido |

**Modificados**

| Archivo | Cambio |
|---|---|
| `data/ArkivRepository.kt:615` | `nextEpisode` delega en `EpisodeNavigation`; se agrega `previousEpisode` |
| `remote/RemoteTransport.kt` | Método `sendTransport` con default `false` |
| `remote/CloudTransport.kt` | Implementa `sendTransport` y parsea los tipos nuevos |
| `remote/TransportRouter.kt` | Enruta `sendTransport` |
| `remote/RemoteController.kt` | `sendTransport(cmd)` + `incomingTransport` |
| `ui/tv/ArkivTvRoot.kt` | Colector que aplica los comandos de transporte |
| `ui/ArkivRoot.kt:219` | `bottomBar` con la barra + ruta `nowplaying` |
| `AppGraph.kt` | Wiring del publisher (TV) y del repositorio (celu) |
| `MainActivity.kt:108` | Usa `DeviceType.isTelevision` |
| `docs/pocketbase/collections.md` | Documenta el campo y los tipos nuevos |

---

## Task 1: Esquema de PocketBase

Tarea manual en el admin, más la actualización del doc. Bloquea a las tareas 6-8: sin el campo, los PATCH fallan.

**Files:**
- Modify: `docs/pocketbase/collections.md`

- [ ] **Step 1: Verificar el select `type` de `commands`**

Entrar al admin de `https://db.comparadorinternet.co` → Collections → `commands` → campo `type`.

El doc lo lista solo como `play`/`key`, pero `remote/CloudTransport.kt:39-40` ya envía `subprefs` y `webquality`. Anotar cuál de las dos cosas es cierta:
- Si el select **ya incluye** `subprefs`/`webquality`, el doc estaba viejo.
- Si **no** los incluye, esos dos comandos vienen fallando callados y hay que agregarlos también.

- [ ] **Step 2: Agregar los valores nuevos al select `type`**

Añadir: `pause`, `resume`, `seek`, `next`, `prev` (más `subprefs` y `webquality` si faltaban). Guardar.

- [ ] **Step 3: Agregar el campo `nowPlaying` a `devices`**

Collections → `devices` → New field:
- Nombre: `nowPlaying`
- Tipo: `json`
- Max size: `4000`
- Required: **no**

Guardar. No hace falta tocar reglas: `devices` ya tiene update restringido a `accountId = @request.auth.accountId`.

- [ ] **Step 4: Verificar de punta a punta**

Desde el admin, editar a mano el record `kind=tv` y poner en `nowPlaying`:

```json
{"v":1,"episodeId":"x","itemId":"y","kind":"ARCHIVE","title":"Prueba","subtitle":"T1E1","posterUrl":"","positionMs":1000,"durationMs":2000,"state":"PAUSED","hasNext":false,"hasPrev":false,"at":"2026-07-27T00:00:00Z"}
```

Guardar sin error. Después vaciarlo (dejarlo `null`).

- [ ] **Step 5: Actualizar el doc**

En `docs/pocketbase/collections.md`, en la tabla de campos custom de `devices`, agregar la fila:

```markdown
| `nowPlaying` | json | opcional (maxSize 4000); estado de reproducción publicado por el TV, ver `remote/NowPlayingCodec.kt` |
```

Y en la sección `commands`, reemplazar la línea de campos por:

```markdown
**Campos:** `accountId` (text, req), `targetDeviceId` (text, req, índice `idx_commands_target`), `fromDeviceId` (text), `type` (select: play/key/subprefs/webquality/pause/resume/seek/next/prev, req), `payload` (json, max 20000), `seq` (number), `ack` (bool).
```

- [ ] **Step 6: Commit**

```bash
git add docs/pocketbase/collections.md
git commit -m "docs(pocketbase): campo nowPlaying en devices y tipos de transporte en commands"
```

---

## Task 2: `EpisodeNavigation` y `previousEpisode`

Extrae la lógica de navegación entre episodios a un objeto puro (hoy vive dentro de `nextEpisode` y no tiene tests), y agrega el anterior.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/EpisodeNavigation.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt:614-623`
- Test: `app/src/test/java/com/arkiv/player/data/EpisodeNavigationTest.kt`

**Interfaces:**
- Produces: `EpisodeNavigation.nextId(all: List<NavEpisode>, currentId: String): String?`, `EpisodeNavigation.prevId(all: List<NavEpisode>, currentId: String): String?`, `data class NavEpisode(val id: String, val section: String)`, `ArkivRepository.previousEpisode(episodeId: String): Episode?`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/EpisodeNavigationTest.kt`:

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNavigationTest {

    private val serie = listOf(
        NavEpisode("t1e1", "Temporada 1"),
        NavEpisode("t1e2", "Temporada 1"),
        NavEpisode("t1e3", "Temporada 1"),
        NavEpisode("t2e1", "Temporada 2"),
    )

    @Test
    fun `next devuelve el siguiente de la misma seccion`() {
        assertEquals("t1e2", EpisodeNavigation.nextId(serie, "t1e1"))
    }

    @Test
    fun `next no cruza de seccion`() {
        assertNull(EpisodeNavigation.nextId(serie, "t1e3"))
    }

    @Test
    fun `prev devuelve el anterior de la misma seccion`() {
        assertEquals("t1e2", EpisodeNavigation.prevId(serie, "t1e3"))
    }

    @Test
    fun `prev no cruza de seccion`() {
        assertNull(EpisodeNavigation.prevId(serie, "t2e1"))
    }

    @Test
    fun `episodio desconocido devuelve null`() {
        assertNull(EpisodeNavigation.nextId(serie, "nope"))
        assertNull(EpisodeNavigation.prevId(serie, "nope"))
    }

    @Test
    fun `pelicula de un solo episodio no tiene vecinos`() {
        val peli = listOf(NavEpisode("solo", ""))
        assertNull(EpisodeNavigation.nextId(peli, "solo"))
        assertNull(EpisodeNavigation.prevId(peli, "solo"))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.EpisodeNavigationTest"`
Expected: FAIL — no compila, `EpisodeNavigation` no existe.

- [ ] **Step 3: Implementar `EpisodeNavigation`**

Crear `app/src/main/java/com/arkiv/player/data/EpisodeNavigation.kt`:

```kotlin
package com.arkiv.player.data

/** Un episodio reducido a lo que hace falta para navegar entre vecinos. */
data class NavEpisode(val id: String, val section: String)

/**
 * Siguiente/anterior episodio dentro de la MISMA sección (temporada). Pura: testeable sin Room.
 * La lista viene ya ordenada por el DAO; acá solo se busca el vecino que comparta sección.
 */
object EpisodeNavigation {

    fun nextId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.drop(idx + 1) }

    fun prevId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.take(idx).asReversed() }

    private fun neighbour(
        all: List<NavEpisode>,
        currentId: String,
        candidates: (Int) -> List<NavEpisode>,
    ): String? {
        val idx = all.indexOfFirst { it.id == currentId }
        if (idx < 0) return null
        val section = all[idx].section
        return candidates(idx).firstOrNull { it.section == section }?.id
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.EpisodeNavigationTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Reescribir `nextEpisode` y agregar `previousEpisode`**

En `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`, reemplazar el bloque de `nextEpisode` (líneas 614-623) por:

```kotlin
    /** Devuelve el siguiente episodio de la misma sección (para autoplay). */
    suspend fun nextEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.nextId(all, id)
    }

    /** Devuelve el episodio anterior de la misma sección (para el control remoto). */
    suspend fun previousEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.prevId(all, id)
    }

    private suspend fun neighbourEpisode(
        episodeId: String,
        pick: (List<NavEpisode>, String) -> String?,
    ): Episode? {
        val current = itemDao.getEpisode(episodeId) ?: return null
        val all = itemDao.getEpisodesOf(current.itemId)
        val targetId = pick(all.map { NavEpisode(it.id, it.section) }, episodeId) ?: return null
        return all.firstOrNull { it.id == targetId }?.toEpisode()
    }
```

- [ ] **Step 6: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/EpisodeNavigation.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/EpisodeNavigationTest.kt
git commit -m "feat(data): EpisodeNavigation puro + previousEpisode"
```

---

## Task 3: Modelo y codec de `nowPlaying`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/NowPlayingModels.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/NowPlayingCodec.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/NowPlayingCodecTest.kt`

**Interfaces:**
- Produces: `TvPlaybackState` (PLAYING/PAUSED/BUFFERING), `TvNowPlaying(...)`, `TvSnapshot(nowPlaying, receivedAtMs)`, `NowPlayingCodec.encode(TvNowPlaying): String`, `NowPlayingCodec.decode(String?): TvNowPlaying?`, `NowPlayingCodec.eventKey(TvNowPlaying): String`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/remote/NowPlayingCodecTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingCodecTest {

    private val sample = TvNowPlaying(
        episodeId = "ep-1",
        itemId = "item-1",
        kind = "TORRENT",
        title = "Dandadan",
        subtitle = "T1E5 · El abuelo turbo",
        posterUrl = "https://ejemplo/p.jpg",
        positionMs = 754_000,
        durationMs = 1_440_000,
        state = TvPlaybackState.PLAYING,
        hasNext = true,
        hasPrev = false,
        at = "2026-07-27T18:04:12Z",
    )

    @Test
    fun `ida y vuelta preserva todos los campos`() {
        assertEquals(sample, NowPlayingCodec.decode(NowPlayingCodec.encode(sample)))
    }

    @Test
    fun `null o vacio significa que no hay nada reproduciendose`() {
        assertNull(NowPlayingCodec.decode(null))
        assertNull(NowPlayingCodec.decode(""))
        assertNull(NowPlayingCodec.decode("null"))
    }

    @Test
    fun `json invalido no lanza`() {
        assertNull(NowPlayingCodec.decode("{esto no es json"))
    }

    @Test
    fun `payload de una version futura se ignora`() {
        val futuro = NowPlayingCodec.encode(sample).replace("\"v\":1", "\"v\":2")
        assertNull(NowPlayingCodec.decode(futuro))
    }

    @Test
    fun `campos ausentes caen a valores por defecto`() {
        val minimo = """{"v":1,"episodeId":"ep-9"}"""
        val d = NowPlayingCodec.decode(minimo)!!
        assertEquals("ep-9", d.episodeId)
        assertEquals("", d.title)
        assertEquals(0L, d.positionMs)
        assertEquals(TvPlaybackState.PAUSED, d.state)
        assertEquals(false, d.hasNext)
    }

    @Test
    fun `sin episodeId no hay foto valida`() {
        assertNull(NowPlayingCodec.decode("""{"v":1,"title":"x"}"""))
    }

    @Test
    fun `eventKey ignora la posicion y el timestamp`() {
        val avanzado = sample.copy(positionMs = 999_000, at = "2026-07-27T18:05:00Z")
        assertEquals(NowPlayingCodec.eventKey(sample), NowPlayingCodec.eventKey(avanzado))
    }

    @Test
    fun `eventKey cambia con el estado el episodio y la duracion`() {
        val base = NowPlayingCodec.eventKey(sample)
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(state = TvPlaybackState.PAUSED)))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(episodeId = "otro")))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(durationMs = 1L)))
        assertNotEquals(base, NowPlayingCodec.eventKey(sample.copy(hasNext = false)))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.NowPlayingCodecTest"`
Expected: FAIL — no compila, `TvNowPlaying` no existe.

- [ ] **Step 3: Implementar el modelo**

Crear `app/src/main/java/com/arkiv/player/remote/NowPlayingModels.kt`:

```kotlin
package com.arkiv.player.remote

/** Estado de transporte del TV. BUFFERING existe aparte para no mostrar la barra congelada sin explicación. */
enum class TvPlaybackState { PLAYING, PAUSED, BUFFERING }

/** Foto del estado de reproducción del TV, tal como viaja en `devices.nowPlaying`. */
data class TvNowPlaying(
    val episodeId: String,
    val itemId: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val state: TvPlaybackState,
    val hasNext: Boolean,
    val hasPrev: Boolean,
    val at: String,
)

/**
 * Una foto más el instante del reloj LOCAL en que llegó. La extrapolación usa `receivedAtMs`, nunca
 * `nowPlaying.at`: comparar relojes de dos dispositivos exigiría sincronizarlos.
 */
data class TvSnapshot(val nowPlaying: TvNowPlaying, val receivedAtMs: Long)
```

- [ ] **Step 4: Implementar el codec**

Crear `app/src/main/java/com/arkiv/player/remote/NowPlayingCodec.kt`:

```kotlin
package com.arkiv.player.remote

import org.json.JSONObject

/**
 * Serializa [TvNowPlaying] al json que se guarda en `devices.nowPlaying`.
 * Sigue la convención de `cloudsync/SyncMappers`. `org.json` está disponible en tests JVM
 * (ver testImplementation en app/build.gradle.kts), así que el codec se testea sin Robolectric.
 */
object NowPlayingCodec {

    /** Versión del payload. Una foto de versión mayor se ignora en vez de leerse a medias. */
    const val VERSION = 1

    fun encode(p: TvNowPlaying): String = JSONObject().apply {
        put("v", VERSION)
        put("episodeId", p.episodeId)
        put("itemId", p.itemId)
        put("kind", p.kind)
        put("title", p.title)
        put("subtitle", p.subtitle)
        put("posterUrl", p.posterUrl)
        put("positionMs", p.positionMs)
        put("durationMs", p.durationMs)
        put("state", p.state.name)
        put("hasNext", p.hasNext)
        put("hasPrev", p.hasPrev)
        put("at", p.at)
    }.toString()

    fun decode(raw: String?): TvNowPlaying? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "{}") return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != VERSION) return null
        val episodeId = o.optString("episodeId")
        if (episodeId.isBlank()) return null
        val state = runCatching { TvPlaybackState.valueOf(o.optString("state")) }
            .getOrDefault(TvPlaybackState.PAUSED)
        return TvNowPlaying(
            episodeId = episodeId,
            itemId = o.optString("itemId"),
            kind = o.optString("kind"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            posterUrl = o.optString("posterUrl"),
            positionMs = o.optLong("positionMs", 0),
            durationMs = o.optLong("durationMs", 0),
            state = state,
            hasNext = o.optBoolean("hasNext", false),
            hasPrev = o.optBoolean("hasPrev", false),
            at = o.optString("at"),
        )
    }

    /**
     * Huella de los campos que representan un EVENTO (no el avance normal del tiempo). El publisher
     * la compara para decidir si publica ya: si incluyera positionMs, cada segundo sería un "cambio"
     * y el throttle no serviría de nada.
     */
    fun eventKey(p: TvNowPlaying): String = listOf(
        p.episodeId, p.itemId, p.kind, p.title, p.subtitle, p.posterUrl,
        p.durationMs.toString(), p.state.name, p.hasNext.toString(), p.hasPrev.toString(),
    ).joinToString("|")
}
```

- [ ] **Step 5: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.NowPlayingCodecTest"`
Expected: PASS (8 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/remote/NowPlayingModels.kt app/src/main/java/com/arkiv/player/remote/NowPlayingCodec.kt app/src/test/java/com/arkiv/player/remote/NowPlayingCodecTest.kt
git commit -m "feat(remote): modelo y codec del estado nowPlaying del TV"
```

---

## Task 4: Reloj extrapolado y estado optimista

Las dos piezas de lógica pura que hacen que la barra se vea viva y no revierta sola.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/ExtrapolatedClock.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/OptimisticOverlay.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/ExtrapolatedClockTest.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/OptimisticOverlayTest.kt`

**Interfaces:**
- Consumes: `TvNowPlaying`, `TvSnapshot`, `TvPlaybackState` (Task 3)
- Produces: `ExtrapolatedClock.positionAt(snapshot, nowMs, scrubbing): Long`, `ExtrapolatedClock.isStale(snapshot, nowMs, thresholdMs): Boolean`, `ExtrapolatedClock.WARN_MS`, `ExtrapolatedClock.HIDE_MS`, `OptimisticOverlay` con `expectState(state, nowMs)`, `expectPosition(positionMs, nowMs)`, `apply(snapshot, nowMs): TvNowPlaying`, `clear()`

- [ ] **Step 1: Escribir el test del reloj**

Crear `app/src/test/java/com/arkiv/player/remote/ExtrapolatedClockTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrapolatedClockTest {

    private fun foto(
        state: TvPlaybackState = TvPlaybackState.PLAYING,
        positionMs: Long = 10_000,
        durationMs: Long = 60_000,
    ) = TvNowPlaying(
        episodeId = "ep", itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = durationMs, state = state,
        hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `avanza con el reloj local mientras reproduce`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 1_000)
        assertEquals(13_000, ExtrapolatedClock.positionAt(snap, nowMs = 4_000, scrubbing = false))
    }

    @Test
    fun `no avanza en pausa`() {
        val snap = TvSnapshot(foto(state = TvPlaybackState.PAUSED), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = false))
    }

    @Test
    fun `no avanza mientras bufferea`() {
        val snap = TvSnapshot(foto(state = TvPlaybackState.BUFFERING), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = false))
    }

    @Test
    fun `se congela mientras se arrastra el slider`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 1_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 9_000, scrubbing = true))
    }

    @Test
    fun `se capa en la duracion`() {
        val snap = TvSnapshot(foto(positionMs = 59_000), receivedAtMs = 1_000)
        assertEquals(60_000, ExtrapolatedClock.positionAt(snap, nowMs = 100_000, scrubbing = false))
    }

    @Test
    fun `duracion desconocida no capa a cero`() {
        val snap = TvSnapshot(foto(durationMs = 0), receivedAtMs = 1_000)
        assertEquals(13_000, ExtrapolatedClock.positionAt(snap, nowMs = 4_000, scrubbing = false))
    }

    @Test
    fun `un reloj que retrocede no resta posicion`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 5_000)
        assertEquals(10_000, ExtrapolatedClock.positionAt(snap, nowMs = 1_000, scrubbing = false))
    }

    @Test
    fun `rancia segun el umbral`() {
        val snap = TvSnapshot(foto(), receivedAtMs = 0)
        assertFalse(ExtrapolatedClock.isStale(snap, nowMs = 9_999, thresholdMs = ExtrapolatedClock.WARN_MS))
        assertTrue(ExtrapolatedClock.isStale(snap, nowMs = 10_000, thresholdMs = ExtrapolatedClock.WARN_MS))
        assertTrue(ExtrapolatedClock.isStale(snap, nowMs = 30_000, thresholdMs = ExtrapolatedClock.HIDE_MS))
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.ExtrapolatedClockTest"`
Expected: FAIL — `ExtrapolatedClock` no existe.

- [ ] **Step 3: Implementar el reloj**

Crear `app/src/main/java/com/arkiv/player/remote/ExtrapolatedClock.kt`:

```kotlin
package com.arkiv.player.remote

/**
 * La barra avanza a 60fps aunque la foto del TV llegue cada 3-10s: se extrapola localmente.
 * Se usa el reloj del PROPIO celu (`receivedAtMs`), nunca el `at` del TV — así no hace falta que
 * los dos dispositivos tengan la hora sincronizada. Costo: la barra va hasta ~3s por detrás,
 * que en un capítulo de 24 min es 0,2%.
 */
object ExtrapolatedClock {

    /** Sin refresco por este tiempo → mostrar el indicador de desconexión. */
    const val WARN_MS = 10_000L

    /** Sin refresco por este tiempo → ocultar la barra en vez de mentir sobre el TV. */
    const val HIDE_MS = 30_000L

    fun positionAt(snapshot: TvSnapshot, nowMs: Long, scrubbing: Boolean): Long {
        val p = snapshot.nowPlaying
        if (scrubbing || p.state != TvPlaybackState.PLAYING) return p.positionMs
        val elapsed = (nowMs - snapshot.receivedAtMs).coerceAtLeast(0)
        val raw = p.positionMs + elapsed
        // durationMs == 0 significa "todavía no se sabe" (torrent recién abierto): no capar a cero.
        return if (p.durationMs > 0) raw.coerceAtMost(p.durationMs) else raw
    }

    fun isStale(snapshot: TvSnapshot, nowMs: Long, thresholdMs: Long): Boolean =
        nowMs - snapshot.receivedAtMs >= thresholdMs
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.ExtrapolatedClockTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Escribir el test del overlay optimista**

Crear `app/src/test/java/com/arkiv/player/remote/OptimisticOverlayTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticOverlayTest {

    private fun foto(
        state: TvPlaybackState = TvPlaybackState.PLAYING,
        positionMs: Long = 10_000,
    ) = TvNowPlaying(
        episodeId = "ep", itemId = "it", kind = "ARCHIVE", title = "t", subtitle = "s",
        posterUrl = "", positionMs = positionMs, durationMs = 60_000, state = state,
        hasNext = false, hasPrev = false, at = "",
    )

    @Test
    fun `sin nada pendiente devuelve la foto tal cual`() {
        val o = OptimisticOverlay()
        assertEquals(TvPlaybackState.PLAYING, o.apply(foto(), nowMs = 0).state)
    }

    @Test
    fun `una foto vieja no revierte el estado que acabo de fijar`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        // La foto en vuelo todavía dice PLAYING; la barra debe seguir mostrando PAUSED.
        assertEquals(TvPlaybackState.PAUSED, o.apply(foto(state = TvPlaybackState.PLAYING), nowMs = 500).state)
    }

    @Test
    fun `cuando el TV confirma se adopta la foto`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        // Llega la confirmación: se limpia lo pendiente...
        assertEquals(TvPlaybackState.PAUSED, o.apply(foto(state = TvPlaybackState.PAUSED), nowMs = 500).state)
        // ...y a partir de ahí manda el TV otra vez.
        assertEquals(TvPlaybackState.PLAYING, o.apply(foto(state = TvPlaybackState.PLAYING), nowMs = 600).state)
    }

    @Test
    fun `lo pendiente expira para no quedar pegado si el comando se perdio`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        assertEquals(TvPlaybackState.PLAYING, o.apply(foto(state = TvPlaybackState.PLAYING), nowMs = 2_500).state)
    }

    @Test
    fun `la posicion fijada por un seek gana hasta que el TV se acerca`() {
        val o = OptimisticOverlay()
        o.expectPosition(40_000, nowMs = 0)
        assertEquals(40_000, o.apply(foto(positionMs = 10_000), nowMs = 500).positionMs)
        // El TV ya está dentro de la tolerancia: se suelta.
        assertEquals(39_000, o.apply(foto(positionMs = 39_000), nowMs = 800).positionMs)
        assertEquals(10_000, o.apply(foto(positionMs = 10_000), nowMs = 900).positionMs)
    }

    @Test
    fun `clear descarta lo pendiente`() {
        val o = OptimisticOverlay()
        o.expectState(TvPlaybackState.PAUSED, nowMs = 0)
        o.clear()
        assertEquals(TvPlaybackState.PLAYING, o.apply(foto(state = TvPlaybackState.PLAYING), nowMs = 100).state)
    }
}
```

- [ ] **Step 6: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.OptimisticOverlayTest"`
Expected: FAIL — `OptimisticOverlay` no existe.

- [ ] **Step 7: Implementar el overlay**

Crear `app/src/main/java/com/arkiv/player/remote/OptimisticOverlay.kt`:

```kotlin
package com.arkiv.player.remote

import kotlin.math.abs

/**
 * Sostiene el efecto de un comando recién enviado hasta que el TV lo confirme.
 *
 * Sin esto, un poll en vuelo con la foto anterior revertiría el botón a la vista medio segundo
 * después de tocarlo. Lo pendiente se suelta cuando el TV confirma (la foto ya refleja lo pedido)
 * o cuando vence [holdMs] — para no quedar pegado si el comando se perdió.
 */
class OptimisticOverlay(private val holdMs: Long = 2_500) {

    private var pendingState: TvPlaybackState? = null
    private var pendingStateAtMs = 0L
    private var pendingPositionMs: Long? = null
    private var pendingPositionAtMs = 0L

    fun expectState(state: TvPlaybackState, nowMs: Long) {
        pendingState = state
        pendingStateAtMs = nowMs
    }

    fun expectPosition(positionMs: Long, nowMs: Long) {
        pendingPositionMs = positionMs
        pendingPositionAtMs = nowMs
    }

    fun clear() {
        pendingState = null
        pendingPositionMs = null
    }

    fun apply(snapshot: TvNowPlaying, nowMs: Long): TvNowPlaying {
        var out = snapshot

        pendingState?.let { want ->
            val confirmed = snapshot.state == want
            val expired = nowMs - pendingStateAtMs >= holdMs
            if (confirmed || expired) pendingState = null else out = out.copy(state = want)
        }

        pendingPositionMs?.let { want ->
            val confirmed = abs(snapshot.positionMs - want) <= SEEK_TOLERANCE_MS
            val expired = nowMs - pendingPositionAtMs >= holdMs
            if (confirmed || expired) pendingPositionMs = null else out = out.copy(positionMs = want)
        }

        return out
    }

    private companion object {
        /** El TV nunca cae exactamente en el ms pedido: se da por confirmado si quedó cerca. */
        const val SEEK_TOLERANCE_MS = 2_000L
    }
}
```

- [ ] **Step 8: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.OptimisticOverlayTest"`
Expected: PASS (6 tests)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arkiv/player/remote/ExtrapolatedClock.kt app/src/main/java/com/arkiv/player/remote/OptimisticOverlay.kt app/src/test/java/com/arkiv/player/remote/ExtrapolatedClockTest.kt app/src/test/java/com/arkiv/player/remote/OptimisticOverlayTest.kt
git commit -m "feat(remote): reloj extrapolado y overlay optimista de la barra"
```

---

## Task 5: Comandos de transporte por el canal existente

Un solo método genérico en el transporte en vez de cinco: `RemoteTransport` ya usa el patrón de default `= false` para que LAN caiga a la nube.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/TransportCommand.kt`
- Modify: `app/src/main/java/com/arkiv/player/remote/RemoteTransport.kt:13`
- Modify: `app/src/main/java/com/arkiv/player/remote/CloudTransport.kt:40`, `:132-137`
- Modify: `app/src/main/java/com/arkiv/player/remote/TransportRouter.kt:16`
- Modify: `app/src/main/java/com/arkiv/player/remote/RemoteController.kt:118-123`
- Test: `app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt`

**Interfaces:**
- Produces: `TransportCommand` (sealed: `Pause`, `Resume`, `Seek(positionMs)`, `Next`, `Prev`), `TransportCommandCodec.typeOf(cmd)`, `TransportCommandCodec.payloadOf(cmd)`, `TransportCommandCodec.parse(type, payload): TransportCommand?`, `TransportCommandCodec.TYPES: Set<String>`, `RemoteController.sendTransport(cmd): Boolean`, `RemoteController.incomingTransport: Flow<TransportCommand>`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCommandTest {

    @Test
    fun `ida y vuelta de cada comando`() {
        val todos = listOf(
            TransportCommand.Pause,
            TransportCommand.Resume,
            TransportCommand.Next,
            TransportCommand.Prev,
            TransportCommand.Seek(754_000),
        )
        for (cmd in todos) {
            val type = TransportCommandCodec.typeOf(cmd)
            val payload = TransportCommandCodec.payloadOf(cmd)
            assertEquals(cmd, TransportCommandCodec.parse(type, payload))
        }
    }

    @Test
    fun `los tipos coinciden con el select de commands`() {
        assertEquals("pause", TransportCommandCodec.typeOf(TransportCommand.Pause))
        assertEquals("resume", TransportCommandCodec.typeOf(TransportCommand.Resume))
        assertEquals("seek", TransportCommandCodec.typeOf(TransportCommand.Seek(0)))
        assertEquals("next", TransportCommandCodec.typeOf(TransportCommand.Next))
        assertEquals("prev", TransportCommandCodec.typeOf(TransportCommand.Prev))
        assertEquals(
            setOf("pause", "resume", "seek", "next", "prev"),
            TransportCommandCodec.TYPES,
        )
    }

    @Test
    fun `un tipo desconocido no es un comando de transporte`() {
        assertNull(TransportCommandCodec.parse("play", ""))
        assertNull(TransportCommandCodec.parse("key", "23"))
    }

    @Test
    fun `un seek sin posicion valida se descarta`() {
        assertNull(TransportCommandCodec.parse("seek", ""))
        assertNull(TransportCommandCodec.parse("seek", "abc"))
        assertNull(TransportCommandCodec.parse("seek", null))
    }

    @Test
    fun `un seek negativo se lleva a cero`() {
        assertEquals(TransportCommand.Seek(0), TransportCommandCodec.parse("seek", "-5000"))
    }

    @Test
    fun `los comandos sin payload lo ignoran`() {
        assertTrue(TransportCommandCodec.payloadOf(TransportCommand.Pause).isEmpty())
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TransportCommandTest"`
Expected: FAIL — `TransportCommand` no existe.

- [ ] **Step 3: Implementar el comando y su codec**

Crear `app/src/main/java/com/arkiv/player/remote/TransportCommand.kt`:

```kotlin
package com.arkiv.player.remote

/**
 * Comandos de transporte del miniplayer (celu → TV). Son SEMÁNTICOS, a diferencia de las teclas del
 * pad: no dependen del foco de la UI del TV, se aplican directo sobre el player.
 */
sealed interface TransportCommand {
    data object Pause : TransportCommand
    data object Resume : TransportCommand
    data object Next : TransportCommand
    data object Prev : TransportCommand

    /** Posición ABSOLUTA. Los ±10s los calcula el celu: así el comando es idempotente. */
    data class Seek(val positionMs: Long) : TransportCommand
}

object TransportCommandCodec {

    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val SEEK = "seek"
    const val NEXT = "next"
    const val PREV = "prev"

    /** Debe coincidir con los valores del select `type` de la colección `commands`. */
    val TYPES: Set<String> = setOf(PAUSE, RESUME, SEEK, NEXT, PREV)

    fun typeOf(cmd: TransportCommand): String = when (cmd) {
        TransportCommand.Pause -> PAUSE
        TransportCommand.Resume -> RESUME
        TransportCommand.Next -> NEXT
        TransportCommand.Prev -> PREV
        is TransportCommand.Seek -> SEEK
    }

    fun payloadOf(cmd: TransportCommand): String = when (cmd) {
        is TransportCommand.Seek -> cmd.positionMs.toString()
        else -> ""
    }

    fun parse(type: String, payload: String?): TransportCommand? = when (type) {
        PAUSE -> TransportCommand.Pause
        RESUME -> TransportCommand.Resume
        NEXT -> TransportCommand.Next
        PREV -> TransportCommand.Prev
        SEEK -> payload?.toLongOrNull()?.let { TransportCommand.Seek(it.coerceAtLeast(0)) }
        else -> null
    }
}
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TransportCommandTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Agregar el método al transporte**

En `app/src/main/java/com/arkiv/player/remote/RemoteTransport.kt`, después de la línea 13 (`sendWebQuality`), agregar:

```kotlin
    /**
     * Comando de transporte del miniplayer. Genérico a propósito: un método en vez de cinco.
     * Por defecto no soportado → el router hace failover a la nube (LAN no lo implementa).
     */
    suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean = false
```

- [ ] **Step 6: Implementarlo en `CloudTransport`**

En `app/src/main/java/com/arkiv/player/remote/CloudTransport.kt`, después de la línea 40 (`sendWebQuality`), agregar:

```kotlin
    override suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean =
        send(type, payload, seq)
```

Y en el `when (type)` de `processRecord` (líneas 132-137), agregar antes de `else ->`:

```kotlin
            in TransportCommandCodec.TYPES -> RemoteCommand(type, null, payload, seq)
```

- [ ] **Step 7: Enrutarlo**

En `app/src/main/java/com/arkiv/player/remote/TransportRouter.kt`, después de la línea 16, agregar:

```kotlin
    suspend fun sendTransport(type: String, payload: String, seq: Long): Boolean =
        route({ it.sendTransport(type, payload, seq) })
```

- [ ] **Step 8: Exponerlo en `RemoteController`**

En `app/src/main/java/com/arkiv/player/remote/RemoteController.kt`, después del bloque de `incomingWebQuality` (línea 123), agregar:

```kotlin
    /** Envía un comando de transporte del miniplayer al TV. */
    suspend fun sendTransport(cmd: TransportCommand): Boolean {
        resolveTvId()
        return router.sendTransport(
            TransportCommandCodec.typeOf(cmd),
            TransportCommandCodec.payloadOf(cmd),
            seq.next(),
        )
    }

    /** (TV) Comandos de transporte entrantes, deduplicados por seq. */
    val incomingTransport: Flow<TransportCommand> = incoming.mapNotNull { cmd ->
        if (cmd.type !in TransportCommandCodec.TYPES) return@mapNotNull null
        if (cmd.seq != 0L && !inSeq.isFresh(cmd.seq)) return@mapNotNull null
        TransportCommandCodec.parse(cmd.type, cmd.key)
    }
```

- [ ] **Step 9: Compilar y correr toda la suite de `remote`**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.*"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/arkiv/player/remote/TransportCommand.kt app/src/main/java/com/arkiv/player/remote/RemoteTransport.kt app/src/main/java/com/arkiv/player/remote/CloudTransport.kt app/src/main/java/com/arkiv/player/remote/TransportRouter.kt app/src/main/java/com/arkiv/player/remote/RemoteController.kt app/src/test/java/com/arkiv/player/remote/TransportCommandTest.kt
git commit -m "feat(remote): comandos de transporte (pause/resume/seek/next/prev) por el canal existente"
```

---

## Task 6: El TV publica su estado

**Files:**
- Create: `app/src/main/java/com/arkiv/player/DeviceType.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt`
- Modify: `app/src/main/java/com/arkiv/player/MainActivity.kt:108-113`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt:177-187`

**Interfaces:**
- Consumes: `TvNowPlaying`, `NowPlayingCodec` (Task 3), `ArkivRepository.nextEpisode/previousEpisode` (Task 2)
- Produces: `DeviceType.isTelevision(context: Context): Boolean`, `NowPlayingPublisher.start()`, `AppGraph.nowPlayingPublisher`

- [ ] **Step 1: Extraer `isTelevision` a un archivo compartido**

Crear `app/src/main/java/com/arkiv/player/DeviceType.kt`:

```kotlin
package com.arkiv.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** ¿Este dispositivo es un TV? Lo usan MainActivity (para elegir raíz de UI) y AppGraph (wiring). */
object DeviceType {
    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
```

En `app/src/main/java/com/arkiv/player/MainActivity.kt`, reemplazar el cuerpo del método privado (líneas 108-113) por:

```kotlin
    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
```

- [ ] **Step 2: Implementar el publisher**

Crear `app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt`:

```kotlin
package com.arkiv.player.remote

import androidx.media3.common.Player
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.playback.PlaybackEngine
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * (Solo TV) Publica fotos del estado de reproducción en `devices.nowPlaying` para que el celu pinte
 * el miniplayer.
 *
 * Lee `PlaybackEngine.vlc` directamente en vez de observar la UI: el estado de posición/duración vive
 * como estado local de Compose dentro de PlayerContent, y el player del servicio es la fuente real.
 *
 * Publica ante un CAMBIO DE EVENTO (episodio, estado, duración, vecinos) o cada [HEARTBEAT_MS]
 * mientras reproduce, para corregir la deriva de la extrapolación del celu. Nunca más de un PATCH
 * por segundo.
 */
class NowPlayingPublisher(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
) {
    private data class Raw(val episodeId: String, val positionMs: Long, val durationMs: Long, val state: TvPlaybackState)

    fun start() {
        scope.launch {
            var lastEventKey: String? = null
            var lastPatchMs = 0L
            var lastWasNull = false
            while (true) {
                val now = System.currentTimeMillis()
                val foto = runCatching { build() }.getOrNull()

                if (foto == null) {
                    // Salió del player: limpiar una sola vez, no cada segundo.
                    if (!lastWasNull) {
                        publish(null)
                        lastWasNull = true
                        lastEventKey = null
                        lastPatchMs = now
                    }
                } else {
                    lastWasNull = false
                    val key = NowPlayingCodec.eventKey(foto)
                    val evento = key != lastEventKey
                    val latido = foto.state == TvPlaybackState.PLAYING && now - lastPatchMs >= HEARTBEAT_MS
                    if ((evento || latido) && now - lastPatchMs >= MIN_INTERVAL_MS) {
                        publish(NowPlayingCodec.encode(foto))
                        lastEventKey = key
                        lastPatchMs = now
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Lee el player en el hilo principal (SimpleBasePlayer se construyó con mainLooper). */
    private suspend fun readPlayer(): Raw? = withContext(Dispatchers.Main) {
        val p = PlaybackEngine.vlc ?: return@withContext null
        val epId = NowPlaying.episodeId ?: return@withContext null
        val state = when {
            p.playbackState == Player.STATE_BUFFERING -> TvPlaybackState.BUFFERING
            p.isPlaying -> TvPlaybackState.PLAYING
            else -> TvPlaybackState.PAUSED
        }
        // duration puede venir C.TIME_UNSET (negativo) hasta que se conoce: normalizar a 0.
        val dur = p.duration.let { if (it > 0) it else 0L }
        Raw(epId, p.currentPosition.coerceAtLeast(0), dur, state)
    }

    private suspend fun build(): TvNowPlaying? {
        val raw = readPlayer() ?: return null
        val header = repository.headerInfo(raw.episodeId)
        val itemId = repository.getEpisode(raw.episodeId)?.itemId.orEmpty()
        // La carátula sale del ítem de la biblioteca, NO de PlayerData.artworkUrl: ésa solo está
        // poblada para archive y vale "" en torrent y web.
        val poster = repository.itemThumbnailForEpisode(raw.episodeId).orEmpty()
        return TvNowPlaying(
            episodeId = raw.episodeId,
            itemId = itemId,
            // Informativo (la UI no lo usa para decidir nada): la fuente real la resuelve el player
            // del TV por el prefijo del id, igual que hace ArkivTvRoot.
            kind = if (raw.episodeId.startsWith("torrent:")) "TORRENT" else "ARCHIVE",
            title = header?.itemTitle.orEmpty(),
            subtitle = header?.episodeLabel.orEmpty(),
            posterUrl = poster,
            positionMs = raw.positionMs,
            durationMs = raw.durationMs,
            state = raw.state,
            hasNext = repository.nextEpisode(raw.episodeId) != null,
            hasPrev = repository.previousEpisode(raw.episodeId) != null,
            at = java.time.Instant.now().toString(),
        )
    }

    private suspend fun publish(json: String?) {
        val s = deviceAuth.session.value ?: return
        runCatching {
            client.updateRecord(
                PocketBaseConfig.COLLECTION_DEVICES,
                s.recordId,
                mapOf("nowPlaying" to json),
                s.token,
            )
        }.onFailure { android.util.Log.w("ArkivRemote", "nowPlaying no se pudo publicar: ${it.message}") }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_INTERVAL_MS = 1_000L
        const val HEARTBEAT_MS = 10_000L
    }
}
```

- [ ] **Step 3: Agregar el lookup de carátula que usa el publisher**

En `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`, justo después de `episodesOf` (línea ~608), agregar:

```kotlin
    /** Carátula del ítem al que pertenece un episodio (para el miniplayer remoto). */
    suspend fun itemThumbnailForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return itemDao.getItem(ep.itemId)?.thumbnailUrl
    }
```

Consulta directa al DAO a propósito: `observeItemDetail` es un `Flow` combinado de tres fuentes y sería caro llamarlo una vez por segundo.

- [ ] **Step 4: Wiring en `AppGraph`**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, después del bloque `presence` (línea 179), agregar:

```kotlin
    /** Solo tiene sentido en el TV: es quien reproduce y publica su estado. */
    val nowPlayingPublisher: com.arkiv.player.remote.NowPlayingPublisher by lazy {
        com.arkiv.player.remote.NowPlayingPublisher(pbClient, deviceAuth, repository, applicationScope)
    }
```

Y en el bloque `init` (líneas 181-187), después de `presence.start()`, agregar:

```kotlin
            if (com.arkiv.player.DeviceType.isTelevision(appContext)) nowPlayingPublisher.start()
```

- [ ] **Step 5: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Verificar en el Fire Stick**

Instalar en el TV y reproducir algo. Después, en el admin de PocketBase, abrir el record `kind=tv` y confirmar que `nowPlaying` tiene un json con `state`, `positionMs` y `title` poblados, y que la posición avanza al recargar pasados ~10s. Al salir del player, el campo debe quedar vacío.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/DeviceType.kt app/src/main/java/com/arkiv/player/remote/NowPlayingPublisher.kt app/src/main/java/com/arkiv/player/MainActivity.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt
git commit -m "feat(remote): el TV publica su estado de reproduccion en devices.nowPlaying"
```

---

## Task 7: El TV aplica los comandos entrantes

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt:71-86`

**Interfaces:**
- Consumes: `RemoteController.incomingTransport` (Task 5), `ArkivRepository.previousEpisode` (Task 2)

- [ ] **Step 1: Agregar el colector**

En `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt`, después del `LaunchedEffect` de `incomingWebQuality` (termina en la línea 86), agregar:

```kotlin
    // Miniplayer del celu -> aplicar el comando directo sobre el player del servicio.
    // No se inyectan teclas acá: un seek absoluto y un salto de capítulo son semánticos, no
    // direccionales, y no deben depender de qué tiene el foco en la TV.
    LaunchedEffect(Unit) {
        graph.remoteController.incomingTransport.collect { cmd ->
            val epId = com.arkiv.player.playback.NowPlaying.episodeId ?: return@collect
            when (cmd) {
                is com.arkiv.player.remote.TransportCommand.Seek ->
                    com.arkiv.player.playback.PlaybackEngine.vlc?.seekTo(cmd.positionMs)
                com.arkiv.player.remote.TransportCommand.Pause ->
                    com.arkiv.player.playback.PlaybackEngine.vlc?.pause()
                com.arkiv.player.remote.TransportCommand.Resume ->
                    com.arkiv.player.playback.PlaybackEngine.vlc?.play()
                com.arkiv.player.remote.TransportCommand.Next ->
                    graph.repository.nextEpisode(epId)?.let { goToPlayer(it.id) }
                com.arkiv.player.remote.TransportCommand.Prev ->
                    graph.repository.previousEpisode(epId)?.let { goToPlayer(it.id) }
            }
        }
    }
```

`LaunchedEffect` ya corre en el `Dispatchers.Main` de Compose, así que las llamadas al player están en el hilo correcto.

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verificar a mano contra el TV**

Con el TV reproduciendo, crear a mano un record en la colección `commands` desde el admin de PocketBase:
- `accountId`: el de la cuenta
- `targetDeviceId`: el id del record del TV
- `type`: `pause`
- `seq`: `1`
- `ack`: `false`

El TV debe pausar en ≤3s (el poll de respaldo). Repetir con `type: resume` (`seq: 2`) y con `type: seek`, `payload: 60000` (`seq: 3`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt
git commit -m "feat(tv): aplicar comandos de transporte del miniplayer sobre el player"
```

---

## Task 8: El celu lee el estado del TV

**Files:**
- Create: `app/src/main/java/com/arkiv/player/remote/TvDeviceSelector.kt`
- Create: `app/src/main/java/com/arkiv/player/remote/TvNowPlayingRepository.kt`
- Modify: `app/src/main/java/com/arkiv/player/remote/RemoteController.kt:62-81`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`
- Test: `app/src/test/java/com/arkiv/player/remote/TvDeviceSelectorTest.kt`

**Interfaces:**
- Consumes: `NowPlayingCodec.decode` (Task 3), `TvSnapshot` (Task 3)
- Produces: `TvDeviceSelector.pick(records: List<JSONObject>): JSONObject?`, `TvNowPlayingRepository.state: StateFlow<TvSnapshot?>`, `TvNowPlayingRepository.setActive(Boolean)`, `TvNowPlayingRepository.start()`, `TvNowPlayingRepository.refreshNow()`, `AppGraph.tvNowPlaying`

- [ ] **Step 1: Escribir el test del selector**

Crear `app/src/test/java/com/arkiv/player/remote/TvDeviceSelectorTest.kt`:

```kotlin
package com.arkiv.player.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDeviceSelectorTest {

    private fun tv(id: String, online: Boolean, lastSeen: String) = JSONObject()
        .put("id", id).put("online", online).put("lastSeen", lastSeen)

    @Test
    fun `sin candidatos devuelve null`() {
        assertNull(TvDeviceSelector.pick(emptyList()))
    }

    @Test
    fun `prefiere el que esta online aunque otro se haya visto despues`() {
        val elegido = TvDeviceSelector.pick(
            listOf(
                tv("viejo", online = false, lastSeen = "2026-07-27T20:00:00Z"),
                tv("vivo", online = true, lastSeen = "2026-07-27T10:00:00Z"),
            ),
        )
        assertEquals("vivo", elegido?.getString("id"))
    }

    @Test
    fun `entre dos online gana el visto mas recientemente`() {
        val elegido = TvDeviceSelector.pick(
            listOf(
                tv("a", online = true, lastSeen = "2026-07-27T10:00:00Z"),
                tv("b", online = true, lastSeen = "2026-07-27T20:00:00Z"),
            ),
        )
        assertEquals("b", elegido?.getString("id"))
    }

    @Test
    fun `un unico candidato se elige aunque este offline`() {
        val elegido = TvDeviceSelector.pick(listOf(tv("solo", online = false, lastSeen = "")))
        assertEquals("solo", elegido?.getString("id"))
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TvDeviceSelectorTest"`
Expected: FAIL — `TvDeviceSelector` no existe.

- [ ] **Step 3: Implementar el selector**

Crear `app/src/main/java/com/arkiv/player/remote/TvDeviceSelector.kt`:

```kotlin
package com.arkiv.player.remote

import org.json.JSONObject

/**
 * Elige a qué TV apuntar cuando hay varios `kind=tv` en la cuenta: evita quedarse con uno viejo o
 * fantasma. Extraído de RemoteController.resolveTvId para poder testearlo y reusarlo.
 */
object TvDeviceSelector {
    fun pick(records: List<JSONObject>): JSONObject? = records.sortedWith(
        compareByDescending<JSONObject> { it.optBoolean("online", false) }
            .thenByDescending { it.optString("lastSeen") },
    ).firstOrNull()
}
```

- [ ] **Step 4: Reusarlo en `RemoteController`**

En `app/src/main/java/com/arkiv/player/remote/RemoteController.kt`, dentro de `resolveTvId`, reemplazar el bloque `runCatching { … }` (líneas 65-74) por:

```kotlin
        val id = runCatching {
            // Preferir el TV online y visto más recientemente (evita apuntar a un TV viejo/fantasma
            // cuando hay varios kind=tv en la cuenta).
            TvDeviceSelector.pick(
                client.listRecords(PocketBaseConfig.COLLECTION_DEVICES, "kind='tv'", s.token),
            )?.getString("id")
        }.getOrNull()
```

- [ ] **Step 5: Correr y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.TvDeviceSelectorTest"`
Expected: PASS (4 tests)

- [ ] **Step 6: Implementar el repositorio**

Crear `app/src/main/java/com/arkiv/player/remote/TvNowPlayingRepository.kt`:

```kotlin
package com.arkiv.player.remote

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * (Solo celu) Estado de reproducción del TV, para el miniplayer.
 *
 * Poll deliberado en vez de SSE: con el reloj extrapolado un refresco cada 3s es invisible en la
 * barra de progreso, y los streams largos por Cloudflare ya demostraron ser el punto frágil del
 * sistema. La latencia que sí se percibe —la de los botones— se resuelve con UI optimista.
 *
 * Solo hace poll con [setActive] en true (app en primer plano) y con un TV pareado; si no, duerme.
 */
class TvNowPlayingRepository(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val tvPaired: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TvSnapshot?>(null)
    val state: StateFlow<TvSnapshot?> = _state.asStateFlow()

    private val active = MutableStateFlow(false)

    fun setActive(value: Boolean) {
        active.value = value
        if (value) scope.launch { refreshNow() }
    }

    fun start() {
        scope.launch {
            while (true) {
                if (active.value) refreshNow()
                delay(POLL_MS)
            }
        }
    }

    suspend fun refreshNow() {
        // Sin TV pareado no hay nada que mirar: ni una petición cada 3s.
        if (!tvPaired()) {
            _state.value = null
            return
        }
        val s = deviceAuth.session.value ?: return
        val record = runCatching {
            TvDeviceSelector.pick(
                client.listRecords(PocketBaseConfig.COLLECTION_DEVICES, "kind='tv'", s.token),
            )
        }.getOrNull() ?: return
        val foto = NowPlayingCodec.decode(record.optString("nowPlaying"))
        _state.value = foto?.let { TvSnapshot(it, System.currentTimeMillis()) }
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}
```

- [ ] **Step 7: Wiring en `AppGraph`**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, después del bloque `nowPlayingPublisher`, agregar:

```kotlin
    /** Solo tiene sentido en el celu: es quien mira lo que reproduce el TV. */
    val tvNowPlaying: com.arkiv.player.remote.TvNowPlayingRepository by lazy {
        com.arkiv.player.remote.TvNowPlayingRepository(
            pbClient, deviceAuth, { remoteController.tvPaired.value }, applicationScope,
        )
    }
```

Y en `init`, junto a la línea del publisher, dejar:

```kotlin
            if (com.arkiv.player.DeviceType.isTelevision(appContext)) {
                nowPlayingPublisher.start()
            } else {
                tvNowPlaying.start()
            }
```

- [ ] **Step 8: Compilar y correr la suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.remote.*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arkiv/player/remote/TvDeviceSelector.kt app/src/main/java/com/arkiv/player/remote/TvNowPlayingRepository.kt app/src/main/java/com/arkiv/player/remote/RemoteController.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/test/java/com/arkiv/player/remote/TvDeviceSelectorTest.kt
git commit -m "feat(remote): el celu lee el estado de reproduccion del TV por poll"
```

---

## Task 9: La barra compacta

Compose puro sobre el estado ya construido. El repo no tiene tests de UI de Compose, así que la verificación acá es manual: inventar una convención de test nueva para dos composables no se justifica.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt:219-243`

**Interfaces:**
- Consumes: `TvSnapshot`, `TvPlaybackState`, `ExtrapolatedClock`, `OptimisticOverlay`, `TransportCommand`, `RemoteController.sendTransport`
- Produces: `MiniPlayerBar(nowPlaying, positionMs, stale, onCommand, onExpand, modifier)`, `data class MiniPlayerRender(nowPlaying, positionMs, stale, hidden)`, `rememberMiniPlayerRender(snapshot, overlay, scrubbing): MiniPlayerRender?`, `rememberPoster(nowPlaying): String?`, `formatTime(ms): String`

- [ ] **Step 1: Crear el estado compartido de la barra**

Crear `app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt` con el holder de estado que reusarán la barra y la pantalla grande:

```kotlin
package com.arkiv.player.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.remote.TransportCommand
import com.arkiv.player.remote.TvNowPlaying
import com.arkiv.player.remote.TvPlaybackState
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** mm:ss o h:mm:ss según dure. */
internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Barra compacta del miniplayer del TV. Dos filas (~80dp) en vez de una: cinco controles de 40dp
 * más el título no entran legibles en una sola línea de celu.
 */
@Composable
fun MiniPlayerBar(
    nowPlaying: TvNowPlaying,
    positionMs: Long,
    stale: Boolean,
    onCommand: (TransportCommand) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ArkivSurfaceHigh)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = nowPlaying.posterUrl.ifBlank { null },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    nowPlaying.title.ifBlank { "Reproduciendo en la TV" },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowPlaying.subtitle.isNotBlank()) {
                    Text(
                        nowPlaying.subtitle,
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (stale) "Sin conexión" else "${formatTime(positionMs)} / ${formatTime(nowPlaying.durationMs)}",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.Filled.SkipPrevious, "Capítulo anterior", enabled = nowPlaying.hasPrev) {
                onCommand(TransportCommand.Prev)
            }
            BarButton(Icons.Filled.Replay10, "Atrasar 10 segundos") {
                onCommand(TransportCommand.Seek((positionMs - 10_000).coerceAtLeast(0)))
            }
            PlayPauseButton(nowPlaying.state, onCommand)
            BarButton(Icons.Filled.Forward10, "Adelantar 10 segundos") {
                val tope = if (nowPlaying.durationMs > 0) nowPlaying.durationMs else Long.MAX_VALUE
                onCommand(TransportCommand.Seek((positionMs + 10_000).coerceAtMost(tope)))
            }
            BarButton(Icons.Filled.SkipNext, "Capítulo siguiente", enabled = nowPlaying.hasNext) {
                onCommand(TransportCommand.Next)
            }
        }

        LinearProgressIndicator(
            progress = {
                if (nowPlaying.durationMs > 0) {
                    (positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            color = ArkivRed,
            trackColor = Color.DarkGray,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(state: TvPlaybackState, onCommand: (TransportCommand) -> Unit) {
    if (state == TvPlaybackState.BUFFERING) {
        // Sin este estado la barra parecería congelada mientras el torrent bufferea.
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    } else {
        val playing = state == TvPlaybackState.PLAYING
        IconButton(
            onClick = { onCommand(if (playing) TransportCommand.Pause else TransportCommand.Resume) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pausar" else "Reproducir",
                tint = ArkivRed,
            )
        }
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Deshabilitado en vez de oculto: si desapareciera, los otros controles se moverían de lugar
    // al pasar de una serie a una película.
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, tint = if (enabled) Color.White else Color.DarkGray)
    }
}

/** Lo que la UI dibuja: la foto ya pasada por el overlay optimista más la posición extrapolada. */
data class MiniPlayerRender(
    val nowPlaying: TvNowPlaying,
    val positionMs: Long,
    val stale: Boolean,
    val hidden: Boolean,
)

/**
 * Recalcula cuatro veces por segundo DENTRO de un efecto, no en el cuerpo del composable.
 *
 * Importante: `overlay.apply` muta su estado interno al dar por consumido lo pendiente. Llamarlo
 * durante la composición haría que una recomposición extra lo consumiera antes de tiempo y el botón
 * revertiría solo. Acá el bucle lee el overlay en cada tick, así que un comando recién enviado se
 * refleja en ≤250ms.
 */
@Composable
internal fun rememberMiniPlayerRender(
    snapshot: com.arkiv.player.remote.TvSnapshot?,
    overlay: com.arkiv.player.remote.OptimisticOverlay,
    scrubbing: Boolean,
): MiniPlayerRender? {
    var render by remember { mutableStateOf<MiniPlayerRender?>(null) }
    LaunchedEffect(snapshot, scrubbing) {
        if (snapshot == null) {
            render = null
            return@LaunchedEffect
        }
        while (true) {
            val ahora = System.currentTimeMillis()
            val ajustada = overlay.apply(snapshot.nowPlaying, ahora)
            // Si el overlay fijó una posición (seek optimista), la barra tiene que MOSTRARLA.
            // Extrapolar siempre desde la foto cruda dejaría el seek sin eco visual: tras tocar
            // ±10s la barra no se movería hasta el siguiente poll, hasta 3s de silencio.
            val base = if (ajustada.positionMs != snapshot.nowPlaying.positionMs) {
                com.arkiv.player.remote.TvSnapshot(ajustada, ahora)
            } else {
                snapshot
            }
            render = MiniPlayerRender(
                nowPlaying = ajustada,
                positionMs = com.arkiv.player.remote.ExtrapolatedClock.positionAt(base, ahora, scrubbing),
                stale = com.arkiv.player.remote.ExtrapolatedClock.isStale(
                    snapshot, ahora, com.arkiv.player.remote.ExtrapolatedClock.WARN_MS,
                ),
                hidden = com.arkiv.player.remote.ExtrapolatedClock.isStale(
                    snapshot, ahora, com.arkiv.player.remote.ExtrapolatedClock.HIDE_MS,
                ),
            )
            delay(250)
        }
    }
    return render
}

/**
 * Carátula a mostrar. El TV ya la resuelve de su biblioteca, pero si llegó vacía se busca en la
 * biblioteca local del celu: es el elemento más visible de la barra y vale la doble red.
 */
@Composable
internal fun rememberPoster(nowPlaying: TvNowPlaying): String? {
    val repo = com.arkiv.player.ui.rememberGraph().repository
    val local by androidx.compose.runtime.produceState<String?>(null, nowPlaying.episodeId) {
        if (nowPlaying.posterUrl.isBlank()) {
            value = runCatching { repo.itemThumbnailForEpisode(nowPlaying.episodeId) }.getOrNull()
        }
    }
    return nowPlaying.posterUrl.ifBlank { local.orEmpty() }.ifBlank { null }
}
```

Cambiar además el `AsyncImage` de `MiniPlayerBar` para que use el respaldo:

```kotlin
                model = rememberPoster(nowPlaying),
```

(Los imports del bloque de arriba ya contemplan `mutableStateOf` y `produceState`.)

- [ ] **Step 2: Verificar que Coil está disponible**

Run: `grep -n "coil" app/build.gradle.kts`
Expected: aparece la dependencia de Coil Compose. Si el artefacto importado no es `coil.compose.AsyncImage` (Coil 3 usa `coil3.compose.AsyncImage`), ajustar el import al que ya usan las pantallas existentes:

Run: `grep -rn "AsyncImage" app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`

Usar el mismo import que ahí.

- [ ] **Step 3: Integrar la barra en el `bottomBar`**

En `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`, antes del `Scaffold` (junto a las otras variables derivadas, cerca de la línea 160), agregar:

```kotlin
    val tvSnapshot by graph.tvNowPlaying.state.collectAsStateWithLifecycle()
    val overlay = remember { com.arkiv.player.remote.OptimisticOverlay() }
    // Solo se hace poll con la app al frente.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> graph.tvNowPlaying.setActive(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> graph.tvNowPlaying.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

Y reemplazar el bloque `bottomBar` (líneas 219-243) por:

```kotlin
        bottomBar = {
            Column {
                val render = com.arkiv.player.ui.remote.rememberMiniPlayerRender(
                    snapshot = tvSnapshot,
                    overlay = overlay,
                    scrubbing = false,
                )
                if (isTab && render != null && !render.hidden) {
                    com.arkiv.player.ui.remote.MiniPlayerBar(
                        nowPlaying = render.nowPlaying,
                        positionMs = render.positionMs,
                        stale = render.stale,
                        onCommand = { cmd ->
                            aplicarOptimista(overlay, cmd)
                            scope.launch { graph.remoteController.sendTransport(cmd) }
                        },
                        onExpand = { navController.navigate("nowplaying") },
                    )
                }
                if (isTab) {
                    NavigationBar(containerColor = ArkivBlack) {
                        TABS.forEach { tab ->
                            val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // Entrar al Catálogo desde otra pestaña resetea su búsqueda (el VM
                                    // sobrevive al cambio de pestaña y si no, quedaría mostrando resultados
                                    // viejos con el campo de texto vacío).
                                    if (tab.route == "catalog") graph.catalogResetSignal.tryEmit(Unit)
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
```

Y al final del archivo, fuera del composable, agregar el helper:

```kotlin
/** Refleja el efecto del comando en la UI antes de que el TV lo confirme. */
private fun aplicarOptimista(
    overlay: com.arkiv.player.remote.OptimisticOverlay,
    cmd: com.arkiv.player.remote.TransportCommand,
) {
    val ahora = System.currentTimeMillis()
    when (cmd) {
        com.arkiv.player.remote.TransportCommand.Pause ->
            overlay.expectState(com.arkiv.player.remote.TvPlaybackState.PAUSED, ahora)
        com.arkiv.player.remote.TransportCommand.Resume ->
            overlay.expectState(com.arkiv.player.remote.TvPlaybackState.PLAYING, ahora)
        is com.arkiv.player.remote.TransportCommand.Seek ->
            overlay.expectPosition(cmd.positionMs, ahora)
        // Next/Prev cambian de episodio entero: no hay nada útil que fijar, se espera la foto nueva.
        else -> overlay.clear()
    }
}
```

Agregar los imports que falten al inicio del archivo (`androidx.compose.runtime.DisposableEffect`, `androidx.compose.runtime.remember`, `androidx.compose.foundation.layout.Column`).

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Verificar en el celu**

Instalar en el celu con el TV reproduciendo. Comprobar:
- la barra aparece sobre Inicio/Descargas/Ajustes y **no** en detalle ni en búsqueda;
- el progreso avanza suave, sin saltos cada 3s;
- pausar cambia el ícono al instante y **no** revierte medio segundo después;
- ±10s mueve la reproducción del TV;
- al salir del player en el TV, la barra desaparece en ≤3s.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/remote/MiniPlayerBar.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(ui): barra de miniplayer del TV sobre la navegacion"
```

---

## Task 10: La pantalla "Reproduciendo ahora"

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/remote/NowPlayingScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (ruta nueva en el `NavHost`)

**Interfaces:**
- Consumes: todo lo de la Task 9
- Produces: `NowPlayingScreen(onBack, onOpenRemote)`

- [ ] **Step 1: Implementar la pantalla**

Crear `app/src/main/java/com/arkiv/player/ui/remote/NowPlayingScreen.kt`:

```kotlin
package com.arkiv.player.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arkiv.player.remote.OptimisticOverlay
import com.arkiv.player.remote.TransportCommand
import com.arkiv.player.remote.TvPlaybackState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Control completo de lo que reproduce el TV: slider arrastrable + los mismos cinco controles en grande. */
@Composable
fun NowPlayingScreen(onBack: () -> Unit, onOpenRemote: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snapshot by graph.tvNowPlaying.state.collectAsStateWithLifecycle()
    val overlay = remember { OptimisticOverlay() }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val render = rememberMiniPlayerRender(snapshot, overlay, scrubbing)
    // El TV dejó de reproducir mientras mirábamos esta pantalla: no hay nada que mostrar.
    LaunchedEffect(render == null) { if (render == null) onBack() }
    if (render == null) return

    val visible = render.nowPlaying
    val positionMs = if (scrubbing) scrubValue.toLong() else render.positionMs

    fun enviar(cmd: TransportCommand) {
        val t = System.currentTimeMillis()
        when (cmd) {
            TransportCommand.Pause -> overlay.expectState(TvPlaybackState.PAUSED, t)
            TransportCommand.Resume -> overlay.expectState(TvPlaybackState.PLAYING, t)
            is TransportCommand.Seek -> overlay.expectPosition(cmd.positionMs, t)
            else -> overlay.clear()
        }
        scope.launch { graph.remoteController.sendTransport(cmd) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Text(
                "Reproduciendo en la TV",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        AsyncImage(
            model = rememberPoster(visible),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            visible.title.ifBlank { "Reproduciendo" },
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (visible.subtitle.isNotBlank()) {
            Text(
                visible.subtitle,
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Slider(
            value = positionMs.toFloat(),
            onValueChange = { v -> scrubbing = true; scrubValue = v },
            onValueChangeFinished = {
                scrubbing = false
                enviar(TransportCommand.Seek(scrubValue.toLong()))
            },
            valueRange = 0f..(if (visible.durationMs > 0) visible.durationMs.toFloat() else 1f),
            enabled = visible.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = ArkivRed,
                activeTrackColor = ArkivRed,
                inactiveTrackColor = Color.DarkGray,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(positionMs), color = ArkivTextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(formatTime(visible.durationMs), color = ArkivTextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(Icons.Filled.SkipPrevious, "Capítulo anterior", enabled = visible.hasPrev) {
                enviar(TransportCommand.Prev)
            }
            BigButton(Icons.Filled.Replay10, "Atrasar 10 segundos") {
                enviar(TransportCommand.Seek((positionMs - 10_000).coerceAtLeast(0)))
            }
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (visible.state == TvPlaybackState.BUFFERING) {
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                } else {
                    val playing = visible.state == TvPlaybackState.PLAYING
                    IconButton(
                        onClick = { enviar(if (playing) TransportCommand.Pause else TransportCommand.Resume) },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pausar" else "Reproducir",
                            tint = ArkivRed,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            BigButton(Icons.Filled.Forward10, "Adelantar 10 segundos") {
                val tope = if (visible.durationMs > 0) visible.durationMs else Long.MAX_VALUE
                enviar(TransportCommand.Seek((positionMs + 10_000).coerceAtMost(tope)))
            }
            BigButton(Icons.Filled.SkipNext, "Capítulo siguiente", enabled = visible.hasNext) {
                enviar(TransportCommand.Next)
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onOpenRemote) {
            Icon(Icons.Filled.SettingsRemote, contentDescription = null, tint = ArkivTextSecondary)
            Text("  Control remoto", color = ArkivTextSecondary)
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (enabled) Color.White else Color.DarkGray,
            modifier = Modifier.size(30.dp),
        )
    }
}
```

- [ ] **Step 2: Registrar la ruta**

En `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt`, dentro del `NavHost`, junto a la ruta `"remote"`, agregar:

```kotlin
            composable("nowplaying") {
                com.arkiv.player.ui.remote.NowPlayingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRemote = { navController.navigate("remote") },
                )
            }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verificar en el celu**

Con el TV reproduciendo, tocar la barra. Comprobar:
- se abre la pantalla con póster, título y slider;
- al arrastrar el slider el número **no** pelea con el dedo (la extrapolación se congela);
- al soltar, el TV salta a esa posición;
- "Control remoto" abre la cruceta;
- si el TV deja de reproducir, la pantalla se cierra sola.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/remote/NowPlayingScreen.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(ui): pantalla Reproduciendo ahora con slider y controles grandes"
```

---

## Task 11: Verificación de punta a punta

**Files:** ninguno (solo verificación; si algo falla, se arregla en la tarea correspondiente)

- [ ] **Step 1: Correr toda la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, sin regresiones en los tests que ya existían.

- [ ] **Step 2: Instalar en los dos dispositivos**

Fire Stick y celu, según los runbooks de ADB ya conocidos.

- [ ] **Step 3: Recorrer los caminos que difieren**

Con una **serie de archive**: reproducir en el TV, verificar barra, pausa, ±10s, siguiente y anterior capítulo.

Con un **pack de torrent**: lo mismo. Este camino resuelve la fuente distinto y es donde más probable es que `hasNext`/`hasPrev` o el salto de capítulo fallen.

Con una **película**: verificar que ⏮/⏭ aparecen deshabilitados y no desplazan a los otros controles.

- [ ] **Step 4: Probar los modos degradados**

- Apagar el TV con la barra visible → debe desaparecer en ≤30s.
- Poner el celu en modo avión unos segundos → aparece "Sin conexión", y al volver la red se recupera solo.
- Mandar el celu a background 1 min y volver → la barra se actualiza al instante (poll inmediato al reactivarse).

- [ ] **Step 5: Revisar los logs**

Run: `adb -t 1 logcat -d -s ArkivRemote ArkivPB`
Expected: sin errores repetidos de publicación de `nowPlaying` ni de envío de comandos.

- [ ] **Step 6: Commit final si hubo ajustes**

```bash
git add <archivos ajustados>
git commit -m "fix(remote): ajustes tras la verificacion en dispositivo"
```
