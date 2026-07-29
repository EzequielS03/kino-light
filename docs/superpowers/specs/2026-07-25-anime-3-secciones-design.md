# Anime: 3 secciones (Torrent / Web / Archive) por episodio — Diseño

**Fecha:** 2026-07-25
**Branch:** `feat/torrents-ondevice-sin-servidor`
**Alcance:** Que la pantalla de anime muestre las fuentes de cada episodio agrupadas en 3 secciones
colapsables (TORRENT / WEB / ARCHIVE), buscando y reproduciendo cada tipo, **igual que la pantalla de
películas** (`CineDetailScreen`).

---

## 1. Contexto

Hoy `AnimeShowDetailScreen` (modo "Por episodio") solo busca y muestra **torrents** por episodio (ya
progresivo + con límite de tamaño). Las películas (`CineDetailScreen`) muestran 3 secciones
independientes (TORRENT/WEB/ARCHIVE), cada una progresiva. El usuario quiere lo mismo en anime.

Piezas que YA existen y se reutilizan:
- **Torrent**: `AnimeSourceProvider.episodeSourcesFlow(show, ep, langs, maxBytes)` (Flow, progresivo).
- **Web (búsqueda)**: `WebSourceEngine.searchFlow(ctx)` (Flow, progresivo, ahora cacheado).
- **Archive (búsqueda)**: `ArchiveApi.search(query)` (suspend, 1 llamada).
- **Reproducción web**: `repository.addWebSeriesEpisode(seriesId, title, poster, season, episode, epName, pageUrl)`.
- **Reproducción archive**: `repository.addItem(identifier)` → `firstEpisodeId`.
- **Títulos del anime**: `AnimeSourceProvider.browseTitles(show)` (AniList + español + alt).

## 2. Diseño

### 2.1 Orquestación (en `AnimeShowDetailScreen`, molde de `CineDetailScreen.runSearch`)

`loadEpisode(ep)` lanza **3 corrutinas independientes** (como películas), cada una llenando su sección
progresivamente; ninguna espera a las otras:
1. **Torrent**: `animeSourceProvider.episodeSourcesFlow(show, ep, langs, maxBytes).collect { … }` →
   `torrentByEp[ep]` (ya existe como `sourcesByEp`).
2. **Web**: obtener títulos (`browseTitles(show)`, suspend/cacheado) → construir
   `SearchContext(titles, type = ANIME, episode = ep)` →
   `webSourceEngine.searchFlow(ctx).collect { … }` → `webByEp[ep]` (progresivo).
3. **Archive**: `archiveApi.search("${primaryTitle} ${ep}")` → `archiveByEp[ep]` (una vez).

Estados nuevos (mapas por episodio): `webByEp: Map<Int, List<WebResult>>`,
`archiveByEp: Map<Int, List<ArchiveSearchResult>>`, y `loadingWebEp` / `loadingArchiveEp`
(análogos al `loadingEp` de torrent). Al cambiar idiomas (`LaunchedEffect(langs)`) se limpian los 3
y se cancelan sus jobs (extender el manejo actual a los nuevos mapas/jobs).

### 2.2 Reproducción

- **Torrent**: `play(TorrentResult, epNumber)` (ya existe).
- **Archive**: `playArchive(item)` → `repository.addItem(item.identifier)` → `firstEpisodeId` → `onPlay`.
- **Web**: `playWebEp(r: WebResult, ep: Int)` → `repository.addWebSeriesEpisode(
  seriesId = "anilist$anilistId", title = show.title, poster = show.posterUrl, season = 1,
  episode = ep, epName = "${show.title} - Ep $ep", pageUrl = r.pageUrl)` → `onPlay`.
  (Molde: `CineDetailScreen.playWeb`. La reproducción web resuelve la pageUrl→stream vía el
  web-resolver, que **sigue en el servidor** — dependencia conocida y aceptada para la capa web.)

### 2.3 UI — 3 sub-secciones colapsables por episodio

Dentro de cada episodio abierto, renderizar 3 sub-secciones con un helper compartido
`AnimeSourceSection(label, color, count, loading, expanded, onToggle, content)`:
- **TORRENT** (rojo) — `torrentByEp[ep]` → `ReleaseRow`.
- **WEB** (violeta) — `webByEp[ep]` → una fila por `WebResult` (título + sitio) que llama `playWebEp`.
- **ARCHIVE** (verde) — `archiveByEp[ep]` → una fila por `ArchiveSearchResult` que llama `playArchive`.
Cada sub-sección muestra su contador, su spinner mientras carga, y "Sin fuentes" si terminó vacía —
igual que `CineDetailScreen.SourceSection`. Estado de expandido por (episodio, tipo).

### 2.4 Fuera de alcance

- El modo **"Todos" (browse)** queda **torrent-only** como está (es una agregación distinta por
  episodio; no se le agregan web/archive ahora).
- No se cambia la reproducción existente ni el resolver web (server).

## 3. Testing

- La lógica de búsqueda web/archive de anime reusa componentes ya testeados (`WebSourceEngine`,
  `ArchiveApi`, `episodeSourcesFlow`). El cambio es principalmente de UI/orquestación (Compose), no
  unit-testeable de forma útil.
- **Regresión**: `./gradlew testDebugUnitTest` verde (no se toca lógica de datos con tests).
- **Verificación en device (gate real)**: en un anime, abrir un episodio → aparecen las 3 secciones;
  TORRENT trae fuentes (verificado antes: Naruto), WEB puede traer o no (sitios ES), ARCHIVE casi
  siempre vacío (archive.org tiene poco anime). Reproducir una fuente torrent y, si hay, una web.

## 4. Criterios de éxito

- Al abrir un episodio de anime, las fuentes se muestran en 3 secciones colapsables (Torrent/Web/
  Archive), cada una **progresiva e independiente**, igual que en películas.
- Reproducir funciona por tipo (torrent como hoy; archive vía `addItem`; web vía `addWebSeriesEpisode`).
- Al cambiar idiomas, las 3 secciones se re-consultan.
- Los tests existentes siguen verdes; verificado en device.
