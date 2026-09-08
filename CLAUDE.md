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
- Se borra código muerto de verdad (login/cuentas, torrent, web-resolver, archive.org, VLC,
  cloud-sync, control remoto TV↔celu). No se comenta, no se deja detrás de un flag — si no se usa,
  se elimina del árbol.
- Reproductor: ExoPlayer/media3 (`MagisExoPlayer`, `LiveExoPlayer`) para todo. El canal en vivo de
  Magis ya se migró a ExoPlayer (Task 1, commits `537dadbb`..`4c3b846a`), pero sin verificación en
  dispositivo real — VLC (`VlcPlayer.kt`, `libvlc-all`) queda sin uso real pero SIN BORRAR hasta que
  un humano confirme en dispositivo que el canal en vivo anda bien y autorice el borrado.

## Spec

Ver `docs/superpowers/specs/2026-09-08-arkiv-light-magis-poda-design.md` (sub-proyecto 1 de 3:
poda estructural). Sub-proyecto 2 (cliente Magis+TMDB directos) y 3 (Ditu/RCN directos) vienen
después, cada uno con su propio spec.
