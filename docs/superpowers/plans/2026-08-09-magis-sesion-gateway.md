# Sesión de magis gestionada por el gateway — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el gateway sea el único que activa la sesión del portal magis, establecible de dos formas (anónima o por cuenta), y que el resto de los clientes le pidan el token en vez de crear el suyo.

**Architecture:** `MagisSession` (que ya guarda `userId`/`userToken` en Redis con TTL de 48 h) gana un **modo** y un almacén de credenciales cifradas. Tres rutas nuevas bajo `/v1/magis/*` la exponen. El CLI de `magia` gana un camino previo que consulta al gateway antes de activar.

**Tech Stack:** Python 3.12 · FastAPI · redis-py (asyncio) · pycryptodome (AES-GCM, ya presente por el 3DES del portal) · pytest + fakeredis

**Spec:** `docs/superpowers/specs/2026-08-09-magis-sesion-gateway-design.md`

**Repos:** `~/arkiv-api` (Tasks 1–5) y `~/../magia` → `/Users/cristian/magia` (Task 6)

## Global Constraints

- El `sn` del dispositivo **no se puede generar**: el portal lo valida contra una lista blanca
  (`snToken已经失效`). Todo el diseño parte de que hay **una sola identidad de dispositivo**.
- La contraseña **nunca** se guarda en claro, **nunca** se devuelve en una respuesta y **nunca**
  se escribe en un log.
- El gateway **prefiere modo `cuenta`** si hay credenciales, y **cae a `anonimo`** si el login
  falla. Nunca queda sin servicio.
- `expires_in` va en **segundos**.
- Rate-limit del portal: **1 llamada / 1500 ms**, coordinado en Redis. Toda llamada nueva al
  portal pasa por `MagisSession.throttle()`.
- Commits en español, sin pie de coautoría, identidad `lordmacu`. `git add` con rutas explícitas.
- El adaptador sigue sirviendo **solo `movie` y `series`**. TV en vivo fuera de alcance.

---

## File Structure

```
~/arkiv-api/src/arkiv_api/adapters/magis/
├── crypto.py        # AES-GCM + HKDF sobre REF_SIGNING_KEY   (nuevo)
├── session.py       # + modo, credenciales, expires_in       (modificar)
└── adapter.py       # sin cambios (ya usa session.client/throttle/invalidate)

~/arkiv-api/src/arkiv_api/router/
└── magis.py         # /v1/magis/session, /credentials        (nuevo)

~/arkiv-api/src/arkiv_api/
├── app.py           # cablear el router y pasar settings a la sesion  (modificar)
└── router/health.py # reportar modo y si hay credenciales             (modificar)

/Users/cristian/magia/
└── iptv_client.py   # camino previo: pedir el token al gateway  (modificar)
```

**Responsabilidades.** `crypto.py` no sabe nada de magis: cifra y descifra bytes. `session.py` es
el único que habla con Redis y decide el modo. `router/magis.py` traduce HTTP ↔ sesión y no
contiene lógica de portal. `adapter.py` **no se toca**: ya consume `client()`, `throttle()` e
`invalidate()`, que siguen igual.

---

### Task 1: Cifrado de credenciales

Sin esto no se puede guardar nada. Es autocontenido y no toca la sesión.

**Files:**
- Create: `src/arkiv_api/adapters/magis/crypto.py`
- Test: `tests/test_magis_crypto.py`

**Interfaces:**
- Produces:
  - `class CryptoError(ValueError)`
  - `def sellar(texto: str, llave_maestra: str) -> str` — devuelve un blob base64 urlsafe
  - `def abrir(blob: str, llave_maestra: str) -> str` — lanza `CryptoError` si no se puede

- [ ] **Step 1: Escribir el test que falla**

`tests/test_magis_crypto.py`:

```python
import pytest

from arkiv_api.adapters.magis.crypto import CryptoError, abrir, sellar

MAESTRA = "una-llave-maestra-de-pruebas-32b"


def test_ida_y_vuelta():
    assert abrir(sellar("secreto", MAESTRA), MAESTRA) == "secreto"


def test_el_blob_no_contiene_el_texto_en_claro():
    assert "secreto" not in sellar("secreto", MAESTRA)


def test_dos_sellados_del_mismo_texto_dan_blobs_distintos():
    # Nonce aleatorio: dos capturas del mismo valor no deben verse iguales.
    assert sellar("secreto", MAESTRA) != sellar("secreto", MAESTRA)


def test_otra_llave_no_abre():
    with pytest.raises(CryptoError):
        abrir(sellar("secreto", MAESTRA), "otra-llave-distinta-de-32-bytes!")


def test_un_blob_manipulado_no_abre():
    blob = sellar("secreto", MAESTRA)
    with pytest.raises(CryptoError):
        abrir(blob[:-4] + "AAAA", MAESTRA)


def test_basura_no_explota_da_cryptoerror():
    with pytest.raises(CryptoError):
        abrir("esto no es un blob", MAESTRA)


def test_texto_con_acentos_sobrevive():
    assert abrir(sellar("contraseña ñandú", MAESTRA), MAESTRA) == "contraseña ñandú"
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/test_magis_crypto.py -q`
Expected: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.adapters.magis.crypto'`.

- [ ] **Step 3: Implementar `crypto.py`**

```python
from __future__ import annotations

import base64

from Crypto.Cipher import AES
from Crypto.Hash import HKDF, SHA256
from Crypto.Random import get_random_bytes

_SAL = b"arkiv-api/magis/credenciales/v1"
_LARGO_NONCE = 12


class CryptoError(ValueError):
    """El blob no se puede abrir: llave distinta, manipulado o corrupto."""


def _llave(maestra: str) -> bytes:
    """Deriva una llave AES de 256 bits desde REF_SIGNING_KEY.

    Se deriva en vez de usarla directo para que el material de firma de los refs
    y el de cifrado de credenciales no sean el mismo secreto.
    """
    return HKDF(maestra.encode(), 32, _SAL, SHA256)


def sellar(texto: str, llave_maestra: str) -> str:
    nonce = get_random_bytes(_LARGO_NONCE)
    cifrador = AES.new(_llave(llave_maestra), AES.MODE_GCM, nonce=nonce)
    cuerpo, tag = cifrador.encrypt_and_digest(texto.encode("utf-8"))
    return base64.urlsafe_b64encode(nonce + tag + cuerpo).decode().rstrip("=")


def abrir(blob: str, llave_maestra: str) -> str:
    try:
        crudo = base64.urlsafe_b64decode(blob + "=" * (-len(blob) % 4))
        nonce, tag, cuerpo = crudo[:_LARGO_NONCE], crudo[_LARGO_NONCE:_LARGO_NONCE + 16], crudo[_LARGO_NONCE + 16:]
        cifrador = AES.new(_llave(llave_maestra), AES.MODE_GCM, nonce=nonce)
        return cifrador.decrypt_and_verify(cuerpo, tag).decode("utf-8")
    except Exception as e:
        # GCM valida el tag: una llave distinta o un byte cambiado caen aca.
        raise CryptoError("no se pudo abrir el blob de credenciales") from e
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/test_magis_crypto.py -q`
Expected: 7 passed.

- [ ] **Step 5: Commit**

```bash
cd ~/arkiv-api
git add src/arkiv_api/adapters/magis/crypto.py tests/test_magis_crypto.py
git commit -m "feat(magis): cifrado AES-GCM de credenciales derivado de REF_SIGNING_KEY"
```

---

### Task 2: `MagisSession` con modo anónimo y por cuenta

**Files:**
- Modify: `src/arkiv_api/adapters/magis/session.py`
- Test: `tests/test_magis_session.py` (extender)

**Interfaces:**
- Consumes: `sellar`, `abrir`, `CryptoError`
- Produces, sobre la clase existente:
  - `MagisSession(redis, bucket, factory, llave_maestra: str)` — parámetro nuevo al final
  - `async def modo() -> str` — `"anonimo"` o `"cuenta"`
  - `async def tiene_credenciales() -> bool`
  - `async def guardar_credenciales(usuario: str, clave: str) -> str` — hace login; devuelve el
    modo resultante; lanza `LoginRechazado` si el portal rechaza
  - `async def borrar_credenciales() -> None`
  - `async def expires_in() -> int` — segundos que le quedan a la sesión (0 si no hay)
  - `class LoginRechazado(RuntimeError)`
  - Se conservan sin cambios de firma: `client()`, `throttle()`, `invalidate()`

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `tests/test_magis_session.py`. El `ClienteFalso` que ya existe cuenta activaciones;
se le suma el login:

```python
from arkiv_api.adapters.magis.session import LoginRechazado

MAESTRA = "llave-maestra-de-pruebas-32-bytes"


class ClienteConLogin(ClienteFalso):
    """Cliente que además soporta login por cuenta."""

    login_ok = True
    logins = 0

    def login(self, usuario, clave):
        ClienteConLogin.logins += 1
        if not ClienteConLogin.login_ok:
            return {"_error": "aaa100022", "_msg": "用户名密码校验失败"}
        self.user_id = "uid-cuenta"
        self.user_token = "tok-cuenta"
        return {"userId": self.user_id, "userToken": self.user_token}


@pytest.fixture(autouse=True)
def _reset_login():
    ClienteConLogin.login_ok = True
    ClienteConLogin.logins = 0


def _sesion_con_login():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    return MagisSession(redis, TokenBucket(redis, "magis", 1), ClienteConLogin, MAESTRA)


async def test_sin_credenciales_el_modo_es_anonimo():
    s = _sesion_con_login()
    assert await s.modo() == "anonimo"
    assert await s.tiene_credenciales() is False


async def test_guardar_credenciales_pasa_a_modo_cuenta():
    s = _sesion_con_login()
    assert await s.guardar_credenciales("u@x.com", "clave") == "cuenta"
    assert await s.modo() == "cuenta"
    assert await s.tiene_credenciales() is True


async def test_un_login_rechazado_no_cambia_el_modo_ni_pisa_la_sesion():
    s = _sesion_con_login()
    await s.client()                      # sesion anonima viva
    ClienteConLogin.login_ok = False
    with pytest.raises(LoginRechazado, match="用户名密码校验失败"):
        await s.guardar_credenciales("u@x.com", "mala")
    assert await s.modo() == "anonimo"
    assert await s.tiene_credenciales() is False


async def test_la_clave_no_queda_en_claro_en_redis():
    s = _sesion_con_login()
    await s.guardar_credenciales("u@x.com", "mi-clave-secreta")
    todo = ""
    for k in await s._r.keys("*"):
        todo += str(await s._r.get(k))
    assert "mi-clave-secreta" not in todo


async def test_en_modo_cuenta_se_reloguea_al_perder_la_sesion():
    s = _sesion_con_login()
    await s.guardar_credenciales("u@x.com", "clave")
    await s.invalidate()
    c = await s.client()
    assert c.user_token == "tok-cuenta"
    assert ClienteConLogin.activaciones == 0   # nunca cayo al anonimo


async def test_si_las_credenciales_dejan_de_descifrarse_cae_a_anonimo():
    """Si cambia REF_SIGNING_KEY, lo guardado no abre. No debe romper el servicio."""
    s = _sesion_con_login()
    await s.guardar_credenciales("u@x.com", "clave")
    s._maestra = "otra-llave-maestra-completamente"
    await s.invalidate()
    c = await s.client()
    assert c.user_token == "tok-nuevo"          # activacion anonima
    assert await s.modo() == "anonimo"


async def test_borrar_credenciales_vuelve_a_anonimo():
    s = _sesion_con_login()
    await s.guardar_credenciales("u@x.com", "clave")
    await s.borrar_credenciales()
    assert await s.modo() == "anonimo"
    assert await s.tiene_credenciales() is False


async def test_expires_in_refleja_el_ttl():
    s = _sesion_con_login()
    await s.client()
    assert 47 * 3600 < await s.expires_in() <= 48 * 3600


async def test_expires_in_es_cero_sin_sesion():
    assert await _sesion_con_login().expires_in() == 0
```

- [ ] **Step 2: Correr y verificar que fallan**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/test_magis_session.py -q`
Expected: FAIL con `ImportError: cannot import name 'LoginRechazado'`.

- [ ] **Step 3: Reescribir `session.py`**

```python
from __future__ import annotations

import asyncio
import json

from .crypto import CryptoError, abrir, sellar

_TTL_S = 48 * 3600  # el portal da ~48 h de vida al token; se refresca al vencer.


class LoginRechazado(RuntimeError):
    """El portal rechazo el usuario/clave. La sesion vigente queda intacta."""


class MagisSession:
    """La UNICA sesion del portal, compartida por todos los dispositivos.

    El `sn` del dispositivo esta en una lista blanca del portal y no se puede
    generar, asi que existe una sola identidad posible: si dos clientes activan
    por su cuenta se expulsan en ciclo. Por eso el gateway es el unico que activa
    y los demas le piden el token (`GET /v1/magis/session`).

    Dos modos: `anonimo` (activate por sn) y `cuenta` (login por email). Prefiere
    cuenta si hay credenciales y cae a anonimo si el login falla — nunca queda sin
    servicio.
    """

    _KEY = "magis:session"
    _KEY_CRED = "magis:credenciales"

    def __init__(self, redis, bucket, factory, llave_maestra: str) -> None:
        self._r = redis
        self._bucket = bucket
        self._factory = factory
        self._maestra = llave_maestra
        self._lock = asyncio.Lock()

    # --- estado ------------------------------------------------------------

    async def tiene_credenciales(self) -> bool:
        return bool(await self._r.exists(self._KEY_CRED))

    async def modo(self) -> str:
        return "cuenta" if await self.tiene_credenciales() else "anonimo"

    async def expires_in(self) -> int:
        ttl = await self._r.ttl(self._KEY)
        return ttl if ttl and ttl > 0 else 0

    async def throttle(self) -> None:
        """Reserva turno en el ritmo global antes de tocar el portal."""
        await self._bucket.acquire()

    # --- credenciales ------------------------------------------------------

    async def _credenciales(self) -> tuple[str, str] | None:
        crudo = await self._r.get(self._KEY_CRED)
        if not crudo:
            return None
        try:
            datos = json.loads(abrir(crudo, self._maestra))
        except CryptoError:
            # Cambio REF_SIGNING_KEY: lo guardado ya no abre. Se descarta y se
            # sigue en anonimo en vez de dejar el servicio muerto.
            await self._r.delete(self._KEY_CRED)
            return None
        return datos["u"], datos["p"]

    async def guardar_credenciales(self, usuario: str, clave: str) -> str:
        """Verifica contra el portal ANTES de guardar. Si el portal rechaza, no se
        guarda nada y la sesion que estaba funcionando queda intacta."""
        async with self._lock:
            cliente = await self._loguear(usuario, clave)
            await self._r.set(
                self._KEY_CRED, sellar(json.dumps({"u": usuario, "p": clave}), self._maestra)
            )
            await self._persistir(cliente)
            return "cuenta"

    async def borrar_credenciales(self) -> None:
        async with self._lock:
            await self._r.delete(self._KEY_CRED, self._KEY)

    # --- sesion ------------------------------------------------------------

    async def _loguear(self, usuario: str, clave: str):
        await self.throttle()
        cliente = await asyncio.to_thread(self._factory)  # sin token: no activa aun
        r = await asyncio.to_thread(cliente.login, usuario, clave)
        if not (isinstance(r, dict) and r.get("userToken")):
            motivo = (r or {}).get("_msg") or (r or {}).get("_error") or "login rechazado"
            raise LoginRechazado(str(motivo))
        return cliente

    async def _persistir(self, cliente) -> None:
        await self._r.set(
            self._KEY,
            json.dumps({"user_id": cliente.user_id, "user_token": cliente.user_token}),
            ex=_TTL_S,
        )

    async def client(self):
        async with self._lock:
            crudo = await self._r.get(self._KEY)
            if crudo:
                datos = json.loads(crudo)
                return self._factory(user_id=datos["user_id"], user_token=datos["user_token"])

            cred = await self._credenciales()
            if cred is not None:
                try:
                    cliente = await self._loguear(*cred)
                    await self._persistir(cliente)
                    return cliente
                except LoginRechazado:
                    # Credencial vencida: se descarta y se sigue en anonimo.
                    await self._r.delete(self._KEY_CRED)

            await self.throttle()
            # El constructor se activa solo cuando no recibe token: es una llamada
            # al portal, por eso va detras del bucket y en un hilo aparte.
            cliente = await asyncio.to_thread(self._factory)
            await self._persistir(cliente)
            return cliente

    async def invalidate(self) -> None:
        """Tira la sesion guardada. Se llama cuando el portal la expulsa."""
        await self._r.delete(self._KEY)
```

> Nota: `guardar_credenciales` y `borrar_credenciales` toman el lock, y `_loguear`
> no lo toma — no re-entra. `client()` toma el lock por su cuenta.

- [ ] **Step 4: Actualizar la construcción existente en `app.py`**

`MagisSession` ahora recibe un cuarto argumento:

```python
        sesion = MagisSession(
            redis,
            TokenBucket(redis, "magis", _MAGIS_INTERVALO_MS),
            _magis_factory(),
            settings.ref_signing_key,
        )
```

- [ ] **Step 5: Correr y verificar que pasan**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/ -q`
Expected: todo verde (los tests viejos de sesión siguen pasando: `client`, `throttle` e
`invalidate` no cambiaron de firma).

- [ ] **Step 6: Commit**

```bash
cd ~/arkiv-api
git add src/arkiv_api/adapters/magis/session.py src/arkiv_api/app.py tests/test_magis_session.py
git commit -m "feat(magis): sesion con modo anonimo o por cuenta y credenciales cifradas"
```

---

### Task 3: Rutas `/v1/magis/session` y `/v1/magis/credentials`

**Files:**
- Create: `src/arkiv_api/router/magis.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_magis.py`

**Interfaces:**
- Consumes: `MagisSession`, `LoginRechazado`, `require_key`
- Produces:
  - `GET /v1/magis/session` → `{user_id, user_token, mode, expires_in}`
  - `POST /v1/magis/credentials` con `{username, password}` → `{mode, user_id}`
  - `DELETE /v1/magis/credentials` → `{mode: "anonimo"}`
  - `app.state.magis_session` (la instancia, o `None` si magis no está configurado)

- [ ] **Step 1: Escribir el test que falla**

`tests/test_router_magis.py`:

```python
import fakeredis.aioredis
import pytest
from fastapi.testclient import TestClient

from arkiv_api.adapters.magis.session import MagisSession
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from arkiv_api.store.ratelimit import TokenBucket
from tests.test_magis_session import ClienteConLogin

CAB = {"X-Arkiv-Key": "k"}
MAESTRA = "x" * 32


def _client(con_magis: bool = True) -> TestClient:
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key=MAESTRA).validated())
    if con_magis:
        redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
        app.state.magis_session = MagisSession(
            redis, TokenBucket(redis, "magis", 1), ClienteConLogin, MAESTRA
        )
    else:
        app.state.magis_session = None
    return TestClient(app)


@pytest.fixture(autouse=True)
def _reset():
    ClienteConLogin.login_ok = True
    ClienteConLogin.activaciones = 0


def test_session_sin_llave_da_401():
    assert _client().get("/v1/magis/session").status_code == 401


def test_session_devuelve_token_modo_y_vencimiento():
    body = _client().get("/v1/magis/session", headers=CAB).json()
    assert body["user_token"]
    assert body["mode"] == "anonimo"
    assert body["expires_in"] > 0


def test_dos_llamadas_a_session_no_activan_dos_veces():
    """Es el punto del endpoint: que nadie mas active y se roben el token."""
    cli = _client()
    cli.get("/v1/magis/session", headers=CAB)
    cli.get("/v1/magis/session", headers=CAB)
    assert ClienteConLogin.activaciones == 1


def test_session_nunca_devuelve_la_contrasena():
    cli = _client()
    cli.post("/v1/magis/credentials",
             json={"username": "u@x.com", "password": "mi-clave"}, headers=CAB)
    assert "mi-clave" not in cli.get("/v1/magis/session", headers=CAB).text


def test_credentials_valida_pasa_a_modo_cuenta():
    body = _client().post("/v1/magis/credentials",
                          json={"username": "u@x.com", "password": "c"}, headers=CAB).json()
    assert body["mode"] == "cuenta"
    assert body["user_id"] == "uid-cuenta"


def test_credentials_rechazada_da_422_y_no_pisa_la_sesion():
    cli = _client()
    cli.get("/v1/magis/session", headers=CAB)          # sesion anonima viva
    ClienteConLogin.login_ok = False
    r = cli.post("/v1/magis/credentials",
                 json={"username": "u@x.com", "password": "mala"}, headers=CAB)
    assert r.status_code == 422
    assert cli.get("/v1/magis/session", headers=CAB).json()["mode"] == "anonimo"


def test_delete_vuelve_a_anonimo():
    cli = _client()
    cli.post("/v1/magis/credentials",
             json={"username": "u@x.com", "password": "c"}, headers=CAB)
    assert cli.delete("/v1/magis/credentials", headers=CAB).json()["mode"] == "anonimo"


def test_sin_magis_configurado_da_503():
    assert _client(con_magis=False).get("/v1/magis/session", headers=CAB).status_code == 503
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/test_router_magis.py -q`
Expected: FAIL con `404` (las rutas no existen).

- [ ] **Step 3: Implementar `router/magis.py`**

```python
from __future__ import annotations

import json

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from ..adapters.magis.session import LoginRechazado
from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])


class CredencialesIn(BaseModel):
    username: str
    password: str


def _sesion(request: Request):
    s = getattr(request.app.state, "magis_session", None)
    if s is None:
        raise HTTPException(status_code=503, detail="magis no esta configurado")
    return s


@router.get("/magis/session")
async def session(request: Request) -> dict:
    """Devuelve la sesion que el gateway YA tiene, sin activar de nuevo.

    Es lo que evita el ping-pong: cualquier otro cliente (el CLI de magia) pide el
    token aca en vez de activar por su cuenta y expulsar al gateway.
    """
    s = _sesion(request)
    cliente = await s.client()
    return {
        "user_id": cliente.user_id,
        "user_token": cliente.user_token,
        "mode": await s.modo(),
        "expires_in": await s.expires_in(),
    }


@router.post("/magis/credentials")
async def guardar(body: CredencialesIn, request: Request) -> dict:
    s = _sesion(request)
    try:
        modo = await s.guardar_credenciales(body.username, body.password)
    except LoginRechazado as e:
        # 422: la peticion estaba bien formada, el portal la rechazo.
        raise HTTPException(status_code=422, detail=str(e)) from e
    cliente = await s.client()
    return {"mode": modo, "user_id": cliente.user_id}


@router.delete("/magis/credentials")
async def borrar(request: Request) -> dict:
    s = _sesion(request)
    await s.borrar_credenciales()
    return {"mode": await s.modo()}
```

- [ ] **Step 4: Exponer la sesión y cablear el router en `app.py`**

En `build_registry`, guardar la instancia además de registrar el adaptador:

```python
    sesion_magis = None
    if settings.credential_namespaces["magis"]:
        sesion_magis = MagisSession(
            redis,
            TokenBucket(redis, "magis", _MAGIS_INTERVALO_MS),
            _magis_factory(),
            settings.ref_signing_key,
        )
        reg.register(MagisAdapter(sesion_magis, Cache(redis), settings.ref_signing_key))

    breakers = {a.name: Breaker(redis, a.name) for a in reg.all()}
    return reg, breakers, sesion_magis
```

Actualizar los dos llamadores (`lifespan` y los tests de `test_app_wiring.py`, que hoy
desempaquetan dos valores) para que reciban tres:

```python
        app.state.registry, app.state.breakers, app.state.magis_session = build_registry(
            resueltos, app.state.http, app.state.redis
        )
```

Y en `create_app`, el default: `app.state.magis_session = None`.
Agregar `magis` a la tupla de routers.

- [ ] **Step 5: Correr y verificar que pasan**

```bash
cd ~/arkiv-api && .venv/bin/pytest tests/ -q && .venv/bin/ruff check src tests
```

Expected: todo verde. Ojo con `test_app_wiring.py`: sus cuatro tests desempaquetan
`build_registry` y hay que ajustarlos a la tupla de tres.

- [ ] **Step 6: Commit**

```bash
cd ~/arkiv-api
git add src/arkiv_api/router/magis.py src/arkiv_api/app.py \
        tests/test_router_magis.py tests/test_app_wiring.py
git commit -m "feat(magis): rutas de sesion y credenciales bajo /v1/magis"
```

---

### Task 4: Reportar el modo en `/v1/health`

Para poder ver desde afuera en qué modo está sin exponer nada sensible.

**Files:**
- Modify: `src/arkiv_api/router/health.py`
- Test: `tests/test_health.py` (extender)

**Interfaces:**
- Consumes: `app.state.magis_session`
- Produces: en la respuesta de `/v1/health`, la clave `magis` con
  `{"mode": str, "has_credentials": bool, "expires_in": int}`, o `null` si no está configurado

- [ ] **Step 1: Escribir el test que falla**

Agregar a `tests/test_health.py`:

```python
import fakeredis.aioredis

from arkiv_api.adapters.magis.session import MagisSession
from arkiv_api.store.ratelimit import TokenBucket
from tests.test_magis_session import ClienteConLogin


def _client_con_magis() -> TestClient:
    s = Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated()
    app = create_app(s)
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    app.state.magis_session = MagisSession(
        redis, TokenBucket(redis, "magis", 1), ClienteConLogin, "x" * 32
    )
    return TestClient(app)


def test_health_reporta_el_modo_de_magis():
    magis = _client_con_magis().get("/v1/health").json()["magis"]
    assert magis["mode"] == "anonimo"
    assert magis["has_credentials"] is False


def test_health_sin_magis_configurado_lo_reporta_como_null():
    assert _client().get("/v1/health").json()["magis"] is None


def test_health_nunca_expone_credenciales():
    cli = _client_con_magis()
    cli.post("/v1/magis/credentials",
             json={"username": "u@x.com", "password": "mi-clave"},
             headers={"X-Arkiv-Key": "k"})
    cuerpo = cli.get("/v1/health").text
    assert "mi-clave" not in cuerpo
    assert cli.get("/v1/health").json()["magis"]["has_credentials"] is True
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/test_health.py -q`
Expected: FAIL con `KeyError: 'magis'`.

- [ ] **Step 3: Implementar en `health.py`**

```python
from fastapi import APIRouter, Request

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict:
    settings = request.app.state.settings
    sesion = getattr(request.app.state, "magis_session", None)

    magis = None
    if sesion is not None:
        # Booleanos y numeros: nunca el token ni la clave.
        magis = {
            "mode": await sesion.modo(),
            "has_credentials": await sesion.tiene_credenciales(),
            "expires_in": await sesion.expires_in(),
        }

    return {
        "status": "ok",
        "credentials": settings.credential_namespaces,
        "magis": magis,
    }
```

- [ ] **Step 4: Correr y verificar que pasan**

Run: `cd ~/arkiv-api && .venv/bin/pytest tests/ -q`
Expected: todo verde.

- [ ] **Step 5: Commit**

```bash
cd ~/arkiv-api
git add src/arkiv_api/router/health.py tests/test_health.py
git commit -m "feat(magis): reportar modo y estado de credenciales en /v1/health"
```

---

### Task 5: Desplegar y verificar contra el portal real

**Files:** ninguno (despliegue y verificación)

- [ ] **Step 1: Sincronizar y reconstruir**

```bash
cd ~/arkiv-api
rsync -a --delete --exclude '.venv' --exclude '.git' --exclude '__pycache__' \
  --exclude '.pytest_cache' --exclude '.ruff_cache' --exclude '*.egg-info' --exclude '.env' \
  ./ blog:~/arkiv-api/
ssh blog 'cd ~/arkiv-api && sudo -n docker compose up -d --build 2>&1 | tail -2'
```

> `docker compose` en `blog` necesita `sudo -n`: el usuario no está en el grupo docker.

- [ ] **Step 2: Verificar el modo y que la sesión no se re-active**

```bash
K=$(ssh blog 'grep ^ARKIV_API_KEYS= ~/arkiv-api/.env | cut -d= -f2')
curl -s -H "X-Arkiv-Key: $K" https://api.comparadorinternet.co/v1/health
curl -s -H "X-Arkiv-Key: $K" https://api.comparadorinternet.co/v1/magis/session
```

Expected: `"magis":{"mode":"anonimo","has_credentials":false,"expires_in":<>0}` y una respuesta
con `user_token`. Llamar dos veces seguidas a `/v1/magis/session` tiene que devolver **el mismo
`user_token`** — si cambia, se está re-activando y el diseño no cumple su objetivo.

- [ ] **Step 3: Verificar que la búsqueda sigue andando**

```bash
curl -s -H "X-Arkiv-Key: $K" \
  "https://api.comparadorinternet.co/v1/search?q=dune&sources=magis&budget_ms=20000&format=json"
```

Expected: `"errors":[]` y resultados con títulos legibles.

- [ ] **Step 4: Verificar que una credencial inválida no rompe nada**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "X-Arkiv-Key: $K" -H "Content-Type: application/json" \
  -d '{"username":"noexiste@x.com","password":"mala"}' \
  https://api.comparadorinternet.co/v1/magis/credentials
curl -s -H "X-Arkiv-Key: $K" \
  "https://api.comparadorinternet.co/v1/search?q=dune&sources=magis&format=json"
```

Expected: `422`, y la búsqueda siguiente **sigue devolviendo resultados** en modo anónimo.

- [ ] **Step 5: Commit del estado verificado**

```bash
cd ~/arkiv-api && git push
```

---

### Task 6: El CLI de magia pide el token en vez de activar

Repo distinto: `/Users/cristian/magia`.

**Files:**
- Modify: `/Users/cristian/magia/iptv_client.py`
- Modify: `/Users/cristian/magia/.env.example`
- Test: `/Users/cristian/magia/test_gateway_session.py`

**Interfaces:**
- Consumes: `GET /v1/magis/session` del gateway
- Produces: en `IPTVClient`, `def _sesion_del_gateway(self) -> bool` — devuelve `True` si tomó
  la sesión del gateway

- [ ] **Step 1: Escribir el test que falla**

`/Users/cristian/magia/test_gateway_session.py`:

```python
"""El CLI debe pedirle el token al gateway antes de activar por su cuenta.

Sin esto, correr el CLI le roba la sesion al gateway (el portal es de dispositivo
unico) y los dos se la quitan en ciclo.
"""
import json
import os
import threading
import http.server

import iptv_client


class _Handler(http.server.BaseHTTPRequestHandler):
    respuesta = {"user_id": "uid-gw", "user_token": "tok-gw", "mode": "anonimo", "expires_in": 100}
    codigo = 200
    llamadas = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        _Handler.llamadas.append((self.path, self.headers.get("X-Arkiv-Key")))
        self.send_response(_Handler.codigo)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(_Handler.respuesta).encode())


def _servidor():
    s = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s


def test_toma_la_sesion_del_gateway_y_no_activa():
    s = _servidor()
    _Handler.llamadas.clear()
    _Handler.codigo = 200
    os.environ["ARKIV_API"] = f"http://127.0.0.1:{s.server_address[1]}"
    os.environ["ARKIV_API_KEY"] = "llave"

    c = iptv_client.IPTVClient(auto_activate=True)
    assert c.user_token == "tok-gw"
    assert _Handler.llamadas[0][0] == "/v1/magis/session"
    assert _Handler.llamadas[0][1] == "llave"
    s.shutdown()


def test_si_el_gateway_no_responde_activa_como_antes(monkeypatch):
    s = _servidor()
    _Handler.codigo = 503
    os.environ["ARKIV_API"] = f"http://127.0.0.1:{s.server_address[1]}"
    os.environ["ARKIV_API_KEY"] = "llave"

    activo = {"si": False}

    def activar_falso(self):
        activo["si"] = True
        self.user_id, self.user_token = "uid-local", "tok-local"
        return {"userId": self.user_id, "userToken": self.user_token}

    monkeypatch.setattr(iptv_client.IPTVClient, "activate", activar_falso)
    c = iptv_client.IPTVClient(auto_activate=True)
    assert activo["si"] is True
    assert c.user_token == "tok-local"
    s.shutdown()


def test_sin_ARKIV_API_no_llama_a_nadie(monkeypatch):
    os.environ.pop("ARKIV_API", None)
    _Handler.llamadas.clear()
    monkeypatch.setattr(
        iptv_client.IPTVClient, "activate",
        lambda self: setattr(self, "user_token", "tok-local") or {},
    )
    iptv_client.IPTVClient(auto_activate=True)
    assert _Handler.llamadas == []
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd /Users/cristian/magia && python3 -m pytest test_gateway_session.py -q`
Expected: FAIL — el CLI activa siempre por su cuenta.

- [ ] **Step 3: Implementar en `iptv_client.py`**

Dentro de `IPTVClient`, agregar el método y cambiar el final de `__init__`:

```python
    def _sesion_del_gateway(self):
        """Toma la sesion que ya tiene arkiv-api en vez de activar una nueva.

        El portal es de dispositivo unico: si el CLI activa por su cuenta le roba
        el token al gateway, el gateway re-activa y se lo roba de vuelta (ping-pong).
        Pidiendola prestada hay un solo activador.

        Devuelve True si la tomo. Ante cualquier problema devuelve False y el
        llamador activa como siempre — el CLI no queda atado a la red del NUC.
        """
        base = os.environ.get("ARKIV_API", "").rstrip("/")
        llave = os.environ.get("ARKIV_API_KEY", "")
        if not base or not llave:
            return False
        try:
            r = self.s.get(f"{base}/v1/magis/session",
                           headers={"X-Arkiv-Key": llave}, timeout=10)
            if r.status_code != 200:
                return False
            datos = r.json()
            if not datos.get("userToken" if "userToken" in datos else "user_token"):
                return False
            self.user_id = datos["user_id"]
            self.user_token = datos["user_token"]
            return True
        except Exception:
            return False
```

Y reemplazar la última línea de `__init__`:

```python
        if auto_activate and not self.user_token:
            if not self._sesion_del_gateway():
                self.activate()
```

- [ ] **Step 4: Documentar las variables en `.env.example`**

```
# Opcional: si estan, el CLI pide la sesion al gateway en vez de activar por su
# cuenta. El portal es de dispositivo unico, asi que dos activadores se expulsan.
ARKIV_API=https://api.comparadorinternet.co
ARKIV_API_KEY=
```

- [ ] **Step 5: Correr y verificar que pasan**

Run: `cd /Users/cristian/magia && python3 -m pytest test_gateway_session.py -q`
Expected: 3 passed.

- [ ] **Step 6: Verificar que ya no hay ping-pong**

```bash
K=$(ssh blog 'grep ^ARKIV_API_KEYS= ~/arkiv-api/.env | cut -d= -f2')
ANTES=$(curl -s -H "X-Arkiv-Key: $K" https://api.comparadorinternet.co/v1/magis/session | python3 -c 'import json,sys;print(json.load(sys.stdin)["user_token"])')
cd /Users/cristian/magia
ARKIV_API=https://api.comparadorinternet.co ARKIV_API_KEY=$K python3 -c 'import iptv_client; print("CLI token:", iptv_client.IPTVClient().user_token[:12])'
DESPUES=$(curl -s -H "X-Arkiv-Key: $K" https://api.comparadorinternet.co/v1/magis/session | python3 -c 'import json,sys;print(json.load(sys.stdin)["user_token"])')
[ "$ANTES" = "$DESPUES" ] && echo "OK: el CLI no robo la sesion" || echo "FALLA: el token cambio"
```

Expected: `OK: el CLI no robo la sesion`.

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/magia
git add iptv_client.py .env.example test_gateway_session.py
git commit -m "feat: tomar la sesion del gateway en vez de activar por cuenta propia"
```

---

## Self-Review

**Cobertura del spec.** Cifrado de credenciales → Task 1. Los dos modos y la caída a anónimo →
Task 2. Las tres rutas (`session`, `POST`/`DELETE credentials`) → Task 3. Reporte del modo sin
exponer secretos → Task 4. Verificación contra el portal real → Task 5. Cambio en el CLI de
magia → Task 6. Las cuatro reglas de credenciales del spec están cubiertas: no devolver la clave
(Tasks 3 y 4 lo testean), booleano en health (Task 4), no loguearla (no se agrega ningún log con
la clave), y descarte si cambia `REF_SIGNING_KEY` (Task 2,
`test_si_las_credenciales_dejan_de_descifrarse_cae_a_anonimo`).

**Consistencia de tipos.** `sellar`/`abrir`/`CryptoError` (Task 1) son lo que consume
`session.py` (Task 2). `modo()`, `tiene_credenciales()`, `expires_in()`,
`guardar_credenciales()`, `borrar_credenciales()` y `LoginRechazado` (Task 2) son exactamente lo
que usan `router/magis.py` (Task 3) y `router/health.py` (Task 4). `client()`, `throttle()` e
`invalidate()` **no cambian de firma**, por eso `adapter.py` no se toca. `app.state.magis_session`
lo produce Task 3 y lo consume Task 4.

**Cambio de firma con efecto en tests existentes:** `build_registry` pasa a devolver una tupla de
**tres** elementos (Task 3, Step 4); `test_app_wiring.py` desempaqueta dos y hay que ajustarlo —
está señalado en el Step 5 de esa tarea.

**Fuera de alcance, coherente con el spec:** sesiones por dispositivo (el portal valida el `sn`),
crear cuentas desde la app, TV en vivo, y la pantalla de Ajustes para cargar credenciales (es
parte de F5, `2026-08-09-arkiv-api-f5-app.md`).

**Riesgo conocido:** el camino de modo `cuenta` no se puede verificar end-to-end hasta que haya
credenciales válidas — las de `magia/.env` devuelven `用户名密码校验失败`. Los tests lo cubren
con un cliente falso, y la Task 5 verifica que **una credencial inválida no rompe nada**, que es
la garantía que sí se puede comprobar hoy.
