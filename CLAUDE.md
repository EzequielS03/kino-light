# Arkiv Light (rama `light-magis`)

Esta rama es un fork permanente de Arkiv — **no se vuelve a mergear a `main`**. `main` sigue
siendo la app completa (torrent+web+archive+Magis+Ditu+RCN, con login PocketBase y el gateway
`arkiv-api`) y no se toca desde acá.

## You are in the right tree — this is where the work happens

If you got here from `/Users/cristian/archive` (branch `main`), good: this is the active tree. Run
`git branch --show-current` and confirm it prints `light-magis` before editing anything. Anything
Cristian asks about Magis, Caracol/Ditu, cast, the player, the catalog or the TV UI belongs here,
not on `main`.

**Never diagnose a bug in this branch by reading `main`'s sources.** They are different codebases:
`main` still has torrent, web, archive.org, NUC, libVLC, PocketBase and the `arkiv-api` gateway,
all of which were deleted here. Its `SourceKind` has eight values, this one has five; its LAN-IP
helper is `graph.torrentEngine.lanIp()`, this one is `graph.lanIp()` and has no `TorrentEngine`.
Code read over there is confidently wrong over here.

### Traps measured in this tree

- **graft's index is stale for this branch** — it lists deleted files as live. Verify with
  `command grep`, never with the graph alone.
- **The `rtk` hook truncates `cat` and `grep` with no warning.** When exact content matters, use
  the Read tool or `command cat` / `command grep`.
- **The git stash stack is shared** with `main` and every other worktree and session. Never bare
  `git stash` / `git stash pop` — prefer a WIP commit.
- **Other Claude sessions share these trees.** Never `git add -A`; stage only the files you
  changed, and re-check the branch before committing — it can change under you.
- **Commits go as `lordmacu`**, never the work account, and never with a `Co-Authored-By: Claude`
  footer — check the footer of every commit a subagent makes.
- **adb:** use only the SDK one (`~/Library/Android/sdk/platform-tools/adb`, v37). Mixing it with
  `/opt/homebrew/bin/adb` (v36) restarts the server and drops every connection.
- **Installing:** this app is debuggable — `assembleDebug`. The `.env` here has no `RELEASE_*`
  keys, so `assembleRelease` silently produces an unsigned APK.

### Language

Lo que ve la persona usuaria: español de Bogotá, tuteo, nunca voseo. Lo que ve un desarrollador
—código, identificadores, comentarios, KDoc, logs, mensajes de commit, specs y planes—: **inglés**.

## Regla del branch (no negociable)

**Todo corre dentro de la app. Cero servidor propio.**

- Nada de PocketBase, nada de `arkiv-api` (gateway), nada de mirror de torrents, nada de jackett,
  nada de NUC offline. Si una feature necesita alguno de esos, o se reescribe para hablar directo
  desde el cliente Android, o se resigna — nunca se reintroduce un servidor propio "solo para esto".
- Las únicas llamadas de red permitidas hacia fuera del dispositivo son:
  1. Directo al **portal de Magis** (protocolo ya crackeado, ver `/Users/cristian/mago/reverse/`).
     **Hecho** (sub-proyecto 2A): todo el protocolo vive en `app/src/main/java/com/arkiv/player/data/magis/`.
  2. Directo a **TMDB** (`api.themoviedb.org` para datos, `image.tmdb.org` para pósters/stills) con
     una API key propia embebida en el build de esta rama. **Hecho** (sub-proyecto 2A).
  3. Al **CDN de Magis** para bajar los bytes de video (como ya es hoy).
  4. Al **OTA** (`apk.comparadorinternet.co/latest.json`, `UpdateChecker`), para avisar de una
     versión nueva del APK.
  5. Directo a **AniList** (`graphql.anilist.co`), tercero público sin llave propia: alimenta las
     filas y la búsqueda de anime (`data/catalog/AniListApi.kt`, consumido por `HomeViewModel`,
     `CategoriasViewModel`, `RowBrowseViewModel`, `SearchViewModel`, `TvSearchScreen`,
     `AnimeShowDetailScreen` y `AnimeSection`).
  6. Directo a **raw.githubusercontent.com**, tercero público sin llave propia: descarga el dataset
     de mapeo de anime de Fribb (`data/catalog/AnimeMappingRepository.kt`).
  7. Directo a **Caracol Streaming** (Ditu), sin cuenta ni llave propia (sub-proyecto 3A, todo en
     `app/src/main/java/com/arkiv/player/data/ditu/`). Va en dos partes, porque el barrido de abajo
     solo ve la primera:
     - **Hosts escritos en el código** (el barrido los ve):
       - `middleware.ditu.caracoltv.com`: la API AVS y también la licencia Widevine
         (`DituCliente.BASE` y `DituCliente.LICENCIA`, que es `…/CONTENT/LICENSE`);
       - `image-registry.ditu.caracoltv.com`: pósters y fondos (`DituCatalogo.CDN_IMAGENES`).
     - **Hosts que devuelve la API en tiempo de ejecución** (el barrido NO los ve, porque no están
       en el código; es el mismo caso que el CDN de Magis del punto 3):
       - el CDN del video: el `src` del `.mpd` que devuelve `CONTENT/VIDEOURL` (`DituResolve`),
         con sus segmentos;
       - los logos de canal (`logoMedium`, en `DituCatalogo`), que según el adaptador de Python
         (`arkiv-api/src/arkiv_api/adapters/ditu/adapter.py`) vienen de
         `image-registry.avscaracoltv.com`;
       - las URLs `fileUrl` del `posterList` (`DituCatalogo`), que se usan cuando falta el
         `pictureUrl`.

     Todos son de Caracol TV y ninguno es servidor propio: ni la cookie `playback_token` ni la
     licencia pasan por nada nuestro.

     Ojo con el punto 1 (el portal de Magis): al barrido tampoco lo ve, y por un motivo TODAVÍA más
     ciego que el del CDN de Magis/Ditu de arriba. Esos son hosts que la API devuelve en tiempo de
     ejecución; el del portal ni siquiera existe como string en el código — se arma en runtime con
     `"$scheme://$host/api/portalCore/$path"` (`data/magis/MagisPortalClient.kt:94`) a partir de
     `BuildConfig.IPTV_HOSTS`, que sale del `.env` (`app/build.gradle.kts:30`). Un futuro barrido
     podría concluir, equivocado, que la llamada al portal desapareció.
  8. Directo a **Kilo** (`api.kilo.ai`), tercero público y **sin llave** (tier anónimo: nunca se
     manda `Authorization`): alimenta el dato curioso y la fila "Para ti"
     (`app/src/main/java/com/arkiv/player/data/ia/ClienteDeIa.kt`). No hay ningún secreto embebido.

  Ninguno de los ocho es servidor propio, así que no violan la regla de arriba. Para verificarlo no
  sirve un grep por nombres propios (`comparadorinternet`, `pocketbase`, `gatewayUrl`, `/v1/`): eso
  es ciego a un host de terceros nuevo. El barrido correcto ENUMERA todos los hosts que la app llama
  y los compara a mano contra esta lista:

  ```
  grep -roE "https?://[a-zA-Z0-9._-]+" app/src/main/java | sort -u
  ```

  (En una máquina donde un hook intercepte `grep`, córrelo como `command grep`: el hook puede
  recortar la salida sin avisar. Va a salir ruido que no es una llamada de red real: URLs de ejemplo
  en comentarios/KDoc, namespaces XML del cliente DLNA, `127.0.0.1` de los proxies locales. Cualquier host nuevo que SÍ
  sea una llamada real y no esté en la lista de ocho es justo lo que este barrido existe para
  atrapar.)

  El gateway `arkiv-api` y PocketBase se sacaron ENTEROS en el sub-proyecto 2B (Tasks 1-10): ya no
  queda una sola línea que les hable. Eso incluyó la **"dato curioso"/trivia**, que en el
  sub-proyecto 1 había quedado anotada como excepción **permanente** -esa excepción se resignó ahí,
  junto con marcadores de intro, metadata de anime, el aviso de recomendaciones, subtítulos
  (OpenSubtitles), Simkl y el login/cuenta de la persona (PocketBase). La trivia volvió en el
  sub-proyecto 4, hablándole directo a Kilo (punto 8 de la lista de arriba) en vez de al gateway:
  sin servidor propio.
- Se borra código muerto de verdad (login/cuentas, torrent, web-resolver, archive.org,
  cloud-sync, control remoto TV↔celu). No se comenta, no se deja detrás de un flag — si no se usa,
  se elimina del árbol.
- Reproductor: ExoPlayer/media3 en toda la app, sin VLC. `MagisExoPlayer`/`LiveExoPlayer` cubren
  Magis VOD y canal en vivo (Task 1, commits `537dadbb`..`4c3b846a`, sin verificar en dispositivo
  real todavía). Los archivos ya descargados al dispositivo (`SourceKind.LOCAL`,
  `PlayerViewModel.loadLocal()`) reproducen en el ExoPlayer que hospeda `PlaybackService`, migrados
  ahí en el sub-proyecto de remoción de libVLC. libVLC se borró entero: la dependencia Gradle,
  `VlcPlayer.kt` y los helpers que solo él usaba — ya no queda reproductor de respaldo.

## Spec

Ver `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md` (sub-proyecto 1 de 3:
poda estructural). Sub-proyecto 2 (cliente Magis+TMDB directos) y 3 (Ditu/RCN directos) vienen
después, cada uno con su propio spec.
