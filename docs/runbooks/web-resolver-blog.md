# Runbook — `web-resolver` (resolver headless de fuentes web) en blog

Servicio que convierte la **pageUrl** de una fuente web (página de detalle de una peli/capítulo)
en una **URL de stream** reproducible (`.m3u8`/`.mp4`) + subtítulos + headers, usando un navegador
headless (Playwright/Chromium) que carga el embed y **snifea** la request real del video. Es
host-agnóstico (no hay resolvers por host). La app lo consume vía `WebResolverApi` y reproduce el
stream en el player unificado (`SourceKind.WEB` → `loadWeb`).

## Ubicación / stack

- Host: `blog` (SSH `blog`), usuario `familia`.
- Dir: `~/web-resolver/` (`server.js`, `package.json`, `node_modules/`).
- Puerto local: `127.0.0.1:8123`. Endpoints: `GET /resolve?url=<pageUrl>`, `GET /health`.
- Node v22. Playwright + su Chromium propio (`npx playwright install chromium`).
- Serializado (un resolve a la vez; blog es 2 CPU) + timeout duro.

## Deploy (desde el Mac)

```bash
# 1. Editar server.js/package.json localmente y subir
scp package.json server.js blog:~/web-resolver/
# 2. Instalar deps + Chromium (una vez, o al cambiar versión de Playwright)
ssh blog 'cd ~/web-resolver && npm install --no-audit --no-fund && npx playwright install chromium'
# 3. (Re)iniciar el servicio
ssh blog 'systemctl --user restart web-resolver'
```

## systemd (usuario) — `~/.config/systemd/user/web-resolver.service`

```ini
[Unit]
Description=Web Resolver (Playwright) para Arkiv
After=network.target
[Service]
ExecStart=/usr/bin/env node %h/web-resolver/server.js
Restart=on-failure
[Install]
WantedBy=default.target
```
```bash
ssh blog 'systemctl --user daemon-reload && systemctl --user enable --now web-resolver'
ssh blog 'systemctl --user status web-resolver --no-pager | head; curl -s 127.0.0.1:8123/health'
```

## Cloudflare tunnel

Enrutar un hostname (ej. `webresolver.comparadorinternet.co`) → `127.0.0.1:8123` en la config del
`cloudflared` existente (igual que Jackett). La app usa ese host en `SettingsStore.DEFAULT_WEB_RESOLVER_URL`
(`.../resolve`). Verificar: `curl -s https://webresolver.comparadorinternet.co/health`.

## Probar / depurar

```bash
# Contra el sitio real (una peli de un canal que anda, ej. sololatino):
ssh blog 'curl -s "http://127.0.0.1:8123/resolve?url=<pageUrl>" | head -c 500'
# Esperado: {"ok":true,"streamUrl":"https://....m3u8","headers":{...},"subtitles":[...]}
# Logs:
ssh blog 'journalctl --user -u web-resolver -n 50 --no-pager'
```

Si devuelve `{"ok":false,"error":"no se detectó stream"}`:
- El embed puede requerir un click distinto para arrancar el video → ajustar el selector del botón
  play en `resolveOne` (`.play, .vjs-big-play-button, [class*=play]`).
- El stream puede cargarse recién tras un gesto/tiempo mayor → subir el `deadline` (12s) o esperar
  un `waitForSelector('video')` en el iframe.
- El host puede necesitar entrar a un 2º iframe anidado → iterar `page.frames()` en profundidad.
- Algunos hosts sirven `blob:`/MSE sin URL directa → esos no se pueden sniffear (limitación).

## Limitación conocida (cast/DLNA)

Streams que exigen `Referer`/headers reproducen en el celu (VLC manda los headers) pero pueden fallar
en **Chromecast/DLNA** (la TV fetchea directo, sin headers). Mitigación futura: proxear el stream con
headers desde blog. Fuera de alcance actual.

## Soporte embed69 (hosts cifrados, portado de Alfa)

Algunos canales (serieskao, entrepeliculasyseries, sololatino a veces) sirven el video a través de
**embed69.org** (iframe `.../vidurl/...`): un meta-embed que trae los links de cada servidor
**cifrados con AES**, y la clave a veces exige resolver un **Proof-of-Work** (`POW_CHALLENGE`/
`POW_DIFFICULTY`/`POW_SALT`). El sniffer genérico NO los cracker (el player deobfusca en JS).

`server.js` porta la lógica de Alfa (`lib/crylink.py::crylink` + `solve_pow` + `bypass_embed69`):
1. Detecta el iframe embed69/vidurl en el detalle y baja su HTML.
2. `bypassEmbed69(html)`: extrae la clave AES (patrón `decryptLink(server.link,'...')`) o la deriva
   resolviendo el PoW (sha256 brute-force sobre `${challenge}${nonce}`, `salt` → `sha256` = key).
3. `crylink` (AES-CBC, IV=primeros 16 bytes, PKCS7): desencripta cada `sortedEmbeds[].link` →
   URL de un host real (minochinos/filemoon/voe/etc.).
4. Navega el sniffer a esa URL (prefiere idioma LAT) y snifea el `.m3u8`/`.mp4`.

Referencia Alfa: `channels/serieskao.py::findvideos`, `lib/unshortenit.py::{bypass_embed69,solve_pow}`,
`lib/crylink.py`. Si embed69 cambia el esquema (nuevo patrón de clave/PoW), ajustar esos regex/lógica.

## Soporte DooPlay `?trembed=` (seriesmega/FullSerieHD y similares)

Los temas DooPlay (torofilm, etc.) sirven el player como iframes **wrapper same-origin**
`.../?trembed=N&trid=XXXX&trtype=2` que a su vez embeben el host real (vidhidepre, doodstream…).
El sniff directo sobre el detalle NO arranca el video (el wrapper no autoplaya en frame anidado).
`resolveOne` desempaca la cadena: recoge todos los `<iframe data-src|src>` del detalle, filtra los
`?trembed=`, baja cada wrapper (con Referer del detalle) y extrae su `<iframe src>` real → snifea esos
hosts. **vidhidepre resuelve; doodstream no** (anti-headless, necesita el token `pass_md5` — pendiente
de portar como resolver dedicado estilo `servers/doodstream.py` de Alfa si se quiere).

Nota: seriesmega es sitio de **series** — sus páginas `/movies/` son stubs vacíos, por eso el canal
`fullseriehd` en `web_sources.json` quedó **solo con `browse.tv`/`keywords.tv`** (sin movie).

## Copia versionada del `server.js`

El `server.js` vive en blog (`~/web-resolver/`), pero se guarda una copia de referencia en
`docs/runbooks/web-resolver-src/server.js` (para diffs/historial). Al editar: cambiar ahí, `scp` a
blog y `systemctl --user restart web-resolver`.
