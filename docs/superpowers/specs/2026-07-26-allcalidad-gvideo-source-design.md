# Fuente allcalidad + Drive (gvideo) — Diseño

**Fecha:** 2026-07-26
**Rama:** `feat/torrents-via-backend-api` (feature web-source, independiente del pipeline Mirror)

## Objetivo

Sumar **allcalidad** (`allcalidad.re`) como fuente de streaming web latino en Arkiv,
reproduciendo lo que ya hacen Alfa/Balandro: buscar por título, anclar al TMDB elegido, y
resolver el stream final — incluyendo embeds de **Google Drive (gvideo)**.

## Contexto: cómo funciona la capa web hoy

- `app/src/main/assets/web_sources.json` = definiciones **declarativas** de sitios. Cada fuente
  se busca por título; `WebTmdbMatcher` ancla el resultado al TMDB que el usuario ya eligió
  (mismo patrón que el catálogo — no se inventa metadata).
- Ya existe el backend **`api`** (JSON): ej. `lamovie` usa `search` → `listPath`/`title` →
  `pageUrl` con placeholder `{_id}`.
- El `pageUrl` resultante se manda al **resolver headless** (`jackett.comparadorinternet.co/resolve`,
  Playwright en `blog`, `docs/runbooks/web-resolver-src/server.js`). Ese resolver carga la página
  con navegador real (pasa Cloudflare), clickea, y **sniffea la red** por
  `.m3u8/.mp4/videoplayback/hls`. Trae proxy con `Referer` (arregla 403 geo/IP) y reescritura HLS.

## El punto clave

allcalidad **no** entrega el stream en una página HTML: lo entrega por su **API JSON**:

- Búsqueda: `GET /api/rest/search?post_type=movies,tvshows,animes&query={q}&posts_per_page=16&page=1`
  → `data.posts[]` con `{_id, title, original_title, slug, type, release_date, images.poster}`.
- Player: `GET /api/rest/player?post_id={_id}&_any=1`
  → `data.embeds[]` con `{lang, quality, url}` (Drive/gvideo, streamtape, etc.). **Protegido tras
  Cloudflare** — por eso lo pide el resolver (navegador real), no la app.

El sniffer genérico carga *una página* y escucha; no parsea ese JSON. Por eso el trabajo se
divide en dos partes.

## Diseño

### Parte 1 — App (declarativo, sin código Kotlin)

Agregar una entrada `allcalidad` a `app/src/main/assets/web_sources.json`, backend `api`.
**Cero código Kotlin nuevo:** `WebJsonBackend`/`WebApiRules` ya soportan exactamente estos campos
(`listPath`, `title`, `pageUrl`, `poster`, `year`), y el parser ya aplica `Regex("\\d{4}")` sobre
`year`, así que `release_date` ("2003-05-15") rinde `2003` solo.

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
}
```

- **`title` = `"title"` (el campo español, ej. "Matrix recargado (2003)"), NO `original_title`.**
  `WebTmdbMatcher.enrich` busca en TMDB *con el título de la web* y exige
  `normalize(web.title) == normalize(hit.title)` (±1 año). El TMDB de Arkiv es es-MX, así que el
  título español matchea (normalize() strippea el `(2003)`); `original_title` inglés fallaría.
- `year` sale de `release_date` (el parser ya le hace regex de 4 dígitos). `years` es ids
  codificados, inútil.
- `needsCloudflare: false`: la API de `/api/rest/search` respondió JSON limpio con solo un UA
  (verificado por curl). Si en el device la búsqueda devuelve un challenge, flipear a `true`.
- `poster` (`images.poster`) queda relativo; `WebTmdbMatcher` lo pisa con el de TMDB al matchear.

### Parte 2 — Resolver (server, el trabajo real)

Una rama nueva en `resolveOne(pageUrl)` de `server.js`:

1. **Detección:** si el `pageUrl` matchea `/api/rest/player` (host allcalidad).
2. **Fetch JSON con navegador real:** cargar el endpoint (o `ctx.request.get` con los headers/cookies
   de una navegación previa a `baseUrl` para pasar Cloudflare) y parsear `data.embeds[].url`.
3. **Targets:** meter esos `embeds[].url` como `targets` al loop de sniff que **ya existe**
   (preferir `lang` latino primero, igual que el branch embed69).
4. **Drive/gvideo:** `videoplayback` **ya está** en `VIDEO_RX`. La hipótesis (a validar en device)
   es que cargar la preview de Drive (`drive.google.com/file/d/{id}/preview` o `/uc?id=`) hace que
   Playwright capture el `...videoplayback...` sin código Drive específico.
   - **Fallback si el sniffer no basta:** portar el resolver `gvideo.py` de Alfa/Balandro
     (GET a `get_video_info?docid=...` → parsear el stream map por `itag` → URL directa + cookie).
     Este fallback queda **fuera del alcance inicial**; se agrega solo si la validación en device falla.

### Flujo end-to-end

```
Usuario elige un título (TMDB) en Arkiv
  → WebSourceEngine busca en allcalidad: /api/rest/search?query=<título>
  → WebJsonBackend parsea data.posts[], WebTmdbMatcher ancla por título+año
  → resultado con pageUrl = /api/rest/player?post_id=<_id>&_any=1
  → al reproducir: WebResolverApi.resolve(pageUrl) → resolver en blog
     → (rama allcalidad) fetch JSON embeds → sniff cada embed (Drive incluido)
     → devuelve streamUrl proxeada (HLS reescrito / mp4 / videoplayback con Referer)
```

## Alcance / YAGNI

- **Sí:** def declarativa allcalidad (movies+tvshows+animes) + rama allcalidad en el resolver +
  apoyarse en el sniffer genérico para Drive + deploy a blog + validación en device (1 peli, 1 serie).
- **No (por ahora):** resolver gvideo determinístico portado de Alfa (solo si el sniffer falla);
  hot-update repo (va bundled en el APK); FilterTools/autoplay de Alfa; capítulos de series
  (primero validar películas; series si el player-API por episodio lo permite igual).

## Testing

- **Resolver (server.js):** unit test de la extracción de embeds del JSON (`data.embeds[].url`,
  orden latino-primero) con un fixture del JSON real. El sniff/Playwright se valida en vivo.
- **App:** si se extiende `WebJsonBackend`, unit test del parser (`titleAlt`, `year {field,regex}`)
  contra un fixture de `data.posts[]` real, y que los defs viejos (string `title`/`year`) sigan
  parseando igual (retro-compat).
- **End-to-end:** instalar en el device y reproducir Matrix Reloaded (peli) — confirmar que el
  embed Drive arranca vía el resolver.

## Riesgos

- El player-API está tras Cloudflare: el resolver debe navegar `baseUrl` primero para tomar la
  cookie `cf_clearance` antes del `request.get` al endpoint JSON (el server ya tiene navegador real).
- Drive puede requerir cookie propia del `videoplayback`; el proxy del resolver ya reenvía headers,
  pero si Google exige la cookie de sesión de Drive, se activa el fallback gvideo.py.
```
