# Alinear numeración de episodio para anime (WEB) — design

Fecha: 2026-08-04
Contexto: residual I4 documentado en `.superpowers/sdd/2026-08-04-arkiv-offline-app-ui/final-fix-report.md`
("Numeración de episodio, no de temporada"), dejado explícitamente fuera de alcance del arreglo de
`season` ya hecho.

## Problema

La fila local de un episodio web se guarda con clave = hash de `pageUrl`
(`ArkivRepository.addWebSeriesEpisode`), así que todos los caminos que tocan la misma `pageUrl`
escriben la MISMA fila (last-write-wins), y el player lee `season`/`episode` de esa fila
(`subtitleContextForEpisode` → `PlaybackPreferenceStore.decide(seriesId, season, episode)` →
`nucLibraryItemDao.find(...)`).

`season` ya quedó alineado (I4, resuelto por `WebSourceSeason.forPageUrl`). `episode` no: para
anime, los caminos de "episodio suelto" guardan el número que el usuario tocó en la grilla de
AniList, mientras los caminos de pack guardan el número real de `MirrorWebSource.episode` (que
para series de larga duración puede ser **absoluto**, no relativo a la temporada de AniList).

| camino | episodio guardado |
|---|---|
| `AnimeShowDetailScreen.playWebEp` | el de AniList (el tocado en la grilla) |
| `AnimeShowDetailScreen.downloadEpisode` | ídem |
| `SearchPlayback.playWeb` (rama anime) | ídem (`refineEpisode`) |
| `AnimeShowDetailScreen.addWebPack` / `downloadPack` | real del mirror (`MirrorWebSource.episode`) |
| `SearchPlayback.addWholeWebSeries` / `SearchScreen.downloadWholeSeries` | real del mirror |

Para la misma `pageUrl`, los dos grupos pueden escribir un `episode` distinto → `decide(...)` no
encuentra el capítulo bajado a la NUC, o encuentra otro con el mismo número por coincidencia.

## Dirección elegida: mismo patrón que `WebSourceSeason`, resolver por `pageUrl`

`WebResult` no trae episodio "real" (viene ya resuelto a nivel de episodio por
`episodeSourcesWeb`), pero los packs del mirror ya cargados en pantalla (`webPacks` /
`sources.filterIsInstance<PlaySource.WebPack>()`) son la misma consulta de la que salió el
`WebResult`: la `pageUrl` identifica su `MirrorWebSource` y con él su episodio real.

Nuevo helper puro, hermano de `WebSourceSeason` (mismo paquete
`com.arkiv.player.data.catalog.mirror`):

```kotlin
object WebSourceEpisode {
    fun forPageUrl(packs: List<MirrorWebPack>, pageUrl: String, fallback: Int): Int =
        packs.firstNotNullOfOrNull { pack -> pack.episodes.firstOrNull { it.pageUrl == pageUrl } }
            ?.episode ?: fallback
}
```

A diferencia de `WebSourceSeason.forPageUrl` (fallback fijo en `1`, la convención histórica de
season), acá no hay una convención universal para "episodio desconocido": cada llamador pasa como
`fallback` el episodio que el usuario tocó (el mismo valor que se usaba antes de este cambio).
Cuando ningún pack conoce la `pageUrl` (scraping en vivo, sin mirror cargado), el comportamiento
es idéntico al actual.

## Call sites

Los 3 caminos de "episodio suelto" (los de pack no se tocan, ya usan el episodio real):

- **`AnimeShowDetailScreen.playWebEp(r, ep)`**: resuelve
  `val episode = WebSourceEpisode.forPageUrl(webPacks, r.pageUrl, fallback = ep)` y lo usa tanto en
  el `episode` de `addWebSeriesEpisode(...)` como en el texto `"Ep $episode"` (no `"Ep $ep"`, para
  no mostrar dos números distintos en el mismo `displayName`).
- **`AnimeShowDetailScreen.downloadEpisode(r, ep)`**: misma resolución, usada en
  `NucDownloadItem(season, episode, r.pageUrl)`.
- **`SearchPlayback.playWeb(...)`**: nuevo parámetro `animeEpisode: Int`, análogo a `animeSeason`,
  usado solo en la rama `card.kind == "anime"` (`addWebSeriesEpisode(..., animeSeason, animeEpisode,
  "$resultTitle - Ep $animeEpisode", ...)`); la rama TMDB (`season != null`) no cambia.
- Sus 2 llamadores, que ya calculan `animeSeason` con el mismo patrón:
  - `SearchScreen.playWebResult`
  - `TvSearchScreen.playWebResult`

  ambos agregan `val animeEpisode = WebSourceEpisode.forPageUrl(packs, r.pageUrl, fallback =
  episode ?: 1)` y lo pasan a `playback.playWeb(...)`.

## Sin tocar

- `addWebPack`, `downloadPack`, `addWholeWebSeries`, `downloadWholeSeries` — ya usan
  `MirrorWebSource.episode` real.
- Esquema de `EpisodeEntity` / `nuc_library_items` — sin cambios de columnas ni migraciones.
- `ArkivRepository.subtitleContextForEpisode` — sigue parseando el episodio de `displayName` con
  la regex `E(\d+)`; ahora ese texto refleja el episodio resuelto (mirror si lo conoce), igual que
  ya pasa con `section` = `"Temporada $season"` desde el fix de I4.
- Rama TMDB de `SearchPlayback.playWeb` (`CineDetailScreen.playWeb`/`downloadEpisode` no forman
  parte de este cambio: ya usan `sheetEpisode.season`/`episode` reales).

## Testing

`WebSourceEpisodeTest.kt`, hermano de `WebSourceSeasonTest.kt` (mismos fixtures de
`MirrorWebPack`/`MirrorWebSource`):

1. Resuelve el episodio real del mirror para una `pageUrl` conocida.
2. No confunde episodios del mismo número en distintos packs/sitios.
3. Busca en todos los packs, no solo en el primero.
4. Cae al `fallback` dado cuando ningún pack conoce la `pageUrl` (scraping en vivo).
5. Cae al `fallback` dado sin packs cargados.
6. Caso motivador: un pack con numeración **absoluta** (`episode = 64`) para la `pageUrl` que el
   usuario tocó como "Ep 5" en la grilla de AniList — el resuelto debe ser `64`, no `5` ni el
   `fallback`.

Unit tests únicamente (JVM, sin Android): mismo nivel que `WebSourceSeasonTest`. Los call sites en
Compose screens no llevan test propio (no lo tenían antes tampoco); se verifican por lectura de
código + `compileDebugKotlin` para confirmar que compilan los nuevos parámetros/argumentos.

## Residuales que quedan igual (no se agravan, no se arreglan acá)

- **Carrera con la carga de packs**: `webPacks`/`sources` se cargan en su propio efecto (hasta ~6s).
  Si el usuario actúa antes de que lleguen, `WebSourceEpisode.forPageUrl` cae al `fallback` (el
  comportamiento anterior, no uno peor) — mismo residual ya documentado para `season` en I4.
- **Arreglo de fondo**: indexar `nuc_library_items` por `source_ref` (`pageUrl`) en vez de
  `(season, episode)` sigue siendo la solución más correcta, pero requiere cambiar el contrato de
  `GET /library` en el repo `arkiv-offline` (otro repo) — fuera de alcance de este cambio.
