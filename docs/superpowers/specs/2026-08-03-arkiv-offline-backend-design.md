# arkiv-offline: backend de descargas server-side en la NUC

Fecha: 2026-08-03 · Estado: diseño (sub-proyecto 1 de 3)

## Objetivo

Hoy, guardar un pack (torrent o web) agrega episodios a la biblioteca del celu que se resuelven **en
vivo** cada vez que se reproducen (torrent streamea del swarm, web resuelve el embed on-device). El
usuario quiere poder decir "bajate este pack **en la NUC**" y que, una vez bajado, cualquier
dispositivo que abra esos episodios los streamee **desde la NUC** (rápido, sin depender de seeds ni
de que el sitio web esté arriba en ese momento), quedando siempre disponible un botón para forzar la
fuente en vivo si se prefiere.

Este documento cubre **solo el backend** (sub-proyecto 1 de 3, ver contexto abajo). Sin él no hay
nada que la app pueda llamar.

## Sub-proyectos relacionados (fuera de este documento)

- **#2 — Resolución WEB server-side:** portar a Python la lógica de "página del sitio → link directo
  del video" que hoy vive en `WebSourceEngine` (Kotlin, on-device). Sin esto, un job de tipo `web`
  se acepta y se encola pero falla honestamente ("aún no soportado"). No bloquea a #1 ni a #3.
- **#3 — App:** botón "Descargar offline" en `PackDialog`/`WebPackDialog`, pantalla de cola/progreso,
  preferencia "usar NUC cuando esté disponible" (se pregunta 1 vez, se recuerda) + botón siempre
  visible "Reproducir desde origen" para forzar la fuente en vivo.

## Arquitectura

Servicio nuevo y separado, `arkiv-offline`, en `blog` (Celeron N3050, 2 cores, ~3.9GB RAM disponible,
326GB libres en disco hoy) — no extiende `hacktorrent-mirror` (mezclar indexado de catálogo con
descarga+almacenamiento de video son responsabilidades demasiado distintas).

- **API:** Flask (mismo stack que el resto de los backends del proyecto — hacktorrent-mirror, alfa-api
  — consistencia de deploy/ops).
- **Estado:** SQLite (servicio aislado, sin necesidad de compartir Postgres con el mirror; un archivo,
  cero setup extra).
- **Motor de descarga:** `aria2c` (RPC JSON-RPC local) para TODO — soporta magnet (BitTorrent) y HTTP
  directo (archive.org) con la misma herramienta. Los jobs `web` (fase 2) también terminan en una URL
  HTTP directa una vez resuelto el embed, así que igual usan aria2 al final.
- **Cola estrictamente secuencial** (1 descarga a la vez, sin excepciones). Con 2 cores y RAM ajustada,
  paralelizar arriesga degradar el resto de los servicios que ya corren en la misma máquina (mirror,
  alfa-api, Jackett, Coolify). Un pack tarda lo que tarda; no compite consigo mismo.
- **Deploy:** mismo patrón que alfa-api/hacktorrent-mirror — `docker-compose` + túnel Cloudflare
  compartido (`comparador-tunnel`), dominio propio (`offline.comparadorinternet.co`).
- **Auth:** a diferencia de hacktorrent-mirror/alfa-api (solo lectura, bajo riesgo si alguien los
  encuentra), este servicio ACEPTA escritura (dispara descargas = consume disco/CPU/ancho de banda de
  la NUC). Todo endpoint que no sea `GET /stream/*` exige un header `X-Api-Key` con un secreto fijo
  (env var, mismo `.env` del servicio) — evita que alguien que descubra el subdominio use la NUC como
  proxy de descargas gratis o la sature.

## Modelo de datos (SQLite)

```sql
CREATE TABLE jobs (
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL,              -- 'torrent' | 'web' | 'archive'
  series_id TEXT NOT NULL,         -- mismo criterio de id que ya usa el app (seriesId/anilist.../tmdb...)
  show_title TEXT NOT NULL,
  poster_url TEXT,
  magnet TEXT,                     -- solo kind='torrent'
  status TEXT NOT NULL,            -- 'queued' | 'downloading' | 'done' | 'failed' | 'canceled'
  error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE job_items (
  id INTEGER PRIMARY KEY,
  job_id INTEGER NOT NULL REFERENCES jobs(id),
  season INTEGER,
  episode INTEGER NOT NULL,
  episode_name TEXT,
  source_ref TEXT,                 -- pageUrl (web) | archive identifier (archive) | file index (torrent)
  status TEXT NOT NULL,            -- 'pending' | 'downloading' | 'done' | 'failed'
  file_path TEXT,                  -- ruta relativa en disco una vez 'done'
  size_bytes INTEGER,
  error TEXT,
  UNIQUE(job_id, episode, season)
);
```

**Identidad estable del item** (clave para que `GET /library` pueda contestar "¿esto ya está bajado?"
sin ambigüedad): `series_id + season + episode`, el MISMO trío que ya usa `ArkivRepository` en el app
para agrupar episodios de una serie/anime. `series_id` reusa exactamente las convenciones que ya
existen (`imdbId`/`tmdb<id>` para TV, `anilist<id>` para anime) — el backend no inventa un esquema
nuevo, hereda el que ya está probado en 4 lugares distintos del app (`playWeb`/`playWebEp`/
`addWebPack` en ambas pantallas).

## Granularidad de descarga por tipo (importante — no es igual para las 3 fuentes)

- **TORRENT:** el pack es **una sola descarga atómica** (un magnet = un swarm). `aria2c` selecciona
  solo los archivos elegidos en el diálogo de pack (`--select-file` por índice, mismo índice que ya
  usa `PackFileRow`/`PackResolver` on-device). El job entero pasa a `downloading` → `done` (mapea
  cada `job_item` a su archivo dentro del torrent) o `failed` (sin seeds / timeout) como una unidad —
  no hay "23 de 50 fallaron", falla o completa el torrent completo. Reintentar = re-encolar el job
  entero.
- **WEB (fase 2) y ARCHIVE:** cada episodio es una URL/item independiente → cada `job_item` tiene su
  propio ciclo de vida. Un episodio puede fallar (link vencido, sitio caído en ese momento) sin tumbar
  los otros 49. El job pasa a `done` cuando TODOS sus items terminan (en `done` o `failed`); la app
  puede ofrecer "reintentar fallidos" apuntando solo a esos `job_items`.

## Contrato API

Todos exigen `X-Api-Key` salvo donde se aclara.

- `POST /jobs` — body: `{kind, series_id, show_title, poster_url, magnet?, items: [{season, episode, episode_name, source_ref}]}`. Devuelve `{job_id}`. Si `kind='web'` y la fase 2 no está lista: `202` con `status='queued'` pero cada item marcado `failed` con `error='web aún no soportado'` (falla honesto, no silencioso).
- `GET /jobs/<id>` — progreso: estado del job + de cada item + `%` (para torrent, del RPC de aria2; para web/archive, items done/total).
- `DELETE /jobs/<id>` — cancela si está en cola/descargando; si ya terminó, borra los archivos.
- `GET /library?series_id=X` — `[{season, episode, item_id, size_bytes}]` de lo ya descargado para esa serie. Sin `X-Api-Key` (la app lo consulta constantemente para decidir de dónde reproducir; exigir key acá es fricción sin beneficio real — es solo lectura de qué existe, no dispara nada).
- `GET /stream/<item_id>` — sirve el archivo con soporte de `Range` (necesario para seek en VLC/media3). Sin `X-Api-Key` — mismo criterio que `/library`, es solo lectura de contenido que la app ya sabe que existe.
- `DELETE /library/<item_id>` — borra un episodio puntual ya descargado (limpieza manual).

## Límite de espacio en disco

`MAX_OFFLINE_STORAGE_GB` (env var, default **10GB** — límite bajo a propósito, pedido por el usuario
para no arriesgar llenar el disco de `blog`; deja ~316GB de margen para Postgres/Coolify/el resto de
lo que ya corre ahí). La misma cuota debe respetarse del lado de la app (sub-proyecto #3): mostrar el
límite y el uso actual antes de dejar encolar una descarga, no solo confiar en el 409 del backend.
Antes de aceptar un `POST /jobs`, el servicio suma el tamaño
ya ocupado en su carpeta de descargas; si el job nuevo excede el límite (estimado por tamaño reportado
del torrent/archive item), responde `409` con el detalle — la app se lo muestra al usuario ("no hay
espacio, borrá algo primero"). **Sin eviction automática (LRU) en v1** — YAGNI: el usuario borra a
mano vía `DELETE /library/<item_id>` cuando quiera liberar espacio, como maneja hoy una carpeta de
descargas cualquiera. Automatizar la limpieza es un follow-up si en la práctica se vuelve tedioso.

## Manejo de errores

- Sin conexión a internet / tracker caído (torrent): el job queda `downloading` con 0% indefinido;
  timeout configurable (`TORRENT_STALL_TIMEOUT_S`, default 30 min sin progreso) → `failed`.
- Disco lleno a mitad de descarga (por más que el precheck de arriba lo intente evitar, un tamaño
  estimado mal o dos jobs compitiendo por el mismo margen puede fallar igual): aria2 corta, el job
  pasa a `failed` con el error de aria2 tal cual, se borra el archivo parcial.
- Reinicio del contenedor/la máquina a mitad de un job: al arrancar, el servicio reconcilia contra
  aria2 (¿sigue vivo ese GID?) — si no, marca `failed` los jobs que quedaron en `downloading` huérfanos
  (nunca los deja como "descargando" para siempre, mismo espíritu que el bug de `reconcile_web` ya
  arreglado en el mirror).

## Testing

- Unit (Python, sin red): construcción del comando aria2 (magnet + `--select-file`), cálculo de
  espacio disponible/límite, transición de estados de `job`/`job_item` dado un evento de aria2.
- Integración manual en `blog`: 1 pack de torrent real (ej. el "Blood Plus 1-50" que ya está indexado
  con 32 seeders) de punta a punta — `POST /jobs` → progreso → `GET /stream/<item_id>` reproducible
  con `curl -r` (range request) → `DELETE /library/<item_id>` limpia el archivo.

## Fuera de alcance de este documento

- Resolución de embeds WEB (sub-proyecto #2).
- Todo lo de UI/UX del app — botón, pantalla de cola, preferencia recordada, botón "reproducir desde
  origen" (sub-proyecto #3). Este documento solo garantiza que el CONTRATO (`/jobs`, `/library`,
  `/stream`) esté listo para que #3 lo consuma.
- Multi-usuario con permisos distintos (hoy es un secreto compartido único, suficiente para uso
  familiar/personal — no hay cuentas ni roles).
- Transcodeo (el archivo se sirve tal cual se bajó; si el formato no es compatible con el player, es
  el mismo problema que ya existe hoy con torrent/web en vivo, no algo nuevo que este backend
  introduzca).
