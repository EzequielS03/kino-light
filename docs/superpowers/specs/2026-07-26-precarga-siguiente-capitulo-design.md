# Precarga del siguiente capítulo — Diseño

**Fecha:** 2026-07-26
**Rama:** feature nueva (desde la rama de trabajo actual)

## Objetivo

Mientras se ve un capítulo de una serie, ir preparando **el siguiente** en segundo plano para que, al pasar, arranque al instante (sin espera de buffer ni de resolve). Cubre las tres fuentes: **torrent PACK**, **web** y **archive**. Siempre en baja prioridad: nunca compite con la reproducción en curso.

## Contexto (cómo funciona hoy)

- **Reproducción:** `PlayerViewModel.load(episodeId)` ramifica por fuente (`loadArchive`/`loadTorrent`/`loadWeb`) y publica un `PlaylistData`. Tiene acceso a `repo`, `torrentEngine` y `webResolverApi`.
- **Serie:** `repo.episodesOf(itemId)` da la lista de episodios; filtrando por `section` y ordenando se obtiene el "siguiente".
- **Torrent PACK:** un pack se guarda como UN `.torrent` con N archivos; cada episodio tiene su `torrentFileIndex`. `repo.torrentSourceForEpisode(id)` → `EpisodeTorrent.Bytes(bytes, fileIndex)`. Todos los episodios del pack están en el **mismo `currentHandle`** del `TorrentEngine`.
- **TorrentEngine:** `beginServing(handle, info, dir, fileIndex)` prioriza (TOP) las piezas de cabeza+cola del archivo servido e IGNORA el resto, con `prioritizePieces` atómico. Un solo `currentHandle`/`server`/`readyUrl` activo.
- **Web:** el `webResolverApi.resolve(pageUrl)` es lo lento (15–60s, serializado en blog) y su resultado queda **cacheado en el resolver** (TTL 8 min). `repo.webSourceForEpisode(id)` da la pageUrl.
- **Archive:** `loadArchive` arma la sección completa como playlist (autoplay nativo); el media es un mp4 público servido por un proxy local (`127.0.0.1`).

## Diseño

### Coordinador común (en PlayerViewModel)

Al terminar de cargar el episodio actual, lanzar un **job de prefetch cancelable** (`prefetchJob`, se cancela/reemplaza en cada `load`):

1. Resolver el **siguiente episodio**: `nextEpisode(episodes, currentId)` — pura: toma `repo.episodesOf(itemId).filter { section }` ordenados, ubica el actual y devuelve el índice+1, o `null` si es el último.
2. Esperar un colchón corto (~8s) para no robarle el arranque al actual (salvo torrent, que va en baja prioridad y no compite).
3. Despachar el prefetch por tipo (best-effort: cualquier error se traga, nunca afecta la reproducción actual).

Solo **un** episodio adelante (N+1), no N+2.

### Prefetch por tipo

**1. Torrent PACK** (siguiente episodio en el MISMO torrent):
- `repo.torrentSourceForEpisode(nextId)` → si es `EpisodeTorrent.Bytes` del **mismo torrent** que el actual (mismo info-hash) → `torrentEngine.preBufferNextFile(nextFileIndex)`.
- Nuevo `TorrentEngine.preBufferNextFile(fileIndex)`: calcula las piezas de **cabeza** de ese archivo (misma lógica que `beginServing`: `fileOffset/pieceLen` + ventana de header) y las pone en prioridad **BAJA** vía `handle.piecePriority(p, Priority.LOW)` una a una (incremental, NO toca las TOP del actual ni resetea el array). libtorrent las baja con banda sobrante después de las TOP.
- Si el siguiente es de **otro** torrent (serie por-capítulo) → **no-op en v1** (fase 2: 2º handle de prefetch).

**2. Web** (allcalidad/pelisplus/etc.):
- `repo.webSourceForEpisode(nextId)` → si hay pageUrl → `webResolverApi.resolve(pageUrl)` en background e **ignorar el resultado** (solo calienta la caché server-side del resolver). Al pasar al siguiente, el resolve es un cache-hit instantáneo.
- Si no hay pageUrl → no-op.

**3. Archive**:
- Tomar la URL del siguiente episodio (misma derivación que `loadArchive`) y hacer un **GET con Range de los primeros ~2–4 MB** (best-effort, timeout corto) para calentar la conexión/CDN de archive.org. Empujoncito (archive ya va por playlist + host rápido).

### Ciclo de vida / seguridad

- `prefetchJob` se **cancela** al cargar otro episodio o cerrar el player.
- Todo best-effort: `runCatching`, timeouts cortos; jamás bloquea ni tira error a la reproducción actual.
- Baja prioridad (torrent) / background (web/archive) → nunca compite con lo que se ve.

## Alcance / YAGNI

- **Sí (v1):** coordinador + `nextEpisode` puro; torrent PACK (mismo torrent, cabeza del próximo archivo en prioridad baja); web pre-resolve; archive pre-fetch de cabeza.
- **No (fase 2):** series de torrent por-capítulo (2º torrent en background + promoción al pasar) — necesita un "handle de prefetch" en el TorrentEngine. Precargar N+2+. Sincronizar el prefetch entre dispositivos.

## Testing

- **`nextEpisode(episodes, currentId)`** (puro): devuelve N+1, `null` en el último, tolera el actual no encontrado.
- **`preBufferNextFile`**: unit-test del cálculo del rango de piezas de cabeza (puro, dado pieceLen + fileOffset + tamaño), separado del handle real de libtorrent.
- **Prefetch web**: con un `webResolverApi` fake, verificar que se llama `resolve(nextPageUrl)` cuando hay pageUrl y NO cuando no hay / no hay siguiente.
- **Cancelación**: cambiar de episodio cancela el job anterior (no dispara dos prefetch).
- **Device**: ver en `ArkivGate`/logs que al pasar al siguiente cap de un pack el gate pasa casi instantáneo; y que un web pre-resuelto abre sin la espera del resolver.

## Riesgos

- El prefetch de torrent no debe robar banda al actual: se mitiga con prioridad BAJA (las TOP del actual siempre ganan) y sin tocar el array de prioridades del actual (piece-priority incremental).
- Web: el resolver serializa; el pre-resolve encola detrás del actual — está bien (el actual ya terminó). No pre-resolver si el actual todavía está resolviendo (evitar cola larga).
