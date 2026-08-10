# TV "Mi biblioteca" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Darle al TV una pantalla dedicada "Mi biblioteca" con lo guardado, lo ya visto y las descargas al dispositivo, accesible desde un botón al lado de "Buscar" en el home.

**Architecture:** Una pantalla `TvLibraryScreen` con menú lateral de secciones + contenido a la derecha. Toda la lógica que se puede testear (cruce de vistos, filtro de secciones, resumen de disco) vive en objetos puros bajo `data/biblioteca/`, siguiendo la convención de `LibraryGrouping` y `DownloadGroupPolicy`. La UI reusa `TvPosterCard` y `DownloadsViewModel`, que ya existen. El home pierde sus filas de biblioteca y gana el botón de entrada.

**Tech Stack:** Kotlin, Jetpack Compose + `androidx.tv.material3`, Room, Flow/StateFlow, JUnit4.

## Global Constraints

- Los tests son JVM puros en `app/src/test/java/...`. **No hay Robolectric ni tests de UI en este repo**: nada que toque Room, `StatFs`, `Context` o Compose se testea — se verifica en el Fire TV por ADB.
- Correr tests: `./gradlew testDebugUnitTest`. Compilar: `./gradlew assembleDebug`.
- Todo el texto de UI va en español rioplatense, igual que el resto de la app ("Guardar", "Quitar", "Ya visto").
- Los comentarios de código se escriben en español y explican **por qué**, no qué — es la convención de este repo.
- Commits sin línea `Co-Authored-By`. La identidad del repo ya está configurada como `lordmacu`.
- **Otras sesiones de Claude comparten este working tree, y se trabaja directo sobre `main`.** Nunca `git add -A` ni `git add .`: agregar SIEMPRE los archivos por ruta explícita. Y commitear SIEMPRE con pathspec explícito — `git commit -m "..." -- <rutas>` — porque el índice puede tener trabajo ajeno ya preparado y un `git commit` pelado se lo lleva puesto (ya pasó una vez en esta sesión). Antes de commitear, `git diff --cached --stat` para ver qué hay.
- No cambiar de rama, no hacer `git pull`, `git rebase` ni `git reset`: `main` se mueve sola por debajo. Si un commit falla por conflicto, parar y reportar en vez de resolverlo.

---

## File Structure

**Lógica pura (nueva, testeable):**
- `app/src/main/java/com/arkiv/player/data/biblioteca/VistosDeLaBiblioteca.kt` — cruza las filas de "visto" con los grupos de la biblioteca.
- `app/src/main/java/com/arkiv/player/data/biblioteca/SeccionDeBiblioteca.kt` — el enum de secciones del menú y el filtro de grupos.
- `app/src/main/java/com/arkiv/player/data/biblioteca/EspacioEnDisco.kt` — resumen de disco y suma de lo ocupado por descargas.

**Datos (modificar):**
- `app/src/main/java/com/arkiv/player/data/db/Daos.kt` — `data class VistoRow` + `PlaybackDao.observeVistos()`.
- `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` — `observeVistos()`.
- `app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt` — `espacioLibreBytes()`.

**UI (nueva):**
- `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryViewModel.kt`
- `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryScreen.kt` — menú lateral + grillas + diálogos.
- `app/src/main/java/com/arkiv/player/ui/tv/library/TvDownloadsSection.kt` — la sección de descargas.

**UI (modificar):**
- `app/src/main/java/com/arkiv/player/ui/tv/TvPosterCard.kt` — sumar `subtitle` y `onLongClick` opcionales.
- `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` — ruta `library`.
- `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` — botón nuevo, quitar filas de biblioteca, arreglar foco inicial.

**Tests (nuevos):**
- `app/src/test/java/com/arkiv/player/data/biblioteca/VistosDeLaBibliotecaTest.kt`
- `app/src/test/java/com/arkiv/player/data/biblioteca/SeccionDeBibliotecaTest.kt`
- `app/src/test/java/com/arkiv/player/data/biblioteca/EspacioEnDiscoTest.kt`

### Desvíos del spec (deliberados, ya decididos)

1. **ViewModel propio en vez de `HomeViewModel`.** El spec decía reusar `HomeViewModel`. No: ese ViewModel arrastra todo el motor de descubrimiento (pide géneros a TMDB y AniList en su `init` y cachea ~40 filas). Abrir la biblioteca crearía una segunda instancia completa de eso para una pantalla que no muestra descubrimiento. `TvLibraryViewModel` sale de `observeLibraryGroups()` y `observeVistos()` y nada más.
2. **Grilla adaptativa, no 6 columnas fijas.** Con el menú de 220 dp, en un Fire TV de 1080p entran ~4 columnas de póster, no 6. `GridCells.Adaptive(minSize = 150.dp)` se acomoda solo.
3. **Descargas a nivel de grupo, no de capítulo.** `DownloadsViewModel` ya expone `cancelGroup` / `removeGroup` / `retryFailedGroup` y `DownloadGroupPolicy.summarize` ya arma el "3 de 24 guardados · 1 bajando". Una fila por serie con acciones de grupo cubre el caso real (una temporada entera encolada) sin construir una pantalla anidada de capítulos.
4. **El foco inicial del home cae en el botón "Buscar", no en una fila.** Ver Task 8: es la única forma determinista de matarlo el bug de scroll, porque la barra superior no vive dentro del `LazyColumn`.
5. **`dpadFocusEscape()` no se usa.** Solo maneja arriba/abajo (existe para sacar el foco de un `TextField`). Entre el menú y la grilla, la búsqueda de foco 2D de Compose ya resuelve izquierda/derecha sola.

---

### Task 1: Cruce de "ya visto" con la biblioteca (puro)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/biblioteca/VistosDeLaBiblioteca.kt`
- Test: `app/src/test/java/com/arkiv/player/data/biblioteca/VistosDeLaBibliotecaTest.kt`

**Interfaces:**
- Consumes: `com.arkiv.player.data.LibraryGroup` (ya existe: `key`, `primary`, `members`), `com.arkiv.player.data.db.LibraryRow`.
- Produces:
  - `data class VistoDeItem(val itemId: String, val episodios: Int, val ultimoVistoMs: Long)`
  - `data class GrupoVisto(val grupo: LibraryGroup, val capitulosVistos: Int, val ultimoVistoMs: Long)`
  - `VistosDeLaBiblioteca.cruzar(grupos: List<LibraryGroup>, vistos: List<VistoDeItem>): List<GrupoVisto>`

`VistoDeItem` es el modelo puro; la fila de Room (`VistoRow`, Task 2) se mapea a él. Se separan a propósito: así este objeto se testea sin traer nada de Room al classpath del test.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/biblioteca/VistosDeLaBibliotecaTest.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Ya visto" se arma cruzando el progreso por ítem con los grupos de la biblioteca, para que una
 * serie guardada desde dos fuentes sea UNA tarjeta y no dos.
 */
class VistosDeLaBibliotecaTest {

    private fun row(id: String, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = "series",
        source = "web",
    )

    private fun grupo(key: String, vararg filas: LibraryRow) =
        LibraryGroup(key = key, primary = filas.first(), members = filas.toList())

    @Test
    fun `el conteo del grupo es el maximo entre fuentes, no la suma`() {
        // Misma serie por dos fuentes: 12 y 10 capítulos vistos. Son copias alternativas del MISMO
        // contenido, así que sumarlas (22) mentiría igual que sumaba 794 episodios de Naruto.
        val g = grupo("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val vistos = listOf(
            VistoDeItem("web:series:a", episodios = 12, ultimoVistoMs = 100L),
            VistoDeItem("torrent:series:a", episodios = 10, ultimoVistoMs = 50L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(g), vistos)
        assertEquals(1, r.size)
        assertEquals(12, r.first().capitulosVistos)
    }

    @Test
    fun `el ultimo visto del grupo es el mas reciente entre fuentes`() {
        val g = grupo("tv:46260", row("web:series:a", 24), row("torrent:series:a", 24))
        val vistos = listOf(
            VistoDeItem("web:series:a", episodios = 12, ultimoVistoMs = 100L),
            VistoDeItem("torrent:series:a", episodios = 10, ultimoVistoMs = 900L),
        )
        assertEquals(900L, VistosDeLaBiblioteca.cruzar(listOf(g), vistos).first().ultimoVistoMs)
    }

    @Test
    fun `un grupo sin nada visto queda afuera`() {
        val visto = grupo("tv:1", row("web:series:visto", 10))
        val sinVer = grupo("tv:2", row("web:series:sinver", 10))
        val vistos = listOf(VistoDeItem("web:series:visto", 3, 10L))
        val r = VistosDeLaBiblioteca.cruzar(listOf(visto, sinVer), vistos)
        assertEquals(listOf("tv:1"), r.map { it.grupo.key })
    }

    /** Un ítem que se quitó de la biblioteca deja su progreso en `playback`: no debe reaparecer. */
    @Test
    fun `una fila de visto sin grupo se ignora`() {
        val g = grupo("tv:1", row("web:series:a", 10))
        val vistos = listOf(
            VistoDeItem("web:series:a", 3, 10L),
            VistoDeItem("web:series:borrado", 5, 999L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(g), vistos)
        assertEquals(listOf("tv:1"), r.map { it.grupo.key })
    }

    @Test
    fun `ordena por ultimo visto, el mas reciente primero`() {
        val viejo = grupo("tv:viejo", row("web:series:viejo", 10))
        val nuevo = grupo("tv:nuevo", row("web:series:nuevo", 10))
        val vistos = listOf(
            VistoDeItem("web:series:viejo", 1, 100L),
            VistoDeItem("web:series:nuevo", 1, 900L),
        )
        val r = VistosDeLaBiblioteca.cruzar(listOf(viejo, nuevo), vistos)
        assertEquals(listOf("tv:nuevo", "tv:viejo"), r.map { it.grupo.key })
    }

    @Test
    fun `sin vistos devuelve vacio`() {
        val g = grupo("tv:1", row("web:series:a", 10))
        assertEquals(emptyList<GrupoVisto>(), VistosDeLaBiblioteca.cruzar(listOf(g), emptyList()))
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests '*VistosDeLaBibliotecaTest*'`
Expected: FAIL de compilación — `Unresolved reference: VistosDeLaBiblioteca`.

- [ ] **Step 3: Escribir la implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/biblioteca/VistosDeLaBiblioteca.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup

/**
 * Lo visto de UN ítem de la biblioteca. Modelo puro: la fila de Room (`VistoRow`) se mapea a esto
 * en el repositorio, para que este objeto se pueda testear sin traer Room al test.
 */
data class VistoDeItem(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** Un grupo de la biblioteca con lo que ya se vio de él. */
data class GrupoVisto(
    val grupo: LibraryGroup,
    val capitulosVistos: Int,
    val ultimoVistoMs: Long,
)

/**
 * Arma la sección "Ya visto" cruzando el progreso por ítem con los grupos de la biblioteca.
 *
 * Se cruza contra los GRUPOS y no contra los ítems sueltos por el mismo motivo que la fila de
 * Series del home: una serie guardada desde varias fuentes tiene que ser una sola tarjeta.
 */
object VistosDeLaBiblioteca {

    /**
     * [vistos] llega por itemId. Un grupo cuenta como visto si CUALQUIERA de sus miembros tiene
     * capítulos vistos.
     *
     * El conteo es el **máximo** entre miembros y no la suma: las adquisiciones son copias
     * alternativas de la misma serie, no contenido disjunto (mismo criterio que
     * `LibraryGroup.episodeCount`, donde sumar 6 adquisiciones daba 794 capítulos para una serie
     * de ~220).
     *
     * Las filas de [vistos] cuyo ítem ya no pertenece a ningún grupo se ignoran: quitar algo de la
     * biblioteca no borra su progreso de `playback`, y sin este filtro reaparecería acá para
     * siempre.
     */
    fun cruzar(grupos: List<LibraryGroup>, vistos: List<VistoDeItem>): List<GrupoVisto> {
        val porItem = vistos.associateBy { it.itemId }
        return grupos.mapNotNull { grupo ->
            val filas = grupo.members.mapNotNull { porItem[it.identifier] }
            if (filas.isEmpty()) return@mapNotNull null
            GrupoVisto(
                grupo = grupo,
                capitulosVistos = filas.maxOf { it.episodios },
                ultimoVistoMs = filas.maxOf { it.ultimoVistoMs },
            )
        }.sortedByDescending { it.ultimoVistoMs }
    }
}
```

- [ ] **Step 4: Correr el test para verificar que pasa**

Run: `./gradlew testDebugUnitTest --tests '*VistosDeLaBibliotecaTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/biblioteca/VistosDeLaBiblioteca.kt app/src/test/java/com/arkiv/player/data/biblioteca/VistosDeLaBibliotecaTest.kt
git diff --cached --stat
git commit -m "feat(biblioteca): cruce puro de lo ya visto con los grupos de la biblioteca"
```

---

### Task 2: Consulta de "ya visto" en Room

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (agregar `VistoRow` junto a `ContinueRow` arriba del archivo; agregar la query en `interface PlaybackDao`, que arranca en la línea 173)
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (agregar `observeVistos()` junto a `observeContinueWatching()`, línea 101)

**Interfaces:**
- Consumes: `VistoDeItem` de Task 1.
- Produces: `ArkivRepository.observeVistos(): Flow<List<VistoDeItem>>`

Sin test: toca Room, y este repo no tiene Robolectric (ver Global Constraints). Se verifica compilando y, al final, en el Fire TV.

- [ ] **Step 1: Agregar el modelo de fila en `Daos.kt`**

Justo debajo de `data class SerieConProgresoRow` (termina en la línea 32), agregar:

```kotlin
/** Lo visto de un ítem, para la sección "Ya visto" de la biblioteca del TV. */
data class VistoRow(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)
```

- [ ] **Step 2: Agregar la consulta a `PlaybackDao`**

Dentro de `interface PlaybackDao`, después de `observeContinueWatching` (termina en la línea 199), agregar:

```kotlin
    /**
     * Los ítems con capítulos ya vistos, con cuántos y cuándo fue el último.
     *
     * Gemela de [observeContinueWatching] pero al revés (`watched = 1`): lo que sale de "Continuar
     * viendo" al terminarlo tiene que aterrizar en algún lado, y hasta ahora no aterrizaba en
     * ninguno.
     *
     * NO hace `JOIN items`: el filtro por ítem vivo lo aplica `VistosDeLaBiblioteca.cruzar`, que ya
     * recibe los grupos (y los grupos ya excluyen los borrados). Sumar el join acá duplicaría esa
     * regla en dos lugares.
     */
    @Query(
        """
        SELECT e.itemId AS itemId, COUNT(*) AS episodios, MAX(p.lastPlayedAt) AS ultimoVistoMs
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        WHERE p.watched = 1 AND p.deleted = 0 AND e.deleted = 0
        GROUP BY e.itemId
        """
    )
    fun observeVistos(): Flow<List<VistoRow>>
```

- [ ] **Step 3: Exponerlo en el repositorio**

En `ArkivRepository.kt`, justo después del cierre de `observeContinueWatching()` (línea 110), agregar:

```kotlin
    /**
     * Lo ya visto, por ítem. El cruce contra los grupos de la biblioteca lo hace
     * [com.arkiv.player.data.biblioteca.VistosDeLaBiblioteca], que es la parte pura y testeada.
     */
    fun observeVistos(): Flow<List<com.arkiv.player.data.biblioteca.VistoDeItem>> =
        playbackDao.observeVistos().map { filas ->
            filas.map { com.arkiv.player.data.biblioteca.VistoDeItem(it.itemId, it.episodios, it.ultimoVistoMs) }
        }
```

- [ ] **Step 4: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Si Room se queja de la query, el mensaje dice exactamente qué columna no matchea con `VistoRow`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt
git diff --cached --stat
git commit -m "feat(biblioteca): consulta de capitulos ya vistos por item"
```

---

### Task 3: Secciones del menú y filtro (puro)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/biblioteca/SeccionDeBiblioteca.kt`
- Test: `app/src/test/java/com/arkiv/player/data/biblioteca/SeccionDeBibliotecaTest.kt`

**Interfaces:**
- Consumes: `LibraryGroup`.
- Produces:
  - `enum class SeccionDeBiblioteca(val etiqueta: String)` con entradas `TODO_LO_GUARDADO`, `SERIES`, `PELICULAS`, `VISTOS`, `DESCARGAS` — en ese orden, que es el orden del menú.
  - `FiltroDeBiblioteca.grupos(seccion, grupos): List<LibraryGroup>?`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/biblioteca/SeccionDeBibliotecaTest.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeccionDeBibliotecaTest {

    private fun row(id: String, categoria: String?, eps: Int) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = 0L,
        categoryOverride = categoria,
        source = "web",
    )

    private val serie = LibraryGroup("tv:1", row("s", "series", 24), listOf(row("s", "series", 24)))
    private val peli = LibraryGroup("item:p", row("p", "movie", 1), listOf(row("p", "movie", 1)))
    private val todos = listOf(serie, peli)

    @Test
    fun `todo devuelve los grupos sin tocar`() {
        assertEquals(todos, FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.TODO_LO_GUARDADO, todos))
    }

    @Test
    fun `series deja fuera las peliculas`() {
        assertEquals(listOf(serie), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.SERIES, todos))
    }

    @Test
    fun `peliculas deja fuera las series`() {
        assertEquals(listOf(peli), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.PELICULAS, todos))
    }

    /** Un ítem de un solo episodio sin override es película por detección automática. */
    @Test
    fun `un item de un episodio sin override cuenta como pelicula`() {
        val suelto = LibraryGroup("item:x", row("x", null, 1), listOf(row("x", null, 1)))
        assertEquals(listOf(suelto), FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.PELICULAS, listOf(suelto)))
    }

    /** Estas dos NO salen de la biblioteca guardada: pedirlas acá es un error del llamador. */
    @Test
    fun `vistos y descargas no salen de esta lista`() {
        assertNull(FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.VISTOS, todos))
        assertNull(FiltroDeBiblioteca.grupos(SeccionDeBiblioteca.DESCARGAS, todos))
    }

    @Test
    fun `las etiquetas del menu van en el orden de la pantalla`() {
        assertEquals(
            listOf("Todo", "Series", "Películas", "Ya visto", "Descargas"),
            SeccionDeBiblioteca.values().map { it.etiqueta },
        )
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests '*SeccionDeBibliotecaTest*'`
Expected: FAIL de compilación — `Unresolved reference: SeccionDeBiblioteca`.

- [ ] **Step 3: Escribir la implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/biblioteca/SeccionDeBiblioteca.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup

/**
 * Las secciones del menú lateral de "Mi biblioteca", en el orden en que se dibujan.
 *
 * Esto ES el filtro "Todas / Películas / Series" del teléfono (`LibraryScreen.LibFilter`): en el TV
 * no se duplica como chips arriba porque con el control remoto subir hasta una fila de chips y
 * volver a bajar en cada cambio de sección es un viaje largo, y dos controles para lo mismo se
 * pelean por el foco.
 *
 * `TODO_LO_GUARDADO` se llama así y no `TODO` para no chocar de lectura con `kotlin.TODO()`.
 */
enum class SeccionDeBiblioteca(val etiqueta: String) {
    TODO_LO_GUARDADO("Todo"),
    SERIES("Series"),
    PELICULAS("Películas"),
    VISTOS("Ya visto"),
    DESCARGAS("Descargas"),
}

object FiltroDeBiblioteca {

    /**
     * Los grupos que le tocan a [seccion], o **null** si esa sección no sale de la biblioteca
     * guardada.
     *
     * Null y no lista vacía a propósito: `VISTOS` y `DESCARGAS` tienen su propia fuente de datos, y
     * devolver vacío haría que un llamador equivocado dibujara "no guardaste nada" sobre una
     * sección que en realidad está llena.
     *
     * Se filtra por `primary.isMovie` y no por el grupo entero porque `LibraryGrouping.groupKeyOf`
     * ya garantiza que una película es siempre un grupo de una sola fila (nunca se agrupan: el
     * tmdbId del artwork se equivoca en películas).
     */
    fun grupos(seccion: SeccionDeBiblioteca, grupos: List<LibraryGroup>): List<LibraryGroup>? =
        when (seccion) {
            SeccionDeBiblioteca.TODO_LO_GUARDADO -> grupos
            SeccionDeBiblioteca.SERIES -> grupos.filter { !it.primary.isMovie }
            SeccionDeBiblioteca.PELICULAS -> grupos.filter { it.primary.isMovie }
            SeccionDeBiblioteca.VISTOS, SeccionDeBiblioteca.DESCARGAS -> null
        }
}
```

- [ ] **Step 4: Correr el test para verificar que pasa**

Run: `./gradlew testDebugUnitTest --tests '*SeccionDeBibliotecaTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/biblioteca/SeccionDeBiblioteca.kt app/src/test/java/com/arkiv/player/data/biblioteca/SeccionDeBibliotecaTest.kt
git diff --cached --stat
git commit -m "feat(biblioteca): secciones del menu lateral del TV y su filtro"
```

---

### Task 4: Espacio en disco

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/biblioteca/EspacioEnDisco.kt`
- Test: `app/src/test/java/com/arkiv/player/data/biblioteca/EspacioEnDiscoTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt` (después de `hasFreeSpaceFor`, línea 62)

**Interfaces:**
- Consumes: `DownloadGroup`, `GroupedEpisode`, `EpisodeDownloadStatus` de `com.arkiv.player.data.local`; `TorrentSizeGate.formatSize(bytes: Long): String` (ya existe, devuelve "12.4 GB" / "820 MB").
- Produces:
  - `EspacioEnDisco.ocupadoPorDescargas(grupos: List<DownloadGroup>): Long`
  - `EspacioEnDisco.resumen(libresBytes: Long, ocupadoBytes: Long): String`
  - `LocalDownloadManager.espacioLibreBytes(): Long`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/biblioteca/EspacioEnDiscoTest.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.db.DownloadRow
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.GroupedEpisode
import com.arkiv.player.data.local.LocalDownloadState
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

class EspacioEnDiscoTest {

    private val GB = 1L shl 30

    // Ojo: es `data.model.Episode` (el del dominio), NO `db.EpisodeEntity`. Sus variantes de video
    // son objetos (`original`/`derivative`), no columnas sueltas.
    private fun episodio(id: String) = Episode(
        id = id,
        itemId = "item",
        section = "",
        displayName = id,
        orderIndex = 0,
        durationSeconds = 0.0,
        thumbPath = null,
        original = null,
        derivative = null,
    )

    private fun bajado(id: String, bytesDone: Long) = GroupedEpisode(
        episode = episodio(id),
        status = EpisodeDownloadStatus.Tracked(
            DownloadRow(
                episodeId = id,
                itemId = "item",
                itemTitle = "item",
                displayName = id,
                thumbPath = null,
                state = LocalDownloadState.COMPLETED,
                progress = 1f,
                localUri = null,
                bytes = bytesDone,
                source = "web",
                error = null,
                bytesDone = bytesDone,
            ),
        ),
    )

    private fun sinBajar(id: String) =
        GroupedEpisode(episode = episodio(id), status = EpisodeDownloadStatus.NotDownloaded)

    private fun grupo(vararg eps: GroupedEpisode) =
        DownloadGroup("item", "Serie", "", "web", eps.toList())

    @Test
    fun `suma los bytes de todos los grupos`() {
        val grupos = listOf(grupo(bajado("a", 2 * GB)), grupo(bajado("b", 1 * GB)))
        assertEquals(3 * GB, EspacioEnDisco.ocupadoPorDescargas(grupos))
    }

    @Test
    fun `los episodios sin descargar no suman`() {
        assertEquals(GB, EspacioEnDisco.ocupadoPorDescargas(listOf(grupo(bajado("a", GB), sinBajar("b")))))
    }

    @Test
    fun `sin descargas el ocupado es cero`() {
        assertEquals(0L, EspacioEnDisco.ocupadoPorDescargas(emptyList()))
    }

    @Test
    fun `el resumen muestra libres y ocupado`() {
        assertEquals("12.0 GB libres  ·  3.0 GB en descargas", EspacioEnDisco.resumen(12 * GB, 3 * GB))
    }

    @Test
    fun `sin nada ocupado el resumen solo dice libres`() {
        assertEquals("12.0 GB libres", EspacioEnDisco.resumen(12 * GB, 0L))
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

Run: `./gradlew testDebugUnitTest --tests '*EspacioEnDiscoTest*'`
Expected: FAIL de compilación — `Unresolved reference: EspacioEnDisco`.

**Nota si falla distinto:** si el error es sobre los parámetros de `Episode` o `DownloadRow`, abrir `app/src/main/java/com/arkiv/player/data/model/Models.kt:11` y `Daos.kt:219` y ajustar el constructor del test a los campos reales — el test es el que se adapta, no el modelo.

- [ ] **Step 3: Escribir la implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/biblioteca/EspacioEnDisco.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.TorrentSizeGate

/**
 * Lo que la sección "Descargas" del TV muestra arriba de todo.
 *
 * Existe porque en un Fire TV Stick el disco es chico y se llena sin avisar: guardar una temporada
 * entera encola N descargas y hasta ahora no había ninguna pantalla en el TV donde verlo.
 */
object EspacioEnDisco {

    /**
     * Bytes ya escritos por las descargas, incluidos los parciales (`bytesDone`, no `bytes`): lo que
     * interesa es lo que ocupa AHORA en el disco, no lo que va a ocupar al terminar.
     *
     * Puede sobrecontar en un caso conocido: cuando dos filas comparten el mismo archivo porque una
     * adoptó el de su gemela (ver `LocalDownloadWorker.adoptTwinIfAlreadyDownloaded`), los bytes se
     * cuentan dos veces. Se acepta: es un número informativo, no una decisión, y quien decide si una
     * descarga entra sigue siendo `FreeSpacePolicy` midiendo el disco de verdad.
     */
    fun ocupadoPorDescargas(grupos: List<DownloadGroup>): Long =
        grupos.sumOf { grupo ->
            grupo.episodes.sumOf { ep ->
                (ep.status as? EpisodeDownloadStatus.Tracked)?.row?.bytesDone ?: 0L
            }
        }

    /**
     * "12.0 GB libres  ·  3.0 GB en descargas". Sin nada bajado, omite la segunda cláusula en vez de
     * mostrar un "0 MB" que no le dice nada a nadie.
     *
     * Usa el formateador que ya existe (`TorrentSizeGate.formatSize`) en vez de uno propio: dos
     * formatos de tamaño distintos en la misma app se notan.
     */
    fun resumen(libresBytes: Long, ocupadoBytes: Long): String {
        val libres = "${TorrentSizeGate.formatSize(libresBytes)} libres"
        return if (ocupadoBytes <= 0) libres
        else "$libres  ·  ${TorrentSizeGate.formatSize(ocupadoBytes)} en descargas"
    }
}
```

- [ ] **Step 4: Correr el test para verificar que pasa**

Run: `./gradlew testDebugUnitTest --tests '*EspacioEnDiscoTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Exponer el espacio libre real en el manager**

En `LocalDownloadManager.kt`, justo después de `hasFreeSpaceFor` (línea 62), agregar:

```kotlin
    /**
     * Bytes disponibles en el disco donde viven las descargas. Es la misma medición que usa
     * [hasFreeSpaceFor], expuesta para poder MOSTRARLA: la biblioteca del TV la necesita para que el
     * disco lleno deje de ser una sorpresa. Bloqueante (toca el filesystem), así que se llama fuera
     * del hilo principal o dentro de un `produceState`.
     */
    fun espacioLibreBytes(): Long = StatFs(targetDir().absolutePath).availableBytes
```

- [ ] **Step 6: Compilar y commitear**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/arkiv/player/data/biblioteca/EspacioEnDisco.kt app/src/test/java/com/arkiv/player/data/biblioteca/EspacioEnDiscoTest.kt app/src/main/java/com/arkiv/player/data/local/LocalDownloadManager.kt
git diff --cached --stat
git commit -m "feat(biblioteca): resumen de espacio en disco para la seccion de descargas"
```

---

### Task 5: `TvLibraryViewModel`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryViewModel.kt`

**Interfaces:**
- Consumes: `ArkivRepository.observeLibraryGroups()`, `ArkivRepository.observeVistos()` (Task 2), `VistosDeLaBiblioteca.cruzar` (Task 1).
- Produces:
  - `TvLibraryViewModel(repo: ArkivRepository)`
  - `grupos: StateFlow<List<LibraryGroup>>`
  - `vistos: StateFlow<List<GrupoVisto>>`
  - `fun quitar(itemId: String)`
  - `fun setCategory(itemId: String, isMovie: Boolean?)`

Sin test: es cableado de flows sobre Room. La lógica que importa ya está testeada en Tasks 1 y 3.

- [ ] **Step 1: Escribir el ViewModel**

Crear `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryViewModel.kt`:

```kotlin
package com.arkiv.player.ui.tv.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.biblioteca.GrupoVisto
import com.arkiv.player.data.biblioteca.VistosDeLaBiblioteca
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado de la pantalla "Mi biblioteca" del TV.
 *
 * NO reusa `HomeViewModel` a propósito: ese arrastra todo el motor de descubrimiento (pide géneros
 * a TMDB y AniList en su `init` y cachea ~40 filas de títulos). Abrir la biblioteca crearía una
 * segunda instancia completa de eso para una pantalla que no muestra descubrimiento.
 *
 * Tampoco expone el `artwork`: la grilla usa carátulas (`LibraryRow.thumbnailUrl`), no los backdrops
 * apaisados que el home necesita para el hero. El artwork igual se resuelve — `observeLibraryGroups`
 * lo lee de la base para agrupar, y `ensureArtwork` ya corre desde el home, que es el destino de
 * arranque del TV.
 */
class TvLibraryViewModel(private val repo: ArkivRepository) : ViewModel() {

    val grupos: StateFlow<List<LibraryGroup>> = repo.observeLibraryGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vistos: StateFlow<List<GrupoVisto>> =
        combine(grupos, repo.observeVistos()) { grupos, vistos ->
            VistosDeLaBiblioteca.cruzar(grupos, vistos)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saca el ítem de la biblioteca. Es soft-delete (ver `ArkivRepository.removeItem`), así que el
     * borrado viaja por el sync y no reaparece desde el otro dispositivo.
     *
     * NO borra los archivos ya descargados al dispositivo: eso se hace desde la sección Descargas.
     * El diálogo que llama a esto lo dice explícitamente.
     */
    fun quitar(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }

    /** Película <-> serie a mano, cuando la detección automática se equivoca. Null = automática. */
    fun setCategory(itemId: String, isMovie: Boolean?) {
        viewModelScope.launch { repo.setCategory(itemId, isMovie) }
    }
}
```

- [ ] **Step 2: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Si `removeItem` o `setCategory` no existen con esa firma, buscarlas con `grep -n "fun removeItem\|fun setCategory" app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` y ajustar.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryViewModel.kt
git diff --cached --stat
git commit -m "feat(tv): view model de la pantalla Mi biblioteca"
```

---

### Task 6: `TvPosterCard` con subtítulo y mantener-pulsado

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvPosterCard.kt`

**Interfaces:**
- Produces: `TvPosterCard(title, posterUrl, cardHeight, modifier, showTitle, subtitle, onFocus, onLongClick, onClick)` — los parámetros nuevos (`subtitle: String? = null`, `onLongClick: (() -> Unit)? = null`) tienen default, así que las llamadas existentes en `TvSearchScreen` siguen compilando sin tocarlas.

Sin test: es Compose.

- [ ] **Step 1: Agregar los parámetros**

En `TvPosterCard.kt`, cambiar la firma (línea 36-44) a:

```kotlin
fun TvPosterCard(
    title: String,
    posterUrl: String?,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    /** Segunda línea bajo el título ("24 ep.", "12 capítulos vistos"). Null = no se dibuja. */
    subtitle: String? = null,
    onFocus: () -> Unit = {},
    /** Mantener pulsado. Null = la tarjeta no ofrece menú contextual. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
```

- [ ] **Step 2: Pasar `onLongClick` al `Card`**

En la llamada a `Card` (línea 48), agregar el parámetro justo debajo de `onClick = onClick,`:

```kotlin
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
```

- [ ] **Step 3: Dibujar el subtítulo**

Después del bloque `if (showTitle) { Text(...) }` (cierra en la línea 89), antes del cierre del `Column`, agregar:

```kotlin
        if (showTitle && subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
```

Agregar el import: `import com.arkiv.player.ui.theme.ArkivTextSecondary`

- [ ] **Step 4: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvPosterCard.kt
git diff --cached --stat
git commit -m "feat(tv): TvPosterCard acepta subtitulo y mantener-pulsado"
```

---

### Task 7: Sección de descargas del TV

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/library/TvDownloadsSection.kt`

**Interfaces:**
- Consumes: `DownloadsViewModel` (`groups`, `cancelGroup`, `removeGroup`, `retryFailedGroup`), `DownloadGroupPolicy.summarize`, `DownloadGroupPolicy.activeEpisodeIds`, `DownloadGroupPolicy.failedEpisodeIds`, `EspacioEnDisco` (Task 4), `AppGraph.localDownloads`, `AppGraph.repository`.
- Produces: `@Composable fun TvDownloadsSection(modifier: Modifier = Modifier)`

Sin test: es Compose.

- [ ] **Step 1: Escribir la sección**

Crear `app/src/main/java/com/arkiv/player/ui/tv/library/TvDownloadsSection.kt`:

```kotlin
package com.arkiv.player.ui.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.biblioteca.EspacioEnDisco
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.ui.downloads.DownloadsViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Las descargas al dispositivo, vistas desde el TV.
 *
 * Hasta ahora el TV no tenía NINGUNA pantalla de descargas, aunque "Guardar toda la temporada" del
 * buscador encola N descargas al disco del aparato. En un Fire TV Stick eso se llena sin avisar.
 *
 * Una fila por SERIE, no por capítulo: `DownloadsViewModel` ya expone las acciones de grupo y el
 * caso real es una temporada entera encolada de una. Una lista por capítulo sería una pantalla
 * anidada más para navegar con el D-pad, sin ganar nada.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDownloadsSection(modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadsViewModel(graph.localDownloads, graph.repository) }
        },
    )
    val grupos by vm.groups.collectAsStateWithLifecycle()
    var acciones by remember { mutableStateOf<DownloadGroup?>(null) }

    val ocupado = EspacioEnDisco.ocupadoPorDescargas(grupos)
    // Se remide cada vez que cambia lo ocupado (una descarga terminó, se borró algo). `StatFs` toca
    // el filesystem, así que va fuera del hilo principal.
    val libres by produceState(initialValue = 0L, ocupado) {
        value = withContext(Dispatchers.IO) { graph.localDownloads.espacioLibreBytes() }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text("Descargas", style = MaterialTheme.typography.headlineSmall, color = ArkivTextPrimary)
        Text(
            EspacioEnDisco.resumen(libres, ocupado),
            style = MaterialTheme.typography.labelLarge,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        if (grupos.isEmpty()) {
            Text(
                "No hay nada descargado en este aparato.\nGuardá una serie desde el buscador y va a aparecer acá.",
                style = MaterialTheme.typography.bodyLarge,
                color = ArkivTextSecondary,
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(grupos, key = { it.itemId }) { grupo ->
                Card(
                    onClick = { acciones = grupo },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(containerColor = ArkivSurfaceHigh),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            grupo.itemTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = ArkivTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            DownloadGroupPolicy.summarize(grupo.episodes),
                            style = MaterialTheme.typography.labelMedium,
                            color = ArkivTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    acciones?.let { grupo ->
        TvDownloadActionsDialog(
            grupo = grupo,
            onCancelar = { vm.cancelGroup(grupo); acciones = null },
            onReintentar = { vm.retryFailedGroup(grupo); acciones = null },
            onQuitar = { vm.removeGroup(grupo); acciones = null },
            onDismiss = { acciones = null },
        )
    }
}

/**
 * Acciones de un grupo de descargas, en un diálogo y no como botones dentro de la fila: con el
 * D-pad, varios botones por fila multiplican los saltos de foco y hacen fácil apretar el equivocado
 * —y acá el equivocado borra gigabytes—.
 *
 * Solo se ofrece lo que el estado del grupo permite: "Cancelar" si hay algo activo, "Reintentar" si
 * hay algo fallido. "Quitar del dispositivo" está siempre: es lo que libera disco.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDownloadActionsDialog(
    grupo: DownloadGroup,
    onCancelar: () -> Unit,
    onReintentar: () -> Unit,
    onQuitar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hayActivas = DownloadGroupPolicy.activeEpisodeIds(grupo).isNotEmpty()
    val hayFallidas = DownloadGroupPolicy.failedEpisodeIds(grupo).isNotEmpty()
    var confirmarQuitar by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                grupo.itemTitle,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DownloadGroupPolicy.summarize(grupo.episodes),
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            if (confirmarQuitar) {
                Text(
                    "Se borran del disco los archivos ya bajados de esta serie. La serie sigue en tu biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onQuitar, modifier = Modifier.fillMaxWidth()) {
                    Text("Sí, borrar del dispositivo", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = { confirmarQuitar = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar", maxLines = 1)
                }
            } else {
                if (hayActivas) {
                    Button(onClick = onCancelar, modifier = Modifier.fillMaxWidth()) {
                        Text("Detener lo que está bajando", maxLines = 1)
                    }
                }
                if (hayFallidas) {
                    Button(onClick = onReintentar, modifier = Modifier.fillMaxWidth()) {
                        Text("Reintentar lo que falló", maxLines = 1)
                    }
                }
                Button(onClick = { confirmarQuitar = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Quitar del dispositivo", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Volver", maxLines = 1)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Si `DownloadGroupPolicy.activeEpisodeIds` / `failedEpisodeIds` no son públicas o tienen otra firma, verificar con:
`grep -n "fun activeEpisodeIds\|fun failedEpisodeIds\|fun summarize" app/src/main/java/com/arkiv/player/data/local/DownloadGroupPolicy.kt`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/library/TvDownloadsSection.kt
git diff --cached --stat
git commit -m "feat(tv): seccion de descargas con espacio en disco y acciones por serie"
```

---

### Task 8: `TvLibraryScreen` — menú lateral y grillas

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryScreen.kt`

**Interfaces:**
- Consumes: `TvLibraryViewModel` (Task 5), `SeccionDeBiblioteca` / `FiltroDeBiblioteca` (Task 3), `GrupoVisto` (Task 1), `TvPosterCard` con `subtitle`/`onLongClick` (Task 6), `TvDownloadsSection` (Task 7), `ArkivRepository.firstEpisodeId`.
- Produces: `@Composable fun TvLibraryScreen(onOpenItem: (String) -> Unit, onPlayEpisode: (String) -> Unit, onBack: () -> Unit)`

Sin test: es Compose.

- [ ] **Step 1: Escribir la pantalla**

Crear `app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryScreen.kt`:

```kotlin
package com.arkiv.player.ui.tv.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.biblioteca.FiltroDeBiblioteca
import com.arkiv.player.data.biblioteca.SeccionDeBiblioteca
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.TvPosterCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CARD_HEIGHT = 200.dp

/**
 * "Mi biblioteca" del TV: lo guardado, lo ya visto y las descargas al dispositivo.
 *
 * Existe porque el home no alcanzaba: su zona de filas mide exactamente dos filas, así que con algo
 * en "Continuar viendo" la fila de Películas nacía fuera de pantalla y no había forma razonable de
 * llegar a lo guardado. Acá el contenido propio tiene su lugar y no compite con ~40 filas de
 * descubrimiento.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLibraryScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val vm: TvLibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { TvLibraryViewModel(graph.repository) } },
    )
    val grupos by vm.grupos.collectAsStateWithLifecycle()
    val vistos by vm.vistos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var seccion by remember { mutableStateOf(SeccionDeBiblioteca.TODO_LO_GUARDADO) }
    var menuDe by remember { mutableStateOf<LibraryGroup?>(null) }

    BackHandler(enabled = menuDe == null) { onBack() }

    // El foco arranca en el menú. Mismo patrón de reintento que el home: a los 150 ms la fila puede
    // no estar compuesta todavía y `requestFocus()` tira "FocusRequester is not initialized"; sin
    // reintentar, el foco no aterriza en ningún lado y Android se lo da a lo que se vaya componiendo.
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { menuFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    // Película: reproduce directo. Serie: abre el detalle, que es donde se elige capítulo.
    // Se navega con la LLAVE DEL GRUPO (`tv:46260`), no con el identifier de la fuente principal:
    // `DetailViewModel.observeGroupMembers` la resuelve a todas las adquisiciones y arma el selector.
    fun abrir(grupo: LibraryGroup) {
        if (grupo.primary.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(grupo.primary.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(grupo.key)
            }
        } else {
            onOpenItem(grupo.key)
        }
    }

    Row(Modifier.fillMaxSize().background(ArkivBlack)) {
        // --- Menú lateral ---
        Column(
            modifier = Modifier.width(220.dp).fillMaxHeight()
                .background(ArkivSurface)
                .padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "ARKIV",
                style = MaterialTheme.typography.titleLarge,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 24.dp, bottom = 20.dp),
            )
            SeccionDeBiblioteca.values().forEachIndexed { i, s ->
                TvMenuItem(
                    etiqueta = s.etiqueta,
                    seleccionada = s == seccion,
                    modifier = if (i == 0) Modifier.focusRequester(menuFocus) else Modifier,
                    // La sección cambia con el FOCO, no con el click: es lo que se espera en un
                    // menú de TV (bajar por el menú va mostrando cada sección), y evita el paso
                    // extra de "enfocar, aceptar, recién ahí ver".
                    onFocus = { seccion = s },
                )
            }
        }

        // --- Contenido ---
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (seccion) {
                SeccionDeBiblioteca.DESCARGAS -> TvDownloadsSection()
                SeccionDeBiblioteca.VISTOS -> TvPosterGrid(
                    titulo = "Ya visto",
                    conteo = vistos.size,
                    grupos = vistos.map { it.grupo },
                    subtituloDe = { g ->
                        vistos.firstOrNull { it.grupo.key == g.key }
                            ?.let { "${it.capitulosVistos} capítulos vistos" }
                    },
                    vacio = "Todavía no terminaste nada.\nLo que veas hasta el final va a aparecer acá.",
                    onClick = ::abrir,
                    onLongClick = { menuDe = it },
                )
                else -> {
                    // `grupos(...)` devuelve null solo para VISTOS/DESCARGAS, que ya se manejaron
                    // arriba: acá nunca es null.
                    val filtrados = FiltroDeBiblioteca.grupos(seccion, grupos).orEmpty()
                    TvPosterGrid(
                        titulo = seccion.etiqueta,
                        conteo = filtrados.size,
                        grupos = filtrados,
                        subtituloDe = { g -> if (g.primary.isMovie) null else "${g.episodeCount} ep." },
                        vacio = "Todavía no guardaste nada acá.\nBuscá algo y dale Guardar.",
                        onClick = ::abrir,
                        onLongClick = { menuDe = it },
                    )
                }
            }
        }
    }

    menuDe?.let { grupo ->
        TvLibraryItemDialog(
            grupo = grupo,
            onOpenDetail = { onOpenItem(grupo.key); menuDe = null },
            onSetCategory = { isMovie -> vm.setCategory(grupo.primary.identifier, isMovie); menuDe = null },
            onQuitar = { vm.quitar(grupo.primary.identifier); menuDe = null },
            onDismiss = { menuDe = null },
        )
    }
}

/** Una entrada del menú lateral. Se pinta como seleccionada cuando su sección es la activa. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMenuItem(
    etiqueta: String,
    seleccionada: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = ArkivRed,
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
    ) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.titleSmall,
            color = if (seleccionada) ArkivTextPrimary else ArkivTextSecondary,
            fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/**
 * Grilla de carátulas. `Adaptive` y no un número fijo de columnas: con el menú de 220 dp, en un
 * Fire TV de 1080p entran ~4 columnas de póster, y en una pantalla más ancha entran más solas.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPosterGrid(
    titulo: String,
    conteo: Int,
    grupos: List<LibraryGroup>,
    subtituloDe: (LibraryGroup) -> String?,
    vacio: String,
    onClick: (LibraryGroup) -> Unit,
    onLongClick: (LibraryGroup) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(
            if (grupos.isEmpty()) titulo else "$titulo  ·  $conteo",
            style = MaterialTheme.typography.headlineSmall,
            color = ArkivTextPrimary,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        if (grupos.isEmpty()) {
            Text(vacio, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(grupos, key = { it.key }) { grupo ->
                TvPosterCard(
                    title = grupo.primary.title,
                    posterUrl = grupo.primary.thumbnailUrl,
                    cardHeight = CARD_HEIGHT,
                    subtitle = subtituloDe(grupo),
                    onLongClick = { onLongClick(grupo) },
                    onClick = { onClick(grupo) },
                )
            }
        }
    }
}

/**
 * Menú de mantener-pulsado de una tarjeta. Es el `TvCategoryDialog` que vivía en `TvHomeScreen` más
 * "Quitar de mi biblioteca", que en el TV no existía: hasta ahora un guardado por error solo se
 * podía deshacer desde el teléfono.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLibraryItemDialog(
    grupo: LibraryGroup,
    onOpenDetail: () -> Unit,
    onSetCategory: (Boolean?) -> Unit,
    onQuitar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val row = grupo.primary
    var confirmarQuitar by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(confirmarQuitar) {
        delay(100)
        runCatching { focus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (confirmarQuitar) {
                Text(
                    "Se quita de tu biblioteca en todos tus aparatos. Los archivos ya descargados en este aparato NO se borran: eso se hace desde Descargas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onQuitar, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Sí, quitar de mi biblioteca", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = { confirmarQuitar = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar", maxLines = 1)
                }
            } else {
                Text(
                    if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Ver detalle / descargar", maxLines = 1)
                }
                if (row.isMovie) {
                    Button(onClick = { onSetCategory(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como serie", maxLines = 1)
                    }
                } else {
                    Button(onClick = { onSetCategory(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como película", maxLines = 1)
                    }
                }
                if (row.categoryOverride != null) {
                    Button(onClick = { onSetCategory(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Detección automática", maxLines = 1)
                    }
                }
                Button(onClick = { confirmarQuitar = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Quitar de mi biblioteca", color = ArkivRed, maxLines = 1)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Volver", maxLines = 1)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/library/TvLibraryScreen.kt
git diff --cached --stat
git commit -m "feat(tv): pantalla Mi biblioteca con menu lateral y grilla de caratulas"
```

---

### Task 9: Cablear la ruta, el botón del home y limpiar las filas viejas

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` (bloque `composable("home")`, línea 142-162; agregar `composable("library")`)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` (firma línea 100-106; barra superior línea 260-290; `firstPosterId` línea 183-220; filas `lib_series`/`lib_movies` línea 356-396; borrar `TvLibrarySection`, `TvSeriesSection` y `TvCategoryDialog`)

**Interfaces:**
- Consumes: `TvLibraryScreen` (Task 8).
- Produces: `TvHomeScreen(onOpenItem, onPlayEpisode, onOpenSettings, onOpenSearch, onOpenLibrary, onOpenSearchRoute)` — parámetro `onOpenLibrary: () -> Unit` nuevo.

- [ ] **Step 1: Registrar la ruta en `ArkivTvRoot`**

Después del bloque `composable("search?...")` (cierra en la línea 178), agregar:

```kotlin
        composable("library") {
            com.arkiv.player.ui.tv.library.TvLibraryScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onBack = { navController.popBackStack() },
            )
        }
```

Y en la llamada a `TvHomeScreen` (línea 155), agregar el callback:

```kotlin
            TvHomeScreen(
                onOpenItem = { navController.navigate("detail/${Uri.encode(it)}") },
                onPlayEpisode = { goToPlayer(it) },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSearch = { navController.navigate("search") },
                onOpenLibrary = { navController.navigate("library") },
                onOpenSearchRoute = { route -> navController.navigate(route) },
            )
```

- [ ] **Step 2: Agregar el parámetro y el botón en `TvHomeScreen`**

En la firma (línea 100), agregar `onOpenLibrary: () -> Unit,` después de `onOpenSearch`.

En la barra superior, después del `TvNavButton` de "Buscar" (línea 272), agregar:

```kotlin
                    TvNavButton(
                        icon = Icons.Default.VideoLibrary,
                        label = "Mi biblioteca",
                        onClick = onOpenLibrary,
                        modifier = Modifier.focusRequester(barraFocus),
                    )
```

Agregar el import: `import androidx.compose.material.icons.filled.VideoLibrary`

`TvNavButton` hoy no acepta `modifier`. Agregárselo en su firma (línea 634) — `modifier: Modifier = Modifier,` — y aplicarlo en su `Surface`/`Card` raíz con `modifier.` en vez de `Modifier.`.

- [ ] **Step 3: Quitar las filas de biblioteca del `LazyColumn`**

Borrar los dos bloques `item(key = "lib_series") { ... }` y `item(key = "lib_movies") { ... }` (líneas 361-396) enteros.

Borrar también las declaraciones que quedan sin uso (líneas 177-187): `val movies`, `val seriesGroups`, `val firstPosterId`.

Y borrar las funciones privadas que quedan muertas: `TvLibrarySection` (línea 545), `TvSeriesSection` (línea 587) y `TvCategoryDialog` (línea 467), junto con `var menuRow` (línea 175) y su bloque `menuRow?.let { ... }` (líneas 450-460). El menú contextual ahora vive en la pantalla de biblioteca.

`libraryFeatured` se conserva: el hero del home sigue cayendo a `library.firstOrNull()` cuando no hay "Continuar viendo".

- [ ] **Step 4: Arreglar el foco inicial**

Reemplazar el bloque `firstFocusKey` (líneas 201-220) por:

```kotlin
    // Foco inicial. Antes caía en la primera tarjeta de biblioteca; esas filas ya no están, y dejar
    // el foco suelto es exactamente el bug que costó el comentario largo de más abajo: Android se lo
    // daba a lo que se fuera componiendo —las filas de descubrimiento—, que al traerse a la vista
    // scrolleaban el home hasta "En cartelera".
    //
    // Sin "Continuar viendo" el foco va a la BARRA SUPERIOR, que es la única zona determinista: no
    // vive dentro del LazyColumn, así que siempre está compuesta y enfocarla no puede scrollear nada.
    // Y deja al usuario a un clic de su biblioteca, que es lo que va a querer si no hay nada empezado.
    val barraFocus = remember { FocusRequester() }
    val firstFocusKey = continueWatching.firstOrNull()?.episodeId
    LaunchedEffect(firstFocusKey) {
        delay(200)
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            if (firstFocusKey != null) {
                // Volver arriba ANTES de pedir foco: si la lista está desplazada, la primera fila ni
                // siquiera está compuesta y el requester no existe, así que reintentar solo no alcanza.
                runCatching { rowsListState.scrollToItem(0) }
                landed = runCatching { firstCardFocus.requestFocus() }.isSuccess
            } else {
                landed = runCatching { barraFocus.requestFocus() }.isSuccess
            }
            if (!landed) delay(50)
        }
    }
```

`barraFocus` se declara acá y se usa en el `TvNavButton` de "Mi biblioteca" del Step 2.

- [ ] **Step 5: Compilar y correr toda la suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, todos los tests en verde. Si el compilador marca referencias a `movies`, `seriesGroups`, `firstPosterId` o `menuRow`, quedó un uso sin borrar — el error dice la línea exacta.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt
git diff --cached --stat
git commit -m "feat(tv): boton Mi biblioteca en el home y la biblioteca sale de las filas"
```

---

### Task 10: Verificar en el Fire TV

**Files:** ninguno. Es la verificación real: nada de esto se puede testear en la JVM.

- [ ] **Step 1: Instalar**

```bash
./gradlew assembleDebug
adb connect <ip-del-firestick>:5555
adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

La IP y el pareo del Fire TV están en las notas de ADB del proyecto. Si `adb connect` falla, el aparato perdió el modo depuración por red y hay que rehabilitarlo desde sus ajustes.

- [ ] **Step 2: Recorrer la lista de verificación**

En el TV, con el control:

1. El home muestra el botón "Mi biblioteca" al lado de "Buscar", y ya NO muestra las filas "Series" ni "Películas".
2. Con "Continuar viendo" vacío, el foco arranca en la barra superior y el home **no** se auto-scrollea hasta "En cartelera".
3. "Mi biblioteca" abre en "Todo" con el foco en el menú. `→` entra a la grilla, `←` desde la primera columna vuelve al menú.
4. Bajar por el menú va cambiando la sección sin tener que apretar aceptar.
5. **Series** muestra la temporada de Magis que motivó todo esto, como UNA tarjeta (no N películas sueltas).
6. Elegir una serie abre el detalle; elegir una película reproduce directo.
7. **Ya visto** lista lo terminado con su "N capítulos vistos", lo más reciente primero.
8. **Descargas** muestra el espacio libre y las series encoladas; "Quitar del dispositivo" pide confirmación y después el espacio libre sube.
9. Mantener pulsada una tarjeta ofrece "Quitar de mi biblioteca"; al confirmar, la tarjeta desaparece.
10. `Atrás` desde cualquier sección vuelve al home.

- [ ] **Step 3: Anotar lo que falle**

Cualquier ítem que falle se arregla antes de dar la tarea por cerrada. Si el problema es de foco con el D-pad, el patrón a seguir es el del home: `requestFocus()` con reintento, nunca un único intento con `runCatching` que se traga el fallo en silencio.

---

## Self-Review

**Cobertura del spec:**

| Requisito del spec | Task |
| --- | --- |
| Ruta `library` + botón al lado de Buscar | 9 |
| Quitar filas de biblioteca del home | 9 |
| No reintroducir el bug de foco/scroll | 9 (Step 4), 10 (Step 2, punto 2) |
| Menú lateral = filtro Todo/Series/Películas | 3, 8 |
| Grilla de carátulas con `TvPosterCard` | 6, 8 |
| Series agrupadas (no duplicadas por fuente) | 8 (usa `libraryGroups`) |
| Sección "Ya visto" | 1, 2, 8 |
| Conteo por grupo = máximo, no suma | 1 |
| Sección "Descargas" con cancelar/borrar | 7 |
| Espacio libre en disco | 4, 7 |
| D-pad: ← → entre menú y grilla | 8 (desvío 5: lo resuelve la búsqueda de foco 2D) |
| Mantener pulsado + "Quitar de mi biblioteca" | 8 |
| Estados vacíos por sección | 7, 8 |
| Confirmación antes de cancelar/borrar | 7, 8 |
| Quitar de biblioteca no borra archivos, y lo dice | 5 (KDoc), 8 (texto del diálogo) |
| Tests: `VistosDeLaBiblioteca`, filtro, formato de espacio | 1, 3, 4 |

Sin huecos.

**Placeholders:** ninguno. Cada paso lleva el código real y el comando exacto.

**Consistencia de tipos:** `VistoDeItem` (puro, Task 1) y `VistoRow` (Room, Task 2) son distintos a propósito, y el mapeo entre ambos está escrito en Task 2 Step 3. `GrupoVisto.grupo` es un `LibraryGroup`, que es lo que `TvPosterGrid` consume en Task 8. `FiltroDeBiblioteca.grupos` devuelve nullable y las dos llamadas en Task 8 lo manejan (`.orEmpty()` en la rama que no puede ser null, y `VISTOS`/`DESCARGAS` interceptadas antes en el `when`). `TvPosterCard` gana `subtitle` y `onLongClick` con default en Task 6, antes de que Task 8 los use.
