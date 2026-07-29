# PocketBase — Esquema de colecciones (Arkiv)

Instancia: `https://db.comparadorinternet.co` (superadmin del usuario).
Modelo de aislamiento: cada record lleva `accountId` (UUID secreto) y las reglas solo permiten ver/escribir lo de la cuenta propia. El `accountId` funciona como bearer.

## `devices` (auth) — creada y verificada 2026-07-21

Cada dispositivo (celu/TV) es un record auth con su propio token. El celu se da de alta anónimo (cuenta nueva); el TV lo crea el celu durante el pareo.

**Campos custom** (además de los de sistema id/email/password/tokenKey/emailVisibility/verified):

| Campo | Tipo | Notas |
|---|---|---|
| `accountId` | text | required; índice `idx_devices_accountId` |
| `kind` | select (maxSelect 1) | valores: `phone`, `tv`; required |
| `deviceName` | text | opcional (nombre legible del dispositivo) |
| `online` | bool | opcional |
| `lastSeen` | date | opcional |
| `caps` | json | opcional (maxSize 20000) |
| `nowPlaying` | json | opcional (maxSize 4000); estado de reproducción que publica el TV para el miniplayer del celu. Formato en `remote/NowPlayingCodec.kt`; `null` = no hay nada reproduciéndose |

**Reglas de acceso (verificadas end-to-end):**

- **List/View:** `@request.auth.id != "" && accountId = @request.auth.accountId`
- **Create:** `@request.auth.id = "" || accountId = @request.auth.accountId`
  (permite el alta anónima del primer device con `accountId` nuevo, y que el celu autenticado cree devices en SU cuenta)
- **Update:** `accountId = @request.auth.accountId`
- **Delete:** `accountId = @request.auth.accountId`
- **passwordAuth:** enabled, identityFields = `email`

Pruebas pasadas: alta anónima ✓, auth-with-password ✓, list solo ve la cuenta propia ✓, lectura de otra cuenta devuelve 0 ✓.

## `pair_requests` (base) — creada 2026-07-21 (Plan 2)

Rendezvous cross-account para el pareo por QR. El `code` del QR NUNCA se guarda (solo su hash con separación de dominio); las credenciales del TV viajan cifradas.

**Campos:** `codeHash` (text, required, índice `idx_pair_requests_codeHash`), `status` (select: pending/claimed, required), `payload` (text, cifrado AES-GCM, max 8000), `tvName` (text), `expiresAt` (date).

**Reglas:** list/view/create/update/delete = `@request.auth.id != ""` (permisivas a nivel auth porque el pareo es cross-account: el TV y el celu están en cuentas distintas hasta parear).

**Modelo de amenaza:** la seguridad NO depende de las reglas sino de la cripto:
- `codeHash = SHA-256("arkiv-pair-lookup:" + code)` → enumerar records no revela el `code`.
- `encKey = SHA-256("arkiv-pair-key:" + code)` (prefijo distinto) → el codeHash guardado no revela la clave.
- `payload` cifrado+autenticado con AES-GCM(encKey) → sin el `code` (solo en el QR) no se lee ni se forja un payload que el TV acepte (el tag GCM falla).
- Riesgo residual: un atacante autenticado podría borrar/sobrescribir pair_requests pendientes (DoS del pareo). Aceptable para uso personal; el usuario reintenta.

## `commands` (base, realtime) — creada 2026-07-21 (Plan 3)

Comandos remotos (play y teclas del pad) cuando el celu y el TV NO están en la misma WiFi. Aislamiento estricto por cuenta (ambos comparten cuenta tras parear).

**Campos:** `accountId` (text, req), `targetDeviceId` (text, req, índice `idx_commands_target`), `fromDeviceId` (text), `type` (select, req), `payload` (json, max 20000), `seq` (number), `ack` (bool).

Valores de `type`: `play`, `key`, `subprefs`, `webquality`, `pause`, `resume`, `seek`, `next`, `prev`, `stop`.

> **Cuidado con este select.** Hasta la migración `1785180813_updated_devices_commands.js` (jul 2026)
> solo admitía `play` y `key`, pero la app ya enviaba `subprefs` y `webquality` desde antes: el server
> los rechazaba y `CloudTransport.send` se tragaba la excepción devolviendo `false`, así que la
> sincronización de subtítulos y de calidad web por nube **nunca funcionó** y falló en silencio.
> Al agregar un tipo de comando nuevo en la app hay que agregarlo también acá.

**Reglas:** list/view/create/update/delete = `@request.auth.id != "" && accountId = @request.auth.accountId`.

El emisor crea un record; el receptor (suscrito por realtime a `commands`, filtrando `targetDeviceId == su recordId`) lo ejecuta y marca `ack=true`. Idempotencia por `seq` monótono.

## Pendientes (fases siguientes)

- `library_items`, `progress` (base, realtime) — Plan 4 (sync tiempo real).
