# arkiv-offline: sub-proyecto #3 (lado de la app)

Fecha: 2026-08-04 · Estado: diseño (sub-proyecto 3 de 3, ver [[arkiv-offline-backend-design]] y
[[arkiv-offline-web-resolver-design]])

## Objetivo

Sub-proyectos #1 (backend core: torrent+archive) y #2 (descargas web con yt-dlp) ya están en
producción en `blog`, verificados en vivo. Falta el lado de la app: un botón "Descargar offline"
que dispare descargas en `arkiv-offline`, una pantalla que muestre el progreso, y que la app sepa
reproducir desde la NUC en vez de en vivo cuando corresponda — incluyendo desde fuera de la red de
casa, vía el túnel de Cloudflare.

## Alcance de esta iteración

- **Solo fuentes WEB.** `arkiv-offline` ya soporta torrent/archive/web, pero esta iteración cubre
  únicamente el botón de descarga para packs y episodios web. Torrent/archive quedan para una
  iteración posterior, una vez que se vea cómo funciona esto en uso real.
- **"Descargar offline" es una acción nueva y separada** del botón "Guardar" que ya existe en
  `WebPackDialog` (que solo agrega la serie a la biblioteca local del cliente, sin descargar nada
  — ver `AnimeShowDetailScreen.kt:addWebPack`). No se tocan ni se fusionan.

## Arquitectura

**Actualizaciones en tiempo real vía SSE + poll de respaldo**, replicando el patrón ya construido
y probado en `CloudTransport.kt` para el control remoto de TV (`merge(sse, poll)`, dedup por id).
Se elige este enfoque en vez de FCM/push (evita meter Firebase y gestión de tokens, que
`arkiv-offline` no tiene hoy) y en vez de polling puro (el usuario explícitamente pidió el
enfoque más completo). Cubre además un gotcha ya conocido en este proyecto: Cloudflare resetea
conexiones SSE — el poll de respaldo es la mitigación ya validada.

**Notificaciones locales sin servidor de push:** mientras haya un job activo, un `WorkManager`
periódico consulta su estado y dispara una notificación de Android local cuando pasa a
`done`/`failed`, cubriendo el caso de la app en segundo plano. No requiere FCM ni registro de
dispositivos — se apoya en el mecanismo estándar de Android para trabajo en background.

**Caché local de "qué está descargado":** tabla Room (`series_id`, `season`, `episode`, `item_id`,
`status`), alimentada por dos caminos — (1) el canal SSE+poll de la pantalla de Descargas la
mantiene al día mientras esa pantalla está abierta, y (2) al abrir la pantalla de detalle de
CUALQUIER serie, se dispara una consulta directa a `GET /library?series_id=X` que refresca la
caché para esa serie puntual. El segundo camino es el que garantiza que la consulta "¿está esto
descargado?" antes de reproducir tenga datos frescos incluso si el usuario nunca visitó la
pantalla de Descargas para esa serie (por ejemplo, si la descarga se disparó desde otro
dispositivo, o después de reinstalar la app). Consultar la caché antes de reproducir es siempre
una lectura local instantánea — la actualización de red ya sucedió al entrar al detalle.

## Cambios en el backend (`arkiv-offline`)

1. **Nuevo endpoint SSE:** `GET /jobs/<id>/events`. Sin auth extra (mismo criterio que
   `GET /jobs/<id>` hoy, que tampoco pide key — es progreso de lectura, no una acción mutante).
   Reusa `_compute_progress` ya existente; emite un evento cada vez que cambia el estado o el
   progreso del job; cierra el stream cuando el job llega a un estado terminal (`done`/`failed`).
   No cambia el modelo de jobs/worker ya construido — es una capa de notificación encima.
2. **`GET /library` y `GET /stream/<item_id>` pasan a requerir `X-Api-Key`.** Cambio de contrato
   deliberado: hoy no lo piden (para que el reproductor los use fácil en LAN), pero al exponerlos
   por el túnel público de Cloudflare, cualquiera que adivine la URL podría ver la biblioteca y
   streamear contenido sin login. Este cambio cierra ese hueco. Rompe los tests existentes de
   estos dos endpoints — hay que actualizarlos junto con el cambio.
3. **Exposición vía Cloudflare tunnel:** nueva entrada de ingress apuntando a `arkiv-offline`
   (puerto 8099), en el mismo túnel/mecanismo ya usado por `jackett.comparadorinternet.co` y
   `torrents.comparadorinternet.co`.

## Cambios en la app

### Pantalla de Descargas (nueva)

Accesible desde el menú/ajustes. Lista de jobs — activos primero (progreso en vivo vía SSE+poll),
terminados debajo (de `GET /library` agrupado por serie). Cada fila con botón de cancelar (jobs
activos, `DELETE /jobs/<id>`) o borrar (terminados, `DELETE /library/<item_id>`).

### Botones de descarga

- **Pack completo:** botón nuevo en la pantalla de detalle (junto a donde hoy se abre
  `WebPackDialog`) — arma UN solo `POST /jobs` con `kind=web` y todos los episodios del pack como
  `items`, coincidiendo con el contrato que `arkiv-offline` ya espera.
- **Episodio individual:** botón junto a cada fila de episodio — dispara un job de 1 item.

### Preferencia de reproducción (por serie)

Tabla Room nueva: `series_id` → preferencia (NUC / en vivo) + flag "ya preguntado". Al abrir un
episodio para reproducir:
1. Si la serie tiene una copia descargada disponible (vía la caché local mencionada arriba) Y
   todavía no se preguntó por esa serie → preguntar una vez, guardar la respuesta.
2. Si la preferencia guardada es NUC y el episodio específico está descargado → reproducir vía
   `/stream/<item_id>`.
3. Si la preferencia es NUC pero ESE episodio puntual no está descargado (aunque otros de la
   misma serie sí) → cae a reproducir en vivo automáticamente, sin volver a preguntar.
4. Botón manual, siempre visible en el reproductor, para forzar el otro camino en ese capítulo
   puntual sin cambiar la preferencia guardada de la serie.

### Resolución de URL: LAN vs túnel

Dos valores configurables en `SettingsStore` (mismo patrón que `webResolverUrl` y las demás URLs
ya configurables ahí): IP/puerto LAN de `arkiv-offline` y el hostname público del túnel. Cada
request intenta LAN primero con timeout corto; si no responde, usa el túnel. Sin detección de red
compleja — solo "probar rápido, caer si no responde".

## Manejo de errores

- SSE cortado por Cloudflare → el poll de respaldo lo cubre, sin código nuevo más allá del merge
  ya diseñado.
- `POST /jobs` falla (arkiv-offline inalcanzable por ningún camino) → mensaje de error claro al
  usuario, no fallar en silencio.
- `WorkManager` no puede consultar el estado → se apoya en el retry/backoff nativo de WorkManager.
- Reproducir desde la NUC falla a mitad de stream → queda cubierto por la cadena de reintentos que
  `VlcPlayer` ya tiene hoy (`triedProxy`/`triedSoftware`); **no** se agrega un fallback nuevo
  "si falla NUC, cae a en vivo automáticamente" en esta iteración — es scope extra que puede
  sumarse después si en el uso real resulta necesario.
- `MAX_STORAGE_GB` superado al encolar un pack → el backend ya devuelve 409 con mensaje; la app
  solo necesita mostrarlo, sin lógica nueva del lado del servidor.

## Testing

- **Backend:** tests para el endpoint SSE nuevo (transiciones de estado del job → eventos
  correctos, el stream cierra en estado terminal) y para el requisito de auth nuevo en
  `/library`/`/stream` (401 sin key, 200 con key correcta) — mismo patrón que el resto del repo.
- **App:** tests de la tabla Room de preferencia (preguntar una sola vez, por serie), del merge
  SSE+poll con dedup, y del fallback LAN→túnel.
- **Verificación en vivo:** disparar una descarga real de un pack desde la app, ver progreso en
  vivo en la pantalla de Descargas, confirmar que la reproducción cambia a la NUC una vez
  terminado, probar el botón de override manual, y probar desde afuera de la red de casa vía el
  túnel.

## Fuera de alcance de este documento

- Torrent/archive como fuentes descargables desde la app (solo web en esta iteración).
- Fallback automático "NUC falló → reproducir en vivo" (queda con el manejo de errores existente
  del reproductor por ahora).
- Cualquier cambio al mecanismo interno de descarga de `arkiv-offline` (worker, yt-dlp, etc.) —
  ya está construido y verificado en sub-proyectos #1 y #2.
