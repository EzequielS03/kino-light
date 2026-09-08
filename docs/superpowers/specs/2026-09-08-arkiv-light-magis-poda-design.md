# Arkiv Light — Sub-proyecto 1: poda estructural

Rama: `light-magis` (fork permanente, no se mergea a `main`). Ver regla del branch en `CLAUDE.md`.

## Contexto

Arkiv hoy depende de cinco servidores propios (PocketBase, gateway `arkiv-api`, mirror de
torrents, resolver web/jackett, NUC offline) para poder ofrecer cuatro fuentes de contenido
(torrent, web, archive.org, Magis) más dos canales en vivo propietarios (Ditu/Caracol, RCN), todo
detrás de un login obligatorio de PocketBase que gatea el 100% de la UI (`MainActivity.kt:110-150`).

Se quiere una versión "Arkiv Light" que dependa **solo de Magis**, sin ningún servidor propio.
Esto se hace en 3 sub-proyectos independientes (specs separados):

1. **Este spec** — poda estructural: sacar del árbol todo lo que no es Magis, dejando un punto de
   control que compila y en el que Magis sigue reproduciendo exactamente igual que hoy.
2. Cliente Magis + TMDB directos (sin servidor) — reemplaza el gateway para Magis, saca
   PocketBase/login del todo. Spec aparte, después de que este se implemente y valide.
3. Ditu + RCN directos (mismo patrón) — al final, prioridad baja.

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
- **Archive.org**: `ArchiveApi.kt`, `playback/ArchiveCacheProxy.kt` (si es exclusivo de
  archive/VLC — confirmar durante el plan si alguna parte es compartida con el proxy de Magis).
- **VLC**: `playback/VlcPlayer.kt`, dependencia `org.videolan.android:libvlc-all` en
  `build.gradle.kts`, cualquier proxy/utilidad exclusiva de la ruta VLC (`CachingDeRed.kt`,
  `BufferQueCrece.kt`, `AguanteDeBuffering.kt`, `AvisoDeSalto.kt` — auditar en el plan cuáles son
  genéricas y cuáles VLC-only). Reproductor único que queda: ExoPlayer/media3
  (`MagisExoPlayer`).
- **Ditu (Caracol) y RCN**: `ui/catalog/CaracolScreen.kt`, `ui/catalog/RcnScreen.kt`,
  `ui/live/RcnLivePlayerScreen.kt`, endpoints `/v1/ditu/*` y `/v1/rcn/*` de `ArkivApiClient.kt`,
  `DituExoPlayer` y su wiring en `PlayerScreen.kt`. Vuelven en el sub-proyecto 3 con clientes
  directos propios — no se dejan a medio camino ahora.
- **Cloud-sync / control remoto TV↔celu**: `cloudsync/CloudSyncManager.kt`, `cloudsync/PbSyncClient.kt`,
  el `commands` realtime (control remoto), `BajadorDeFrames.kt` (sync de miniaturas cross-device;
  la captura/visualización LOCAL de miniaturas —`FrameCapturer`, `AlmacenDeFrames`,
  `EleccionDeMiniatura`, `GuardasDeFrame`, `DestructorDeFrames`— se queda intacta, no depende de
  esto). Pareo QR TV↔celu (`PairingManager.kt`, `pair_requests`) sale también — sin sync no tiene
  para qué existir.
- **Búsqueda unificada multi-fuente y búsqueda por frase (LLM)**: el fan-out `/v1/search` que
  combina torrent+web+archive+magis+ditu (`ArkivApiClient.kt:223-251`, `SearchViewModel.kt:449-505`)
  y `buscarPorFrase` (`ArkivApiClient.kt:399-441`). Sin las otras fuentes no hay fan-out que hacer;
  la búsqueda de Magis específica queda pendiente del sub-proyecto 2 (hoy no existe un camino
  "solo Magis" para buscar — el `searchByName` del portal solo lo llama el servidor).
- **NUC offline**: cualquier código de descarga local al NUC (`arkiv-offline`), fuera de alcance de
  "solo Magis".
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

- Auditar fila-por-fila cuáles proxies de `playback/` son exclusivos de VLC/archive vs
  compartidos con el pipeline ExoPlayer de Magis (`ArchiveCacheProxy`, `CachingDeRed`,
  `BufferQueCrece`, `AguanteDeBuffering`, `AvisoDeSalto`) antes de borrarlos — algunos comentarios
  sugieren lógica genérica de streaming reutilizada, no exclusiva de VLC.
  `google play services cast-framework`/Chromecast: revisar si el cast depende de alguna de las
  fuentes eliminadas o es agnóstico (parece agnóstico, pero confirmar antes de tocarlo).
- Confirmar que ningún ViewModel/pantalla de Home/Biblioteca referencia tipos de datos específicos
  de torrent/web/archive (ej. campos de `GatewayModels.kt` compartidos) de forma que romper la
  fuente rompa compilación de código que sí se mantiene.
- Candado de sección 18+ (`ADULT_CODE`) — confirmar si aplica a contenido de Magis o era solo para
  otras fuentes; si aplica, se mantiene.

## Fuera de alcance (queda para sub-proyectos 2 y 3)

- Sacar PocketBase/login/gateway del todo.
- Cliente directo a Magis (crypto, catálogo, búsqueda, resolución) y TMDB directo con key propia.
- Ditu y RCN con clientes directos.
