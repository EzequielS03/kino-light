# Alinear numeración de episodio para anime (WEB) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Para anime, hacer que `playWebEp`/`downloadEpisode` (`AnimeShowDetailScreen`) y la rama
anime de `SearchPlayback.playWeb` guarden el número de episodio REAL del mirror (cuando se conoce
por `pageUrl`) en vez del número de AniList que el usuario tocó, para que coincida con lo que ya
guardan `addWebPack`/`downloadPack`/`addWholeWebSeries`/`downloadWholeSeries` para esa misma fila.

**Architecture:** Nuevo objeto puro `WebSourceEpisode` (hermano de `WebSourceSeason`, mismo
paquete `com.arkiv.player.data.catalog.mirror`) que busca en los packs del mirror ya cargados en
pantalla el `MirrorWebSource` cuya `pageUrl` coincide y devuelve su `episode` real, o un `fallback`
explícito (a diferencia de `WebSourceSeason`, sin default) cuando ningún pack la conoce. Se usa en
los 3 call sites de "episodio suelto"; los caminos de pack no cambian.

**Tech Stack:** Kotlin, JUnit4 (tests unitarios JVM, sin Android), Jetpack Compose (call sites, sin
test propio — se verifican con `compileDebugKotlin`).

## Global Constraints

- No tocar el esquema de `EpisodeEntity` ni de `nuc_library_items` — ningún cambio de columnas ni
  migraciones (spec, sección "Sin tocar").
- `addWebPack`, `downloadPack`, `addWholeWebSeries`, `downloadWholeSeries` no se modifican: ya usan
  `MirrorWebSource.episode` real (spec, sección "Sin tocar").
- La rama TMDB de `SearchPlayback.playWeb` (`season != null && episode != null`) no cambia.
- `WebSourceEpisode.forPageUrl` no lleva `fallback` por defecto: cada llamador debe pasar
  explícitamente el episodio de AniList como fallback (spec, sección "Dirección elegida").

---

### Task 1: `WebSourceEpisode` — helper puro + tests

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisode.kt`
- Create: `app/src/test/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisodeTest.kt`

**Interfaces:**
- Consumes: `MirrorWebPack`, `MirrorWebSource` (ya existen en
  `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt`, sin cambios).
- Produces: `WebSourceEpisode.forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int): Int`
  — usado por Task 2 y Task 3.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisodeTest.kt`:

```kotlin
package com.arkiv.player.data.catalog.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceEpisodeTest {

    private fun src(site: String, season: Int, episode: Int, url: String) =
        MirrorWebSource(site, url, season, episode, "Ep $episode", "1080p", "lat")

    private val packs = listOf(
        MirrorWebPack(
            "sitioA", "Shingeki no Kyojin",
            listOf(
                src("sitioA", 1, 5, "http://a/s1e5"),
                src("sitioA", 2, 5, "http://a/s2e5"),
            ),
        ),
        MirrorWebPack("sitioB", "Shingeki no Kyojin", listOf(src("sitioB", 3, 12, "http://b/s3e12"))),
    )

    @Test fun `usa el episodio real del mirror para esa pageUrl`() {
        assertEquals(5, WebSourceEpisode.forPageUrl(packs, "http://a/s2e5", fallback = 99))
    }

    @Test fun `busca en todos los packs, no solo en el primero`() {
        assertEquals(12, WebSourceEpisode.forPageUrl(packs, "http://b/s3e12", fallback = 99))
    }

    @Test fun `cae al fallback dado cuando ningun pack conoce la url (scraping en vivo)`() {
        assertEquals(7, WebSourceEpisode.forPageUrl(packs, "http://otro/sitio/ep7", fallback = 7))
    }

    @Test fun `cae al fallback dado sin packs cargados`() {
        assertEquals(7, WebSourceEpisode.forPageUrl(emptyList(), "http://a/s2e5", fallback = 7))
    }

    @Test fun `resuelve numeracion absoluta del mirror distinta a la de AniList`() {
        // El usuario tocó "Ep 5" en la grilla de AniList (temporada actual), pero el sitio numera
        // absoluto para series de larga duración: debe ganar el 1071 del mirror, no el 5 que llega
        // como fallback.
        val onePiece = listOf(
            MirrorWebPack("sitioC", "One Piece", listOf(src("sitioC", 1, 1071, "http://c/abs1071"))),
        )
        assertEquals(1071, WebSourceEpisode.forPageUrl(onePiece, "http://c/abs1071", fallback = 5))
    }
}
```

- [ ] **Step 2: Confirmar que falla (no compila, `WebSourceEpisode` no existe)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.WebSourceEpisodeTest"`
Expected: FAIL — error de compilación, `unresolved reference: WebSourceEpisode`.

- [ ] **Step 3: Implementación mínima**

Crear `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisode.kt`:

```kotlin
package com.arkiv.player.data.catalog.mirror

/**
 * Episodio canónico de una fuente web SUELTA (un `WebResult`, que no lo trae: viene ya resuelto a
 * nivel de episodio por `episodeSourcesWeb`), hermano de [WebSourceSeason] -- mismo problema
 * (fila local indexada por hash de `pageUrl`, last-write-wins), aplicado a `episode` en vez de
 * `season`.
 *
 * Para anime, los caminos de "episodio suelto" (`playWebEp`/`downloadEpisode`/`SearchPlayback
 * .playWeb`) guardaban el episodio de AniList que el usuario tocó, mientras que los caminos de
 * pack guardan `MirrorWebSource.episode`, que para series de larga duración puede ser absoluto y
 * no coincidir. A diferencia de [WebSourceSeason] (fallback fijo en 1, la convención histórica de
 * season), acá no hay una convención universal para "episodio desconocido": cada llamador decide
 * su propio [fallback] -- normalmente el episodio que el usuario tocó.
 */
object WebSourceEpisode {

    /**
     * Episodio real de [pageUrl] según los packs del mirror ya cargados; [fallback] si ningún pack
     * la conoce -- caso de las fuentes scrapeadas en vivo, que no vienen del mirror y por lo tanto
     * tampoco tienen fila de pack que las contradiga.
     */
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.episode ?: fallback
}
```

- [ ] **Step 4: Confirmar que pasa**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.WebSourceEpisodeTest"`
Expected: PASS — 5 tests verdes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisode.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/WebSourceEpisodeTest.kt
git commit -m "feat(anime): agregar WebSourceEpisode, hermano de WebSourceSeason"
```

---

### Task 2: `AnimeShowDetailScreen` — `playWebEp` y `downloadEpisode` usan el episodio real

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt:311-322` (`playWebEp`)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt:385-396` (`downloadEpisode`)

**Interfaces:**
- Consumes: `WebSourceEpisode.forPageUrl(packs, pageUrl, fallback)` de Task 1.
- Produces: nada nuevo (mismo comportamiento externo de `playWebEp`/`downloadEpisode`, solo cambia
  el `episode` guardado).

No hay test unitario para este paso: `AnimeShowDetailScreen` es una función `@Composable` sin
tests propios en el repo (ni los tenía antes). Se verifica con `compileDebugKotlin` (Task 4) y
lectura de código.

- [ ] **Step 1: Editar `playWebEp`**

Reemplazar (texto exacto actual, líneas 311-322):

```kotlin
    fun playWebEp(r: WebResult, ep: Int) {
        val s = show ?: return
        preparing = true; error = null
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(webPacks, r.pageUrl)
        scope.launch {
            val epId = graph.repository.addWebSeriesEpisode(
                "anilist$anilistId", s.title, s.posterUrl, season, ep, "${s.title} - Ep $ep", r.pageUrl,
            )
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo abrir la fuente web"
        }
    }
```

por:

```kotlin
    fun playWebEp(r: WebResult, ep: Int) {
        val s = show ?: return
        preparing = true; error = null
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(webPacks, r.pageUrl)
        val episode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forPageUrl(webPacks, r.pageUrl, fallback = ep)
        scope.launch {
            val epId = graph.repository.addWebSeriesEpisode(
                "anilist$anilistId", s.title, s.posterUrl, season, episode, "${s.title} - Ep $episode", r.pageUrl,
            )
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo abrir la fuente web"
        }
    }
```

- [ ] **Step 2: Editar `downloadEpisode`**

Reemplazar (texto exacto actual, líneas 385-396):

```kotlin
    fun downloadEpisode(r: WebResult, ep: Int) {
        val s = show ?: return
        askNotifications()
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(webPacks, r.pageUrl)
        scope.launch {
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = "anilist$anilistId", showTitle = s.title, posterUrl = s.posterUrl,
                items = listOf(com.arkiv.player.data.offline.NucDownloadItem(season, ep, r.pageUrl)),
            )
        }
    }
```

por:

```kotlin
    fun downloadEpisode(r: WebResult, ep: Int) {
        val s = show ?: return
        askNotifications()
        val season = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(webPacks, r.pageUrl)
        val episode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forPageUrl(webPacks, r.pageUrl, fallback = ep)
        scope.launch {
            error = com.arkiv.player.data.offline.NucDownloads.start(
                context, graph.arkivOfflineApi, graph.database.localActiveJobDao(),
                seriesId = "anilist$anilistId", showTitle = s.title, posterUrl = s.posterUrl,
                items = listOf(com.arkiv.player.data.offline.NucDownloadItem(season, episode, r.pageUrl)),
            )
        }
    }
```

- [ ] **Step 3: Compilar para detectar errores de sintaxis temprano**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt
git commit -m "fix(anime): playWebEp/downloadEpisode usan el episodio real del mirror"
```

---

### Task 3: `SearchPlayback.playWeb` — nuevo parámetro `animeEpisode`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt:169-190`

**Interfaces:**
- Consumes: nada nuevo (usa el `episode: Int?` ya existente como valor a mostrar cuando
  `animeEpisode` no se resuelve; el propio parámetro lo calculan los llamadores en Task 4).
- Produces: `playWeb(..., animeSeason: Int = 1, animeEpisode: Int = 1): PlaybackResult` — firma que
  consume Task 4.

No hay test unitario propio (no lo tenía antes); se verifica con `compileDebugKotlin` (falla si
algún llamador no compila con la nueva firma) más Task 4, que agrega el argumento en los 2
llamadores existentes.

- [ ] **Step 1: Editar `playWeb`**

Reemplazar (texto exacto actual, líneas 169-190):

```kotlin
    suspend fun playWeb(
        result: WebResult,
        card: TitleCard,
        detail: TmdbDetail?,
        animeShow: AnimeShow?,
        resultTitle: String,
        resultPoster: String,
        season: Int?,
        episode: Int?,
        animeSeason: Int = 1,
    ): PlaybackResult {
        val epId = if (card.kind == "anime" && episode != null) {
            val anilistId = card.anilistId ?: animeShow?.id
            graph.repository.addWebSeriesEpisode("anilist$anilistId", resultTitle, resultPoster, animeSeason, episode, "$resultTitle - Ep $episode", result.pageUrl)
        } else if (season != null && episode != null) {
            val epName = episodeNameFor(detail, season, episode)
            graph.repository.addWebSeriesEpisode(seriesIdFor(card, detail), resultTitle, resultPoster, season, episode, epName, result.pageUrl)
        } else {
            graph.repository.addWebSource(result.pageUrl, result.title.ifBlank { resultTitle }, resultPoster)
        }
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo abrir la fuente web")
    }
```

por:

```kotlin
    suspend fun playWeb(
        result: WebResult,
        card: TitleCard,
        detail: TmdbDetail?,
        animeShow: AnimeShow?,
        resultTitle: String,
        resultPoster: String,
        season: Int?,
        episode: Int?,
        animeSeason: Int = 1,
        animeEpisode: Int = 1,
    ): PlaybackResult {
        val epId = if (card.kind == "anime" && episode != null) {
            val anilistId = card.anilistId ?: animeShow?.id
            graph.repository.addWebSeriesEpisode("anilist$anilistId", resultTitle, resultPoster, animeSeason, animeEpisode, "$resultTitle - Ep $animeEpisode", result.pageUrl)
        } else if (season != null && episode != null) {
            val epName = episodeNameFor(detail, season, episode)
            graph.repository.addWebSeriesEpisode(seriesIdFor(card, detail), resultTitle, resultPoster, season, episode, epName, result.pageUrl)
        } else {
            graph.repository.addWebSource(result.pageUrl, result.title.ifBlank { resultTitle }, resultPoster)
        }
        return if (epId != null) PlaybackResult.Ready(epId) else PlaybackResult.Failed("No se pudo abrir la fuente web")
    }
```

También actualizar el comentario doc de `[animeSeason]` (líneas 162-167, inmediatamente arriba de
la firma) agregando la explicación equivalente para `animeEpisode`. Reemplazar:

```kotlin
     * [animeSeason]: un [WebResult] suelto no trae temporada, pero la fila local se guarda por hash
     * de `pageUrl` -- la misma fila que escribe [addWholeWebSeries] con la temporada real del
     * mirror. El llamador la resuelve por `pageUrl` contra los packs ya listados
     * (`WebSourceSeason.forPageUrl`) para no revertirle la temporada a esa fila y romper la
     * búsqueda de `PlaybackPreferenceStore.decide()`; queda en 1 (convención histórica) solo cuando
     * ningún pack conoce esa URL, o sea cuando tampoco hay nadie que la contradiga.
     */
```

por:

```kotlin
     * [animeSeason]: un [WebResult] suelto no trae temporada, pero la fila local se guarda por hash
     * de `pageUrl` -- la misma fila que escribe [addWholeWebSeries] con la temporada real del
     * mirror. El llamador la resuelve por `pageUrl` contra los packs ya listados
     * (`WebSourceSeason.forPageUrl`) para no revertirle la temporada a esa fila y romper la
     * búsqueda de `PlaybackPreferenceStore.decide()`; queda en 1 (convención histórica) solo cuando
     * ningún pack conoce esa URL, o sea cuando tampoco hay nadie que la contradiga.
     *
     * [animeEpisode]: mismo problema pero de `episode` -- el mirror puede numerar absoluto y
     * distinto al episodio de AniList que el usuario tocó. El llamador la resuelve por `pageUrl`
     * (`WebSourceEpisode.forPageUrl`), con el episodio de AniList como fallback cuando ningún pack
     * la conoce.
     */
```

- [ ] **Step 2: Compilar (fallará hasta Task 4 por los llamadores existentes seguir sin el nuevo argumento — el parámetro tiene default, así que en realidad compila igual)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (el nuevo parámetro tiene default `= 1`, los llamadores existentes
siguen compilando sin cambios hasta que Task 4 los actualice).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt
git commit -m "feat(anime): SearchPlayback.playWeb acepta animeEpisode"
```

---

### Task 4: `SearchScreen` y `TvSearchScreen` — calcular y pasar `animeEpisode`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt:196-209` (`playWebResult`)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt:172-188` (`playWebResult`)

**Interfaces:**
- Consumes: `WebSourceEpisode.forPageUrl` (Task 1), `SearchPlayback.playWeb(..., animeEpisode)`
  (Task 3).
- Produces: nada nuevo (call sites finales).

No hay test unitario propio. Se verifica con `compileDebugKotlin` + `testDebugUnitTest` (suite
completa) en Task 5.

- [ ] **Step 1: Editar `SearchScreen.playWebResult`**

Reemplazar (texto exacto actual, líneas 196-209):

```kotlin
    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        val animeSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(
            sources.filterIsInstance<PlaySource.WebPack>().map { it.pack }, r.pageUrl,
        )
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, detail, animeShow, resultTitle, resultPoster, season, episode, animeSeason),
            )
        }
    }
```

por:

```kotlin
    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        val packs = sources.filterIsInstance<PlaySource.WebPack>().map { it.pack }
        val animeSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(packs, r.pageUrl)
        val animeEpisode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forPageUrl(
            packs, r.pageUrl, fallback = episode ?: 1,
        )
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, detail, animeShow, resultTitle, resultPoster, season, episode, animeSeason, animeEpisode),
            )
        }
    }
```

- [ ] **Step 2: Editar `TvSearchScreen.playWebResult`**

Reemplazar (texto exacto actual, líneas 172-188):

```kotlin
    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        // Misma resolución de temporada por pageUrl que SearchScreen.playWebResult: sin esto, tocar
        // play sobre un capítulo ya guardado desde un pack le revierte la temporada a 1 en la fila
        // local y PlaybackPreferenceStore.decide() deja de encontrar el capítulo bajado a la NUC.
        val animeSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(
            sources.filterIsInstance<PlaySource.WebPack>().map { it.pack }, r.pageUrl,
        )
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, vmDetail, vmAnimeShow, resultTitle, resultPoster, season, episode, animeSeason),
            )
        }
    }
```

por:

```kotlin
    fun playWebResult(r: WebResult) {
        val card = selected ?: return
        val season = refineSeason
        val episode = refineEpisode
        // Misma resolución de temporada/episodio por pageUrl que SearchScreen.playWebResult: sin
        // esto, tocar play sobre un capítulo ya guardado desde un pack le revierte la temporada o
        // el episodio a los de AniList en la fila local y PlaybackPreferenceStore.decide() deja de
        // encontrar el capítulo bajado a la NUC.
        val packs = sources.filterIsInstance<PlaySource.WebPack>().map { it.pack }
        val animeSeason = com.arkiv.player.data.catalog.mirror.WebSourceSeason.forPageUrl(packs, r.pageUrl)
        val animeEpisode = com.arkiv.player.data.catalog.mirror.WebSourceEpisode.forPageUrl(
            packs, r.pageUrl, fallback = episode ?: 1,
        )
        preparing = true; playError = null
        scope.launch {
            applyResult(
                playback.playWeb(r, card, vmDetail, vmAnimeShow, resultTitle, resultPoster, season, episode, animeSeason, animeEpisode),
            )
        }
    }
```

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt
git commit -m "fix(anime): SearchScreen/TvSearchScreen pasan el episodio real resuelto del mirror"
```

---

### Task 5: Verificación final de la suite completa

**Files:** ninguno (solo comandos de verificación).

**Interfaces:** N/A.

- [ ] **Step 1: Compilar todo**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Correr toda la suite unitaria**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, incluyendo `WebSourceEpisodeTest` (5 tests) y `WebSourceSeasonTest`
(sin cambios, sigue en 5 tests verdes).

- [ ] **Step 3: Confirmar que no quedan cambios sin commitear**

Run: `git status`
Expected: working tree limpio (todo ya commiteado en los Tasks 1-4).
