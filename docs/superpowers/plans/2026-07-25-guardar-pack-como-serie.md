# Guardar un pack como serie — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Al tocar una fuente marcada PACK, abrir un diálogo que lista los capítulos del torrent y permite guardarlo (todo o un subconjunto, con título renombrable) como una entrada propia en biblioteca para luego elegir qué capítulo reproducir.

**Architecture:** Un parser puro de nombres (`PackFileParser`) alimenta un builder puro de filas (`PackRowBuilder`); un resolver fino (`PackResolver`) une el `TorrentEngine` con esos builders; un método de repositorio (`savePackAsSeries`) indexa el pack como 1 ítem con N episodios (uno por archivo, cada uno con su `torrentFileIndex`); y un composable compartido (`PackDialog`) orquesta resolución + selección + guardado, reutilizado por las 3 pantallas de detalle. Navegar/reproducir capítulos lo da el `DetailScreen` existente.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Room (DAO existente), JUnit4.

## Global Constraints

- Commits con identidad **lordmacu** (`user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`), **sin** línea `Co-Authored-By`.
- Solo commitear archivos propios de esta feature (hay una sesión concurrente tocando otros archivos en el working tree).
- El ítem del pack usa id `torrent:<infoHashHex>` y `categoryOverride = "series"`.
- El título por defecto es `"<Título> — Pack"`, **editable** en el diálogo; el texto final se pasa tal cual (sin re-forzar sufijo).
- "Reproducir uno" guarda igual el ítem-pack completo (el player lee de la DB) y salta al player en `"<itemId>::<fileIndex>"`.
- Aplica en películas, series y anime; las fuentes de un solo episodio **no cambian** de comportamiento.

---

### Task 1: `PackFileParser` (parseo de nombre → temporada/episodio/absoluto)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/torrent/PackFileParser.kt`
- Test: `app/src/test/java/com/arkiv/player/torrent/PackFileParserTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `data class PackFileInfo(val season: Int?, val episode: Int?, val absolute: Int?)` y `object PackFileParser { fun parse(name: String): PackFileInfo }`.

Nota: **no** se modifica `EpisodeFilePicker` (para no arriesgar regresión en su test existente). `PackFileParser` es un parser independiente con grupos de captura, la lógica de reconocimiento espejo de la del picker (S01E02 / 1x05 / Cap NEE / número absoluto de anime, ignorando resolución/codec/año).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class PackFileParserTest {
    private fun p(s: String) = PackFileParser.parse(s)

    @Test fun `SxxEyy`() = assertEquals(PackFileInfo(1, 2, null), p("House.of.the.Dragon.S01E02.1080p.x264-GRP.mkv"))
    @Test fun `NxNN`() = assertEquals(PackFileInfo(1, 5, null), p("Show 1x05 [720p].mkv"))
    @Test fun `Cap NEE espanol`() = assertEquals(PackFileInfo(1, 2, null), p("Serie Cap.102 Latino.mkv"))
    @Test fun `absoluto anime`() = assertEquals(PackFileInfo(null, null, 1085), p("One Piece - 1085 [1080p].mkv"))
    @Test fun `no confunde resolucion ni año`() = assertEquals(PackFileInfo(1, 2, null), p("The.Show.S01E02.1080p.h265.2019.mkv"))
    @Test fun `basura sin numero de episodio`() = assertEquals(PackFileInfo(null, null, null), p("readme.txt"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.torrent.PackFileParserTest"`
Expected: FAIL con "unresolved reference: PackFileParser".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.torrent

/** Parseo de un nombre de archivo a (temporada, episodio, absoluto). Espejo del reconocimiento de
 *  [EpisodeFilePicker], con grupos de captura, para listar los capítulos de un pack. */
data class PackFileInfo(val season: Int?, val episode: Int?, val absolute: Int?)

object PackFileParser {
    private val SE = Regex("""s(\d{1,2})\s*e(\d{1,3})(?!\d)""")
    private val NX = Regex("""\b(\d{1,2})x(\d{1,3})(?!\d)""")
    private val CAP = Regex("""cap\w*\s*0*(\d)(\d{2})(?!\d)""")
    // Enmascara resolución/codec/año antes de buscar el número absoluto (anime).
    private val BAD = Regex("""\d+\s*(p|bit)\b|x\s*2\s*6[45]|h\s*26[45]|\b(19|20)\d{2}\b""")

    fun parse(name: String): PackFileInfo {
        val n = name.lowercase().replace('.', ' ').replace('_', ' ')
        SE.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        NX.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        CAP.find(n)?.let { return PackFileInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        val abs = Regex("""\b(\d{1,4})\b""").findAll(BAD.replace(n, " "))
            .map { it.groupValues[1].toInt() }.firstOrNull { it in 1..3000 }
        return PackFileInfo(null, null, abs)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.torrent.PackFileParserTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/torrent/PackFileParser.kt app/src/test/java/com/arkiv/player/torrent/PackFileParserTest.kt
git commit -m "feat(pack): PackFileParser (nombre de archivo -> temporada/episodio/absoluto)"
```

---

### Task 2: `PackFileRow` + `PackRowBuilder` (archivos del torrent → filas mostrables)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/PackContents.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/PackRowBuilderTest.kt`

**Interfaces:**
- Consumes: `PackFileParser.parse` (Task 1); `com.arkiv.player.torrent.TorrentFile(index, name, sizeBytes)`; `com.arkiv.player.data.catalog.QualityLabel.extract(name)`; `com.arkiv.player.data.MetadataParser.cleanName(name)`.
- Produces: `data class PackFileRow(val index: Int, val label: String, val sizeBytes: Long, val quality: String, val section: String, val orderIndex: Int)` y `object PackRowBuilder { fun build(files: List<TorrentFile>): List<PackFileRow> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.catalog

import com.arkiv.player.torrent.TorrentFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PackRowBuilderTest {
    @Test fun `series arma etiqueta seccion y orden por temporada-episodio`() {
        val rows = PackRowBuilder.build(listOf(
            TorrentFile(0, "Show.S01E02.1080p.mkv", 200),
            TorrentFile(1, "Show.S01E01.1080p.mkv", 100),
        ))
        // Ordenadas por orderIndex (1002 > 1001): E01 primero.
        assertEquals(listOf(1, 0), rows.map { it.index })
        assertEquals("T1 · E1", rows[0].label)
        assertEquals("Temporada 1", rows[0].section)
        assertEquals(1001, rows[0].orderIndex)
        assertEquals("1080p", rows[0].quality)
        assertEquals(100L, rows[0].sizeBytes)
    }

    @Test fun `anime absoluto sin seccion`() {
        val rows = PackRowBuilder.build(listOf(TorrentFile(3, "One Piece - 1085 [720p].mkv", 500)))
        assertEquals("Ep 1085", rows[0].label)
        assertEquals("", rows[0].section)
        assertEquals(1085, rows[0].orderIndex)
    }

    @Test fun `sin parseo usa nombre limpio y posicion`() {
        val rows = PackRowBuilder.build(listOf(TorrentFile(7, "pelicula_random.mkv", 900)))
        assertEquals("", rows[0].section)
        assertEquals(7, rows[0].index)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.PackRowBuilderTest"`
Expected: FAIL con "unresolved reference: PackRowBuilder".

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.arkiv.player.data.catalog

import com.arkiv.player.data.MetadataParser
import com.arkiv.player.torrent.PackFileParser
import com.arkiv.player.torrent.TorrentFile

/** Una fila mostrable del diálogo de pack: un archivo de video con su etiqueta/sección/orden. */
data class PackFileRow(
    val index: Int,        // torrentFileIndex dentro del torrent
    val label: String,     // "T1 · E2" | "Ep 1085" | nombre limpio
    val sizeBytes: Long,
    val quality: String,   // "1080p" | ""
    val section: String,   // "Temporada 1" | ""
    val orderIndex: Int,
)

object PackRowBuilder {
    fun build(files: List<TorrentFile>): List<PackFileRow> = files.mapIndexed { pos, f ->
        val info = PackFileParser.parse(f.name)
        val (label, section, order) = when {
            info.season != null && info.episode != null ->
                Triple("T${info.season} · E${info.episode}", "Temporada ${info.season}", info.season * 1000 + info.episode)
            info.absolute != null ->
                Triple("Ep ${info.absolute}", "", info.absolute)
            else ->
                Triple(MetadataParser.cleanName(f.name), "", pos)
        }
        PackFileRow(f.index, label, f.sizeBytes, QualityLabel.extract(f.name), section, order)
    }.sortedBy { it.orderIndex }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.PackRowBuilderTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/PackContents.kt app/src/test/java/com/arkiv/player/data/catalog/PackRowBuilderTest.kt
git commit -m "feat(pack): PackFileRow + PackRowBuilder (archivos -> filas con etiqueta/seccion/orden)"
```

---

### Task 3: `PackEntities` (builder puro) + `repository.savePackAsSeries`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/PackEntities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (agregar método `savePackAsSeries`)
- Test: `app/src/test/java/com/arkiv/player/data/PackEntitiesTest.kt`

**Interfaces:**
- Consumes: `PackFileRow` (Task 2); `com.arkiv.player.data.db.ItemEntity`, `EpisodeEntity`.
- Produces:
  - `object PackEntities { fun build(title: String, posterUrl: String, description: String?, infoHashHex: String, infoBase64: String, rows: List<PackFileRow>, addedAt: Long): Pair<ItemEntity, List<EpisodeEntity>> }`
  - `suspend fun ArkivRepository.savePackAsSeries(title: String, posterUrl: String, description: String?, infoHashHex: String, infoBytes: ByteArray, files: List<PackFileRow>): String` (devuelve itemId).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.catalog.PackFileRow
import org.junit.Assert.assertEquals
import org.junit.Test

class PackEntitiesTest {
    private val rows = listOf(
        PackFileRow(1, "T1 · E1", 100, "1080p", "Temporada 1", 1001),
        PackFileRow(0, "T1 · E2", 200, "1080p", "Temporada 1", 1002),
    )

    @Test fun `arma item pack y episodios por archivo`() {
        val (item, eps) = PackEntities.build(
            title = "House of the Dragon — Pack", posterUrl = "http://p/x.jpg", description = "desc",
            infoHashHex = "abc123", infoBase64 = "BYTES", rows = rows, addedAt = 42L,
        )
        assertEquals("torrent:abc123", item.identifier)
        assertEquals("House of the Dragon — Pack", item.title)
        assertEquals("desc", item.description)
        assertEquals("http://p/x.jpg", item.thumbnailUrl)
        assertEquals("series", item.categoryOverride)
        assertEquals("torrent", item.source)
        assertEquals("BYTES", item.torrentData)
        assertEquals(42L, item.addedAt)

        assertEquals(2, eps.size)
        val e1 = eps.first { it.displayName == "T1 · E1" }
        assertEquals("torrent:abc123::1", e1.id)
        assertEquals("torrent:abc123", e1.itemId)
        assertEquals("Temporada 1", e1.section)
        assertEquals(1001, e1.orderIndex)
        assertEquals(1, e1.torrentFileIndex)
        assertEquals(100L, e1.originalSize)
        assertEquals(null, e1.torrentData) // el .torrent vive en el ítem, no por episodio
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.PackEntitiesTest"`
Expected: FAIL con "unresolved reference: PackEntities".

- [ ] **Step 3a: Write minimal implementation (PackEntities.kt)**

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.ItemEntity

/** Construye (item + episodios) de un pack. Puro/JVM (sin android.*) para poder testearse. El
 *  Base64 de los infoBytes se pasa ya codificado por el repositorio. */
object PackEntities {
    fun build(
        title: String, posterUrl: String, description: String?, infoHashHex: String,
        infoBase64: String, rows: List<PackFileRow>, addedAt: Long,
    ): Pair<ItemEntity, List<EpisodeEntity>> {
        val itemId = "torrent:$infoHashHex"
        val item = ItemEntity(
            identifier = itemId, title = title, description = description, thumbnailUrl = posterUrl,
            addedAt = addedAt, categoryOverride = "series", source = "torrent", torrentData = infoBase64,
        )
        val episodes = rows.map { r ->
            EpisodeEntity(
                id = "$itemId::${r.index}", itemId = itemId, section = r.section, displayName = r.label,
                orderIndex = r.orderIndex, durationSeconds = 0.0, thumbPath = null, originalPath = null,
                originalFormat = null, originalSize = r.sizeBytes, derivativePath = null,
                derivativeFormat = null, derivativeSize = 0, torrentFileIndex = r.index, torrentData = null,
            )
        }
        return item to episodes
    }
}
```

- [ ] **Step 3b: Add `savePackAsSeries` to ArkivRepository**

Agregar (junto a `addTorrent`, ~línea 212 de `ArkivRepository.kt`):

```kotlin
/**
 * Guarda un pack (temporada/serie completa) como UNA entrada propia de biblioteca con N episodios
 * (uno por archivo de video, cada uno con su torrentFileIndex). El título llega ya final (renombrable
 * por el usuario). Un solo .torrent para todo el pack (guardado en el ítem). Devuelve el itemId.
 */
suspend fun savePackAsSeries(
    title: String,
    posterUrl: String,
    description: String?,
    infoHashHex: String,
    infoBytes: ByteArray,
    files: List<com.arkiv.player.data.catalog.PackFileRow>,
): String {
    val b64 = android.util.Base64.encodeToString(infoBytes, android.util.Base64.NO_WRAP)
    val (item, episodes) = PackEntities.build(title, posterUrl, description, infoHashHex, b64, files, clock())
    itemDao.replaceItem(item, episodes)
    return item.identifier
}
```

- [ ] **Step 4: Run test + compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.PackEntitiesTest" && ./gradlew :app:compileDebugKotlin`
Expected: PASS (1 test) y compila.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/PackEntities.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/PackEntitiesTest.kt
git commit -m "feat(pack): PackEntities + repository.savePackAsSeries (indexa el pack como serie propia)"
```

---

### Task 4: `PackResolver` (une TorrentEngine + builders) + AppGraph

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/PackContents.kt` (agregar `PackContents` + `PackResolver`)
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (agregar `packResolver`)

**Interfaces:**
- Consumes: `TorrentSearchApi.resolveSource(result): TorrentSource?`, `TorrentSource.Magnet(uri)`, `TorrentSource.TorrentFile(bytes)`; `TorrentEngine.resolveMagnet(uri)`, `resolveTorrent(bytes)`, `videoFiles(meta): List<TorrentFile>`, `meta.infoHashHex`, `meta.infoBytes`; `PackRowBuilder.build` (Task 2).
- Produces: `data class PackResolver.PackContents(val infoHashHex: String, val infoBytes: ByteArray, val rows: List<PackFileRow>)` y `class PackResolver(...) { suspend fun resolve(result: TorrentResult): PackContents? }`; `AppGraph.packResolver: PackResolver`.

Sin test unitario (depende del `TorrentEngine`, que necesita red/peers); se valida en device en Task 6. Es un wrapper fino sobre piezas ya testeadas.

- [ ] **Step 1: Implementación (agregar al final de `PackContents.kt`)**

```kotlin
import com.arkiv.player.torrent.TorrentEngine

/** Resuelve la metadata de una fuente pack y arma sus filas mostrables. Null si no se pudo (sin
 *  seeds/timeout, o sin video). El resolver de magnet puede tardar (hasta ~45s buscando peers). */
class PackResolver(
    private val torrentSearchApi: TorrentSearchApi,
    private val torrentEngine: TorrentEngine,
) {
    data class PackContents(val infoHashHex: String, val infoBytes: ByteArray, val rows: List<PackFileRow>)

    suspend fun resolve(result: TorrentResult): PackContents? {
        val source = torrentSearchApi.resolveSource(result) ?: return null
        val meta = when (source) {
            is TorrentSource.Magnet -> torrentEngine.resolveMagnet(source.uri)
            is TorrentSource.TorrentFile -> torrentEngine.resolveTorrent(source.bytes)
        } ?: return null
        val files = torrentEngine.videoFiles(meta)
        if (files.isEmpty()) return null
        return PackContents(meta.infoHashHex, meta.infoBytes, PackRowBuilder.build(files))
    }
}
```

- [ ] **Step 2: Registrar en AppGraph**

En `AppGraph.kt`, junto a los demás `by lazy` (cerca de `animeSourceProvider`/`torrentEngine`):

```kotlin
val packResolver: com.arkiv.player.data.catalog.PackResolver by lazy {
    com.arkiv.player.data.catalog.PackResolver(torrentSearchApi, torrentEngine)
}
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compila sin errores.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/PackContents.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(pack): PackResolver (resuelve metadata del pack -> filas) + AppGraph"
```

---

### Task 5: `PackDialog` (composable compartido)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/catalog/PackDialog.kt`

**Interfaces:**
- Consumes: `PackResolver` (Task 4) y su `PackContents`; `PackFileRow` (Task 2); `TorrentResult`.
- Produces:
```kotlin
@Composable
fun PackDialog(
    result: TorrentResult,
    packResolver: PackResolver,
    defaultTitle: String,
    onDismiss: () -> Unit,
    onSave: (title: String, contents: PackResolver.PackContents, rows: List<PackFileRow>) -> Unit,
    onPlayOne: (title: String, contents: PackResolver.PackContents, row: PackFileRow) -> Unit,
)
```

Sin test unitario (UI Compose); se valida en device en Task 6.

- [ ] **Step 1: Implementación**

```kotlin
package com.arkiv.player.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.catalog.PackResolver
import com.arkiv.player.data.catalog.TorrentResult

/** Diálogo de un pack: resuelve sus archivos, permite renombrar el título, elegir capítulos y
 *  guardar (todo/seleccionados) o reproducir uno. */
@Composable
fun PackDialog(
    result: TorrentResult,
    packResolver: PackResolver,
    defaultTitle: String,
    onDismiss: () -> Unit,
    onSave: (title: String, contents: PackResolver.PackContents, rows: List<PackFileRow>) -> Unit,
    onPlayOne: (title: String, contents: PackResolver.PackContents, row: PackFileRow) -> Unit,
) {
    var contents by remember { mutableStateOf<PackResolver.PackContents?>(null) }
    var failed by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(defaultTitle) }
    val selected = remember { mutableStateListOf<Int>() } // torrentFileIndex marcados

    LaunchedEffect(result) {
        val c = runCatching { packResolver.resolve(result) }.getOrNull()
        if (c == null) failed = true else { contents = c; selected.addAll(c.rows.map { it.index }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val c = contents
            if (c != null) TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(title.trim().ifBlank { defaultTitle }, c, c.rows.filter { it.index in selected }) },
            ) { Text(if (selected.size == c.rows.size) "Guardar todo" else "Guardar (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Guardar pack") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    failed -> Text("No se pudo leer el pack (sin seeds ahora).")
                    contents == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Leyendo el pack…")
                    }
                    else -> {
                        val c = contents!!
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(c.rows) { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = row.index in selected,
                                        onCheckedChange = { on -> if (on) selected.add(row.index) else selected.remove(row.index) },
                                    )
                                    Column(
                                        Modifier.weight(1f).clickable { onPlayOne(title.trim().ifBlank { defaultTitle }, c, row) },
                                    ) {
                                        Text(row.label)
                                        val meta = listOfNotNull(
                                            row.quality.ifBlank { null },
                                            if (row.sizeBytes > 0) "%.0f MB".format(row.sizeBytes / 1_048_576.0) else null,
                                        ).joinToString("  ·  ")
                                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Text("Tocá un capítulo para reproducirlo ya.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
}
```

- [ ] **Step 2: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: compila.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/PackDialog.kt
git commit -m "feat(pack): PackDialog (resolver + renombrar + elegir capitulos + guardar/reproducir)"
```

---

### Task 6: Wiring en `CineDetailScreen` + navegación `onOpenItem`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (pasar `onOpenItem` a `CineDetailScreen`)

**Interfaces:**
- Consumes: `PackDialog` (Task 5), `graph.packResolver` (Task 4), `graph.repository.savePackAsSeries` (Task 3), `PackDetector.isPack` (existente), `com.arkiv.player.data.catalog.PackFileRow`.
- Produces: parámetro nuevo `onOpenItem: (String) -> Unit` en `CineDetailScreen`.

- [ ] **Step 1: Agregar el parámetro `onOpenItem` a la firma de `CineDetailScreen`**

Buscar `fun CineDetailScreen(` y agregar el parámetro (con default no-op para no romper otras llamadas):

```kotlin
onOpenItem: (String) -> Unit = {},
```

- [ ] **Step 2: Estado del diálogo + intercepción del tap en fuentes PACK**

Dentro del composable, agregar estado:

```kotlin
var packFor by remember { mutableStateOf<com.arkiv.player.data.catalog.TorrentResult?>(null) }
```

Donde hoy una fuente torrent se reproduce (en `playSource`/`SourceRow` → `play(s.result)`), interceptar los packs. En el punto donde se maneja `PlaySource.Torrent`, cambiar a:

```kotlin
is PlaySource.Torrent ->
    if (com.arkiv.player.data.catalog.PackDetector.isPack(s.result.name)) packFor = s.result
    else play(s.result)
```

- [ ] **Step 3: Renderizar el diálogo**

Cerca del final del composable (junto a otros diálogos/overlays), con `d` = detalle del catálogo ya cargado:

```kotlin
packFor?.let { r ->
    val d = detail
    if (d != null) PackDialog(
        result = r,
        packResolver = graph.packResolver,
        defaultTitle = "${d.title} — Pack",
        onDismiss = { packFor = null },
        onSave = { title, contents, rows ->
            scope.launch {
                val id = graph.repository.savePackAsSeries(title, d.posterUrl, d.overview, contents.infoHashHex, contents.infoBytes, rows)
                packFor = null
                onOpenItem(id)
            }
        },
        onPlayOne = { title, contents, row ->
            scope.launch {
                val id = graph.repository.savePackAsSeries(title, d.posterUrl, d.overview, contents.infoHashHex, contents.infoBytes, contents.rows)
                packFor = null
                onPlay("$id::${row.index}")
            }
        },
    )
}
```

Nota: `detail` es `TmdbDetail?` y su descripción es `d.overview` (String; puede venir vacío — se pasa igual).

- [ ] **Step 4: Pasar `onOpenItem` desde ArkivRoot**

En `ArkivRoot.kt`, en `composable("cine/{type}/{tmdbId}")`, agregar a `CineDetailScreen(...)`:

```kotlin
onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
```

- [ ] **Step 5: Compilar, instalar, verificar en device**

Run:
```bash
./gradlew :app:assembleDebug -q && adb -s R5CX7251VRM install -r app/build/outputs/apk/debug/app-debug.apk
```
Verificación manual (gate real): abrir **House of the Dragon** → en la lista de fuentes, tocar la fuente pack `S01.COMPLETE` (badge naranja) → aparece el diálogo "Leyendo el pack…" → lista T1·E1…E10 con tamaños → renombrar (opcional) → "Guardar todo" → la biblioteca muestra "House of the Dragon — Pack" → abrir → el detalle agrupa por Temporada 1 → tocar E2 reproduce el archivo correcto (verificar en logcat: `ArkivTorrent: STREAM ... file=N 'House.of.the.Dragon.S01E02...'`). Probar también tocar un capítulo dentro del diálogo (reproducir uno).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(pack): abrir PackDialog al tocar un pack en peliculas/series (CineDetailScreen) + nav a detalle"
```

---

### Task 7: Wiring en `ShowDetailScreen` y `AnimeShowDetailScreen`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/ShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt` (pasar `onOpenItem` a ambas)

**Interfaces:**
- Consumes: idéntico a Task 6 (`PackDialog`, `graph.packResolver`, `savePackAsSeries`, `PackDetector.isPack`).
- Produces: parámetro `onOpenItem: (String) -> Unit` en `ShowDetailScreen` y `AnimeShowDetailScreen`.

- [ ] **Step 1: `ShowDetailScreen` — mismo patrón que Task 6**

Agregar `onOpenItem: (String) -> Unit = {}` a la firma. Agregar `var packFor by remember { mutableStateOf<com.arkiv.player.data.catalog.TorrentResult?>(null) }`. Interceptar el tap de la fuente torrent: si `PackDetector.isPack(result.name)` → `packFor = result`, si no, la reproducción actual. Renderizar `PackDialog` con `defaultTitle = "${show.title} — Pack"`, `posterUrl = show.posterUrl`, y como descripción el campo que exponga el detalle de la serie (Cinemeta expone `description`; si el modelo usado aquí no lo tiene, pasar `null`). `onSave`→`savePackAsSeries`+`onOpenItem(id)`, `onPlayOne`→`savePackAsSeries(todas)`+`onPlay("$id::${row.index}")`.

- [ ] **Step 2: `AnimeShowDetailScreen` — mismo patrón**

Agregar `onOpenItem: (String) -> Unit = {}` a la firma (línea 78). Agregar `var packFor by remember { mutableStateOf<com.arkiv.player.data.catalog.TorrentResult?>(null) }`. En la sección TORRENT, donde `ReleaseRow(...) { play(src.result, src.episode ?: ep) }`, interceptar:

```kotlin
ReleaseRow(result = src.result, enabled = !preparing) {
    if (com.arkiv.player.data.catalog.PackDetector.isPack(src.result.name)) packFor = src.result
    else play(src.result, src.episode ?: ep)
}
```

Renderizar `PackDialog` (con `show` no-null) usando `defaultTitle = "${s.title} — Pack"`, `posterUrl = s.posterUrl`, `description = s.description` (o `null`), `onSave`/`onPlayOne` con `graph.repository.savePackAsSeries` como en Task 6.

- [ ] **Step 3: Pasar `onOpenItem` desde ArkivRoot**

En `ArkivRoot.kt`, agregar a `ShowDetailScreen(...)` (composable `catalog_show/{imdbId}`) y `AnimeShowDetailScreen(...)` (composable `catalog_anime/{anilistId}`):

```kotlin
onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
```

- [ ] **Step 4: Compilar, instalar, verificar en device**

Run:
```bash
./gradlew :app:assembleDebug -q && adb -s R5CX7251VRM install -r app/build/outputs/apk/debug/app-debug.apk
```
Verificación (gate real): en un anime con pack (o una serie del catálogo con pack), tocar la fuente pack → diálogo lista capítulos → guardar → biblioteca muestra "… — Pack" → detalle → elegir capítulo → reproduce el correcto.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/ShowDetailScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt
git commit -m "feat(pack): abrir PackDialog al tocar un pack en series y anime + nav a detalle"
```

---

## Self-Review

**Spec coverage:**
- §2.1 identidad ítem propio con "Pack" → Task 3 (`torrent:$infoHash`, título final) + Tasks 6/7 (default `"… — Pack"`). ✔
- §2.2 `PackFileParser` → Task 1. ✔ (decisión: `EpisodeFilePicker` se deja intacto para no arriesgar su test; parser espejo independiente.)
- §2.3 `PackContents` + resolución → Task 2 (`PackFileRow`/`PackRowBuilder`) + Task 4 (`PackResolver`). ✔
- §2.4 `savePackAsSeries` → Task 3. ✔
- §2.5 `PackDialog` con título editable, checkbox, tap-reproducir, guardar todo/seleccionados → Task 5. ✔
- §2.6 disparador `PackDetector.isPack` en pelis/series/anime → Tasks 6/7. ✔
- §2.7 errores (sin seeds/timeout, sin video, sin parseo) → Task 4 (resolve→null) + Task 5 (mensaje) + Task 2 (fallback nombre limpio). ✔
- §3 testing → Tasks 1/2/3 con unit tests; device en 6/7. ✔

**Placeholder scan:** sin TBD/TODO; el único punto abierto es el nombre exacto del campo de descripción del detalle (`d.description`/`d.overview`) — indicado explícitamente con fallback a `null`.

**Type consistency:** `PackFileRow`(index,label,sizeBytes,quality,section,orderIndex), `PackFileInfo`(season,episode,absolute), `PackResolver.PackContents`(infoHashHex,infoBytes,rows), `savePackAsSeries(title,posterUrl,description,infoHashHex,infoBytes,files)` usados consistentes en todas las tasks. ✔
