# Packs web en pantallas de detalle (Anime + Cine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cuando el usuario navega un show en `AnimeShowDetailScreen` o `CineDetailScreen`, si el
backend Mirror tiene la serie completa crawleada en algún sitio web, ofrecerle guardarla como
"pack" — igual que ya puede hacer con packs de torrent — en vez de solo capítulo a capítulo.

**Architecture:** Todo el motor ya existe y está probado en producción vía `SearchScreen`
(`MirrorWebPack`, `WebPackDialog`, `TorrentSearchApi.seriesWebPacks()`, `MirrorWebFilter`). Este
plan agrega una función pura nueva (`MirrorWebPack.coversEpisode`, para decidir si un pack cubre un
episodio concreto), un wrapper delgado en `AnimeSourceProvider` (mismo molde que sus otros métodos),
y cablea ambos en las 2 pantallas de detalle copiando el patrón ya usado ahí mismo para packs de
torrent (`packFor`/`PackDialog`) y para fuentes web sueltas (`playWeb`/`playWebEp`). Cero cambios de
backend ni de `SearchScreen` (ya hace esto correctamente).

**Tech Stack:** Kotlin, Jetpack Compose, JUnit (tests JVM puros, sin Robolectric/Android deps).

## Global Constraints

- Sin cambios en el backend Mirror ni en el contrato `/api/title/<slug>` — los datos y el contrato
  ya están completos.
- Sin cambios en `SearchScreen.kt`/`TvSearchScreen.kt`/`SearchViewModel.kt`/`SearchPlayback.kt` — ya
  implementan el pack web correctamente, se usan solo como referencia de patrón ("molde").
- `ShowDetailScreen.kt` fuera de alcance (ruta muerta, nada navega ahí).
- Seguir el patrón de "copiar-adaptar la función chica" ya establecido en el código (comentarios
  `// Molde: X.metodo`) en vez de introducir abstracciones nuevas compartidas entre pantallas.
- Identidad de commits en este repo: `user.name=lordmacu`,
  `user.email=10134930+lordmacu@users.noreply.github.com` (ya configurado).

---

## File Structure

- `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt` — agrega
  `MirrorWebPack.coversEpisode()`, función pura reusada por las 2 pantallas.
- `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt` — tests de
  `coversEpisode` (archivo existente, se le agregan casos).
- `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt` — agrega
  `seriesWebPacks(show)`, wrapper delgado (mismo molde que `episodeSourcesWeb`/`browseTitles`).
- `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt` — cablea packs en los 2
  modos ("Por episodio" y "Todos") + diálogo + guardado.
- `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt` — cablea packs en el sheet de
  episodio/película + diálogo + guardado (cierra el no-op documentado en el propio archivo).

---

### Task 1: `MirrorWebPack.coversEpisode` (función pura, con test)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt`

**Interfaces:**
- Produces: `MirrorWebPack.coversEpisode(season: Int, episode: Int, seasonStrict: Boolean): Boolean`
  — usado por Task 3 y Task 4 para filtrar qué packs mostrar en un episodio concreto.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar al final de `app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt`,
DENTRO de la clase `MirrorWebPackTest` (antes de la última llave `}` que cierra la clase, línea 52):

```kotlin
    @Test fun `coversEpisode con seasonStrict exige temporada exacta`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 1, 5), w("s", 2, 5)))[0]
        assertEquals(true, pack.coversEpisode(season = 1, episode = 5, seasonStrict = true))
        assertEquals(false, pack.coversEpisode(season = 3, episode = 5, seasonStrict = true))
    }

    @Test fun `coversEpisode sin seasonStrict matchea el episodio en cualquier temporada`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 2, 5)))[0]
        assertEquals(true, pack.coversEpisode(season = 0, episode = 5, seasonStrict = false))
        assertEquals(false, pack.coversEpisode(season = 0, episode = 9, seasonStrict = false))
    }

    @Test fun `coversEpisode devuelve false para episodio ausente`() {
        val pack = MirrorWebPack.groupBySite("X", listOf(w("s", 1, 1), w("s", 1, 2)))[0]
        assertEquals(false, pack.coversEpisode(season = 1, episode = 3, seasonStrict = true))
    }
```

(El helper privado `w(site, season, episode)` ya existe en el archivo, líneas 7-9 — no hace falta
tocarlo.)

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorWebPackTest"`
Expected: FAIL — `coversEpisode` no existe todavía (error de compilación "unresolved reference").

- [ ] **Step 3: Implementar `coversEpisode`**

En `app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt`, dentro de la clase
`MirrorWebPack` (después de la propiedad `bySeason`, antes de `companion object {`):

```kotlin
    /** ¿Este pack tiene un episodio que matchea (season, episode)? Mismo criterio que
     *  [MirrorWebFilter]: TV exige temporada exacta (seasonStrict=true); anime solo exige que el
     *  episodio esté en el set, sin importar season (seasonStrict=false). */
    fun coversEpisode(season: Int, episode: Int, seasonStrict: Boolean): Boolean =
        episodes.any { (!seasonStrict || season <= 0 || it.season == season) && it.episode == episode }
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.mirror.MirrorWebPackTest"`
Expected: PASS (8 tests: 5 existentes + 3 nuevos).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/mirror/WebMirrorModels.kt app/src/test/java/com/arkiv/player/data/catalog/mirror/MirrorWebPackTest.kt
git commit -m "feat: MirrorWebPack.coversEpisode para filtrar packs por episodio"
```

---

### Task 2: `AnimeSourceProvider.seriesWebPacks`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`

**Interfaces:**
- Consumes: `TorrentSearchApi.seriesWebPacks(titles: List<String>, kind: ContentType, tmdbId: Int?, showTitle: String): List<MirrorWebPack>`
  (ya existe, `TorrentSearchApi.kt:215-224`); `showMeta(show).titles`/`.tmdbId` (privado, ya existe
  en esta clase).
- Produces: `AnimeSourceProvider.seriesWebPacks(show: AnimeShow): List<MirrorWebPack>` — usado por
  Task 3.

No hay test dedicado para esta clase (ningún método existente de `AnimeSourceProvider` lo tiene — la
cobertura real de esta capa es `MirrorWebFilterTest`/`MirrorWebPackTest`, que ya cubren la lógica
pura; este método es puro passthrough sin lógica propia). Se verifica por compilación acá y
funcionalmente en Task 3 (que sí es observable en pantalla).

- [ ] **Step 1: Agregar el import de `ContentType`**

En `app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt`, línea 1-9 (bloque de
imports), agregar:

```kotlin
import com.arkiv.player.data.catalog.providers.ContentType
```

- [ ] **Step 2: Agregar el método**

En el mismo archivo, después de `browseTitles` (línea 111, `suspend fun browseTitles(show: AnimeShow): List<String> = showMeta(show).titles`):

```kotlin

    /** Packs web (serie completa por sitio) del mirror para este show — reusa los mismos títulos y
     *  tmdbId que [episodeSourcesWeb], sin filtrar por episodio. */
    suspend fun seriesWebPacks(show: AnimeShow): List<com.arkiv.player.data.catalog.mirror.MirrorWebPack> {
        val meta = showMeta(show)
        return torrentSearchApi.seriesWebPacks(meta.titles, ContentType.ANIME, tmdbId = meta.tmdbId, showTitle = show.title)
    }
```

- [ ] **Step 3: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/AnimeSourceProvider.kt
git commit -m "feat: AnimeSourceProvider.seriesWebPacks"
```

---

### Task 3: Cablear packs web en `AnimeShowDetailScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`

**Interfaces:**
- Consumes: `AnimeSourceProvider.seriesWebPacks(show)` (Task 2), `MirrorWebPack.coversEpisode`
  (Task 1), `WebPackDialog` (ya existe, `ui/catalog/WebPackDialog.kt`, mismo paquete — sin import
  nuevo), `graph.repository.addWebSeriesEpisode(seriesId, title, poster, season, episode, name, pageUrl): String?`
  (ya existe, `ArkivRepository.kt:522`).
- Produces: nada consumido por otras tasks — es la hoja del árbol para Anime.

No hay tests de Compose en este codebase (ningún archivo de `ui/catalog/*Screen.kt` tiene test
unitario — se verifican por compilación + manual en device). Se verifica igual.

- [ ] **Step 1: Agregar imports**

En `app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt`, después de la línea 63
(`import com.arkiv.player.data.catalog.web.WebResult`):

```kotlin
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
```

- [ ] **Step 2: Agregar estado**

Después de la línea 118 (`var manualEpText by remember { mutableStateOf("") }`):

```kotlin
    var webPacks by remember { mutableStateOf<List<MirrorWebPack>>(emptyList()) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }
```

- [ ] **Step 3: Fetch de los packs, una vez por show**

Después del bloque (líneas 123-127):

```kotlin
    LaunchedEffect(anilistId) {
        loading = true
        show = runCatching { graph.aniListApi.details(anilistId) }.getOrNull()
        loading = false
    }
```

agregar:

```kotlin

    // Packs web (serie completa por sitio): un solo fetch por show, cacheado y reusado por los 2
    // modos — "Por episodio" inyecta los que cubren el episodio abierto, "Todos" los lista enteros.
    LaunchedEffect(show) {
        val s = show ?: return@LaunchedEffect
        webPacks = runCatching { graph.animeSourceProvider.seriesWebPacks(s) }.getOrDefault(emptyList())
    }
```

- [ ] **Step 4: Función de guardado**

Después de la función `playWebEp` (líneas 276-286, termina en `}`), agregar:

```kotlin

    // Agrega los capítulos elegidos de un pack web (serie completa de un sitio) a la biblioteca, uno
    // por episodio — mismo molde que playWebEp pero en loop. Devuelve al reproducir el episodio
    // pedido si se tocó uno puntual (onPlayOne del diálogo), si no el primero agregado (onSave).
    fun addWebPack(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>, playEpisode: MirrorWebSource? = null) {
        val s = show ?: return
        preparing = true; error = null
        scope.launch {
            var first: String? = null
            var wanted: String? = null
            for (ep in episodes) {
                val id = graph.repository.addWebSeriesEpisode(
                    "anilist$anilistId", title, s.posterUrl, 1, ep.episode,
                    ep.name.ifBlank { "Ep ${ep.episode}" }, ep.pageUrl,
                )
                if (first == null) first = id
                if (playEpisode != null && ep.pageUrl == playEpisode.pageUrl) wanted = id
            }
            preparing = false
            val target = wanted ?: first
            if (target != null) onPlay(target) else error = "No se pudo agregar la serie"
        }
    }
```

- [ ] **Step 5: Inyectar packs en la sub-sección WEB del modo "Por episodio"**

Reemplazar (líneas 504-509):

```kotlin
                                AnimeSourceSection(
                                    "WEB", Color(0xFFB39DDB), webs.size, loadingWebEp[ep] == true,
                                    expandedSub["$ep-w"] ?: false, { expandedSub["$ep-w"] = !(expandedSub["$ep-w"] ?: false) },
                                ) {
                                    webs.forEach { r -> WebEpRow(r, enabled = !preparing) { playWebEp(r, ep) } }
                                }
```

por:

```kotlin
                                val epPacks = webPacks.filter { it.coversEpisode(season = 0, episode = ep, seasonStrict = false) }
                                AnimeSourceSection(
                                    "WEB", Color(0xFFB39DDB), webs.size + epPacks.size, loadingWebEp[ep] == true,
                                    expandedSub["$ep-w"] ?: false, { expandedSub["$ep-w"] = !(expandedSub["$ep-w"] ?: false) },
                                ) {
                                    webs.forEach { r -> WebEpRow(r, enabled = !preparing) { playWebEp(r, ep) } }
                                    epPacks.forEach { p -> WebPackRow(p, enabled = !preparing) { webPackFor = p } }
                                }
```

- [ ] **Step 6: Sección WEB en el modo "Todos"**

Reemplazar (línea 520):

```kotlin
                    if (mode == "all") {
                        when {
```

por:

```kotlin
                    if (mode == "all") {
                        if (webPacks.isNotEmpty()) {
                            AnimeSourceSection(
                                "WEB", Color(0xFFB39DDB), webPacks.size, false,
                                expandedSub["all-w"] ?: true, { expandedSub["all-w"] = !(expandedSub["all-w"] ?: true) },
                            ) {
                                webPacks.forEach { p -> WebPackRow(p, enabled = !preparing) { webPackFor = p } }
                            }
                        }
                        when {
```

- [ ] **Step 7: Renderizar el diálogo**

Después del bloque `packFor?.let { ... }` (líneas 608-631, termina justo antes del `}` que cierra la
función `AnimeShowDetailScreen` en la línea 632), agregar:

```kotlin

    webPackFor?.let { p ->
        val s = show
        if (s != null) WebPackDialog(
            pack = p,
            defaultTitle = s.title,
            posterUrl = s.posterUrl,
            onDismiss = { webPackFor = null },
            onSave = { title, episodes ->
                webPackFor = null
                addWebPack(p, title, episodes)
            },
            onPlayOne = { title, ep ->
                webPackFor = null
                addWebPack(p, title, p.episodes, ep)
            },
        )
    }
```

- [ ] **Step 8: Composable `WebPackRow`**

Después de la función `WebEpRow` (líneas 709-725, termina en `}`), agregar:

```kotlin

@Composable
private fun WebPackRow(pack: MirrorWebPack, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFFFB74D))
        Column(Modifier.weight(1f)) {
            Text(pack.showTitle, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "PACK · ${pack.episodeCount} capítulos" +
                    (if (pack.seasons.size > 1) "  ·  ${pack.seasons.size} temporadas" else "") +
                    "  ·  ${pack.siteId}",
                color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
```

- [ ] **Step 9: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Verificación manual en device**

Instalar el APK (ver memoria de ADB WiFi al celular / Fire Stick del proyecto) y navegar a Death
Note (ya tiene 74 web_sources reales en el backend, 37 pelisplus + 37 serieskao, temporada 1):
1. Modo "Todos" → debe aparecer una sección WEB con 2 filas PACK (una por sitio, "37 capítulos" cada
   una).
2. Tocar una → se abre `WebPackDialog`, mostrar/guardar selección funciona.
3. Modo "Por episodio" → expandir el episodio 1 → la sub-sección WEB debe traer, además de las
   fuentes sueltas normales, las 2 filas PACK (Death Note tiene 1 sola temporada, así que todo pack
   cubre cualquier episodio 1-37).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/AnimeShowDetailScreen.kt
git commit -m "feat: packs web en AnimeShowDetailScreen (modos Por episodio y Todos)"
```

---

### Task 4: Cablear packs web en `CineDetailScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`

**Interfaces:**
- Consumes: `TorrentSearchApi.seriesWebPacks(titles, kind, tmdbId, showTitle)` (ya existe,
  `TorrentSearchApi.kt:215-224`, llamado directo vía `graph.torrentSearchApi` — este archivo ya
  llama otros métodos de `TorrentSearchApi` directo, a diferencia de Anime que pasa por
  `AnimeSourceProvider`), `MirrorWebPack.coversEpisode` (Task 1), `WebPackDialog` (mismo paquete),
  `PlaySource.WebPack` (ya existe, `PlaySources.kt:44`).
- Produces: nada consumido por otras tasks.

- [ ] **Step 1: Agregar imports**

En `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`, después de la línea 53
(`import com.arkiv.player.data.catalog.TorrentSource`):

```kotlin
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource
import com.arkiv.player.data.catalog.providers.ContentType
```

- [ ] **Step 2: Agregar estado**

Después de la línea 91 (`var sources by remember { mutableStateOf<List<PlaySource>>(emptyList()) }`):

```kotlin
    var webPacks by remember { mutableStateOf<List<MirrorWebPack>>(emptyList()) }
    var webPackFor by remember { mutableStateOf<MirrorWebPack?>(null) }
```

- [ ] **Step 3: Fetch de los packs, una vez por título**

Después del bloque (líneas 108-118):

```kotlin
    LaunchedEffect(tmdbId, type) {
        loading = true
        val d = runCatching { graph.tmdbApi.detail(type, tmdbId) }.getOrNull()
        detail = d
        // Si viene un deep-link de temporada, respetarlo en vez de pisarlo con la temporada por
        // defecto (si no, este efecto se ejecuta después de LaunchedEffect(deepLinkSeason) y lo clobbers).
        selectedSeason = deepLinkSeason
            ?: d?.seasons?.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
            ?: d?.seasons?.firstOrNull()?.seasonNumber
        loading = false
    }
```

agregar:

```kotlin

    // Packs web (serie completa por sitio): un solo fetch por título, cacheado y reusado por todos
    // los episodios del sheet. Películas no tienen concepto de pack (queda vacío).
    LaunchedEffect(detail) {
        val d = detail
        webPacks = if (d != null && d.isSeries) {
            runCatching {
                graph.torrentSearchApi.seriesWebPacks(d.searchTitles, ContentType.TV, tmdbId = d.id, showTitle = d.title)
            }.getOrDefault(emptyList())
        } else emptyList()
    }
```

- [ ] **Step 4: Inyectar packs en la búsqueda WEB del sheet**

Reemplazar (líneas 153-164, el inicio del bloque WEB dentro de `runSearch`):

```kotlin
            launch {
                // Mirror primero (rapido, sin Cloudflare on-device): si el backend ya tiene fuentes
                // web para este episodio, las usamos. Si no (o es pelicula, fuera de alcance del
                // mirror web), caemos al scraping en vivo de siempre.
                val mirrorWeb = if (ep != null) {
                    runCatching {
                        graph.torrentSearchApi.searchEpisodeWeb(d.searchTitles, ep.season, ep.episode, tmdbId = d.id)
                    }.getOrDefault(emptyList())
                } else emptyList()
                if (mirrorWeb.isNotEmpty()) {
                    append(mirrorWeb.map { PlaySource.Web(it) })
                    if (sheetEpisode == ep) loadingWeb = false
```

por:

```kotlin
            launch {
                // Packs web cacheados que cubren este episodio (temporada exacta, mismo criterio que
                // MirrorFilter usa para torrent en TV): se suman aparte de mirrorWeb/scraping en
                // vivo, nunca los reemplazan.
                if (ep != null) {
                    val epPacks = webPacks.filter { it.coversEpisode(ep.season, ep.episode, seasonStrict = true) }
                    if (epPacks.isNotEmpty()) append(epPacks.map { PlaySource.WebPack(it) })
                }
                // Mirror primero (rapido, sin Cloudflare on-device): si el backend ya tiene fuentes
                // web para este episodio, las usamos. Si no (o es pelicula, fuera de alcance del
                // mirror web), caemos al scraping en vivo de siempre.
                val mirrorWeb = if (ep != null) {
                    runCatching {
                        graph.torrentSearchApi.searchEpisodeWeb(d.searchTitles, ep.season, ep.episode, tmdbId = d.id)
                    }.getOrDefault(emptyList())
                } else emptyList()
                if (mirrorWeb.isNotEmpty()) {
                    append(mirrorWeb.map { PlaySource.Web(it) })
                    if (sheetEpisode == ep) loadingWeb = false
```

(El resto del bloque, `else { ... }` con el scraping en vivo, queda IGUAL — no se toca.)

- [ ] **Step 5: Cerrar el no-op de `playSource`**

Reemplazar (líneas 265-273):

```kotlin
    fun playSource(s: PlaySource) = when (s) {
        is PlaySource.Torrent ->
            if (com.arkiv.player.data.catalog.PackDetector.isPack(s.result.name)) packFor = s.result
            else play(s.result)
        is PlaySource.Archive -> playArchive(s.item)
        is PlaySource.Web -> playWeb(s.result)
        // Esta pantalla nunca produce WebPack (solo la búsqueda lo hará, en una tarea posterior); no-op.
        is PlaySource.WebPack -> Unit
    }
```

por:

```kotlin
    fun playSource(s: PlaySource) = when (s) {
        is PlaySource.Torrent ->
            if (com.arkiv.player.data.catalog.PackDetector.isPack(s.result.name)) packFor = s.result
            else play(s.result)
        is PlaySource.Archive -> playArchive(s.item)
        is PlaySource.Web -> playWeb(s.result)
        is PlaySource.WebPack -> webPackFor = s.pack
    }
```

- [ ] **Step 6: Función de guardado**

Después de la función `playWeb` (líneas 249-263, termina en `}`), agregar:

```kotlin

    // Agrega los capítulos elegidos de un pack web (serie completa de un sitio) a la biblioteca, uno
    // por episodio real (season/episode tal cual los trae el sitio) — mismo molde que playWeb pero
    // en loop. Devuelve al reproducir el episodio pedido si se tocó uno puntual, si no el primero.
    fun addWebPack(pack: MirrorWebPack, title: String, episodes: List<MirrorWebSource>, playEpisode: MirrorWebSource? = null) {
        val d = detail ?: return
        val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            var first: String? = null
            var wanted: String? = null
            for (ep in episodes) {
                val id = graph.repository.addWebSeriesEpisode(
                    seriesId, title, d.posterUrl, ep.season, ep.episode,
                    ep.name.ifBlank { "Ep ${ep.episode}" }, ep.pageUrl,
                )
                if (first == null) first = id
                if (playEpisode != null && ep.pageUrl == playEpisode.pageUrl) wanted = id
            }
            preparing = false
            val target = wanted ?: first
            if (target != null) onPlay(target) else error = "No se pudo agregar la serie"
        }
    }
```

- [ ] **Step 7: Renderizar el diálogo**

Después del bloque `packFor?.let { ... }` (líneas 415-438, termina justo antes del `}` que cierra la
función `CineDetailScreen` en la línea 439), agregar:

```kotlin

    webPackFor?.let { p ->
        val d = detail
        if (d != null) WebPackDialog(
            pack = p,
            defaultTitle = d.title,
            posterUrl = d.posterUrl,
            onDismiss = { webPackFor = null },
            onSave = { title, episodes ->
                webPackFor = null
                addWebPack(p, title, episodes)
            },
            onPlayOne = { title, ep ->
                webPackFor = null
                addWebPack(p, title, p.episodes, ep)
            },
        )
    }
```

- [ ] **Step 8: Verificar que compila**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Verificación manual en device**

Buscar/navegar en `CineDetailScreen` una serie TMDB (no anime) que tenga pack web indexado en el
backend — o esperar a que el catálogo pelispanda/serieskao la cubra — y confirmar:
1. Abrir un episodio de una temporada cubierta por el pack → la sección WEB trae, además de las
   fuentes sueltas, una fila PACK por sitio.
2. Tocarla abre `WebPackDialog`; guardar todo/seleccionados agrega los episodios a la biblioteca y
   reproduce.
3. Un episodio de una temporada NO cubierta por ningún pack no muestra fila PACK (respeta
   `seasonStrict = true`).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git commit -m "feat: packs web en CineDetailScreen (cierra el no-op diferido)"
```

---

## Self-Review (hecho al escribir este plan)

- **Cobertura del spec:** backend (ya resuelto fuera de este plan, crawl manual + `WEB_CRAWL_BUDGET`
  subido) — Task 1-4 cubren AnimeShowDetailScreen (ambos modos) y CineDetailScreen, tal como acordó
  el spec. `ShowDetailScreen` y `SearchScreen` explícitamente fuera de alcance, documentado arriba.
- **Placeholders:** ninguno — cada step tiene el código exacto a escribir/reemplazar con contexto de
  líneas del archivo actual.
- **Consistencia de tipos:** `MirrorWebPack`/`MirrorWebSource` (Task 1) se usan con los mismos
  nombres en Task 3 y 4; `coversEpisode(season, episode, seasonStrict)` se llama igual en ambas
  pantallas, solo cambian los argumentos (`seasonStrict=false` tolerante en Anime,
  `seasonStrict=true` exacto en Cine — igual que `MirrorWebFilter` ya distingue ANIME/TV);
  `addWebPack(pack, title, episodes, playEpisode)` tiene la misma firma en ambos archivos (cada uno
  con su propia implementación local, a propósito — ver "Por qué NO reusar SearchPlayback" en el
  spec).
