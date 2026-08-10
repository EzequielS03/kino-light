# Agrupar películas duplicadas — Plan de implementación

> **Para trabajadores agénticos:** SUB-SKILL REQUERIDA: usar `superpowers:subagent-driven-development` (recomendado) o `superpowers:executing-plans` para implementar tarea por tarea. Los pasos usan checkbox (`- [ ]`) para seguimiento.

**Goal:** Que una película que entró a la biblioteca varias veces (dos rips, dos calidades, dos fuentes) se vea como **una sola tarjeta** en la fila Películas del home de TV, sin fusionar nunca dos obras distintas.

**Architecture:** Extiende la agrupación en tiempo de lectura que ya existe para series ([plan anterior](2026-08-10-series-duplicadas-agrupacion.md)). Las películas no tienen ningún id en su `identifier` — son todas `torrent:<infohash>` o `web:<hash>` —, así que la única señal es `artwork.tmdbId`, que se resuelve buscando por título y **sola miente**. La llave cruza dos señales débiles: `tmdbId` **y** título normalizado. Sigue sin tocarse el esquema ni la base.

**Tech Stack:** Kotlin, Compose (TV Material3), Room, Flows de kotlinx.coroutines, JUnit4.

## Global Constraints

- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`.
- **Sin coautoría:** ningún mensaje de commit lleva `Co-Authored-By`.
- **Sin migración de Room:** NO subir `ArkivDatabase.version` (hoy 18) ni agregar columnas.
- **Nunca fusionar dos obras distintas.** Ante la duda, dejar dos tarjetas. Un duplicado molesta; fusionar "Lego Batman" con "Batman (1966)" es un bug que el usuario no puede ni diagnosticar.
- **Comentarios en español**, explicando el *porqué*.
- Otras sesiones commitean sobre `main` en el mismo working tree: `git add` con rutas explícitas, **nunca** `git add -A`.
- Tests: `./gradlew :app:testDebugUnitTest` (827 verdes hoy). Build: `./gradlew :app:assembleDebug`.

---

## Evidencia (medida sobre la biblioteca real, 2026-08-10)

55 películas vivas. Aplicando "mismo `tmdbId` **y** mismo título normalizado":

| Veredicto | Grupos | Detalle |
|---|---|---|
| **Fusiona** | 9 | Supergirl ×7, El retorno del rey ×4, Superman ×3, Batman Azteca ×3, Deadpool ×2, Blood+ ×2, Naruto ×2, Un pequeño Batman Navideño ×2, Interstellar ×2 (2160p + 1080p) |
| **Se abstiene, correctamente** | 2 | `movie:324849` "Batman: La película (1966)" vs "Lego Batman: la película"; `movie:123025` "El caballero oscuro" vs "Batman: El regreso del Caballero Oscuro, Parte 1" |
| **Se abstiene de más** | 3 | "The Batman" vs "Batman"; "Batman v Superman" vs "Batman vs Superman"; "la batalla de los hijos" vs "los Super hijos" |

**Cero falsos positivos.** 26 tarjetas pasarían a 9. Aflojar la comparación para pescar las 3 perdidas reintroduce exactamente el riesgo que la regla evita — **no aflojarla**.

Otros datos medidos:
- Solo **2** películas no tienen `tmdbId` resuelto: quedan como grupo de una.
- **Ningún** título normalizado aparece bajo dos `tmdbId` distintos, así que el guard de título nunca separa de más por sí solo.
- Los 3 "Superman" comparten póster (`fvUJb08…`): son la misma obra.

## Matriz de escenarios

Cada fila tiene su test en la Tarea 1 salvo donde se indica.

| # | Escenario | Esperado |
|---|---|---|
| 1 | Dos rips, mismo `tmdbId`, mismo título | Fusionan |
| 2 | Misma peli, distinta calidad (`Interstellar (2014) · 2160p` / `· 1080p`) | Fusionan |
| 3 | Obras distintas con el mismo `tmdbId` (Lego Batman / Batman 1966) | **NO** fusionan |
| 4 | Misma obra con títulos distintos (The Batman / Batman) | **NO** fusionan (conservador a propósito) |
| 5 | Película sin `tmdbId` | Grupo de una |
| 6 | Película cuyo artwork quedó con `tmdbType = "tv"` | No aplica la regla de película → grupo de una |
| 7 | Una serie y una película con el mismo número de `tmdbId` | Llaves distintas (`tv:` vs `movie:`) → no colisionan |
| 8 | Título que queda vacío al normalizar (p. ej. `"(2024)"`) | Cae al título completo, no a `""` |
| 9 | Se **renombra** una película de un grupo → su llave cambia | `resolveMembers` sigue el rastro (Tarea 2) |
| 10 | "Marcar como serie" sobre el primary de un grupo | El ítem sale del grupo; el resto sigue agrupado |
| 11 | Clic en un grupo de **1** fuente | Reproduce directo (comportamiento de hoy) |
| 12 | Clic en un grupo de **>1** fuente | Abre el detalle con el selector de fuentes (Tarea 3) |

El escenario 12 es una decisión de producto deliberada: hoy una película se reproduce con un clic, y agrupar dos calidades sin dar a elegir le quitaría al usuario justo la elección que la agrupación habilita. Un grupo de una sigue reproduciendo directo, así que el camino común no cambia.

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt` | **Modificar.** `normalizedMovieTitle`, la rama de película en `groupKeyOf`, la rama `movie:` en `resolveMembers`, y los dos *minor* pendientes. |
| `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt` | **Modificar.** Un test por escenario de la matriz. |
| `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt` | **Modificar.** La fila Películas pasa a grupos; el clic distingue 1 fuente de varias. |

---

### Task 1: La llave de grupo de películas (lógica pura)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt`
- Test: `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt`

**Interfaces:**
- Consumes: `LibraryRow`, `ArtworkEntity`, la `groupKeyOf` existente.
- Produces: `LibraryGrouping.normalizedMovieTitle(title: String): String`; `groupKeyOf` devuelve `movie:<tmdbId>:<tituloNormalizado>` para películas agrupables.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `LibraryGroupingTest.kt`. Reusar los helpers `row(...)` y `art(...)` que ya existen en la clase (mirar su firma antes de escribir; `row` toma `category` y `eps`, y una película es `category = "movie"` o `eps <= 1` sin override).

```kotlin
    // --- Escenarios de agrupación de películas (ver la matriz del plan) ---

    /** 1: dos rips de la misma peli, mismo tmdbId y mismo título -> una tarjeta. */
    @Test
    fun `dos rips de la misma pelicula fusionan`() {
        val a = row("torrent:aaa", "Supergirl", 1, source = "torrent", category = "movie")
        val b = row("torrent:bbb", "Supergirl", 1, source = "torrent", category = "movie")
        assertEquals("movie:1081003:supergirl", LibraryGrouping.groupKeyOf(a, art(a.identifier, 1081003, "movie")))
        assertEquals("movie:1081003:supergirl", LibraryGrouping.groupKeyOf(b, art(b.identifier, 1081003, "movie")))
    }

    /** 2: el sufijo de calidad no debe separar dos copias de la misma peli. */
    @Test
    fun `la misma pelicula en dos calidades fusiona`() {
        val uhd = row("torrent:uhd", "Interstellar (2014) · 2160p", 1, source = "torrent", category = "movie")
        val hd = row("torrent:hd", "Interstellar (2014) · 1080p", 1, source = "torrent", category = "movie")
        assertEquals("movie:157336:interstellar", LibraryGrouping.groupKeyOf(uhd, art(uhd.identifier, 157336, "movie")))
        assertEquals("movie:157336:interstellar", LibraryGrouping.groupKeyOf(hd, art(hd.identifier, 157336, "movie")))
    }

    /**
     * 3: EL caso que justifica todo el guard de título. El resolver de artwork busca por título y
     * mete estas dos obras distintas bajo el mismo tmdbId; fusionarlas sería peor que el duplicado.
     */
    @Test
    fun `dos peliculas distintas con el mismo tmdbId NO fusionan`() {
        val lego = row("torrent:lego", "Lego Batman: la película", 1, source = "torrent", category = "movie")
        val vieja = row("torrent:1966", "Batman: La película (1966)", 1, source = "torrent", category = "movie")
        val kLego = LibraryGrouping.groupKeyOf(lego, art(lego.identifier, 324849, "movie"))
        val kVieja = LibraryGrouping.groupKeyOf(vieja, art(vieja.identifier, 324849, "movie"))
        assertNotEquals(kLego, kVieja)
    }

    /** 4: conservador a propósito — dos títulos distintos de la misma obra quedan separados. */
    @Test
    fun `la misma obra con titulos distintos queda separada`() {
        val a = row("torrent:a", "The Batman", 1, source = "torrent", category = "movie")
        val b = row("torrent:b", "Batman", 1, source = "torrent", category = "movie")
        assertNotEquals(
            LibraryGrouping.groupKeyOf(a, art(a.identifier, 414906, "movie")),
            LibraryGrouping.groupKeyOf(b, art(b.identifier, 414906, "movie")),
        )
    }

    /** 5: sin tmdbId no hay llave que cruzar -> grupo de una. */
    @Test
    fun `una pelicula sin tmdbId queda sola`() {
        val r = row("torrent:sin", "Peli Rara", 1, source = "torrent", category = "movie")
        assertEquals("item:torrent:sin", LibraryGrouping.groupKeyOf(r, art(r.identifier, null, null)))
        assertEquals("item:torrent:sin", LibraryGrouping.groupKeyOf(r, null))
    }

    /** 6: un artwork de tipo tv sobre una película no habilita la regla de película. */
    @Test
    fun `una pelicula con artwork de tipo tv queda sola`() {
        val r = row("torrent:raro", "Naruto", 1, source = "torrent", category = "movie")
        assertEquals("item:torrent:raro", LibraryGrouping.groupKeyOf(r, art(r.identifier, 46260, "tv")))
    }

    /** 7: los ids de TMDB de película y de serie son espacios distintos; las llaves no deben chocar. */
    @Test
    fun `una serie y una pelicula con el mismo numero de tmdbId no colisionan`() {
        val serie = row("web:series:tt1", "Cosa", 10)
        val peli = row("torrent:x", "Cosa", 1, source = "torrent", category = "movie")
        assertNotEquals(
            LibraryGrouping.groupKeyOf(serie, art(serie.identifier, 12345, "tv")),
            LibraryGrouping.groupKeyOf(peli, art(peli.identifier, 12345, "movie")),
        )
    }

    /** 8: un título que se queda sin nada al recortarlo no puede colapsar a la cadena vacía. */
    @Test
    fun `un titulo que queda vacio al normalizar cae al titulo completo`() {
        assertEquals("2024", LibraryGrouping.normalizedMovieTitle("(2024)"))
        assertEquals("", LibraryGrouping.normalizedMovieTitle(""))
    }

    /** El acento y los signos son parte del título: no se tocan salvo la puntuación separadora. */
    @Test
    fun `la normalizacion conserva acentos y signos del titulo`() {
        assertEquals("elseñordelosanilloselretornodelrey", LibraryGrouping.normalizedMovieTitle("El señor de los anillos: El retorno del rey"))
        assertEquals("blood+", LibraryGrouping.normalizedMovieTitle("Blood+"))
    }
```

Agregar el import `org.junit.Assert.assertNotEquals` si no está.

- [ ] **Step 2: Correr los tests y verificar que fallan**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: FALLA. Primero por `Unresolved reference: normalizedMovieTitle`; una vez que exista, los de fusión fallan porque `groupKeyOf` todavía devuelve `item:<identifier>` para toda película.

- [ ] **Step 3: Implementación**

En `LibraryGrouping.kt`, agregar la normalización y cambiar la rama de película de `groupKeyOf`:

```kotlin
    /**
     * Título "desnudo" de una película, para poder compararlo entre dos adquisiciones.
     *
     * Recorta desde el primer `(` (el año o un calificador: "Batman: La película (1966)") o desde
     * el primer `·` (el sufijo de calidad que arma la app: "Interstellar (2014) · 2160p"), lo que
     * venga primero, y saca la puntuación separadora. NO toca acentos ni signos que son parte del
     * nombre ("Blood+", "El señor..."): sacarlos solo agregaría fusiones, que es el error caro.
     *
     * Si al recortar no queda nada (un título que empieza con paréntesis), cae al título completo:
     * devolver "" haría que todas esas películas compartieran llave.
     */
    fun normalizedMovieTitle(title: String): String {
        val cut = title.indexOfFirst { it == '(' || it == '·' }
        val base = if (cut > 0) title.substring(0, cut) else title
        val clean = base.lowercase().filterNot { it.isWhitespace() || it in ":,.-!?_/" }
        return clean.ifBlank { title.lowercase().filterNot { it.isWhitespace() || it in ":,.-!?_/" } }
    }
```

Y en `groupKeyOf`, reemplazar la primera línea (`if (row.isMovie) return "item:${row.identifier}"`) por:

```kotlin
        if (row.isMovie) {
            // Las películas no tienen ningún id en su identifier (son `torrent:<infohash>` /
            // `web:<hash>`), así que la única señal es el tmdbId de artwork — que se resuelve
            // buscando por TÍTULO y solo, miente: junta "Lego Batman" con "Batman (1966)" bajo
            // movie:324849, y "El caballero oscuro" con "El regreso del Caballero Oscuro Parte 1"
            // bajo movie:123025. Por eso la llave cruza DOS señales débiles, id y título: medido
            // sobre la biblioteca real da cero falsos positivos, a cambio de perderse tres
            // fusiones donde el título difiere ("The Batman" vs "Batman"). Ese es el error barato.
            val movieId = artwork?.tmdbId?.takeIf { artwork.tmdbType == "movie" }
                ?: return "item:${row.identifier}"
            return "movie:$movieId:${normalizedMovieTitle(row.title)}"
        }
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: PASS. Después la suite completa, que **va a romper**: hay tests viejos que asumen que una película siempre da `item:<identifier>` (buscar `assertEquals("item:` y el test `las peliculas nunca se agrupan aunque compartan tmdbId`). Ese test seguía siendo válido con la regla vieja; con la nueva, su fixture usa **títulos distintos**, así que sigue sin fusionar — pero por el guard de título, no por la exclusión. Actualizar su nombre y su doc para que diga lo que ahora comprueba, sin debilitar la aserción.

```bash
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt
git commit -m "feat(biblioteca): agrupar peliculas por tmdbId y titulo normalizado"
```

---

### Task 2: `resolveMembers` sigue las llaves `movie:`, y los dos *minor* pendientes

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt`
- Test: `app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt`

**Interfaces:**
- Consumes: `resolveMembers`, `LibraryGroup.episodeCount` (ya existen).
- Produces: sin firmas nuevas; cambia el comportamiento de `resolveMembers` y de `episodeCount`.

**Contexto — por qué esto es obligatorio y no un extra.** Una llave de grupo es estado derivado: si cambia mientras el detalle está abierto, la ruta deja de resolver y la pantalla queda en "No se pudo cargar este contenido". Ya pasó dos veces en el plan anterior (llaves `item:` y `series:`). Una llave `movie:` es **más** volátil que aquellas: depende del título, y `ArkivRepository.renameItem` deja renombrar un ítem desde el menú de mantener presionado. Renombrar una película de un grupo mientras su detalle está abierto cambia la llave y huerfaniza la pantalla.

- [ ] **Step 1: Escribir los tests que fallan**

```kotlin
    /**
     * 9: renombrar una película cambia su título normalizado y con eso la llave del grupo. La ruta
     * abierta sigue apuntando a la llave vieja, así que resolveMembers tiene que seguir el rastro
     * igual que hace con `item:` y `series:` — si no, el detalle abierto queda huérfano.
     */
    @Test
    fun `resolveMembers sigue una llave movie cuya pelicula fue renombrada`() {
        val a = row("torrent:aaa", "Supergirl", 1, source = "torrent", category = "movie")
        val b = row("torrent:bbb", "Supergirl", 1, source = "torrent", category = "movie")
        val artwork = mapOf(
            a.identifier to art(a.identifier, 1081003, "movie"),
            b.identifier to art(b.identifier, 1081003, "movie"),
        )
        val llaveVieja = LibraryGrouping.groupKeyOf(a, artwork[a.identifier])
        // El usuario renombra la primera; su título normalizado cambia y el grupo se parte.
        val renombrada = a.copy(title = "Supergirl 2024")
        val groups = LibraryGrouping.group(listOf(renombrada, b), artwork)
        assertNull(groups.firstOrNull { it.key == llaveVieja })
        val miembros = LibraryGrouping.resolveMembers(llaveVieja, groups, listOf(renombrada, b))
        assertTrue(miembros.isNotEmpty())
    }

    /** El grupo nunca viene vacío desde group(), pero episodeCount no debe poder tirar si lo estuviera. */
    @Test
    fun `episodeCount de un grupo sin miembros es cero y no explota`() {
        assertEquals(0, LibraryGroup(key = "item:x", primary = row("x", "X", 3), members = emptyList()).episodeCount)
    }

    /**
     * Si dos filas con el mismo seriesId terminaran en grupos tv distintos (TMDB matchea por
     * título), quedarse con el primero perdería miembros. Devolver los de todos los que matcheen.
     */
    @Test
    fun `resolveMembers junta los miembros de todos los grupos que matcheen una llave series`() {
        val web = row("web:series:tt99", "Cosa", 10)
        val torrent = row("torrent:series:tt99", "Cosa", 4, source = "torrent")
        val artwork = mapOf(
            web.identifier to art(web.identifier, 111, "tv"),
            torrent.identifier to art(torrent.identifier, 222, "tv"),
        )
        val groups = LibraryGrouping.group(listOf(web, torrent), artwork)
        assertEquals(2, groups.size)
        val miembros = LibraryGrouping.resolveMembers("series:tt99", groups, listOf(web, torrent))
        assertEquals(2, miembros.size)
    }
```

Agregar los imports `assertNull` / `assertTrue` si faltan.

- [ ] **Step 2: Correr y verificar que fallan**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.LibraryGroupingTest"
```

Esperado: los tres fallan — no hay rama `movie:` en `resolveMembers`, `episodeCount` usa `maxOf` (que tira `NoSuchElementException` con la lista vacía) y la rama `series:` usa `firstOrNull`.

- [ ] **Step 3: Implementación**

En `LibraryGroup`, hacer total la expresión:

```kotlin
    /**
     * Capítulos de la adquisición más completa. NO la suma: las fuentes son copias alternativas de
     * la misma obra, no contenido distinto (seis Naruto sumaban 794 para una serie de ~220).
     * `maxOfOrNull` y no `maxOf` porque este data class es público y nada obliga a que `members`
     * venga con algo; hoy `group()` es el único constructor y nunca lo deja vacío.
     */
    val episodeCount: Int get() = members.maxOfOrNull { it.episodeCount } ?: 0
```

En `resolveMembers`, agregar la rama `movie:` (antes del `return` final) y cambiar la de `series:` para juntar todos los grupos que matcheen:

```kotlin
        if (groupKey.startsWith("series:")) {
            val seriesId = groupKey.removePrefix("series:")
            // TODOS los grupos que matcheen, no el primero: si dos filas del mismo seriesId
            // quedaron en grupos tv distintos (TMDB matchea por título), quedarse con uno perdería
            // miembros silenciosamente.
            val hits = groups.filter { g -> g.members.any { m -> SeriesItemIds.seriesIdOrNull(m.identifier) == seriesId } }
            if (hits.isNotEmpty()) return hits.flatMap { it.members }.distinctBy { it.identifier }.sortedByDescending { it.episodeCount }
        }
        if (groupKey.startsWith("movie:")) {
            // Una llave `movie:` es la MÁS volátil de todas: lleva el título normalizado adentro, y
            // renameItem deja cambiar el título desde el menú de mantener presionado. Si cambia con
            // el detalle abierto, sin este paso la pantalla queda huérfana.
            val hits = groups.filter { g -> g.members.any { m -> groupKeyOf(m, artworkOf(m)) == groupKey } }
            if (hits.isNotEmpty()) return hits.flatMap { it.members }.distinctBy { it.identifier }.sortedByDescending { it.episodeCount }
        }
```

**Ojo:** `resolveMembers` no recibe el mapa de artwork, así que no puede recomputar `groupKeyOf` de un miembro. Resolver esto de la forma más simple que funcione — la opción recomendada es comparar por el `tmdbId` que ya está embebido en la llave contra la llave **actual** de cada grupo: dos llaves `movie:<id>:<titulo>` con el mismo `<id>` son la misma película para este fin, y el título solo cambió por un renombre. Es decir:

```kotlin
        if (groupKey.startsWith("movie:")) {
            val movieId = groupKey.removePrefix("movie:").substringBefore(':')
            val hits = groups.filter { it.key.startsWith("movie:$movieId:") }
            if (hits.isNotEmpty()) return hits.flatMap { it.members }.distinctBy { it.identifier }.sortedByDescending { it.episodeCount }
        }
```

Documentar en el KDoc que este paso afloja el guard de título **solo para reencontrar una ruta ya abierta**, nunca para agrupar: la agrupación sigue exigiendo id + título.

- [ ] **Step 4: Correr los tests y verificar que pasan**

```bash
./gradlew :app:testDebugUnitTest
```

Esperado: toda la suite verde.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/LibraryGrouping.kt app/src/test/java/com/arkiv/player/data/LibraryGroupingTest.kt
git commit -m "fix(biblioteca): resolveMembers sigue llaves movie y no pierde miembros"
```

---

### Task 3: La fila Películas del home pinta grupos

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`

**Interfaces:**
- Consumes: `HomeViewModel.libraryGroups` (ya existe), `LibraryGroup`, el composable `TvSeriesSection` que ya pinta la fila de Series.
- Produces: la fila Películas renderiza `LibraryGroup`.

- [ ] **Step 1: Generalizar la sección**

`TvSeriesSection` ya hace casi todo, pero tiene la etiqueta "Series" fija y siempre muestra el contador de episodios. Renombrarlo a `TvGroupSection` y parametrizar esas dos cosas (`label: String`, y que el contador de episodios solo se muestre cuando el grupo no es de película). Actualizar la llamada de Series. No cambiar nada más de su comportamiento: el badge sigue siendo `"${sourceCount} FUENTES"` cuando hay más de una fuente.

- [ ] **Step 2: Pasar la fila Películas a grupos**

Derivar `val movieGroups = libraryGroups.filter { it.primary.isMovie }` junto a `seriesGroups`, borrar `val movies = library.filter { it.isMovie }` (queda sin uso) y reemplazar la llamada a `TvLibrarySection` de Películas por `TvGroupSection`. Ajustar `firstPosterId` para que su rama de películas use `movieGroups.firstOrNull()?.key`.

Si después de esto `TvLibrarySection` queda sin ningún llamador, borrarlo — no dejar código muerto.

- [ ] **Step 3: El clic distingue una fuente de varias**

Hoy `open(row)` reproduce una película directo. Con grupos:

```kotlin
    // Un grupo de una fuente se comporta como siempre (clic = reproducir). Con varias, abrir el
    // detalle: agrupar dos calidades de la misma peli sin dejar elegir le quitaría al usuario justo
    // la elección que la agrupación habilita (Interstellar 2160p vs 1080p).
    fun openGroup(group: LibraryGroup) {
        if (group.primary.isMovie && group.sourceCount == 1) open(group.primary) else onOpenItem(group.key)
    }
```

y usarlo como `onClickRow` en las dos filas. La fila de Series ya navega con `it.key`, así que su comportamiento no cambia.

- [ ] **Step 4: Compilar y verificar en la TV**

```bash
./gradlew :app:assembleDebug
```

Instalar (APK ~150 MB, `adb install` se corrompe por WiFi — hay que empujar y después instalar):

```bash
adb -s 192.168.1.22:5555 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/arkiv.apk
```

```bash
adb -s 192.168.1.22:5555 shell pm install -r /data/local/tmp/arkiv.apk
```

```bash
adb -s 192.168.1.22:5555 shell monkey -p com.arkiv.player -c android.intent.category.LEANBACK_LAUNCHER 1
```

Esperar ~15 s. `input tap` NO hace nada en el Fire Stick: navegar solo con D-pad (`input keyevent 19/20/21/22`, `23` = OK, `4` = atrás). Verificar con `exec-out screencap -p`:
- La fila Películas tiene **una** tarjeta de Supergirl con badge "7 FUENTES" (hoy hay 7 tarjetas).
- "El señor de los anillos: El retorno del rey" aparece **una** vez ("4 FUENTES").
- **Siguen apareciendo dos tarjetas** para "Lego Batman: la película" y "Batman: La película (1966)" — si estas se fusionaron, es un fallo grave, no un detalle.
- Abrir la tarjeta de Supergirl: como tiene varias fuentes, debe abrir el detalle con la fila "Fuentes", no reproducir directo.
- Abrir una película con una sola fuente: debe reproducir directo, como siempre.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt
git commit -m "feat(home tv): una tarjeta por pelicula aunque tenga varias fuentes"
```

---

## Fuera de alcance

- **Las 3 fusiones que la regla se pierde** ("The Batman" vs "Batman", etc.). Pescarlas necesita comparación difusa de títulos, que es exactamente el riesgo que este diseño evita.
- **El menú de mantener presionado** sigue navegando con el identifier crudo, así que su "Ver detalle" abre sin selector de fuentes. Es una inconsistencia conocida y anotada del plan anterior.
- **El home del teléfono.** Solo se toca el de TV.
