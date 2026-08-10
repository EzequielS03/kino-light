# Device anónimo auto-provisionado (v3/snToken) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El gateway auto-provisiona su device anónimo de Magis con `v3/snToken` (persiste el `sn`, re-mintea si lo expulsan), en vez de depender de un `IPTV_DEVICE_SN` whitelisted manual. Solo el camino anónimo.

**Architecture:** Se porta `new_anonymous_device()` al `iptv_client` vendorizado. La sesión anónima (`MagisSession` con `identity == "anon"`) resuelve el `sn` con precedencia **Redis `magis:sn:anon` → env (semilla) → mintear**, activa con él, y re-mintea si el `sn` es inválido. Config: `iptv_device_sn` pasa a opcional. Per-cuenta / link / cache / fallback del Critical: sin cambios.

**Tech Stack:** Python (redis async, pytest-asyncio, fakeredis) en `arkiv-api`.

## Global Constraints

- **Solo el anónimo** (`identity == "anon"`) mintea/auto-provisiona. Las sesiones por-cuenta (login Magis) y `for_request`/`for_account` NO cambian.
- Precedencia del `sn`: `magis:sn:anon` (Redis) → `IPTV_DEVICE_SN` (env, semilla) → mintear. Re-mint **un solo nivel** ante `sn` inválido (sin loop).
- Backward-compat: el CLI (`GET /v1/magis/session`) y `/magis/credentials` globales siguen; el `sn` del env se sigue usando como semilla hasta el primer kick.
- Commits `lordmacu`, sin coautoría; `git add <paths>` explícito.
- Deploy es aparte (rsync + docker compose), NO git pull. El plan solo deja código+tests verdes.
- Tests: `.venv/bin/python -m pytest` (macOS: no hay `python`).
- Salt (del reversing, handoff §Apéndice): `SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"`; `sn = MD5(snToken + salt)` hex-lower.

---

## File Structure

- `src/arkiv_api/adapters/magis/vendor/iptv_client.py` — nuevo `new_anonymous_device()` + `SNTOKEN_SALT`.
- `src/arkiv_api/adapters/magis/session.py` — `MagisSession` anon: resolver/persistir `sn`, re-mint.
- `src/arkiv_api/config.py` — `iptv_device_sn` opcional en `credential_namespaces["magis"]`.
- Tests: `tests/test_magis_session.py` (+ un test de la fórmula del `sn` / mint flow).

---

## Task 1: `new_anonymous_device()` en el vendored client

**Files:**
- Modify: `src/arkiv_api/adapters/magis/vendor/iptv_client.py`
- Test: `tests/test_magis_session.py` (o un `tests/test_magis_sntoken.py` nuevo)

**Interfaces:**
- Produces: `IPTVClient.new_anonymous_device()` → hace `v3/snToken` → `sn = MD5(snToken+salt)` → `v8/active`; deja `self.user_id/user_token` free y `self.device["sn"]`/`self.sn_token` nuevos; devuelve el dict de active con `{"sn","snToken"}` agregados, o `{"_error": ...}`. Módulo expone `SNTOKEN_SALT`.

- [ ] **Step 1: Escribir el test que falla**

En un test nuevo (mockeando `call` para no tocar red), verificar la secuencia + la fórmula:
```python
import hashlib
from arkiv_api.adapters.magis.vendor.iptv_client import IPTVClient, SNTOKEN_SALT

def test_new_anonymous_device_mintea_y_deriva_sn(monkeypatch):
    c = IPTVClient(user_id="", user_token="", auto_activate=False)
    llamadas = []
    def fake_call(path, bean=None, base_fields=True):
        llamadas.append(path)
        if path == "v3/snToken":
            return {"snToken": "TOK123", "isNew": "1"}          # sin `sn` -> se deriva
        if path == "v8/active":
            return {"userId": "u1", "userToken": "t1"}
        return {}
    monkeypatch.setattr(c, "call", fake_call)
    r = c.new_anonymous_device()
    assert llamadas == ["v3/snToken", "v8/active"]
    esperado = hashlib.md5(("TOK123" + SNTOKEN_SALT).encode()).hexdigest().lower()
    assert c.device["sn"] == esperado
    assert c.user_id == "u1" and c.user_token == "t1"
    assert r["sn"] == esperado and r["snToken"] == "TOK123"
```

- [ ] **Step 2: Correr — debe fallar** (`AttributeError: new_anonymous_device` / `ImportError SNTOKEN_SALT`).

Run: `.venv/bin/python -m pytest tests/test_magis_sntoken.py -q`

- [ ] **Step 3: Implementar** (portar del handoff §v1.4.6, adaptando al vendored client)

En `iptv_client.py` (arriba, junto a otros imports/constantes):
```python
import secrets, hashlib
SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"
```
Método en `IPTVClient` (cerca de `activate`):
```python
    def new_anonymous_device(self):
        """v3/snToken -> snToken -> sn=MD5(snToken+salt) -> v8/active. No toca ninguna cuenta.
        Al exito deja self con userId/userToken free y self.device['sn'] nuevo."""
        def _mac(): return ":".join("%02x" % secrets.randbelow(256) for _ in range(6))
        fp = {
            "androidId": secrets.token_hex(8), "board": "goldfish_arm64", "brand": "google",
            "cpuAbi": "arm64-v8a", "cpuId": secrets.token_hex(8), "device": "emu64a",
            "diskInfo": "8GB", "display": "sdk_gphone64_arm64", "etheMac": _mac(),
            "fingerprint": "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
            "gatewayMac": _mac(), "hardware": "ranchu", "host": "abfarm", "manufacturer": "Google",
            "ramSize": "4GB", "romSize": "8GB", "serialNumber": secrets.token_hex(8),
            "tags": "release-keys", "verId": "", "wifiMac": _mac(),
        }
        for k in ("sn", "drmId", "deviceToken", "reserve1"):
            self.device[k] = ""
        r = self.call("v3/snToken", fp, base_fields=False)
        sn_token = r.get("snToken") if isinstance(r, dict) else None
        if not sn_token:
            return {"_error": "snToken_failed", "_detail": r}
        sn = (r.get("sn") or hashlib.md5((sn_token + SNTOKEN_SALT).encode()).hexdigest()).lower()
        self.device["sn"] = sn
        self.sn_token = sn_token
        bean = {"snToken": sn_token, "authVersion": "", "authCode": "", "preCode": "",
                "macAddr": "02:00:00:00:00:00", "reserve1": "", "openNum": 4,
                "channel": "default", "matadata": "", "signdata": ""}
        ar = self.call("v8/active", bean, base_fields=False)
        if isinstance(ar, dict) and ar.get("userToken"):
            self.user_id = ar["userId"]; self.user_token = ar["userToken"]
            ar = {**ar, "sn": sn, "snToken": sn_token}
        return ar
```

- [ ] **Step 4: Correr — debe pasar**

Run: `.venv/bin/python -m pytest tests/test_magis_sntoken.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/magis/vendor/iptv_client.py tests/test_magis_sntoken.py
git commit -m "feat(magis): new_anonymous_device (v3/snToken) en el vendored client"
```

---

## Task 2: Sesión anónima auto-provisionada

**Files:**
- Modify: `src/arkiv_api/adapters/magis/session.py`
- Test: `tests/test_magis_session.py`

**Interfaces:**
- Consumes: `IPTVClient.new_anonymous_device()` (Task 1), `activate()`, `client.device["sn"]`.
- Produces: `MagisSession` (para `identity == "anon"`) resuelve el `sn` con precedencia Redis(`magis:sn:anon`)→env→mint, activa, re-mintea si el `sn` es inválido, y persiste el `sn` usado. La rama per-cuenta y el resto de `client()` no cambian.

- [ ] **Step 1: Escribir los tests que fallan**

En `tests/test_magis_session.py`, con un `ClienteFalso` extendido que simula activate/mint y cuenta cada uno. Casos:
```python
async def test_anon_sin_sn_mintea_y_persiste():
    # redis vacío, env sin sn -> client() del anon debe MINTEAR y guardar magis:sn:anon
    ...
    assert await redis.get("magis:sn:anon") == "<sn minteado>"
    assert Cliente.minteos == 1 and Cliente.activaciones == 0

async def test_anon_con_sn_persistido_reusa_sin_mintear():
    await redis.set("magis:sn:anon", "sn-guardado")
    ...
    assert Cliente.minteos == 0   # activa con el sn guardado

async def test_anon_sn_invalido_re_mintea():
    await redis.set("magis:sn:anon", "sn-malo")
    # el fake activate() con "sn-malo" devuelve error aaa100080 -> re-mint
    ...
    assert Cliente.minteos == 1
    assert await redis.get("magis:sn:anon") != "sn-malo"
```
(Definir un `ClienteAnon` de test: `activate()` setea user_token solo si `device["sn"]` es un sn "bueno", si no devuelve `{"_error":"aaa100080"}`; `new_anonymous_device()` setea un sn bueno + user_token y cuenta el minteo.)

- [ ] **Step 2: Correr — deben fallar**

Run: `.venv/bin/python -m pytest tests/test_magis_session.py -k anon -q`

- [ ] **Step 3: Implementar**

En `session.py`, agregar la clave y modificar SOLO la rama de activación anónima de `client()`:
```python
    # (en __init__, junto a _KEY/_KEY_CRED) — solo relevante para anon:
    self._SN_KEY = "magis:sn:anon"
```
En `client()`, reemplazar el bloque final `await self.throttle(); cliente = await asyncio.to_thread(self._factory); ...` por:
```python
        await self.throttle()
        if self._identity == "anon":
            sn = await self._r.get(self._SN_KEY)
            cliente, sn_usado = await asyncio.to_thread(self._provisionar_anon, sn)
            if sn_usado and sn_usado != sn:
                await self._r.set(self._SN_KEY, sn_usado)
        else:
            cliente = await asyncio.to_thread(self._factory)
        await self._persistir(cliente)
        return cliente
```
Y el helper (sync, corre en hilo; NO toca redis):
```python
    _SN_INVALIDO = ("aaa100080",)   # snToken/sn invalido -> re-mintear

    def _provisionar_anon(self, sn):
        """(cliente, sn_usado). Precedencia: sn(redis) -> env(device['sn']) -> mintear."""
        cliente = self._factory(auto_activate=False)
        semilla = sn or cliente.device.get("sn") or ""
        if semilla:
            cliente.device["sn"] = semilla
            r = cliente.activate()
            if getattr(cliente, "user_token", ""):
                return cliente, semilla
            if not (isinstance(r, dict) and str(r.get("_error", "")) in self._SN_INVALIDO):
                # otro error (red, etc.): devolver el cliente (sin token) como hoy, sin re-mint
                return cliente, semilla
        # sin semilla, o sn invalido -> mintear
        cliente.new_anonymous_device()
        return cliente, cliente.device.get("sn") or None
```

- [ ] **Step 4: Correr — deben pasar (y no romper los tests viejos)**

Run: `.venv/bin/python -m pytest tests/test_magis_session.py -q`

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/magis/session.py tests/test_magis_session.py
git commit -m "feat(magis): sesion anonima auto-provisiona su device (sn: redis->env->mint)"
```

---

## Task 3: `iptv_device_sn` opcional en la config

**Files:**
- Modify: `src/arkiv_api/config.py`
- Test: `tests/test_config.py`

**Interfaces:**
- Produces: `credential_namespaces["magis"]` deja de exigir `iptv_device_sn` (queda `bool(iptv_3des_key and iptv_hosts)`).

- [ ] **Step 1: Escribir el test que falla**

En `tests/test_config.py`:
```python
def test_magis_habilitado_sin_device_sn():
    # con 3des_key + hosts pero SIN iptv_device_sn, magis debe seguir habilitado (se mintea)
    s = Settings(iptv_3des_key="ab"*24, iptv_hosts="h1", iptv_device_sn="")   # ajustar al ctor real
    assert s.credential_namespaces["magis"] is True
```
(Leer `tests/test_config.py` y `config.py` para construir el `Settings` como en los tests existentes.)

- [ ] **Step 2: Correr — debe fallar**

Run: `.venv/bin/python -m pytest tests/test_config.py -k device_sn -q`

- [ ] **Step 3: Implementar**

En `config.py` (línea ~68), cambiar:
```python
"magis": bool(self.iptv_3des_key and self.iptv_hosts and self.iptv_device_sn),
```
por:
```python
"magis": bool(self.iptv_3des_key and self.iptv_hosts),
```

- [ ] **Step 4: Correr — debe pasar + suite completa**

Run: `.venv/bin/python -m pytest tests/test_config.py -q` luego `.venv/bin/python -m pytest -q`
Expected: verde toda la suite.

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/config.py tests/test_config.py
git commit -m "feat(magis): iptv_device_sn opcional (el device se auto-provisiona)"
```

---

## Verificación final
- [ ] `cd /Users/cristian/arkiv-api && .venv/bin/python -m pytest -q` → verde.
- [ ] Manual (con gateway desplegado y SIN `IPTV_DEVICE_SN`): el primer request anónimo mintea un device (`v3/snToken`), persiste `magis:sn:anon`, y sirve contenido; un reinicio reusa ese `sn`.

## Cobertura del spec (self-review)
- `new_anonymous_device` en el vendor → Task 1.
- Sesión anon: precedencia redis→env→mint + re-mint ante sn inválido + persistir → Task 2.
- `iptv_device_sn` opcional → Task 3.
- Fuera de alcance (per-cuenta, link, cache, fallback) → sin tareas, correcto.
