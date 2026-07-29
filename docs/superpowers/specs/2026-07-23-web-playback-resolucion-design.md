# Diseño — Reproducción de fuentes web (resolución embed→stream) en el player unificado

**Fecha:** 2026-07-23
**Rama:** `feat/torrent-buffering-tier1` (in-place)
**Estado:** Diseño aprobado, pendiente plan

## Objetivo

Hacer que las fuentes **web** (que hoy solo se listan en la hoja de fuentes con click stub) **se reproduzcan** en el **mismo reproductor unificado** que ya reproduce archive.org y torrent, heredando automáticamente: seek, controles, velocidad/zoom, gestos, **Chromecast, DLNA**, **subtítulos e idiomas**. Alcance: **películas y episodios**.

## Principio rector

El player es **genérico sobre una URL de stream**: `PlayerViewModel.load(episodeId)` ramifica por `SourceKind` y, una vez que tiene una `mediaUrl`, todo lo demás (controles/cast/dlna/subs/audio) funciona igual para cualquier fuente. Por lo tanto **el único trabajo nuevo real es RESOLVER `pageUrl` (web) → `streamUrl` (mp4/m3u8)**; el resto es plumbing para enchufar esa URL al player existente.

## Decisión arquitectónica: sniffer headless en blog (lo más robusto)

Resolver embed→stream con **resolvers por host portados de Alfa** (los ~199 "servers": doodstream/streamtape/voe…) es frágil y de mantenimiento infinito (cada host cambia su JS ofuscado). En su lugar:

**Un servicio resolver en `blog` que abre la página en un navegador headless (Playwright/Chromium) y SNIFEA la request real del video.** Es **host-agnóstico** (no hay código por host), robusto (no se rompe con cambios de JS), y aprovecha que blog ya corre Chromium (FlareSolverr) y tiene otra IP/red (bypassa geo/Cloudflare, ya verificado). Alfa se usa como **referencia** para el paso detalle→embed (qué iframe/player buscar), no se portan sus resolvers.

## Componentes

### 1. Servicio resolver en blog — `web-resolver`

- Endpoint `GET /resolve?url=<pageUrl>` (expuesto por Cloudflare tunnel, como Jackett).
- Con **Playwright** (necesario: FlareSolverr NO expone intercepción de red; solo devuelve HTML final):
  1. Navega a `pageUrl` (página de detalle de la peli/capítulo).
  2. Encuentra y carga el reproductor/embed (guiado por los patrones de `findvideos` de Alfa: iframes de servers conocidos).
  3. Intercepta la red y captura la **URL del stream** (`.m3u8`/`.mp4`), sus **headers necesarios** (Referer/UA) y las **pistas de subtítulos** (`.vtt`/`.srt`) que cargue el player.
  4. Responde JSON: `{ ok: bool, streamUrl: string, headers?: {Referer,User-Agent,...}, subtitles?: [{lang, url}], error?: string }`.
- **Timeout** duro (~30s) y serializado (un browser a la vez; blog es 2 CPU). Cache corta por `pageUrl` para reintentos.
- Se prepara/compila en el **Mac** y se sube (regla del proyecto: nada pesado en blog). Node + Playwright; el Chromium se instala una vez en blog.
- Config app: `settings.webResolverUrl` (default en el mismo blog, filename/ruta `/resolve`), igual patrón que `webSourcesUrl`.

### 2. App — enchufe al player unificado

- **`SourceKind.WEB`** nuevo en `playback/PlayerSource.kt`; `kindFor(episodeId)`: prefijo `"web:"` → WEB. Revisar todos los `when (SourceKind)` para exhaustividad.
- **Episodio web en el repo:** un método `repository.addWebSource(pageUrl, title, posterUrl, language, season?, episode?)` que crea/persiste un "episodio" con `id` prefijado `web:` guardando la `pageUrl` (+ metadata). Análogo a `addTorrentMagnet` (que guarda un magnet). Room: una tabla/columna para la web source (o reutilizar el store de torrent con un tipo).
- **Click en la hoja:** `playSource(PlaySource.Web)` deja de ser stub → `addWebSource(...)` desde el `WebResult` (pageUrl, título, póster, idioma, y season/episode si es capítulo) → `onPlay(episodeId)` (mismo camino que `playArchive`/`play` de torrent).
- **`PlayerViewModel.loadWeb(episodeId)`** (molde: `loadTorrent`):
  1. `pageUrl = repo.webSourceForEpisode(episodeId)`.
  2. `resolved = webResolver.resolve(pageUrl)` (llamada HTTP a blog `/resolve`). Muestra progreso en `prepProgress` ("Resolviendo…") como el pre-buffer del torrent.
  3. Si falla → `_error`. Si ok → arma `PlayerData(kind=WEB, mediaUrl=resolved.streamUrl, castUrl=resolved.streamUrl, title=…, artworkUrl=poster, subtitles=resolved.subtitles)`.
  4. `_playlist.value = PlaylistData(listOf(item), 0, safeStartPosition(...))`.
- **Subtítulos sniffeados:** se adjuntan como pistas externas al `MediaItem` (mecanismo de subtítulos externos que ya usa el player; si `PlayerData` no tiene campo de subs, agregarlo). El **audio multi-pista** sale del propio stream (m3u8) vía VLC — el selector de audio del player ya existe.
- **Headers del stream:** si el stream requiere `Referer`/`UA`, pasarlos al data source de VLC (media3/libVLC soporta headers). Para VLC en el celu funciona; ver limitación de cast abajo.

### 3. Alfa como referencia (no se porta)

Consultar `channels/<id>.py::findvideos` para saber qué iframes/servers expone cada detalle y las etiquetas de idioma; y `servers/*.py` para entender qué URL final produce cada host (validar que el sniffer captura lo mismo). No se traduce ese código: el sniffer lo cubre genéricamente.

## Flujo completo

```
Hoja de fuentes → click PlaySource.Web(WebResult)
  → repo.addWebSource(pageUrl,...) → episodeId "web:..." → onPlay(episodeId)
    → PlayerViewModel.load → kindFor=WEB → loadWeb
      → blog GET /resolve?url=pageUrl  (Playwright snifea)
        → {streamUrl, headers, subtitles}
      → PlayerData(kind=WEB, mediaUrl=streamUrl, subs=subtitles)
      → player unificado: reproduce con seek/controles/velocidad/gestos/cast/dlna/subs/audio
```

## Manejo de errores / degradación

- `/resolve` falla, timeout, o sin stream → `_error` claro ("No se pudo resolver esta fuente"), el usuario prueba otra fuente. No rompe la app.
- Un host que el sniffer no puede resolver → error por-fuente; las demás fuentes web/torrent/archive siguen disponibles.
- Blog caído → web no reproduce (torrent/archive intactos); mensaje claro.

## Riesgos / limitaciones conocidas

- **Chromecast/DLNA con streams que exigen `Referer`/headers:** la TV/dispositivo fetchea la URL **directo** (sin los headers), así que un m3u8 con Referer obligatorio puede fallar en cast aunque ande en el celu. Muchos m3u8 públicos no lo requieren. Se marca como limitación; mitigación futura: proxear el stream con headers desde blog para cast (fuera de alcance ahora).
- **Blog 2 CPU + Playwright/Chromium:** pesado; serializar (un resolve a la vez) y timeout duro. Reusa el footprint de Chromium que ya existe.
- **m3u8 con segmentos que expiran / tokens:** el streamUrl resuelto puede caducar; se resuelve al momento de reproducir (no se cachea largo).

## Alcance (esta fase): películas + episodios

- Películas: `pageUrl` = detalle de la peli.
- Episodios: `pageUrl` = página del capítulo (el search de la hoja ya es por capítulo). El resolver trata la pageUrl igual; validar en device que el capítulo resuelve.

## Fuera de alcance

- Portar resolvers de Alfa por host.
- Proxy de stream con headers para cast (mitigación futura de la limitación de cast).
- Selección de "server/calidad" específico en la UI (por ahora el resolver devuelve un stream reproducible; multi-audio/subs salen del propio stream).

## Archivos afectados (estimado)

- **Blog (nuevo):** servicio `web-resolver` (Node+Playwright) + unit systemd de usuario + Cloudflare tunnel. Runbook.
- **App nuevos:** `data/catalog/web/WebResolverApi.kt` (cliente HTTP de `/resolve`); entidad/DAO Room para web source; en `SettingsStore` el `webResolverUrl`.
- **App modificados:** `playback/PlayerSource.kt` (SourceKind.WEB + kindFor); `ui/player/PlayerViewModel.kt` (loadWeb + rama en load); `PlayerData` (campo de subtítulos si falta); `data/ArkivRepository` (addWebSource + webSourceForEpisode); `ui/catalog/CineDetailScreen.kt` (playSource web real en vez de stub); `AppGraph.kt` (wiring de WebResolverApi).

## Testing

- **Unit (JVM):** `WebResolverApi` (parseo del JSON de `/resolve`, manejo de error/timeout con OkHttp fakeado); `PlayerSource.kindFor` con prefijo `web:`; `addWebSource`/`webSourceForEpisode` (Room in-memory).
- **Validación en dispositivo (S24+ por USB):** click en una fuente web de un canal que anda (sololatino/pelisplus) → resuelve → reproduce; probar seek, subtítulos, selector de audio/idioma, Chromecast, DLNA. Un episodio también.
- **Blog:** `curl /resolve?url=<pageUrl>` devuelve streamUrl para una peli conocida.
