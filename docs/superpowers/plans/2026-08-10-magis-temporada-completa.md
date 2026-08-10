# Temporada completa de Magis al reproducir un capítulo — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que reproducir un capítulo de una serie de Magis guarde la temporada completa en la
biblioteca (sin descargar nada) y que el detalle diga por qué capítulo vas.

**Architecture:** La lógica que se puede probar sin Room ni red vive en objetos puros
(`MagisEntities`, `ContadorDeNuevos`, `ItemDetail`, `EtiquetaDeCapitulo`) y el repositorio solo los
cablea a los DAOs — que es como ya está armado el resto del proyecto. `SearchPlayback` expone un
único método que guarda la temporada y devuelve el `episodeId` a reproducir, y las dos pantallas
(TV y celu) lo llaman igual. Los ids son estables y derivados del contenido, así que guardar la misma
temporada N veces es idempotente.

**Tech Stack:** Kotlin, Android, Jetpack Compose (+ Compose for TV), Room (KSP), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-10-magis-temporada-completa-design.md`

## Global Constraints

- **Commits sin coautoría.** Nunca agregar `Co-Authored-By: Claude ...` ni ningún pie de coautoría.
- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`
  (ya está configurada en el repo; verificar con `git config user.name` antes del primer commit).
- **Otras sesiones de Claude comparten este working tree, y se trabaja directo sobre `main`.** Nunca
  `git add -A` ni `git add .`: agregar SIEMPRE los archivos por ruta explícita. Y commitear SIEMPRE
  con pathspec explícito — `git commit -m "..." -- <rutas>` — porque el índice puede tener trabajo
  ajeno ya preparado y un `git commit` pelado se lo lleva puesto. Antes de commitear,
  `git diff --cached --stat` para ver qué hay.
- No cambiar de rama, no hacer `git pull`, `git rebase` ni `git reset`: `main` se mueve sola por
  debajo. Si un commit falla por conflicto, parar y reportar en vez de resolverlo.
- Hay trabajo ajeno sin commitear en `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt`.
  Ninguna tarea de este plan lo toca: no lo agregues, no lo revierta, no lo commitees.
- Tests: JUnit 4, nombres de método en `snake_case` en español, sin backticks (convención del repo,
  ver `MagisEntitiesTest`).
- Comentarios y KDoc en español, explicando **por qué**, no qué (convención del repo).
- Correr los tests con `./gradlew testDebugUnitTest --tests "<clase>"` desde `/Users/cristian/archive`.
- No tocar el botón "Guardar N" / "Guardar toda la temporada": ese sigue guardando **y descargando**.

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `data/MagisEntities.kt` (modificar) | Puro: de datos del portal a (ítem, episodios). Se le suma `buildSeason` y el modelo `CapituloDeTemporada`. |
| `data/nuevos/ContadorDeNuevos.kt` (modificar) | Puro: el badge de novedades. Se le suma la regla de re-sellado. |
| `data/ArkivRepository.kt` (modificar) | Cableado a Room: `addMagisSeason`, `marcarEnCurso`, y la regla de "por dónde voy" en `ItemDetail`. |
| `ui/search/SearchPlayback.kt` (modificar) | Resolver+guardar una fuente elegida. Se le suma `playMagisSeason`. |
| `ui/tv/TvSearchScreen.kt`, `ui/catalog/MagisSeasonDialog.kt`, `ui/search/SearchScreen.kt` (modificar) | Las dos pantallas de temporada pasan a llamar `playMagisSeason`. |
| `ui/player/PlayerViewModel.kt` (modificar) | Sella el capítulo como "en curso" al arrancar. |
| `ui/EtiquetaDeCapitulo.kt` (crear) | Puro: "T1 · E5", "Vas en E5 · 20 episodios", texto del botón. Compartido por TV y celu. |
| `ui/tv/TvDetailScreen.kt`, `ui/detail/DetailScreen.kt` (modificar) | Usan esos textos. |

---

### Task 1: `MagisEntities.buildSeason` — la temporada completa como dato puro

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/MagisEntities.kt`
- Test: `app/src/test/java/com/arkiv/player/data/MagisEntitiesTest.kt`

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces:
  - `data class CapituloDeTemporada(val number: Int, val title: String, val ref: String)` en el
    paquete `com.arkiv.player.data`.
  - `MagisEntities.buildSeason(contentId: String, title: String, capitulos: List<CapituloDeTemporada>, posterUrl: String, ahora: Long, seriesRef: String, existente: ItemEntity?): Pair<ItemEntity, List<EpisodeEntity>>`

- [ ] **Step 1: Escribir los tests que fallan**

Agregar al final de `MagisEntitiesTest.kt`, ANTES de la llave de cierre de la clase (el helper
`capitulo(...)` que ya existe arriba se reusa en el tercer test):

```kotlin
    private fun temporada(
        contentId: String = "ABC",
        title: String = "Dragon Ball Daima T1",
        capitulos: List<CapituloDeTemporada> = listOf(
            CapituloDeTemporada(1, "El misterio", "ref-1"),
            CapituloDeTemporada(2, "El deseo", "ref-2"),
            CapituloDeTemporada(3, "La aventura", "ref-3"),
        ),
        seriesRef: String = "ref-temporada",
        existente: ItemEntity? = null,
    ) = MagisEntities.buildSeason(
        contentId = contentId, title = title, capitulos = capitulos,
        posterUrl = "poster.jpg", ahora = 1_000L, seriesRef = seriesRef, existente = existente,
    )

    @Test fun la_temporada_entra_como_UN_item_con_todos_sus_capitulos() {
        val (item, eps) = temporada()
        assertEquals("magis:ABC", item.identifier)
        assertEquals("series", item.categoryOverride)
        assertEquals(listOf("magis:ABC::e1", "magis:ABC::e2", "magis:ABC::e3"), eps.map { it.id })
        assertEquals(listOf(1, 2, 3), eps.map { it.episode })
        assertEquals(listOf(1, 2, 3), eps.map { it.orderIndex })
        assertEquals(listOf("ref-1", "ref-2", "ref-3"), eps.map { it.torrentData })
    }

    @Test fun un_capitulo_de_la_temporada_sale_igual_que_guardado_de_a_uno() {
        // Si divergieran, guardar la temporada duplicaría los capítulos que ya estaban sueltos:
        // el id es la clave primaria y `upsert` es REPLACE, así que TIENE que coincidir.
        val (_, suelto) = capitulo(episode = 2, ref = "ref-2", episodeTitle = "El deseo")
        val dentro = temporada().second.first { it.episode == 2 }
        assertEquals(suelto.id, dentro.id)
        assertEquals(suelto.displayName, dentro.displayName)
        assertEquals(suelto.itemId, dentro.itemId)
    }

    @Test fun guardar_la_temporada_otra_vez_no_duplica_ni_reordena_el_home() {
        // Esto corre en CADA reproducción: si moviera `addedAt`, la serie saltaría al principio del
        // home cada vez que le das play a un capítulo.
        val previo = temporada().first.copy(addedAt = 500L, episodiosVistosEnLista = 4, tmdbId = 123)
        val (item, eps) = temporada(existente = previo)
        assertEquals(500L, item.addedAt)
        assertEquals(4, item.episodiosVistosEnLista)
        assertEquals(123, item.tmdbId)
        assertEquals(3, eps.size)
        assertEquals(3, eps.map { it.id }.distinct().size)
    }

    @Test fun el_ref_de_la_temporada_manda_y_en_blanco_no_pisa_el_guardado() {
        // Los refs caducan y se re-emiten; uno en blanco nunca debe borrar uno bueno.
        assertEquals("ref-temporada", temporada().first.torrentData)
        val previo = temporada(seriesRef = "ref-buena").first
        assertEquals("ref-buena", temporada(seriesRef = "", existente = previo).first.torrentData)
    }

    @Test fun una_temporada_sin_capitulos_no_inventa_episodios() {
        val (item, eps) = temporada(capitulos = emptyList())
        assertEquals("magis:ABC", item.identifier)
        assertEquals(0, eps.size)
    }
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.MagisEntitiesTest"`
Expected: FAIL — no compila, `Unresolved reference: buildSeason` y `Unresolved reference: CapituloDeTemporada`.

- [ ] **Step 3: Implementar**

En `MagisEntities.kt`, agregar el modelo arriba del `object` (después de los imports):

```kotlin
/**
 * Un capítulo de una temporada, tal como lo necesita [MagisEntities].
 *
 * Modelo propio y no `GatewayEpisode` a propósito: `MagisEntities` es puro/JVM (se testea sin Room
 * ni red) y no debe depender del paquete `gateway`. El llamador mapea uno al otro.
 */
data class CapituloDeTemporada(val number: Int, val title: String, val ref: String)
```

Dentro del `object MagisEntities`, extraer el armado del episodio que ya usa `build` (para que la
temporada y el capítulo suelto no puedan divergir) y agregar `buildSeason`:

```kotlin
    /**
     * El episodio de UN capítulo. Lo comparten [build] y [buildSeason] a propósito: el `id` es la
     * clave primaria, así que si los dos caminos no lo armaran idéntico, guardar la temporada
     * duplicaría los capítulos que ya estaban guardados sueltos.
     */
    private fun capituloDe(itemId: String, number: Int, title: String, ref: String) = EpisodeEntity(
        id = "$itemId::e$number",
        itemId = itemId,
        section = "",
        displayName = "E$number" + title.trim().takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
        orderIndex = number,
        durationSeconds = 0.0,
        thumbPath = null,
        originalPath = null,
        originalFormat = null,
        originalSize = 0,
        derivativePath = null,
        derivativeFormat = null,
        derivativeSize = 0,
        // Numerado a propósito: es con esto que `CapitulosFaltantes` sabe cuál falta.
        season = null,
        episode = number,
        torrentFileIndex = null,
        torrentData = ref,
    )

    /**
     * La temporada COMPLETA: un ítem y un episodio por capítulo.
     *
     * Es lo que se guarda al tocar un capítulo para verlo — la lista ya la tiene la pantalla, así
     * que no cuesta ni una llamada de red. Corre en cada reproducción, así que **tiene que ser
     * idempotente**: ids derivados del contenido y todo lo que `upsertItem` (REPLACE) borraría
     * copiado de [existente], igual que en [build].
     *
     * [seriesRef] es el ref de la temporada (el que responde `/v1/episodes`). En blanco no pisa el
     * guardado: los refs caducan y uno vencido es mejor que ninguno. A diferencia de [build], acá
     * NO se cae al ref de un capítulo como último recurso — un ref de capítulo en el ítem haría que
     * `BuscadorDeCapitulos` le pidiera la lista de capítulos a un capítulo.
     */
    fun buildSeason(
        contentId: String,
        title: String,
        capitulos: List<CapituloDeTemporada>,
        posterUrl: String,
        ahora: Long,
        seriesRef: String,
        existente: ItemEntity?,
    ): Pair<ItemEntity, List<EpisodeEntity>> {
        val itemId = itemIdDe(contentId)
        val item = ItemEntity(
            identifier = itemId,
            title = title.ifBlank { "Magis" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existente?.addedAt ?: ahora,
            categoryOverride = "series",
            source = "magis",
            torrentData = seriesRef.ifBlank { existente?.torrentData.orEmpty() }.takeIf { it.isNotBlank() },
            episodiosVistosEnLista = existente?.episodiosVistosEnLista,
            tmdbId = existente?.tmdbId,
        )
        return item to capitulos.map { capituloDe(itemId, it.number, it.title, it.ref) }
    }
```

Y en `build`, reemplazar el bloque `EpisodeEntity(...)` de la rama `esCapitulo` por
`capituloDe(itemId, episode, episodeTitle, ref)`, dejando el resto de la función igual.

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.MagisEntitiesTest"`
Expected: PASS — los 5 tests nuevos y los 10 que ya estaban (la refactorización de `build` no puede
romperlos: son los que fijan el contrato del capítulo suelto).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/MagisEntities.kt app/src/test/java/com/arkiv/player/data/MagisEntitiesTest.kt && git diff --cached --stat && git commit -m "feat(magis): armar la temporada completa como dato puro" -- app/src/main/java/com/arkiv/player/data/MagisEntities.kt app/src/test/java/com/arkiv/player/data/MagisEntitiesTest.kt
```

---

### Task 2: Guardar la temporada en la base sin prender el badge de novedades

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/nuevos/ContadorDeNuevos.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`addMagisSource` ~línea 677 y
  siguientes)
- Test: `app/src/test/java/com/arkiv/player/data/nuevos/ContadorDeNuevosTest.kt`

**Interfaces:**
- Consumes: `MagisEntities.buildSeason(...)` y `CapituloDeTemporada` (Task 1).
- Produces:
  - `ContadorDeNuevos.reSellar(vistos: Int?, totalAhora: Int): Int?`
  - `ArkivRepository.addMagisSeason(contentId: String, title: String, capitulos: List<CapituloDeTemporada>, seriesRef: String, posterUrl: String = "", backdropUrl: String = ""): Map<Int, String>`
    — devuelve `número de capítulo → episodeId`.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `ContadorDeNuevosTest.kt`, dentro de la clase:

```kotlin
    @Test fun guardar_capitulos_vos_mismo_no_prende_el_badge() {
        // Guardás la temporada entera de una serie que ya tenías con 3 capítulos y el detalle ya
        // abierto: los 17 que aparecen no son novedades del portal, los trajiste vos.
        assertEquals(20, ContadorDeNuevos.reSellar(vistos = 3, totalAhora = 20))
        assertEquals(0, ContadorDeNuevos.cuantos(20, ContadorDeNuevos.reSellar(3, 20)))
    }

    @Test fun una_serie_que_nunca_abriste_sigue_sin_contador() {
        // `null` es "nunca abriste el detalle": sellarlo acá le apagaría para siempre el badge a
        // capítulos que sí van a ser novedad más adelante.
        assertNull(ContadorDeNuevos.reSellar(vistos = null, totalAhora = 20))
    }
```

Si `assertNull` no está importado en ese archivo, agregar `import org.junit.Assert.assertNull`.

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.nuevos.ContadorDeNuevosTest"`
Expected: FAIL — `Unresolved reference: reSellar`.

- [ ] **Step 3: Implementar la regla del badge**

En `ContadorDeNuevos.kt`, dentro del `object`:

```kotlin
    /**
     * Qué dejar en `episodiosVistosEnLista` después de guardar capítulos que trajo el usuario (no el
     * portal), como al guardar la temporada entera para reproducir uno.
     *
     * Si el contador ya estaba sellado, se re-sella al total de ahora: el badge es para "salieron
     * capítulos nuevos", no para "acabás de guardar la temporada". Si era `null` (nunca se abrió el
     * detalle) sigue `null`, porque sellarlo acá apagaría el badge de novedades que todavía no
     * ocurrieron.
     */
    fun reSellar(vistos: Int?, totalAhora: Int): Int? = if (vistos == null) null else totalAhora
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.nuevos.ContadorDeNuevosTest"`
Expected: PASS

- [ ] **Step 5: Extraer el guardado del backdrop en el repositorio**

En `ArkivRepository.kt`, el bloque de `addMagisSource` que escribe el backdrop (el `if
(backdropUrl.isNotBlank()) { artworkDao.upsert(...) }`, justo antes del `return ep.id`) pasa a una
función privada, para que `addMagisSeason` no lo duplique. Reemplazar ese bloque por
`guardarBackdropDeMagis(id, backdropUrl)` y agregar, debajo de `addMagisSource`:

```kotlin
    /**
     * La imagen apaisada del portal va al mismo lugar donde el hero del Home busca la de TMDB.
     * Se escribe SOLO si Magis la trajo: una fila con backdrops —aunque tmdbId sea null, como
     * acá— ensureArtwork ya NO la vuelve a tocar (ver LibraryGrouping.shouldRefetchArtwork),
     * así que este backdrop del portal no se pisa con un "[]" cada vez que pasa la ventana de
     * reintento. Sin fila (backdropUrl vacío), TMDB la completa como siempre.
     */
    private suspend fun guardarBackdropDeMagis(itemId: String, backdropUrl: String) {
        if (backdropUrl.isBlank()) return
        artworkDao.upsert(
            com.arkiv.player.data.db.ArtworkEntity(
                itemId = itemId,
                tmdbId = null,
                tmdbType = null,
                backdropsJson = JSONArray(listOf(backdropUrl)).toString(),
                fetchedAt = clock(),
            ),
        )
    }
```

- [ ] **Step 6: Implementar `addMagisSeason`**

En `ArkivRepository.kt`, debajo de `guardarBackdropDeMagis`:

```kotlin
    /**
     * Guarda la temporada COMPLETA de Magis: es lo que corre al tocar un capítulo para verlo, con la
     * lista que la pantalla ya tenía cargada (sin red). Gemelo de [savePackAsSeries] para torrent y
     * de `addWholeWebSeries` para web — Magis era la única fuente que guardaba de a un capítulo.
     *
     * `upsert` y NO `replaceItem`: un capítulo que ya tenías guardado tiene que sobrevivir aunque el
     * portal no lo liste esta vez. Es idempotente (ids derivados del contenido), así que se puede
     * llamar en cada reproducción.
     *
     * Devuelve `número de capítulo → episodeId` para que el llamador sepa cuál reproducir sin
     * re-derivar ids a mano.
     */
    suspend fun addMagisSeason(
        contentId: String,
        title: String,
        capitulos: List<CapituloDeTemporada>,
        seriesRef: String,
        posterUrl: String = "",
        backdropUrl: String = "",
    ): Map<Int, String> {
        if (contentId.isBlank() || capitulos.isEmpty()) return emptyMap()
        val id = MagisEntities.itemIdDe(contentId)
        val existente = itemDao.getItem(id)
        val (item, episodios) = MagisEntities.buildSeason(
            contentId = contentId, title = title, capitulos = capitulos, posterUrl = posterUrl,
            ahora = clock(), seriesRef = seriesRef, existente = existente,
        )
        itemDao.upsertItem(item)
        itemDao.upsertEpisodes(episodios)
        // Las tarjetas-película que dejó el esquema viejo (un ítem por capítulo), ahora que su
        // contenido vive dentro del ítem de la temporada.
        capitulos.forEach { barrerItemLegacyDeCapitulo(contentId, it.number) }
        guardarBackdropDeMagis(id, backdropUrl)
        // El badge es para capítulos que salieron en el portal, no para los que acabás de guardar vos.
        val total = itemDao.getEpisodesOf(id).count { !it.deleted }
        com.arkiv.player.data.nuevos.ContadorDeNuevos.reSellar(existente?.episodiosVistosEnLista, total)
            ?.let { itemDao.marcarEpisodiosVistos(id, it) }
        return episodios.mapNotNull { ep -> ep.episode?.let { it to ep.id } }.toMap()
    }
```

- [ ] **Step 7: Verificar que compila y que no se rompió nada**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — toda la suite. (`addMagisSeason` no tiene test propio: no hay tests con Room en el
proyecto, y por eso toda su lógica decidible vive en `buildSeason` y `reSellar`, que sí los tienen.)

- [ ] **Step 8: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/nuevos/ContadorDeNuevos.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/nuevos/ContadorDeNuevosTest.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(magis): guardar la temporada completa en la biblioteca" -- $RUTAS
```

---

### Task 3: Tocar un capítulo guarda la temporada y reproduce ese capítulo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt` (después de
  `playMagisEpisode`, ~línea 119)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt` (línea ~538 y la firma de
  `TvMagisSeasonContent` en ~1547)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/MagisSeasonDialog.kt` (firma en línea 65 y
  llamada en línea 168)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt` (línea ~508)

**Interfaces:**
- Consumes: `ArkivRepository.addMagisSeason(...)` (Task 2).
- Produces: `SearchPlayback.playMagisSeason(temporada: GatewayResult, capitulos: List<GatewayEpisode>, elegido: GatewayEpisode): PlaybackResult`

- [ ] **Step 1: Implementar `playMagisSeason`**

En `SearchPlayback.kt`, debajo de `playMagisEpisode`:

```kotlin
    /**
     * Guarda la temporada ENTERA y devuelve el capítulo que se tocó, para reproducirlo.
     *
     * Es el gemelo de `playPackRow` (torrent) y `saveWebPack` (web): tocar un capítulo trae la serie
     * completa a la biblioteca, no solo ese capítulo. La lista ya está cargada en la pantalla, así
     * que esto no cuesta ninguna llamada de red. **No descarga nada**: eso lo sigue haciendo el
     * botón "Guardar".
     *
     * Si la temporada no se pudo guardar (el portal no mandó `content_id`), cae al camino de
     * siempre —guardar solo el capítulo— antes que dejar al usuario sin reproducir nada.
     */
    suspend fun playMagisSeason(
        temporada: com.arkiv.player.data.gateway.GatewayResult,
        capitulos: List<com.arkiv.player.data.gateway.GatewayEpisode>,
        elegido: com.arkiv.player.data.gateway.GatewayEpisode,
    ): PlaybackResult {
        val guardados = graph.repository.addMagisSeason(
            contentId = temporada.extra["content_id"].orEmpty(),
            title = temporada.title,
            capitulos = capitulos.map {
                com.arkiv.player.data.CapituloDeTemporada(it.number, it.title, it.ref)
            },
            seriesRef = temporada.ref,
            posterUrl = temporada.extra["poster"].orEmpty(),
            backdropUrl = temporada.extra["backdrop"].orEmpty(),
        )
        val epId = guardados[elegido.number] ?: return playMagisEpisode(temporada, elegido)
        return PlaybackResult.Ready(epId)
    }
```

- [ ] **Step 2: Cablear el TV**

En `TvSearchScreen.kt`, la firma de `TvMagisSeasonContent` (línea ~1547) pasa a entregar también la
lista:

```kotlin
    onPlayOne: (List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewayEpisode) -> Unit,
```

y la fila de capítulo (línea ~1642) pasa a:

```kotlin
                    items(caps, key = { it.ref }) { cap ->
                        TvMagisEpisodeRow(cap = cap, enabled = !preparing, onClick = { onPlayOne(caps, cap) })
                    }
```

En el llamador (línea ~538), reemplazar el `onPlayOne` actual por:

```kotlin
                        onPlayOne = { capitulos, capitulo ->
                            magisSeasonFor = null
                            preparing = true; playError = null
                            scope.launch {
                                applyResult(playback.playMagisSeason(currentMagis, capitulos, capitulo))
                            }
                        },
```

- [ ] **Step 3: Cablear el celu**

En `MagisSeasonDialog.kt`, la firma (línea 65):

```kotlin
    onPlay: (List<GatewayEpisode>, GatewayEpisode) -> Unit,
```

y la llamada de la fila (línea 168), donde `capitulos!!` es la lista que ya se está pintando:

```kotlin
                            onPlay = { onPlay(capitulos!!, cap) },
```

En `SearchScreen.kt` (línea ~508):

```kotlin
            onPlay = { capitulos, capitulo ->
                magisSeason = null
                preparing = true; playError = null
                scope.launch { applyResult(playback.playMagisSeason(temporada, capitulos, capitulo)) }
            },
```

- [ ] **Step 4: Verificar que compila y que la suite sigue verde**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/MagisSeasonDialog.kt app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(magis): tocar un capitulo guarda la temporada entera" -- $RUTAS
```

---

### Task 4: Por dónde voy — el capítulo en curso deja de ser el primero

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`ItemDetail`, líneas 44-54;
  y una función nueva junto a `savePlayback`, ~línea 937)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`load`, línea 125)
- Test: `app/src/test/java/com/arkiv/player/data/ItemDetailResumeTest.kt` (crear)

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces:
  - `ItemDetail.inProgressEpisode` pasa a ser "el último capítulo **tocado** y sin terminar".
  - `ItemDetail.resumeEpisode` cae al primero **sin ver** en vez de al primero a secas.
  - `ArkivRepository.marcarEnCurso(episodeId: String)`.

- [ ] **Step 1: Escribir los tests que fallan**

Crear `app/src/test/java/com/arkiv/player/data/ItemDetailResumeTest.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Por dónde vas en una serie.
 *
 * Con un solo capítulo guardado esto no se notaba. Ahora que reproducir uno trae la temporada
 * completa (ver `addMagisSeason`), "Reproducir" y el texto del detalle apuntan a un capítulo entre
 * veinte, y las dos formas viejas de equivocarse se ven enseguida: caer al E1 apenas terminás el E5,
 * y caer al E1 cuando le diste play al E5 hace tres segundos y todavía no hay progreso guardado.
 */
class ItemDetailResumeTest {

    private fun ep(n: Int) = Episode(
        id = "magis:ABC::e$n", itemId = "magis:ABC", section = "", displayName = "E$n",
        orderIndex = n, durationSeconds = 0.0, thumbPath = null, original = null, derivative = null,
        season = null, episode = n,
    )

    private fun detalle(vararg progreso: Pair<Int, PlaybackEntity>) = ItemDetail(
        identifier = "magis:ABC",
        title = "Dragon Ball Daima T1",
        description = null,
        thumbnailUrl = "",
        episodes = (1..5).map { ep(it) },
        progress = progreso.associate { (n, p) -> ep(n).id to p },
    )

    private fun visto(cuando: Long) =
        PlaybackEntity("", positionMs = 600_000L, durationMs = 600_000L, watched = true, lastPlayedAt = cuando)

    private fun aMedias(cuando: Long) =
        PlaybackEntity("", positionMs = 120_000L, durationMs = 600_000L, watched = false, lastPlayedAt = cuando)

    /** Recién le diste play: hay fila, pero todavía sin posición ni duración. */
    private fun reciénTocado(cuando: Long) =
        PlaybackEntity("", positionMs = 0L, durationMs = 0L, watched = false, lastPlayedAt = cuando)

    @Test fun sin_nada_empezado_vas_en_el_primero() {
        assertEquals("magis:ABC::e1", detalle().resumeEpisode?.id)
    }

    @Test fun un_capitulo_a_medias_es_donde_vas() {
        val d = detalle(1 to visto(10L), 2 to aMedias(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
        assertEquals("magis:ABC::e2", d.resumeEpisode?.id)
    }

    @Test fun un_capitulo_recien_tocado_ya_es_donde_vas() {
        // `saveProgress` no escribe nada hasta saber la duración, y en Magis la sonda tarda: sin
        // esto, salir a los tres segundos del E3 dejaba el detalle diciendo "vas en el E1".
        val d = detalle(3 to reciénTocado(30L))
        assertEquals("magis:ABC::e3", d.inProgressEpisode?.id)
    }

    @Test fun si_terminaste_el_tercero_vas_en_el_cuarto() {
        // Antes caía al E1 apenas el capítulo pasaba a `watched`.
        val d = detalle(1 to visto(10L), 2 to visto(20L), 3 to visto(30L))
        assertEquals(null, d.inProgressEpisode)
        assertEquals("magis:ABC::e4", d.resumeEpisode?.id)
    }

    @Test fun con_todo_visto_vuelve_al_primero() {
        val d = detalle(1 to visto(10L), 2 to visto(20L), 3 to visto(30L), 4 to visto(40L), 5 to visto(50L))
        assertEquals("magis:ABC::e1", d.resumeEpisode?.id)
    }

    @Test fun gana_el_ultimo_tocado_no_el_de_numero_mas_alto() {
        val d = detalle(2 to aMedias(90L), 5 to aMedias(20L))
        assertEquals("magis:ABC::e2", d.inProgressEpisode?.id)
    }
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.ItemDetailResumeTest"`
Expected: FAIL — `un_capitulo_recien_tocado_ya_es_donde_vas` (hoy exige `positionMs > 0`) y
`si_terminaste_el_tercero_vas_en_el_cuarto` (hoy cae a `episodes.firstOrNull()`).

- [ ] **Step 3: Implementar la regla en `ItemDetail`**

En `ArkivRepository.kt`, reemplazar las dos propiedades de `ItemDetail` (líneas 44-54) por:

```kotlin
    /**
     * Último episodio **tocado** y sin terminar (el "capítulo en el que voy"), o null si no hay.
     *
     * Alcanza con que exista la fila de `playback`: NO se exige `positionMs > 0` porque
     * `PlayerViewModel.saveProgress` no escribe nada hasta conocer la duración, y en Magis la sonda
     * de duración puede tardar (stream TS). Sin esto, darle play al E5 y salir a los tres segundos
     * dejaba el detalle diciendo "vas en el E1". La fila "Continuar viendo" del home sí filtra por
     * posición (`observeContinueWatching`), que es lo que evita que se llene de ruido.
     */
    val inProgressEpisode: Episode?
        get() = episodes
            .mapNotNull { ep -> progress[ep.id]?.let { ep to it } }
            .filter { !it.second.watched }
            .maxByOrNull { it.second.lastPlayedAt }
            ?.first

    /**
     * Episodio para el botón "Reproducir": el que estás viendo, o el primero que te falta.
     *
     * El fallback es el primero **sin ver** y no el primero a secas: con la serie entera en la
     * biblioteca, terminar el E5 tiene que dejarte en el E6, no devolverte al E1. Si están todos
     * vistos, el primero (volver a empezar).
     */
    val resumeEpisode: Episode?
        get() = inProgressEpisode
            ?: episodes.firstOrNull { progress[it.id]?.watched != true }
            ?: episodes.firstOrNull()
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.ItemDetailResumeTest"`
Expected: PASS (los 6)

- [ ] **Step 5: Sellar el capítulo al arrancar la reproducción**

En `ArkivRepository.kt`, junto a `savePlayback` (~línea 937):

```kotlin
    /**
     * Sella "voy por acá" apenas arranca la reproducción, sin esperar a que se sepa la duración.
     *
     * [savePlayback] solo escribe cuando el player ya conoce `durationMs`, y en Magis eso puede
     * tardar (stream TS, sonda de hasta 20 s): hasta entonces el capítulo que estás viendo no
     * existía para el detalle. Preserva posición, duración y `watched` de lo que ya hubiera: esto
     * marca dónde estás, no reinicia el progreso ni desmarca un capítulo ya visto.
     */
    suspend fun marcarEnCurso(episodeId: String) {
        val existente = playbackDao.get(episodeId)
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = existente?.positionMs ?: 0L,
                durationMs = existente?.durationMs ?: 0L,
                watched = existente?.watched ?: false,
                lastPlayedAt = clock(),
            ),
        )
    }
```

En `PlayerViewModel.kt`, dentro de `load(episodeId)`, como primera línea del `viewModelScope.launch`
(antes de `_error.value = null`):

```kotlin
            // Antes que nada: que el detalle sepa por qué capítulo vas aunque salgas enseguida.
            runCatching { repo.marcarEnCurso(episodeId) }
```

- [ ] **Step 6: Verificar que compila y que la suite sigue verde**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/test/java/com/arkiv/player/data/ItemDetailResumeTest.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(detalle): el capitulo en curso es el que tocaste, y al terminarlo sigue el siguiente" -- $RUTAS
```

---

### Task 5: "Vas en E5 · 20 episodios" en el detalle (y el bug de numeración de Magis)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloTest.kt` (crear)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt` (líneas 194-203, 233 y
  `episodeMeta` en 333-341)
- Modify: `app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt` (líneas 406-419)

**Interfaces:**
- Consumes: `ItemDetail.inProgressEpisode` / `resumeEpisode` (Task 4).
- Produces:
  - `EtiquetaDeCapitulo.numero(ep: Episode): String`
  - `EtiquetaDeCapitulo.avance(detail: ItemDetail, unidad: String): String`
  - `EtiquetaDeCapitulo.botonReproducir(detail: ItemDetail): String`

- [ ] **Step 1: Escribir los tests que fallan**

Crear `app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloTest.kt`:

```kotlin
package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cómo se nombra un capítulo en el detalle.
 *
 * El caso que originó esto: los capítulos de Magis guardan `season = null` y `episode = N`, y la
 * versión vieja (privada en `TvDetailScreen`) exigía season Y episode a la vez, así que caía a la
 * rama de `orderIndex` y mostraba el capítulo 5 como "E6".
 */
class EtiquetaDeCapituloTest {

    private fun ep(
        orderIndex: Int = 0,
        season: Int? = null,
        episode: Int? = null,
        id: String = "item::x",
    ) = Episode(
        id = id, itemId = "item", section = "", displayName = "", orderIndex = orderIndex,
        durationSeconds = 0.0, thumbPath = null, original = null, derivative = null,
        season = season, episode = episode,
    )

    @Test fun magis_numera_por_episodio_aunque_no_tenga_temporada() {
        assertEquals("E5", EtiquetaDeCapitulo.numero(ep(orderIndex = 5, episode = 5)))
    }

    @Test fun con_temporada_y_episodio_se_muestran_los_dos() {
        assertEquals("T2 · E5", EtiquetaDeCapitulo.numero(ep(season = 2, episode = 5)))
    }

    @Test fun un_pack_de_torrent_numera_desde_el_orderIndex() {
        // Los packs codifican temporada*1000 + episodio.
        assertEquals("T1 · E3", EtiquetaDeCapitulo.numero(ep(orderIndex = 1003)))
    }

    @Test fun sin_nada_el_orden_es_1_based() {
        // archive.org: correlativo 0..N-1.
        assertEquals("E1", EtiquetaDeCapitulo.numero(ep(orderIndex = 0)))
    }

    private fun detalle(progreso: Map<String, PlaybackEntity> = emptyMap()) = ItemDetail(
        identifier = "magis:ABC", title = "Daima", description = null, thumbnailUrl = "",
        episodes = (1..20).map { ep(orderIndex = it, episode = it, id = "magis:ABC::e$it") },
        progress = progreso,
    )

    @Test fun sin_haber_empezado_solo_dice_cuantos_hay() {
        assertEquals("20 episodios", EtiquetaDeCapitulo.avance(detalle(), "episodios"))
        assertEquals("Reproducir", EtiquetaDeCapitulo.botonReproducir(detalle()))
    }

    @Test fun empezada_dice_por_donde_vas() {
        val progreso = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 120_000L, 600_000L, false, 50L),
        )
        assertEquals("Vas en E5  ·  20 episodios", EtiquetaDeCapitulo.avance(detalle(progreso), "episodios"))
        assertEquals("Reproducir E5", EtiquetaDeCapitulo.botonReproducir(detalle(progreso)))
    }

    @Test fun terminado_un_capitulo_apunta_al_siguiente() {
        val progreso = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 600_000L, 600_000L, true, 50L),
        )
        assertEquals("Vas en E6  ·  20 episodios", EtiquetaDeCapitulo.avance(detalle(progreso), "episodios"))
        assertEquals("Reproducir E6", EtiquetaDeCapitulo.botonReproducir(detalle(progreso)))
    }

    @Test fun una_pelicula_no_dice_por_donde_vas() {
        val peli = ItemDetail(
            identifier = "magis:X", title = "Duro de matar", description = null, thumbnailUrl = "",
            episodes = listOf(ep(id = "magis:X::0")),
            progress = mapOf("magis:X::0" to PlaybackEntity("magis:X::0", 120_000L, 600_000L, false, 50L)),
        )
        assertEquals("Reproducir", EtiquetaDeCapitulo.botonReproducir(peli))
    }
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.ui.EtiquetaDeCapituloTest"`
Expected: FAIL — `Unresolved reference: EtiquetaDeCapitulo`.

- [ ] **Step 3: Implementar el helper**

Crear `app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt`:

```kotlin
package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.model.Episode

/**
 * Cómo se nombra un capítulo y por dónde vas, en el detalle del TV y en el del celu.
 *
 * Vive acá, compartido, porque las dos pantallas TIENEN que decir lo mismo: antes esto era una
 * función privada del detalle del TV y el del celu no numeraba nada.
 */
object EtiquetaDeCapitulo {

    /**
     * "T1 · E5" / "E5".
     *
     * Se omite el tramo que no se sepa en vez de inventarlo: es preferible "E5" solo antes que un
     * "T1 · E5" que apunte al capítulo equivocado. El orden de preferencia importa: `episode` manda
     * aunque no haya `season` —los capítulos de Magis guardan `season = null`— y recién después se
     * cae al `orderIndex`, que en packs de torrent codifica temporada*1000 + episodio y en
     * archive.org es un correlativo 0..N-1.
     */
    fun numero(ep: Episode): String {
        val temporada = ep.season
        val capitulo = ep.episode
        return when {
            temporada != null && capitulo != null -> "T$temporada · E$capitulo"
            capitulo != null -> "E$capitulo"
            ep.orderIndex >= 1000 -> "T${ep.orderIndex / 1000} · E${ep.orderIndex % 1000}"
            else -> "E${ep.orderIndex + 1}"
        }
    }

    /**
     * "Vas en E5  ·  20 episodios", o "20 episodios" si todavía no empezaste.
     *
     * [unidad] es "episodios" (TV) o "videos" (celu), que es como los llama hoy cada pantalla.
     */
    fun avance(detail: ItemDetail, unidad: String): String {
        val total = "${detail.episodes.size} $unidad"
        if (detail.progress.isEmpty() || detail.episodes.size <= 1) return total
        val donde = detail.resumeEpisode ?: return total
        return "Vas en ${numero(donde)}  ·  $total"
    }

    /**
     * "Reproducir" o "Reproducir E5".
     *
     * Nombra el capítulo que el botón va a reproducir de verdad ([ItemDetail.resumeEpisode]), que no
     * siempre es el que estás viendo: si terminaste el E5, reproduce el E6. En una película (un solo
     * episodio) no hay nada que numerar.
     */
    fun botonReproducir(detail: ItemDetail): String {
        if (detail.episodes.size <= 1) return "Reproducir"
        val donde = detail.resumeEpisode ?: return "Reproducir"
        return "Reproducir ${numero(donde)}"
    }
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.ui.EtiquetaDeCapituloTest"`
Expected: PASS (los 9)

- [ ] **Step 5: Usarlo en el detalle del TV**

En `TvDetailScreen.kt`:

1. Reemplazar el cuerpo de `episodeMeta` (líneas 333-341) por:

```kotlin
private fun episodeMeta(ep: Episode): String {
    val minutos = (ep.durationSeconds / 60).toInt()
    val numero = com.arkiv.player.ui.EtiquetaDeCapitulo.numero(ep)
    return if (minutos > 0) "$numero · $minutos min" else numero
}
```

y ajustar su KDoc: la numeración ahora vive en `EtiquetaDeCapitulo`; acá solo se le suman los
minutos.

2. La línea de datos (líneas 194-203) pasa a:

```kotlin
                Text(
                    when {
                        focused != null -> episodeMeta(focused)
                        data.episodes.size > 1 -> com.arkiv.player.ui.EtiquetaDeCapitulo.avance(data, "episodios")
                        else -> "Película"
                    },
```

3. El texto del botón (línea 233):

```kotlin
                        Text("▶  ${com.arkiv.player.ui.EtiquetaDeCapitulo.botonReproducir(data)}")
```

- [ ] **Step 6: Usarlo en el detalle del celu**

En `DetailScreen.kt`, líneas 406-419:

```kotlin
                Text(
                    EtiquetaDeCapitulo.avance(data, "videos"),
                    color = ArkivTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (resume != null) {
                    Button(
                        onClick = { onPlayEpisode(resume.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  ${EtiquetaDeCapitulo.botonReproducir(data)}", fontWeight = FontWeight.Bold)
                    }
                }
```

Agregar `import com.arkiv.player.ui.EtiquetaDeCapitulo` si el archivo no está en ese paquete.

- [ ] **Step 7: Verificar que compila y que toda la suite pasa**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/ui/EtiquetaDeCapitulo.kt app/src/test/java/com/arkiv/player/ui/EtiquetaDeCapituloTest.kt app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(detalle): decir por que capitulo vas y arreglar la numeracion de magis" -- $RUTAS
```

---

## Verificación en device (después de la Task 5)

Ningún test cubre esto: la lista de capítulos viene del portal y los refs caducan. Instalar el APK
(ver `docs/` para ADB al celu / Fire Stick — confirmar antes con Cristian que no esté usando el
celular si se automatiza con taps) y:

1. Buscar una serie de Magis (Dragon Ball Daima), abrir la temporada, tocar un capítulo del medio.
2. Que reproduzca **ese** capítulo, no el primero.
3. Volver a la biblioteca: la tarjeta tiene que mostrar la temporada completa, no "1 episodio", y
   **no** haber saltado al principio del home.
4. Abrir el detalle: `"Vas en E5 · 20 episodios"`, botón `"▶ Reproducir E5"`, carrusel posicionado en
   el 5, sin badge de novedades.
5. Salir del capítulo a los pocos segundos (antes de que resuelva la duración) y volver al detalle:
   tiene que seguir diciendo "Vas en E5".
6. Terminar el E5 y volver: "Vas en E6".
7. Volver a entrar a la misma temporada desde el buscador y tocar otro capítulo: una sola tarjeta,
   los mismos N episodios, sin duplicados.
8. Comprobar que el botón "Guardar N" sigue encolando descargas como antes.
