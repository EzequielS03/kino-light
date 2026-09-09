# Arkiv Light (rama `light-magis`)

Esta rama es un fork permanente de Arkiv — **no se vuelve a mergear a `main`**. `main` sigue
siendo la app completa (torrent+web+archive+Magis+Ditu+RCN, con login PocketBase y el gateway
`arkiv-api`) y no se toca desde acá.

## Regla del branch (no negociable)

**Todo corre dentro de la app. Cero servidor propio.**

- Nada de PocketBase, nada de `arkiv-api` (gateway), nada de mirror de torrents, nada de jackett,
  nada de NUC offline. Si una feature necesita alguno de esos, o se reescribe para hablar directo
  desde el cliente Android, o se resigna — nunca se reintroduce un servidor propio "solo para esto".
- Las únicas llamadas de red permitidas hacia fuera del dispositivo son:
  1. Directo al **portal de Magis** (protocolo ya crackeado, ver `/Users/cristian/mago/reverse/`).
  2. Directo a **TMDB** (`api.themoviedb.org`) con una API key propia embebida en el build de esta rama.
  3. Al **CDN de Magis** para bajar los bytes de video (como ya es hoy).
  4. Al gateway `arkiv-api`, temporalmente, para: Magis (hasta sub-proyecto 2), TMDB-proxy (hasta
     sub-proyecto 2), y **"dato curioso"/trivia** (`ArkivApiClient.trivia()`, `/v1/trivia` — se deja
     tal cual está, llamando al gateway, indefinidamente; no tiene fecha de reemplazo por ahora).
- Se borra código muerto de verdad (login/cuentas, torrent, web-resolver, archive.org, VLC,
  cloud-sync, control remoto TV↔celu). No se comenta, no se deja detrás de un flag — si no se usa,
  se elimina del árbol.
- Reproductor: ExoPlayer/media3 (`MagisExoPlayer`, `LiveExoPlayer`) para Magis VOD y canal en vivo
  (Task 1, commits `537dadbb`..`4c3b846a`, sin verificar en dispositivo real todavía). **VLC no está
  sin uso** — corrección tras la revisión final del sub-proyecto 1 (2026-09-08): `VlcPlayer.kt` es
  el reproductor activo de `SourceKind.LOCAL` (archivos ya descargados al dispositivo,
  `PlayerViewModel.loadLocal()` → `PlayerScreen.isExo = isMagis || isLiveExo`, todo lo demás cae a
  VLC). Verificar el canal en vivo en dispositivo NO autoriza borrar VLC — haría falta además
  migrar la reproducción local a ExoPlayer, que no está planeado en este sub-proyecto.

## Spec

Ver `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md` (sub-proyecto 1 de 3:
poda estructural). Sub-proyecto 2 (cliente Magis+TMDB directos) y 3 (Ditu/RCN directos) vienen
después, cada uno con su propio spec.
