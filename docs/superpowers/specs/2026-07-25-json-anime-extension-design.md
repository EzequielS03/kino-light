# Backend JSON: extensión a anime (por episodio) — Diseño

**Fecha:** 2026-07-25
**Branch:** `feat/torrents-ondevice-sin-servidor`
**Alcance:** Extender el `JsonProviderBackend` (v1: solo películas) para cubrir **anime** de hacktorrent
(magnets directos por episodio vía su API JSON), delegando el matching de episodio al pipeline existente.

---

## 1. Contexto

`JsonProviderBackend` (spec `2026-07-25-json-provider-backend-design.md`) ya scrapea la API JSON de
hacktorrent para **películas** (`type=="pelicula"` → `/wp-json/wpreact/v1/movie/{slug}/` →
`downloads:[{download_link magnet, quality, size, language}]`).

hacktorrent también expone **anime** (verificado en vivo, jul 2026):
- Búsqueda: los resultados incluyen items `type=="anime"`.
- Detalle: `/wp-json/wpreact/v1/anime/{slug}/` → `downloads:[{season, episode, quality, size,
  download_link(magnet), language}]`. Numeración **por temporada** (ej. Slime: S3E1..S3E24, S4E1..S4E13;
  37 downloads = episodios). `download_link` es magnet directo. `language` p. ej. "Latino/Japones".

## 2. Diseño

### 2.1 `JsonApiConfig` — campos de anime (opcionales)

Añadir:
```
animeType: String? = null        // "anime" (valor de itemType que es anime)
animeDetailPath: String? = null  // "/wp-json/wpreact/v1/anime/{slug}/"
dlSeason: String? = null         // "season" (campo del download)
dlEpisode: String? = null        // "episode"
```
Si faltan, el backend simplemente no atiende anime (degradación limpia). Los campos de película (v1)
no cambian.

### 2.2 `JsonProviderBackend` — ramificar por `ctx.type`

`search(ctx)` decide la "modalidad" según `ctx.type`:
- **`ContentType.ANIME`** → si `cfg.animeType != null && cfg.animeDetailPath != null`:
  filtra items `itemType == animeType`, usa `animeDetailPath` para el detalle, y por cada download
  produce un `RawTorrent` **nombrado con SxxEyy**:
  `name = "{title} S{season:02}E{episode:02} {quality} {language}"` (season/episode leídos de
  `dlSeason`/`dlEpisode`; si alguno falta, se omite el token SxxEyy y cae al nombre v1).
- **otro (`MOVIE`/`TV`)** → comportamiento v1 (filtra `movieType`, `detailPath`, nombre
  `"{title} {quality} {language}"`).

Todo lo demás igual que v1: magnet directo (`startsWith("magnet:")`), `infoHash` del btih, `sizeBytes`
via `HtmlParser.parseSize`, **`seeders = 1`**, fallback de idioma al del item, cap de `maxMovies`
detalles, `runCatching` por item.

### 2.3 El matching de episodio lo hace el PIPELINE (no el backend)

Clave del diseño: el backend produce **todos** los episodios del anime, nombrados `SxxEyy`, y NO
filtra por episodio. El pipeline de anime ya existente lo hace:
- `searchAnime(spec)` usa `spec.matches(name)` (`SourceQuerySpec.matches`): si el nombre trae `SxxEyy`
  y la temporada es conocida, exige `s==season && e==episode`; si no, matchea por número de episodio.
  Esto es EXACTAMENTE cómo el pipeline filtra los releases de nyaa (que también traen SxxEyy en el
  nombre). Reusarlo evita reimplementar (mal) el matching de anime.
- `searchAnimeBrowse` (navegar todo el show) usa relevancia por título → **todos** los episodios pasan.
Por eso el backend no necesita `ctx.season`/`ctx.episode` (que además `toSearchContext()` no setea para
anime): solo expone el season/episode DE HACKTORRENT en el nombre, y el pipeline decide.

### 2.4 Limitación honesta (numeración de anime)

hacktorrent numera por temporada; la app usa temporada/episodio de TVDB. Cuando coinciden → match
perfecto. Cuando la temporada difiere → ese episodio se descarta (el `SxxEyy` no cuadra). Es la MISMA
limitación de toda fuente de anime (incluido nyaa), delegada al pipeline; no se resuelve aquí.

## 3. Def de hacktorrent (añadir a su `jsonApi`)

Agregar al bloque `jsonApi` existente de hacktorrent en `providers.json`:
```json
"animeType": "anime",
"animeDetailPath": "/wp-json/wpreact/v1/anime/{slug}/",
"dlSeason": "season",
"dlEpisode": "episode"
```
(`itemType` sigue siendo "type"; los items anime ya traen `type:"anime"`.)

## 4. Testing

- **JsonApiConfig:** parsea los nuevos campos anime (y siguen opcionales/null).
- **JsonProviderBackend (fixtures JSON reales):**
  - Búsqueda anime (`ctx.type=ANIME`) → item `type:"anime"` → detalle anime con `downloads` que traen
    `season`/`episode` → RawTorrents con `name` conteniendo `S03E01` (etc.), magnet, `seeders==1`.
  - Un download con `language:"Latino/Japones"` → `classify(name)` → `LATINO`.
  - Búsqueda de PELÍCULA (`ctx.type=MOVIE`) sigue igual (regresión): items `type:"anime"` se ignoran,
    `type:"pelicula"` se procesan.
  - Anime sin `animeType` configurado → no atiende anime (vacío), sin romper.
- **Integración con el matcher:** un test que pase los RawTorrents anime por `SourceQuerySpec.matches`
  (o por `TorrentSearchApi.searchAnime` con backends fake) y confirme que el episodio pedido matchea y
  otro episodio no.
- Suite completa verde; `RealProvidersJsonTest` sigue verde con la def ampliada.

## 5. Verificación en device

Hot-update (sincronizar providers.json). Buscar un anime con temporada mapeada (ej. Slime T3 Ep1). En
logcat `provider=hacktorrent json filas=N`; confirmar que aparece el episodio latino y reproduce.

## 6. Fuera de alcance

- Reconciliación de numeración absoluta⇄temporada más allá de lo que hace el pipeline.
- Otras APIs JSON (solo hacktorrent hoy). Series (no-anime) de hacktorrent (endpoint `serie/{slug}` con
  estructura de temporadas distinta) — futuro si se quiere.

## 7. Criterios de éxito

- Buscar un episodio de anime en hacktorrent devuelve el/los magnet(s) de ese episodio (latino/jap),
  filtrado por el pipeline, verificado en device.
- Las búsquedas de película (v1) se comportan idénticas.
- Config anime ausente → el backend ignora anime sin romper.
- Genérico: otra API JSON con anime por episodio se cubre con solo los campos de config.
