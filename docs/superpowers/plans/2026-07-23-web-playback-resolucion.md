# Reproducción de fuentes web (resolución embed→stream) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que las fuentes web se reproduzcan en el reproductor unificado existente, resolviendo `pageUrl` → `streamUrl` con un sniffer headless en blog, y heredando seek/controles/cast/dlna/subtítulos/idiomas.

**Architecture:** Servicio `web-resolver` en blog (Node+Playwright) que snifea el stream; la app lo llama vía `WebResolverApi`, guarda un "episodio web" (reusa el store de torrent), y `PlayerViewModel.loadWeb` arma el `PlayerData` con la streamUrl. El player es genérico sobre una URL.

**Tech Stack:** Kotlin, Coroutines, OkHttp, Room, media3/libVLC; blog: Node.js + Playwright + systemd de usuario + Cloudflare tunnel.

## Global Constraints

- Commits SIN coautoría de Claude. Identidad: `git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit`.
- Comentarios/código en español, estilo existente.
- Tests unitarios JVM puros JUnit4; `android.util.Log` → envolver en `runCatching`. Correr: `./gradlew :app:testDebugUnitTest`.
- Kotlin 2.0.21: sin `continue`/`break` dentro de lambdas inline.
- **Árbol compartido con otra sesión:** commits SOLO con rutas explícitas (`git add <ruta>`), NUNCA `git add -A`/`.`/`commit -a`. Si un archivo tiene WIP ajeno, PARAR y reportar BLOCKED.
- **Regla blog:** nada pesado se compila en blog (2 CPU). Node/Playwright se prepara en el Mac (`npm install` local, o `npm ci` en blog solo si liviano) y el Chromium de Playwright se instala UNA vez. Servicios en blog = systemd de USUARIO (`systemctl --user`). Deploy vía `ssh blog` / `scp`.
- Validación en dispositivo: instalar por USB (serial `R5CX7251VRM`).

**Spec:** `docs/superpowers/specs/2026-07-23-web-playback-resolucion-design.md`. Referencia Alfa (detalle→embed): `channels/<id>.py::findvideos`.

---

## File Structure

- **Blog (nuevo, en `~/web-resolver/`):** `server.js` (Express + Playwright), `package.json`, `~/.config/systemd/user/web-resolver.service`, tunnel cloudflared. Runbook en el repo: `docs/runbooks/web-resolver-blog.md`.
- **App nuevos:** `data/catalog/web/WebResolverApi.kt`; tests.
- **App modificados:** `playback/PlayerSource.kt` (SourceKind.WEB + kindFor); `ui/player/PlayerViewModel.kt` (loadWeb + rama); `ui/player/PlayerScreen.kt` (adjuntar subs web); `data/ArkivRepository.kt` (addWebSource/addWebSeriesEpisode/webSourceForEpisode); `data/SettingsStore.kt` (webResolverUrl); `ui/catalog/CineDetailScreen.kt` (click web real); `AppGraph.kt` (wiring).

---

### Task 1: Servicio resolver en blog (`web-resolver`, Node+Playwright)

**Files (en blog `~/web-resolver/`):** `server.js`, `package.json`; systemd unit; tunnel. **Repo:** `docs/runbooks/web-resolver-blog.md`.

**Interfaces:** `GET /resolve?url=<pageUrl>` → `{ ok, streamUrl, headers?, subtitles?:[{lang,url}], error? }`.

- [ ] **Step 1: `package.json`**

```json
{
  "name": "web-resolver",
  "version": "1.0.0",
  "private": true,
  "type": "commonjs",
  "dependencies": { "express": "^4.19.2", "playwright": "^1.47.0" }
}
```

- [ ] **Step 2: `server.js`**

```js
const express = require('express');
const { chromium } = require('playwright');

const PORT = 8123;
const NAV_TIMEOUT = 30000;
// Extensiones/patrones que delatan el stream real de video.
const VIDEO_RX = /\.m3u8(\?|$)|\.mp4(\?|$)|\/manifest|videoplayback/i;
const SUB_RX = /\.vtt(\?|$)|\.srt(\?|$)/i;

const app = express();
let browser = null;
async function getBrowser() {
  if (!browser) browser = await chromium.launch({ args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  return browser;
}

// Serializa: un resolve a la vez (blog es 2 CPU).
let busy = Promise.resolve();
function serialize(fn) { const p = busy.then(fn, fn); busy = p.catch(() => {}); return p; }

async function resolveOne(pageUrl) {
  const b = await getBrowser();
  const ctx = await b.newContext({ userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' });
  const page = await ctx.newPage();
  const found = [];       // {url, headers}
  const subs = [];        // {lang, url}
  page.on('request', (req) => {
    const u = req.url();
    if (VIDEO_RX.test(u)) found.push({ url: u, headers: req.headers() });
    else if (SUB_RX.test(u)) subs.push({ lang: '', url: u });
  });
  try {
    await page.goto(pageUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
    // Muchos detalles cargan el video dentro de un <iframe> de un server. Entrar a los iframes y,
    // si hay un <video>/botón play, intentar dispararlo para forzar la carga del stream.
    for (const frame of page.frames()) {
      await frame.evaluate(() => {
        const v = document.querySelector('video'); if (v) { v.muted = true; v.play?.().catch(() => {}); }
        document.querySelector('.play, .vjs-big-play-button, [class*=play]')?.click?.();
      }).catch(() => {});
    }
    // Esperar hasta ~12s a que aparezca una request de video.
    const deadline = Date.now() + 12000;
    while (found.length === 0 && Date.now() < deadline) await page.waitForTimeout(300);
  } finally {
    await ctx.close();
  }
  if (found.length === 0) return { ok: false, error: 'no se detectó stream' };
  // Preferir m3u8 (adaptativo, multi-audio/subs) sobre mp4 suelto.
  found.sort((a, b) => (/\.m3u8/i.test(b.url) ? 1 : 0) - (/\.m3u8/i.test(a.url) ? 1 : 0));
  const best = found[0];
  const headers = {};
  if (best.headers['referer']) headers['Referer'] = best.headers['referer'];
  headers['User-Agent'] = best.headers['user-agent'] || 'Mozilla/5.0';
  return { ok: true, streamUrl: best.url, headers, subtitles: subs.slice(0, 8) };
}

app.get('/resolve', async (req, res) => {
  const url = req.query.url;
  if (!url || !/^https?:\/\//.test(url)) return res.status(400).json({ ok: false, error: 'url inválida' });
  try {
    const out = await serialize(() => resolveOne(url));
    res.json(out);
  } catch (e) {
    res.json({ ok: false, error: String(e && e.message || e) });
  }
});
app.get('/health', (_req, res) => res.json({ ok: true }));
app.listen(PORT, '127.0.0.1', () => console.log('web-resolver on 127.0.0.1:' + PORT));
```

- [ ] **Step 3: Preparar en el Mac y subir**

```bash
mkdir -p /tmp/web-resolver && cd /tmp/web-resolver
# copiar package.json + server.js aquí (de este plan)
npm install
scp -r /tmp/web-resolver blog:~/web-resolver
# instalar el Chromium de Playwright en blog (una vez; liviano vs compilar)
ssh blog 'cd ~/web-resolver && npx playwright install chromium'
```

- [ ] **Step 4: systemd de usuario en blog** — `~/.config/systemd/user/web-resolver.service`

```ini
[Unit]
Description=Web Resolver (Playwright) para Arkiv
After=network.target
[Service]
ExecStart=/usr/bin/node %h/web-resolver/server.js
Restart=on-failure
[Install]
WantedBy=default.target
```
```bash
ssh blog 'systemctl --user daemon-reload && systemctl --user enable --now web-resolver && sleep 2 && curl -s 127.0.0.1:8123/health'
```

- [ ] **Step 5: Cloudflare tunnel** — enrutar un hostname (ej. `webresolver.comparadorinternet.co`) a `127.0.0.1:8123` (config del cloudflared existente, como Jackett). Verificar: `curl -s https://webresolver.comparadorinternet.co/health`.

- [ ] **Step 6: Smoke test end-to-end** con una peli conocida de un canal que anda:

```bash
ssh blog 'curl -s "http://127.0.0.1:8123/resolve?url=<pageUrl_de_una_peli_de_sololatino>" | head -c 400'
```
Expected: `{"ok":true,"streamUrl":"https://....m3u8",...}`. Si `ok:false`, ajustar el disparo del play / los patrones VIDEO_RX.

- [ ] **Step 7: Runbook + commit**

Escribir `docs/runbooks/web-resolver-blog.md` (qué es, cómo deployar, systemd, tunnel, cómo depurar un `ok:false`). Commit del runbook (los archivos de blog no van al repo).

```bash
git add docs/runbooks/web-resolver-blog.md
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "docs(web): runbook del resolver headless en blog"
```

---

### Task 2: `WebResolverApi` (cliente HTTP del resolver)

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/catalog/web/WebResolverApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebResolverApiTest.kt`

**Interfaces:**
- Produces:
  - `data class ResolvedStream(val streamUrl: String, val headers: Map<String,String>, val subtitles: List<ResolvedSub>)`
  - `data class ResolvedSub(val lang: String, val url: String)`
  - `class WebResolverApi(baseUrl: () -> String, client: OkHttpClient = ...)` con `suspend fun resolve(pageUrl: String): ResolvedStream?`
  - `fun parse(json: String): ResolvedStream?` (puro, testeable).

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebResolverApiTest {
    @Test fun `parsea ok con streamUrl headers y subs`() {
        val r = WebResolverApi.parse("""
            {"ok":true,"streamUrl":"https://cdn/x.m3u8","headers":{"Referer":"https://h/"},
             "subtitles":[{"lang":"es","url":"https://cdn/es.vtt"}]}
        """.trimIndent())!!
        assertEquals("https://cdn/x.m3u8", r.streamUrl)
        assertEquals("https://h/", r.headers["Referer"])
        assertEquals(1, r.subtitles.size)
        assertEquals("es", r.subtitles[0].lang)
    }

    @Test fun `ok false o sin streamUrl devuelve null`() {
        assertNull(WebResolverApi.parse("""{"ok":false,"error":"no stream"}"""))
        assertNull(WebResolverApi.parse("""{"ok":true}"""))
        assertNull(WebResolverApi.parse("no json"))
    }
}
```

- [ ] **Step 2: Correr → falla**

Run: `./gradlew :app:testDebugUnitTest --tests "*WebResolverApiTest*"` → FAIL "unresolved reference".

- [ ] **Step 3: Implementar `WebResolverApi.kt`**

```kotlin
package com.arkiv.player.data.catalog.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ResolvedSub(val lang: String, val url: String)
data class ResolvedStream(val streamUrl: String, val headers: Map<String, String>, val subtitles: List<ResolvedSub>)

/** Cliente del resolver headless de blog: pageUrl → stream reproducible (o null). */
class WebResolverApi(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)  // el sniff tarda (headless)
        .build(),
) {
    suspend fun resolve(pageUrl: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val url = baseUrl().trimEnd('/') + "?url=" + URLEncoder.encode(pageUrl, "UTF-8")
        val body = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }.getOrNull() ?: return@withContext null
        parse(body)
    }

    companion object {
        fun parse(json: String): ResolvedStream? = runCatching {
            val o = JSONObject(json)
            if (!o.optBoolean("ok")) return null
            val stream = o.optString("streamUrl").ifBlank { return null }
            val h = o.optJSONObject("headers")
            val headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap()
            val sj = o.optJSONArray("subtitles")
            val subs = if (sj == null) emptyList() else (0 until sj.length()).mapNotNull { i ->
                sj.optJSONObject(i)?.let { s ->
                    val u = s.optString("url").ifBlank { return@mapNotNull null }
                    ResolvedSub(lang = s.optString("lang"), url = u)
                }
            }
            ResolvedStream(stream, headers, subs)
        }.onFailure { runCatching { Log.w("ArkivWeb", "resolve parse fail: $it") } }.getOrNull()
    }
}
```

- [ ] **Step 4: Correr → pasa.** `./gradlew :app:testDebugUnitTest --tests "*WebResolverApiTest*"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/catalog/web/WebResolverApi.kt app/src/test/java/com/arkiv/player/data/catalog/web/WebResolverApiTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): WebResolverApi (cliente del resolver headless)"
```

---

### Task 3: `SourceKind.WEB` + `kindFor`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/playback/PlayerSource.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt`

**Interfaces:** `SourceKind` gana `WEB`; `kindFor("web:...")` → WEB.

- [ ] **Step 1: Test que falla**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTest {
    @Test fun `kindFor por prefijo`() {
        assertEquals(SourceKind.TORRENT, PlayerSource.kindFor("torrent:abc"))
        assertEquals(SourceKind.WEB, PlayerSource.kindFor("web:https://x/pelicula/y"))
        assertEquals(SourceKind.ARCHIVE, PlayerSource.kindFor("cualquier_otro"))
    }
}
```

- [ ] **Step 2: Correr → falla** (`SourceKind.WEB` no existe).

- [ ] **Step 3: Editar `PlayerSource.kt`**

```kotlin
enum class SourceKind { ARCHIVE, TORRENT, WEB }
```
y en `kindFor`:
```kotlin
fun kindFor(episodeId: String): SourceKind = when {
    episodeId.startsWith("torrent:") -> SourceKind.TORRENT
    episodeId.startsWith("web:") -> SourceKind.WEB
    else -> SourceKind.ARCHIVE
}
```

- [ ] **Step 4: Correr → pasa.** Además `./gradlew :app:compileDebugKotlin` para ver qué `when (SourceKind)` quedaron no-exhaustivos (PlayerViewModel.load, VlcPlayer, PlayerScreen). Los `== SourceKind.TORRENT` NO rompen (WEB cae en el else, comportándose como archive, que es lo deseado). Solo el `when` EXHAUSTIVO de `PlayerViewModel.load` requerirá la rama WEB (se agrega en Task 5).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/playback/PlayerSource.kt app/src/test/java/com/arkiv/player/playback/PlayerSourceTest.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): SourceKind.WEB + kindFor prefijo web:"
```

---

### Task 4: Store del episodio web en el repo (reusa el patrón torrent)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt`

**Interfaces (Produces):**
- `suspend fun addWebSource(pageUrl: String, title: String, posterUrl: String = ""): String?` → episodeId `web:...`
- `suspend fun addWebSeriesEpisode(seriesId: String, showTitle: String, posterUrl: String, season: Int, episode: Int, episodeName: String, pageUrl: String): String?`
- `suspend fun webSourceForEpisode(episodeId: String): String?` (devuelve la pageUrl)

**Decisión:** la `pageUrl` se guarda en `EpisodeEntity.torrentData` (campo genérico de "payload de fuente"), con `source="web"` e id prefijado `web:` — así NO hace falta migración Room. `webSourceForEpisode` lee `ep.torrentData`.

- [ ] **Step 1: Añadir los métodos** (molde: `addTorrentMagnet`/`addSeriesEpisodeMagnet`/`torrentSourceForEpisode`, ~líneas 333-395)

```kotlin
    /** Guarda una PELÍCULA web (la pageUrl se resuelve al reproducir). Devuelve el episodeId. */
    suspend fun addWebSource(pageUrl: String, title: String, posterUrl: String = ""): String? {
        val id = "web:" + pageUrl.hashCode().toUInt().toString(16)   // id estable por pageUrl
        val existing = itemDao.getItem(id)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = id, title = title.ifBlank { "Web" }, description = null,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            source = "web", torrentData = pageUrl,   // pageUrl como payload de fuente
        )
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = "$id::0", itemId = id, section = "", displayName = MetadataParser.cleanName(title),
            orderIndex = 0, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = pageUrl,
        )
        itemDao.replaceItem(item, listOf(ep))
        return ep.id
    }

    /** Guarda un capítulo de serie web. */
    suspend fun addWebSeriesEpisode(
        seriesId: String, showTitle: String, posterUrl: String,
        season: Int, episode: Int, episodeName: String, pageUrl: String,
    ): String? {
        val itemId = "web:series:$seriesId"
        val episodeId = "$itemId::${pageUrl.hashCode().toUInt().toString(16)}"
        val existing = itemDao.getItem(itemId)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = itemId, title = showTitle.ifBlank { "Serie" }, description = null,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            categoryOverride = "series", source = "web", torrentData = null,
        )
        itemDao.upsertItem(item)
        val siblings = itemDao.getEpisodesOf(itemId)
        val order = siblings.firstOrNull { it.id == episodeId }?.orderIndex ?: (season * 1000 + episode)
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = episodeId, itemId = itemId, section = "Temporada $season",
            displayName = "T$season · E$episode" + if (episodeName.isNotBlank()) "  $episodeName" else "",
            orderIndex = order, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = pageUrl,
        )
        itemDao.upsertEpisodes(listOf(ep))
        return episodeId
    }

    /** pageUrl guardada de un episodio web. */
    suspend fun webSourceForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return ep.torrentData ?: itemDao.getItem(ep.itemId)?.torrentData
    }
```

Verificar contra el código real que `ItemEntity`/`EpisodeEntity` tienen esos campos exactos (los del `addTorrentMagnet` que se está copiando); ajustar si difieren.

- [ ] **Step 2: Compilar.** `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ArkivRepository.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): store de episodio web en el repo (reusa payload de fuente)"
```

---

### Task 5: `PlayerViewModel.loadWeb` + rama en `load`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt`

**Interfaces:** Consume `repo.webSourceForEpisode`, `webResolverApi.resolve`. El VM recibe `webResolverApi` por constructor.

- [ ] **Step 1: Añadir `webResolverApi` al constructor del VM** (junto a `torrentEngine`, `archiveCacheProxy`). Actualizar el call-site donde se instancia el VM (buscar `PlayerViewModel(`).

- [ ] **Step 2: Rama en `load`** (~línea 75):

```kotlin
            when (PlayerSource.kindFor(episodeId)) {
                SourceKind.TORRENT -> loadTorrent(episodeId)
                SourceKind.ARCHIVE -> loadArchive(episodeId)
                SourceKind.WEB -> loadWeb(episodeId)
            }
```

- [ ] **Step 3: `loadWeb`** (molde: `loadTorrent`):

```kotlin
    private suspend fun loadWeb(episodeId: String) {
        val pageUrl = repo.webSourceForEpisode(episodeId)
        if (pageUrl.isNullOrBlank()) { _error.value = "No se encontró la fuente web"; return }
        _prepProgress.value = com.arkiv.player.torrent.StreamStatus(peers = 0, downKbps = 0, progress = 0f)
            .let { null }   // placeholder: si prepProgress es de torrent, mostrar spinner genérico en la UI para WEB
        val resolved = withContext(Dispatchers.IO) { webResolverApi.resolve(pageUrl) }
        if (resolved == null) { _error.value = "No se pudo resolver esta fuente web"; return }
        val ep = repo.getEpisode(episodeId)
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: "Web",
            subtitle = ep?.section ?: "",
            mediaUrl = resolved.streamUrl,
            castUrl = resolved.streamUrl,   // ver limitación de headers en cast (spec)
            artworkUrl = repo.getItem(episodeId.substringBefore("::"))?.thumbnailUrl ?: "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.WEB,
        )
        // Las subs sniffeadas + headers viajan por un canal aparte para que PlayerScreen las adjunte.
        _webExtras.value = WebExtras(episodeId, resolved.headers, resolved.subtitles)
        val startPos = safeStartPosition(episodeId, SourceKind.WEB)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
    }
```

Nota: definir un `StateFlow` `_webExtras` (`data class WebExtras(val episodeId: String, val headers: Map<String,String>, val subtitles: List<ResolvedSub>)`) expuesto como `webExtras`, para pasar subs/headers a la UI (Task 6). Si `repo.getEpisode`/`repo.getItem` no existen con esas firmas, usar las equivalentes (ver `loadArchive`/`buildData`).

- [ ] **Step 4: Compilar** (el `when` exhaustivo ahora cubre WEB). `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): PlayerViewModel.loadWeb (resuelve vía blog y arma PlayerData)"
```

---

### Task 6: Adjuntar subtítulos web + headers en `PlayerScreen`/`VlcPlayer`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt` (si hace falta pasar headers al media)

**Interfaces:** Consume `vm.webExtras`; reusa `VlcPlayer.addSubtitleSlave(uri)`.

- [ ] **Step 1: Headers del stream a VLC.** libVLC permite pasar opciones de media (`:http-referrer=`, `:http-user-agent=`). En `VlcPlayer` donde se crea el `Media` para reproducir, si el tag/URL trae headers (WEB), añadir `media.addOption(":http-referrer=$referer")` y `:http-user-agent=`. Localizar la creación del `Media` (buscar `Media(` en VlcPlayer) y añadir las opciones cuando corresponda. Pasar los headers desde `webExtras` (o incluir Referer/UA en `PlayerData` como campos opcionales).

- [ ] **Step 2: Adjuntar subs sniffeadas.** En `PlayerScreen`, observar `vm.webExtras`; cuando llega para el episodio actual, por cada `ResolvedSub`: descargar el archivo (reusar el path de descarga de subs de OpenSubtitles, ~línea 568-578) y `vlc.addSubtitleSlave(Uri.fromFile(file))`. Si la URL es directamente reproducible por VLC, se puede `addSubtitleSlave(Uri.parse(sub.url))` sin descargar (probar; algunas vtt remotas cargan directo).

- [ ] **Step 3: Feedback de "Resolviendo…".** Mientras `loadWeb` resuelve (puede tardar ~10-30s), mostrar el overlay de preparación (reusar el de pre-buffer del torrent) con texto "Resolviendo fuente web…". Enganchar a un estado de "resolviendo" (un `_resolving` bool en el VM, o reusar `prepProgress` con un flag).

- [ ] **Step 4: Compilar + smoke.** `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): subtítulos sniffeados + headers del stream en el player"
```

---

### Task 7: Click real en la hoja de fuentes

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt`

**Interfaces:** `playSource(PlaySource.Web)` deja de ser stub → crea episodio web → `onPlay`.

- [ ] **Step 1: Reemplazar el stub** (en `playSource`, la rama `is PlaySource.Web`):

```kotlin
        is PlaySource.Web -> playWeb(s.result)
```

- [ ] **Step 2: Añadir `playWeb`** (molde: `playArchive`, ~línea 177):

```kotlin
    fun playWeb(r: com.arkiv.player.data.catalog.web.WebResult) {
        val d = detail ?: return
        preparing = true; error = null; sheetOpen = false
        scope.launch {
            val ep = sheetEpisode
            val epId = if (ep != null) {
                val seriesId = d.imdbId.ifBlank { "tmdb${d.id}" }
                graph.repository.addWebSeriesEpisode(seriesId, d.title, d.posterUrl, ep.season, ep.episode, ep.name, r.pageUrl)
            } else {
                graph.repository.addWebSource(r.pageUrl, r.title.ifBlank { d.title }, d.posterUrl)
            }
            preparing = false
            if (epId != null) onPlay(epId) else error = "No se pudo abrir la fuente web"
        }
    }
```

- [ ] **Step 3: Compilar.** `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/catalog/CineDetailScreen.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): click en fuente web reproduce (crea episodio web + onPlay)"
```

---

### Task 8: Wiring — `AppGraph` + `SettingsStore`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

- [ ] **Step 1: `SettingsStore.webResolverUrl`** (molde: `webSourcesUrl`, líneas 35/62/76/82):

```kotlin
    private val _webResolverUrl = MutableStateFlow(prefs.getString(KEY_WEB_RESOLVER_URL, DEFAULT_WEB_RESOLVER_URL)!!)
    val webResolverUrl: StateFlow<String> = _webResolverUrl
    // ...
    fun setWebResolverUrl(v: String) { prefs.edit().putString(KEY_WEB_RESOLVER_URL, v).apply(); _webResolverUrl.value = v }
    // ...companion:
    private const val KEY_WEB_RESOLVER_URL = "web_resolver_url"
    const val DEFAULT_WEB_RESOLVER_URL = "https://webresolver.comparadorinternet.co/resolve"
```

- [ ] **Step 2: `AppGraph.webResolverApi`**:

```kotlin
    val webResolverApi: com.arkiv.player.data.catalog.web.WebResolverApi by lazy {
        com.arkiv.player.data.catalog.web.WebResolverApi(baseUrl = { settings.webResolverUrl.value })
    }
```
Y pasar `webResolverApi` al `PlayerViewModel` donde se construye (Task 5 Step 1 lo requiere).

- [ ] **Step 3: Compilar + suite completa.** `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/SettingsStore.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git -c user.name=lordmacu -c user.email=10134930+lordmacu@users.noreply.github.com commit -m "feat(web): wiring de WebResolverApi + setting webResolverUrl"
```

---

### Task 9: Validación end-to-end en dispositivo

**Files:** ninguno (validación).

- [ ] **Step 1: Verificar el resolver de blog** con una pageUrl de una peli de sololatino/pelisplus (Task 1 Step 6).

- [ ] **Step 2: Build + install.** `./gradlew :app:assembleDebug && adb -s R5CX7251VRM install -r app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Película.** Abrir una peli con fuente web que anda → Buscar fuentes → sección WEB → click en una fuente. Verificar: aparece "Resolviendo…", luego reproduce; probar **seek**, **controles**, **velocidad/zoom/gestos**, **subtítulos**, **selector de audio/idioma**. Confirmar en logcat que `loadWeb` resolvió (`ArkivWeb`).

- [ ] **Step 4: Episodio.** Abrir una serie → capítulo → fuente web → reproduce.

- [ ] **Step 5: Cast/DLNA.** Castear una peli web a Chromecast y probar DLNA. Anotar si algún stream falla por headers (limitación conocida del spec).

- [ ] **Step 6: Reporte.** Documentar qué canales reproducen bien end-to-end y cuáles fallan (y por qué: resolver `ok:false`, headers en cast, etc.). Ajustar el resolver de blog (patrones/disparo del play) para los que fallen.

---

## Self-Review

**Spec coverage:**
- Resolver headless en blog → Task 1. ✔
- Cliente `WebResolverApi` → Task 2. ✔
- `SourceKind.WEB` + kindFor → Task 3. ✔
- Episodio web en repo → Task 4. ✔
- `loadWeb` + rama en `load` → Task 5. ✔
- Subtítulos sniffeados + headers → Task 6. ✔
- Feedback "Resolviendo…" → Task 6 Step 3. ✔
- Click real en la hoja → Task 7. ✔
- Wiring (AppGraph + Settings) → Task 8. ✔
- Cast/DLNA + limitación de headers → Task 9 Step 5 (validación) + spec (limitación). ✔
- Películas + episodios → Tasks 4/7 (movie + series) + Task 9. ✔

**Placeholder scan:** el `_prepProgress.value = ... .let { null }` en Task 5 Step 3 es una nota deliberada (el feedback de "resolviendo" se resuelve limpio en Task 6 Step 3 con un estado propio); el implementador debe usar el `_resolving` bool en vez del placeholder. Marcado explícitamente.

**Type consistency:** `ResolvedStream`/`ResolvedSub`/`WebResolverApi.resolve`/`parse`, `SourceKind.WEB`, `addWebSource`/`addWebSeriesEpisode`/`webSourceForEpisode`, `loadWeb`, `webResolverApi`, `webResolverUrl` — consistentes entre tareas.

**Riesgo mayor:** el resolver de blog (Task 1) es la pieza incierta — puede necesitar iteración por sitio (disparar el play del embed, patrones de stream). Task 9 lo valida en vivo y realimenta. Todo lo demás (app) es plumbing determinista sobre patrones existentes.
