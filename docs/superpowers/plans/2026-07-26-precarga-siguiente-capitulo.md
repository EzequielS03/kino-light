# Precarga del siguiente capítulo — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Precargar el siguiente capítulo de una serie en segundo plano (torrent pack, web pre-resolve, archive) para que al pasar arranque al instante.

**Architecture:** Un coordinador en `PlayerViewModel` calcula el siguiente episodio (`repo.nextEpisode`) y dispara un prefetch cancelable por tipo de fuente, siempre en baja prioridad. Torrent: prioridad BAJA de la cabeza del próximo archivo en el mismo handle. Web: pre-resolve (calienta la caché del resolver). Archive: Range-GET de la cabeza.

**Tech Stack:** Kotlin/Compose, libtorrent4j 2.1.0-31, OkHttp.

**Spec:** `docs/superpowers/specs/2026-07-26-precarga-siguiente-capitulo-design.md`

## Global Constraints

- **Git:** identidad `lordmacu`, sin `Co-Authored-By`.
- **Best-effort:** el prefetch NUNCA bloquea ni tira error a la reproducción actual (todo en `runCatching`, timeouts cortos, baja prioridad).
- **Solo N+1** (no N+2). **Cancelable:** un `prefetchJob` que se cancela al cargar otro episodio.
- `repo.nextEpisode(episodeId)` YA existe (siguiente de la misma sección) — reusarlo, no reescribirlo.
- Torrent v1 = solo PACK (mismo torrent). Series por-capítulo (2º torrent) = fuera de alcance.

---

### Task 1: `preBufferNextFile` en TorrentEngine (+ helper puro de rango de piezas)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt`
- Test: `app/src/test/java/com/arkiv/player/torrent/HeadPieceRangeTest.kt` (crear)

**Interfaces:**
- Produces: `TorrentEngine.computeHeadPieceRange(pieceLen, fileOffset, fileSize, numPieces, headBytes): IntRange` (puro, internal/companion) y `TorrentEngine.preBufferNextFile(fileIndex: Int)` (usa el handle actual).

- [ ] **Step 1: Escribir el test del cálculo puro** (`HeadPieceRangeTest.kt`)

```kotlin
package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadPieceRangeTest {
    @Test fun `cabeza arranca en la pieza del offset y cubre headBytes`() {
        // pieceLen=1MB, archivo en offset 100MB, size 50MB, header 16MB → 16 piezas desde la 100.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 1L * 1024 * 1024, fileOffset = 100L * 1024 * 1024,
            fileSize = 50L * 1024 * 1024, numPieces = 1000, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(100, r.first)
        assertEquals(115, r.last) // 100 + 16 - 1
    }

    @Test fun `no pasa del fin del archivo ni del total de piezas`() {
        // archivo chico (2MB) → la cabeza no excede su última pieza.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 1L * 1024 * 1024, fileOffset = 0L, fileSize = 2L * 1024 * 1024,
            numPieces = 5, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(0, r.first)
        assertEquals(1, r.last) // fileEnd = 2MB-1 → pieza 1
    }

    @Test fun `pieceLen invalido devuelve rango vacio`() {
        val r = TorrentEngine.computeHeadPieceRange(0L, 0L, 10L, 10, 16L)
        assertEquals(true, r.isEmpty())
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.torrent.HeadPieceRangeTest"`
Expected: FAIL — `computeHeadPieceRange` no existe.

- [ ] **Step 3: Implementar el helper puro + `preBufferNextFile`**

En `TorrentEngine`, agregar al `companion object` (o como `internal` en el archivo) el helper puro:

```kotlin
/** Rango de piezas de CABEZA de un archivo (para pre-priorizar el próximo episodio del pack). Puro. */
fun computeHeadPieceRange(pieceLen: Long, fileOffset: Long, fileSize: Long, numPieces: Int, headBytes: Long): IntRange {
    if (pieceLen <= 0L || fileSize <= 0L || numPieces <= 0) return IntRange.EMPTY
    val firstPiece = (fileOffset / pieceLen).toInt()
    val headWin = (headBytes / pieceLen).toInt().coerceIn(1, 16)
    val fileEnd = fileOffset + fileSize - 1
    val lastPiece = (fileEnd / pieceLen).toInt().coerceAtMost(numPieces - 1)
    val headEnd = (firstPiece + headWin - 1).coerceAtMost(lastPiece)
    return firstPiece..headEnd
}
```

Y el método que usa el handle actual (junto a `beginServing`):

```kotlin
/** Pre-buffer del PRÓXIMO archivo del pack (mismo torrent): pone su cabeza en prioridad BAJA para que
 * baje con banda sobrante SIN robarle a las TOP del archivo en curso. Best-effort. */
fun preBufferNextFile(fileIndex: Int) {
    val handle = currentHandle?.takeIf { it.isValid } ?: return
    val info = runCatching { handle.torrentFile() }.getOrNull() ?: return
    if (fileIndex < 0 || fileIndex >= info.numFiles()) return
    val fs = info.files()
    val pieceLen = info.pieceLength().toLong()
    val range = computeHeadPieceRange(pieceLen, fs.fileOffset(fileIndex), fs.fileSize(fileIndex), info.numPieces(), HEADER_BYTES)
    if (range.isEmpty()) return
    runCatching {
        // Solo SUBIR de IGNORE→LOW: nunca bajar una pieza que ya esté TOP (solape con la cola del actual).
        for (p in range) if (handle.piecePriority(p) == org.libtorrent4j.Priority.IGNORE) {
            handle.piecePriority(p, org.libtorrent4j.Priority.LOW)
        }
    }
    Log.i("ArkivTorrent", "preBuffer próximo file=$fileIndex piezas=${range.first}..${range.last} (LOW)")
}
```

(Si `HEADER_BYTES` es `private const` en el companion, ya es accesible desde el método. `Priority`/`Log` ya están importados en el archivo.)

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.torrent.HeadPieceRangeTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt app/src/test/java/com/arkiv/player/torrent/HeadPieceRangeTest.kt
git commit -m "feat(torrent): preBufferNextFile — cabeza del próximo archivo del pack en prioridad baja + helper puro testeado"
```

---

### Task 2: Coordinador de prefetch en PlayerViewModel

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`

**Interfaces:**
- Consumes: `repo.nextEpisode(id)`, `repo.torrentSourceForEpisode(id)` (→ `EpisodeTorrent.Bytes(bytes, fileIndex)`), `repo.webSourceForEpisode(id)`, `repo.getSkipMarker`, `repo.completedDownloadUri`, `buildData(ep, uri, marker)` (existente, para archive), `torrentEngine.preBufferNextFile`, `webResolverApi.resolve`, `PlayerSource.kindFor`.
- Produces: efecto secundario (prefetch); ningún símbolo público nuevo salvo el manejo interno de `prefetchJob`.

- [ ] **Step 1: Agregar el campo del job cancelable** (junto a los otros `private var` de la clase)

```kotlin
private var prefetchJob: kotlinx.coroutines.Job? = null
```

- [ ] **Step 2: Disparar el prefetch al final de `load`** — en `fun load(episodeId)`, tras el `when` que ramifica, cancelar el anterior y lanzar el nuevo:

```kotlin
    fun load(episodeId: String) {
        viewModelScope.launch {
            _error.value = null
            val kind = PlayerSource.kindFor(episodeId)
            Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
            when (kind) {
                SourceKind.TORRENT -> loadTorrent(episodeId)
                SourceKind.ARCHIVE -> loadArchive(episodeId)
                SourceKind.WEB -> loadWeb(episodeId)
            }
        }
        // Precarga del siguiente capítulo (best-effort, cancelable, baja prioridad).
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) { prefetchNext(episodeId) }
    }
```

- [ ] **Step 3: Implementar `prefetchNext`** (nuevo método privado)

```kotlin
    /** Precarga el SIGUIENTE episodio de la serie en segundo plano (torrent pack / web / archive). Best-effort. */
    private suspend fun prefetchNext(currentId: String) = runCatching {
        // Colchón: dejar que el actual arranque primero (torrent va en baja prioridad, no compite igual).
        kotlinx.coroutines.delay(PREFETCH_DELAY_MS)
        val next = repo.nextEpisode(currentId) ?: return@runCatching
        when (PlayerSource.kindFor(next.id)) {
            // Torrent PACK: si el actual es torrent (mismo pack) y el próximo tiene fileIndex → pre-buffer.
            SourceKind.TORRENT -> {
                if (PlayerSource.kindFor(currentId) != SourceKind.TORRENT) return@runCatching
                val src = repo.torrentSourceForEpisode(next.id)
                if (src is EpisodeTorrent.Bytes) {
                    Log.w(PLAY, "prefetch torrent: próximo file=${src.fileIndex}")
                    torrentEngine.preBufferNextFile(src.fileIndex)
                }
            }
            // Web: pre-resolver (calienta la caché del resolver). No si el actual sigue resolviendo.
            SourceKind.WEB -> {
                if (_resolving.value) return@runCatching
                val pageUrl = repo.webSourceForEpisode(next.id)
                if (!pageUrl.isNullOrBlank()) {
                    Log.w(PLAY, "prefetch web: pre-resolviendo próximo")
                    webResolverApi.resolve(pageUrl)   // ignora el resultado; queda en caché del resolver
                }
            }
            // Archive: calentar la cabeza (Range-GET de los primeros MB de la URL del próximo).
            SourceKind.ARCHIVE -> {
                val ep = repo.getEpisode(next.id) ?: return@runCatching
                val marker = repo.getSkipMarker(ep.itemId)
                val url = buildData(ep, repo.completedDownloadUri(ep.id), marker)?.mediaUrl ?: return@runCatching
                Log.w(PLAY, "prefetch archive: calentando cabeza")
                warmHead(url)
            }
        }
    }

    /** GET con Range de los primeros MB (best-effort, timeout corto) para calentar conexión/CDN. */
    private fun warmHead(url: String) {
        runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-3145727").get().build() // 3 MB
            client.newCall(req).execute().use { it.body?.byteStream()?.readNBytes(3 * 1024 * 1024) }
        }
    }
```

Agregar la constante al companion existente:

```kotlin
        /** Colchón antes de precargar el próximo capítulo (dar aire al arranque del actual). */
        const val PREFETCH_DELAY_MS = 8_000L
```

Verificar que estén los imports: `kotlinx.coroutines.Dispatchers` (si no está, agregarlo), `EpisodeTorrent` (ya usado en `resolveTorrentUrl`). `okhttp3` se referencia con FQN inline (no requiere import).

- [ ] **Step 4: Cancelar el prefetch al limpiar** — en el `onCleared()` de la ViewModel (o donde se liberen recursos), agregar `prefetchJob?.cancel()`. Si no existe `onCleared`, agregarlo:

```kotlin
    override fun onCleared() {
        prefetchJob?.cancel()
        super.onCleared()
    }
```

- [ ] **Step 5: Compilar y correr los tests de la capa player**

Run: `./gradlew :app:assembleDebug -q` y `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.*"`
Expected: compila; sin regresiones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt
git commit -m "feat(player): coordinador de precarga del siguiente capítulo (torrent pack / web pre-resolve / archive), cancelable y best-effort"
```

---

### Task 3: Validación en device (controlador)

- [ ] **Step 1: Build + install**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Torrent pack** — reproducir un episodio de un pack agregado; en `adb logcat -s ArkivTorrent ArkivPlay` ver `prefetch torrent: próximo file=…` y `preBuffer próximo file=…`. Pasar al siguiente episodio → el gate (`ArkivGate GATE PASSED`) debe pasar casi instantáneo (cabeza ya bajada).

- [ ] **Step 3: Web** — reproducir un episodio web de una serie; ver `prefetch web: pre-resolviendo próximo` y, en el resolver de blog, que quede cacheado. Pasar al siguiente → el resolve debe ser cache-hit (arranque sin la espera de ~15–60s).

- [ ] **Step 4: Archive** — reproducir un episodio archive; ver `prefetch archive: calentando cabeza`. Confirmar que el siguiente abre fluido (ya venía en playlist; el warm es un extra).

- [ ] **Step 5: Cancelación** — cambiar rápido entre episodios; confirmar que no se acumulan prefetch (cada `load` cancela el anterior).

---

## Self-Review

- **Cobertura del spec:** coordinador + nextEpisode (reuso) = Task 2; torrent pack (preBufferNextFile) = Task 1; web pre-resolve + archive warm = Task 2; validación = Task 3. ✓
- **Placeholders:** ninguno — helper puro, método del engine, coordinador y warm-head completos. ✓
- **Consistencia de tipos:** `computeHeadPieceRange(...): IntRange` definido en Task 1 y usado en `preBufferNextFile`. `preBufferNextFile(Int)` usado en Task 2. `EpisodeTorrent.Bytes(bytes, fileIndex)` y `buildData(ep,uri,marker)` existentes. ✓
- **Riesgo:** el pre-buffer torrent no roba banda (LOW < TOP, incremental). Web no encola si el actual sigue resolviendo (`_resolving`). Todo best-effort/cancelable.
