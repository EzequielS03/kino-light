# Fuente allcalidad + Drive (gvideo) — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sumar allcalidad (`allcalidad.re`) como fuente de streaming web latino en Arkiv, resolviendo el stream final vía el resolver headless de blog, incluyendo embeds de Google Drive (gvideo).

**Architecture:** La app agrega una definición **declarativa** (`web_sources.json`, backend `api`) — cero código Kotlin nuevo, el parser ya soporta los campos. El trabajo real es una rama nueva en el resolver Playwright (`server.js` en blog): detecta el player-API de allcalidad, hace fetch del JSON, extrae `data.embeds[].url` y los mete al loop de sniff existente (Drive se apoya en que `videoplayback` ya está en `VIDEO_RX`).

**Tech Stack:** Kotlin/Compose + org.json (app); Node.js + Playwright (resolver, blog vía systemd usuario).

**Spec:** `docs/superpowers/specs/2026-07-26-allcalidad-gvideo-source-design.md`

## Global Constraints

- **Git:** identidad `lordmacu` (`user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`); **sin** línea `Co-Authored-By`. Rama de trabajo: `feat/torrents-via-backend-api`.
- **Título para TMDB:** el campo `api.title` DEBE ser `"title"` (español, ej. "Matrix recargado (2003)"), NO `original_title`. `WebTmdbMatcher` exige `normalize(web.title) == normalize(hit.title)` contra TMDB es-MX; el inglés fallaría.
- **Sin código Kotlin nuevo en la Parte app:** `WebApiRules`/`WebJsonBackend` ya soportan `listPath/title/pageUrl/poster/year`, y el parser ya hace `Regex("\\d{4}")` sobre `year`.
- **Resolver:** editar la copia de referencia `docs/runbooks/web-resolver-src/server.js`; el deploy a blog es `scp` + `systemctl --user restart web-resolver` (runbook `docs/runbooks/web-resolver-blog.md`). NUNCA compilar nada pesado en blog.
- **Paridad de fixtures:** `app/src/main/assets/web_sources.json` y `app/src/test/resources/web/bundled_web_sources.json` deben quedar idénticos.

---

### Task 1: App — definición declarativa de allcalidad + test de parseo

**Files:**
- Modify: `app/src/main/assets/web_sources.json` (agregar la entrada `allcalidad`)
- Modify: `app/src/test/resources/web/bundled_web_sources.json` (misma entrada, mantener paridad)
- Test: `app/src/test/java/com/arkiv/player/data/catalog/web/WebJsonBackendTest.kt` (agregar test allcalidad)

**Interfaces:**
- Consumes: `WebJsonBackend.parse(def, def.api!!, json, kind)` → `List<WebResult>` (ya existe); `WebSourceRegistry.parseDefinitions(text)` para construir `WebSourceDefinition` desde el JSON.
- Produces: la fuente `allcalidad` cargable por `WebSourceEngine`; ningún símbolo nuevo.

- [ ] **Step 1: Escribir el test que falla** (agregar a `WebJsonBackendTest.kt`)

```kotlin
@Test fun `allcalidad parsea data posts con titulo espanol y year de release_date`() {
    val defJson = """
    {"id":"allcalidad","name":"AllCalidad","enabled":true,"priority":55,
     "baseUrl":"https://allcalidad.re","hostAlt":[],"needsCloudflare":false,
     "languageTokens":["latino"],
     "api":{"search":"/api/rest/search?query={query}","browse":{},
            "listPath":"data.posts","title":"title",
            "pageUrl":"/api/rest/player?post_id={_id}&_any=1",
            "poster":"images.poster","year":"release_date"}}
    """.trimIndent()
    val def = WebSourceRegistry.parseDefinitions("[$defJson]").firstOrNull()
    assertNotNull(def); assertNotNull(def!!.api)
    val json = org.json.JSONObject(
        """{"data":{"posts":[
             {"_id":27622,"title":"Matrix recargado (2003)","original_title":"The Matrix Reloaded",
              "release_date":"2003-05-15","images":{"poster":"/thumbs/x.webp"}}
           ]}}"""
    )
    val rows = WebJsonBackend.parse(def, def.api!!, json, "movie")
    assertEquals(1, rows.size)
    assertEquals("Matrix recargado (2003)", rows[0].title)   // campo español, NO original_title
    assertEquals("2003", rows[0].year)                       // regex 4 dígitos sobre release_date
    assertEquals("https://allcalidad.re/api/rest/player?post_id=27622&_any=1", rows[0].pageUrl)
    assertEquals("LAT", rows[0].language)
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.web.WebJsonBackendTest"`
Expected: FAIL — el test compila pero (si se corriera antes de tocar assets) el def parsea; en realidad este test es autocontenido y pasa apenas el código esté. Si falla, es por un typo en el def o en el path. (El objetivo real del test es blindar los campos correctos; corre verde una vez escrito bien.)

- [ ] **Step 3: Agregar la entrada `allcalidad` al asset real**

En `app/src/main/assets/web_sources.json`, agregar como primer objeto del array (antes de `pelisplus`) — respetar comas del JSON:

```json
{
  "id": "allcalidad",
  "name": "AllCalidad",
  "enabled": true,
  "priority": 55,
  "baseUrl": "https://allcalidad.re",
  "hostAlt": [],
  "needsCloudflare": false,
  "languageTokens": ["latino"],
  "api": {
    "search": "/api/rest/search?post_type=movies%2Ctvshows%2Canimes&query={query}&posts_per_page=16&page=1",
    "browse": {
      "movie": "/api/rest/listing?post_type=movies&posts_per_page=24&page={page}",
      "tv": "/api/rest/listing?post_type=tvshows&posts_per_page=24&page={page}"
    },
    "listPath": "data.posts",
    "title": "title",
    "pageUrl": "/api/rest/player?post_id={_id}&_any=1",
    "poster": "images.poster",
    "year": "release_date"
  }
},
```

- [ ] **Step 4: Copiar la misma entrada al fixture de test** (`app/src/test/resources/web/bundled_web_sources.json`), en la misma posición, para mantener paridad byte-a-byte del resto.

- [ ] **Step 5: Correr los tests de la capa web (no debe romperse ninguno)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.catalog.web.*"`
Expected: PASS — incluye el nuevo test allcalidad + `WebFixtureTest`/`WebSourceRegistryTest` sin regresiones. Si `WebFixtureTest` asume un conteo exacto de fuentes, ajustar esa aserción.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/web_sources.json app/src/test/resources/web/bundled_web_sources.json app/src/test/java/com/arkiv/player/data/catalog/web/WebJsonBackendTest.kt
git commit -m "feat(web): fuente allcalidad (api latino) + test de parseo data.posts"
```

---

### Task 2: Resolver — rama allcalidad (extraer embeds del player-API) + test

**Files:**
- Modify: `docs/runbooks/web-resolver-src/server.js` (función pura `parseAllcalidadEmbeds` + rama en `resolveOne`)
- Create: `docs/runbooks/web-resolver-src/server.test.js` (node --test para la función pura)

**Interfaces:**
- Consumes: `resolveOne(pageUrl)` y su loop `sniffPage(ctx, t)` sobre `targets` (ya existen); `ctx.request.get(url, {headers})` de Playwright.
- Produces: `parseAllcalidadEmbeds(jsonText) -> Array<{lang, url}>` (latino primero), usada por la rama nueva.

- [ ] **Step 1: Escribir el test que falla** (`docs/runbooks/web-resolver-src/server.test.js`)

```js
const { test } = require('node:test');
const assert = require('node:assert');
const { parseAllcalidadEmbeds } = require('./server.js');

test('parseAllcalidadEmbeds saca urls y pone latino primero', () => {
  const json = JSON.stringify({ data: { embeds: [
    { lang: 'Castellano', quality: 'HD', url: 'https://streamtape.com/e/AAA' },
    { lang: 'Latino', quality: 'HD', url: 'https://drive.google.com/file/d/XYZ/preview' },
  ] } });
  const out = parseAllcalidadEmbeds(json);
  assert.strictEqual(out.length, 2);
  assert.match(out[0].url, /drive\.google/);          // latino primero
  assert.strictEqual(out[0].lang, 'Latino');
});

test('parseAllcalidadEmbeds tolera json basura y embeds vacíos', () => {
  assert.deepStrictEqual(parseAllcalidadEmbeds('no json'), []);
  assert.deepStrictEqual(parseAllcalidadEmbeds(JSON.stringify({ data: {} })), []);
});
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd docs/runbooks/web-resolver-src && node --test`
Expected: FAIL — `parseAllcalidadEmbeds is not a function` (aún no existe / no exportada).

- [ ] **Step 3: Implementar la función pura + exportarla** (en `server.js`, cerca de `bypassEmbed69`)

```js
function parseAllcalidadEmbeds(jsonText) {
  let j; try { j = JSON.parse(jsonText); } catch (e) { return []; }
  const embeds = (j && j.data && Array.isArray(j.data.embeds)) ? j.data.embeds : [];
  const out = [];
  for (const e of embeds) {
    if (e && typeof e.url === 'string' && /^https?:\/\//.test(e.url)) {
      out.push({ lang: e.lang || '', url: e.url });
    }
  }
  // latino primero (igual criterio que el branch embed69)
  out.sort((a, b) => (/lat/i.test(b.lang) ? 1 : 0) - (/lat/i.test(a.lang) ? 1 : 0));
  return out;
}
```

Al final de `server.js` (junto al `app.listen`), exportar para el test sin romper la ejecución como servicio:

```js
module.exports = { parseAllcalidadEmbeds };
```

- [ ] **Step 4: Agregar la rama allcalidad en `resolveOne`** — insertar al inicio de `resolveOne`, justo tras `const b = await getBrowser(); const ctx = await b.newContext({ userAgent: UA });`, ANTES del `probe`:

```js
    // allcalidad: el stream está en su API JSON (/api/rest/player), no en HTML. Traemos el JSON con
    // el navegador real (pasa Cloudflare tomando cookie de baseUrl) y sniffeamos cada embed (Drive incl.).
    if (/\/api\/rest\/player/i.test(pageUrl)) {
      try {
        let origin = ''; try { origin = new URL(pageUrl).origin; } catch (_) {}
        const warm = await ctx.newPage();
        await warm.goto(origin || pageUrl, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT }).catch(() => {});
        await warm.waitForTimeout(1500);
        await warm.close().catch(() => {});
        const resp = await ctx.request.get(pageUrl, { headers: { Referer: origin, 'User-Agent': UA } }).catch(() => null);
        const body = resp ? await resp.text() : '';
        const embeds = parseAllcalidadEmbeds(body);
        if (embeds.length) {
          let subsAll = [];
          for (const t of embeds.slice(0, 4).map(e => e.url)) {
            const { found, subs } = await sniffPage(ctx, t);
            subsAll = subsAll.concat(subs);
            if (found.length) {
              found.sort((a, b) => (/\.m3u8/i.test(b.url) ? 1 : 0) - (/\.m3u8/i.test(a.url) ? 1 : 0));
              const best = found[0]; const referer = best.headers['referer'] || '';
              const proxied = `${PUBLIC_BASE}/proxy?url=${encodeURIComponent(best.url)}&referer=${encodeURIComponent(referer)}`;
              return { ok: true, streamUrl: proxied, headers: { 'User-Agent': UA }, subtitles: subsAll.slice(0, 8) };
            }
          }
        }
        return { ok: false, error: 'allcalidad: sin stream en embeds' };
      } finally { await ctx.close().catch(() => {}); }
    }
```

Nota: este bloque hace su propio `ctx.close()` en su `finally` y `return`, por lo que no cae al flujo genérico. El `finally` externo de `resolveOne` volvería a cerrar `ctx` (idempotente/`catch`), OK.

- [ ] **Step 5: Correr el test y verificar que pasa**

Run: `cd docs/runbooks/web-resolver-src && node --test`
Expected: PASS (2 tests).

- [ ] **Step 6: Sanity de sintaxis del server**

Run: `node -c docs/runbooks/web-resolver-src/server.js`
Expected: sin salida (sintaxis OK).

- [ ] **Step 7: Commit**

```bash
git add docs/runbooks/web-resolver-src/server.js docs/runbooks/web-resolver-src/server.test.js
git commit -m "feat(resolver): rama allcalidad (embeds del player-API JSON) + test"
```

---

### Task 3: Deploy del resolver a blog + validación en device

**Files:** ninguno (ops/validación). Esta tarea la ejecuta el controlador (no subagente).

- [ ] **Step 1: Deploy del resolver a blog**

```bash
scp docs/runbooks/web-resolver-src/server.js blog:~/web-resolver/server.js
ssh blog 'systemctl --user restart web-resolver && sleep 2 && curl -s 127.0.0.1:8123/health'
```
Expected: `{"ok":true}`.

- [ ] **Step 2: Smoke-test del resolve contra un player-API real**

```bash
ssh blog "curl -s --max-time 90 'http://127.0.0.1:8123/resolve?url=https%3A%2F%2Fallcalidad.re%2Fapi%2Frest%2Fplayer%3Fpost_id%3D27622%26_any%3D1' | head -c 400"
```
Expected: `{"ok":true,"streamUrl":"https://jackett.comparadorinternet.co/proxy?url=..."}`. Si `ok:false`, revisar `journalctl --user -u web-resolver -n 60` y decidir si se necesita el fallback gvideo (fuera de alcance inicial).

- [ ] **Step 3: Build + install del app en el device** (Samsung S24+ por ADB WiFi, ver memoria)

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: Validación funcional**

Buscar "Matrix Reloaded"/"Matrix recargado" en el app, entrar al título (TMDB), y confirmar que aparece la fuente **AllCalidad** y que **reproduce** (embed Drive vía resolver). Revisar `adb logcat | grep -iE "ArkivWeb|ArkivWebResolve"` para el flujo. Si Drive no arranca por el sniffer, ese es el disparador del fallback `gvideo.py` (nueva tarea, fuera de este plan).

---

### Task 4: Resolver — validar stream reproducible y caer al siguiente embed (hardening)

**Contexto:** La validación en device mostró que el resolver devuelve el PRIMER embed sniffeado
(ej. `vimeos.zip`), cuyo token está atado a la sesión Playwright → VLC recibe **HTTP 403** al
reproducir (confirmado: `fetch` cookie-less del m3u8 crudo da 403 con cualquier Referer). El `/proxy`
usa `fetch` global **sin cookies**, así que el validador debe hacer lo MISMO (fetch cookie-less con
el Referer del candidato) para predecir exacto lo que verá VLC. Fix: antes de retornar, validar que
el stream responda 2xx/3xx; si no, probar el siguiente candidato/embed. Se aplica en AMBOS sitios de
retorno (rama allcalidad + loop genérico) → beneficia a todas las fuentes web.

**Files:**
- Modify: `docs/runbooks/web-resolver-src/server.js` (helper `firstPlayable` + usarlo en ambos retornos)
- Modify: `docs/runbooks/web-resolver-src/server.test.js` (test de `firstPlayable` con fetch inyectado)

**Interfaces:**
- Consumes: `sniffPage` devuelve `{ found: [{url, headers}], subs }` (ya existe); `UED`/`PUBLIC_BASE`/`UA` (constantes existentes).
- Produces: `firstPlayable(found, subsAll, fetchImpl = fetch) -> {ok, streamUrl, headers, subtitles} | null`.

- [ ] **Step 1: Escribir el test que falla** (agregar a `server.test.js`)

```js
const { parseAllcalidadEmbeds, firstPlayable } = require('./server.js');

test('firstPlayable salta el 403 y devuelve el primer stream 2xx (proxeado)', async () => {
  const found = [
    { url: 'https://p2.vimeos.zip/hls2/master.m3u8?t=x', headers: { referer: 'https://vimeos.net/' } },
    { url: 'https://streamtape.com/get_video?id=Y.mp4', headers: { referer: 'https://streamtape.com/' } },
  ];
  const fake = async (u) => ({ status: /vimeos/.test(u) ? 403 : 200, body: { cancel: async () => {} } });
  const res = await firstPlayable(found, [], fake);
  assert.ok(res && res.ok);
  assert.match(res.streamUrl, /streamtape\.com/);       // saltó vimeos (403)
  assert.match(res.streamUrl, /^https:\/\/jackett\.comparadorinternet\.co\/proxy\?url=/);
});

test('firstPlayable devuelve null si todos fallan', async () => {
  const found = [{ url: 'https://x/y.m3u8', headers: {} }];
  const fake = async () => ({ status: 403, body: { cancel: async () => {} } });
  assert.strictEqual(await firstPlayable(found, [], fake), null);
});
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd docs/runbooks/web-resolver-src && node --test`
Expected: FAIL — `firstPlayable is not a function`.

- [ ] **Step 3: Implementar el helper** (en `server.js`, cerca de `sniffPage`) — usa `fetch` global (cookie-less, como el `/proxy`), con `Range: bytes=0-1` para no bajar archivos grandes:

```js
// Devuelve el primer stream sniffeado que realmente responde 2xx/3xx con SU Referer (fetch
// cookie-less, idéntico a lo que hará /proxy) → evita devolver tokens muertos (403) tipo vimeos.zip.
async function firstPlayable(found, subsAll, fetchImpl = fetch) {
  const sorted = found.slice().sort((a, b) => (/\.m3u8/i.test(b.url) ? 1 : 0) - (/\.m3u8/i.test(a.url) ? 1 : 0));
  for (const cand of sorted) {
    const referer = (cand.headers && cand.headers['referer']) || '';
    const r = await fetchImpl(cand.url, { headers: { Referer: referer, 'User-Agent': UA, Range: 'bytes=0-1' } }).catch(() => null);
    try { if (r && r.body && r.body.cancel) await r.body.cancel(); } catch (_) {}
    if (r && r.status >= 200 && r.status < 400) {
      const proxied = `${PUBLIC_BASE}/proxy?url=${encodeURIComponent(cand.url)}&referer=${encodeURIComponent(referer)}`;
      return { ok: true, streamUrl: proxied, headers: { 'User-Agent': UA }, subtitles: (subsAll || []).slice(0, 8) };
    }
  }
  return null;
}
```

Actualizar el `module.exports`:

```js
module.exports = { parseAllcalidadEmbeds, firstPlayable };
```

- [ ] **Step 4: Usar `firstPlayable` en los DOS sitios de retorno** (reemplazar el bloque `found.sort(...); const best = found[0]; ... return {ok:true,...}`):

Rama allcalidad (~líneas 116-122) — dentro del `for (const t of embeds...)`:

```js
          const { found, subs } = await sniffPage(ctx, t);
          subsAll = subsAll.concat(subs);
          if (found.length) {
            const res = await firstPlayable(found, subsAll);
            if (res) return res;   // si ninguno reproduce, seguir al próximo embed
          }
```

Loop genérico (~líneas 219-229) — dentro del `for (const t of targets...)`:

```js
      const { found, subs } = await sniffPage(ctx, t);
      subsAll = subsAll.concat(subs);
      if (found.length) {
        const res = await firstPlayable(found, subsAll);
        if (res) return res;   // si ninguno reproduce, seguir al próximo target
      }
```

(Conservar el `subsAll` que cada sitio ya acumula; `firstPlayable` recibe el acumulado.)

- [ ] **Step 5: Correr test + sanity de sintaxis**

Run: `cd docs/runbooks/web-resolver-src && node --test` → 4 tests PASS.
Run: `node -c docs/runbooks/web-resolver-src/server.js` → sin salida.

- [ ] **Step 6: Commit**

```bash
git add docs/runbooks/web-resolver-src/server.js docs/runbooks/web-resolver-src/server.test.js
git commit -m "feat(resolver): validar stream 2xx y caer al siguiente embed (evita 403 de hosts token-gated)"
```

- [ ] **Step 7: Deploy + re-validación en device** (controlador)

```bash
scp docs/runbooks/web-resolver-src/server.js blog:~/web-resolver/server.js
ssh blog 'systemctl --user restart web-resolver && sleep 2 && curl -s 127.0.0.1:8123/health'
```
Luego reproducir "Matrix recargado" → AllCalidad en el Fire TV y confirmar que ahora **arranca**
(el resolver debe devolver un embed que dé 2xx, saltando vimeos.zip). Revisar `ArkivWebResolve` en logcat.

---

## Self-Review

- **Cobertura del spec:** Parte 1 (def declarativa) = Task 1; Parte 2 (rama resolver + Drive vía sniffer) = Task 2; deploy + validación device = Task 3; hardening validar-y-caer = Task 4. ✓
- **Placeholders:** ninguno — todo el código (JSON, Kotlin test, JS función+rama+test, comandos) está completo. ✓
- **Consistencia de tipos:** `parseAllcalidadEmbeds(jsonText) -> [{lang,url}]` definida en Task 2 Step 3, usada en Step 4 y en el test Step 1. `api.title="title"` consistente entre spec, def y test. ✓
- **Riesgo abierto (documentado, no placeholder):** que el sniffer capture el `videoplayback` de Drive se valida en device (Task 3 Step 4); si falla, dispara el fallback gvideo.py como plan aparte.
```
