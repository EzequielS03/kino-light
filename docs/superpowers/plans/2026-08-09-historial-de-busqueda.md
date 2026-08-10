# Historial de búsqueda en Buscar (celular) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la pantalla Buscar del celular recuerde las búsquedas y los títulos abiertos, los muestre mientras no se haya buscado nada, y los esconda al buscar.

**Architecture:** Un store nuevo (`SearchHistoryStore`, SharedPreferences + `org.json`) guarda dos listas; la lógica de orden/dedupe/tope vive aparte en un objeto puro y testeado (`SearchHistoryPolicy`). El `SearchViewModel` es el único que graba (en `search()` y `pickTitle()`) y expone las listas; `QueryContent` pinta el historial mientras un flag local `haBuscado` sea `false`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3, BOM 2024.12.01), SharedPreferences, `org.json`, JUnit 4.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-09-historial-de-busqueda-design.md`.
- Alcance: **solo `ui/search/SearchScreen.kt`**. `ui/tv/TvSearchScreen.kt` NO cambia de UI (solo se le agrega el parámetro nuevo al constructor del ViewModel para que compile).
- Topes: **10** búsquedas de texto, **12** títulos.
- Comentarios y nombres de test **en español**, como el resto del repo (ver `UnknownLengthPolicyTest`).
- **Nunca `git add -A` ni `git commit -am`.** El working tree lo comparten varias sesiones de Claude: commitear siempre con rutas explícitas, solo los archivos de la tarea.
- Identidad de git obligatoria: `lordmacu` / `10134930+lordmacu@users.noreply.github.com`. Sin pie de coautoría.
- Verificación de tests: `./gradlew :app:testDebugUnitTest` (no `assembleDebug`, que puede pasar con clases cacheadas).
- Divergencia deliberada del spec: donde el spec listaba `clearQueries()` y `clearTitles()` por separado, el plan deja un solo `clear()`. La UI tiene un único botón "Borrar historial" que borra las dos listas; dos métodos serían un usuario imaginario.

---

### Task 1: `SearchHistoryPolicy` + `RecentTitle`

Lógica pura de las listas: qué entra, en qué orden, cuándo se deduplica y cuándo cae lo viejo. Sin Android, así que se puede probar de verdad (con `unitTests.isReturnDefaultValues = true`, cualquier cosa que toque `JSONObject` o `SharedPreferences` devuelve defaults en tests unitarios).

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt`
- Test: `app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `data class RecentTitle(kind: String, tmdbId: Int?, anilistId: Long?, title: String, posterUrl: String, year: String)`
  - `object SearchHistoryPolicy` con `const val MAX_QUERIES = 10`, `const val MAX_TITLES = 12`,
    `fun pushQuery(actuales: List<String>, texto: String, max: Int = MAX_QUERIES): List<String>`,
    `fun pushTitle(actuales: List<RecentTitle>, nuevo: RecentTitle, max: Int = MAX_TITLES): List<RecentTitle>`,
    `fun mismaIdentidad(a: RecentTitle, b: RecentTitle): Boolean`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt`:

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orden, dedupe y tope del historial del buscador. Ver SearchHistoryStore para la persistencia. */
class SearchHistoryPolicyTest {

    private fun titulo(
        kind: String = "series",
        tmdbId: Int? = 1399,
        anilistId: Long? = null,
        title: String = "Game of Thrones",
    ) = RecentTitle(kind, tmdbId, anilistId, title, posterUrl = "", year = "2011")

    // ─── textos ────────────────────────────────────────────────────────────

    @Test fun lo_nuevo_queda_primero() {
        val r = SearchHistoryPolicy.pushQuery(listOf("dune", "akira"), "one piece")
        assertEquals(listOf("one piece", "dune", "akira"), r)
    }

    @Test fun repetir_un_texto_lo_sube_al_tope_sin_duplicarlo() {
        val r = SearchHistoryPolicy.pushQuery(listOf("dune", "akira", "one piece"), "akira")
        assertEquals(listOf("akira", "dune", "one piece"), r)
    }

    @Test fun el_dedupe_de_texto_ignora_mayusculas_y_espacios_sobrantes() {
        val r = SearchHistoryPolicy.pushQuery(listOf("One Piece", "dune"), "  one piece  ")
        assertEquals(listOf("one piece", "dune"), r)
    }

    @Test fun el_texto_se_guarda_recortado() {
        val r = SearchHistoryPolicy.pushQuery(emptyList(), "  dune  ")
        assertEquals(listOf("dune"), r)
    }

    @Test fun un_texto_vacio_o_de_solo_espacios_no_entra() {
        assertEquals(listOf("dune"), SearchHistoryPolicy.pushQuery(listOf("dune"), ""))
        assertEquals(listOf("dune"), SearchHistoryPolicy.pushQuery(listOf("dune"), "   "))
    }

    @Test fun se_respeta_el_tope_y_cae_el_mas_viejo() {
        val llena = (1..10).map { "q$it" }   // q1 es el más nuevo, q10 el más viejo
        val r = SearchHistoryPolicy.pushQuery(llena, "nueva")
        assertEquals(10, r.size)
        assertEquals("nueva", r.first())
        assertFalse(r.contains("q10"))
    }

    // ─── títulos ───────────────────────────────────────────────────────────

    @Test fun el_dedupe_de_titulos_usa_la_identidad_no_el_nombre() {
        // Dos series distintas que se llaman igual NO se pisan.
        val a = titulo(tmdbId = 1399, title = "The Office")
        val b = titulo(tmdbId = 2316, title = "The Office")
        val r = SearchHistoryPolicy.pushTitle(listOf(a), b)
        assertEquals(2, r.size)
        assertEquals(b, r.first())
    }

    @Test fun volver_a_abrir_un_titulo_lo_sube_al_tope_sin_duplicarlo() {
        val a = titulo(tmdbId = 1399, title = "Game of Thrones")
        val b = titulo(tmdbId = 66732, title = "Stranger Things")
        val r = SearchHistoryPolicy.pushTitle(listOf(b, a), a)
        assertEquals(listOf(a, b), r)
    }

    @Test fun una_peli_y_un_anime_con_el_mismo_numero_de_id_no_se_pisan() {
        val peli = RecentTitle("movie", tmdbId = 21, anilistId = null, title = "Peli", posterUrl = "", year = "")
        val anime = RecentTitle("anime", tmdbId = null, anilistId = 21L, title = "Anime", posterUrl = "", year = "")
        assertFalse(SearchHistoryPolicy.mismaIdentidad(peli, anime))
        assertEquals(2, SearchHistoryPolicy.pushTitle(listOf(peli), anime).size)
    }

    @Test fun sin_ningun_id_la_identidad_cae_al_nombre() {
        val a = RecentTitle("movie", null, null, "Dune", "", "2021")
        val b = RecentTitle("movie", null, null, "dune", "", "2021")
        assertTrue(SearchHistoryPolicy.mismaIdentidad(a, b))
        assertEquals(1, SearchHistoryPolicy.pushTitle(listOf(a), b).size)
    }

    @Test fun los_titulos_tambien_respetan_su_tope() {
        val llena = (1..12).map { titulo(tmdbId = it, title = "t$it") }
        val r = SearchHistoryPolicy.pushTitle(llena, titulo(tmdbId = 999, title = "nueva"))
        assertEquals(12, r.size)
        assertEquals("nueva", r.first().title)
        assertFalse(r.any { it.title == "t12" })
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SearchHistoryPolicyTest"
```

Esperado: FALLA al compilar — `Unresolved reference: RecentTitle` / `SearchHistoryPolicy`.

- [ ] **Step 3: Implementar**

Crear `app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt`:

```kotlin
package com.arkiv.player.data

/**
 * Un título que se abrió desde el buscador, guardado para poder volver a él sin buscarlo de nuevo.
 *
 * No se persiste el `TitleCard` de la UI: arrastra `overview` y `backdropUrl`, que el hero de la
 * fase RESULTS vuelve a pedir a TMDB/AniList igual. Ver `TitleCard.toRecent()` en CardContext.kt.
 */
data class RecentTitle(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
)

/**
 * Qué entra al historial del buscador, en qué orden y cuándo cae lo viejo.
 *
 * Objeto puro a propósito: la persistencia (SharedPreferences + org.json) no se puede probar en
 * tests unitarios — con `unitTests.isReturnDefaultValues = true` las clases de Android devuelven
 * defaults — así que la decisión vive acá y [SearchHistoryStore] queda como una capa flaca de I/O.
 * Mismo patrón que UnknownLengthPolicy y TorrentSizeGate.
 */
object SearchHistoryPolicy {

    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12

    /** Mete el texto al tope. Recorta, ignora vacíos y deduplica sin mirar mayúsculas. */
    fun pushQuery(actuales: List<String>, texto: String, max: Int = MAX_QUERIES): List<String> {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return actuales
        val resto = actuales.filterNot { it.equals(limpio, ignoreCase = true) }
        return (listOf(limpio) + resto).take(max)
    }

    /** Mete el título al tope, deduplicando por identidad (ver [mismaIdentidad]). */
    fun pushTitle(actuales: List<RecentTitle>, nuevo: RecentTitle, max: Int = MAX_TITLES): List<RecentTitle> {
        val resto = actuales.filterNot { mismaIdentidad(it, nuevo) }
        return (listOf(nuevo) + resto).take(max)
    }

    /**
     * Si dos entradas son la misma obra. Por id, no por nombre: hay series distintas que se llaman
     * igual, y el mismo número puede ser una peli en TMDB y otra cosa en AniList — por eso el `kind`
     * también cuenta. Sin ningún id (no debería pasar, pero el JSON viejo puede traerlo) cae al
     * nombre, que es mejor que dar todo por distinto y llenar la lista de repetidos.
     */
    fun mismaIdentidad(a: RecentTitle, b: RecentTitle): Boolean {
        if (a.kind != b.kind) return false
        if (a.tmdbId == null && a.anilistId == null && b.tmdbId == null && b.anilistId == null) {
            return a.title.equals(b.title, ignoreCase = true)
        }
        return a.tmdbId == b.tmdbId && a.anilistId == b.anilistId
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.SearchHistoryPolicyTest"
```

Esperado: PASA, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SearchHistoryPolicy.kt app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt
git commit -m "feat(buscar): reglas del historial de busquedas (orden, dedupe, tope)"
```

---

### Task 2: `SearchHistoryStore` + registro en el grafo

La persistencia: SharedPreferences propio y JSON con `org.json`, el patrón del repo (`TmdbApi`, `AnimeMapping`, `UpdateChecker`). No lleva test unitario — `JSONObject` y `SharedPreferences` devuelven defaults en el variant de test. Se verifica compilando acá y en el device en la Task 4.

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt:47` (agregar la línea después de `settings`)

**Interfaces:**
- Consumes: `RecentTitle`, `SearchHistoryPolicy` (Task 1).
- Produces:
  - `class SearchHistoryStore(context: Context)` con
    `val queries: StateFlow<List<String>>`, `val titles: StateFlow<List<RecentTitle>>`,
    `fun addQuery(q: String)`, `fun addTitle(t: RecentTitle)`,
    `fun removeQuery(q: String)`, `fun removeTitle(t: RecentTitle)`, `fun clear()`
  - `AppGraph.searchHistory: SearchHistoryStore`

- [ ] **Step 1: Crear el store**

Crear `app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt`:

```kotlin
package com.arkiv.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial del buscador: qué se buscó y qué títulos se abrieron.
 *
 * Va aparte de [SettingsStore] a propósito. Ese archivo es configuración (URLs, llaves, calidad);
 * esto es dato de uso, que se ensucia y se borra. La lógica de orden y tope vive en
 * [SearchHistoryPolicy]; acá solo hay prefs y JSON.
 *
 * Lectura tolerante: un JSON corrupto o de un formato viejo devuelve lista vacía. El historial
 * nunca puede tumbar la pantalla de búsqueda.
 */
class SearchHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _queries = MutableStateFlow(leerQueries())
    val queries: StateFlow<List<String>> = _queries

    private val _titles = MutableStateFlow(leerTitles())
    val titles: StateFlow<List<RecentTitle>> = _titles

    fun addQuery(q: String) = guardarQueries(SearchHistoryPolicy.pushQuery(_queries.value, q))

    fun addTitle(t: RecentTitle) = guardarTitles(SearchHistoryPolicy.pushTitle(_titles.value, t))

    fun removeQuery(q: String) = guardarQueries(_queries.value.filterNot { it.equals(q, ignoreCase = true) })

    fun removeTitle(t: RecentTitle) =
        guardarTitles(_titles.value.filterNot { SearchHistoryPolicy.mismaIdentidad(it, t) })

    /** Borra el historial entero (las dos listas): es lo que espera un botón que dice "borrar". */
    fun clear() {
        guardarQueries(emptyList())
        guardarTitles(emptyList())
    }

    private fun guardarQueries(lista: List<String>) {
        prefs.edit().putString(KEY_QUERIES, JSONArray(lista).toString()).apply()
        _queries.value = lista
    }

    private fun guardarTitles(lista: List<RecentTitle>) {
        val arr = JSONArray()
        for (t in lista) {
            arr.put(
                JSONObject().apply {
                    put("kind", t.kind)
                    t.tmdbId?.let { put("tmdbId", it) }
                    t.anilistId?.let { put("anilistId", it) }
                    put("title", t.title)
                    put("poster", t.posterUrl)
                    put("year", t.year)
                },
            )
        }
        prefs.edit().putString(KEY_TITLES, arr.toString()).apply()
        _titles.value = lista
    }

    private fun leerQueries(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_QUERIES, "[]"))
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun leerTitles(): List<RecentTitle> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_TITLES, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            if (title.isBlank()) return@mapNotNull null
            RecentTitle(
                kind = o.optString("kind", "movie"),
                tmdbId = if (o.has("tmdbId")) o.optInt("tmdbId") else null,
                anilistId = if (o.has("anilistId")) o.optLong("anilistId") else null,
                title = title,
                posterUrl = o.optString("poster"),
                year = o.optString("year"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PREFS_NAME = "arkiv_search_history"
        private const val KEY_QUERIES = "queries"
        private const val KEY_TITLES = "titles"
    }
}
```

- [ ] **Step 2: Registrarlo en el grafo**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, justo debajo de la línea de `settings`:

```kotlin
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }
```

Si `SettingsStore` está importado con nombre, agregar el import de `com.arkiv.player.data.SearchHistoryStore` al lado; si el archivo importa el paquete entero o ya usa `com.arkiv.player.data.*`, no hace falta.

- [ ] **Step 3: Verificar que compila**

```bash
./gradlew :app:compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SearchHistoryStore.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(buscar): store del historial en prefs + registro en el grafo"
```

---

### Task 3: El ViewModel graba y expone el historial

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt` (agregar los dos mappers al final)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt:70-81` (parámetro nuevo), `:186-194` (`search`), `:270-277` (`pickTitle`)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt:110-114` (call site)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt:115-119` (call site — solo para que compile)

**Interfaces:**
- Consumes: `SearchHistoryStore`, `RecentTitle` (Tasks 1-2), `TitleCard` (ya existe en `CardContext.kt`).
- Produces:
  - `fun TitleCard.toRecent(): RecentTitle` y `fun RecentTitle.toTitleCard(): TitleCard`
  - En `SearchViewModel`: `val recentQueries: StateFlow<List<String>>`, `val recentTitles: StateFlow<List<RecentTitle>>`,
    `fun forgetQuery(q: String)`, `fun forgetTitle(t: RecentTitle)`, `fun clearHistory()`

- [ ] **Step 1: Agregar los mappers**

Al final de `app/src/main/java/com/arkiv/player/ui/search/CardContext.kt` (y agregar arriba `import com.arkiv.player.data.RecentTitle`):

```kotlin
/** Card → entrada del historial. Se tira `overview`/`backdrop`: el hero los vuelve a pedir igual. */
fun TitleCard.toRecent(): RecentTitle = RecentTitle(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
)

/** Historial → card, para poder tocar un póster reciente y caer directo en las fuentes. */
fun RecentTitle.toTitleCard(): TitleCard = TitleCard(
    kind = kind,
    tmdbId = tmdbId,
    anilistId = anilistId,
    title = title,
    posterUrl = posterUrl,
    year = year,
    overview = null,
)
```

- [ ] **Step 2: El ViewModel recibe el store y graba**

En `SearchViewModel.kt`, agregar el parámetro al final del constructor (después de `arkivApiClient`):

```kotlin
    private val arkivApiClient: com.arkiv.player.data.gateway.ArkivApiClient,
    private val searchHistory: com.arkiv.player.data.SearchHistoryStore,
) : ViewModel() {
```

Justo debajo de la declaración de `searchJob`/`sourceJob`, exponer las listas y el borrado:

```kotlin
    // --- historial del buscador -------------------------------------------
    // Graba el ViewModel, no la pantalla: así da igual quién dispare la búsqueda y hay un solo
    // lugar donde mirar. El TV usa el mismo ViewModel, así que también llena su historial (no lo
    // muestra todavía; cuando se le haga UI, el dato ya va a estar).
    val recentQueries: StateFlow<List<String>> = searchHistory.queries
    val recentTitles: StateFlow<List<com.arkiv.player.data.RecentTitle>> = searchHistory.titles

    fun forgetQuery(q: String) = searchHistory.removeQuery(q)
    fun forgetTitle(t: com.arkiv.player.data.RecentTitle) = searchHistory.removeTitle(t)
    fun clearHistory() = searchHistory.clear()
```

En `search(q)`, grabar después del guard de vacío (así limpiar el campo no ensucia el historial) —
queda:

```kotlin
    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _titleResults.value = emptyList()
            _directResults.value = emptyList()
            _loadingTitles.value = false
            _loadingDirect.value = false
            return
        }
        searchHistory.addQuery(q)
        searchJob = viewModelScope.launch {
```

En `pickTitle(card)`, grabar en la primera línea:

```kotlin
    fun pickTitle(card: TitleCard) {
        searchHistory.addTitle(card.toRecent())
        _selected.value = card
```

- [ ] **Step 3: Actualizar los dos call sites**

En `SearchScreen.kt:110-114` y en `TvSearchScreen.kt:115-119`, la misma línea final:

```kotlin
                SearchViewModel(
                    graph.tmdbApi, graph.aniListApi, graph.torrentSearchApi, graph.api,
                    graph.mirrorApiClient, graph.animeSourceProvider, graph.webSourceEngine,
                    graph.settings, graph.torrentEngine, graph.arkivApiClient,
                    graph.searchHistory,
                )
```

- [ ] **Step 4: Verificar que compila y que los tests siguen verdes**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Esperado: BUILD SUCCESSFUL, sin tests rotos.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/CardContext.kt app/src/main/java/com/arkiv/player/ui/search/SearchViewModel.kt app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt
git commit -m "feat(buscar): el viewmodel graba y expone el historial"
```

---

### Task 4: El historial en pantalla

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt` — imports (bloque 1-60), call site de `QueryContent` (:462-471), y `QueryContent` entero (:596-676)

**Interfaces:**
- Consumes: `vm.recentQueries`, `vm.recentTitles`, `vm.forgetQuery`, `vm.forgetTitle`, `vm.clearHistory` (Task 3); `RecentTitle.toTitleCard()` (Task 3); `TitleCardItem` (ya existe, `:679`).
- Produces: nada para tareas siguientes.

- [ ] **Step 1: Agregar los imports**

En el bloque de imports de `SearchScreen.kt`, en orden alfabético dentro de su grupo:

```kotlin
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.InputChip
import com.arkiv.player.data.RecentTitle
```

- [ ] **Step 2: Recolectar el historial y pasarlo**

En el cuerpo de la pantalla, al lado de los otros `collectAsStateWithLifecycle()` (cerca de `:134`):

```kotlin
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val recentTitles by vm.recentTitles.collectAsStateWithLifecycle()
```

Y el call site de `QueryContent` (`:462-471`) queda:

```kotlin
                else -> QueryContent(
                    titleResults = titleResults,
                    directResults = directResults,
                    loadingTitles = loadingTitles,
                    loadingDirect = loadingDirect,
                    recentQueries = recentQueries,
                    recentTitles = recentTitles,
                    onSearch = { vm.search(it) },
                    onPickTitle = { card -> vm.pickTitle(card) },
                    onPlayDirect = { playDirect(it) },
                    onDownloadDirect = { saveDirect(it) },
                    onForgetQuery = { vm.forgetQuery(it) },
                    onForgetTitle = { vm.forgetTitle(it) },
                    onClearHistory = { vm.clearHistory() },
                )
```

- [ ] **Step 3: Reemplazar `QueryContent` y agregar el bloque de historial**

Reemplazar el `QueryContent` actual (desde el KDoc de `:596` hasta el cierre en `:676`) por:

```kotlin
/** Fase QUERY: buscador + historial (hasta que se busca) + resultados. */
@Composable
private fun QueryContent(
    titleResults: List<TitleCard>,
    directResults: List<PlaySource>,
    loadingTitles: Boolean,
    loadingDirect: Boolean,
    recentQueries: List<String>,
    recentTitles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onPlayDirect: (PlaySource) -> Unit,
    /** Guarda el resultado directo en el dispositivo (botón de descarga de cada fila). */
    onDownloadDirect: (PlaySource) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Mientras no se haya buscado nada se muestra el historial en vez de dos "Sin resultados" que
    // no informan nada. Es estado local: salir de la pantalla y volver muestra el historial otra vez.
    var haBuscado by remember { mutableStateOf(false) }

    val buscar: (String) -> Unit = { q ->
        text = q
        haBuscado = true
        onSearch(q)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Buscar…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                // Limpiar devuelve al historial. Sin esto, una vez buscada la primera cosa el
                // historial no vuelve hasta salir y entrar de nuevo a la pantalla.
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; haBuscado = false; onSearch("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { buscar(text) }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (!haBuscado) {
            historialItems(
                queries = recentQueries,
                titles = recentTitles,
                onSearch = buscar,
                onPickTitle = onPickTitle,
                onForgetQuery = onForgetQuery,
                onForgetTitle = onForgetTitle,
                onClearHistory = onClearHistory,
            )
            return@LazyVerticalGrid
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Películas y series", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadingTitles) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (titleResults.isEmpty() && !loadingTitles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        items(titleResults, key = { "${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { card ->
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Text("Resultados directos", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadingDirect) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (directResults.isEmpty() && !loadingDirect) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (directResults.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    directResults.forEach { source ->
                        SourceRow(
                            source, enabled = true,
                            onDownload = { onDownloadDirect(source) },
                        ) { onPlayDirect(source) }
                    }
                }
            }
        }
    }
}

/**
 * Historial: los textos buscados como chips y los títulos abiertos como pósters. Va aparte de
 * [QueryContent] para no engordarlo; es una extensión de LazyGridScope porque vive dentro de la
 * misma grilla (los pósters tienen que caer en las mismas 3 columnas que los resultados).
 */
private fun LazyGridScope.historialItems(
    queries: List<String>,
    titles: List<RecentTitle>,
    onSearch: (String) -> Unit,
    onPickTitle: (TitleCard) -> Unit,
    onForgetQuery: (String) -> Unit,
    onForgetTitle: (RecentTitle) -> Unit,
    onClearHistory: () -> Unit,
) {
    // Primera vez que se abre la app: ni encabezados. Solo el buscador y nada más.
    if (queries.isEmpty() && titles.isEmpty()) return

    if (queries.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Búsquedas recientes",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                queries.forEach { q ->
                    InputChip(
                        selected = false,
                        onClick = { onSearch(q) },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Quitar $q",
                                modifier = Modifier.size(16.dp).clickable { onForgetQuery(q) },
                            )
                        },
                    )
                }
            }
        }
    }

    if (titles.isNotEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Seguí buscando",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(titles, key = { "recent-${it.kind}-${it.tmdbId}-${it.anilistId}-${it.title}" }) { reciente ->
            val card = reciente.toTitleCard()
            TitleCardItem(card, onClick = { onPickTitle(card) })
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }) {
        TextButton(onClick = onClearHistory, modifier = Modifier.padding(top = 8.dp)) {
            Text("Borrar historial", color = ArkivTextSecondary)
        }
    }
}
```

Nota: `FlowRow` es estable en compose-foundation 1.7 (BOM 2024.12.01). Si el compilador igual pide
opt-in, agregar `@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)` sobre
`historialItems`.

Nota 2: el `onForgetTitle` no se usa en esta pasada (los pósters no traen × todavía: en una grilla
de 3 columnas la × pisa el póster). Se deja el parámetro cableado — borrar un título se hace hoy
con "Borrar historial". **Si el compilador avisa por parámetro sin usar, dejarlo igual**; es la
puerta para agregar el long-press después.

- [ ] **Step 4: Compilar y correr los tests**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Esperado: BUILD SUCCESSFUL, tests verdes.

- [ ] **Step 5: Probar en el celular**

El Samsung S24+ va por ADB WiFi. El puerto cambia cada vez que se apaga la depuración inalámbrica,
así que primero ver qué hay conectado y, si no hay nada, pedirle a Cristian el puerto de la
**pantalla principal** de Depuración inalámbrica (no el del popup de vinculación):

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

Con el device conectado (reemplazar `<serial>` por lo que liste el comando anterior):

```bash
~/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Recorrido a verificar, en orden:

1. Abrir Buscar con la app recién instalada → **solo el campo de búsqueda**, sin "Sin resultados".
2. Buscar algo → aparecen las secciones de siempre; el historial desaparece.
3. Tocar la × del campo → vuelve el historial, ahora con un chip.
4. Abrir un título de los resultados → volver a Buscar → sale en "Seguí buscando" con su póster.
5. Tocar ese póster → cae directo en las fuentes, sin pasar por la búsqueda.
6. Tocar un chip → relanza esa búsqueda y el texto queda en el campo.
7. Repetir una búsqueda que ya estaba → sube al tope, no se duplica.
8. Matar la app y volver a abrirla → el historial sigue ahí.
9. "Borrar historial" → quedan las dos listas vacías y la pantalla limpia.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/search/SearchScreen.kt
git commit -m "feat(buscar): chips de busquedas recientes y posters de lo ultimo abierto"
```

---

## Fuera de alcance

- El historial en el TV (`TvSearchScreen.kt`): el ViewModel ya lo graba, falta la UI con navegación por foco.
- Borrar un título suelto con long-press sobre el póster.
- Sugerencias mientras se escribe (autocompletar desde el historial).
