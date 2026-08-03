# Packs web en las pantallas de detalle (Anime + Cine)

Fecha: 2026-08-03 · Estado: diseño

## Objetivo

Buscando Death Note el usuario notó que, a diferencia de torrent, la web nunca ofrece "guardar la
serie completa como pack". El pack web YA existe como concepto (`MirrorWebPack`, `WebPackDialog`,
`TorrentSearchApi.seriesWebPacks()`) y funciona en el buscador (`SearchScreen`/`TvSearchScreen`),
pero nunca se cableó en las pantallas de detalle (`AnimeShowDetailScreen`, `CineDetailScreen`) — que
es por donde de verdad se navega un show. `CineDetailScreen` incluso tiene el hueco marcado en el
código: `is PlaySource.WebPack -> Unit` con el comentario *"solo la búsqueda lo hará, en una tarea
posterior"*.

## Dos causas, no una

1. **Backend (dato):** Death Note no tenía `web_sources` — nunca había sido crawleado
   (`last_web_crawled IS NULL`), por orden de cola (`ORDER BY last_web_crawled NULLS FIRST, year DESC`
   prioriza los títulos más nuevos entre los nunca-crawleados; Death Note es de 2006). **Ya resuelto**:
   se disparó un crawl manual puntual en `blog` (mismo patrón que el re-enrich puntual documentado) —
   Death Note tiene ahora 74 `web_sources` (37 pelisplus + 37 serieskao, temporada 1). No fue un bug,
   solo timing; el resto del catálogo se sigue poblando solo por el timer diario.
2. **App (feature incompleto):** el pack web se construyó y se ató SOLO al buscador. Las pantallas de
   detalle nunca lo piden ni lo muestran. Este documento cubre solo esta parte.

## Alcance

| Pantalla | Ruta | Estado hoy | Acción |
|---|---|---|---|
| `AnimeShowDetailScreen` | `catalog_anime/{anilistId}` (donde entra Death Note) | Sin ninguna referencia a `WebPack` | Agregar en los 2 modos |
| `CineDetailScreen` | `cine/{type}/{tmdbId}` (series/pelis TMDB) | `WebPack -> Unit` (no-op, deferred a propósito) | Cerrar la tarea diferida |
| `SearchScreen`/`TvSearchScreen` | buscador de texto libre | Ya implementado y correcto (`SearchViewModel.kt:330-334`) | Ninguna — se valida, no se toca |
| `ShowDetailScreen` | `catalog_show/{imdbId}` | Sin sección WEB de ningún tipo | Fuera de alcance — ruta muerta, nada navega ahí hoy |

Todo lo reusado (`MirrorWebPack`, `MirrorWebFilter`, `WebPackDialog`, `TorrentSearchApi.seriesWebPacks`)
ya existe, está probado (277 tests) y en producción vía el buscador — no se toca ni se generaliza, solo
se **llama** desde 2 archivos más. Cero cambios de backend/mirror API.

## Diseño por pantalla

### `AnimeShowDetailScreen.kt`

Hoy tiene dos modos ya armados: **"Por episodio"** (`sourcesByEp`/`webByEp`, per-episodio) y **"Todos"**
(`browse`, solo torrent, pensado para "quiero ver todo el show sin elegir capítulo"). Ninguno toca web
packs.

- **Fetch único por show**, no por episodio ni por modo: al cargar `show` (mismo `LaunchedEffect(anilistId)`
  que trae los metadatos), se dispara en paralelo
  `graph.torrentSearchApi.seriesWebPacks(titles, ContentType.ANIME, tmdbId=null, showTitle=s.title)`
  y el resultado (`List<MirrorWebPack>`) se guarda en un `webPacks` state. Se cachea ahí — ni "Por
  episodio" ni "Todos" lo vuelven a pedir.
- **"Todos":** debajo (o junto a) la lista de torrents, una sección WEB nueva que pinta un renglón por
  `MirrorWebPack` (mismo look que `SourceRow` con `PlaySource.WebPack`: chip PACK ámbar + "N capítulos"
  + sitio). Tap → abre `WebPackDialog` (estado `webPackFor`), igual que en `SearchScreen`.
- **"Por episodio":** para cada episodio expandido, la sub-sección WEB de ese episodio (`AnimeSourceSection`)
  agrega, ADEMÁS de los `WebResult` individuales que ya trae `episodeSourcesWeb`, un renglón por cada
  `MirrorWebPack` cacheado cuyo `.episodes` contenga ese número de episodio (filtro tolerante —
  episodio-en-set, sin exigir temporada, mismo criterio que `MirrorWebFilter` ya usa para anime). Tap
  → mismo `webPackFor` compartido con el modo "Todos".
- **Guardar:** `onSave`/`onPlayOne` de `WebPackDialog` llaman una función local `addWebPack(pack, title,
  episodes, playEpisode)` que recorre `episodes` y llama `graph.repository.addWebSeriesEpisode("anilist$anilistId",
  title, s.posterUrl, season=1, ep.episode, ep.name, ep.pageUrl)` por cada una — el mismo molde que
  `playWebEp` ya usa para un solo episodio (season fija en 1, numeración absoluta), solo en loop.
  Devuelve el id del `playEpisode` pedido o el primero agregado, igual que el buscador.

### `CineDetailScreen.kt`

Solo tiene el sheet de fuentes por episodio/película (sin modo "Todos" separado); ahí es donde también
aparecen hoy los packs de TORRENT (inline, vía `PackDetector.isPack` sobre el nombre del release).

- **Fetch único por título**: al resolver `detail` (`LaunchedEffect(tmdbId, type)`), si `d.isSeries`,
  se dispara en paralelo `graph.torrentSearchApi.seriesWebPacks(d.searchTitles, ContentType.TV, tmdbId=d.id,
  showTitle=d.title)` → state `webPacks`. Películas no tienen concepto de pack, se omite.
- **En `runSearch(ep)`**, dentro del `launch` de WEB, además de `searchEpisodeWeb` se filtran los
  `webPacks` cacheados a los que cubran `(ep.season, ep.episode)` — temporada EXACTA (mismo criterio
  `seasonStrict=true` que ya usa `MirrorWebFilter` para TV) — y se agregan como `PlaySource.WebPack`
  a `webs`, junto a los `PlaySource.Web` individuales.
- **`playSource`**: reemplaza el no-op — `is PlaySource.WebPack -> webPackFor = s.pack`.
- **Guardar**: función local `addWebPack` con el mismo patrón que `AnimeShowDetailScreen`, pero usando
  `seriesIdFor` real (`d.imdbId.ifBlank { "tmdb${d.id}" }`, ya existe en el archivo) y la temporada REAL
  del episodio (no fija en 1) — molde exacto de `playWeb` ya presente, en loop.

## Por qué NO reusar `SearchPlayback` directamente

`SearchPlayback.addWholeWebSeries` ya hace esto mismo, pero recibe un `TitleCard` — tipo propio del
flujo de búsqueda que unifica tmdb/anilist/kind. Ninguna de las dos pantallas de detalle lo tiene ni
lo necesita: cada una ya resuelve su propio `seriesId`/convención de guardado inline (`play`, `playWeb`,
`playWebEp`). Meterles `TitleCard` sería acoplar dos pantallas independientes a un tipo pensado para
una tercera, solo para ahorrarse un loop de 5 líneas. Se sigue el patrón ya establecido en el código
("Molde: X.playWeb") — copiar-adaptar la función chica, no una abstracción nueva.

## Estados de carga y error

- `webPacks` se carga en paralelo con el resto (no bloquea torrent/web individual/archive), mismo
  patrón "progresivo e independiente" que ya usan las 3 secciones existentes. Si tarda o el título no
  está crawleado, la sección/fila de pack simplemente no aparece — no hay spinner dedicado nuevo (el
  spinner de la sub-sección WEB ya cubre esta espera implícitamente).
- Si `seriesWebPacks()` devuelve vacío (título aún no crawleado, como Death Note ayer), no se muestra
  nada distinto a hoy — cero regresión respecto al estado actual.
- Igual que en el buscador: los packs SUMAN a los resultados individuales, nunca los reemplazan
  (aprendizaje documentado del bug de spoiler de anime — sitios con numeración distinta entre sí).

## Testing

- Unit: filtro de packs por episodio en cada pantalla (temporada exacta para Cine, tolerante para
  Anime) — reusa la lógica de `MirrorWebFilter`, se puede extraer a una función pura testeable si el
  filtro inline crece más de una línea.
- Manual en device: Death Note (anime, ya tiene datos reales) en ambos modos de `AnimeShowDetailScreen`;
  una serie TMDB con pack web conocido (o el mismo Death Note si TMDB lo expone como "tv") en
  `CineDetailScreen`.

## Fuera de alcance

- `ShowDetailScreen.kt` (ruta muerta, `catalog_show/{imdbId}`, nada navega ahí).
- Cualquier cambio en `mirror`/backend — los datos y el contrato `/api/title/<slug>` ya están completos.
- Remapeo de numeración entre sitios para web (limitación conocida y aceptada, ver memoria
  `arkiv-hacktorrent-mirror`).
