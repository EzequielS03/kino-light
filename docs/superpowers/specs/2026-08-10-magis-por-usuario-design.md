# Spec 2 — Magis por usuario (vínculo de la cuenta Magis a la persona)

- **Fecha:** 2026-08-10
- **Estado:** Diseño aprobado (pendiente de plan de implementación)
- **Repos afectados:** `arkiv-api` (gateway, el grueso) + `archive` (app Android: header + UI)
- **Depende de:** Spec 1 — Cuentas Arkiv (`docs/superpowers/specs/2026-08-10-cuentas-arkiv-design.md`), ya implementado y en `main`. La "persona" = un `accountId` de PocketBase.

## Contexto y problema

Hoy el gateway `arkiv-api` mantiene **UNA sola sesión Magis global** compartida por todos
(`adapters/magis/session.py`: modo `anonimo` por `sn`, o UNA cuenta global vía
`POST /v1/magis/credentials`). El objetivo: que **cada persona use SU cuenta de Magis**, con el
token guardado en el backend colgado de su `accountId`, y usado en su búsqueda/reproducción, sin
que las cuentas se pisen entre sí. Un device sin vincular sigue anónimo.

Con el Spec 1 en pie, la "persona" ya existe (`accountId`). Este spec cuelga el Magis de ese
`accountId`.

### Estado actual del gateway (confirmado, head `73b9262`)
- `MagisSession` = "la ÚNICA sesión, compartida por todos"; claves Redis `magis:session` (token) y
  `magis:credenciales` (creds cifradas con llave maestra). Dos modos: anónimo (activate por `sn`
  whitelisted) o cuenta (login por email). El `sn` no se puede multiplicar → por eso el gateway es
  el único que activa y presta el token.
- `router/magis.py`: `GET /v1/magis/session`, `POST /v1/magis/credentials`, `DELETE /v1/magis/credentials` — todos **globales**.
- `MagisAdapter` (`adapter.py`): `search`/`resolve`/`episodes` usan `self._session.client()` fijo.
  Cache: `magis:search:*` y `magis:eps:*` (catálogo, global) y `magis:play:*` (el `Playable`, que
  **embebe el `Content-Auth` de la sesión**).
- Auth: solo `X-Arkiv-Key` (llave compartida, va en todos los APK). Sin noción de usuario.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Identidad de la request | La app manda `X-Arkiv-Account: <accountId>`; el gateway **confía** (accountId es UUID no adivinable; ya todo va detrás del `X-Arkiv-Key`). |
| Requisito para vincular Magis | **Requiere cuenta Arkiv** (estar logueado). Así el vínculo sigue a la persona entre devices. |
| Endpoints globales actuales | **Se conservan** (los usa el CLI de magia y el fallback anónimo). Los nuevos `/magis/link` conviven por `accountId`. |
| Token en vivo | Keyeado por `accountId` → **compartido entre los devices de la persona** (evita el kick del portal). |
| `v8/login` / `v5/loginOut` | Se **reusan** de `session.py` (no se reescriben). |

## Diseño — Gateway (`arkiv-api`)

### Sesiones Magis por `accountId`
- `MagisSession` se parametriza con un `identity`:
  - **Anónima** (`identity` = `"anon"`): mantiene las claves ACTUALES `magis:session` / `magis:credenciales` (retrocompat con el CLI y el `GET /v1/magis/session`).
  - **Por persona** (`identity` = `accountId`): `magis:sess:<accountId>` (token, TTL ~48h) y `magis:cred:<accountId>` (creds cifradas con la llave maestra, igual mecanismo que hoy).
- Un `MagisSessions` (registry/factory) devuelve `for_account(accountId)` o la anónima. Reusa el
  bucket de rate-limit, el `factory` del cliente y la llave maestra que ya recibe `MagisSession`.
- **Link** = `guardar_credenciales(usuario, clave)` sobre la sesión del `accountId`: verifica con
  `v8/login` ANTES de guardar (si el portal rechaza, no guarda nada y la sesión previa queda
  intacta), luego persiste creds + calienta el token. **Unlink** = `v5/loginOut` (fire-and-forget)
  + borra `magis:cred:<accountId>` y `magis:sess:<accountId>`.

### Fallback anónimo (sin romper nada)
- Request sin `X-Arkiv-Account`, o con un `accountId` sin Magis vinculado → la sesión anónima
  compartida de hoy. Todo el que no vincule opera igual que ahora.

### Ruteo en el adapter
- `SearchContext` y los payloads de `resolve`/`episodes` ganan un `account_id` (opcional). El
  router unificado (`/v1/search`, `/v1/resolve`, `/v1/episodes`) lee `X-Arkiv-Account` y lo pasa
  al contexto; solo el adapter de Magis lo usa.
- `MagisAdapter._intento`/`_llamar` dejan de usar `self._session` fijo: resuelven
  `self._sessions.for_account(account_id or "anon")` por request. El reintento-con-sesión-nueva y
  el `invalidate()` operan sobre esa sesión.

### Cache (correctitud — importante)
- `magis:play:*` (el `Playable`) embebe el `Content-Auth`/license de la sesión → se **scopea por
  sesión**: `magis:play:v6:<sesskey>:<cid>:<ep>` con `sesskey` = `accountId` o `anon`. Sin esto,
  el token de una cuenta se serviría a otra → 401 en el CDN.
- `magis:search:*` y `magis:eps:*` = catálogo, iguales para todos → **quedan globales** (sin cambio).

### Endpoints (nuevos, todos con `X-Arkiv-Account`)
- `POST /v1/magis/link` `{username, password}` → `v8/login`; rechazo del portal → **422** (no
  guarda nada). Responde `{status:"vinculado", account}`.
- `DELETE /v1/magis/link` → `v5/loginOut` + borra creds/token de ese `accountId`.
- `GET /v1/magis/link` → `{linked: bool, account?, expires_in}` para pintar Ajustes.
- Los `GET /v1/magis/session` y `POST/DELETE /v1/magis/credentials` globales **se conservan** para el CLI.

## Diseño — App (`archive`, Android)

- `ArkivApiClient` agrega el header `X-Arkiv-Account: <accountId>` en `search`/`resolve`/`episodes`
  cuando hay accountId efectivo (del `AccountManager`/`DeviceAuthManager`). Sin login Arkiv no se
  manda (fallback anónimo).
- En Ajustes → Cuenta (debajo de la sección de cuenta, y **solo si hay sesión Arkiv**): **"Vincular
  Magis"** (email + clave de Magis) → `POST /v1/magis/link`. Si ya está vinculado → "Vinculado como
  X" + "Desvincular" (`DELETE`). Un `MagisLinkClient` pequeño encapsula los 3 endpoints.
- Estado de UI (vinculado sí/no + etiqueta) en DataStore o vía `GET /v1/magis/link`.

## Manejo de errores
- Link inválido (portal rechaza) → 422 → mensaje inline; la sesión previa (si había) queda intacta.
- Portal expulsa/expira a mitad de uso → el adapter ya invalida y reintenta 1 vez; con creds
  guardadas re-loguea solo (persistencia del token cumplida).
- Cambia la llave maestra → las creds guardadas no abren → se descartan → cae a anónimo (mismo
  patrón que hoy).
- Header presente pero accountId sin vínculo → anónimo (no es error).

## Testing
- **Gateway**: `MagisSessions` (selección por accountId vs anónima; claves separadas; anon mantiene
  las claves viejas), round-trip de cifrado por accountId, link (verifica antes de guardar),
  unlink (loginOut + borra), kick→relogin, y la clave de cache de `resolve` scopeada por sesión.
  Extender `test_magis_session.py` / `test_router_magis.py`.
- **App**: `ArkivApiClient` manda `X-Arkiv-Account` cuando hay accountId; UI vincular/desvincular.

## Fuera de alcance (a propósito)
- Validación fuerte de identidad (token PocketBase) — se eligió el header confiado; hardening futuro.
- TV en vivo de Magis (sigue fuera del gateway, como hoy).
- Recuperación/registro de la cuenta **Magis** desde la app (eso lo hace el portal; el CLI ya lo tiene).

## Riesgos / preguntas abiertas
- **Spoofing de accountId**: con la llave compartida (que está en el APK) + el UUID de una víctima,
  alguien podría usar el Magis de otro. Aceptado para app personal (UUID no adivinable); hardening
  = validar token PocketBase.
- **Qué desbloquea vincular**: depende de si el `resolve` de una cuenta de pago difiere del anónimo
  (¿token/tier de CDN distinto? ¿catálogo extra?). El cache scopeado por sesión ya lo cubre; si el
  catálogo de búsqueda difiere por cuenta, habría que scopear también `magis:search` (hoy no).
- **Multi-device misma cuenta Magis**: keyear por `accountId` comparte un token entre los devices
  de la persona → no pelean por los cupos del portal (openNum ~4). Deseado.

## Cierre del arco
Con Spec 1 (cuentas Arkiv) + Spec 2 (Magis por persona), se cumple el pedido original: una opción
en Ajustes para loguearte con Magis, con el token guardado en el backend por la persona logueada,
usado en búsqueda/reproducción, y aislado entre usuarios; un device sin login queda anónimo.
