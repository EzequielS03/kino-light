# Qué es esto

**Kino Light** — app Android (Kotlin + Compose) para ver películas y series. Uso personal, no hay
usuarios ni cuentas. Corre en celular y en Android TV / Fire TV con la misma base y pantallas
distintas.

- `applicationId`: `com.arkiv.player.light` — convive en el mismo aparato con Arkiv completo.
- El package y las clases siguen diciendo `arkiv`: **Kino es solo la marca**, no hay rename pendiente.
- Nació el 2026-09-08 como la rama `light-magis` de `lordmacu/arkiv`; repo propio desde 2026-09-13.

## De dónde sale el video

Tres fuentes, y `SourceKind` (en `playback/`) tiene exactamente cinco valores:
`UNKNOWN, MAGIS, LOCAL, LIVE, DITU`.

| fuente | qué es | dónde vive |
|---|---|---|
| **Magis** | Portal IPTV, protocolo crackeado. VOD + canales en vivo. El grueso del catálogo. | `data/magis/` |
| **Caracol / Ditu** | Caracol Streaming (Colombia). Gratis, sin cuenta. **DASH + Widevine.** | `data/ditu/`, `data/caracol/` |
| **Local** | Lo ya descargado al aparato. | `data/local/` |

**TMDB** (`data/catalog/`) aporta metadatos y pósters; **AniList** las filas de anime; **Kilo** el
dato curioso y "Para ti". Ninguno es servidor propio — ver [reglas.md](reglas.md).

## Cómo está organizado

```
app/src/main/java/com/arkiv/player/
  data/       107 archivos — fuentes, Room, repositorio
    magis/      el portal: sesión, catálogo, resolución de VOD
    ditu/       la API AVS de Caracol
    caracol/    las descargas de Caracol (caché cifrado). Ver trampas.md
    local/      cola de descargas, estrategias por fuente, biblioteca en disco
    catalog/    TMDB + AniList
    gateway/    el CONTRATO común a todas las fuentes (FuenteDeContenido)
  ui/         113 archivos — Compose; `ui/tv/` es la variante de televisor
    player/     el reproductor y su overlay de controles
  playback/    39 archivos — ExoPlayer, proxies HTTP locales, servidores LAN
  cast/        Chromecast
  dlna/        renderers DLNA de la red
```

**El reproductor es ExoPlayer/media3 y nada más.** libVLC se borró entero, no hay respaldo. Hay tres
reproductores según la fuente: `MagisExoPlayer`, `DituExoPlayer` (el que negocia el DRM) y
`LiveExoPlayer` (canal en vivo, vía `LiveHlsProxy`).

`data/gateway/FuenteDeContenido` es la abstracción que hace que Magis y Caracol se parezcan desde
arriba: buscar, listar capítulos, resolver a algo reproducible. Una fuente nueva se enchufa ahí.

## Dónde está la documentación que sí sirve

- `docs/superpowers/specs/2026-09-*-arkiv-light-*.md` — los siete specs de esta app, en orden. Son
  la historia de por qué el código es así.
- El resto de `docs/`: leer con desconfianza, ver [trampas.md](trampas.md#docs-podrido).
- **La historia de git.** 961 commits, y los mensajes traen las mediciones y el porqué. `git log` de
  un archivo suele explicar más que el archivo.
