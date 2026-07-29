# Guía: trackers de torrents que cargan resultados por JavaScript (`renderJs`)

Cómo scrapear on-device un tracker cuyos resultados **no están en el HTML servido** sino que el
propio JS del sitio los inyecta en el cliente (AJAX/fetch + render en el navegador). Ejemplo real
implementado: **wolfmax4k**.

Spec/plan de origen: `docs/superpowers/specs/2026-07-24-webview-render-provider-design.md` y
`docs/superpowers/plans/2026-07-24-webview-render-provider.md`.

---

## 1. ¿Cuándo se necesita?

Sospecha `renderJs` cuando:

- `curl "https://sitio/buscar/<algo>"` responde **200 OK** pero el contenedor de resultados llega
  **vacío** (p. ej. `<div id="resDetalle"></div>` sin hijos).
- En el HTML ves un `<form>` con un token/CSRF y un `.js` que hace `XMLHttpRequest`/`fetch` a un
  endpoint (`.../data.find.php`, `/api/search`, etc.) y arma las tarjetas en el navegador.
- El scraping declarativo normal (HTTP + selectores) devuelve `filas=0` aunque el sitio sí tiene
  resultados en el navegador de verdad.

En cambio, **no** uses `renderJs` si el HTML servido ya trae los resultados (usa el fetcher HTTP
normal, que es mucho más rápido). El WebView es lento y serializado: solo para lo que de verdad lo
necesita.

## 2. Cómo funciona (ya construido, no hay que tocar código para un tracker nuevo)

Al marcar un proveedor con `renderJs`, la obtención del **listado** pasa por
`WebViewPageRenderer` (`app/src/main/java/com/arkiv/player/data/catalog/providers/JsRenderer.kt`):

1. Carga la URL de búsqueda en un **WebView headless** (con User-Agent de navegador, cookies/headers
   del proveedor si los declaraste en `requestHeaders`).
2. Deja correr el **JS del propio sitio** (hace su CSRF/POST/render — nosotros NO reverse-engineamos
   su API).
3. Hace **poll** de `document.querySelectorAll(<readySelector>).length` cada ~300 ms hasta que haya
   ≥1 resultado.
4. Devuelve `document.documentElement.outerHTML` **ya renderizado**.
5. Ese HTML entra al **mismo parser declarativo** (`rowSelector`/`name`/`torrentUrl`/`detail`) que
   cualquier otro tracker.

Disciplina/seguridad (igual que el `WebViewCloudflareSolver`): un WebView a la vez (Mutex), **timeout
duro ~18 s → vacío** (nunca cuelga ni rompe a los demás), corre en Main, sin puentes JS a la app.
Cap de **2 queries** por proveedor `renderJs` (evita colas largas de renders). La **página de
detalle** (la ficha) se baja con el fetcher HTTP normal, no con WebView (suele ser HTML servido).

El seam está en `DeclarativeHtmlBackend.searchOne`: si `def.renderJs && def.readySelector != null`
usa el `jsRenderer`; si no, el `fetcher` HTTP. Cableado en `AppGraph` (el renderer se pasa a
`RegistryProviderBackend`). El flag `settings.cloudflareSolverEnabled` habilita/inhabilita TODO lo
basado en WebView (solver + renderer).

## 3. Cómo agregar un tracker `renderJs` nuevo (solo JSON + un test)

### Paso 1 — Investigar el sitio en vivo
```bash
UA='Mozilla/5.0 (Linux; Android 14) Chrome/120.0 Mobile Safari/537.36'
curl -sL -A "$UA" "https://<sitio>/buscar/superman" -o /tmp/x.html
# ¿el contenedor de resultados está vacío? ¿qué JS lo rellena? ¿qué selector tienen las tarjetas ya renderizadas?
```
Como las tarjetas solo existen tras ejecutar el JS, reconstruye su estructura mirando el `.js` del
sitio (la función que hace `innerHTML`/append) o —mejor— con un **dump del DOM renderizado en el
device** (abrir la búsqueda en la app y volcar el HTML). Anota:
- `searchPath` (ej. `/buscar/{query}`)
- `readySelector` y `rowSelector` (normalmente el MISMO: el selector de la tarjeta de resultado)
- `name`, y el enlace de la tarjeta (`torrentUrl` = href de la tarjeta → ficha)
- en la **ficha**: dónde está el `.torrent`/magnet (`detail`)

### Paso 2 — Agregar la entrada a `app/src/main/assets/providers.json`
Ejemplo real (wolfmax4k):
```json
{
  "id": "wolfmax4k",
  "name": "Wolfmax4K",
  "enabled": true,
  "priority": 66,
  "baseUrl": "https://wolfmax4k.com",
  "searchPath": "/buscar/{query}",
  "renderJs": true,
  "readySelector": "#resDetalle a.card-movie",
  "keywords": { "movie": "{title}", "tv": "{title}" },
  "languageTokens": ["latino", "castellano"],
  "parser": {
    "rowSelector": "#resDetalle a.card-movie",
    "name": { "selector": "h3", "attr": "text" },
    "torrentUrl": { "selector": "", "attr": "href", "resolve": "absolute" }
  },
  "detail": {
    "followFrom": "torrentUrl",
    "torrentUrl": { "selector": "a[href*='.torrent']", "attr": "href", "resolve": "absolute" }
  }
}
```
Notas:
- **`keywords` es OBLIGATORIO** — sin él, `ProviderDefinition.fromJson` descarta la entrada en
  silencio (lo caza `RealProvidersJsonTest`).
- `torrentUrl.selector: ""` = "usar el elemento fila mismo" (cuando la tarjeta ES el `<a>`).
- Si la ficha solo tiene un `.torrent` (no magnet), usa `detail.torrentUrl` — `resolveDownloadUrl`
  ya sigue redirects 302 y descarga el `.torrent`. Si la ficha tiene magnet, usa `detail.magnet`.
- `requestHeaders` si el sitio necesita cookie/Referer (ej. eztv usa `Cookie: layout=def_wlinks`).

### Paso 3 — Test de regresión con el DOM renderizado
Crea `app/src/test/java/com/arkiv/player/data/catalog/providers/<Id>DefinitionTest.kt` que:
- lea la def con `BundledProviders.byId("<id>")`,
- corra `HtmlParser.parseList(def, <HTML renderizado recortado real>, def.baseUrl)` y asegure
  `name` + `torrentUrl`,
- (ideal) un end-to-end con un `JsRenderer` fake que devuelve el HTML renderizado y un `PageFetcher`
  fake que sirve la ficha con el `.torrent`, asertando el `downloadUrl` final.
Patrón: `Wolfmax4kDefinitionTest.kt`.

### Paso 4 — Sincronizar el hot-update
**Copiar `app/src/main/assets/providers.json` al repo remoto** `lordmacu/arkiv-providers` y `push`.
Si no, el remoto (que **gana por `id`**) revierte tu cambio al siguiente arranque. Ver
`docs/arkiv-providers-README.md`.

### Paso 5 — Verificar en device
`./gradlew testDebugUnitTest` + `assembleDebug` + instalar (ver la nota de ADB WiFi). En logcat,
tag **`ArkivProv`**:
- `render onPageFinished, polling '<readySelector>'` → el render arrancó,
- `provider=<id> ... filas=N` con N>0 → parseó resultados.
Si `readySelector` nunca matchea (el sitio no tiene ese título) → timeout limpio sin filas (correcto).

## 4. Diagnóstico rápido si un `renderJs` no trae nada

| Síntoma en logcat `ArkivProv` | Causa probable |
|---|---|
| Ni una línea del proveedor, ni `render onPageFinished` | El def llegó `enabled:false` (¿remoto lo pisó? ver hot-update) o `cloudflareSolverEnabled=false` |
| `render onPageFinished` pero nunca `provider=<id> filas=N` | El `readySelector` no matchea (título ausente, o el selector cambió) → timeout |
| `filas=0` con `rowSelector matcheó 0 filas` | El `rowSelector` no coincide con el DOM renderizado (el sitio cambió su markup) |
| Página nunca carga (sin `onPageFinished`) | WAF/UA: el WebView usa UA de navegador (`BROWSER_UA`); si aún falla, el sitio bloquea headless |

Si tras esfuerzo razonable no se resuelve: dejar `enabled:false` + `_nota` del motivo, sin bloquear al
resto.
