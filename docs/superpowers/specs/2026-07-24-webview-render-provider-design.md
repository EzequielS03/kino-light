# Render por WebView para proveedores JS-rendered — Diseño

**Fecha:** 2026-07-24
**Branch:** `feat/torrents-ondevice-sin-servidor` (continúa la feature de torrents on-device)
**Alcance:** Añadir un mecanismo **genérico y declarativo** para scrapear trackers cuyos resultados se
cargan por JavaScript en el cliente (no hay HTML de resultados servido). Primer consumidor: **wolfmax4k**.

---

## 1. Contexto y motivación

Al portar los trackers on-device (spec `2026-07-24-torrents-sin-servidor`), **wolfmax4k** quedó
`enabled:false`: sus resultados no están en el HTML servido. Investigación en vivo (curl):

- `GET https://wolfmax4k.com/buscar/{query}` → 200 OK; el HTML trae el contenedor `#resDetalle` **vacío**
  y un token CSRF en un input: `<input name="token" value="…">`.
- El JS del sitio (`assets/u/temp/js/datafind.js`) hace un `XMLHttpRequest POST` con `FormData`
  (token + query) a `https://wolfmax4k.com/mvc/controllers/data.find.php`, y **inyecta** las tarjetas
  de resultado en `#resDetalle` como `a.card.card-movie` (con `h3` de título y `href` a la ficha
  `/pelicula/<id>/…`).
- La **ficha** de cada resultado es HTML servido normal y expone un enlace `.torrent` directo — el
  mismo patrón que dontorrent/divxtotal, ya resuelto por `DetailRules.torrentUrl`
  (spec/plan de torrents on-device, Task 12b).

Conclusión: lo único que falta es **obtener el DOM ya renderizado por el JS del sitio**. De ahí en
adelante, el parser declarativo existente (`rowSelector`/`name`/`torrentUrl`/`detail`) aplica igual.

### Objetivo explícito: que quede estándar

No un parche para wolfmax4k: un mecanismo genérico que **cualquier** tracker JS-rendered pueda usar
declarando dos campos, ejecutando el **propio JS del sitio** (sin reverse-engineering de su API por
sitio).

---

## 2. Diseño

### 2.1 Esquema declarativo (dos campos nuevos)

`ProviderDefinition` gana:
- `renderJs: Boolean = false` — si true, la página de listado se obtiene renderizando su JS.
- `readySelector: String? = null` — selector CSS que indica "resultados listos" (el render espera
  hasta que matchee ≥1 elemento).

Ejemplo (wolfmax4k):
```json
{
  "id": "wolfmax4k", "name": "Wolfmax4K", "enabled": true, "priority": 66,
  "baseUrl": "https://wolfmax4k.com", "searchPath": "/buscar/{query}",
  "renderJs": true, "readySelector": "#resDetalle a.card.card-movie",
  "languageTokens": ["latino", "castellano"],
  "parser": {
    "rowSelector": "#resDetalle a.card.card-movie",
    "name": { "selector": "h3", "attr": "text" },
    "torrentUrl": { "selector": "", "attr": "href", "resolve": "absolute" }
  },
  "detail": { "followFrom": "torrentUrl",
    "torrentUrl": { "selector": "a[href*='.torrent']", "attr": "href", "resolve": "absolute" } }
}
```
(rowSelector = la tarjeta `<a>`; `torrentUrl` con selector `""` = el propio elemento fila, idiom ya
soportado por `HtmlParser.applyRule`. La ficha se resuelve con `detail.torrentUrl`, reusando Task 12b.)

### 2.2 Componente: `WebViewPageRenderer`

Clase nueva, hermana de `WebViewCloudflareSolver`, con su MISMA disciplina:
- Un WebView headless reutilizable, **serializado con Mutex** (un render a la vez), **timeout duro**,
  ejecutado en `Dispatchers.Main`.
- `suspend fun render(url: String, readySelector: String, headers: Map<String,String>): String?`:
  1. Carga `url` (con `User-Agent` de navegador; si hay `headers`, inyectarlos — p. ej. cookies).
  2. Tras `onPageFinished`, **poll** con `evaluateJavascript` de
     `document.querySelectorAll(<readySelector JSON-escapado>).length` cada ~300 ms.
  3. Cuando el conteo es > 0, devuelve `document.documentElement.outerHTML`.
  4. **Timeout (~18 s) → devuelve null** (degrada a vacío). Nunca cuelga la UI.
- JS habilitado + DOM storage, igual que el solver. Sin bridges JS a la app (mismo modelo de
  confianza que el `WebViewCloudflareSolver`, que ya carga sitios en WebView).
- Flag de habilitación: **reutiliza `settings.cloudflareSolverEnabled`** (pasa a significar, más
  ampliamente, "permitir scraping basado en WebView"): si el usuario lo apagó, el renderer también
  queda inerte. No se añade un setting nuevo (menos superficie); el nombre queda algo más amplio que
  solo Cloudflare, aceptable.

### 2.3 Seam: selección de fetcher en `DeclarativeHtmlBackend`

`DeclarativeHtmlBackend` recibe, además del `fetcher: PageFetcher` actual, un
`jsRenderer: WebViewPageRenderer? = null`. En `searchOne`, al obtener la página de LISTADO:
```kotlin
val html = if (def.renderJs && def.readySelector != null && jsRenderer != null)
    jsRenderer.render(url, def.readySelector, def.requestHeaders)
else
    fetcher.fetch(url, def.charset, def.requestHeaders)
```
La **página de detalle** (`resolveDetail`) sigue usando el `fetcher` normal (la ficha es HTML servido;
no necesita JS). Así el render solo se paga en el listado del proveedor `renderJs`.

`PageFetcher.fetch` NO cambia de firma; el renderer tiene su propia firma con `readySelector`.

### 2.4 Pipeline y costo

- Los proveedores `renderJs` corren en el **tier on-device (fase 2 "lenta")** del pipeline progresivo
  que ya existe — apibay/knaben pintan primero; el WebView no retrasa la primera emisión.
- Serializado + timeout: un render lento/colgado degrada a vacío sin romper a los demás (ya hay
  `runCatching` por proveedor).
- Solo los proveedores con `renderJs:true` pagan el WebView.

---

## 3. Wiring (AppGraph)

- Crear `WebViewPageRenderer(appContext, enabled = { settings.cloudflareSolverEnabled.value })`.
- Pasarlo a `RegistryProviderBackend` → `DeclarativeHtmlBackend` como `jsRenderer`.
  (`RegistryProviderBackend` construye `DeclarativeHtmlBackend(def, fetcher)`; se le añade el
  `jsRenderer` opcional.)

---

## 4. Testing

- **Unit (parsing):** fixture con el DOM **renderizado** de wolfmax4k (`#resDetalle` con 1-2
  `a.card.card-movie` reales, tomados de un dump del device o reconstruidos del template del JS) →
  `HtmlParser.parseList` extrae `name` + `torrentUrl`. + test de `detail.torrentUrl` (patrón Task 12b).
- **Unit (schema):** `ProviderDefinition.fromJson` parsea `renderJs`/`readySelector` (y defaults
  false/null cuando faltan; no rompe defs existentes).
- **Unit (seam):** `DeclarativeHtmlBackend` con un `jsRenderer` fake — cuando `def.renderJs`, usa el
  renderer (no el `fetcher`), y su HTML se parsea igual. Cuando no, usa el `fetcher` (comportamiento
  idéntico al actual).
- **Unit (puro):** el armado del script de poll (`querySelectorAll` con el selector JSON-escapado) como
  función pura testeable (evita inyección/comillas rotas).
- **No unit-testable:** el WebView vivo (necesita device/instrumentación). Se valida en el device
  (§5).

---

## 5. Validación en device (gate real)

- Instalar en S24+ / Fire Stick, buscar una película conocida y confirmar que wolfmax4k **devuelve
  fuentes** y que al menos una **reproduce** (render → tarjeta → ficha → `.torrent` → resolveSource).
- Confirmar en logcat que el render espera al `readySelector` y no cuelga (timeout → vacío).
- Si el sitio cambia su JS/estructura y el render deja de dar resultados: `enabled:false` + `_nota`,
  sin bloquear al resto (mismo protocolo que el resto de trackers).

---

## 6. Fuera de alcance

- No se toca la capa web (`web_sources.json`, web-resolver) — otro branch.
- No se implementa el POST directo a `data.find.php` (alternativa más rápida pero específica por
  sitio; el WebView genérico se prefirió a propósito por reusabilidad).
- No se añade un pool de WebViews ni render concurrente (un render a la vez, como el solver).

---

## 7. Criterios de éxito

- Un proveedor puede declararse `renderJs:true` + `readySelector` y ser scrapeado con los MISMOS
  selectores declarativos que cualquier otro — sin código nuevo por sitio.
- wolfmax4k pasa de `enabled:false` a devolver fuentes reproducibles (verificado en device).
- Los proveedores sin `renderJs` se comportan **exactamente igual** que antes (fetcher HTTP normal).
- Timeout/fallo del render → vacío, nunca cuelga ni rompe a los demás trackers.
- Los tests existentes siguen verdes; se agregan los unitarios de §4.
