# Torrents 100% on-device (sin servidor) — Diseño

**Fecha:** 2026-07-24
**Branch:** `feat/torrents-ondevice-sin-servidor` (desde `feat/torrent-buffering-tier1`)
**Alcance:** Eliminar la dependencia del servidor `blog` para la **búsqueda de torrents**. La capa web
(`/resolve`, web-resolver Playwright) queda **fuera de este branch** (se aborda después).

---

## 1. Contexto y motivación

Hoy la app depende del servidor `blog` (`jackett.comparadorinternet.co`) para tres cosas; este branch
ataca las dos primeras (torrents), la tercera queda para otro branch:

1. **Búsqueda de torrents** — Jackett + FlareSolverr agregan indexers, incluidos trackers españoles
   (DonTorrent, Wolfmax, DivxTotal, EliteTorrent) protegidos por Cloudflare.
2. **Hot-update** de `providers.json` / `web_sources.json` — definiciones de scraping servidas por el túnel.
3. **Resolución de streams web** — `web-resolver` (Playwright headless, descifra embeds). **Fuera de alcance.**

El objetivo es que la app **no dependa de ningún servidor headless** para buscar y reproducir torrents.

### Lo que ya existe on-device (base del diseño)

La infraestructura para reemplazar a Jackett **ya está construida como fallback**:

- `WebViewCloudflareSolver` — resuelve el challenge JS de Cloudflare con un WebView headless en el
  dispositivo (equivalente a FlareSolverr). Entrega la cookie `cf_clearance` + el UA usado.
- `HttpFetcher` — descarga HTML con UA de navegador; detecta el challenge de Cloudflare en runtime
  (`looksLikeCloudflareChallenge`) y delega en el solver, reintentando con la cookie inyectada.
- `RegistryProviderBackend` + `DeclarativeHtmlBackend` — scraping declarativo (selectores CSS) sobre
  las definiciones activas del registro. Corre cada proveedor aislado con `runCatching`.
- `ProviderDefinition` — esquema declarativo estilo Cardigann: `baseUrl`, `searchPath`, `keywords` por
  tipo de contenido, `parser` (rowSelector + FieldRules), `detail` (2º fetch), `needsCloudflare`,
  `languageTokens`.
- El pipeline de `TorrentSearchApi` (clasificación por idioma, ranking por seeds/calidad, dedupe,
  caché en memoria, búsqueda progresiva en 2 fases) es **agnóstico del backend** y se conserva.

**Lo que falta** es (a) promover ese path de fallback a primario, (b) portar las definiciones de los
trackers (hoy `providers.json` bundled solo trae `bitsearch`), (c) endurecer el solver con caché, y
(d) cortar el cable a `blog`.

### Guía de portado

Las técnicas del servidor se replican mirando su fuente:
- **Selectores/search-paths de cada tracker** → las definiciones **Cardigann YAML** de Jackett
  (`Definitions/<indexer>.yml`, públicas en el repo de Jackett). Son la fuente de verdad de cómo
  Jackett scrapea cada sitio.
- **Manejo de Cloudflare** → Jackett cachea el `cf_clearance` por indexer (verificado: la config viva
  de `wolfmax4k` guarda `cf_clearance=...` en su `CookieHeader`). Replicamos esa caché on-device.

---

## 2. Arquitectura de backends (después del cambio)

**Antes:**
```
primary   = [ Apibay, Knaben, JackettBackend(blog) ]
fallback  = [ RegistryProviderBackend ]   (solo si Jackett DOWN o useful < minResults)
healthGate = JackettHealth(blog/health)
```

**Después:**
```
rápidos   = [ Apibay, Knaben ]                    (agregadores públicos; inglés/general)
trackers  = [ RegistryProviderBackend ]           (on-device; español + anime + eztv)  ← SIEMPRE activo
(sin healthGate, sin JackettBackend, sin llamada a blog)
```

- Se **elimina** `JackettBackend` (clase y wiring), `JackettHealth`/`HealthGate` y sus settings
  (`jackettBaseUrl`, `jackettApiKey`, `jackettHealthUrl`).
- `RegistryProviderBackend` (interfaz `ProviderBackend`, `search(ctx)`) se integra al fan-out de
  `TorrentSearchApi`. Se mantiene la **búsqueda progresiva de 2 fases** (`runSearchFlow`):
  - **Fase 1 (rápida):** Apibay + Knaben → emite resultados de inmediato.
  - **Fase 2 (lenta):** trackers on-device (scraping + posible WebView de Cloudflare) → emite los nuevos.
- El gating por `useful < minResults` deja de aplicar al tier de trackers: como es la **única fuente de
  español**, corre **siempre** (ya no es un fallback condicional). El `ctx` (SearchContext) siempre está
  disponible en las rutas de búsqueda, así que el backend recibe lo que necesita.

**Decisión de integración:** `RegistryProviderBackend` se consume vía el canal `fallbackBackends`
existente de `TorrentSearchApi`, pero se cambia la condición para que corra incondicionalmente (no solo
cuando `status == DOWN || useful < minResults`). Esto minimiza el cambio al pipeline y conserva el
dedupe/ranking. Alternativa descartada: envolverlo como `TorrentBackend` (query-based) — perdería el
`SearchContext` (season/episode/year) que la relevancia on-device necesita.

---

## 3. Trackers a portar

Definiciones **bundled** en `app/src/main/assets/providers.json`, guiadas por el Cardigann de Jackett.

| id | Dominio actual | Cloudflare | Rol / idioma |
|---|---|---|---|
| `wolfmax4k` | wolfmax4k.com | **Sí** | Español (cine 1080p/4K) |
| `dontorrent` | todotorrents.org | runtime* | Español (catálogo amplio) |
| `divxtotal` | divxtotal.foo | runtime* | Español |
| `elitetorrent` | elitetorrent.wf | runtime* | Español/castellano |
| `nyaa` | nyaa.si | no | Anime (jap-sub) |
| `eztv` | eztvx.to | **Sí** | Series (inglés; complementa apibay) |

\* **runtime** = no marcamos `needsCloudflare` a priori; el `HttpFetcher` detecta el challenge cuando
aparece y dispara el solver. El flag `needsCloudflare:true` solo se usa como pista para **pre-calentar**
la cookie (ver §4). Para wolfmax4k/eztv se marca `true` (sabemos que lo usan).

### Robustez ante rotación de dominios

Estos trackers **cambian de dominio con frecuencia** (dontorrent ya va por su 4º: todotorrents.org).
Se añade al esquema un campo **`hostAlt: [String]`** (lista de espejos), replicando lo que ya hace la
capa web (`WebSourceDefinition.hostAlt`). El `DeclarativeHtmlBackend` intenta `baseUrl` y, si falla o
devuelve vacío/challenge irresoluble, reintenta con cada `hostAlt`. Esto exige:
- Extender `ProviderDefinition` con `hostAlt` (parse + validación; default lista vacía).
- Extender `DeclarativeHtmlBackend.search` para iterar `[baseUrl] + hostAlt` hasta obtener resultados.

### Contenido inglés/general

No se portan `thepiratebay`, `yts`, `limetorrents`, `torrentgalaxyclone`: **apibay + knaben ya los
cubren on-device** sin scraping. Portarlos sería trabajo redundante (YAGNI).

---

## 4. Endurecer el solver de Cloudflare

`WebViewCloudflareSolver` hoy resuelve **fresco en cada búsqueda** (WebView de ~hasta 20 s, serializado
por mutex). Con varios trackers Cloudflare esto degrada la UX. Cambios:

### 4.1 Caché de `cf_clearance` por host

- Nuevo componente (o extensión del solver) que cachea `CfClearance` **por host** con TTL configurable
  (default **45 min**; el cf_clearance de Cloudflare suele durar más, pero un TTL conservador evita usar
  una cookie expirada).
- `HttpFetcher`/solver consultan la caché **antes** de lanzar el WebView; si hay clearance vigente para
  el host, se reutiliza directo en la petición OkHttp.
- El `CookieManager` de Android ya persiste cookies a disco entre arranques; la caché en memoria
  host→`CfClearance` se reconstruye lazy (primer miss → WebView).

### 4.2 Pre-warm opcional

- Al primer `search()` de una sesión, disparar en background (sin bloquear) el solve de los hosts con
  `needsCloudflare:true`, para que la cookie esté lista cuando el scraping los toque.
- Best-effort: si el pre-warm falla, el flujo normal (detección en runtime + solve on-demand) sigue
  funcionando.

### 4.3 Aislamiento (ya existente, se conserva)

- Mutex: un challenge a la vez.
- Timeout duro → degradado a `null` → el proveedor degrada a lista vacía. Un tracker con Cloudflare
  irresoluble **nunca** rompe a los demás.

---

## 5. Hot-update sin servidor (opción C)

- `providers.json` **completo bundled** en assets = fuente de verdad offline. La app **siempre**
  funciona sin red hacia ningún host de definiciones.
- Se conserva el merge remoto de definiciones, pero `DEFAULT_PROVIDERS_URL` deja de apuntar a `blog` y
  pasa a un **archivo estático público** que controla el usuario:
  `https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json`
- Comportamiento: al arranque, intento de bajar el JSON remoto (timeout corto) y `ProviderRegistry.merge`
  sobre las bundled. Si falla o no hay red, quedan las bundled. Sin FlareSolverr, sin server headless:
  es solo un archivo de texto en GitHub.
- **Setup de usuario (una vez, documentado):** crear el repo público `lordmacu/arkiv-providers` con el
  `providers.json`. Arreglar un tracker roto = editar ese archivo (desde web/móvil) → los dispositivos se
  auto-actualizan al siguiente arranque, sin recompilar.
- El `providers.json` bundled y el remoto comparten el mismo esquema; el repo se siembra con una copia
  del bundled.

**Nota:** `web_sources.json` (capa web) **no se toca** en este branch; su hot-update sigue como está
hasta que se aborde la capa web.

---

## 6. Corte de la dependencia a `blog` (torrents)

Se eliminan del código de torrents:
- Clase `JackettBackend` (en `TorrentSearchApi.kt`) y su uso en `AppGraph`.
- Clase `JackettHealth` + interfaz `HealthGate` + su uso; simplificar `TorrentSearchApi` para no requerir
  `healthGate` (o dejarlo opcional y no cablearlo).
- Settings: `jackettBaseUrl`, `jackettApiKey`, `jackettHealthUrl` (`DEFAULT_JACKETT_URL`, `_KEY`,
  `_HEALTH_URL`) y sus setters/UI si están expuestos en pantalla de ajustes.
- `DEFAULT_PROVIDERS_URL` reapuntado a GitHub raw (§5).
- **Sin cambios** a `web_sources.json`, `WebResolverApi`, `webResolverUrl` (capa web, otro branch).

La lógica de `resolveSource`/`resolveDownloadUrl` (seguir el `/dl` con UA de navegador) **se conserva**:
sigue siendo necesaria para los trackers que sirven un `.torrent` o redirigen a magnet, y ya corre
on-device (no depende de blog).

---

## 7. Validación contra HTML vivo

El riesgo real no es el código, son los **selectores**: se rompen si el sitio cambió su HTML. Por cada
tracker portado:

1. Portar la def desde el Cardigann YAML de Jackett (selectores, search path, detail).
2. Bajar el **HTML vivo** con el mecanismo `WebDiag`/`dumpProviderHtml` que ya existe en `AppGraph`
   (dump por `id` a `<dir>/<id>.html`) y verificar `rowSelector` / `name` / `magnet` / `seeds` / `size`
   contra la página real (incluyendo espejos `hostAlt`).
3. **Test unitario** con un fixture del HTML real (patrón `DeclarativeHtmlBackendTest`): asegura que el
   parsing extrae los campos esperados y sobrevive a un refactor.
4. Verificación en device (S24+ / Fire Stick) con búsquedas reales por idioma.
5. Si un tracker está irresoluble tras esfuerzo razonable (dominio caído, Cloudflare que el WebView no
   pasa, HTML irreconocible) → dejar la entrada `enabled:false` con `_nota` del motivo. **No** bloquear el
   resto.

---

## 8. Estrategia de testing

- **Unit por definición:** fixtures de HTML real por tracker (`src/test/resources/providers/…`).
- **Parsing:** cobertura de `DeclarativeHtmlBackend` con `hostAlt` (fallback de dominio), detail-page,
  charset.
- **Registro:** `RegistryProviderBackend` corre siempre; aislamiento de fallos (un proveedor que lanza no
  tumba a los demás).
- **Caché de Cloudflare:** TTL, reuso por host, expiración — **pura, sin WebView** (inyectar un clock y un
  solver fake).
- **Merge de definiciones:** bundled + remoto; remoto inválido/caído → quedan las bundled.
- **Pipeline:** los tests existentes de `TorrentSearchApi` (clasificación, ranking, dedupe) deben seguir
  verdes tras quitar Jackett.
- **En device:** búsquedas latino / castellano / anime en S24+ y Fire Stick.

---

## 9. Fuera de alcance (explícito)

- Capa web (`web_sources.json`, `WebSourceEngine` de catálogo web).
- Web-resolver / descifrado de embeds headless (`/resolve`, Playwright, embed69). → **Otro branch.**
- `alfa-api` (backend Alfa en blog). → Otro branch.
- Portar trackers en inglés que apibay/knaben ya cubren.

---

## 10. Criterios de éxito

- La app busca y reproduce torrents (latino, castellano, anime, inglés) **sin ninguna petición a
  `jackett.comparadorinternet.co`** para el path de torrents.
- Al menos los trackers españoles marcados `enabled:true` devuelven resultados verificados en device.
- Cloudflare se resuelve on-device y la cookie se **reutiliza** (segunda búsqueda al mismo host no
  relanza el WebView dentro del TTL).
- Un `providers.json` remoto editado en GitHub se refleja en los dispositivos al siguiente arranque, sin
  recompilar; sin red, la app usa las definiciones bundled.
- Los tests existentes de torrents siguen verdes.
