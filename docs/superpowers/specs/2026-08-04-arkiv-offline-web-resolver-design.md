# arkiv-offline: descargas de fuentes WEB (sub-proyecto #2)

Fecha: 2026-08-04 · Estado: diseño (sub-proyecto 2 de 3, ver [[arkiv-offline-backend-design]])

## Objetivo

El backend `arkiv-offline` (sub-proyecto #1, ya desplegado y validado en `blog` con un magnet real
de punta a punta) hoy soporta `torrent` y `archive`; los jobs de tipo `web` se aceptan pero se
marcan `failed` de inmediato ("web aun no soportado"). Este documento cierra ese hueco: bajar a
disco, en `blog`, un episodio servido por pelisplus/serieskao/sololatino (los mismos 3 sitios que ya
scrapea `hacktorrent-mirror` para poblar `web_sources`).

## Hallazgo clave: la pieza más incierta ya está resuelta

El spec original de `arkiv-offline` marcaba esto como "la resolución de embeds server-side... la
pieza más incierta". Investigando antes de diseñar, encontramos que **ya existe, desplegada y en
uso en producción**, exactamente la pieza que hacía falta: `web-resolver.service` — un servicio
Node.js + Playwright en `blog` (`~/web-resolver/server.js`, systemd de usuario), que la app YA usa
hoy para reproducir fuentes web (`WebResolverApi.kt` → `jackett.comparadorinternet.co/resolve`).

A diferencia de Alfa (selectores CSS por sitio, frágiles — confirmado roto para pelisplus en esta
sesión: la clase `bg-tabs` que esperaba ya no existe en el HTML real), `web-resolver` abre un
navegador real (Chromium headless) y **espía el tráfico de red** buscando URLs de video
(`.m3u8`/`.mp4`) — funciona genéricamente contra cualquier sitio con reproductor HTML5 estándar, no
depende de la estructura exacta del HTML. Probado en vivo en esta sesión contra pelisplus/Death
Note: devolvió un stream `.m3u8` real y reproducible.

**No hay que construir ni mantener un resolver nuevo.** `arkiv-offline` solo necesita aprender a
*descargar* lo que este servicio ya le devuelve.

## Contrato del resolver (ya existe, no se toca)

`GET http://127.0.0.1:8123/resolve?url=<page_url>` (mismo host que `arkiv-offline` — sin pasar por
el túnel de Cloudflare, ver más abajo). Serializa internamente (1 resolve a la vez, 10-60s cada uno,
cachea 8 min por `page_url`). Devuelve:

```json
{"ok": true, "streamUrl": "...", "proxyUrl": "https://jackett.comparadorinternet.co/proxy?url=...&referer=...", "headers": {...}, "subtitles": [...]}
```

o `{"ok": false, "error": "..."}`.

**`arkiv-offline` siempre descarga por `proxyUrl`, nunca por `streamUrl` directo.** El propio
`web-resolver` ya expone `/proxy`, que reescribe playlists HLS (segmentos/variantes relativos →
absolutos) e inyecta el `Referer` del lado del servidor — es el MISMO mecanismo que ya usa la app
para reproducir sin manejar headers a mano. Reusarlo evita reimplementar el manejo de headers en
`arkiv-offline`: no hace falta pasarle `Referer`/`User-Agent` a nada nuestro, el proxy ya resuelto
lo hace. Nota: `/proxy` construye sus URLs con `PUBLIC_BASE = jackett.comparadorinternet.co`
(hardcodeado en `web-resolver`), así que aunque la llamada a `/resolve` sea local, la descarga real
de los segmentos SÍ sale y entra por el túnel de Cloudflare (ida y vuelta dentro de la misma
máquina). Aceptable para v1 — no es tráfico hacia el celu, es `blog` hablando consigo mismo.

## Por qué el descargador no puede ser `aria2` para este caso

`aria2` baja archivos directos (HTTP/FTP/BitTorrent) — no sabe convertir un playlist HLS
(`.m3u8` + N segmentos `.ts`) en un archivo de video reproducible. Para eso hace falta un muxer.
**`ffmpeg`** (`-i <proxyUrl> -c copy <archivo>.mkv`) resuelve esto sin recodificar (solo remuxea) —
funciona igual para HLS que para un `.mp4` directo, así que no hace falta ramificar por tipo de
stream: un solo comando cubre ambos casos. Se agrega `ffmpeg` a la imagen Docker (no está hoy — solo
`aria2`).

## Modelo de descarga: distinto al de torrent/archive, y hay que decirlo explícito

Para `archive`, `dispatch_job` dispara TODAS las URIs de los items a la vez y deja que `aria2`
(con `--max-concurrent-downloads=1`) las serialice externamente. **No hay un `aria2` externo
sirviendo de cola para `ffmpeg`** — si lanzáramos un proceso `ffmpeg` por item a la vez, en un host
de 2 cores tendríamos varios muxers compitiendo por CPU, violando la misma regla de "1 descarga a la
vez" que ya nos costó una ronda de arreglos en el sub-proyecto #1.

Por eso, para `web`, la secuenciación la hace **el propio worker de `arkiv-offline`**, no una cola
externa: `dispatch_job` arranca el `ffmpeg` del PRIMER item pendiente (`subprocess.Popen`, no
bloqueante) y nada más. `poll_job` cada ciclo revisa si ese proceso sigue vivo; cuando termina
(¿código de salida 0? → ese item `done`; ¿no-cero? → `failed` con el stderr de `ffmpeg` como
`error`), recién ahí dispara el `ffmpeg` del SIGUIENTE item pendiente. Un job de 20 episodios hace
20 arranques de `ffmpeg`, uno detrás del otro, nunca dos a la vez — mismo espíritu que la cola
externa de `aria2`, implementado a mano porque acá no hay cola externa que lo haga por nosotros.

**Progreso:** en vez de un mecanismo nuevo, se reusa el `progress_tracker` que ya existe (del
timeout de estancado del sub-proyecto #1) — la señal de progreso para el item en curso es
simplemente el tamaño del archivo de salida en disco, sondeado en cada ciclo
(`os.path.getsize` del archivo que `ffmpeg` está escribiendo). Un `ffmpeg` genuinamente descargando
hace crecer ese archivo; uno colgado (embed muerto, red caída a mitad) no — el stall-timeout ya
construido lo detecta igual que detecta un torrent sin seeds, sin código nuevo para eso.

**Reinicio del servicio a mitad de una descarga `web`:** a diferencia de `aria2` (proceso externo,
persistente, con el que `reconcile_on_startup` puede re-conectar por gid), un `ffmpeg` lanzado como
subproceso de `arkiv-offline` **muere con el proceso padre**. No hay ambigüedad que reconciliar: si
al arrancar hay un job `web` en `downloading`, su `ffmpeg` ya no existe — se marca `failed`
directamente (o se re-encola desde cero, a definir en el plan) sin necesidad de comprobar nada
externo, más simple que el caso torrent/archive.

## Contrato de items para jobs `web`

Igual forma que `archive` (`source_ref` = el `page_url` del episodio, no un índice — ese ya es el
identificador natural que el mirror guarda en `web_sources.page_url`). Cada item se resuelve y
descarga independientemente: uno puede fallar (sitio caído en ese momento, resolver no encontró
stream) sin tumbar los demás — mismo `jobstate.derive_job_status_from_items` ya construido.

## Manejo de errores

- `/resolve` devuelve `{"ok": false, "error": "..."}` → item `failed` con ese mensaje tal cual
  (mismo patrón que ya usa `dispatch_job` para el stub `web` actual).
- `/resolve` no responde / timeout (puede tardar hasta 60s por el sniff de Playwright; usar un
  timeout HTTP generoso, ~90s, para no cortar un resolve que sí iba a terminar bien) → item
  `failed`, error claro ("resolver no respondió").
- `ffmpeg` sale con código no-cero → item `failed`, `error` = las últimas líneas de su stderr
  (mensajes de ffmpeg suelen ser diagnósticos útiles: "stream not found", "403", etc.).
- Archivo parcial de un `ffmpeg` fallido: se borra (mismo criterio de limpieza que el resto del
  backend — no dejar basura en disco de un intento fallido).

## Fuera de alcance de este documento

- Exponer `arkiv-offline` (o sus endpoints `/stream`/`/library`) por el túnel de Cloudflare —
  decisión explícita del usuario: se hace en sub-proyecto #3, cuando la app realmente lo necesite
  para reproducir desde fuera de la red de casa.
- Cualquier cambio a `web-resolver.service` — funciona, se usa tal cual.
- Subtítulos (`subtitles[]` ya viene en la respuesta del resolver pero no se descargan en v1 — igual
  que hoy la app no los usa para fuentes descargadas offline).
- Selección de calidad/idioma cuando el resolver encuentra varios candidatos — usa lo que
  `web-resolver` ya elige como "primero reproducible" (mismo criterio que la app confía hoy).

## Testing

- Unit (Python, sin red real): parseo de la respuesta de `/resolve` (mock del HTTP), construcción
  del comando `ffmpeg`, transición de estados del item en cada ciclo de `poll_job` para `web`
  (proceso vivo → sigue `downloading`; código 0 → `done`; código no-cero → `failed`), reconciliación
  al reiniciar (job `web` en `downloading` al arrancar → `failed` inmediato, sin gid que comprobar).
- Integración manual en `blog`: un episodio real de Death Note/pelisplus (ya tenemos el `page_url`
  real de esta sesión) de punta a punta — `POST /jobs` (`kind=web`) → `/resolve` real → `ffmpeg` real
  → archivo en disco reproducible → `GET /stream/<item_id>` con Range, mismo patrón de verificación
  que ya se usó para validar el flujo de torrent.
