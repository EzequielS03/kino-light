# Reglas no negociables

Lo de abajo salió del `CLAUDE.md`, que se carga en cada sesión y tiene que ser corto. Acá está
completo. Si vas a agregar una llamada de red, una dependencia o una feature, leelo antes.

## Regla del proyecto (no negociable)

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
  4. Al **OTA** (`github.com`/`release-assets.githubusercontent.com`, `UpdateChecker` apunta a
     `github.com/lordmacu/kino-light/releases/latest/download/latest.json`), para avisar de una
     versión nueva del APK y bajar el APK firmado. Reemplazó a un servidor propio
     (`apk.comparadorinternet.co`) que era una violación no documentada de la regla de arriba —
     esta migración la cierra, no abre un hueco nuevo. Ver
     `docs/superpowers/specs/2026-09-14-github-release-ota-pipeline-design.md`.
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
         con sus segmentos. Medido el 2026-09-13: es **Mediastream** (`mdstrm.com`), que redirige a
         CloudFront. Ahí también van los segmentos que guarda la descarga de Caracol
         (`DituDownloadStrategy`), o sea que bajar no agrega ningún host nuevo a esta lista;
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


## Idioma

- **Lo que ve la persona usuaria** (textos de la UI, mensajes de error, toasts): español de Bogotá,
  **tuteo**, nunca voseo. "Podés" está mal; "puedes" está bien.
- **Lo que ve quien programa** (código, identificadores, comentarios, KDoc, logs, mensajes de
  commit, specs y planes): **inglés**.
- Ya no hay regla de transición. El código existente en español (clases, funciones, comentarios,
  KDoc) es una deuda a saldar, no algo para dejar en paz — se audita y se traduce, no se deja como
  está solo por ser viejo. (Decidido 2026-09-14.)

## Commits

- Van como **`lordmacu`**: `user.name = lordmacu`,
  `user.email = 10134930+lordmacu@users.noreply.github.com`. Nunca la cuenta de trabajo.
- **Nunca** un pie `Co-Authored-By: Claude` ni ninguna coautoría. Ya pasó que un subagente lo metió
  igual pese al prompt: revisá el pie de cada commit que haga un subagente y enmendá si es local.
- Nunca `git add -A`: stagear solo los archivos que tocaste. Puede haber otra sesión en el árbol.
- El mensaje explica **por qué**, con la medición si la hubo. Los 961 commits de este repo son así y
  es la mejor documentación que tiene el proyecto.
