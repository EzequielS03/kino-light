# Spec 3 — Device anónimo auto-provisionado (`v3/snToken`)

- **Fecha:** 2026-08-10
- **Estado:** Diseño aprobado (pendiente de plan de implementación)
- **Repo afectado:** `arkiv-api` (gateway) — SOLO el camino anónimo de Magis.
- **Relación:** complementa Spec 2 (Magis por usuario). NO toca el login per-usuario (Spec 1/2): ese camino ya sirve la cuenta Magis de cada persona en todos sus dispositivos (token keyeado por `accountId`). Este spec solo hace robusto el ANÓNIMO.
- **Origen:** handoff del usuario `/Users/cristian/magia/docs/HANDOFF_AUTH_UX_v1.4.md` (v1.4.6).

## Contexto y problema

Hoy TODA activación anónima de Magis usa un único `IPTV_DEVICE_SN` **whitelisted** configurado a
mano en el env (`config.py:44,68` lo EXIGE; `vendor/iptv_client.py:119` lo lee; `activate()` hace
`v8/active` con `snToken=""` usando ese `sn`). Eso es frágil: el `sn` hay que conseguirlo, es único,
y si el portal lo expulsa (o el CLI activa el mismo `sn` en otro lado) → ping-pong / activación
muerta. El review final del Spec 2 encontró justo un caso de este ping-pong (Critical, ya mitigado
con el fallback-a-anónimo).

El handoff reveló `v3/snToken`: el portal **mintea** un device nuevo (`snToken` UUID →
`sn = MD5(snToken + salt)` → `v8/active`), sin tocar ninguna cuenta. Esto permite que el gateway
**auto-provisione** su device anónimo en vez de depender del `sn` whitelisted manual.

## Decisión de alcance
- **Un device anónimo COMPARTIDO, auto-provisionado** (no uno por accountId). Suficiente para el
  contenido anónimo/free; cambio chico; sin minteo masivo (un device, re-minteado solo si lo
  expulsan → sin riesgo de abuso). El aislamiento por-cuenta se descartó (más código/minteo sin
  beneficio para contenido anónimo).
- **`IPTV_DEVICE_SN` = semilla opcional.** Precedencia del `sn`: **persistido en Redis
  (`magis:sn:anon`) → env (semilla) → mintear**. Backward-compat: el `sn` whitelisted actual se
  sigue usando hasta que lo expulsen; ahí se re-mintea.

## Diseño

### 1. Vendored client (`adapters/magis/vendor/iptv_client.py`)
Portar `new_anonymous_device()` del handoff (§v1.4.6):
- `SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"`.
- Arma una huella de hardware fresca (todos strings; MACs/ids random), limpia `sn/drmId/deviceToken/reserve1`.
- `call("v3/snToken", fingerprint, base_fields=False)` → `snToken`.
- `sn = (resp.sn or MD5(snToken + SNTOKEN_SALT)).lower()`; setea `self.device["sn"]`, `self.sn_token`.
- `call("v8/active", {snToken, ...}, base_fields=False)`; al éxito setea `user_id`/`user_token` y
  devuelve `{...active, "sn": sn, "snToken": snToken}`.
El cliente ya activa con el `sn` de `self.device["sn"]`, así que la sesión puede **inyectar** un `sn`
persistido antes de activar (setear `device["sn"]` en el factory/cliente).

### 2. Sesión anónima auto-provisionada (`adapters/magis/session.py`, solo `identity == "anon"`)
- Clave nueva Redis: `magis:sn:anon` = el `sn` en uso (persistido, estable entre reinicios — el
  handoff confirma que re-activar con `snToken=""` + ese `sn` devuelve el mismo `userId`).
- `client()` del anon (extiende el flujo actual): token cacheado → reusar; si no:
  1. `sn = get(magis:sn:anon) or IPTV_DEVICE_SN(env)`.
  2. Si hay `sn` → crear cliente con ese `sn` y `activate()` (v8/active, snToken=""). Si falla con
     error de **sn inválido** (`aaa100080` / `snToken已经失效`) → ir a (3).
  3. Sin `sn` (o sn inválido) → `new_anonymous_device()` (mintea), persistir el `sn` en `magis:sn:anon`.
  - Al activar OK, persistir la sesión (token) como hoy (`_persistir`) y, si se minteó/cambió, el `sn`.
- La lógica per-cuenta (login Magis) y `for_request`/`for_account` NO cambian: minting es solo del
  anon. (Las cuentas con Magis vinculado hacen `v8/login`, no dependen del `sn`.)

### 3. Config (`config.py`)
- `iptv_device_sn` pasa a **opcional**: `credential_namespaces["magis"]` deja de exigirlo
  (`bool(iptv_3des_key and iptv_hosts)`; el device se mintea on-demand). Si está seteado, se usa como
  semilla (ver precedencia).

### 4. Fuera de alcance / sin cambios
- Sesiones por-cuenta, endpoints `/magis/link`, cache scopeada, el fallback-a-anónimo del Critical:
  **intactos**. El fallback ahora se respalda con un device anónimo propio y robusto.
- La app y el login per-usuario (Spec 1/2): **no se tocan**.

## Manejo de errores
- Mint falla (portal caído) → `new_anonymous_device` devuelve `{_error:...}`; la sesión propaga el
  error como cualquier fallo de Magis (el adapter ya lo maneja; el anónimo simplemente no está
  disponible ese momento, se reintenta).
- Activar con `sn` persistido inválido → re-mint (una vez); si el mint también falla → error normal.
- Se evita loop de mint: el re-mint es 1 nivel (sn inválido → mint → activate); si el minteado
  también da sn inválido, se propaga el error (no re-mint infinito).

## Testing (`tests/test_magis_session.py`, y `test_magis_crypto`/nuevo para la fórmula del `sn`)
- Sin `sn` persistido ni env → mintea (v3/snToken) y persiste `magis:sn:anon`.
- Con `sn` persistido → reusa (no mintea).
- Con env sn y sin persistido → usa el env (semilla).
- Activar con `sn` inválido → re-mintea y persiste el nuevo.
- Fórmula `sn = MD5(snToken + salt)` (si el server no manda `sn`).
Usar `fakeredis` + un `ClienteFalso` que simule `v3/snToken`/`v8/active` y cuente minteos/activaciones.

## Riesgos / preguntas abiertas
- **Huella de hardware del mint:** el portal podría validar coherencia de la fingerprint; se usa un
  set de emulador plausible (del handoff). Si el portal la rechaza, ajustar los campos.
- **Detección de minteo:** un device compartido re-minteado ocasionalmente es de bajo perfil
  (a diferencia del minteo masivo por-cuenta que se descartó).
- **Migración:** las instalaciones con `IPTV_DEVICE_SN` seteado siguen igual hasta el primer kick;
  no hay paso de migración forzado.

## Cierre
Con Spec 3, el gateway ya no depende de un `sn` whitelisted frágil: se auto-provisiona el device
anónimo y se recupera solo ante expulsión. Complementa el Spec 2 sin tocar el per-usuario.
