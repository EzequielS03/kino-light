# Arkiv Light — Sub-proyecto 1: poda estructural

Rama: `light-magis` (fork permanente, no se mergea a `main`). Ver regla del branch en `CLAUDE.md`.

## Contexto

Arkiv hoy depende de cinco servidores propios (PocketBase, gateway `arkiv-api`, mirror de
torrents, resolver web/jackett, NUC offline) para poder ofrecer cuatro fuentes de contenido
(torrent, web, archive.org, Magis) más un canal en vivo propietario aparte (Ditu/Caracol Play),
todo detrás de un login obligatorio de PocketBase que gatea el 100% de la UI
(`MainActivity.kt:110-150`). **Magis en sí ya trae su propio "canal en vivo"** (~1000 canales de TV
IP, ver `docs/superpowers/specs/2026-08-11-canal-en-vivo-design.md`) — no es lo mismo que Ditu, y
se reproduce con **VLC**, no con ExoPlayer. Esto corrige una confusión del borrador anterior de
este spec (ver nota de corrección más abajo).

Se quiere una versión "Arkiv Light" que dependa **solo de Magis**, sin ningún servidor propio.
Esto se hace en 3 sub-proyectos independientes (specs separados):

1. **Este spec** — poda estructural: sacar del árbol todo lo que no es Magis, dejando un punto de
   control que compila y en el que Magis (VOD + canal en vivo) sigue reproduciendo exactamente
   igual que hoy.
2. Cliente Magis + TMDB directos (sin servidor) — reemplaza el gateway para Magis, saca
   PocketBase/login del todo. Spec aparte, después de que este se implemente y valide.
3. Ditu directo (mismo patrón) — al final, prioridad baja. RCN no está implementado en esta rama
   (ver nota de corrección) — si se retoma, entra en este mismo sub-proyecto 3 cuando exista.

**Nota de corrección (tras inventario exhaustivo de archivos, 2026-09-08):** la versión original de
este spec tenía tres supuestos equivocados, corregidos abajo:
- Asumía que VLC solo servía a torrent/web/archive y se podía borrar entero — en realidad **es el
  reproductor del canal en vivo de Magis** y se mantiene.
- Asumía que `ArchiveCacheProxy.kt` era exclusivo de archive.org — en realidad **el VOD de Magis
  lo usa directamente** (`precalentar`, `precalentarSalto`, `passthrough` en modo directo); se
  parte en vez de borrarse.
- Mencionaba "RCN" como fuente ya implementada — no existe en esta rama (los archivos que se veían
  en el `git status` del repo original son trabajo sin commitear que quedó solo en la copia de
  `main`, no llegó a este worktree porque partió limpio de `origin/main`). Se saca del alcance.

## Objetivo de este sub-proyecto

Reducir la app a: Magis (vía gateway, sin cambios de protocolo) + TMDB (vía gateway, sin cambios)
+ biblioteca/progreso/miniaturas 100% locales (Room, sin sync) + login/PocketBase mínimo (el que
Magis todavía necesita para autenticarse contra el gateway). Todo lo demás desaparece del árbol.

**Por qué el login NO se saca todavía:** hoy el vínculo de Magis (`MagisLinkClient`) se autentica
con el mismo `personToken`/`deviceToken` que cualquier otro endpoint del gateway — no hay un camino
"solo Magis" sin sesión de persona. Sacar el login en este sub-proyecto dejaría a Magis sin poder
autenticarse, violando el objetivo ("Magis sigue funcionando igual que hoy"). El login sale recién
en el sub-proyecto 2, en el mismo movimiento en que se lo reemplaza por el cliente directo.

## Se elimina (código real, no flags)

- **Torrent**: `TorrentEngine` y todo `torrent/`, `MirrorApiClient.kt`, proveedores de torrents
  on-device (jsoup), dependencias `libtorrent4j`/`libtorrent4j-android-*` en `build.gradle.kts`,
  pantallas/ViewModels de torrent.
- **Web**: `WebResolverApi.kt`, motor de fuentes web on-device (`data/catalog/web/`), pantallas
  asociadas.
- **Archive.org**: `ArchiveApi.kt`, `ArchiveUrls.kt`, `NodoDeArchive.kt`, `CoincidenciaDeArchivo.kt`,
  `playback/DiskLruCache.kt`. **`ArchiveCacheProxy.kt` NO se borra** — se parte: se le saca el modo
  disk-cache exclusivo de archive (`ensureDownload/runDownload/serveGrowing/readFromDisk/
  streamGrowingFromDisk/serveFromFile`, la clase `Download`, `abrirEnNodo`/`leerTexto`) y se
  mantiene todo lo que usa el VOD de Magis (`precalentar*`, `servirDeVentana`/`precalentarSalto`,
  `passthrough` en modo directo, el dispatcher de `serve()`).
- **VLC — condicionado a una migración, no se asume que se mantiene.** Hoy es el reproductor del
  canal en vivo de Magis (`SourceKind.LIVE` en `PlayerViewModel.abrirCanalActual()`, vía
  `LiveHlsProxy` + la playlist VLC), no solo de torrent/web/archive. Pero el proxy ya sirve **HLS**
  (mismo tipo de stream que ExoPlayer maneja nativamente y mejor que Ditu, que además tiene DRM) —
  no hay evidencia en el código de una razón técnica dura para haber elegido VLC ahí; parece que el
  canal en vivo (Tarea 14) reusó el pipeline VLC que ya existía antes de la migración de VOD a
  ExoPlayer, sin revisarlo después. **Primera tarea del plan de implementación**: migrar el canal
  en vivo a ExoPlayer y verificarlo en dispositivo real (reproducción + zapeo entre canales). Si
  funciona bien, VLC se borra del todo (`VlcPlayer.kt`, dependencia `libvlc-all`, proxies
  exclusivos de la ruta VLC). Si aparece un bloqueo técnico real durante esa verificación, VLC
  queda pero acotado solo al canal en vivo, y el resto del plan sigue igual.
- **Ditu (Caracol Play)**: `ui/catalog/CaracolScreen.kt`, `ui/live/DituLivePlayerScreen.kt`,
  `ui/player/DituExoPlayer.kt`, `data/DituEntities.kt`, endpoints `dituCatalog/dituResolveLive/
  dituChannels` de `ArkivApiClient.kt`, tipos `Ditu*` de `GatewayModels.kt`,
  `data/local/DituDownloadStrategy.kt`. Vuelve en el sub-proyecto 3 con cliente directo propio.
  (RCN no está implementado en esta rama — no hay nada que podar de RCN todavía.)
- **Cloud-sync / control remoto TV↔celu**: `cloudsync/CloudSyncManager.kt`, `cloudsync/PbSyncClient.kt`,
  el `commands` realtime (control remoto), `BajadorDeFrames.kt` (sync de miniaturas cross-device;
  la captura/visualización LOCAL de miniaturas —`FrameCapturer`, `AlmacenDeFrames`,
  `EleccionDeMiniatura`, `GuardasDeFrame`, `DestructorDeFrames`— se queda intacta, no depende de
  esto). Pareo QR TV↔celu (`pairing/`, `remote/` —control remoto—, `sync/` —descubrimiento LAN de
  la TV—, `presence/`, `tvservice/`) sale también — sin sync no tiene para qué existir. La pantalla
  de entrada de TV (`TvPantallaDeEntrada.kt`) tiene hoy 3 pestañas (Descarga/Pareo/Login) — al sacar
  el pareo, se rediseña a 2 (Descarga/Login) o menos.
- **NUC offline** (descargas locales al NUC físico): `data/offline/ArkivOfflineApi.kt`,
  `NucDownloadCheckWorker.kt`, `NucDownloads.kt`, `NucJobEvents.kt`, `PlaybackPreferenceStore.kt`,
  `ui/offline/*`, `ui/downloads/*` — fuera de alcance de "solo Magis".
- **Búsqueda unificada multi-fuente y búsqueda por frase (LLM)**: el fan-out `/v1/search` que
  combina torrent+web+archive+magis+ditu (`ArkivApiClient.kt:223-251`, `SearchViewModel.kt:449-505`)
  y `buscarPorFrase` (`ArkivApiClient.kt:399-441`). Sin las otras fuentes no hay fan-out que hacer;
  la búsqueda de Magis específica queda pendiente del sub-proyecto 2 (hoy no existe un camino
  "solo Magis" para buscar — el `searchByName` del portal solo lo llama el servidor).
- **Licencias/registro**: el flujo de `licencias` para registro de nuevas cuentas (era para admitir
  usuarios nuevos vía CLI de Cristian) — en la app light no hace falta abrir registro a terceros.

## Se mantiene sin cambios (por ahora)

- `MagisExoPlayer`, `FormatoDeMagis.kt`, el proxy local que inyecta headers de Magis
  (`PlayerSource.kt`) — pipeline de reproducción de Magis intacto.
- `MagisLinkClient.kt`, `AccountManager` (vínculo Magis), `ArkivApiClient` para `/v1/search`
  (acotado a `sources=magis`), `/v1/resolve`, `/v1/episodes`, `/v1/magis/*` — Magis sigue
  resolviéndose vía gateway, sin tocar el protocolo.
- `TmdbApi.kt` vía gateway (`/v1/catalog/tmdb`) — sin cambios; pasa a llamada directa en el
  sub-proyecto 2.
- Login/PocketBase mínimo: `SesionDePersona`, `DeviceAuthManager`, `MainActivity` gate — se
  mantienen para que Magis siga autenticando contra el gateway. Se cae el flujo de **registro**
  (licencias) pero el de **login** (email/password) queda.
- Room completo: DAOs de biblioteca/progreso/episodios/miniaturas — ya están desacoplados de
  PocketBase (confirmado: ninguno depende de sesión de persona para leer/escribir localmente).
- Captura/almacenamiento/visualización local de miniaturas de frame (`miniaturas/`, sin la parte
  de sync).

## Criterio de éxito / punto de control

- La app compila en la rama `light-magis` sin las dependencias de libtorrent4j/libVLC/jsoup.
- Instalada en dispositivo: login funciona, vínculo de Magis funciona (vía gateway, igual que
  hoy), catálogo/reproducción de Magis funciona igual que en `main`, biblioteca/progreso/miniaturas
  locales funcionan sin sync.
- No quedan pantallas, botones ni rutas de navegación que apunten a torrent/web/archive/Ditu/RCN.
- `MainActivity` y la navegación no referencian código eliminado (compila limpio, sin código muerto
  comentado).

## Riesgos / decisiones para el plan de implementación

- **Resuelto por inventario exhaustivo (2026-09-08):** `ArchiveCacheProxy.kt` se parte (no se
  borra) — el VOD de Magis usa `precalentar*`, `servirDeVentana`/`precalentarSalto` y `passthrough`
  en modo directo; solo el modo disk-cache (`ensureDownload`/`runDownload`/`serveGrowing`/
  `readFromDisk`/`streamGrowingFromDisk`/`serveFromFile`, clase `Download`, `abrirEnNodo`/
  `leerTexto`) es exclusivo de archive.org y se borra. `CachingDeRed`/`BufferQueCrece` los sigue
  usando Magis (VOD y, mientras no migre, en vivo) — se mantienen enteros.
- **Abierto, primera tarea del plan:** si el canal en vivo de Magis migra a ExoPlayer con éxito,
  VLC se borra del todo; si no, queda acotado solo a eso (ver sección VLC arriba).
  `google play services cast-framework`/Chromecast: revisar si el cast depende de alguna de las
  fuentes eliminadas o es agnóstico (parece agnóstico, pero confirmar antes de tocarlo).
- Confirmar que ningún ViewModel/pantalla de Home/Biblioteca referencia tipos de datos específicos
  de torrent/web/archive (ej. campos de `GatewayModels.kt` compartidos) de forma que romper la
  fuente rompa compilación de código que sí se mantiene. Zona gris a auditar archivo por archivo:
  `TorrentSearchApi.kt` (sirve también búsquedas web), `PackContents.kt`/`PackDetector.kt`
  (mezclan lógica de packs torrent/web), `AnimeSourceProvider.kt`.
- Candado de sección 18+ (`ADULT_CODE`) — confirmar si aplica a contenido de Magis o era solo para
  otras fuentes; si aplica, se mantiene.

## Fuera de alcance (queda para sub-proyectos 2 y 3)

- Sacar PocketBase/login/gateway del todo.
- Cliente directo a Magis (crypto, catálogo, búsqueda, resolución) y TMDB directo con key propia.
- Ditu y RCN con clientes directos.
