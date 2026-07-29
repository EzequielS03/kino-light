# Torrents 100% on-device (sin servidor) — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar la dependencia del servidor `blog` (Jackett/FlareSolverr) para la búsqueda de torrents; resolver todo on-device (scraping declarativo + WebView-Cloudflare) con hot-update por archivo estático.

**Architecture:** Se elimina `JackettBackend` y el `HealthGate`. El tier on-device (`RegistryProviderBackend` sobre `DeclarativeHtmlBackend` + `WebViewCloudflareSolver`) pasa de fallback condicional a **primario y siempre activo**, corriendo en la fase 2 (lenta) de la búsqueda progresiva ya existente. Se endurece el solver con caché de `cf_clearance` por host, se añaden espejos de dominio (`hostAlt`) y headers/cookies por proveedor, y se bundlean las definiciones de los trackers españoles/anime portadas del Cardigann de Jackett. El hot-update apunta a un GitHub raw en vez de a `blog`.

**Tech Stack:** Kotlin, Coroutines, OkHttp, Jsoup, Android WebView, JUnit4. Sin Hilt (grafo manual en `AppGraph`).

## Global Constraints

- **Ningún** request del path de torrents puede ir a `jackett.comparadorinternet.co` / `blog` al terminar el plan.
- La capa web (`web_sources.json`, `WebResolverApi`, `webResolverUrl`, `WebSourceEngine`) **NO se toca**: es otro branch.
- Identidad git del repo: `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. Commits **sin** línea de coautoría de Claude.
- Tests unitarios: `./gradlew testDebugUnitTest`. Una clase sola: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.NombreTest"`.
- TDD: test que falla → implementación mínima → test verde → commit. Commits frecuentes (uno por tarea).
- Branch de trabajo: `feat/torrents-ondevice-sin-servidor` (ya creado).
- Las definiciones de proveedores viven en `app/src/main/assets/providers.json`; su esquema lo define `ProviderDefinition.fromJson`.
- URL de hot-update por defecto: `https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json`.
- Cuando un selector de tracker no matchee HTML vivo tras esfuerzo razonable, dejar la entrada `"enabled": false` con `"_nota"` del motivo; **nunca** bloquear el resto.

---

## Mapa de archivos

**Modificados:**
- `app/src/main/java/com/arkiv/player/data/SettingsStore.kt` — quitar constantes/flows de Jackett; reapuntar `DEFAULT_PROVIDERS_URL`.
- `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt` — quitar `JackettBackend`, `HealthGate`, `healthGate`; tier de proveedores siempre activo.
- `app/src/main/java/com/arkiv/player/AppGraph.kt` — quitar wiring Jackett/health; pre-warm CF.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt` — caché CF + headers por request.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/CloudflareSolver.kt` — caché por host.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt` — `hostAlt`, `requestHeaders`.
- `app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt` — iterar `hostAlt`, pasar headers.
- `app/src/main/assets/providers.json` — definiciones portadas.

**Creados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/CfClearanceStore.kt` — caché TTL por host.
- `app/src/test/java/com/arkiv/player/data/catalog/providers/CfClearanceStoreTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/HttpFetcherCacheTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionHostAltTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/HostAltFallbackTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/NyaaDefinitionTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/EztvDefinitionTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/ElitetorrentDefinitionTest.kt`
- `app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt`

**Eliminados:**
- `app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt`

---

## FASE A — Cortar el servidor

### Task 1: Settings sin Jackett + hot-update a GitHub

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`
- Test: `app/src/test/java/com/arkiv/player/data/SettingsDefaultsTest.kt` (crear)

**Interfaces:**
- Produces: `SettingsStore.DEFAULT_PROVIDERS_URL` = GitHub raw. Se eliminan: `jackettBaseUrl`, `jackettApiKey`, `jackettHealthUrl` (flows), `setJackettBaseUrl/…`, `DEFAULT_JACKETT_URL/_KEY/_HEALTH_URL`, `KEY_JACKETT_URL/_KEY/_HEALTH_URL`.

- [ ] **Step 1: Test que fija el nuevo default (falla al compilar/aserción)**

Crear `app/src/test/java/com/arkiv/player/data/SettingsDefaultsTest.kt`:
```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    @Test fun `el providers url por defecto apunta a github raw, no a blog`() {
        assertEquals(
            "https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json",
            SettingsStore.DEFAULT_PROVIDERS_URL,
        )
        assertTrue(!SettingsStore.DEFAULT_PROVIDERS_URL.contains("comparadorinternet"))
    }
}
```

- [ ] **Step 2: Correr el test (debe fallar)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.SettingsDefaultsTest"`
Expected: FAIL (aserción: el default aún apunta a blog).

- [ ] **Step 3: Editar SettingsStore**

En `SettingsStore.kt`:
1. Borrar los bloques de `_jackettBaseUrl`, `_jackettApiKey`, `_jackettHealthUrl` (líneas de sus `MutableStateFlow`/`StateFlow`).
2. Borrar los setters `setJackettBaseUrl`, `setJackettApiKey`, `setJackettHealthUrl`.
3. En `companion object`, borrar `KEY_JACKETT_URL`, `KEY_JACKETT_KEY`, `KEY_HEALTH_URL`, `DEFAULT_JACKETT_URL`, `DEFAULT_JACKETT_KEY`, `DEFAULT_HEALTH_URL`.
4. Cambiar la constante de providers:
```kotlin
const val DEFAULT_PROVIDERS_URL = "https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json"
```
Dejar intactos: `providersUrl`, `webSourcesUrl`, `webResolverUrl`, `cloudflareSolverEnabled`, `maxTorrentSizeGb`, quality settings, y sus DEFAULT/KEY. (No tocar la capa web.)

- [ ] **Step 4: Correr el test (debe pasar) + compilar**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.SettingsDefaultsTest"`
Expected: PASS.
Nota: la app aún no compila completa porque `AppGraph`/`TorrentSearchApi` referencian lo borrado; se arregla en Tasks 2-3. No compilar el módulo entero todavía.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SettingsStore.kt app/src/test/java/com/arkiv/player/data/SettingsDefaultsTest.kt
git commit -m "feat(torrent): quitar settings de Jackett y reapuntar hot-update a GitHub raw"
```

---

### Task 2: TorrentSearchApi sin Jackett; tier de proveedores siempre activo

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt`
- Delete: `app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/TorrentFallbackGatingTest.kt` (reescribir → renombrar a `ProviderTierTest.kt`)

**Interfaces:**
- Consumes: `ProviderBackend.search(ctx: SearchContext): List<RawTorrent>`, `RawTorrent`, `SearchContext`.
- Produces: `TorrentSearchApi(backends: List<TorrentBackend>, providerBackends: List<ProviderBackend> = emptyList(), minResults: Int = 3)`. Sin `healthGate`. `providerBackends` corre SIEMPRE que haya `ctx`.

- [ ] **Step 1: Reescribir el test de gating con la nueva semántica**

Borrar `TorrentFallbackGatingTest.kt` y crear `app/src/test/java/com/arkiv/player/data/catalog/ProviderTierTest.kt`:
```kotlin
package com.arkiv.player.data.catalog

import com.arkiv.player.data.catalog.providers.ContentType
import com.arkiv.player.data.catalog.providers.ProviderBackend
import com.arkiv.player.data.catalog.providers.SearchContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTierTest {
    private fun rt(name: String) = RawTorrent(name = name, seeders = 10, sizeBytes = 1L shl 30,
        magnetUri = "magnet:?xt=urn:btih:${name.hashCode()}")

    private class FakeBackend(override val id: String, val out: List<RawTorrent>) : TorrentBackend {
        var called = false
        override suspend fun search(query: String): List<RawTorrent> { called = true; return out }
    }
    private class FakeProvider(override val id: String, val out: List<RawTorrent>) : ProviderBackend {
        var called = false
        override suspend fun search(ctx: SearchContext): List<RawTorrent> { called = true; return out }
    }

    @Test fun `el tier de proveedores corre SIEMPRE, aun con agregadores suficientes`() = runBlocking {
        val agg = FakeBackend("apibay", List(5) { rt("Coco 2017 v$it") })
        val prov = FakeProvider("prov", listOf(rt("Coco 2017 latino")))
        val api = TorrentSearchApi(listOf(agg), listOf(prov), minResults = 3)
        val res = api.searchMovie(listOf("Coco"), "2017", emptySet())
        assertTrue(agg.called)
        assertTrue(prov.called)              // clave: ya no es condicional
        assertTrue(res.any { it.name.contains("latino") })
    }

    @Test fun `sin agregadores, el tier de proveedores igual entrega resultados`() = runBlocking {
        val prov = FakeProvider("prov", listOf(rt("One Piece 1085 latino")))
        val api = TorrentSearchApi(emptyList(), listOf(prov), minResults = 3)
        val res = api.searchEpisode(listOf("One Piece"), 1, 5, emptySet())
        assertTrue(prov.called)
        assertTrue(res.isNotEmpty())
    }

    @Test fun `un proveedor que lanza no rompe la busqueda`() = runBlocking {
        val bueno = FakeProvider("bueno", listOf(rt("Coco 2017 bueno")))
        val malo = object : ProviderBackend {
            override val id = "malo"
            override suspend fun search(ctx: SearchContext): List<RawTorrent> = throw RuntimeException("boom")
        }
        val api = TorrentSearchApi(emptyList(), listOf(malo, bueno), minResults = 3)
        val res = api.searchMovie(listOf("Coco"), "2017", emptySet())
        assertTrue(res.any { it.name.contains("bueno") })
    }
}
```

- [ ] **Step 2: Correr (debe fallar por firma vieja)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.ProviderTierTest"`
Expected: FAIL de compilación (constructor con `healthGate`, `fallbackBackends`).

- [ ] **Step 3: Editar TorrentSearchApi**

En `TorrentSearchApi.kt`:
1. **Borrar** la clase `JackettBackend` completa (líneas del bloque `class JackettBackend(...) { ... }`).
2. Borrar los imports de `HealthGate`, `JackettStatus`.
3. Cambiar la firma del constructor:
```kotlin
class TorrentSearchApi(
    private val backends: List<TorrentBackend>,
    private val providerBackends: List<ProviderBackend> = emptyList(),
    private val minResults: Int = 3,
) {
```
4. En `runSearch`, reemplazar el bloque de status/activeBackends/needFallback por: los agregadores corren siempre y el tier de proveedores corre siempre que haya `ctx`:
```kotlin
val raw = cached?.raw ?: run {
    val primary = run {
        val jobs = distinct.flatMap { q ->
            backends.map { b -> async { runCatching { b.search(q) }.getOrDefault(emptyList()) } }
        }
        jobs.awaitAll().flatten()
    }
    val providers = if (ctx != null && providerBackends.isNotEmpty()) {
        val jobs = providerBackends.map { p -> async { runCatching { p.search(ctx) }.getOrDefault(emptyList()) } }
        jobs.awaitAll().flatten()
    } else emptyList()
    (primary + providers).also { if (it.isNotEmpty()) rawCache[cacheKey] = CacheEntry(it, now) }
}
```
   (La variable `minResults` deja de usarse en `runSearch`; se conserva el parámetro para `runSearchFlow`, ver abajo. Si el linter marca `useful`/`minResults` sin uso en `runSearch`, borrar solo lo que quede muerto ahí.)
5. En `runSearchFlow`, reemplazar el bloque de status/activeBackends: `fast` = `backends` en `fastBackendIds`, `slow` = `backends` fuera de ellos, y el tier de proveedores corre SIEMPRE en fase 2:
```kotlin
val fast = backends.filter { it.id in fastBackendIds }
val slow = backends.filterNot { it.id in fastBackendIds }

coroutineScope {
    val fastJobs = distinct.flatMap { q -> fast.map { b -> async { runCatching { b.search(q) }.getOrDefault(emptyList()) } } }
    val slowJobs = distinct.flatMap { q -> slow.map { b -> async { runCatching { b.search(q) }.getOrDefault(emptyList()) } } }

    val fastRaw = fastJobs.awaitAll().flatten()
    val phase1 = finalize(fastRaw, langs, relevance, maxSizeBytes, langPriority)
    if (phase1.isNotEmpty()) send(phase1)
    val seen = phase1.map { it.dedupKey }.toMutableSet()

    val slowRaw = slowJobs.awaitAll().flatten()
    val providerRaw = if (ctx != null && providerBackends.isNotEmpty())
        providerBackends.map { p -> async { runCatching { p.search(ctx) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    else emptyList()

    val allRaw = fastRaw + slowRaw + providerRaw
    if (allRaw.isNotEmpty()) rawCache[cacheKey] = CacheEntry(allRaw, now)
    val newOnes = finalize(allRaw, langs, relevance, maxSizeBytes, langPriority).filter { it.dedupKey !in seen }
    if (newOnes.isNotEmpty()) send(newOnes)
}
```
6. Renombrar toda referencia interna de `fallbackBackends` → `providerBackends`.
7. Borrar el archivo `JackettHealth.kt`:
```bash
git rm app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt
```
   (Esto elimina `JackettStatus`, `HealthGate`, `JackettHealth`. Verificar que nada más los importe: `git grep -n "JackettStatus\|HealthGate\|JackettHealth"` debe salir vacío tras editar AppGraph en Task 3.)

- [ ] **Step 4: Correr el test (debe pasar)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.ProviderTierTest"`
Expected: PASS. (AppGraph aún roto; se arregla en Task 3.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/TorrentSearchApi.kt app/src/test/java/com/arkiv/player/data/catalog/ProviderTierTest.kt
git rm app/src/main/java/com/arkiv/player/data/catalog/providers/JackettHealth.kt app/src/test/java/com/arkiv/player/data/catalog/TorrentFallbackGatingTest.kt
git commit -m "feat(torrent): quitar JackettBackend/HealthGate; tier on-device siempre activo"
```

---

### Task 3: AppGraph — quitar wiring de Jackett/health

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `TorrentSearchApi(backends, providerBackends, minResults)` (Task 2); `RegistryProviderBackend` (existente).

- [ ] **Step 1: Editar AppGraph**

1. Borrar imports: `JackettBackend`, `JackettHealth`, `HealthGate`.
2. Borrar la línea `private val jackettHealth: HealthGate by lazy { JackettHealth(...) }`.
3. Reemplazar el bloque `torrentSearchApi`:
```kotlin
val torrentSearchApi: TorrentSearchApi by lazy {
    TorrentSearchApi(
        backends = listOf(ApibayBackend(), KnabenBackend()),
        providerBackends = listOf(registryProviderBackend),
        minResults = 3,
    )
}
```
4. En `refreshRemoteProviders()` no hay cambio de código (ya usa `settings.providersUrl.value`, que ahora es el GitHub raw). Verificar que sigue igual.

- [ ] **Step 2: Compilar el módulo entero**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Si falla por referencias residuales a Jackett/health, borrarlas.

- [ ] **Step 3: Correr toda la suite (regresión)**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. (Los tests de `TorrentSearchApi` de clasificación/ranking/cache deben seguir verdes.)

- [ ] **Step 4: Verificar que no queda rastro de blog en el path de torrents**

Run: `git grep -n "comparadorinternet" app/src/main/java | grep -iv "web_sources\|webResolver\|webSourcesUrl"`
Expected: sin líneas de Jackett/health/providers (solo, si acaso, referencias de la capa web que NO tocamos).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(torrent): AppGraph sin Jackett/health; agregadores + tier on-device"
```

---

## FASE B — Endurecer Cloudflare on-device

### Task 4: CfClearanceStore (caché TTL por host)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/providers/CfClearanceStore.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/CfClearanceStoreTest.kt`

**Interfaces:**
- Consumes: `CfClearance(cookies: String, userAgent: String)` (ya existe en `CloudflareSolver.kt`).
- Produces:
  - `class CfClearanceStore(val ttlMs: Long = 45 * 60 * 1000L, val nowMs: () -> Long = { System.currentTimeMillis() })`
  - `fun get(host: String): CfClearance?` — devuelve la clearance vigente (dentro de TTL) o null.
  - `fun put(host: String, clearance: CfClearance)`
  - `fun hostOf(url: String): String?` — host en minúsculas o null si la URL es inválida.

- [ ] **Step 1: Test (falla: clase no existe)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CfClearanceStoreTest {
    private fun cl(v: String) = CfClearance(cookies = "cf_clearance=$v", userAgent = "UA")

    @Test fun `devuelve la clearance dentro del TTL y null tras expirar`() {
        var t = 1000L
        val store = CfClearanceStore(ttlMs = 100L, nowMs = { t })
        store.put("wolfmax4k.com", cl("abc"))
        assertEquals("cf_clearance=abc", store.get("wolfmax4k.com")?.cookies)
        t = 1099L
        assertEquals("cf_clearance=abc", store.get("wolfmax4k.com")?.cookies)  // dentro
        t = 1101L
        assertNull(store.get("wolfmax4k.com"))                                 // expiró
    }

    @Test fun `separa por host`() {
        val store = CfClearanceStore(ttlMs = 10_000L, nowMs = { 0L })
        store.put("a.com", cl("A"))
        assertNull(store.get("b.com"))
    }

    @Test fun `hostOf extrae el host o null`() {
        val store = CfClearanceStore()
        assertEquals("wolfmax4k.com", store.hostOf("https://wolfmax4k.com/buscar?q=x"))
        assertNull(store.hostOf("no-es-url"))
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.CfClearanceStoreTest"`
Expected: FAIL (unresolved `CfClearanceStore`).

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.catalog.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * Caché en memoria de cookies `cf_clearance` por host, con TTL. Replica lo que hace el server (Jackett
 * guarda el cf_clearance por indexer) para no relanzar el WebView en cada búsqueda al mismo host.
 * Puro y testeable: reloj inyectable.
 */
class CfClearanceStore(
    private val ttlMs: Long = 45 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val clearance: CfClearance, val atMs: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(host: String): CfClearance? {
        val e = map[host] ?: return null
        if (nowMs() - e.atMs > ttlMs) { map.remove(host); return null }
        return e.clearance
    }

    fun put(host: String, clearance: CfClearance) {
        map[host] = Entry(clearance, nowMs())
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URL(url).host?.lowercase() }.getOrNull()?.ifBlank { null }
}
```

- [ ] **Step 4: Correr (pasa)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.CfClearanceStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/CfClearanceStore.kt app/src/test/java/com/arkiv/player/data/catalog/providers/CfClearanceStoreTest.kt
git commit -m "feat(torrent): CfClearanceStore (cache TTL de cf_clearance por host)"
```

---

### Task 5: Cablear la caché en HttpFetcher + solver

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/CloudflareSolver.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HttpFetcherCacheTest.kt`

**Interfaces:**
- Consumes: `CfClearanceStore` (Task 4), `PageFetcher.fetch(url, charset)`.
- Produces:
  - `HttpFetcher(client, solver, store: CfClearanceStore = CfClearanceStore())` — antes del primer GET usa `store.get(host)` como cookie; tras resolver, la petición usa la cookie del solver.
  - `WebViewCloudflareSolver(context, enabled, timeoutMs, store: CfClearanceStore)` — en `solve(url)` primero mira `store.get(host)`; si hay, la devuelve sin WebView; al resolver, `store.put(host, clearance)`.

- [ ] **Step 1: Test del reuso de caché en HttpFetcher (sin WebView)**

```kotlin
package com.arkiv.player.data.catalog.providers

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpFetcherCacheTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    // Solver espía: cuenta cuántas veces se invoca (queremos 0 cuando hay cookie cacheada válida).
    private class SpySolver(val clearance: CfClearance?) : CloudflareSolver {
        var calls = 0
        override suspend fun solve(url: String): CfClearance? { calls++; return clearance }
    }

    @Test fun `con clearance cacheada, el primer GET la usa y NO invoca al solver`() = runBlocking {
        // El servidor responde 200 normal cuando llega la cookie; el fetcher debe usar la cacheada.
        server.enqueue(MockResponse().setBody("<html>ok</html>").setResponseCode(200))
        val host = server.hostName
        val store = CfClearanceStore(ttlMs = 60_000L, nowMs = { 0L })
        store.put(host, CfClearance(cookies = "cf_clearance=cached", userAgent = "UA"))
        val solver = SpySolver(null)
        val fetcher = HttpFetcher(solver = solver, store = store)

        val body = fetcher.fetch(server.url("/s/coco").toString(), "utf-8")
        assertEquals("<html>ok</html>", body)
        assertEquals(0, solver.calls)                                   // no relanzó WebView
        val sent = server.takeRequest()
        assertTrue(sent.getHeader("Cookie")?.contains("cf_clearance=cached") == true)
    }

    @Test fun `ante challenge sin cache, invoca al solver y guarda la clearance`() = runBlocking {
        server.enqueue(MockResponse().setBody("Just a moment...").setResponseCode(503)) // challenge
        server.enqueue(MockResponse().setBody("<html>ok</html>").setResponseCode(200))  // reintento ok
        val host = server.hostName
        val store = CfClearanceStore(ttlMs = 60_000L, nowMs = { 0L })
        val solver = SpySolver(CfClearance(cookies = "cf_clearance=fresh", userAgent = "UA2"))
        val fetcher = HttpFetcher(solver = solver, store = store)

        val body = fetcher.fetch(server.url("/s/coco").toString(), "utf-8")
        assertEquals("<html>ok</html>", body)
        assertEquals(1, solver.calls)
        // El fetcher persiste la clearance para el próximo request al mismo host.
        assertEquals("cf_clearance=fresh", store.get(host)?.cookies)
    }
}
```
Nota: `okhttp3.mockwebserver` ya está disponible como dependencia de test en proyectos con OkHttp; si no, añadir `testImplementation("com.squareup.okhttp3:mockwebserver")` en `app/build.gradle.kts` con la misma versión de OkHttp del proyecto, como paso previo.

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HttpFetcherCacheTest"`
Expected: FAIL (constructor de `HttpFetcher` no acepta `store`).

- [ ] **Step 3: Editar HttpFetcher**

```kotlin
class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val solver: CloudflareSolver = NoopCloudflareSolver,
    private val store: CfClearanceStore = CfClearanceStore(),
) : PageFetcher {

    override suspend fun fetch(url: String, charset: String): String? = withContext(Dispatchers.IO) {
        val host = store.hostOf(url)
        val cached = host?.let { store.get(it) }
        val first = get(url, charset, cookie = cached?.cookies, ua = cached?.userAgent ?: BROWSER_UA)
        val isChallenge = first != null && looksLikeCloudflareChallenge(first.code, first.body)
        if (first != null && !isChallenge) return@withContext first.body?.takeIf { first.code in 200..299 }
        if (!isChallenge) return@withContext null
        val clearance = runCatching { solver.solve(url) }.getOrNull() ?: return@withContext null
        if (host != null) store.put(host, clearance)
        val second = get(url, charset, cookie = clearance.cookies, ua = clearance.userAgent)
        second?.body?.takeIf { second.code in 200..299 }
    }
    // ... get(...) y Resp igual
}
```

- [ ] **Step 4: Editar WebViewCloudflareSolver (short-circuit + persistencia)**

En `CloudflareSolver.kt`, la clase `WebViewCloudflareSolver` recibe el store y lo consulta/actualiza:
```kotlin
class WebViewCloudflareSolver(
    context: Context,
    private val enabled: () -> Boolean,
    private val timeoutMs: Long = 20_000,
    private val store: CfClearanceStore = CfClearanceStore(),
) : CloudflareSolver {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(url: String): CfClearance? {
        if (!enabled()) return null
        val host = store.hostOf(url)
        host?.let { store.get(it) }?.let { return it }        // short-circuit: ya resuelto
        return mutex.withLock {
            host?.let { store.get(it) }?.let { return@withLock it }  // pudo resolverlo otro mientras esperaba el lock
            val result = withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Main) { solveOnMain(url) } }
            if (result != null && host != null) store.put(host, result)
            result
        }
    }
    // ... solveOnMain igual
}
```

- [ ] **Step 5: Correr (pasa)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HttpFetcherCacheTest"`
Expected: PASS.

- [ ] **Step 6: Compilar + suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt app/src/main/java/com/arkiv/player/data/catalog/providers/CloudflareSolver.kt app/src/test/java/com/arkiv/player/data/catalog/providers/HttpFetcherCacheTest.kt
git commit -m "feat(torrent): reusar cf_clearance cacheado en HttpFetcher y solver"
```

---

### Task 6: Compartir un solo store y pre-warm de hosts Cloudflare

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `CfClearanceStore` (Task 4), `WebViewCloudflareSolver(..., store)` (Task 5), `HttpFetcher(..., store)` (Task 5), `ProviderDefinition.needsCloudflare`, `ProviderDefinition.baseUrl`.

- [ ] **Step 1: Un store único inyectado a solver y fetcher**

En `AppGraph.kt`, en el bloque de proveedores:
```kotlin
private val cfStore by lazy { CfClearanceStore() }
private val cloudflareSolver by lazy {
    WebViewCloudflareSolver(appContext, enabled = { settings.cloudflareSolverEnabled.value }, store = cfStore)
}
private val providerFetcher by lazy { HttpFetcher(solver = cloudflareSolver, store = cfStore) }
```
Añadir import `com.arkiv.player.data.catalog.providers.CfClearanceStore`.

- [ ] **Step 2: Pre-warm best-effort al iniciar**

Añadir un método y llamarlo desde el scope de app existente (el mismo `CoroutineScope(SupervisorJob()+Dispatchers…)` que ya usa AppGraph para `refreshRemoteProviders`). Buscar dónde se lanza `refreshRemoteProviders()` y añadir junto a él:
```kotlin
/** Best-effort: calienta el cf_clearance de los hosts marcados needsCloudflare para no pagar el WebView en el camino crítico. */
private fun prewarmCloudflareHosts() {
    if (!settings.cloudflareSolverEnabled.value) return
    providerDefinitions.filter { it.enabled && it.needsCloudflare }.forEach { def ->
        appScope.launch { runCatching { cloudflareSolver.solve(def.baseUrl) } }
    }
}
```
(Usar el nombre real del scope de AppGraph; si el bloque de init lanza `refreshRemoteProviders()` dentro de un `scope.launch { }`, encadenar `prewarmCloudflareHosts()` después del refresh para calentar también los hosts que llegaron por hot-update.)

- [ ] **Step 3: Compilar + suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (pre-warm no tiene unit test; es best-effort de runtime).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(torrent): store CF compartido + pre-warm de hosts Cloudflare"
```

---

## FASE C — Definiciones de proveedores

### Task 7: ProviderDefinition con hostAlt y requestHeaders

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionHostAltTest.kt`

**Interfaces:**
- Produces: `ProviderDefinition` gana `val hostAlt: List<String>` (default `emptyList()`) y `val requestHeaders: Map<String, String>` (default `emptyMap()`), ambos parseados en `fromJson`.

- [ ] **Step 1: Test**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDefinitionHostAltTest {
    private fun def(json: String) = ProviderDefinition.fromJson(JSONObject(json))

    @Test fun `parsea hostAlt y requestHeaders`() {
        val d = def("""
            {"id":"eztv","baseUrl":"https://eztvx.to","searchPath":"/search/{query}",
             "hostAlt":["https://eztv.wf","https://eztv.tf"],
             "requestHeaders":{"Cookie":"layout=def_wlinks"},
             "keywords":{"tv":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a.m","attr":"href"}}}
        """.trimIndent())!!
        assertEquals(listOf("https://eztv.wf", "https://eztv.tf"), d.hostAlt)
        assertEquals("layout=def_wlinks", d.requestHeaders["Cookie"])
    }

    @Test fun `defaults vacios cuando faltan`() {
        val d = def("""
            {"id":"x","baseUrl":"https://x.org","searchPath":"/s/{query}",
             "keywords":{"movie":"{title}"},
             "parser":{"rowSelector":"tr","name":{"selector":"a","attr":"text"},
               "magnet":{"selector":"a.m","attr":"href"}}}
        """.trimIndent())!!
        assertEquals(emptyList<String>(), d.hostAlt)
        assertEquals(emptyMap<String, String>(), d.requestHeaders)
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionHostAltTest"`
Expected: FAIL (no existe `hostAlt`).

- [ ] **Step 3: Implementar**

En `ProviderDefinition` (data class) añadir campos (antes de `parser`/`detail` o al final, respetando orden con defaults):
```kotlin
val hostAlt: List<String> = emptyList(),
val requestHeaders: Map<String, String> = emptyMap(),
```
En `fromJson`, antes de construir el objeto:
```kotlin
val hostAltJson = o.optJSONArray("hostAlt")
val hostAlt = if (hostAltJson == null) emptyList()
    else (0 until hostAltJson.length()).map { hostAltJson.getString(it).trimEnd('/') }
        .filter { it.startsWith("http") }
val headersJson = o.optJSONObject("requestHeaders")
val requestHeaders = headersJson?.keys()?.asSequence()?.associateWith { headersJson.getString(it) } ?: emptyMap()
```
Y pasarlos al constructor: `hostAlt = hostAlt, requestHeaders = requestHeaders,`.

- [ ] **Step 4: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.ProviderDefinitionHostAltTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (los tests existentes de `ProviderDefinition` siguen verdes; solo se agregaron campos con default).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/ProviderDefinition.kt app/src/test/java/com/arkiv/player/data/catalog/providers/ProviderDefinitionHostAltTest.kt
git commit -m "feat(torrent): ProviderDefinition con hostAlt y requestHeaders"
```

---

### Task 8: DeclarativeHtmlBackend — iterar hostAlt y enviar headers

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt` (firma de `PageFetcher.fetch`)
- Modify: `app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/HostAltFallbackTest.kt`

**Interfaces:**
- Produces: `PageFetcher.fetch(url: String, charset: String, headers: Map<String, String> = emptyMap()): String?`. `DeclarativeHtmlBackend` intenta `[baseUrl] + hostAlt` hasta obtener filas; usa `def.requestHeaders` en cada fetch.

- [ ] **Step 1: Test (fetcher fake que solo responde en el 2º host)**

```kotlin
package com.arkiv.player.data.catalog.providers

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAltFallbackTest {
    private val def = ProviderDefinition.fromJson(JSONObject("""
        {"id":"m","baseUrl":"https://dead.example","searchPath":"/s/{query}",
         "hostAlt":["https://mirror.example"],
         "requestHeaders":{"Cookie":"layout=def_wlinks"},
         "keywords":{"movie":"{title}"},
         "parser":{"rowSelector":"tr.r","name":{"selector":"a.t","attr":"text"},
           "magnet":{"selector":"a.m","attr":"href"}}}
    """.trimIndent()))!!

    @Test fun `si baseUrl no da filas, reintenta con hostAlt y usa los headers`() = runBlocking {
        val seenHeaders = mutableListOf<Map<String, String>>()
        val fetcher = object : PageFetcher {
            override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? {
                seenHeaders += headers
                return if (url.startsWith("https://mirror.example"))
                    """<table><tr class="r"><td><a class="t">Coco</a></td>
                       <td><a class="m" href="magnet:?xt=urn:btih:AA">m</a></td></tr></table>"""
                else ""   // baseUrl "muerto": HTML sin filas
            }
        }
        val out = DeclarativeHtmlBackend(def, fetcher).search(SearchContext(listOf("Coco"), ContentType.MOVIE))
        assertEquals(1, out.size)
        assertEquals("Coco", out[0].name)
        assertTrue(seenHeaders.all { it["Cookie"] == "layout=def_wlinks" })
    }
}
```

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HostAltFallbackTest"`
Expected: FAIL (firma de `fetch` sin `headers`).

- [ ] **Step 3: Ampliar PageFetcher.fetch con headers**

En `HttpFetcher.kt`:
```kotlin
interface PageFetcher {
    suspend fun fetch(url: String, charset: String, headers: Map<String, String> = emptyMap()): String?
}
```
En `HttpFetcher.fetch`, propagar `headers` a ambos `get(...)`, y en `get(...)` aplicarlos:
```kotlin
override suspend fun fetch(url: String, charset: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
    val host = store.hostOf(url)
    val cached = host?.let { store.get(it) }
    val first = get(url, charset, cookie = cached?.cookies, ua = cached?.userAgent ?: BROWSER_UA, extra = headers)
    val isChallenge = first != null && looksLikeCloudflareChallenge(first.code, first.body)
    if (first != null && !isChallenge) return@withContext first.body?.takeIf { first.code in 200..299 }
    if (!isChallenge) return@withContext null
    val clearance = runCatching { solver.solve(url) }.getOrNull() ?: return@withContext null
    if (host != null) store.put(host, clearance)
    val second = get(url, charset, cookie = clearance.cookies, ua = clearance.userAgent, extra = headers)
    second?.body?.takeIf { second.code in 200..299 }
}

private fun get(url: String, charset: String, cookie: String?, ua: String, extra: Map<String, String> = emptyMap()): Resp? = runCatching {
    val req = Request.Builder().url(url).header("User-Agent", ua)
        .header("Accept", "text/html,application/xhtml+xml,*/*")
        .apply {
            // Cookie de la clearance + cookies del proveedor (ej. eztv layout=def_wlinks) se combinan.
            val provCookie = extra["Cookie"]
            val merged = listOfNotNull(cookie, provCookie).joinToString("; ").ifBlank { null }
            if (merged != null) header("Cookie", merged)
            extra.forEach { (k, v) -> if (!k.equals("Cookie", true)) header(k, v) }
        }
        .build()
    client.newCall(req).execute().use { resp ->
        val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        val bytes = resp.body?.bytes()
        Resp(resp.code, bytes?.toString(cs))
    }
}.onFailure { Log.w("ArkivProv", "fetch fail $url: $it") }.getOrNull()
```
Actualizar el `WebSourceBackend`/`WebJsonBackend` u otros consumidores de `PageFetcher.fetch` solo si el compilador lo exige (el nuevo parámetro tiene default, así que las llamadas existentes siguen válidas).

- [ ] **Step 4: DeclarativeHtmlBackend itera hostAlt**

En `DeclarativeHtmlBackend.kt`, reemplazar `searchOne`:
```kotlin
private suspend fun searchOne(query: String): List<RawTorrent> {
    val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    val path = def.searchPath.replace("{query}", encoded)
    val bases = listOf(def.baseUrl) + def.hostAlt
    for (base in bases) {
        val url = base.trimEnd('/') + path
        val html = fetcher.fetch(url, def.charset, def.requestHeaders) ?: continue
        val rows = HtmlParser.parseList(effectiveDef(base), html, url)
        runCatching { Log.i("ArkivProv", "provider=${def.id} base=$base q='$query' filas=${rows.size}") }
        if (rows.isEmpty()) continue
        val detail = def.detail ?: return rows
        return withContext(Dispatchers.IO) {
            rows.map { r ->
                if (r.magnetUri != null || r.infoHash != null || r.downloadUrl == null) r
                else resolveDetail(r, detail, base) ?: r
            }
        }
    }
    return emptyList()
}

/** Copia de la def con baseUrl = el host efectivo (para que HtmlParser resuelva URLs relativas bien). */
private fun effectiveDef(base: String): ProviderDefinition =
    if (base == def.baseUrl) def else def.copy(baseUrl = base.trimEnd('/'))
```
Y `resolveDetail` recibe `base` para usar `def.requestHeaders` y resolver con el host correcto:
```kotlin
private suspend fun resolveDetail(row: RawTorrent, detail: DetailRules, base: String): RawTorrent? {
    val page = row.downloadUrl ?: return null
    val html = fetcher.fetch(page, def.charset, def.requestHeaders) ?: return null
    val doc = runCatching { org.jsoup.Jsoup.parse(html, base.trimEnd('/')) }.getOrNull() ?: return null
    val magnet = detail.magnet?.let { HtmlParser.applyRule(doc, it, base.trimEnd('/')) }?.takeIf { it.startsWith("magnet:") }
    if (magnet != null) return row.copy(magnetUri = magnet)
    val infohash = detail.infohash?.let { HtmlParser.applyRule(doc, it, base.trimEnd('/')) }?.takeIf { INFOHASH_REGEX.matches(it) }
    if (infohash != null) return row.copy(infoHash = infohash)
    return null
}
```
(`HtmlParser.applyRule(row: Element, ...)` acepta un `Element`; `Jsoup.parse(...)` devuelve un `Document`, que ES un `Element`, así que la llamada compila igual que hoy.)

- [ ] **Step 5: Correr (pasa) + suite**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.HostAltFallbackTest"`
Expected: PASS. Luego `./gradlew testDebugUnitTest` → PASS (el `DeclarativeHtmlBackendTest` existente sigue verde: el default de `headers` no cambia su comportamiento).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/providers/HttpFetcher.kt app/src/main/java/com/arkiv/player/data/catalog/providers/DeclarativeHtmlBackend.kt app/src/test/java/com/arkiv/player/data/catalog/providers/HostAltFallbackTest.kt
git commit -m "feat(torrent): DeclarativeHtmlBackend con fallback de hostAlt y headers por proveedor"
```

---

### Task 9: Portar nyaa (anime, sin Cloudflare) — ejemplo completo

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/NyaaDefinitionTest.kt`

**Selectores (del Cardigann `nyaasi.yml`):** rows `tr.default,tr.danger,tr.success`; name `td:nth-child(2) a:last-of-type`; magnet `td:nth-child(3) a[href^="magnet:?"]`; size `td:nth-child(4)`; seeders `td:nth-child(6)`. Búsqueda: `/?q={query}&s=seeders&o=desc&f=0&c=0_0`.

- [ ] **Step 1: Fixture test con HTML real de nyaa**

Bajar una muestra real:
```bash
curl -s -A "Mozilla/5.0 (Linux; Android 14)" "https://nyaa.si/?q=one+piece+1085&s=seeders&o=desc" -o /tmp/nyaa.html
```
Copiar 2-3 filas `<tr class="success">…</tr>` representativas al test (recortadas pero con la estructura real de columnas). Crear `NyaaDefinitionTest.kt`:
```kotlin
package com.arkiv.player.data.catalog.providers

import org.junit.Assert.assertTrue
import org.junit.Test

class NyaaDefinitionTest {
    // Definición leída del bundled providers.json por id (helper compartido).
    private val def = BundledProviders.byId("nyaa")

    // HTML recortado de una fila real de nyaa.si (pegar desde /tmp/nyaa.html).
    private val html = """
        <table class="torrent-list"><tbody>
        <tr class="success">
          <td><a href="/?c=1_2">Anime</a></td>
          <td><a href="/view/1" title="[Grupo] One Piece 1085 [1080p]">[Grupo] One Piece 1085 [1080p]</a></td>
          <td class="text-center">
            <a href="/download/1.torrent"></a>
            <a href="magnet:?xt=urn:btih:AAAABBBBCCCCDDDDEEEEFFFF0000111122223333"></a>
          </td>
          <td class="text-center">1.3 GiB</td>
          <td class="text-center">2024-01-01</td>
          <td class="text-center">120</td>
          <td class="text-center">3</td>
          <td class="text-center">500</td>
        </tr></tbody></table>
    """.trimIndent()

    @Test fun `parsea nombre, magnet, seeds y size de nyaa`() {
        val rows = HtmlParser.parseList(def, html, def.baseUrl)
        assertTrue(rows.isNotEmpty())
        val r = rows.first { it.name.contains("One Piece 1085") }
        assertTrue(r.magnetUri!!.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(r.seeders >= 100)
        assertTrue(r.sizeBytes > 1_000_000_000L)   // ~1.3 GiB
    }
}
```
Crear el helper `app/src/test/java/com/arkiv/player/data/catalog/providers/BundledProviders.kt` (una vez):
```kotlin
package com.arkiv.player.data.catalog.providers

import java.io.File

/** Lee el providers.json bundled desde src/main/assets para tests JVM (sin Android assets). */
object BundledProviders {
    private val all: List<ProviderDefinition> by lazy {
        val f = File("src/main/assets/providers.json")
        ProviderRegistry.parseDefinitions(f.readText())
    }
    fun byId(id: String): ProviderDefinition = all.first { it.id == id }
}
```
Nota: el working dir de los tests JVM es el módulo `app/`, así que `src/main/assets/providers.json` resuelve. Si en tu setup no, usar ruta absoluta del repo.

- [ ] **Step 2: Correr (falla: no existe la def "nyaa")**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.NyaaDefinitionTest"`
Expected: FAIL (`byId("nyaa")` lanza: aún no está en providers.json).

- [ ] **Step 3: Añadir nyaa a providers.json**

Agregar al array de `app/src/main/assets/providers.json`:
```json
{
  "id": "nyaa",
  "name": "Nyaa",
  "enabled": true,
  "priority": 70,
  "baseUrl": "https://nyaa.si",
  "hostAlt": ["https://nyaa.land"],
  "searchPath": "/?q={query}&s=seeders&o=desc&f=0&c=0_0",
  "needsCloudflare": false,
  "keywords": {
    "anime": "{title} {episode_abs:2}",
    "tv": "{title} {episode_abs:2}"
  },
  "languageTokens": [],
  "parser": {
    "rowSelector": "tr.default,tr.danger,tr.success",
    "name": { "selector": "td:nth-child(2) a:last-of-type", "attr": "text" },
    "magnet": { "selector": "td:nth-child(3) a[href^=magnet]", "attr": "href" },
    "torrentUrl": { "selector": "td:nth-child(3) a[href$=.torrent]", "attr": "href", "resolve": "absolute" },
    "seeds": { "selector": "td:nth-child(6)", "attr": "text", "regex": "\\d+" },
    "size": { "selector": "td:nth-child(4)", "attr": "text" }
  }
}
```

- [ ] **Step 4: Correr (pasa)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.NyaaDefinitionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/NyaaDefinitionTest.kt app/src/test/java/com/arkiv/player/data/catalog/providers/BundledProviders.kt
git commit -m "feat(torrent): portar nyaa (anime) a definición on-device bundled"
```

---

### Task 10: Portar eztv (Cloudflare + cookie header + hostAlt)

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/EztvDefinitionTest.kt`

**Del Cardigann `eztv.yml`:** search path `search/{query}` (espacios→`-`, quitar `&` y `-`); cookie `layout=def_wlinks` (necesaria para que aparezcan los magnets); rows con `a.magnet`; title `td:nth-child(2) a` attr `title`; download `td:nth-child(3) a.magnet`; size `td:nth-child(4)`; seeders `td:nth-child(6)`. Espejos (`links`): eztvx.to, eztv.wf, eztv.tf, eztv.yt, eztv1.xyz.

- [ ] **Step 1: Fixture test**

Bajar muestra:
```bash
curl -s -A "Mozilla/5.0 (Linux; Android 14)" -H "Cookie: layout=def_wlinks; sort_no=100" "https://eztvx.to/search/breaking-bad" -o /tmp/eztv.html
```
Crear `EztvDefinitionTest.kt` con 1-2 filas reales (recortadas) y aserciones de name/magnet/seeds, patrón idéntico a `NyaaDefinitionTest` (usar `BundledProviders.byId("eztv")`). Verificar que `def.requestHeaders["Cookie"]` contiene `layout=def_wlinks` y que `def.hostAlt` no está vacío.

- [ ] **Step 2: Correr (falla)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.EztvDefinitionTest"`
Expected: FAIL.

- [ ] **Step 3: Añadir eztv a providers.json**

```json
{
  "id": "eztv",
  "name": "EZTV",
  "enabled": true,
  "priority": 55,
  "baseUrl": "https://eztvx.to",
  "hostAlt": ["https://eztv.wf", "https://eztv.tf", "https://eztv.yt", "https://eztv1.xyz"],
  "searchPath": "/search/{query}",
  "needsCloudflare": true,
  "requestHeaders": { "Cookie": "layout=def_wlinks; sort_no=100" },
  "keywords": { "tv": "{title} S{season:2}E{episode:2}" },
  "languageTokens": [],
  "parser": {
    "rowSelector": "tr[name=hover]:has(a.magnet)",
    "name": { "selector": "td:nth-child(2) a", "attr": "title" },
    "magnet": { "selector": "td:nth-child(3) a.magnet", "attr": "href" },
    "seeds": { "selector": "td:nth-child(6)", "attr": "text", "regex": "\\d+" },
    "size": { "selector": "td:nth-child(4)", "attr": "text" }
  }
}
```
Nota: eztv usa espacios en `{title}`; el sitio tolera `+`/`%20` en `/search/`. Si en validación con HTML vivo el guionado importa (el Cardigann reemplaza espacios por `-`), ajustar el template a `{title}` y verificar; documentar en `_nota` si un espejo requiere otro formato.

- [ ] **Step 4: Validar contra HTML vivo en device (dump)**

Usar el mecanismo `WebDiag` existente (`dumpProviderHtml` en `AppGraph`) para volcar el HTML real de eztv desde el dispositivo y confirmar que `rowSelector` matchea filas con `a.magnet`. Si el WebView no pasa Cloudflare o no aparecen magnets, revisar el cookie header. Si irresoluble: `"enabled": false` + `"_nota"`.

- [ ] **Step 5: Correr (pasa) + commit**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.EztvDefinitionTest"`
Expected: PASS.
```bash
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/EztvDefinitionTest.kt
git commit -m "feat(torrent): portar eztv (CF + cookie header + espejos) on-device"
```

---

### Task 11: Portar elitetorrent (español, GET ?s=)

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/ElitetorrentDefinitionTest.kt`

**Del Cardigann `elitetorrent-wf.yml`:** búsqueda por formulario (inputs `s`,`x`,`y`) → GET `/?s={query}&x=0&y=0`; rows `#principal .miniboxs-ficha li:has(span:nth-of-type(2))`; title `div.imagen > a` attr `title`; download/details `.meta a` attr `href` (página de detalle → magnet `a[href^="magnet:?"]`); sin seeders reales (usar default). Idioma es-ES.

- [ ] **Step 1: Fixture test (listado + detalle)**

Bajar muestras:
```bash
curl -s -A "Mozilla/5.0 (Linux; Android 14)" "https://www.elitetorrent.wf/?s=coco" -o /tmp/elite_list.html
# abrir un enlace .meta a del listado y bajar la página de detalle → /tmp/elite_detail.html
```
Crear `ElitetorrentDefinitionTest.kt`: probar `HtmlParser.parseList` sobre el listado (name + torrentUrl no vacío) y, con un `DeclarativeHtmlBackend` + fetcher fake de 2 páginas (listado y detalle con `a[href^=magnet]`), que el magnet se resuelve vía `detail`. Patrón: `DeclarativeHtmlBackendTest.resuelve el magnet desde la pagina de detalle`.

- [ ] **Step 2: Correr (falla) → Step 3: añadir a providers.json**

```json
{
  "id": "elitetorrent",
  "name": "EliteTorrent",
  "enabled": true,
  "priority": 65,
  "baseUrl": "https://www.elitetorrent.wf",
  "hostAlt": [],
  "searchPath": "/?s={query}&x=0&y=0",
  "needsCloudflare": false,
  "charset": "utf-8",
  "keywords": {
    "movie": "{title}",
    "tv": "{title}"
  },
  "languageTokens": ["latino", "castellano"],
  "parser": {
    "rowSelector": "#principal .miniboxs-ficha li:has(span:nth-of-type(2))",
    "name": { "selector": "div.imagen > a", "attr": "title" },
    "torrentUrl": { "selector": ".meta a", "attr": "href", "resolve": "absolute" },
    "size": { "selector": ".voto1", "attr": "text" }
  },
  "detail": {
    "followFrom": "torrentUrl",
    "magnet": { "selector": "a[href^=magnet]", "attr": "href" }
  }
}
```

- [ ] **Step 4: Validar HTML vivo (dump) + Step 5: correr (pasa) + commit**

Confirmar selectores con dump del device; ajustar o `enabled:false`+`_nota` si el sitio cambió.
```bash
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/ElitetorrentDefinitionTest.kt
git commit -m "feat(torrent): portar elitetorrent (español) on-device"
```

---

### Task 12: Portar wolfmax4k, dontorrent, divxtotal (derivar de HTML vivo)

Estas tres NO tienen Cardigann `.yml` en el contenedor; se derivan del **repo público de Jackett**
(buscar `wolfmax4k.yml` / `dontorrent.yml` / `divxtotal.yml` en `github.com/Jackett/Jackett` o su repo
de definiciones) **y** se validan contra HTML vivo. wolfmax4k usa Cloudflare (`needsCloudflare:true`).

**Files:**
- Modify: `app/src/main/assets/providers.json`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt`

**Interfaces:**
- Produces: entradas `wolfmax4k`, `dontorrent`, `divxtotal` en providers.json (o `enabled:false`+`_nota` las irresolubles).

Para CADA uno de los tres, repetir este ciclo:

- [ ] **Step 1: Obtener selectores**

Fuente A (Cardigann de Jackett): abrir la def pública correspondiente y anotar search path, rows y fields.
Fuente B (HTML vivo):
```bash
# wolfmax4k (Cloudflare: puede requerir el WebView del device; para la muestra basta un curl que quizá dé el challenge)
curl -s -A "Mozilla/5.0 (Linux; Android 14)" "https://wolfmax4k.com/?s=coco" -o /tmp/wolfmax.html
curl -s -A "Mozilla/5.0 (Linux; Android 14)" "https://todotorrents.org/?s=coco" -o /tmp/dontorrent.html
curl -s -A "Mozilla/5.0 (Linux; Android 14)" "https://divxtotal.foo/?s=coco" -o /tmp/divxtotal.html
```

- [ ] **Step 2: Escribir el fixture test**

En `SpanishTrackersDefinitionTest.kt`, un `@Test` por tracker que corre `HtmlParser.parseList(BundledProviders.byId("<id>"), html, baseUrl)` sobre una fila real recortada y asegura: `name` no vacío y (`magnet` o `torrentUrl`) presente. Patrón: `NyaaDefinitionTest`.

- [ ] **Step 3: Correr (falla) → añadir la entrada a providers.json**

Estructura base a rellenar con los selectores reales (ejemplo wolfmax4k; ajustar rows/name/magnet/detail según el HTML):
```json
{
  "id": "wolfmax4k",
  "name": "Wolfmax4K",
  "enabled": true,
  "priority": 68,
  "baseUrl": "https://wolfmax4k.com",
  "hostAlt": [],
  "searchPath": "/?s={query}",
  "needsCloudflare": true,
  "keywords": { "movie": "{title}", "tv": "{title} {season}x{episode:2}" },
  "languageTokens": ["latino", "castellano"],
  "parser": {
    "rowSelector": "<VALIDAR EN HTML VIVO>",
    "name": { "selector": "<VALIDAR>", "attr": "text" },
    "torrentUrl": { "selector": "<VALIDAR>", "attr": "href", "resolve": "absolute" }
  },
  "detail": { "followFrom": "torrentUrl", "magnet": { "selector": "a[href^=magnet]", "attr": "href" } }
}
```
Los `<VALIDAR…>` se reemplazan por los selectores confirmados en Step 1-2; una entrada solo se commitea `enabled:true` cuando su fixture test pasa. Si tras esfuerzo razonable no matchea (dominio caído, Cloudflare irresoluble): `"enabled": false` + `"_nota": "roto: <motivo> 2026-07"`.

- [ ] **Step 4: Correr los tres tests (pasan o quedan `enabled:false` documentados)**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.catalog.providers.SpanishTrackersDefinitionTest"`
Expected: PASS (los tests de trackers `enabled:false` se marcan con `@Ignore("roto: <motivo>")` o se omiten de la aserción de resultados).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/providers.json app/src/test/java/com/arkiv/player/data/catalog/providers/SpanishTrackersDefinitionTest.kt
git commit -m "feat(torrent): portar wolfmax4k/dontorrent/divxtotal (validados con HTML vivo)"
```

---

### Task 13: Sembrar el repo público arkiv-providers (hot-update)

**Files:**
- Create: `docs/arkiv-providers-README.md` (instrucciones)
- (Acción manual del usuario: crear el repo en GitHub)

- [ ] **Step 1: Copiar el providers.json bundled a un repo público**

Acción del usuario (una vez), documentada:
```bash
# En una carpeta aparte, fuera de este repo:
gh repo create lordmacu/arkiv-providers --public --clone
cp /Users/cristian/archive/app/src/main/assets/providers.json arkiv-providers/providers.json
cd arkiv-providers && git add providers.json && git commit -m "seed providers.json" && git push
```
La app ya baja de `https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json` (Task 1).

- [ ] **Step 2: Documentar el flujo de arreglo**

Crear `docs/arkiv-providers-README.md`:
```markdown
# Hot-update de definiciones de trackers (arkiv-providers)

Cuando un tracker cambie su HTML/dominio y deje de dar resultados:
1. Edita `providers.json` en github.com/lordmacu/arkiv-providers (web o móvil).
2. Ajusta el selector roto o el `hostAlt` (nuevo dominio).
3. Commit → los dispositivos se auto-actualizan al siguiente arranque (merge por `id`, remoto gana).

Sin red hacia GitHub, la app usa el `providers.json` bundled en el APK.
El bundled es la línea base; el remoto solo sobrescribe/añade por `id`.
```

- [ ] **Step 3: Commit (solo el doc; el repo externo es manual)**

```bash
git add docs/arkiv-providers-README.md
git commit -m "docs(torrent): flujo de hot-update de providers vía GitHub raw"
```

---

## FASE D — Verificación

### Task 14: Suite completa + verificación en device

**Files:** (ninguno nuevo; verificación)

- [ ] **Step 1: Suite unitaria completa verde**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 fallos.

- [ ] **Step 2: Sin rastros de blog en el path de torrents**

Run: `git grep -n "comparadorinternet\|jackett\|Jackett\|FlareSolverr\|HealthGate\|JackettStatus" app/src/main`
Expected: solo, si acaso, referencias de la capa web (`web_sources`, `webResolver`) que NO tocamos. Cero en el path de torrents.

- [ ] **Step 3: Build de la app**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificación en device (manual)**

Instalar en el S24+ (ADB WiFi) y/o Fire Stick y probar, con el WiFi del server **apagado o irrelevante** (para confirmar independencia):
- Buscar una película latino conocida → aparecen fuentes; al menos una `Latino`/`Castellano`.
- Buscar una serie por capítulo (SxxEyy) → fuentes del capítulo.
- Buscar un anime (numeración absoluta) → fuentes de nyaa.
- Repetir la MISMA búsqueda de un tracker Cloudflare (wolfmax/eztv) → la 2ª vez es notablemente más rápida (cf_clearance cacheado; no relanza WebView).
- Reproducir una fuente → arranca (resolveSource/`/dl` sigue on-device).

- [ ] **Step 5: Verificación de red (opcional, con Charles/logcat)**

Confirmar en logcat (`ArkivProv`, `ArkivDl`) que ninguna petición del flujo de búsqueda/reproducción de torrents va a `jackett.comparadorinternet.co`.

- [ ] **Step 6: Commit final / merge**

Si todo verde, usar el skill `superpowers:finishing-a-development-branch` para decidir merge/PR.

---

## Self-Review — cobertura del spec

- **Spec §2 (backends sin Jackett/health, tier siempre activo):** Tasks 2, 3. ✓
- **Spec §3 (portar 6 trackers, hostAlt):** Tasks 7 (schema), 8 (fallback), 9-12 (nyaa, eztv, elitetorrent, wolfmax/dontorrent/divxtotal). ✓
- **Spec §4 (caché cf_clearance + pre-warm):** Tasks 4, 5, 6. ✓
- **Spec §5 (hot-update GitHub raw):** Tasks 1 (URL), 13 (repo + doc). ✓
- **Spec §6 (corte de blog):** Tasks 1, 2, 3; verificado en Task 14 Step 2. ✓
- **Spec §7 (validación HTML vivo):** Tasks 9-12 (dump + fixture) . ✓
- **Spec §8 (testing):** cada task trae su test; Task 14 la suite + device. ✓
- **Spec §9 (fuera de alcance web):** respetado; la capa web no se toca (Global Constraints). ✓
- **Spec §10 (criterios de éxito):** Task 14 los verifica. ✓

Notas de riesgo conocidas (no bloquean el plan):
- Los selectores de wolfmax4k/dontorrent/divxtotal se confirman en ejecución (Task 12); pueden terminar `enabled:false` si el sitio está irresoluble — el resto del sistema funciona igual.
- `mockwebserver` como dependencia de test: si no está, añadirla (Task 5, Step 1) antes de ese test.
