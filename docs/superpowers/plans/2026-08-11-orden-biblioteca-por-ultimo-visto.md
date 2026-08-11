# Orden de la biblioteca por lo último visto — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que las tarjetas de "Mi biblioteca" —en el celu y en el TV— se ordenen por lo último que el usuario vio, con lo recién agregado también arriba, en vez de por fecha de agregado a secas.

**Architecture:** Una consulta nueva en `PlaybackDao` da `itemId → MAX(playback.lastPlayedAt)`. Un objeto puro nuevo (`OrdenDeBiblioteca`) define la regla `recencia = max(última reproducción, addedAt)` y ordena tanto filas (celu) como grupos (TV). El repositorio combina ese mapa con los flows que ya existen y expone dos flows ordenados; `observeLibrary()` cruda queda intacta para `ensureArtwork`, descargas y sync.

**Tech Stack:** Kotlin, Room (SQLite), Kotlin Flow, Jetpack Compose, JUnit4 (tests JVM puros, sin Robolectric).

**Spec:** `docs/superpowers/specs/2026-08-11-orden-biblioteca-por-ultimo-visto-design.md`

## Global Constraints

- **Sin migración de Room.** No se toca ningún `@Entity` ni el número de versión de la base. Solo se agrega una `@Query` de lectura sobre tablas existentes.
- **`ItemDao.observeLibrary` no se modifica.** Su `ORDER BY i.addedAt DESC` se queda como está. Atarla a `playback` haría que Room re-emita la biblioteca entera en cada guardado de progreso, y de ese flow cuelga `ensureArtwork`.
- **`LibraryGrouping.kt` no se modifica** (salvo un comentario en la Tarea 3). Su `sortedByDescending { max(addedAt) }` interno queda como desempate.
- **Tests JVM puros.** Este proyecto no tiene Robolectric: nada que necesite Room o Android en un test unitario. La lógica nueva va en un objeto puro para poder testearla.
- **Idioma del código:** nombres y comentarios en español, como el resto de `data/biblioteca/`.
- **Commits sin coautoría** y con la identidad `lordmacu`. **Nunca `git add -A`**: este working tree lo comparten varias sesiones. Agregar solo los archivos que nombra cada tarea.
- **Comando de tests:** `./gradlew testDebugUnitTest`

## Estructura de archivos

| Archivo | Responsabilidad | Tarea |
|---|---|---|
| `app/src/test/java/com/arkiv/player/data/biblioteca/OrdenDeBibliotecaTest.kt` (crear) | Tests de la regla de orden, puros | 1 |
| `app/src/main/java/com/arkiv/player/data/biblioteca/OrdenDeBiblioteca.kt` (crear) | La regla `max(última reproducción, addedAt)` y los dos ordenamientos | 1 |
| `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (modificar) | `UltimaReproduccionRow` + `PlaybackDao.observeUltimaReproduccion()` | 2 |
| `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (modificar) | `observeUltimaReproduccion()`, `observeLibraryOrdenada()`, orden en `observeLibraryGroups()` | 3 |
| `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt` (modificar) | `bibliotecaOrdenada` | 4 |
| `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt` (modificar) | Consumir `bibliotecaOrdenada` | 4 |

El TV no aparece: `TvLibraryViewModel.grupos` ya sale de `observeLibraryGroups()`, así que hereda el orden con la Tarea 3 sin tocar una línea de su UI.

---

### Task 1: La regla de orden (objeto puro + tests)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/biblioteca/OrdenDeBiblioteca.kt`
- Test: `app/src/test/java/com/arkiv/player/data/biblioteca/OrdenDeBibliotecaTest.kt`

**Interfaces:**
- Consumes: `com.arkiv.player.data.db.LibraryRow` y `com.arkiv.player.data.LibraryGroup`, que ya existen.
- Produces:
  - `OrdenDeBiblioteca.recenciaDe(row: LibraryRow, ultimas: Map<String, Long>): Long`
  - `OrdenDeBiblioteca.filas(rows: List<LibraryRow>, ultimas: Map<String, Long>): List<LibraryRow>`
  - `OrdenDeBiblioteca.grupos(grupos: List<LibraryGroup>, ultimas: Map<String, Long>): List<LibraryGroup>`
  - El `Map<String, Long>` es `itemId → última reproducción en epoch ms`. Un ítem ausente del mapa nunca se reprodujo.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/biblioteca/OrdenDeBibliotecaTest.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El orden de "Mi biblioteca": lo último que viste va primero, y lo recién agregado también, para
 * no tener que ir a buscar abajo la serie que venís viendo.
 */
class OrdenDeBibliotecaTest {

    private fun row(id: String, addedAt: Long) = LibraryRow(
        identifier = id,
        title = id,
        description = null,
        thumbnailUrl = "",
        episodeCount = 10,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = "series",
        source = "web",
    )

    private fun grupo(key: String, vararg filas: LibraryRow) =
        LibraryGroup(key = key, primary = filas.first(), members = filas.toList())

    @Test
    fun `lo visto mas reciente va primero`() {
        val viejo = row("a", addedAt = 0L)
        val nuevo = row("b", addedAt = 0L)
        val r = OrdenDeBiblioteca.filas(
            listOf(viejo, nuevo),
            mapOf("a" to 100L, "b" to 900L),
        )
        assertEquals(listOf("b", "a"), r.map { it.identifier })
    }

    @Test
    fun `lo recien agregado le gana a lo visto hace rato`() {
        val visto = row("visto", addedAt = 10L)
        val recien = row("recien", addedAt = 900L)
        val r = OrdenDeBiblioteca.filas(listOf(visto, recien), mapOf("visto" to 100L))
        assertEquals(listOf("recien", "visto"), r.map { it.identifier })
    }

    /**
     * El mapa no distingue capítulo terminado de capítulo a medias: los dos llegan como una marca
     * de tiempo. Terminar el E4 anoche tiene que dejar la serie primera hoy, que es el caso que
     * motiva esta funcionalidad.
     */
    @Test
    fun `un item sin reproducciones se ordena por su fecha de agregado`() {
        val sinVer = row("sinver", addedAt = 500L)
        val visto = row("visto", addedAt = 0L)
        val r = OrdenDeBiblioteca.filas(listOf(visto, sinVer), mapOf("visto" to 100L))
        assertEquals(listOf("sinver", "visto"), r.map { it.identifier })
    }

    /** `max(...)` y no la reproducción sola: re-agregar algo viejo lo trae al frente. */
    @Test
    fun `con progreso viejo pero agregado reciente manda el agregado`() {
        val row = row("a", addedAt = 900L)
        assertEquals(900L, OrdenDeBiblioteca.recenciaDe(row, mapOf("a" to 100L)))
    }

    @Test
    fun `la recencia es la reproduccion cuando es posterior al agregado`() {
        val row = row("a", addedAt = 100L)
        assertEquals(900L, OrdenDeBiblioteca.recenciaDe(row, mapOf("a" to 900L)))
    }

    /** Empate: el orden entrante (que viene `addedAt DESC` del SQL) se respeta. */
    @Test
    fun `un empate mantiene el orden entrante`() {
        val primero = row("primero", addedAt = 100L)
        val segundo = row("segundo", addedAt = 100L)
        val r = OrdenDeBiblioteca.filas(listOf(primero, segundo), emptyMap())
        assertEquals(listOf("primero", "segundo"), r.map { it.identifier })
    }

    /**
     * Una serie guardada desde dos fuentes es UNA tarjeta: verla por cualquiera de las dos sube el
     * grupo entero. Mismo criterio que `VistosDeLaBiblioteca`.
     */
    @Test
    fun `la recencia de un grupo es la del miembro mas reciente`() {
        val naruto = grupo("tv:46260", row("web:series:a", 0L), row("torrent:series:a", 0L))
        val otra = grupo("tv:1", row("web:series:otra", 0L))
        val r = OrdenDeBiblioteca.grupos(
            listOf(otra, naruto),
            mapOf("torrent:series:a" to 900L, "web:series:otra" to 100L),
        )
        assertEquals(listOf("tv:46260", "tv:1"), r.map { it.key })
    }

    @Test
    fun `una reproduccion de un item que no esta en la lista no rompe nada`() {
        val a = row("a", addedAt = 100L)
        val r = OrdenDeBiblioteca.filas(listOf(a), mapOf("borrado" to 900L))
        assertEquals(listOf("a"), r.map { it.identifier })
    }
}
```

- [ ] **Step 2: Correr el test para verificar que falla**

```bash
./gradlew testDebugUnitTest --tests "com.arkiv.player.data.biblioteca.OrdenDeBibliotecaTest"
```

Esperado: falla a nivel de **compilación**, con `Unresolved reference: OrdenDeBiblioteca`.

- [ ] **Step 3: Escribir la implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/biblioteca/OrdenDeBiblioteca.kt`:

```kotlin
package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.db.LibraryRow

/**
 * El orden de "Mi biblioteca": lo último que viste primero.
 *
 * La regla es `max(última reproducción, addedAt)`, en una sola lista (lo nunca visto NO va a un
 * bloque aparte): lo que acabás de ver sube al tope, y lo que acabás de agregar también, porque su
 * `addedAt` es "ahora". El `max` y no la reproducción sola es lo que evita que algo visto hace un
 * año pero re-agregado hoy quede enterrado justo cuando lo acabás de buscar.
 *
 * La "última reproducción" incluye capítulos TERMINADOS, no solo los que quedaron a medias: terminar
 * el E4 anoche tiene que dejar la serie primera hoy, con el E5 a un toque. Ese es el caso que este
 * objeto existe para resolver, y por eso no se reusa el criterio de "Continuar viendo"
 * (`observeContinueWatching`, que filtra `watched = 0`), que la tiraría del tope justo al terminar
 * el capítulo.
 *
 * Vive acá y no en el repositorio para poder testearlo sin Room, igual que [VistosDeLaBiblioteca].
 */
object OrdenDeBiblioteca {

    /**
     * [ultimas] es `itemId -> última reproducción en epoch ms`. Un ítem ausente nunca se reprodujo,
     * y ahí manda su `addedAt`.
     */
    fun recenciaDe(row: LibraryRow, ultimas: Map<String, Long>): Long =
        maxOf(ultimas[row.identifier] ?: 0L, row.addedAt)

    /**
     * Las filas crudas ordenadas (la grilla del teléfono).
     *
     * `sortedByDescending` es estable, así que dos ítems con la misma recencia conservan el orden
     * entrante, que viene `addedAt DESC` de `ItemDao.observeLibrary`.
     */
    fun filas(rows: List<LibraryRow>, ultimas: Map<String, Long>): List<LibraryRow> =
        rows.sortedByDescending { recenciaDe(it, ultimas) }

    /**
     * Los grupos ordenados (la grilla del TV). La recencia de un grupo es la de su miembro más
     * reciente: una serie guardada desde varias fuentes es UNA tarjeta, y verla por cualquiera de
     * ellas la sube entera (mismo criterio que [VistosDeLaBiblioteca.cruzar]).
     */
    fun grupos(grupos: List<LibraryGroup>, ultimas: Map<String, Long>): List<LibraryGroup> =
        grupos.sortedByDescending { g -> g.members.maxOf { recenciaDe(it, ultimas) } }
}
```

- [ ] **Step 4: Correr los tests para verificar que pasan**

```bash
./gradlew testDebugUnitTest --tests "com.arkiv.player.data.biblioteca.OrdenDeBibliotecaTest"
```

Esperado: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/biblioteca/OrdenDeBiblioteca.kt app/src/test/java/com/arkiv/player/data/biblioteca/OrdenDeBibliotecaTest.kt
git commit -m "feat(biblioteca): regla de orden por lo ultimo visto"
```

---

### Task 2: La consulta de última reproducción por ítem

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (agregar `UltimaReproduccionRow` junto a `VistoRow` en la línea ~48, y la `@Query` dentro de `PlaybackDao` justo después de `observeVistos()`, línea ~239)

**Interfaces:**
- Consumes: nada de la Tarea 1.
- Produces:
  - `data class UltimaReproduccionRow(val itemId: String, val ultimaMs: Long)`
  - `PlaybackDao.observeUltimaReproduccion(): Flow<List<UltimaReproduccionRow>>`

Sin test: es una `@Query` de Room, y este proyecto no testea Room (no hay Robolectric). Room valida el SQL **en tiempo de compilación** vía KSP — un error de columna o de tipo rompe el build, que es lo que verifica el Step 2.

- [ ] **Step 1: Agregar la data class de la fila**

En `app/src/main/java/com/arkiv/player/data/db/Daos.kt`, justo después de la `data class VistoRow` (que termina en la línea 48), agregar:

```kotlin
/** Cuándo se reprodujo por última vez algo de un ítem, para ordenar la biblioteca. */
data class UltimaReproduccionRow(
    val itemId: String,
    val ultimaMs: Long,
)
```

- [ ] **Step 2: Agregar la consulta al DAO**

En el mismo archivo, dentro de `interface PlaybackDao`, justo después de `fun observeVistos(): Flow<List<VistoRow>>` (línea ~239), agregar:

```kotlin
/**
 * Cuándo se reprodujo por última vez CUALQUIER capítulo de cada ítem, para el orden de la
 * biblioteca (ver [com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]).
 *
 * Gemela de [observeVistos] pero SIN el filtro `watched = 1`: acá cuenta igual el capítulo
 * terminado que el que quedó a medias. Si solo contara lo terminado, una serie que estás viendo
 * ahora mismo no subiría hasta que termines el capítulo; si solo contara lo de a medias, se caería
 * del tope justo al terminarlo.
 *
 * NO hace `JOIN items`: el filtro por ítem vivo lo aplica quien cruza este mapa contra la
 * biblioteca, que ya excluye los borrados. Mismo criterio que [observeVistos].
 */
@Query(
    """
    SELECT e.itemId AS itemId, MAX(p.lastPlayedAt) AS ultimaMs
    FROM playback p
    JOIN episodes e ON e.id = p.episodeId
    WHERE p.deleted = 0 AND e.deleted = 0
    GROUP BY e.itemId
    """
)
fun observeUltimaReproduccion(): Flow<List<UltimaReproduccionRow>>
```

- [ ] **Step 3: Compilar para verificar que Room acepta la consulta**

```bash
./gradlew compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Si Room se queja de columnas (`The columns returned by the query does not have the fields...`), revisar que los alias `itemId` y `ultimaMs` coincidan exactamente con los nombres de `UltimaReproduccionRow`.

- [ ] **Step 4: Correr la suite completa para confirmar que no se rompió nada**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS (incluidos los 8 tests de la Tarea 1).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt
git commit -m "feat(biblioteca): consulta de ultima reproduccion por item"
```

---

### Task 3: Cablear los flows ordenados en el repositorio

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`observeLibraryGroups()` en la línea ~120; el flow nuevo va justo debajo de `observeLibrary()`, línea 113)
- Modify: `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt` (solo el comentario del método `group`, línea ~77)

**Interfaces:**
- Consumes: `OrdenDeBiblioteca.filas(...)` y `OrdenDeBiblioteca.grupos(...)` (Tarea 1); `PlaybackDao.observeUltimaReproduccion()` y `UltimaReproduccionRow` (Tarea 2).
- Produces:
  - `ArkivRepository.observeUltimaReproduccion(): Flow<Map<String, Long>>`
  - `ArkivRepository.observeLibraryOrdenada(): Flow<List<LibraryRow>>`
  - `ArkivRepository.observeLibraryGroups()` mantiene su firma `Flow<List<LibraryGroup>>`; solo cambia el orden que emite.

Sin test: es cableado de flows sobre Room. La lógica que se podía testear ya está cubierta en la Tarea 1; lo de acá se verifica en el aparato (Tarea 5).

- [ ] **Step 1: Agregar el mapa de recencias y el flow de filas ordenadas**

En `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`, justo después de `fun observeLibrary(): Flow<List<LibraryRow>> = itemDao.observeLibrary()` (línea 113), agregar:

```kotlin
    /**
     * `itemId -> cuándo se reprodujo por última vez algo de ese ítem`, para el orden de la
     * biblioteca. Un ítem que nunca se reprodujo no está en el mapa.
     */
    fun observeUltimaReproduccion(): Flow<Map<String, Long>> =
        playbackDao.observeUltimaReproduccion().map { filas ->
            filas.associate { it.itemId to it.ultimaMs }
        }

    /**
     * La biblioteca ordenada por lo último que viste (ver
     * [com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]). La consume la grilla del teléfono.
     *
     * Es un flow aparte y NO el orden de [observeLibrary] a propósito: esa consulta cruda la usan
     * `ensureArtwork`, la pantalla de descargas y el héroe del home del TV, a los que el reorden no
     * les aporta nada. Si el orden viviera en el SQL, la consulta pasaría a depender de `playback`
     * y Room re-emitiría la biblioteca entera cada vez que se guarda progreso — cada pocos segundos
     * mientras reproducís —, disparando una pasada de arte por fila en cada emisión.
     */
    fun observeLibraryOrdenada(): Flow<List<LibraryRow>> =
        combine(observeLibrary(), observeUltimaReproduccion()) { rows, ultimas ->
            com.arkiv.player.data.biblioteca.OrdenDeBiblioteca.filas(rows, ultimas)
        }
```

- [ ] **Step 2: Aplicar el orden a los grupos**

En el mismo archivo, reemplazar el cuerpo de `observeLibraryGroups()` (línea ~120):

```kotlin
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork())
```

por:

```kotlin
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        combine(
            LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork()),
            observeUltimaReproduccion(),
        ) { grupos, ultimas ->
            com.arkiv.player.data.biblioteca.OrdenDeBiblioteca.grupos(grupos, ultimas)
        }
```

El KDoc que ya tiene el método arriba se conserva tal cual; agregarle al final del bloque de comentario esta línea:

```
     * El orden final es por lo último visto ([com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]),
     * no por fecha de agregado: el `sortedByDescending` de [LibraryGrouping.group] queda como
     * desempate, porque el orden de Kotlin es estable.
```

- [ ] **Step 3: Actualizar el comentario de `LibraryGrouping.group`**

En `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt`, el KDoc de `group` (línea ~77) dice hoy que agrupar "no debe reordenar la fila del home, que ya viene ordenada por `addedAt DESC`". Reemplazar esa primera oración por:

```
     * Arma los grupos preservando el orden de entrada por lo más reciente de cada grupo: agrupar no
     * debe reordenar la lista, que ya viene ordenada por `addedAt DESC`. Ese orden es el de
     * DESEMPATE: quien muestra los grupos los reordena después por lo último visto (ver
     * `ArkivRepository.observeLibraryGroups` y `biblioteca.OrdenDeBiblioteca`), y como el orden de
     * Kotlin es estable, dos grupos con la misma recencia conservan este.
```

- [ ] **Step 4: Compilar y correr la suite**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS. `LibraryGroupingTest` sigue verde porque `LibraryGrouping.group` no cambió de comportamiento.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt
git commit -m "feat(biblioteca): flows ordenados por lo ultimo visto"
```

---

### Task 4: Consumir el orden nuevo en la grilla del teléfono

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt` (agregar el StateFlow después de `library`, línea ~32)
- Modify: `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt` (línea 76)

**Interfaces:**
- Consumes: `ArkivRepository.observeLibraryOrdenada()` (Tarea 3).
- Produces: `HomeViewModel.bibliotecaOrdenada: StateFlow<List<LibraryRow>>`.

El TV no necesita tarea propia: `TvLibraryViewModel.grupos` ya sale de `observeLibraryGroups()`, que quedó ordenado en la Tarea 3.

Sin test: es cableado de UI Compose, y este proyecto no tiene tests de instrumentación. Se verifica en el aparato (Tarea 5).

- [ ] **Step 1: Agregar el StateFlow al ViewModel**

En `app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt`, justo después del `val library` (que termina en la línea 32), agregar:

```kotlin
    /**
     * La biblioteca ordenada por lo último que viste, para la grilla de "Mi biblioteca".
     *
     * Es una suscripción aparte de [library] a propósito: [library] cruda alimenta el
     * `onEach { ensureArtwork(rows) }` del `init` (una consulta por fila en cada emisión) y el
     * héroe del home del TV, y no debe re-emitirse cada vez que se guarda progreso.
     */
    val bibliotecaOrdenada: StateFlow<List<LibraryRow>> = repo.observeLibraryOrdenada()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Nota: el parámetro del constructor es `repo: ArkivRepository` sin `private val`, y ya se usa así en las líneas 31 y 34 para los otros flows, así que no hace falta cambiar la firma. Los imports de `StateFlow`, `SharingStarted` y `stateIn` ya están.

- [ ] **Step 2: Apuntar la pantalla al flow nuevo**

En `app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt`, reemplazar la línea 76:

```kotlin
    val library by vm.library.collectAsStateWithLifecycle()
```

por:

```kotlin
    // Ordenada por lo último que viste: lo que venís viendo queda primero, sin ir a buscarlo abajo.
    val library by vm.bibliotecaOrdenada.collectAsStateWithLifecycle()
```

Nada más cambia en el archivo: `filtered`, `hasMovies`, `hasSeries` y el `EmptyState` ya derivan de `library`, así que heredan el orden. El carrusel "Continuar viendo" queda intacto.

- [ ] **Step 3: Compilar y correr la suite**

```bash
./gradlew testDebugUnitTest
```

Esperado: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/home/HomeViewModel.kt app/src/main/java/com/arkiv/player/ui/library/LibraryScreen.kt
git commit -m "feat(biblioteca): la grilla del telefono ordena por lo ultimo visto"
```

---

### Task 5: Verificación en el aparato

**Files:** ninguno (verificación manual).

Lo que los tests JVM no cubren: la consulta de Room, los `combine` del repositorio y el cableado de las dos pantallas. Esto se prueba en aparatos reales.

**Antes de mandar taps por ADB al celular, confirmar con Cristian que no lo esté usando.**

- [ ] **Step 1: Compilar e instalar en el celu**

```bash
./gradlew assembleDebug
```

Instalar por ADB WiFi en el Samsung S24+ (ver el runbook de ADB WiFi del proyecto).

- [ ] **Step 2: Probar el caso que motiva el cambio, en el celu**

1. Abrir "Mi biblioteca" y anotar cuál es la primera tarjeta.
2. Bajar hasta una serie que esté al fondo de la grilla y reproducir un capítulo unos segundos.
3. Salir del reproductor y volver a la biblioteca.

Esperado: esa serie ahora es la **primera** tarjeta de la grilla.

4. Terminar un capítulo entero de otra serie del fondo (o dejar que llegue al final).
5. Volver a la biblioteca.

Esperado: esa otra serie pasó al primer lugar — el capítulo terminado cuenta igual que el de a medias.

6. Cambiar a los chips "Películas" y "Series".

Esperado: dentro de cada filtro el orden también es por lo último visto.

- [ ] **Step 3: Probar en el Fire TV Stick**

Instalar el mismo APK por ADB de red (puerto 5555, ver el runbook del proyecto) y abrir "Mi biblioteca".

Esperado:
- En "Todo", "Series" y "Películas", lo último reproducido está primero.
- Una serie guardada desde varias fuentes sigue siendo **una sola** tarjeta, y sube entera al reproducirla por cualquiera de ellas.
- "Ya visto" y "Descargas" se ven igual que antes.

- [ ] **Step 4: Confirmar que no se rompió el arte**

Con la biblioteca abierta un rato y después de reproducir algo, las carátulas y backdrops siguen apareciendo normalmente (o sea: `ensureArtwork` sigue corriendo, y no en bucle).

- [ ] **Step 5: Reportar el resultado**

Contarle a Cristian qué se probó y qué se vio en cada aparato. Si algo no dio lo esperado, **no** declarar la funcionalidad terminada.
