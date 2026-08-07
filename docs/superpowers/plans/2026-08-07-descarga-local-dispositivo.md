# Descarga local al dispositivo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que Arkiv pueda guardar películas y anime en el propio dispositivo desde búsqueda y
librería, para las tres fuentes (torrent, web, archive.org), y que al dar play reproduzca el archivo
local en vez del streaming.

**Architecture:** Una tabla (`downloads`, extendida), una cola procesada por un único worker de
WorkManager, y tres estrategias de descarga intercambiables detrás de una interfaz. El worker no sabe
cómo baja cada fuente; ninguna estrategia sabe que existe una cola. La reproducción cambia en un solo
punto: `PlayerViewModel.load()` consulta el archivo local antes de ramificar por fuente.

**Tech Stack:** Kotlin, Jetpack Compose, Room 16→17, WorkManager 2.9.1, OkHttp 4.12, libtorrent4j,
JUnit 4 + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-08-07-descarga-local-dispositivo-design.md`

## Global Constraints

- **Tests JVM puros.** El proyecto NO tiene Robolectric (`app/build.gradle.kts:161-166`: junit,
  org.json, mockwebserver). Todo lo que se testea debe estar en clases sin dependencias del framework
  de Android. Si una clase necesita `Context`, la lógica testeable va extraída aparte.
- **Room:** versión actual 16. Este plan la lleva a **17**. Las migraciones se agregan a
  `addMigrations(...)` en `ArkivDatabase.kt:275`. Nunca borrar migraciones existentes.
- **`variant` sigue siendo `String` NOT NULL.** Se usa `""` para torrent y web. SQLite no puede
  cambiar la nulabilidad de una columna con `ALTER TABLE`, y hacerlo exigiría reconstruir la tabla:
  no vale la pena por un campo que solo usa archive.
- **Git:** identidad `lordmacu` (ya configurada en el repo). **Nunca** `Co-Authored-By`. **Nunca**
  `git add -A` ni `git commit -a` — hay otras sesiones trabajando sobre el mismo working tree;
  agregar siempre los archivos por ruta explícita.
- **Umbral de aviso de torrent:** `5L * 1024 * 1024 * 1024` bytes. No confundir con
  `settings.maxTorrentSizeGb` (21 GB por defecto), que es un filtro de resultados de búsqueda.
- **Destino de los archivos:** `context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)`.
- **Build:** `./gradlew :app:testDebugUnitTest` para tests; `./gradlew :app:assembleDebug` para
  compilar.

## Estructura de archivos

**Paquete nuevo `com.arkiv.player.data.local`** — todo lo de descarga local vive junto. El paquete
viejo `data.download` desaparece al final de la fase 1.

| Archivo | Responsabilidad |
|---|---|
| `data/local/LocalDownloadState.kt` | constantes de estado + política de la cola (puro) |
| `data/local/TorrentSizeGate.kt` | compuerta de 5 GB (puro) |
| `data/local/LocalFilePaths.kt` | nombres y rutas de los archivos destino (puro) |
| `data/local/HttpRangeDownloader.kt` | descarga HTTP reanudable a `.part` + rename |
| `data/local/DownloadStrategy.kt` | interfaz + `DownloadOutcome` |
| `data/local/ArchiveDownloadStrategy.kt` | archive.org |
| `data/local/TorrentDownloadStrategy.kt` | torrent |
| `data/local/StagingProgress.kt` | mapeo de progreso staging/transferencia (puro, fase 2) |
| `data/local/NucStagedStrategy.kt` | web vía NUC (fase 2) |
| `data/local/LocalDownloadManager.kt` | fachada para la UI |
| `data/local/LocalLibrary.kt` | consulta de archivo local para el player |
| `data/local/LocalDownloadWorker.kt` | worker de la cola |
| `playback/LocalFileServer.kt` | HTTP local con Range para cast/DLNA |
| `torrent/PersistentTorrentDownload.kt` | handle de descarga persistente |

**Modificados:** `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/ArkivDatabase.kt`,
`torrent/TorrentEngine.kt`, `playback/PlayerSource.kt`, `ui/player/PlayerViewModel.kt`,
`ui/downloads/DownloadsScreen.kt`, `ui/downloads/DownloadsViewModel.kt`,
`ui/catalog/CineDetailScreen.kt`, `ui/catalog/AnimeShowDetailScreen.kt`, `ui/search/SearchScreen.kt`,
`ui/library/LibraryScreen.kt`, `ui/ArkivRoot.kt`, `AppGraph.kt`, `ArkivApp.kt`.

**Borrados (fase 1, última tarea):** `data/download/Downloader.kt`.

---

# FASE 1 — cola, torrent, archive, UI

## Task 1: Estados de la cola y política de selección

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/LocalDownloadState.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/DownloadQueuePolicyTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `LocalDownloadState` (constantes `QUEUED`, `NEEDS_CONFIRMATION`, `STAGING`,
  `DOWNLOADING`, `COMPLETED`, `FAILED`), `QueueRow(episodeId: String, state: String, createdAt: Long)`,
  `DownloadQueuePolicy.nextToProcess(rows: List<QueueRow>): QueueRow?`,
  `DownloadQueuePolicy.isTerminal(state: String): Boolean`,
  `DownloadQueuePolicy.isRetryable(state: String): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/arkiv/player/data/local/DownloadQueuePolicyTest.kt`:

```kotlin
package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {

    @Test
    fun `toma la fila encolada mas vieja`() {
        val rows = listOf(
            QueueRow("b", LocalDownloadState.QUEUED, createdAt = 200),
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("c", LocalDownloadState.QUEUED, createdAt = 300),
        )
        assertEquals("a", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `una fila a medio bajar tiene prioridad sobre las encoladas`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.DOWNLOADING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `staging tambien se retoma antes que lo encolado`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.STAGING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `no toma completadas fallidas ni pendientes de confirmacion`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.COMPLETED, createdAt = 100),
            QueueRow("b", LocalDownloadState.FAILED, createdAt = 200),
            QueueRow("c", LocalDownloadState.NEEDS_CONFIRMATION, createdAt = 300),
        )
        assertNull(DownloadQueuePolicy.nextToProcess(rows))
    }

    @Test
    fun `cola vacia no da nada`() {
        assertNull(DownloadQueuePolicy.nextToProcess(emptyList()))
    }

    @Test
    fun `estados terminales`() {
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.COMPLETED))
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.FAILED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.QUEUED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.DOWNLOADING))
    }

    @Test
    fun `solo falla y pendiente de confirmacion se pueden reintentar`() {
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.FAILED))
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.NEEDS_CONFIRMATION))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.COMPLETED))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.QUEUED))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*DownloadQueuePolicyTest*"`
Expected: FALLA al compilar — `Unresolved reference: LocalDownloadState`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/arkiv/player/data/local/LocalDownloadState.kt`:

```kotlin
package com.arkiv.player.data.local

/**
 * Estados de una fila de la tabla `downloads`. Son strings y no un enum porque Room ya los guarda
 * así desde la primera versión de la tabla y cambiarlo obligaría a un converter + migración de datos
 * sin ganar nada.
 */
object LocalDownloadState {
    const val QUEUED = "queued"
    /** Torrent que supera el umbral de tamaño: espera confirmación del usuario, no baja nada. */
    const val NEEDS_CONFIRMATION = "needs_confirmation"
    /** Solo web: la NUC está bajando el archivo, todavía no empezó la transferencia al dispositivo. */
    const val STAGING = "staging"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

/** Fila mínima de la cola: lo único que la política necesita para decidir. */
data class QueueRow(val episodeId: String, val state: String, val createdAt: Long)

/**
 * Decide qué fila procesa el worker. La cola es de UNA a la vez (ver el spec: `TorrentEngine` es de
 * un stream activo, el disco de blog no aguanta varios staging, y el ancho de banda del Fire TV no
 * sobra), así que esto devuelve una sola fila o null.
 */
object DownloadQueuePolicy {

    /**
     * Lo ya empezado (`downloading` / `staging`) gana sobre lo encolado: si la app se mató a mitad de
     * una descarga de 4 GB, retomarla vale más que arrancar otra desde cero. Entre iguales, la más
     * vieja primero (FIFO).
     */
    fun nextToProcess(rows: List<QueueRow>): QueueRow? {
        val inFlight = rows.filter { it.state == LocalDownloadState.DOWNLOADING || it.state == LocalDownloadState.STAGING }
        if (inFlight.isNotEmpty()) return inFlight.minByOrNull { it.createdAt }
        return rows.filter { it.state == LocalDownloadState.QUEUED }.minByOrNull { it.createdAt }
    }

    fun isTerminal(state: String): Boolean =
        state == LocalDownloadState.COMPLETED || state == LocalDownloadState.FAILED

    fun isRetryable(state: String): Boolean =
        state == LocalDownloadState.FAILED || state == LocalDownloadState.NEEDS_CONFIRMATION
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*DownloadQueuePolicyTest*"`
Expected: PASA (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/LocalDownloadState.kt app/src/test/java/com/arkiv/player/data/local/DownloadQueuePolicyTest.kt
git commit -m "feat(descargas): estados y politica de la cola local"
```

---

## Task 2: Compuerta de tamaño para torrents

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/TorrentSizeGate.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/TorrentSizeGateTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `TorrentSizeGate.WARN_TORRENT_SIZE_BYTES: Long`,
  `TorrentSizeGate.needsConfirmation(fileSizeBytes: Long, alreadyConfirmed: Boolean): Boolean`,
  `TorrentSizeGate.formatSize(bytes: Long): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/arkiv/player/data/local/TorrentSizeGateTest.kt`:

```kotlin
package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSizeGateTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `un archivo de 6 GB pide confirmacion`() {
        assertTrue(TorrentSizeGate.needsConfirmation(6 * gb, alreadyConfirmed = false))
    }

    @Test
    fun `un archivo de 1 punto 2 GB no pide confirmacion`() {
        val size = (1.2 * gb).toLong()
        assertFalse(TorrentSizeGate.needsConfirmation(size, alreadyConfirmed = false))
    }

    /**
     * El caso que motiva que la compuerta viva en el worker y no al encolar: TorrentResult.sizeBytes
     * es el peso del PACK entero. Acá se compara contra el archivo elegido, que es lo que se baja.
     */
    @Test
    fun `un pack de 30 GB cuyo capitulo pesa 1 punto 2 GB no pide confirmacion`() {
        val fileSize = (1.2 * gb).toLong()
        assertFalse(TorrentSizeGate.needsConfirmation(fileSize, alreadyConfirmed = false))
    }

    @Test
    fun `exactamente 5 GB no dispara`() {
        assertFalse(TorrentSizeGate.needsConfirmation(5 * gb, alreadyConfirmed = false))
    }

    @Test
    fun `un byte por encima de 5 GB dispara`() {
        assertTrue(TorrentSizeGate.needsConfirmation(5 * gb + 1, alreadyConfirmed = false))
    }

    @Test
    fun `una vez confirmada no vuelve a disparar`() {
        assertFalse(TorrentSizeGate.needsConfirmation(20 * gb, alreadyConfirmed = true))
    }

    @Test
    fun `tamano desconocido no dispara`() {
        assertFalse(TorrentSizeGate.needsConfirmation(0, alreadyConfirmed = false))
        assertFalse(TorrentSizeGate.needsConfirmation(-1, alreadyConfirmed = false))
    }

    @Test
    fun `formatea el tamano para mostrarlo`() {
        assertEquals("8.4 GB", TorrentSizeGate.formatSize((8.4 * gb).toLong()))
        assertEquals("700 MB", TorrentSizeGate.formatSize(700L * 1024 * 1024))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*TorrentSizeGateTest*"`
Expected: FALLA — `Unresolved reference: TorrentSizeGate`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/arkiv/player/data/local/TorrentSizeGate.kt`:

```kotlin
package com.arkiv.player.data.local

import java.util.Locale

/**
 * Aviso antes de bajar un torrent pesado.
 *
 * La comparación es SIEMPRE contra el tamaño del archivo que se va a descargar, nunca contra el del
 * torrent completo: `TorrentResult.sizeBytes` es el peso del pack, y usarlo haría que un pack de
 * temporada de 30 GB con capítulos de 1,2 GB avisara en falso en cada capítulo. Por eso la compuerta
 * corre en el worker, después de resolver la metadata, que es el primer momento en que se conoce el
 * tamaño real del archivo elegido.
 *
 * NO confundir con `SettingsStore.maxTorrentSizeGb` (21 GB por defecto): ese es un filtro que se
 * aplica a los RESULTADOS DE BÚSQUEDA vía `MirrorFilter`, no un aviso de descarga. Los dos valores
 * conviven sin pisarse.
 */
object TorrentSizeGate {

    const val WARN_TORRENT_SIZE_BYTES = 5L * 1024 * 1024 * 1024

    /**
     * [fileSizeBytes] <= 0 significa "no se pudo determinar" y NO dispara: bloquear una descarga por
     * un dato que no tenemos sería peor que dejarla correr.
     */
    fun needsConfirmation(fileSizeBytes: Long, alreadyConfirmed: Boolean): Boolean {
        if (alreadyConfirmed) return false
        if (fileSizeBytes <= 0) return false
        return fileSizeBytes > WARN_TORRENT_SIZE_BYTES
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*TorrentSizeGateTest*"`
Expected: PASA (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/TorrentSizeGate.kt app/src/test/java/com/arkiv/player/data/local/TorrentSizeGateTest.kt
git commit -m "feat(descargas): compuerta de aviso para torrents de mas de 5 GB"
```

---

## Task 3: Rutas y nombres de los archivos destino

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/LocalFilePaths.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/LocalFilePathsTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `LocalFilePaths.sanitize(id: String): String`,
  `LocalFilePaths.fileNameFor(episodeId: String, sourceName: String?): String`,
  `LocalFilePaths.partOf(file: File): File`,
  `LocalFilePaths.torrentDirName(episodeId: String): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/arkiv/player/data/local/LocalFilePathsTest.kt`:

```kotlin
package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalFilePathsTest {

    @Test
    fun `sanitiza caracteres que no valen en un nombre de archivo`() {
        assertEquals("web_series_123__s01e02", LocalFilePaths.sanitize("web:series:123::s01e02"))
    }

    @Test
    fun `conserva letras numeros punto guion y guion bajo`() {
        assertEquals("Show.S01E02-1080p_x265", LocalFilePaths.sanitize("Show.S01E02-1080p_x265"))
    }

    @Test
    fun `toma la extension del nombre de origen`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie S01E01 1080p.mkv"))
    }

    @Test
    fun `cae a mp4 si el origen no tiene extension`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "sin extension"))
    }

    @Test
    fun `cae a mp4 si no hay nombre de origen`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", null))
    }

    /** Una "extensión" larga es parte del título, no una extensión (ej. "Peli 2024.Latino"). */
    @Test
    fun `ignora una extension implausible y cae a mp4`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "Peli 2024.Latino"))
    }

    @Test
    fun `normaliza la extension a minusculas`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie.MKV"))
    }

    @Test
    fun `el parcial agrega punto part`() {
        assertEquals("ep1.mkv.part", LocalFilePaths.partOf(File("/tmp/ep1.mkv")).name)
    }

    @Test
    fun `el directorio de torrent usa el episodeId sanitizado`() {
        assertEquals("web_series_9__s01e01", LocalFilePaths.torrentDirName("web:series:9::s01e01"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalFilePathsTest*"`
Expected: FALLA — `Unresolved reference: LocalFilePaths`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/arkiv/player/data/local/LocalFilePaths.kt`:

```kotlin
package com.arkiv.player.data.local

import java.io.File

/**
 * Nombres y rutas de los archivos guardados en el dispositivo. Puro a propósito (no toca `Context`)
 * para poder testearlo sin Robolectric: quien conoce el directorio raíz es
 * `LocalDownloadManager`, que lo saca de `getExternalFilesDir(DIRECTORY_MOVIES)`.
 */
object LocalFilePaths {

    /** Extensiones de video plausibles. Todo lo demás después de un punto es parte del título. */
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "mpg", "mpeg", "ogv", "wmv")

    private const val DEFAULT_EXT = "mp4"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * El nombre destino es el `episodeId` sanitizado + la extensión del origen. Se usa el episodeId y
     * no el título del release porque es la clave con la que después se busca el archivo al dar play,
     * y así el mapeo es directo sin depender de la tabla.
     */
    fun fileNameFor(episodeId: String, sourceName: String?): String {
        val ext = sourceName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in VIDEO_EXT } ?: DEFAULT_EXT
        return "${sanitize(episodeId)}.$ext"
    }

    /** Archivo parcial: se escribe acá y se renombra al final, para que nunca exista un destino a medias. */
    fun partOf(file: File): File = File(file.parentFile, file.name + ".part")

    /** Directorio propio de cada descarga de torrent (libtorrent necesita un savePath por torrent). */
    fun torrentDirName(episodeId: String): String = sanitize(episodeId)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalFilePathsTest*"`
Expected: PASA (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/LocalFilePaths.kt app/src/test/java/com/arkiv/player/data/local/LocalFilePathsTest.kt
git commit -m "feat(descargas): rutas y nombres de archivos locales"
```

---

## Task 4: Migración Room 16→17 y DAO

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt:122-130`
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt:169-240`
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt:24` y `:275`

**Interfaces:**
- Consumes: `LocalDownloadState` (Task 1).
- Produces: `DownloadEntity` con los campos nuevos (`source`, `filePath`, `bytesDone`,
  `stagingItemId`, `error`, `createdAt`, `sizeConfirmed`); `DownloadRow` con `source`, `error`,
  `bytesDone`; `DownloadDao.observeAll(): Flow<List<DownloadEntity>>`,
  `DownloadDao.updateState(episodeId, state, error)`,
  `DownloadDao.updateBytes(episodeId, state, progress, bytesDone, bytes)`,
  `DownloadDao.markCompleted(episodeId, filePath)`,
  `DownloadDao.markConfirmed(episodeId)`,
  `DownloadDao.setStagingItem(episodeId, stagingItemId)`,
  `DownloadDao.orphanStagingItems(): List<Long>`.

- [ ] **Step 1: Escribir la entidad y el DAO**

En `Entities.kt`, reemplazar el bloque `DownloadEntity` (líneas 122-130) por:

```kotlin
/**
 * Una descarga al almacenamiento del PROPIO dispositivo. Sirve a las tres fuentes: `source`
 * distingue archive.org, torrent y web. NO confundir con [NucLibraryItemEntity], que es la caché de
 * lo que vive en la NUC.
 *
 * `variant` sigue siendo NOT NULL (y vale `""` para torrent y web) porque SQLite no puede cambiar la
 * nulabilidad de una columna con ALTER TABLE y reconstruir la tabla no se justifica por un campo que
 * solo usa archive.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val variant: String,              // archive: "original" | "derivative"; torrent/web: ""
    val state: String,                // ver LocalDownloadState
    val progress: Float,              // 0..1
    val localUri: String?,            // histórico: file:// que dejó el DownloadManager del sistema
    val bytes: Long,                  // tamaño total conocido (0 si aún no se sabe)
    val source: String = "archive",   // "archive" | "torrent" | "web"
    val filePath: String? = null,     // ruta absoluta del archivo final
    val bytesDone: Long = 0,
    val stagingItemId: Long? = null,  // web: item de la NUC mientras es paso intermedio
    val error: String? = null,
    val createdAt: Long = 0,
    val sizeConfirmed: Boolean = false, // el usuario ya aceptó la compuerta de tamaño
)
```

En `Daos.kt`, reemplazar `DownloadRow` (líneas 169-180) por:

```kotlin
/** Descarga combinada con datos del episodio para mostrar en pantalla. */
data class DownloadRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    val state: String,
    val progress: Float,
    val localUri: String?,
    val bytes: Long,
    val source: String,
    val error: String?,
    val bytesDone: Long,
)
```

En `Daos.kt`, reemplazar el cuerpo de `DownloadDao` (desde `@Dao interface DownloadDao {` hasta su
cierre) por:

```kotlin
@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE episodeId = :episodeId")
    suspend fun updateState(episodeId: String, state: String, error: String?)

    @Query(
        "UPDATE downloads SET state = :state, progress = :progress, bytesDone = :bytesDone, bytes = :bytes " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun updateBytes(episodeId: String, state: String, progress: Float, bytesDone: Long, bytes: Long)

    @Query(
        "UPDATE downloads SET state = 'completed', progress = 1.0, filePath = :filePath, error = NULL " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun markCompleted(episodeId: String, filePath: String)

    @Query("UPDATE downloads SET sizeConfirmed = 1, state = 'queued', error = NULL WHERE episodeId = :episodeId")
    suspend fun markConfirmed(episodeId: String)

    @Query("UPDATE downloads SET stagingItemId = :stagingItemId WHERE episodeId = :episodeId")
    suspend fun setStagingItem(episodeId: String, stagingItemId: Long?)

    /**
     * Items de la NUC que quedaron colgados: la fila ya terminó de bajar al dispositivo pero el
     * DELETE /library falló. El barrido de arranque los reintenta.
     */
    @Query("SELECT stagingItemId FROM downloads WHERE stagingItemId IS NOT NULL AND state = 'completed'")
    suspend fun orphanStagingItems(): List<Long>

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    @Query(
        """
        SELECT d.episodeId AS episodeId, e.itemId AS itemId, i.title AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               d.state AS state, d.progress AS progress, d.localUri AS localUri, d.bytes AS bytes,
               d.source AS source, d.error AS error, d.bytesDone AS bytesDone
        FROM downloads d
        JOIN episodes e ON e.id = d.episodeId
        JOIN items i ON i.identifier = e.itemId
        ORDER BY d.createdAt DESC
        """
    )
    fun observeDownloadRows(): Flow<List<DownloadRow>>
}
```

> Si la firma de `observeDownloadRows()` en el archivo actual difiere (nombre o tipo de retorno),
> conservar la que ya está y solo agregarle las tres columnas nuevas al SELECT.

En `ArkivDatabase.kt` cambiar `version = 16` por `version = 17` y agregar la migración antes de
`fun get(context: Context)`:

```kotlin
        /**
         * v16 -> v17: la tabla `downloads` deja de ser exclusiva de archive.org y pasa a servir a las
         * tres fuentes (archive, torrent, web).
         *
         * `source` va con DEFAULT 'archive' a propósito: todas las filas que ya existen vienen del
         * único camino que había, así que ese default las clasifica bien sin tocar datos.
         *
         * `filePath` va NULL sin DEFAULT: las filas viejas guardaron la ruta como un `file://` en
         * `localUri` (lo que devolvía el DownloadManager del sistema). Inventarles un filePath las
         * rompería; con NULL, `LocalLibrary` cae a `localUri` y lo ya descargado sigue reproduciéndose.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN source TEXT NOT NULL DEFAULT 'archive'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN stagingItemId INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN sizeConfirmed INTEGER NOT NULL DEFAULT 0")
            }
        }
```

Y agregarla al final de la lista de `addMigrations(...)`: `..., MIGRATION_15_16, MIGRATION_16_17)`.

- [ ] **Step 2: Compilar para verificar que Room acepta el esquema**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Si Room se queja de que el esquema no coincide con la migración, revisar
que cada columna nueva de la entidad tenga su `ALTER TABLE` con el mismo tipo y nulabilidad.

> **Nota de entorno:** si el build falla con un error de KSP/Room sobre generación de Kotlin, el
> proyecto ya tiene registrado el flag `room.generateKotlin` como fix conocido para esa combinación
> de JDK — verificar que siga presente en `app/build.gradle.kts` antes de investigar otra cosa.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Entities.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt
git commit -m "feat(descargas): tabla downloads para las tres fuentes (migracion 16-17)"
```

---

## Task 5: Descargador HTTP reanudable

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/HttpRangeDownloader.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/HttpRangeDownloaderTest.kt`

**Interfaces:**
- Consumes: `LocalFilePaths` (Task 3).
- Produces: `HttpRangeDownloader(client: OkHttpClient)` con
  `suspend fun download(url: String, target: File, headers: Map<String, String>, onProgress: (Long, Long) -> Unit): Result<File>`;
  `RangeMath.rangeHeaderFor(existingBytes: Long): String?`,
  `RangeMath.totalBytesOf(contentLength: Long, startByte: Long): Long`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/arkiv/player/data/local/HttpRangeDownloaderTest.kt`:

```kotlin
package com.arkiv.player.data.local

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpRangeDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpRangeDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = HttpRangeDownloader(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `sin parcial previo no manda Range`() {
        assertNull(RangeMath.rangeHeaderFor(0))
    }

    @Test
    fun `con parcial previo pide desde donde quedo`() {
        assertEquals("bytes=1024-", RangeMath.rangeHeaderFor(1024))
    }

    @Test
    fun `el total es lo que falta mas lo ya escrito`() {
        assertEquals(5000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 1000))
        assertEquals(4000, RangeMath.totalBytesOf(contentLength = 4000, startByte = 0))
    }

    @Test
    fun `descarga completa y renombra el parcial`() = runBlocking {
        val body = "0123456789".repeat(100)   // 1000 bytes
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8(body)))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals(1000, target.length())
        assertEquals(body, target.readText())
        assertFalse(LocalFilePaths.partOf(target).exists())
    }

    @Test
    fun `reanuda desde el parcial existente`() = runBlocking {
        val target = File(tmp.root, "peli.mp4")
        LocalFilePaths.partOf(target).writeText("AAAA")            // 4 bytes ya bajados
        server.enqueue(MockResponse().setResponseCode(206).setBody("BBBB"))

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isSuccess)
        assertEquals("AAAABBBB", target.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `manda los headers que le pasan`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        val target = File(tmp.root, "peli.mp4")

        downloader.download(
            server.url("/f").toString(), target,
            mapOf("Referer" to "https://origen.example/"),
        ) { _, _ -> }

        assertEquals("https://origen.example/", server.takeRequest().getHeader("Referer"))
    }

    @Test
    fun `un 403 falla y no deja archivo final`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val target = File(tmp.root, "peli.mp4")

        val result = downloader.download(server.url("/f").toString(), target, emptyMap()) { _, _ -> }

        assertTrue(result.isFailure)
        assertFalse(target.exists())
    }

    @Test
    fun `informa progreso creciente`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().writeUtf8("x".repeat(200_000))))
        val target = File(tmp.root, "peli.mp4")
        val seen = mutableListOf<Long>()

        downloader.download(server.url("/f").toString(), target, emptyMap()) { done, _ -> seen.add(done) }

        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen)
        assertEquals(200_000L, seen.last())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*HttpRangeDownloaderTest*"`
Expected: FALLA — `Unresolved reference: HttpRangeDownloader`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/arkiv/player/data/local/HttpRangeDownloader.kt`:

```kotlin
package com.arkiv.player.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Aritmética de la reanudación, aparte para poder testearla sin red ni disco. */
object RangeMath {
    /** null cuando no hay nada previo: pedir `bytes=0-` innecesariamente confunde a algunos hosts. */
    fun rangeHeaderFor(existingBytes: Long): String? =
        if (existingBytes > 0) "bytes=$existingBytes-" else null

    /** El `Content-Length` de una respuesta parcial es lo que FALTA, no el total del archivo. */
    fun totalBytesOf(contentLength: Long, startByte: Long): Long =
        if (contentLength <= 0) 0 else contentLength + startByte
}

/**
 * Descarga un archivo por HTTP con soporte de reanudación.
 *
 * Escribe siempre a `<target>.part` y renombra al final: así nunca existe un archivo destino a
 * medias que `LocalLibrary` pueda tomar por bueno y mandarle a VLC.
 */
class HttpRangeDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            val part = LocalFilePaths.partOf(target)
            val startByte = if (part.exists()) part.length() else 0L

            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            RangeMath.rangeHeaderFor(startByte)?.let { builder.header("Range", it) }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("respuesta sin cuerpo")

                // Si se pidió Range y el server respondió 200 (no lo soporta), lo que llega es el
                // archivo ENTERO: hay que descartar el parcial o quedaría duplicado el prefijo.
                val appending = startByte > 0 && resp.code == 206
                if (startByte > 0 && !appending) part.delete()
                val effectiveStart = if (appending) startByte else 0L
                val total = RangeMath.totalBytesOf(body.contentLength(), effectiveStart)

                var written = effectiveStart
                java.io.FileOutputStream(part, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                }

                // Verificación: si el server declaró un tamaño y no llegó completo, es un corte.
                if (total > 0 && written < total) {
                    throw IOException("descarga incompleta: $written de $total bytes")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("no se pudo renombrar el parcial")
                target
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*HttpRangeDownloaderTest*"`
Expected: PASA (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/HttpRangeDownloader.kt app/src/test/java/com/arkiv/player/data/local/HttpRangeDownloaderTest.kt
git commit -m "feat(descargas): descargador HTTP reanudable con Range"
```

---

## Task 6: Interfaz de estrategia y estrategia de archive.org

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/DownloadStrategy.kt`
- Create: `app/src/main/java/com/arkiv/player/data/local/ArchiveDownloadStrategy.kt`

**Interfaces:**
- Consumes: `HttpRangeDownloader`, `LocalFilePaths`, `ArkivRepository.getEpisode`,
  `ArchiveUrls.download`, `SettingsStore.downloadQuality`.
- Produces: `DownloadOutcome` (`Done(file: File)`, `NeedsConfirmation(fileSizeBytes: Long)`,
  `Failed(reason: String)`); `DownloadStrategy.download(episodeId: String, alreadyConfirmed: Boolean, targetDir: File, onProgress: (Long, Long) -> Unit): DownloadOutcome`;
  `ArchiveDownloadStrategy(repo, settings, http, hasSpace)`.

- [ ] **Step 1: Escribir la interfaz**

Create `app/src/main/java/com/arkiv/player/data/local/DownloadStrategy.kt`:

```kotlin
package com.arkiv.player.data.local

import java.io.File

/** Resultado de intentar bajar un episodio. */
sealed interface DownloadOutcome {
    data class Done(val file: File) : DownloadOutcome
    /** Torrent que supera el umbral: no se bajó nada, espera confirmación del usuario. */
    data class NeedsConfirmation(val fileSizeBytes: Long) : DownloadOutcome
    data class Failed(val reason: String) : DownloadOutcome
}

/**
 * Cómo se baja UNA fuente. El worker elige la implementación por `source` y no sabe nada de
 * libtorrent, de la NUC ni de archive.org.
 *
 * Las estrategias resuelven el origen consultando el repositorio por `episodeId` (igual que hace hoy
 * `PlayerViewModel`), en vez de recibirlo por parámetro: así la tabla `downloads` no duplica datos
 * que ya viven en `items`/`episodes` y no hay dos fuentes de verdad que se puedan desincronizar.
 */
interface DownloadStrategy {
    suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (bytesDone: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome
}
```

- [ ] **Step 2: Escribir la estrategia de archive**

Create `app/src/main/java/com/arkiv/player/data/local/ArchiveDownloadStrategy.kt`:

```kotlin
package com.arkiv.player.data.local

import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.Quality
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant
import java.io.File

/**
 * archive.org: descarga HTTP directa de la variante elegida. Reemplaza al `Downloader` viejo, que
 * usaba el `DownloadManager` del sistema — ver el spec para por qué se unificó a OkHttp.
 */
class ArchiveDownloadStrategy(
    private val repo: ArkivRepository,
    private val settings: SettingsStore,
    private val http: HttpRangeDownloader,
    /** `LocalDownloadManager::hasFreeSpaceFor`. Inyectado para no meter `StatFs` acá. */
    private val hasSpace: (Long) -> Boolean,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val episode = repo.getEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró el episodio")
        val variant = variantFor(episode)
            ?: return DownloadOutcome.Failed("Este episodio no tiene un archivo descargable")

        // archive.org publica el tamaño en la metadata, así que acá se sabe ANTES de bajar un byte.
        if (!hasSpace(variant.sizeBytes)) {
            return DownloadOutcome.Failed("No hay espacio suficiente en el dispositivo")
        }

        val url = ArchiveUrls.download(episode.itemId, variant.path)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, variant.path))

        return http.download(url, target, mapOf("User-Agent" to USER_AGENT), onProgress)
            .fold(
                onSuccess = { DownloadOutcome.Done(it) },
                onFailure = { DownloadOutcome.Failed(it.message ?: "Falló la descarga") },
            )
    }

    /** Misma elección de variante que hacía el Downloader viejo, para no cambiar de comportamiento. */
    private fun variantFor(episode: Episode): VideoVariant? = when (settings.downloadQuality.value) {
        Quality.DERIVATIVE -> episode.derivative ?: episode.original
        Quality.ORIGINAL -> episode.original ?: episode.derivative
    }

    private companion object {
        const val USER_AGENT = "Arkiv/0.1 (personal)"
    }
}
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/DownloadStrategy.kt app/src/main/java/com/arkiv/player/data/local/ArchiveDownloadStrategy.kt
git commit -m "feat(descargas): interfaz de estrategia y descarga de archive.org"
```

---

## Task 7: Descarga persistente en TorrentEngine

**Files:**
- Create: `app/src/main/java/com/arkiv/player/torrent/PersistentTorrentDownload.kt`
- Modify: `app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt` (agregar método público)

**Interfaces:**
- Consumes: `TorrentMeta`, `TorrentFile` (ya existen en `TorrentEngine.kt:25-33`).
- Produces: `PersistentTorrentDownload` con `bytesDone(): Long`, `totalBytes(): Long`,
  `isComplete(): Boolean`, `file(): File`, `detach()`, `discard()`;
  `TorrentEngine.startPersistentDownload(meta: TorrentMeta, fileIndex: Int, saveDir: File): PersistentTorrentDownload?`.

- [ ] **Step 1: Escribir el handle persistente**

Create `app/src/main/java/com/arkiv/player/torrent/PersistentTorrentDownload.kt`:

```kotlin
package com.arkiv.player.torrent

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import java.io.File

/**
 * Descarga de torrent PERSISTENTE: baja el archivo completo y NO lo borra al terminar.
 *
 * Es la excepción explícita a la regla del motor, documentada en `TorrentEngine`: "descarga al
 * cacheDir y borra al parar". Acá el `savePath` está en `filesDir`, fuera del `workDir` que barre
 * `sweepOrphans()`, y [detach] quita el torrent de la sesión SIN el flag de borrado de archivos.
 */
class PersistentTorrentDownload internal constructor(
    private val session: SessionManager,
    private val handle: TorrentHandle,
    private val saveDir: File,
    private val relativePath: String,
) {

    fun bytesDone(): Long =
        runCatching { handle.status().totalWantedDone() }.getOrDefault(0L)

    /** Solo el archivo elegido está en `wanted` (el resto del pack quedó en IGNORE). */
    fun totalBytes(): Long =
        runCatching { handle.status().totalWanted() }.getOrDefault(0L)

    fun isComplete(): Boolean {
        val total = totalBytes()
        return total > 0 && bytesDone() >= total
    }

    /** Ruta real del archivo en disco: para un torrent multi-archivo incluye la carpeta del pack. */
    fun file(): File = File(saveDir, relativePath)

    /** Quita el torrent de la sesión conservando lo descargado. */
    fun detach() {
        runCatching { session.remove(handle) }
            .onFailure { Log.w(TAG, "detach: $it") }
    }

    /** Quita el torrent y borra lo descargado (cancelar). */
    fun discard() {
        detach()
        runCatching { saveDir.deleteRecursively() }
            .onFailure { Log.w(TAG, "discard: $it") }
    }

    private companion object { const val TAG = "ArkivTorrentDl" }
}
```

- [ ] **Step 2: Agregar el método al motor**

En `TorrentEngine.kt`, agregar este método público justo después de `startStream` (que termina en la
línea 485). Necesita los imports `org.libtorrent4j.Priority` y `org.libtorrent4j.TorrentInfo`, que ya
están en el archivo:

```kotlin
    /**
     * Arranca una descarga PERSISTENTE del archivo [fileIndex] en [saveDir]. A diferencia de
     * [startStream]:
     *
     * - NO llama a `stopStreamInternal()`, así que no mata el stream que se esté reproduciendo;
     * - NO toca `currentHandle` ni levanta el server local: el handle se lo queda quien llama;
     * - prioriza el archivo en NORMAL (descarga completa) en vez del gate cabeza+cola del streaming;
     * - `saveDir` está fuera de `workDir`, así que `sweepOrphans()` no lo borra.
     *
     * Devuelve null si el torrent no arrancó.
     */
    @Synchronized
    fun startPersistentDownload(meta: TorrentMeta, fileIndex: Int, saveDir: File): PersistentTorrentDownload? {
        ensureStarted()
        acquireLocks()
        saveDir.mkdirs()
        val info = TorrentInfo(meta.infoBytes)
        session.download(info, saveDir)
        val handle = pollHandle(info) ?: run {
            Log.w("ArkivTorrent", "startPersistentDownload: no se pudo obtener el handle")
            return null
        }
        runCatching {
            extraTrackers().forEach { handle.addTracker(AnnounceEntry(it)) }
            handle.forceReannounce()
        }
        // Solo el archivo pedido: bajar el pack entero llenaría el disco del celular.
        val priorities = Array(info.numFiles()) { if (it == fileIndex) Priority.NORMAL else Priority.IGNORE }
        runCatching { handle.prioritizeFiles(priorities) }
            .onFailure { Log.w("ArkivTorrent", "prioritizeFiles: $it") }
        val relativePath = info.files().filePath(fileIndex)
        Log.i("ArkivTorrent", "DESCARGA infohash=${meta.infoHashHex} file=$fileIndex '$relativePath' -> $saveDir")
        return PersistentTorrentDownload(session, handle, saveDir, relativePath)
    }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Si `pollHandle` es privado y está declarado después, no importa: Kotlin
no exige orden de declaración dentro de una clase.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/torrent/PersistentTorrentDownload.kt app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt
git commit -m "feat(torrent): descarga persistente que no borra al terminar"
```

---

## Task 8: Estrategia de torrent

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/TorrentDownloadStrategy.kt`

**Interfaces:**
- Consumes: `DownloadStrategy`, `DownloadOutcome`, `TorrentSizeGate`, `LocalFilePaths`,
  `TorrentEngine.startPersistentDownload`, `TorrentEngine.resolveTorrent`,
  `TorrentEngine.resolveMagnet`, `ArkivRepository.torrentSourceForEpisode`,
  `EpisodeTorrent.Bytes/Magnet`.
- Produces: `TorrentDownloadStrategy(repo, engine, hasSpace)`.

- [ ] **Step 1: Escribir la estrategia**

Create `app/src/main/java/com/arkiv/player/data/local/TorrentDownloadStrategy.kt`:

```kotlin
package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.EpisodeTorrent
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentMeta
import kotlinx.coroutines.delay
import java.io.File

/**
 * Torrent: baja el archivo completo a un directorio persistente y lo mueve a su nombre final.
 *
 * Acá vive la compuerta de tamaño (ver [TorrentSizeGate]): se aplica DESPUÉS de resolver la
 * metadata, que es el primer momento en que se conoce el peso del archivo elegido — y no el del pack.
 */
class TorrentDownloadStrategy(
    private val repo: ArkivRepository,
    private val engine: TorrentEngine,
    /** `LocalDownloadManager::hasFreeSpaceFor`. Inyectado para no meter `StatFs` acá. */
    private val hasSpace: (Long) -> Boolean,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val src = repo.torrentSourceForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró el torrent guardado")

        val (meta, fileIndex) = resolve(src) ?: return DownloadOutcome.Failed("No se pudo leer el torrent")
        val file = meta.files.getOrNull(fileIndex)
            ?: return DownloadOutcome.Failed("El torrent no tiene el archivo pedido")

        if (TorrentSizeGate.needsConfirmation(file.sizeBytes, alreadyConfirmed)) {
            Log.i(TAG, "compuerta de tamaño: ${file.name} pesa ${TorrentSizeGate.formatSize(file.sizeBytes)}")
            return DownloadOutcome.NeedsConfirmation(file.sizeBytes)
        }

        // Mismo momento que la compuerta de tamaño: resolver la metadata es lo primero que revela
        // cuánto pesa el archivo, así que es lo antes que se puede fallar por espacio sin haber
        // bajado nada.
        if (!hasSpace(file.sizeBytes)) {
            return DownloadOutcome.Failed("No hay espacio suficiente en el dispositivo")
        }

        val workDir = File(targetDir, "torrents/${LocalFilePaths.torrentDirName(episodeId)}")
        val download = engine.startPersistentDownload(meta, fileIndex, workDir)
            ?: return DownloadOutcome.Failed("No se pudo iniciar el torrent")

        var waited = 0L
        var lastBytes = 0L
        var stalledMs = 0L
        while (!download.isComplete()) {
            delay(POLL_MS)
            waited += POLL_MS
            val done = download.bytesDone()
            onProgress(done, download.totalBytes())
            // Sin peers no avanza nunca: mismo tope que ya usa resolveTorrentUrl en PlayerViewModel.
            stalledMs = if (done > lastBytes) 0 else stalledMs + POLL_MS
            lastBytes = done
            if (done == 0L && stalledMs >= NO_PEERS_TIMEOUT_MS) {
                download.discard()
                return DownloadOutcome.Failed("No se encontró ningún peer para este torrent")
            }
        }

        download.detach()
        val downloaded = download.file()
        if (!downloaded.exists()) return DownloadOutcome.Failed("El torrent terminó pero no dejó archivo")

        // Mover a <targetDir>/<episodeId>.<ext> para que todas las fuentes dejen el archivo con el
        // mismo esquema de nombre y LocalLibrary no tenga que saber de dónde vino.
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, downloaded.name))
        if (target.exists()) target.delete()
        val moved = downloaded.renameTo(target)
        if (!moved) {
            downloaded.copyTo(target, overwrite = true)
            downloaded.delete()
        }
        runCatching { workDir.deleteRecursively() }
        return DownloadOutcome.Done(target)
    }

    /** Devuelve (metadata, índice del archivo a bajar) resolviendo bytes o magnet. */
    private suspend fun resolve(src: EpisodeTorrent): Pair<TorrentMeta, Int>? = when (src) {
        is EpisodeTorrent.Bytes -> engine.resolveTorrent(src.data)?.let { it to src.fileIndex }
        is EpisodeTorrent.Magnet -> engine.resolveMagnet(src.uri)?.let { meta ->
            val picked = engine.pickVideo(meta) ?: return null
            meta to picked.index
        }
    }

    private companion object {
        const val TAG = "ArkivTorrentDl"
        const val POLL_MS = 1_000L
        const val NO_PEERS_TIMEOUT_MS = 180_000L
    }
}
```

> **Verificar antes de escribir:** confirmar la forma exacta de `EpisodeTorrent.Bytes` y
> `EpisodeTorrent.Magnet` en `data/ArkivRepository.kt` (se usan en `PlayerViewModel.kt:339-379`:
> `Bytes` tiene `data` y `fileIndex`, `Magnet` tiene `uri`). Ajustar los nombres de campo si difieren.

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/TorrentDownloadStrategy.kt
git commit -m "feat(descargas): estrategia de torrent con compuerta de tamano"
```

---

## Task 9: LocalDownloadManager y LocalLibrary

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/FreeSpacePolicy.kt`
- Create: `app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt`
- Create: `app/src/main/java/com/arkiv/player/data/local/LocalLibrary.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/FreeSpacePolicyTest.kt`

**Interfaces:**
- Consumes: `DownloadDao`, `LocalDownloadState`, `DownloadQueuePolicy`, `LocalFilePaths`.
- Produces:
  `FreeSpacePolicy.fits(availableBytes: Long, neededBytes: Long): Boolean`,
  `FreeSpacePolicy.MARGIN_BYTES: Long`;
  `LocalDownloadManager(context, db, wakeWorker)` con
  `suspend fun enqueue(episodeId: String, source: String)`,
  `suspend fun confirmSize(episodeId: String)`,
  `suspend fun remove(episodeId: String)`,
  `fun observeRows(): Flow<List<DownloadRow>>`,
  `fun targetDir(): File`,
  `fun hasFreeSpaceFor(bytes: Long): Boolean`;
  `LocalLibrary(db)` con `suspend fun fileFor(episodeId: String): String?`.

- [ ] **Step 1a: Write the failing test para el chequeo de espacio**

Create `app/src/test/java/com/arkiv/player/data/local/FreeSpacePolicyTest.kt`:

```kotlin
package com.arkiv.player.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpacePolicyTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `entra si sobra espacio con margen`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 10 * gb, neededBytes = 2 * gb))
    }

    @Test
    fun `no entra si el archivo es mas grande que lo disponible`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 4 * gb))
    }

    /** Justo-justo tampoco: dejar el sistema sin un byte libre rompe otras cosas antes que a Arkiv. */
    @Test
    fun `no entra si cabe pero se come el margen`() {
        assertFalse(FreeSpacePolicy.fits(availableBytes = 2 * gb, neededBytes = 2 * gb - 1))
    }

    @Test
    fun `tamano desconocido siempre entra`() {
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = 0))
        assertTrue(FreeSpacePolicy.fits(availableBytes = 0, neededBytes = -1))
    }
}
```

- [ ] **Step 1b: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*FreeSpacePolicyTest*"`
Expected: FALLA — `Unresolved reference: FreeSpacePolicy`.

- [ ] **Step 1c: Escribir FreeSpacePolicy**

Create `app/src/main/java/com/arkiv/player/data/local/FreeSpacePolicy.kt`:

```kotlin
package com.arkiv.player.data.local

/**
 * Decide si una descarga entra en el disco. Puro (no toca `StatFs`) para poder testearlo sin
 * Robolectric: quien mide el espacio real es `LocalDownloadManager`.
 */
object FreeSpacePolicy {

    /** Margen para no dejar el dispositivo al borde: otras apps se rompen antes que Arkiv. */
    const val MARGIN_BYTES = 500L * 1024 * 1024

    /** [neededBytes] <= 0 es "todavía no se sabe": no tiene sentido bloquear por un dato que no hay. */
    fun fits(availableBytes: Long, neededBytes: Long): Boolean {
        if (neededBytes <= 0) return true
        return availableBytes >= neededBytes + MARGIN_BYTES
    }
}
```

- [ ] **Step 1d: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*FreeSpacePolicyTest*"`
Expected: PASA (4 tests).

- [ ] **Step 1: Escribir LocalLibrary**

Create `app/src/main/java/com/arkiv/player/data/local/LocalLibrary.kt`:

```kotlin
package com.arkiv.player.data.local

import com.arkiv.player.data.db.ArkivDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lo único que el reproductor consulta para saber si un episodio está guardado en el dispositivo.
 *
 * Verifica que el archivo EXISTA de verdad, no solo que la fila diga `completed`: si el usuario lo
 * borró desde los ajustes de Android, sin esta comprobación el player apuntaría a un archivo
 * fantasma y VLC mostraría pantalla negra sin explicación.
 */
class LocalLibrary(private val db: ArkivDatabase) {

    private val downloadDao = db.downloadDao()

    suspend fun fileFor(episodeId: String): String? = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext null
        if (row.state != LocalDownloadState.COMPLETED) return@withContext null

        // filePath es lo nuevo; localUri es el `file://` que dejaron las descargas hechas con el
        // DownloadManager del sistema antes de la migración 16->17. Las dos siguen valiendo.
        val path = row.filePath ?: row.localUri?.removePrefix("file://") ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            // El archivo se fue: limpiar la fila para que la UI no siga diciendo "listo" y para que
            // el próximo play caiga a streaming en vez de fallar.
            downloadDao.delete(episodeId)
            return@withContext null
        }
        file.absolutePath
    }
}
```

- [ ] **Step 2: Escribir LocalDownloadManager**

Create `app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt`:

```kotlin
package com.arkiv.player.data.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.DownloadEntity
import com.arkiv.player.data.db.DownloadRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fachada de las descargas al dispositivo: lo único que toca la UI. Encola en Room y despierta al
 * worker; no baja nada por su cuenta.
 */
class LocalDownloadManager(
    context: Context,
    db: ArkivDatabase,
    /** Inyectado para poder testear sin WorkManager; en producción es `LocalDownloadWorker::schedule`. */
    private val wakeWorker: (Context) -> Unit,
) {
    private val appContext = context.applicationContext
    private val downloadDao = db.downloadDao()

    /** `Android/data/<pkg>/files/Movies`. Cae a filesDir si no hay almacenamiento externo montado. */
    fun targetDir(): File =
        (appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(appContext.filesDir, "Movies"))
            .apply { mkdirs() }

    fun observeRows(): Flow<List<DownloadRow>> = downloadDao.observeDownloadRows()

    /** Mide el disco real y delega la decisión en [FreeSpacePolicy], que es lo testeable. */
    fun hasFreeSpaceFor(bytes: Long): Boolean =
        FreeSpacePolicy.fits(StatFs(targetDir().absolutePath).availableBytes, bytes)

    /**
     * Encola un episodio. Idempotente: si ya hay una fila que no falló, no hace nada — así tocar dos
     * veces el botón no duplica la descarga.
     */
    suspend fun enqueue(episodeId: String, source: String) = withContext(Dispatchers.IO) {
        val existing = downloadDao.get(episodeId)
        if (existing != null && existing.state != LocalDownloadState.FAILED) return@withContext
        downloadDao.upsert(
            DownloadEntity(
                episodeId = episodeId,
                variant = "",
                state = LocalDownloadState.QUEUED,
                progress = 0f,
                localUri = null,
                bytes = 0,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        )
        wakeWorker(appContext)
    }

    /** El usuario aceptó bajar un torrent que superaba el umbral de tamaño. */
    suspend fun confirmSize(episodeId: String) = withContext(Dispatchers.IO) {
        downloadDao.markConfirmed(episodeId)
        wakeWorker(appContext)
    }

    /** Borra la fila y el archivo (y el parcial, si quedó a medias). */
    suspend fun remove(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId)
        val path = row?.filePath ?: row?.localUri?.removePrefix("file://")
        if (path != null) {
            val file = File(path)
            runCatching { file.delete() }
            runCatching { LocalFilePaths.partOf(file).delete() }
        }
        runCatching { File(targetDir(), "torrents/${LocalFilePaths.torrentDirName(episodeId)}").deleteRecursively() }
        downloadDao.delete(episodeId)
    }
}
```

Quitar del import list `androidx`… es decir: `Dispatchers`/`withContext` siguen usándose en los otros
métodos, pero `hasFreeSpaceFor` ya no es `suspend`.

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt app/src/main/java/com/arkiv/player/data/local/LocalLibrary.kt
git commit -m "feat(descargas): fachada de descargas locales y consulta de biblioteca"
```

---

## Task 10: Worker de la cola

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/LocalDownloadWorker.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (agregar los singletons nuevos)

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: `LocalDownloadWorker.schedule(context: Context)`;
  en `AppGraph`: `localDownloads: LocalDownloadManager`, `localLibrary: LocalLibrary`,
  `downloadStrategies: Map<String, DownloadStrategy>`, `httpRangeDownloader: HttpRangeDownloader`.

- [ ] **Step 1: Agregar los singletons al grafo**

En `AppGraph.kt`, después de `val downloader: Downloader by lazy { ... }` (línea 67), agregar:

```kotlin
    // --- Descargas al propio dispositivo (ver docs/superpowers/specs/2026-08-07-...) ---
    val httpRangeDownloader: com.arkiv.player.data.local.HttpRangeDownloader by lazy {
        com.arkiv.player.data.local.HttpRangeDownloader(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                // Sin timeout de lectura: una descarga de varios GB no es una petición lenta, es
                // una petición larga. Con el default de 10s cualquier bache la mataría.
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
    }

    val localDownloads: com.arkiv.player.data.local.LocalDownloadManager by lazy {
        com.arkiv.player.data.local.LocalDownloadManager(
            appContext, database,
            wakeWorker = { com.arkiv.player.data.local.LocalDownloadWorker.schedule(it) },
        )
    }

    val localLibrary: com.arkiv.player.data.local.LocalLibrary by lazy {
        com.arkiv.player.data.local.LocalLibrary(database)
    }

    /** Una estrategia por `source` de la tabla `downloads`. La entrada "web" llega en la fase 2. */
    val downloadStrategies: Map<String, com.arkiv.player.data.local.DownloadStrategy> by lazy {
        mapOf(
            "archive" to com.arkiv.player.data.local.ArchiveDownloadStrategy(
                repository, settings, httpRangeDownloader, localDownloads::hasFreeSpaceFor,
            ),
            "torrent" to com.arkiv.player.data.local.TorrentDownloadStrategy(
                repository, torrentEngine, localDownloads::hasFreeSpaceFor,
            ),
        )
    }
```

- [ ] **Step 2: Escribir el worker**

Create `app/src/main/java/com/arkiv/player/data/local/LocalDownloadWorker.kt`:

```kotlin
package com.arkiv.player.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arkiv.player.AppGraph
import com.arkiv.player.data.db.DownloadEntity

/**
 * Procesa la cola de descargas al dispositivo, UNA a la vez.
 *
 * Secuencial y no en paralelo por tres razones concretas: `TorrentEngine` es de un stream activo a la
 * vez, el disco de blog no aguanta varios staging simultáneos (fase 2), y en el Fire TV Stick el
 * ancho de banda no sobra.
 *
 * Mismo patrón de auto-relanzamiento que `NucDownloadCheckWorker`: al terminar una fila se re-encola
 * para tomar la siguiente, en vez de iterar dentro de un solo `doWork()` — WorkManager no garantiza
 * un trabajo largo indefinido en background.
 */
class LocalDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        val dao = graph.database.downloadDao()

        val rows = dao.getAll().map { QueueRow(it.episodeId, it.state, it.createdAt) }
        val next = DownloadQueuePolicy.nextToProcess(rows) ?: return Result.success()
        val entity = dao.get(next.episodeId) ?: return Result.success()

        setForeground(foregroundInfo("Descargando", entity.episodeId))

        val strategy = graph.downloadStrategies[entity.source]
        if (strategy == null) {
            dao.updateState(entity.episodeId, LocalDownloadState.FAILED, "Fuente no soportada: ${entity.source}")
            reschedule()
            return Result.success()
        }

        dao.updateState(entity.episodeId, LocalDownloadState.DOWNLOADING, null)

        val outcome = runCatching {
            strategy.download(
                episodeId = entity.episodeId,
                alreadyConfirmed = entity.sizeConfirmed,
                targetDir = graph.localDownloads.targetDir(),
                onProgress = { done, total -> persistProgress(dao, entity, done, total) },
            )
        }.getOrElse { DownloadOutcome.Failed(it.message ?: "Error inesperado") }

        when (outcome) {
            is DownloadOutcome.Done -> {
                dao.markCompleted(entity.episodeId, outcome.file.absolutePath)
                notifyDone(entity.episodeId)
            }
            is DownloadOutcome.NeedsConfirmation -> {
                dao.updateBytes(
                    entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION, 0f, 0, outcome.fileSizeBytes,
                )
                dao.updateState(
                    entity.episodeId, LocalDownloadState.NEEDS_CONFIRMATION,
                    "Pesa ${TorrentSizeGate.formatSize(outcome.fileSizeBytes)}",
                )
                notifyNeedsConfirmation(entity.episodeId, outcome.fileSizeBytes)
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "falló ${entity.episodeId}: ${outcome.reason}")
                dao.updateState(entity.episodeId, LocalDownloadState.FAILED, outcome.reason)
            }
        }

        reschedule()
        return Result.success()
    }

    /**
     * Escribe el progreso a Room, no más de una vez por segundo. Sin esta cadencia una descarga de
     * 4 GB haría decenas de miles de UPDATE (el callback llega cada 64 KB) y la UI, que observa la
     * tabla, se recompondría sin parar.
     */
    private var lastPersistMs = 0L
    private suspend fun persistProgress(
        dao: com.arkiv.player.data.db.DownloadDao,
        entity: DownloadEntity,
        done: Long,
        total: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PROGRESS_THROTTLE_MS) return
        lastPersistMs = now
        val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        dao.updateBytes(entity.episodeId, LocalDownloadState.DOWNLOADING, progress, done, total)
    }

    private fun reschedule() = schedule(applicationContext)

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        ensureChannel()
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun notifyDone(episodeId: String) =
        notify(episodeId.hashCode(), "Descarga completa", "Ya podés verlo sin conexión")

    private fun notifyNeedsConfirmation(episodeId: String, bytes: Long) = notify(
        episodeId.hashCode(),
        "Descarga pesada",
        "Pesa ${TorrentSizeGate.formatSize(bytes)}. Confirmá en Descargas para bajarla.",
    )

    private fun notify(id: Int, title: String, text: String) {
        ensureChannel()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            id,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "ArkivLocalDl"
        private const val CHANNEL_ID = "arkiv_local_downloads"
        private const val NOTIF_ID = 4711
        private const val PROGRESS_THROTTLE_MS = 1_000L
        private const val WORK_NAME = "arkiv_local_downloads"

        /**
         * KEEP y no REPLACE: si ya hay una pasada corriendo, encolar otra descarga no debe matarla a
         * mitad. Cuando termine, se re-encola sola y toma la siguiente.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalDownloadWorker>().build(),
            )
        }
    }
}
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/LocalDownloadWorker.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(descargas): worker que procesa la cola local de a una"
```

---

## Task 11: Servidor HTTP local para castear lo guardado

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/LocalFileServer.kt`

**Interfaces:**
- Consumes: nada del plan.
- Produces: `LocalFileServer` con `fun serve(file: File): String?` (URL alcanzable por la LAN),
  `fun stop()`.

> **Por qué existe:** un `file://` no le llega a un Chromecast ni a un renderer DLNA — hacen su
> propio GET desde otro dispositivo. Sin esto, guardar algo en local sería una regresión: dejarías de
> poder castear justo lo que ya tenés bajado. Sigue el patrón de `TorrentStreamServer`
> (`ServerSocket(0)` + Range), pero sirviendo un archivo del disco en vez de piezas de un torrent.

- [ ] **Step 1: Escribir el servidor**

Create `app/src/main/java/com/arkiv/player/playback/LocalFileServer.kt`:

```kotlin
package com.arkiv.player.playback

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Sirve UN archivo del almacenamiento local por HTTP con soporte de Range, para que el Chromecast y
 * el DLNA puedan reproducir lo que se guardó en el dispositivo (no pueden abrir un `file://`).
 *
 * Un archivo a la vez: [serve] reemplaza al anterior. Mismo patrón que
 * `com.arkiv.player.torrent.TorrentStreamServer`.
 */
class LocalFileServer(private val lanIp: () -> String?) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var current: File? = null
    @Volatile private var thread: Thread? = null

    /** Devuelve la URL alcanzable desde la LAN, o null si no hay IP (sin red) o el archivo no está. */
    @Synchronized
    fun serve(file: File): String? {
        if (!file.exists()) return null
        current = file
        if (server == null) start()
        val ip = lanIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/file"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        current = null
        thread = null
    }

    private fun start() {
        val s = ServerSocket(0)
        server = s
        thread = Thread {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(socket) }.onFailure { Log.w(TAG, "handle: $it") } }
                    .apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) = socket.use { sock ->
        val file = current ?: return
        val reader = sock.getInputStream().bufferedReader()
        val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return

        val size = file.length()
        val rangeLine = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
        val hasRange = rangeLine != null && rangeLine.contains("bytes=")
        val start = if (hasRange) {
            rangeLine!!.substringAfter("bytes=").substringBefore('-').trim().toLongOrNull() ?: 0L
        } else 0L
        val end = size - 1
        val length = (end - start + 1).coerceAtLeast(0)

        val status = if (hasRange) "206 Partial Content" else "200 OK"
        val rangeHeader = if (hasRange) "Content-Range: bytes $start-$end/$size\r\n" else ""
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: ${mimeOf(file)}\r\n" +
            "Accept-Ranges: bytes\r\n" +
            rangeHeader +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n\r\n"

        val out = sock.getOutputStream()
        out.write(header.toByteArray())
        // HEAD: el Chromecast lo manda antes del GET para conocer tamaño y tipo.
        if (lines.first().startsWith("HEAD")) { out.flush(); return }

        file.inputStream().use { input ->
            input.skip(start)
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        out.flush()
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private companion object { const val TAG = "ArkivLocalServer" }
}
```

- [ ] **Step 2: Registrar en el grafo**

En `AppGraph.kt`, junto a los otros singletons de descarga local, agregar:

```kotlin
    /** Sirve el archivo local por HTTP para poder castearlo (un file:// no le llega al Chromecast). */
    val localFileServer: com.arkiv.player.playback.LocalFileServer by lazy {
        com.arkiv.player.playback.LocalFileServer(lanIp = { torrentEngine.lanIp() })
    }
```

> **Verificar:** `TorrentEngine` tiene un helper de IP de LAN privado (se usa en `lanStreamUrl()`,
> línea 414). Si `lanIp()` es privado, hacerlo `internal` o `public` en `TorrentEngine` en vez de
> duplicar la lógica.

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/LocalFileServer.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt
git commit -m "feat(descargas): servidor HTTP local para castear archivos guardados"
```

---

## Task 12: Reproducir el archivo local

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt:5`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt:113-131` (el `load`)
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (construcción del `PlayerViewModel`)

**Interfaces:**
- Consumes: `LocalLibrary.fileFor`, `LocalFileServer.serve`.
- Produces: `SourceKind.LOCAL`; `PlayerViewModel.loadLocal(episodeId, path)` (privado).

- [ ] **Step 1: Agregar el valor al enum**

En `playback/PlayerSource.kt` línea 5:

```kotlin
enum class SourceKind { ARCHIVE, TORRENT, WEB, NUC, LOCAL }
```

Compilar (`./gradlew :app:assembleDebug`) y arreglar todos los `when(kind)` exhaustivos que el
compilador marque. En `PlayerViewModel.load()` la rama `LOCAL` no se alcanza (el atajo del paso
siguiente actúa antes), pero el `when` la exige: tratarla como `loadWebRespectingPreference` igual
que ya se hace con `NUC`.

- [ ] **Step 2: Agregar el atajo en `load()`**

En `PlayerViewModel.kt`, agregar `localLibrary` y `localFileServer` al constructor:

```kotlin
class PlayerViewModel(
    private val repo: ArkivRepository,
    private val settings: SettingsStore,
    private val torrentEngine: TorrentEngine,
    private val archiveCacheProxy: ArchiveCacheProxy,
    private val webResolverApi: com.arkiv.player.data.catalog.web.WebResolverApi,
    private val arkivOfflineApi: ArkivOfflineApi,
    private val playbackPreferenceStore: PlaybackPreferenceStore,
    private val localLibrary: com.arkiv.player.data.local.LocalLibrary,
    private val localFileServer: com.arkiv.player.playback.LocalFileServer,
) : ViewModel() {
```

Y reemplazar el cuerpo de `load()` (líneas 113-131) por:

```kotlin
    /** Carga el episodio como playlist, ramificando por fuente (archive vs torrent vs web). */
    fun load(episodeId: String) {
        viewModelScope.launch {
            _error.value = null
            // Si está guardado en el dispositivo, gana sobre cualquier streaming. Va ANTES de
            // ramificar por fuente: da igual de dónde vino el archivo, ya está acá.
            //
            // ARCHIVE queda fuera a propósito: loadArchive() ya arma la playlist de la sección
            // pasando el archivo local por episodio, así que ya mezcla local y remoto bien. Meterlo
            // acá lo degradaría a un solo ítem y rompería el autoplay del siguiente capítulo.
            val kind = PlayerSource.kindFor(episodeId)
            if (kind != SourceKind.ARCHIVE) {
                val local = localLibrary.fileFor(episodeId)
                if (local != null) { loadLocal(episodeId, local); return@launch }
            }
            Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
            when (kind) {
                SourceKind.TORRENT -> loadTorrent(episodeId)
                SourceKind.ARCHIVE -> loadArchive(episodeId)
                SourceKind.WEB -> loadWebRespectingPreference(episodeId)
                SourceKind.NUC, SourceKind.LOCAL -> loadWebRespectingPreference(episodeId)
            }
        }
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) { prefetchNext(episodeId) }
    }

    /**
     * Archivo guardado en el dispositivo. `castUrl` apunta al servidor HTTP local y NO al `file://`:
     * el Chromecast hace su propio GET desde otro dispositivo y no puede abrir una ruta del sistema
     * de archivos del celular.
     */
    private suspend fun loadLocal(episodeId: String, path: String) {
        val ep = repo.getEpisode(episodeId)
        val file = java.io.File(path)
        val castUrl = withContext(Dispatchers.IO) { runCatching { localFileServer.serve(file) }.getOrNull() }
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: file.name,
            subtitle = ep?.section ?: "",
            mediaUrl = "file://$path",
            castUrl = castUrl,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.LOCAL,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.LOCAL)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
        Log.w(PLAY, "loadLocal() $episodeId -> $path (cast=$castUrl)")
    }
```

- [ ] **Step 3: Pasar las dependencias nuevas donde se construye el ViewModel**

En `ArkivRoot.kt`, buscar la construcción de `PlayerViewModel` (dentro de una `viewModelFactory` o un
`remember`) y agregar `graph.localLibrary` y `graph.localFileServer` a la llamada, en el mismo orden
del constructor.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Si algún otro sitio construye `PlayerViewModel`, el compilador lo marca.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/PlayerSource.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(descargas): reproducir el archivo local en vez del streaming"
```

---

## Task 13: Pantalla de descargas

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/downloads/DownloadsViewModel.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/downloads/DownloadsScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt:324` (llamada a `DownloadsScreen`)

**Interfaces:**
- Consumes: `LocalDownloadManager.observeRows/confirmSize/remove`, `DownloadRow`,
  `LocalDownloadState`, `TorrentSizeGate.formatSize`.
- Produces: `DownloadsViewModel(manager: LocalDownloadManager)` con `downloads: StateFlow<List<DownloadRow>>`,
  `fun confirm(episodeId: String)`, `fun remove(episodeId: String)`.

- [ ] **Step 1: Reescribir el ViewModel**

Reemplazar el contenido de `DownloadsViewModel.kt` por:

```kotlin
package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.LocalDownloadManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * El estado sale de Room, que actualiza el worker. Ya no hay poll: el `refreshProgress()` cada 1,5 s
 * existía porque el progreso vivía en el DownloadManager del sistema y había que ir a buscarlo.
 */
class DownloadsViewModel(
    private val manager: LocalDownloadManager,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadRow>> = manager.observeRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** El usuario aceptó bajar un torrent que superaba el umbral de tamaño. */
    fun confirm(episodeId: String) {
        viewModelScope.launch { manager.confirmSize(episodeId) }
    }

    /** Reintenta una descarga fallida: la vuelve a poner en cola y despierta al worker. */
    fun retry(episodeId: String) {
        viewModelScope.launch { manager.retry(episodeId) }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch { manager.remove(episodeId) }
    }
}
```

Y en `LocalDownloadManager.kt` (Task 9) agregar el método que falta:

```kotlin
    /**
     * Vuelve a encolar una fila fallida. El `.part` que haya quedado se conserva a propósito: el
     * descargador reanuda desde ahí con `Range` en vez de empezar de cero.
     */
    suspend fun retry(episodeId: String) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(episodeId) ?: return@withContext
        if (!DownloadQueuePolicy.isRetryable(row.state)) return@withContext
        downloadDao.updateState(episodeId, LocalDownloadState.QUEUED, null)
        wakeWorker(appContext)
    }
```

- [ ] **Step 2: Actualizar la pantalla**

En `DownloadsScreen.kt`:

1. Cambiar la firma para recibir el `DownloadsViewModel` nuevo (o construirlo desde `graph.localDownloads`,
   siguiendo el patrón que ya use el archivo).
2. Agregar una función de etiqueta de estado y usarla en cada fila:

```kotlin
/** Texto que se le muestra al usuario para cada estado de la cola. */
private fun stateLabel(row: DownloadRow): String = when (row.state) {
    LocalDownloadState.QUEUED -> "En cola"
    LocalDownloadState.STAGING -> "Preparando en el servidor"
    LocalDownloadState.DOWNLOADING -> "Bajando ${(row.progress * 100).toInt()}%"
    LocalDownloadState.NEEDS_CONFIRMATION -> "Necesita confirmación · ${TorrentSizeGate.formatSize(row.bytes)}"
    LocalDownloadState.COMPLETED -> "Listo"
    LocalDownloadState.FAILED -> row.error ?: "Falló"
    else -> row.state
}

/** Origen del archivo, para distinguir de un vistazo torrent de web de archive. */
private fun sourceBadge(source: String): String = when (source) {
    "torrent" -> "TORRENT"
    "web" -> "WEB"
    else -> "ARCHIVE"
}
```

3. En la fila, cuando `row.state == LocalDownloadState.NEEDS_CONFIRMATION`, mostrar **dos** botones:

```kotlin
when (row.state) {
    LocalDownloadState.NEEDS_CONFIRMATION -> Row {
        TextButton(onClick = { vm.confirm(row.episodeId) }) { Text("Descargar igual") }
        TextButton(onClick = { vm.remove(row.episodeId) }) { Text("Descartar") }
    }
    LocalDownloadState.FAILED -> Row {
        TextButton(onClick = { vm.retry(row.episodeId) }) { Text("Reintentar") }
        TextButton(onClick = { vm.remove(row.episodeId) }) { Text("Quitar") }
    }
    else -> TextButton(onClick = { vm.remove(row.episodeId) }) { Text("Quitar") }
}
```

4. Mostrar el badge de origen junto al título y, cuando `row.error != null`, el motivo del fallo.

- [ ] **Step 3: Actualizar la llamada en ArkivRoot**

En `ArkivRoot.kt:324`, ajustar los argumentos de `DownloadsScreen(...)` a la firma nueva.

- [ ] **Step 4: Compilar y verificar en dispositivo**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Instalar en el S24+ por ADB WiFi, encolar una descarga de torrent desde el detalle de una película,
y verificar en la pantalla de Descargas: aparece la fila, el badge dice TORRENT, el porcentaje sube,
y al terminar dice "Listo".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/downloads/DownloadsViewModel.kt app/src/main/java/com/arkiv/player/ui/downloads/DownloadsScreen.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(descargas): pantalla de descargas para las tres fuentes"
```

---

## Task 14: Botón de guardar en búsqueda y detalles

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt:78-109` (solo el comentario)

**Interfaces:**
- Consumes: `LocalDownloadManager.enqueue`, `TorrentSizeGate`.
- Produces: nada nuevo; cablea `SourceSection(onDownload = ...)` a la cola local en las tres secciones.

> **Contexto:** `SourceSection` ya acepta `onDownload` (`PlaySources.kt:92`), pero hoy solo se lo pasa
> la sección WEB y apunta a la NUC. Esta tarea lo pasa también a TORRENT y ARCHIVE, y lo redirige a la
> cola local.

- [ ] **Step 1: Actualizar el comentario de `SourceSection`**

En `PlaySources.kt:78-82`, reemplazar el KDoc por:

```kotlin
/**
 * Sección colapsable por tipo de fuente (TORRENT/WEB/ARCHIVE) con contador y spinner propio.
 * [onDownload], si no es null, agrega un botón de "Guardar en el dispositivo" a cada fila. Lo reciben
 * las tres secciones: el archivo final queda en el celular, no en la NUC.
 */
```

- [ ] **Step 2: Cablear en `CineDetailScreen`**

Localizar dónde se llama a `SourceSection` para cada tag. Reemplazar el `onDownload` de la sección WEB
y agregar el mismo callback a TORRENT y ARCHIVE:

```kotlin
    val scope = rememberCoroutineScope()
    // El aviso inline es un ATAJO de UX: evita encolar algo que vas a descartar cuando el tamaño ya
    // se conoce. La compuerta que garantiza el comportamiento es la del worker, que es la única que
    // ve el tamaño del ARCHIVO (TorrentResult.sizeBytes es el del pack entero).
    var pendingBig by remember { mutableStateOf<Pair<String, Long>?>(null) }

    fun saveLocally(episodeId: String, source: String, knownSizeBytes: Long) {
        if (TorrentSizeGate.needsConfirmation(knownSizeBytes, alreadyConfirmed = false)) {
            pendingBig = episodeId to knownSizeBytes
        } else {
            scope.launch { graph.localDownloads.enqueue(episodeId, source) }
        }
    }
```

Y el diálogo, cerca del resto de diálogos de la pantalla:

```kotlin
    pendingBig?.let { (episodeId, bytes) ->
        AlertDialog(
            onDismissRequest = { pendingBig = null },
            title = { Text("Descarga pesada") },
            text = { Text("Este torrent pesa ${TorrentSizeGate.formatSize(bytes)}. ¿Lo bajás igual?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.localDownloads.enqueue(episodeId, "torrent")
                        graph.localDownloads.confirmSize(episodeId)
                    }
                    pendingBig = null
                }) { Text("Descargar") }
            },
            dismissButton = { TextButton(onClick = { pendingBig = null }) { Text("Cancelar") } },
        )
    }
```

En cada `SourceSection`, pasar el callback con el `source` que corresponde. El `episodeId` se obtiene
guardando primero el ítem local con el mismo camino que ya usa el botón "Guardar" de esa pantalla
(`onSave` / `addWebPack` / `addSeriesEpisode`), y usando el id que devuelve. Para la sección TORRENT,
el tamaño conocido sale de `(source as PlaySource.Torrent).result.sizeBytes`; para WEB y ARCHIVE se
pasa `0` (desconocido, no dispara el atajo).

- [ ] **Step 3: Repetir en `AnimeShowDetailScreen` y `SearchScreen`**

Mismo patrón. En `AnimeShowDetailScreen` los iconos de descarga de las líneas 884 y 907 pasan a
llamar a `saveLocally(...)` en vez de al job de la NUC. En `SearchScreen`, la función de la línea 234
que dispara la descarga a la NUC pasa a encolar en la cola local.

- [ ] **Step 4: Compilar y verificar en dispositivo**

Run: `./gradlew :app:assembleDebug`

En el S24+: buscar una película, abrir la hoja de fuentes, y verificar que **las tres** secciones
muestran el botón de guardar. Tocar uno de torrent chico (<5 GB) y confirmar que aparece en Descargas.
Tocar uno grande (>5 GB) y confirmar que sale el diálogo antes de encolar.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/PlaySources.kt app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt
git commit -m "feat(descargas): boton de guardar en el dispositivo en busqueda y detalles"
```

---

## Task 15: Guardar desde la librería

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt:192-220`

**Interfaces:**
- Consumes: `LocalDownloadManager.enqueue`, `LocalLibrary.fileFor`.
- Produces: nada nuevo.

- [ ] **Step 1: Agregar la acción al bottom sheet**

En `LibraryScreen.kt`, dentro del `ModalBottomSheet` del long-press, después de
`SheetAction("Ver detalle / descargar")` (línea 207):

```kotlin
                SheetAction("Guardar en el dispositivo") {
                    scope.launch {
                        // Una película es un ítem de un solo episodio; una serie se guarda desde su
                        // detalle, capítulo por capítulo (no tiene sentido encolar 200 capítulos
                        // desde un menú contextual sin decir cuáles).
                        val episodes = graph.repository.episodesOf(row.identifier)
                        val single = episodes.singleOrNull()
                        if (single != null) {
                            graph.localDownloads.enqueue(single.id, sourceOf(row))
                        } else {
                            onOpenItem(row.identifier)
                        }
                    }
                    menuRow = null
                }
```

Y la función auxiliar, en el mismo archivo:

```kotlin
/** El `source` de la tabla `downloads` a partir de la fila de la librería. */
private fun sourceOf(row: LibraryRow): String = if (row.isTorrent) "torrent" else "archive"
```

> **Verificar:** el tipo real de `row` (arriba se lo llama `LibraryRow` por su uso en
> `LibraryScreen.kt:182`). Ajustar el nombre al que use el archivo. Si la fila tiene un flag de web,
> devolver `"web"` en vez de `"archive"`.

- [ ] **Step 2: Indicador de "ya guardado" en la tarjeta**

Donde se arma la tarjeta (línea ~180), agregar un estado observado y un ícono cuando el ítem tiene un
episodio ya descargado:

```kotlin
    val savedIds by graph.localDownloads.observeRows()
        .map { rows -> rows.filter { it.state == LocalDownloadState.COMPLETED }.map { it.itemId }.toSet() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
```

y pasar `saved = row.identifier in savedIds` al composable de la tarjeta para que dibuje un ícono de
descarga.

- [ ] **Step 3: Compilar y verificar en dispositivo**

Run: `./gradlew :app:assembleDebug`

En el S24+: mantener pulsada una película de la librería, elegir "Guardar en el dispositivo",
verificar que aparece en Descargas y que al terminar la tarjeta muestra el indicador.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt
git commit -m "feat(descargas): guardar en el dispositivo desde la libreria"
```

---

## Task 16: Retirar el Downloader viejo

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/download/Downloader.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt:25,67`
- Modify: `app/src/main/java/com/arkiv/player/ArkivApp.kt:24,39`
- Modify: `app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt:128-135`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt:225`

**Interfaces:**
- Consumes: `LocalDownloadManager`.
- Produces: nada; elimina `AppGraph.downloader` y `ArkivRepository.completedDownloadUri` queda como
  está (la sigue usando `loadArchive`).

- [ ] **Step 1: Reemplazar los usos**

1. `DetailScreen.kt:130` — cambiar `graph.downloader.enqueue(ep)` por
   `graph.localDownloads.enqueue(ep.id, "archive")`. Los `Log.d/Log.e` de alrededor pierden sentido
   (ya no hay excepción que capturar): dejar solo la llamada dentro del `scope.launch`.
2. `ArkivApp.kt:24` y `:39` — borrar las dos llamadas a `graph.downloader.refreshProgress()`. El
   progreso ya no se sondea: lo escribe el worker.
3. `AppGraph.kt:67` — borrar la línea `val downloader: Downloader by lazy { ... }`.
4. `AppGraph.kt:25` — borrar el import `com.arkiv.player.data.download.Downloader`.

- [ ] **Step 2: Borrar el archivo**

```bash
git rm app/src/main/java/com/arkiv/player/data/download/Downloader.kt
```

- [ ] **Step 3: Compilar y correr todos los tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests en verde. Si algo más referenciaba `Downloader`, el
compilador lo marca acá.

- [ ] **Step 4: Verificar en dispositivo que lo ya descargado sigue andando**

Con un APK anterior instalado que tenga descargas de archive.org completadas, instalar este build
encima (sin desinstalar) y verificar que esos episodios siguen reproduciéndose desde el archivo local.
Es la comprobación de que el fallback `filePath ?: localUri` de `LocalLibrary` y la migración 16→17
hicieron lo suyo.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ArkivApp.kt app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt
git commit -m "refactor(descargas): retirar el Downloader basado en DownloadManager"
```

---

# FASE 2 — web vía la NUC

## Task 17: Mapeo de progreso del staging

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/StagingProgress.kt`
- Test: `app/src/test/java/com/arkiv/player/data/local/StagingProgressTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `StagingProgress.fromStaging(jobProgress: Float): Float`,
  `StagingProgress.fromTransfer(bytesDone: Long, totalBytes: Long): Float`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/arkiv/player/data/local/StagingProgressTest.kt`:

```kotlin
package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StagingProgressTest {

    @Test
    fun `el staging ocupa la primera mitad`() {
        assertEquals(0f, StagingProgress.fromStaging(0f), 0.001f)
        assertEquals(0.25f, StagingProgress.fromStaging(0.5f), 0.001f)
        assertEquals(0.5f, StagingProgress.fromStaging(1f), 0.001f)
    }

    @Test
    fun `la transferencia ocupa la segunda mitad`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(0, 1000), 0.001f)
        assertEquals(0.75f, StagingProgress.fromTransfer(500, 1000), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(1000, 1000), 0.001f)
    }

    @Test
    fun `sin total conocido la transferencia se queda en la mitad`() {
        assertEquals(0.5f, StagingProgress.fromTransfer(400, 0), 0.001f)
    }

    @Test
    fun `nunca se pasa de los limites`() {
        assertEquals(0.5f, StagingProgress.fromStaging(2f), 0.001f)
        assertEquals(0f, StagingProgress.fromStaging(-1f), 0.001f)
        assertEquals(1f, StagingProgress.fromTransfer(2000, 1000), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*StagingProgressTest*"`
Expected: FALLA — `Unresolved reference: StagingProgress`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/arkiv/player/data/local/StagingProgress.kt`:

```kotlin
package com.arkiv.player.data.local

/**
 * Una descarga web tiene DOS fases en serie (la NUC baja del origen, y después el dispositivo baja de
 * la NUC), pero la UI muestra una sola barra. Cada fase ocupa la mitad, así que la barra avanza
 * siempre hacia adelante en vez de volver a cero al cambiar de fase.
 */
object StagingProgress {

    fun fromStaging(jobProgress: Float): Float = (jobProgress.coerceIn(0f, 1f)) * 0.5f

    fun fromTransfer(bytesDone: Long, totalBytes: Long): Float {
        if (totalBytes <= 0) return 0.5f
        val ratio = (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f)
        return 0.5f + ratio * 0.5f
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*StagingProgressTest*"`
Expected: PASA (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/StagingProgress.kt app/src/test/java/com/arkiv/player/data/local/StagingProgressTest.kt
git commit -m "feat(descargas): mapeo de progreso staging + transferencia"
```

---

## Task 18: Estrategia web vía la NUC

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/local/NucStagedStrategy.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (agregar `"web"` al mapa de estrategias)

**Interfaces:**
- Consumes: `ArkivOfflineApi` (`createJob`, `getJob`, `library`, `deleteLibraryItem`,
  `baseUrlResolved`, `streamUrl`), `HttpRangeDownloader`, `StagingProgress`, `LocalFilePaths`,
  `ArkivRepository.webSourceForEpisode`, `ArkivRepository.subtitleContextForEpisode`,
  `DownloadDao.setStagingItem`.
- Produces: `NucStagedStrategy(repo, api, http, dao)`.

- [ ] **Step 1: Escribir la estrategia**

Create `app/src/main/java/com/arkiv/player/data/local/NucStagedStrategy.kt`:

```kotlin
package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.DownloadDao
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.NucDownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fuentes web: la NUC (arkiv-offline) hace de ESTACIÓN DE PASO, nunca de destino.
 *
 * El resolver web devuelve HLS (.m3u8) con frecuencia, que no es un archivo descargable. El backend
 * ya resuelve eso con yt-dlp, y `GET /stream/{item}` sirve el resultado con Range (RFC 7233), así que
 * la transferencia al dispositivo es reanudable. Ver el spec para por qué se descartó remuxar HLS con
 * libVLC en el propio dispositivo y por qué NO hay un camino directo para mp4 progresivo.
 */
class NucStagedStrategy(
    private val repo: ArkivRepository,
    private val api: ArkivOfflineApi,
    private val http: HttpRangeDownloader,
    private val dao: DownloadDao,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val pageUrl = repo.webSourceForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente web")
        val ctx = runCatching { repo.subtitleContextForEpisode(episodeId) }.getOrNull()
        val season = ctx?.season ?: 0
        val episode = ctx?.episode ?: 0
        val seriesId = episodeId.substringBefore("::").removePrefix(SERIES_ITEM_PREFIX)

        // 1) Reusar lo que ya esté en la NUC para esta (serie, temporada, capítulo, pageUrl).
        val existing = runCatching { api.library(seriesId) }.getOrNull()
            ?.firstOrNull { it.season == season && it.episode == episode && it.sourceRef == pageUrl }

        val itemId = existing?.itemId ?: run {
            val jobId = api.createJob(
                seriesId = seriesId,
                showTitle = repo.getEpisode(episodeId)?.displayName ?: seriesId,
                posterUrl = "",
                items = listOf(NucDownloadItem(season = season, episode = episode, sourceRef = pageUrl)),
            ) ?: return DownloadOutcome.Failed("La NUC no aceptó el trabajo (¿sin espacio o caída?)")

            // 2) Esperar a que la NUC termine de bajar del origen.
            val waitResult = awaitJob(jobId, onProgress)
            if (waitResult != null) return waitResult

            runCatching { api.library(seriesId) }.getOrNull()
                ?.firstOrNull { it.season == season && it.episode == episode && it.sourceRef == pageUrl }
                ?.itemId
                ?: return DownloadOutcome.Failed("La NUC terminó pero no publicó el archivo")
        }

        dao.setStagingItem(episodeId, itemId)

        // 3) Transferir de la NUC al dispositivo (Range → reanudable).
        val base = withContext(Dispatchers.IO) { api.baseUrlResolved() }
        val url = api.streamUrl(itemId, base)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, pageUrl))

        val result = http.download(url, target, emptyMap()) { done, total -> onProgress(done, total) }

        return result.fold(
            onSuccess = { file ->
                // 4) Liberar el disco de la NUC. Best-effort: si falla, el stagingItemId queda en la
                // fila y el barrido de arranque lo reintenta (ver Task 19).
                val deleted = runCatching { api.deleteLibraryItem(itemId) }.getOrDefault(false)
                if (deleted) dao.setStagingItem(episodeId, null)
                else Log.w(TAG, "no se pudo borrar el item $itemId de la NUC; queda para el barrido")
                DownloadOutcome.Done(file)
            },
            onFailure = { DownloadOutcome.Failed(it.message ?: "Falló la transferencia desde la NUC") },
        )
    }

    /** Devuelve null si el job terminó bien, o el DownloadOutcome de fallo. */
    private suspend fun awaitJob(jobId: Long, onProgress: (Long, Long) -> Unit): DownloadOutcome? {
        var waited = 0L
        while (waited < STAGING_TIMEOUT_MS) {
            val job = api.getJob(jobId)
            when (job?.status) {
                "done" -> return null
                "failed" -> return DownloadOutcome.Failed("La NUC no pudo bajar este capítulo")
                else -> {
                    // El progreso del job viaja en 0..1; se mapea a la primera mitad de la barra.
                    val p = job?.progress ?: 0f
                    onProgress((StagingProgress.fromStaging(p) * PROGRESS_SCALE).toLong(), PROGRESS_SCALE)
                }
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return DownloadOutcome.Failed("La NUC tardó demasiado")
    }

    private companion object {
        const val TAG = "ArkivLocalDl"
        const val SERIES_ITEM_PREFIX = "web:series:"
        const val POLL_MS = 3_000L
        const val STAGING_TIMEOUT_MS = 60L * 60 * 1000   // 1 h: un capítulo grande por HLS tarda
        /** Escala artificial para reportar progreso de staging por el mismo callback de bytes. */
        const val PROGRESS_SCALE = 1_000_000L
    }
}
```

> **Verificar antes de escribir:** la forma exacta de `NucDownloadItem` y de `NucJob` (campos
> `status` y `progress`) en `data/offline/ArkivOfflineApi.kt`, y el valor real de
> `SERIES_ITEM_PREFIX` en `PlayerViewModel.kt` (se usa en `loadWebRespectingPreference`). Ajustar
> nombres si difieren.

> **Por qué poll de `GET /jobs/{id}` y no el SSE de `NucJobEvents`:** el túnel de Cloudflare corta
> las conexiones SSE largas — por eso `NucJobEvents` ya trae un poll de respaldo. Dentro de un worker
> que corre en background y puede ser suspendido por el sistema, sostener un stream no aporta nada
> sobre un poll cada 3 s, y sí agrega un modo de fallo. El spec lista `GET /jobs/{id}/events` entre
> lo que el backend ofrece; usarlo o no es decisión de implementación, y acá se elige el poll.

> **Extensión del archivo web:** `LocalFilePaths.fileNameFor(episodeId, pageUrl)` cae a `.mp4` porque
> una pageUrl no trae extensión de video. Es correcto para reproducir (VLC no se guía por la
> extensión), pero `LocalFileServer.mimeOf` declarará `video/mp4` para un contenedor que puede ser
> Matroska. Si al castear un capítulo web guardado el Chromecast lo rechaza, ahí es donde hay que
> mirar: la solución es leer el nombre real del `Content-Disposition` de `GET /stream/{item}`.

- [ ] **Step 2: Registrar la estrategia**

En `AppGraph.kt`, agregar la entrada `"web"` al mapa `downloadStrategies`:

```kotlin
            "web" to com.arkiv.player.data.local.NucStagedStrategy(
                repository, arkivOfflineApi, httpRangeDownloader, database.downloadDao(),
            ),
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/NucStagedStrategy.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(descargas): fuentes web via la NUC como estacion de paso"
```

---

## Task 19: Barrido de huérfanos y cableado del botón web

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ArkivApp.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/WebPackDialog.kt:84`

**Interfaces:**
- Consumes: `DownloadDao.orphanStagingItems`, `ArkivOfflineApi.deleteLibraryItem`.
- Produces: `LocalDownloadManager.sweepNucOrphans()`.

- [ ] **Step 1: Agregar el barrido**

En `LocalDownloadManager.kt`, agregar el parámetro `deleteNucItem` al constructor y el método:

```kotlin
class LocalDownloadManager(
    context: Context,
    db: ArkivDatabase,
    private val wakeWorker: (Context) -> Unit,
    /** Borra un item de la NUC. Inyectado para no acoplar la fachada al cliente REST. */
    private val deleteNucItem: suspend (Long) -> Boolean = { false },
) {
```

```kotlin
    /**
     * Borra de la NUC los items que ya se transfirieron al dispositivo pero cuyo DELETE falló en su
     * momento (blog caído, red cortada). Sin esto el disco de la NUC se llena de archivos que ya
     * nadie va a reproducir, y `fits` empieza a rechazar trabajos nuevos.
     */
    suspend fun sweepNucOrphans() = withContext(Dispatchers.IO) {
        for (itemId in downloadDao.orphanStagingItems()) {
            if (runCatching { deleteNucItem(itemId) }.getOrDefault(false)) {
                downloadDao.clearStagingItem(itemId)
            }
        }
    }
```

En `DownloadDao`, agregar:

```kotlin
    @Query("UPDATE downloads SET stagingItemId = NULL WHERE stagingItemId = :stagingItemId")
    suspend fun clearStagingItem(stagingItemId: Long)
```

En `AppGraph.kt`, pasar el borrador real:

```kotlin
            deleteNucItem = { itemId -> arkivOfflineApi.deleteLibraryItem(itemId) },
```

- [ ] **Step 2: Disparar el barrido al arrancar**

En `ArkivApp.kt`, donde antes estaban las llamadas a `refreshProgress()` (borradas en la Task 16),
poner:

```kotlin
        graph.applicationScope.launch { runCatching { graph.localDownloads.sweepNucOrphans() } }
```

- [ ] **Step 3: Cablear el botón del pack web**

En `WebPackDialog.kt:84`, cambiar el texto del botón a `"Guardar en el dispositivo (${selected.size})"`
y su acción a encolar cada capítulo seleccionado con `source = "web"`, usando el mismo camino de
guardado local que ya usa el botón "Guardar" del diálogo para obtener cada `episodeId`.

- [ ] **Step 4: Compilar y verificar en dispositivo**

Run: `./gradlew :app:assembleDebug`

En el S24+: abrir el pack web de una serie, marcar dos capítulos, tocar "Guardar en el dispositivo".
En Descargas debe verse primero "Preparando en el servidor" y después "Bajando N%". Al terminar,
reproducir sin red móvil ni WiFi para confirmar que sale del archivo local.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/AppGraph.kt app/src/main/java/com/arkiv/player/ArkivApp.kt app/src/main/java/com/arkiv/player/ui/catalog/WebPackDialog.kt
git commit -m "feat(descargas): barrido de huerfanos en la NUC y pack web al dispositivo"
```

---

## Task 20: Desconectar la reproducción remota desde la NUC

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt:425` (ruta de `NucDownloadsScreen`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt:187-227`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (diálogo "¿NUC o en vivo?")

**Interfaces:**
- Consumes: nada nuevo.
- Produces: nada; `loadWebRespectingPreference` pasa a ser `loadWeb` directo.

> **El código NO se borra** (decisión del spec): solo deja de estar cableado, por si hiciera falta
> volver atrás. `PlaybackPreferenceStore`, `NucLibraryItemEntity`, `NucDownloadsScreen` y el diálogo
> siguen existiendo.

- [ ] **Step 1: Saltear la preferencia en el player**

En `PlayerViewModel.kt`, en `load()`, cambiar la rama `SourceKind.WEB` para que llame directamente a
`loadWeb(episodeId)` en vez de a `loadWebRespectingPreference(episodeId)`, y dejar en el KDoc de
`loadWebRespectingPreference` una nota de que quedó sin cablear:

```kotlin
    /**
     * DESCONECTADA desde que las descargas van al dispositivo: `load()` llama a [loadWeb] directo.
     * Se conserva porque la maquinaria de reproducción remota desde la NUC sigue completa y
     * volver a cablearla es cambiar esta única línea.
     */
```

- [ ] **Step 2: Quitar la entrada de navegación a `NucDownloadsScreen`**

En `ArkivRoot.kt:425`, quitar el destino (o el ítem de menú que navega a él). Dejar el composable
importado y sin usar produce un warning: quitar también el import y dejar el archivo en su lugar.

- [ ] **Step 3: Compilar y correr todos los tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests en verde.

- [ ] **Step 4: Verificación final en los dos dispositivos**

En el S24+ y en el Fire TV Stick:

1. Guardar una película por **torrent**, esperar, reproducir. Debe salir del archivo local (verificar
   con el celular en modo avión tras la descarga).
2. Guardar un capítulo por **web**, esperar las dos fases, reproducir en modo avión.
3. Guardar un episodio de **archive.org** desde el detalle, reproducir en modo avión.
4. Castear a la TV algo guardado y confirmar que se ve (es lo que valida el `LocalFileServer`).
5. Intentar guardar un torrent de más de 5 GB y confirmar que pide confirmación antes de bajar.
6. Borrar el archivo a mano desde los ajustes de Android y dar play: debe caer a streaming sin
   pantalla negra.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "refactor(descargas): desconectar la reproduccion remota desde la NUC"
```

---

## Notas de verificación

Los dispositivos de prueba y sus modos de conexión (ADB WiFi al S24+, ADB de red al Fire TV Stick en
el puerto 5555) están documentados en las notas del proyecto. El paso 4 de la Task 20 es la única
verificación que cubre el recorrido completo de las tres fuentes: no darla por hecha a partir de que
los tests unitarios pasen — los tests cubren la lógica pura, no que un torrent real termine de bajar.
