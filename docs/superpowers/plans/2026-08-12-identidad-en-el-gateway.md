# Identidad en el gateway — Plan de implementación (2 de 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el gateway sea la autoridad de identidad — valida sesión y licencia en cada pedido, y es el único que crea cuentas y engancha aparatos — para que un APK modificado no pueda saltarse ningún control.

**Architecture:** Un módulo `identidad/` nuevo con un cliente admin de PocketBase de proceso largo (token cacheado y refrescado), una dependencia FastAPI `require_sesion` con caché Redis de 60 s, y un router `/v1/cuenta` con los caminos que hoy la app hace sola contra PocketBase: registrarse, adoptar un aparato, listarlos y sacarlos. Una migracion cierra la creacion de cuentas del lado de PocketBase.

**Tech Stack:** Python 3.12, FastAPI, httpx, redis.asyncio, pytest + respx. PocketBase en `db.comparadorinternet.co`.

**Repo:** `arkiv-api` (todo el plan salvo la migracion de la Task 6, que va en `archive/docs/pocketbase/`).

**Spec:** [Login obligatorio y licencias](../specs/2026-08-12-licencias-y-login-obligatorio-design.md). Plan 1 (CLI de licencias) está terminado y desplegado.

## Por qué este plan va antes que el de la app

El spec ponía el conteo de aparatos en la app. Un límite que verifica el cliente no es un límite: quien repackage el APK se lo saltea, y ese es exactamente el atacante contra el que existe todo esto. Por eso la validación de licencia y el conteo de aparatos viven acá, y el plan de la app (3 de 3) se escribe después, contra el contrato que este plan fija.

## Global Constraints

- **Comentarios y docstrings en español sin tildes**, explicando el porqué, no el qué.
- **Commits en español sin tildes**, con `user.name = lordmacu` y `user.email = 10134930+lordmacu@users.noreply.github.com`. **NUNCA** `Co-Authored-By`.
- **Nunca `git add -A` ni `git add .`** — este working tree se comparte con otras sesiones.
- Tests con `uv run pytest -q`. La suite arranca en **452 verdes**: ninguna tarea puede bajar ese número.
- `uv run ruff check src/arkiv_api/identidad/` tiene que salir limpio. Los 10 errores preexistentes en `adapters/magis/` y `catalog/tmdb.py` no son de este plan: no tocarlos.
- **Un arreglo cuya reversión deja los tests en verde no está cubierto.** Antes de dar una tarea por hecha, mutá lo que agregaste y comprobá que algo se ponga rojo.
- **La llave vieja sigue viva todo este plan.** `require_key` no se borra acá: los aparatos que hoy están instalados dejarían de funcionar hasta que salga el plan 3. El corte limpio que pide el spec es la última tarea del plan 3.
- Nunca loguear tokens, contraseñas ni códigos de licencia completos.

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `src/arkiv_api/identidad/pocketbase.py` | Cliente admin de proceso largo: autentica, cachea el token, lo refresca al vencer, y expone get/list/create/patch/delete. |
| `src/arkiv_api/identidad/sesion.py` | `require_sesion`: token de persona → persona + licencia válida, con caché Redis. |
| `src/arkiv_api/identidad/cuentas.py` | Reglas de negocio: registrar con licencia, adoptar aparato, listar y sacar aparatos. Sin FastAPI adentro. |
| `src/arkiv_api/router/cuenta.py` | Los cuatro endpoints HTTP. Traduce excepciones de `cuentas.py` a códigos. |
| `src/arkiv_api/config.py` | Suma las tres variables `pocketbase_*`. |
| `archive/docs/pocketbase/*.js` | La migracion que cierra la creacion de `users`. |

`cuentas.py` no importa FastAPI a propósito: las reglas se prueban llamando funciones, sin levantar la app ni armar `Request`.

## El contrato que el plan 3 va a consumir

Todos bajo `/v1/cuenta`, con `Authorization`. En `registrar` es el token del aparato (colección `devices`); en los de aparatos, el de la persona (colección `users`).

**El aparato se identifica con su token, nunca con su id suelto — en `registrar` y tambien en `POST /aparatos`.** Un id en una cabecera lo puede escribir cualquiera: quien mandara el de otra persona quedaría registrado bajo el `accountId` ajeno y vería su biblioteca. El token lo valida PocketBase, y de ahí sale el id.

| Endpoint | Auth | Body | Respuestas |
|---|---|---|---|
| `POST /registrar` | `Authorization: <token de devices>` | `{email, password, licencia}` | `201 {userId, accountId}` · `400 licencia_invalida` · `409 email_en_uso` |
| `POST /aparatos` | token de persona | `{deviceToken}` | `200 {kind, usados, tope}` · `403 tope_alcanzado` · `404` |
| `GET /aparatos` | token de persona | — | `200 {aparatos: [{id, kind, nombre, ultimoUso}]}` |
| `DELETE /aparatos/{id}` | token de persona | — | `204` · `404` |

Errores con forma estable: `{"detail": {"codigo": "licencia_invalida", "mensaje": "..."}}`. El `codigo` es lo que la app ramifica; el `mensaje` es lo que muestra.

---

### Task 1: Cliente admin de PocketBase para proceso largo

**Files:**
- Create: `src/arkiv_api/identidad/__init__.py`, `src/arkiv_api/identidad/pocketbase.py`
- Modify: `src/arkiv_api/config.py`
- Test: `tests/test_identidad_pocketbase.py`

**Interfaces:**
- Consumes: `Settings` de `config.py`, un `httpx.AsyncClient` compartido (`app.state.http`).
- Produces: `AdminPocketBase(http, url, email, password)` con `async get(col, id) -> dict | None`, `async listar(col, filtro) -> list[dict]`, `async crear(col, campos) -> dict`, `async patch(col, id, campos) -> dict`, `async borrar(col, id) -> None`, y `PocketBaseError(status, cuerpo)`.

El CLI de licencias ya tiene un cliente admin, pero **no sirve acá y no hay que reusarlo**: aquel autentica una vez por corrida y muere; este vive semanas dentro del contenedor y tiene que refrescar el token cuando vence. Son ciclos de vida distintos, no duplicación.

- [ ] **Step 1: Sumar las variables a `Settings`**

En `src/arkiv_api/config.py`, junto a las otras:

```python
    pocketbase_url: str = ""
    pocketbase_admin_email: str = ""
    pocketbase_admin_password: str = ""
```

Y en `credential_namespaces`, dentro del dict:

```python
            "pocketbase": bool(self.pocketbase_url and self.pocketbase_admin_password),
```

- [ ] **Step 2: Escribir el test que falla**

```python
import httpx
import pytest
import respx

from arkiv_api.identidad.pocketbase import AdminPocketBase, PocketBaseError

BASE = "https://pb.test"


def _admin(http):
    return AdminPocketBase(http, BASE, "admin@test", "secreta")


@pytest.mark.asyncio
@respx.mock
async def test_autentica_una_sola_vez_para_dos_pedidos():
    auth = respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "T1"})
    )
    respx.get(f"{BASE}/api/collections/users/records/u1").mock(
        return_value=httpx.Response(200, json={"id": "u1"})
    )
    async with httpx.AsyncClient() as http:
        pb = _admin(http)
        await pb.get("users", "u1")
        await pb.get("users", "u1")
    assert auth.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_un_401_fuerza_reautenticar_y_reintenta_una_vez():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        side_effect=[
            httpx.Response(200, json={"token": "VIEJO"}),
            httpx.Response(200, json={"token": "NUEVO"}),
        ]
    )
    ruta = respx.get(f"{BASE}/api/collections/users/records/u1").mock(
        side_effect=[
            httpx.Response(401, json={"message": "expirado"}),
            httpx.Response(200, json={"id": "u1"}),
        ]
    )
    async with httpx.AsyncClient() as http:
        assert (await _admin(http).get("users", "u1"))["id"] == "u1"
    assert ruta.calls[1].request.headers["Authorization"] == "NUEVO"


@pytest.mark.asyncio
@respx.mock
async def test_un_404_devuelve_None_y_no_reintenta():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "T1"})
    )
    ruta = respx.get(f"{BASE}/api/collections/users/records/nada").mock(
        return_value=httpx.Response(404, json={})
    )
    async with httpx.AsyncClient() as http:
        assert await _admin(http).get("users", "nada") is None
    assert ruta.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_un_500_sale_como_PocketBaseError_con_el_status():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "T1"})
    )
    respx.get(f"{BASE}/api/collections/users/records/u1").mock(
        return_value=httpx.Response(500, json={"message": "boom"})
    )
    async with httpx.AsyncClient() as http:
        with pytest.raises(PocketBaseError) as e:
            await _admin(http).get("users", "u1")
    assert e.value.status == 500
```

- [ ] **Step 3: Correrlo y ver que falla**

Run: `uv run pytest tests/test_identidad_pocketbase.py -q`
Expected: FAIL con `ModuleNotFoundError: arkiv_api.identidad`

- [ ] **Step 4: Implementar**

`src/arkiv_api/identidad/__init__.py` vacío. En `pocketbase.py`:

```python
"""Cliente admin de PocketBase para el gateway.

A diferencia del que usa el CLI de licencias, este vive dentro de un proceso que corre semanas:
el token de superusuario se cachea y se renueva solo cuando PocketBase lo rechaza. Reautenticar
en cada pedido le pegaria a PocketBase el doble de veces, y ese PocketBase corre en un NUC
saturado.
"""

from __future__ import annotations

import asyncio
from typing import Any

import httpx


class PocketBaseError(Exception):
    """Respuesta que no se puede interpretar como exito ni como 'no existe'."""

    def __init__(self, status: int, cuerpo: Any) -> None:
        super().__init__(f"PocketBase respondio {status}")
        self.status = status
        self.cuerpo = cuerpo


class AdminPocketBase:
    def __init__(self, http: httpx.AsyncClient, url: str, email: str, password: str) -> None:
        self._http = http
        self._base = url.rstrip("/")
        self._email = email
        self._password = password
        self._token: str | None = None
        # Sin el lock, N pedidos concurrentes con el token vencido disparan N autenticaciones
        # simultaneas contra el NUC. Con el lock, la primera autentica y las otras esperan.
        self._lock = asyncio.Lock()

    async def _autenticar(self) -> str:
        async with self._lock:
            if self._token:
                return self._token
            r = await self._http.post(
                f"{self._base}/api/collections/_superusers/auth-with-password",
                json={"identity": self._email, "password": self._password},
            )
            if r.status_code != 200:
                raise PocketBaseError(r.status_code, r.text)
            self._token = r.json()["token"]
            return self._token

    async def _pedir(self, metodo: str, ruta: str, **kw) -> httpx.Response:
        token = self._token or await self._autenticar()
        url = f"{self._base}{ruta}"
        r = await self._http.request(metodo, url, headers={"Authorization": token}, **kw)
        if r.status_code == 401:
            # El token vencio. Se tira el cacheado y se reintenta UNA vez: si el segundo
            # tambien da 401 el problema son las credenciales, y reintentar en loop solo
            # castigaria a PocketBase.
            async with self._lock:
                self._token = None
            token = await self._autenticar()
            r = await self._http.request(metodo, url, headers={"Authorization": token}, **kw)
        return r

    async def get(self, col: str, id_: str) -> dict | None:
        r = await self._pedir("GET", f"/api/collections/{col}/records/{id_}")
        if r.status_code == 404:
            return None
        if r.status_code != 200:
            raise PocketBaseError(r.status_code, r.text)
        return r.json()

    async def listar(self, col: str, filtro: str, per_page: int = 200) -> list[dict]:
        r = await self._pedir(
            "GET",
            f"/api/collections/{col}/records",
            params={"filter": filtro, "perPage": per_page, "sort": "id"},
        )
        if r.status_code != 200:
            raise PocketBaseError(r.status_code, r.text)
        return r.json().get("items", [])

    async def crear(self, col: str, campos: dict) -> dict:
        r = await self._pedir("POST", f"/api/collections/{col}/records", json=campos)
        if r.status_code not in (200, 201):
            raise PocketBaseError(r.status_code, r.text)
        return r.json()

    async def patch(self, col: str, id_: str, campos: dict) -> dict:
        r = await self._pedir("PATCH", f"/api/collections/{col}/records/{id_}", json=campos)
        if r.status_code != 200:
            raise PocketBaseError(r.status_code, r.text)
        return r.json()

    async def borrar(self, col: str, id_: str) -> None:
        r = await self._pedir("DELETE", f"/api/collections/{col}/records/{id_}")
        if r.status_code not in (200, 204, 404):
            raise PocketBaseError(r.status_code, r.text)
```

- [ ] **Step 5: Correr los tests**

Run: `uv run pytest tests/test_identidad_pocketbase.py -q` → PASS (4)
Run: `uv run pytest -q` → 456 passed

- [ ] **Step 6: Mutar para comprobar que los tests muerden**

Sacá el `self._token = None` del bloque de 401 y corré: `test_un_401_fuerza_reautenticar_y_reintenta_una_vez` tiene que fallar. Restaurá.

- [ ] **Step 7: Commit**

```bash
git add src/arkiv_api/identidad/__init__.py src/arkiv_api/identidad/pocketbase.py src/arkiv_api/config.py tests/test_identidad_pocketbase.py
git commit -m "feat(identidad): cliente admin de PocketBase con token que se renueva solo"
```

---

### Task 2: `require_sesion` — la sesión y la licencia, con caché

**Files:**
- Create: `src/arkiv_api/identidad/sesion.py`
- Test: `tests/test_identidad_sesion.py`

**Interfaces:**
- Consumes: `AdminPocketBase` (Task 1), `app.state.redis`.
- Produces: `Persona(user_id, account_id, email, licencia)` (dataclass), `async resolver_sesion(pb, redis, token) -> Persona` (lanza `SesionInvalida` o `LicenciaNoVigente`), y la dependencia FastAPI `require_sesion(request) -> Persona`.

- [ ] **Step 1: Escribir el test que falla**

```python
import fakeredis.aioredis
import httpx
import pytest
import respx

from arkiv_api.identidad.pocketbase import AdminPocketBase
from arkiv_api.identidad.sesion import (
    LicenciaNoVigente,
    SesionInvalida,
    resolver_sesion,
)

BASE = "https://pb.test"


def _redis():
    # fakeredis y no un doble a mano: ya es dependencia del repo (`test_router_magis.py`) y
    # respeta la semantica real, TTL incluido -- un dict casero afirmaria que guardamos algo,
    # no que expire.
    return fakeredis.aioredis.FakeRedis(decode_responses=True)


def _rutas(*, licencia_estado="activa", auth=200):
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "ADMIN"})
    )
    respx.post(f"{BASE}/api/collections/users/auth-refresh").mock(
        return_value=httpx.Response(
            auth,
            json={"record": {"id": "u1", "accountId": "A1", "email": "x@y.z", "licencia": "AAAA-BBBB-CCCC"}},
        )
    )
    respx.get(f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(
            200, json={"items": [{"id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": licencia_estado}]}
        )
    )


@pytest.mark.asyncio
@respx.mock
async def test_token_valido_con_licencia_activa_devuelve_la_persona():
    _rutas()
    r = _redis()
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        p = await resolver_sesion(pb, r, "TOKEN")
    assert (p.user_id, p.account_id, p.licencia) == ("u1", "A1", "AAAA-BBBB-CCCC")


@pytest.mark.asyncio
@respx.mock
async def test_licencia_revocada_no_deja_pasar():
    _rutas(licencia_estado="revocada")
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        with pytest.raises(LicenciaNoVigente):
            await resolver_sesion(pb, _redis(), "TOKEN")


@pytest.mark.asyncio
@respx.mock
async def test_token_rechazado_por_pocketbase_es_sesion_invalida():
    _rutas(auth=401)
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        with pytest.raises(SesionInvalida):
            await resolver_sesion(pb, _redis(), "TOKEN")


@pytest.mark.asyncio
@respx.mock
async def test_la_segunda_vez_sale_de_la_cache_sin_tocar_pocketbase():
    _rutas()
    r = _redis()
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        await resolver_sesion(pb, r, "TOKEN")
        antes = respx.calls.call_count
        p = await resolver_sesion(pb, r, "TOKEN")
    assert respx.calls.call_count == antes
    assert p.account_id == "A1"


@pytest.mark.asyncio
@respx.mock
async def test_la_cache_vence_a_los_60_segundos():
    # Revocar tiene que surtir efecto rapido: un TTL largo dejaria a un revocado
    # adentro por todo ese tiempo.
    _rutas()
    r = _redis()
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        await resolver_sesion(pb, r, "TOKEN")
    clave = (await r.keys("identidad:sesion:*"))[0]
    assert await r.ttl(clave) == 60


@pytest.mark.asyncio
@respx.mock
async def test_una_licencia_revocada_no_se_cachea_como_valida():
    _rutas(licencia_estado="revocada")
    r = _redis()
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        with pytest.raises(LicenciaNoVigente):
            await resolver_sesion(pb, r, "TOKEN")
    assert await r.keys("identidad:sesion:*") == []
```

- [ ] **Step 2: Correrlo y ver que falla**

Run: `uv run pytest tests/test_identidad_sesion.py -q`
Expected: FAIL con `ModuleNotFoundError`

- [ ] **Step 3: Implementar**

```python
"""Quien esta pidiendo, y si todavia tiene derecho a pedir.

Dos preguntas distintas que se responden juntas: el token dice QUIEN es, la licencia dice si
sigue habilitado. Separarlas importa porque se revocan por caminos distintos -- el token muere
solo, la licencia la mata el dueño desde el CLI.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from fastapi import HTTPException, Request

from .pocketbase import AdminPocketBase, PocketBaseError

# 60 s: lo que puede tardar como maximo una revocacion en surtir efecto. Sin cache, cada
# busqueda le pegaria a PocketBase, que corre en un NUC saturado.
TTL_S = 60

# La forma que genera el CLI: tres grupos de cuatro, con el alfabeto sin caracteres
# ambiguos. Vive aca y no en cuentas.py porque los dos modulos la necesitan y este es el
# que no depende del otro.
_ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
FORMA_DE_CODIGO = re.compile(f"^[{_ALFABETO}]{{4}}-[{_ALFABETO}]{{4}}-[{_ALFABETO}]{{4}}$")


@dataclass(frozen=True)
class Persona:
    user_id: str
    account_id: str
    email: str
    licencia: str


class SesionInvalida(Exception):
    """El token no vale: vencido, falso, o la cuenta ya no existe."""


class LicenciaNoVigente(Exception):
    """La persona existe, pero su licencia no esta activa."""


def _clave(token: str) -> str:
    # El token NO se usa como clave en claro: Redis no es un lugar para credenciales, y un
    # dump de Redis no tiene por que servir para suplantar a nadie.
    return "identidad:sesion:" + hashlib.sha256(token.encode()).hexdigest()


async def resolver_sesion(pb: AdminPocketBase, redis, token: str) -> Persona:
    if not token:
        raise SesionInvalida("sin token")

    clave = _clave(token)
    guardado = await redis.get(clave)
    if guardado:
        return Persona(**json.loads(guardado))

    try:
        r = await pb._pedir(
            "POST",
            "/api/collections/users/auth-refresh",
            headers={"Authorization": token},
        )
    except PocketBaseError as e:
        raise SesionInvalida(str(e)) from e
    if r.status_code != 200:
        raise SesionInvalida(f"auth-refresh respondio {r.status_code}")

    registro = r.json().get("record", {})
    codigo = registro.get("licencia") or ""
    if not codigo:
        # Una cuenta sin licencia es una cuenta creada por el agujero que este plan cierra.
        raise LicenciaNoVigente("la cuenta no tiene licencia")

    # El codigo sale de la cuenta, pero termina dentro de un literal del filtro de
    # PocketBase igual que si lo hubiera tipeado una persona: si quedo corrupto por una
    # edicion a mano, una comilla cerraria el literal antes de tiempo y el filtro pasaria a
    # matchear OTRA licencia -- posiblemente una activa. Se valida la forma antes de armarlo.
    if not FORMA_DE_CODIGO.fullmatch(codigo):
        # fullmatch y no match: `match` ancla solo el principio, asi que un codigo valido de 14
        # caracteres seguido de basura pasaria el guard y la basura entraria al filtro.
        raise LicenciaNoVigente(codigo)
    filas = await pb.listar("licencias", f'codigo="{codigo}"')
    if not filas or filas[0].get("estado") != "activa":
        raise LicenciaNoVigente(codigo)

    persona = Persona(
        user_id=registro["id"],
        account_id=registro.get("accountId", ""),
        email=registro.get("email", ""),
        licencia=codigo,
    )
    # Solo se cachea el SI. Cachear el no dejaria a alguien afuera por 60 s despues de que
    # el dueño le reactive la licencia, sin que nadie entienda por que.
    await redis.set(clave, json.dumps(persona.__dict__), ex=TTL_S)
    return persona


async def require_sesion(request: Request) -> Persona:
    token = request.headers.get("Authorization", "")
    try:
        return await resolver_sesion(request.app.state.pb_admin, request.app.state.redis, token)
    except SesionInvalida as e:
        raise HTTPException(401, {"codigo": "sesion_invalida", "mensaje": "volve a entrar"}) from e
    except LicenciaNoVigente as e:
        raise HTTPException(403, {"codigo": "licencia_no_vigente", "mensaje": "tu acceso fue revocado"}) from e
```

**Nota para el implementador:** `pb._pedir` es privado y acá se usa desde afuera. Agregale a `AdminPocketBase` un método público `pedir_como(token, metodo, ruta)` — el `auth-refresh` va con el token de la persona, no con el de admin, y ese es un uso legítimo que merece su propio nombre. No cambia ningún test de la Task 1.

Sumá también este test, que cubre la validación de forma:

```python
@pytest.mark.asyncio
@respx.mock
async def test_una_licencia_con_comillas_no_llega_a_armar_el_filtro():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "ADMIN"})
    )
    respx.post(f"{BASE}/api/collections/users/auth-refresh").mock(
        return_value=httpx.Response(
            200,
            json={"record": {"id": "u1", "accountId": "A1", "email": "x@y.z",
                             "licencia": 'A" || estado="activa'}},
        )
    )
    ruta = respx.get(f"{BASE}/api/collections/licencias/records")
    async with httpx.AsyncClient() as http:
        pb = AdminPocketBase(http, BASE, "a@b.c", "s")
        with pytest.raises(LicenciaNoVigente):
            await resolver_sesion(pb, _redis(), "TOKEN")
    assert ruta.call_count == 0
```

- [ ] **Step 4: Correr los tests**

Run: `uv run pytest tests/test_identidad_sesion.py -q` → PASS
Run: `uv run pytest -q` → sin regresiones

- [ ] **Step 5: Mutar**

Cambiá `!= "activa"` por `== "nunca"` y comprobá que `test_licencia_revocada_no_deja_pasar` se pone rojo. Sacá el `ex=TTL_S` y comprobá que `test_la_cache_vence_a_los_60_segundos` se pone rojo. Restaurá.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/identidad/sesion.py tests/test_identidad_sesion.py
git commit -m "feat(identidad): require_sesion valida token y licencia con cache de 60 s"
```

---

### Task 3: Registrarse con una licencia

**Files:**
- Create: `src/arkiv_api/identidad/cuentas.py`
- Test: `tests/test_identidad_cuentas.py`

**Interfaces:**
- Consumes: `AdminPocketBase`.
- Produces: `async registrar(pb, device_id, email, password, codigo) -> dict` con `{"userId", "accountId"}`; excepciones `LicenciaInvalida`, `EmailEnUso`, `DeviceDesconocido`.

Esta es la operación que cierra el agujero de producción: hoy `users.createRule` deja crear una cuenta con solo estar autenticado como device, sin ninguna licencia.

- [ ] **Step 1: Escribir el test que falla**

```python
import pytest

from arkiv_api.identidad.cuentas import (
    DeviceDesconocido,
    EmailEnUso,
    LicenciaInvalida,
    registrar,
)


class PbFalso:
    """PocketBase con estado: lo que se escribe es lo que despues se lee.

    Un mock que devuelve respuestas fijas se afirma a si mismo -- probaria que el codigo
    llama a lo que esperamos, no que el resultado sea correcto.
    """

    def __init__(self, licencias=(), devices=(), users=()):
        self.licencias = {l["codigo"]: dict(l) for l in licencias}
        self.devices = {d["id"]: dict(d) for d in devices}
        self.users = {u["id"]: dict(u) for u in users}
        self.creados = []

    async def get(self, col, id_):
        return dict(self.devices[id_]) if col == "devices" and id_ in self.devices else None

    async def listar(self, col, filtro, per_page=200):
        if col == "licencias":
            codigo = filtro.split('"')[1]
            fila = self.licencias.get(codigo)
            return [dict(fila)] if fila else []
        if col == "users":
            email = filtro.split('"')[1]
            return [dict(u) for u in self.users.values() if u["email"] == email]
        return []

    async def crear(self, col, campos):
        nuevo = {"id": f"{col}-{len(self.creados) + 1}", **campos}
        self.creados.append((col, campos))
        if col == "users":
            self.users[nuevo["id"]] = nuevo
        return nuevo

    async def patch(self, col, id_, campos):
        destino = self.licencias if col == "licencias" else self.users
        fila = next(f for f in destino.values() if f.get("id") == id_ or f is destino.get(id_))
        fila.update(campos)
        return fila


def _pb(**kw):
    base = dict(
        licencias=[{"id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": "activa", "usadaPor": ""}],
        devices=[{"id": "d1", "accountId": "A_anon", "kind": "phone"}],
    )
    base.update(kw)
    return PbFalso(**base)


@pytest.mark.asyncio
async def test_registro_valido_crea_la_cuenta_con_el_accountId_del_device():
    pb = _pb()
    r = await registrar(pb, "d1", "x@y.z", "clave12345", "AAAA-BBBB-CCCC")
    # El accountId NO cambia: la biblioteca local del aparato queda donde estaba.
    assert r["accountId"] == "A_anon"
    creado = dict(pb.creados[0][1])
    assert creado["licencia"] == "AAAA-BBBB-CCCC"
    assert creado["accountId"] == "A_anon"


@pytest.mark.asyncio
async def test_registro_valido_marca_la_licencia_como_usada():
    pb = _pb()
    r = await registrar(pb, "d1", "x@y.z", "clave12345", "AAAA-BBBB-CCCC")
    assert pb.licencias["AAAA-BBBB-CCCC"]["usadaPor"] == r["userId"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "licencia",
    [
        {"id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": "revocada", "usadaPor": ""},
        {"id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": "activa", "usadaPor": "otro"},
    ],
    ids=["revocada", "ya-usada"],
)
async def test_licencia_revocada_o_ya_usada_no_crea_nada(licencia):
    pb = _pb(licencias=[licencia])
    with pytest.raises(LicenciaInvalida):
        await registrar(pb, "d1", "x@y.z", "clave12345", "AAAA-BBBB-CCCC")
    assert pb.creados == []


@pytest.mark.asyncio
async def test_codigo_inexistente_no_crea_nada():
    pb = _pb()
    with pytest.raises(LicenciaInvalida):
        await registrar(pb, "d1", "x@y.z", "clave12345", "ZZZZ-ZZZZ-ZZZZ")
    assert pb.creados == []


@pytest.mark.asyncio
async def test_un_codigo_con_comillas_no_llega_a_armar_el_filtro():
    # Mismo cuidado que en el CLI: el codigo lo tipea una persona y se mete en un literal
    # del filtro de PocketBase.
    pb = _pb()
    with pytest.raises(LicenciaInvalida):
        await registrar(pb, "d1", "x@y.z", "clave12345", 'A" || estado="activa')
    assert pb.creados == []


@pytest.mark.asyncio
async def test_email_repetido_no_consume_la_licencia():
    pb = _pb(users=[{"id": "u9", "email": "x@y.z", "accountId": "A9"}])
    with pytest.raises(EmailEnUso):
        await registrar(pb, "d1", "x@y.z", "clave12345", "AAAA-BBBB-CCCC")
    assert pb.licencias["AAAA-BBBB-CCCC"]["usadaPor"] == ""


@pytest.mark.asyncio
async def test_device_desconocido_no_crea_nada():
    pb = _pb()
    with pytest.raises(DeviceDesconocido):
        await registrar(pb, "no-existe", "x@y.z", "clave12345", "AAAA-BBBB-CCCC")
    assert pb.creados == []
```

- [ ] **Step 2: Correrlo y ver que falla**

Run: `uv run pytest tests/test_identidad_cuentas.py -q`
Expected: FAIL con `ModuleNotFoundError`

- [ ] **Step 3: Implementar**

```python
"""Las reglas de quien entra y con cuantos aparatos.

Vive fuera de FastAPI a proposito: son reglas de negocio, y probarlas no deberia necesitar
levantar la app ni fabricar un Request.
"""

from __future__ import annotations

from .pocketbase import AdminPocketBase
from .sesion import FORMA_DE_CODIGO


class LicenciaInvalida(Exception):
    """No existe, esta revocada, ya se uso, o ni siquiera tiene forma de codigo."""


class EmailEnUso(Exception):
    pass


class DeviceDesconocido(Exception):
    pass


class TopeAlcanzado(Exception):
    def __init__(self, kind: str, usados: int, tope: int) -> None:
        super().__init__(f"{kind}: {usados}/{tope}")
        self.kind = kind
        self.usados = usados
        self.tope = tope


async def _licencia_libre(pb: AdminPocketBase, codigo: str) -> dict:
    # Validar la FORMA antes de tocar la red: el codigo lo tipea una persona y termina dentro
    # de un literal del filtro de PocketBase. Una comilla lo cerraria antes de tiempo y el
    # filtro pasaria a matchear otra licencia.
    if not FORMA_DE_CODIGO.fullmatch(codigo or ""):
        # fullmatch y no match: `match` ancla solo el principio, asi que un codigo valido de 14
        # caracteres seguido de basura pasaria el guard y la basura entraria al filtro.
        raise LicenciaInvalida(codigo)
    filas = await pb.listar("licencias", f'codigo="{codigo}"')
    if not filas:
        raise LicenciaInvalida(codigo)
    fila = filas[0]
    if fila.get("estado") != "activa" or (fila.get("usadaPor") or ""):
        raise LicenciaInvalida(codigo)
    return fila


async def registrar(pb: AdminPocketBase, device_id: str, email: str, password: str, codigo: str) -> dict:
    device = await pb.get("devices", device_id)
    if not device:
        raise DeviceDesconocido(device_id)

    licencia = await _licencia_libre(pb, codigo)

    if '"' in email or "\\" in email:
        raise EmailEnUso(email)
    if await pb.listar("users", f'email="{email}"'):
        raise EmailEnUso(email)

    # El accountId del device NO cambia: la persona adopta la identidad que el aparato ya
    # tenia, y con eso su biblioteca local queda en su lugar sin migrar nada.
    account_id = device.get("accountId", "")
    user = await pb.crear("users", {
        "email": email,
        "password": password,
        "passwordConfirm": password,
        "accountId": account_id,
        "licencia": codigo,
    })

    # Marcar la licencia DESPUES de crear la cuenta: si se marcara antes y la creacion
    # fallara, la licencia quedaria quemada sin que exista nadie que la use.
    await pb.patch("licencias", licencia["id"], {"usadaPor": user["id"]})
    return {"userId": user["id"], "accountId": account_id}
```

- [ ] **Step 4: Correr los tests**

Run: `uv run pytest tests/test_identidad_cuentas.py -q` → PASS
Run: `uv run pytest -q` → sin regresiones

- [ ] **Step 5: Mutar**

Sacá la condición `or (fila.get("usadaPor") or "")` y comprobá que el caso `ya-usada` se pone rojo. Mové el `pb.patch` de la licencia a antes del `pb.crear` y comprobá que `test_email_repetido_no_consume_la_licencia` se pone rojo. Restaurá.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/identidad/cuentas.py tests/test_identidad_cuentas.py
git commit -m "feat(identidad): registrarse exige una licencia activa y sin usar"
```

---

### Task 4: Aparatos — adoptar, listar y sacar, con el tope por tipo

**Files:**
- Modify: `src/arkiv_api/identidad/cuentas.py`
- Test: `tests/test_identidad_aparatos.py`

**Interfaces:**
- Consumes: `AdminPocketBase`, `Persona` (Task 2), `TopeAlcanzado` (ya definido en Task 3).
- Produces: `async adoptar_aparato(pb, persona, device_id) -> dict`, `async listar_aparatos(pb, persona) -> list[dict]`, `async sacar_aparato(pb, persona, device_id) -> None`.

El tope se cuenta acá y no en la app porque un APK repackado no lo respetaria. Los aparatos anónimos tienen su propio `accountId` random; lo que consume cupo es **pasar un device al `accountId` de la persona**, y eso solo puede hacerlo este código.

**El tope no queda cerrado del todo al terminar este plan, y hay que saberlo.** Hoy `devices.updateRule` es `accountId = @request.auth.accountId`: un aparato puede moverse de cuenta con un PATCH directo a PocketBase, sin pasar por acá. Y `devices.createRule` acepta creaciones **sin autenticar y con cualquier `accountId`**. Cerrar esas dos reglas ahora rompería a los aparatos ya instalados, que dan de alta su identidad anónima escribiendo `devices` directo. Por eso se cierran en el plan 3, junto con el cambio de la app que mueve ese alta al gateway. Hasta entonces esto es la puerta principal, no la única.

- [ ] **Step 1: Escribir el test que falla**

```python
import pytest

from arkiv_api.identidad.cuentas import (
    TopeAlcanzado,
    adoptar_aparato,
    listar_aparatos,
    sacar_aparato,
)
from arkiv_api.identidad.sesion import Persona
from .conftest import PbFalso   # ver la nota de abajo: PbFalso vive en tests/conftest.py

PERSONA = Persona(user_id="u1", account_id="A1", email="x@y.z", licencia="AAAA-BBBB-CCCC")


def _pb(devices, max_celulares=1, max_tvs=1):
    pb = PbFalso(
        licencias=[{
            "id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": "activa", "usadaPor": "u1",
            "maxCelulares": max_celulares, "maxTvs": max_tvs,
        }],
        devices=devices,
    )
    return pb


@pytest.mark.asyncio
async def test_adoptar_el_primer_tv_pasa_el_device_a_la_cuenta():
    pb = _pb([{"id": "d2", "accountId": "A_anon", "kind": "tv"}])
    await adoptar_aparato(pb, PERSONA, "d2")
    assert pb.devices["d2"]["accountId"] == "A1"


@pytest.mark.asyncio
async def test_el_segundo_tv_no_entra_y_no_toca_el_device():
    pb = _pb([
        {"id": "d1", "accountId": "A1", "kind": "tv"},
        {"id": "d2", "accountId": "A_anon", "kind": "tv"},
    ])
    with pytest.raises(TopeAlcanzado) as e:
        await adoptar_aparato(pb, PERSONA, "d2")
    assert (e.value.kind, e.value.tope) == ("tv", 1)
    assert pb.devices["d2"]["accountId"] == "A_anon"


@pytest.mark.asyncio
async def test_el_tope_es_por_tipo_un_tv_no_bloquea_un_celular():
    pb = _pb([
        {"id": "d1", "accountId": "A1", "kind": "tv"},
        {"id": "d2", "accountId": "A_anon", "kind": "phone"},
    ])
    await adoptar_aparato(pb, PERSONA, "d2")
    assert pb.devices["d2"]["accountId"] == "A1"


@pytest.mark.asyncio
async def test_readoptar_un_aparato_que_ya_es_mio_no_consume_cupo():
    # Sin esto, volver a loguearse en el mismo celular daria "llegaste al limite"
    # contra uno mismo.
    pb = _pb([{"id": "d1", "accountId": "A1", "kind": "phone"}])
    await adoptar_aparato(pb, PERSONA, "d1")
    assert pb.devices["d1"]["accountId"] == "A1"


@pytest.mark.asyncio
async def test_la_licencia_manda_sobre_el_tope_no_un_numero_fijo():
    pb = _pb([
        {"id": "d1", "accountId": "A1", "kind": "tv"},
        {"id": "d2", "accountId": "A_anon", "kind": "tv"},
    ], max_tvs=2)
    await adoptar_aparato(pb, PERSONA, "d2")
    assert pb.devices["d2"]["accountId"] == "A1"


@pytest.mark.asyncio
async def test_listar_solo_devuelve_los_aparatos_de_esa_persona():
    pb = _pb([
        {"id": "d1", "accountId": "A1", "kind": "phone"},
        {"id": "d9", "accountId": "OTRA", "kind": "phone"},
    ])
    assert [a["id"] for a in await listar_aparatos(pb, PERSONA)] == ["d1"]


@pytest.mark.asyncio
async def test_no_se_puede_sacar_un_aparato_ajeno():
    pb = _pb([{"id": "d9", "accountId": "OTRA", "kind": "phone"}])
    with pytest.raises(KeyError):
        await sacar_aparato(pb, PERSONA, "d9")
    assert "d9" in pb.devices
```

**`PbFalso` se muda a `tests/conftest.py` en esta tarea.** En la Task 3 nació dentro de `tests/test_identidad_cuentas.py`, pero acá lo necesitan dos archivos y importar de un módulo de test a otro es frágil (depende de que `tests/` sea un paquete). Movelo a `conftest.py`, dejá el import en `test_identidad_cuentas.py`, y comprobá que los tests de la Task 3 siguen verdes.

Ahí mismo extendelo con lo que esta tarea necesita: `listar("devices", ...)` — filtrando por `accountId` y, si viene, por `kind` — y `borrar(col, id)`. El filtro del fake tiene que **parsear** lo que recibe, no ignorarlo: si devolviera siempre todos los devices, `test_listar_solo_devuelve_los_aparatos_de_esa_persona` pasaría con una implementación que no filtra nada.

- [ ] **Step 2: Correrlo y ver que falla**

Run: `uv run pytest tests/test_identidad_aparatos.py -q`
Expected: FAIL con `ImportError: cannot import name 'adoptar_aparato'`

- [ ] **Step 3: Implementar**

```python
async def _tope_de(pb: AdminPocketBase, persona, kind: str) -> int:
    filas = await pb.listar("licencias", f'codigo="{persona.licencia}"')
    if not filas:
        raise LicenciaInvalida(persona.licencia)
    campo = "maxTvs" if kind == "tv" else "maxCelulares"
    return int(filas[0].get(campo) or 1)


async def adoptar_aparato(pb: AdminPocketBase, persona, device_id: str) -> dict:
    device = await pb.get("devices", device_id)
    if not device:
        raise DeviceDesconocido(device_id)
    kind = device.get("kind") or "phone"

    if device.get("accountId") == persona.account_id:
        # Ya es suyo: volver a loguearse en el mismo aparato no puede dar "llegaste al limite".
        return {"kind": kind, "usados": 0, "tope": 0, "yaEra": True}

    tope = await _tope_de(pb, persona, kind)
    mios = await pb.listar("devices", f'accountId="{persona.account_id}" && kind="{kind}"')
    if len(mios) >= tope:
        raise TopeAlcanzado(kind, len(mios), tope)

    await pb.patch("devices", device_id, {"accountId": persona.account_id})
    return {"kind": kind, "usados": len(mios) + 1, "tope": tope, "yaEra": False}


async def listar_aparatos(pb: AdminPocketBase, persona) -> list[dict]:
    return await pb.listar("devices", f'accountId="{persona.account_id}"')


async def sacar_aparato(pb: AdminPocketBase, persona, device_id: str) -> None:
    device = await pb.get("devices", device_id)
    # Comparar el accountId antes de borrar: el id del aparato viaja en la URL y no hay
    # nada que impida mandar el de otra persona.
    if not device or device.get("accountId") != persona.account_id:
        raise KeyError(device_id)
    await pb.borrar("devices", device_id)
```

- [ ] **Step 4: Correr los tests**

Run: `uv run pytest tests/test_identidad_aparatos.py tests/test_identidad_cuentas.py -q` → PASS
Run: `uv run pytest -q` → sin regresiones

- [ ] **Step 5: Mutar**

Cambiá `len(mios) >= tope` por `len(mios) > tope` y comprobá que `test_el_segundo_tv_no_entra_y_no_toca_el_device` se pone rojo. Sacá la comparación de `accountId` de `sacar_aparato` y comprobá que `test_no_se_puede_sacar_un_aparato_ajeno` se pone rojo. Restaurá.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/identidad/cuentas.py tests/test_identidad_aparatos.py tests/test_identidad_cuentas.py
git commit -m "feat(identidad): el tope de aparatos por tipo se cuenta en el servidor"
```

---

### Task 5: Los cuatro endpoints

**Files:**
- Create: `src/arkiv_api/router/cuenta.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_cuenta.py`

**Interfaces:**
- Consumes: todo lo de las tasks 1-4.
- Produces: el contrato HTTP de la tabla del encabezado. `app.state.pb_admin` queda disponible para `require_sesion`.

- [ ] **Step 1: Escribir el test que falla**

Segui el patron que ya usa `tests/test_router_magis.py`: `create_app(Settings(...).validated())`, se pisa el `app.state` que haga falta, y recien despues `TestClient(app)`. **Sin `with`**: TestClient corre el lifespan solo como context manager, y ese lifespan sobreescribiria el `app.state.pb_admin` que acabas de poner.

```python
import fakeredis.aioredis
import pytest
from fastapi.testclient import TestClient

from arkiv_api.app import create_app
from arkiv_api.config import Settings
from .conftest import PbFalso

KEY = "k"
MAESTRA = "x" * 32
LICENCIA = {"id": "l1", "codigo": "AAAA-BBBB-CCCC", "estado": "activa",
            "usadaPor": "", "maxCelulares": 1, "maxTvs": 1}


@pytest.fixture
def app_y_cliente():
    app = create_app(Settings(arkiv_api_keys=KEY, ref_signing_key=MAESTRA).validated())
    app.state.pb_admin = PbFalso(
        licencias=[dict(LICENCIA)],
        devices=[{"id": "d1", "accountId": "A_anon", "kind": "phone"}],
    )
    app.state.redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    return app, TestClient(app)



def test_registrar_sin_cabecera_de_device_da_401(app_y_cliente):
    _, http = app_y_cliente
    r = http.post("/v1/cuenta/registrar",
                  json={"email": "x@y.z", "password": "clave12345",
                        "licencia": "AAAA-BBBB-CCCC"})
    assert r.status_code == 401
    assert r.json()["detail"]["codigo"] == "sin_device"


def test_registrar_con_licencia_buena_da_201_y_el_accountId_del_device(app_y_cliente):
    _, http = app_y_cliente
    r = http.post("/v1/cuenta/registrar",
                  headers={"Authorization": "TOKEN-DEL-DEVICE-d1"},
                  json={"email": "x@y.z", "password": "clave12345",
                        "licencia": "AAAA-BBBB-CCCC"})
    assert r.status_code == 201
    # El accountId del device se conserva: la biblioteca local no se migra.
    assert r.json()["accountId"] == "A_anon"


def test_registrar_con_licencia_revocada_da_400_con_codigo_estable(app_y_cliente):
    app, http = app_y_cliente
    app.state.pb_admin.licencias["AAAA-BBBB-CCCC"]["estado"] = "revocada"
    r = http.post("/v1/cuenta/registrar",
                  headers={"Authorization": "TOKEN-DEL-DEVICE-d1"},
                  json={"email": "x@y.z", "password": "clave12345",
                        "licencia": "AAAA-BBBB-CCCC"})
    assert r.status_code == 400
    assert r.json()["detail"]["codigo"] == "licencia_invalida"


def test_la_llave_vieja_sigue_abriendo_la_busqueda(app_y_cliente):
    """La transicion: desplegar esto NO puede dejar sin app a los aparatos instalados."""
    _, http = app_y_cliente
    r = http.get("/v1/search", params={"q": "matrix"}, headers={"X-Arkiv-Key": KEY})
    assert r.status_code == 200
```

Sumá además: `POST /aparatos` con el tope lleno → 403 y `detail.codigo == "tope_alcanzado"`; `GET /aparatos` → solo los de esa persona; `DELETE /aparatos/{ajeno}` → 404; y un pedido a `/v1/search` con token de licencia revocada → 403.

El test de la llave vieja no es opcional: es el que garantiza que desplegar esto no deja sin app al celular y a la TV que ya están instalados.

- [ ] **Step 2: Correrlo y ver que falla**

Run: `uv run pytest tests/test_router_cuenta.py -q`
Expected: FAIL — las rutas no existen (404)

- [ ] **Step 3: Implementar el router**

```python
"""Los caminos de identidad. Traducen excepciones de `cuentas.py` a codigos HTTP y nada mas:
la logica vive alla, para poder probarla sin levantar la app.
"""

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from ..identidad import cuentas
from ..identidad.sesion import Persona, require_sesion

router = APIRouter(prefix="/v1/cuenta")


class RegistroIn(BaseModel):
    email: str
    password: str
    licencia: str


def _error(status: int, codigo: str, mensaje: str) -> HTTPException:
    # Forma estable: la app ramifica por `codigo` y muestra `mensaje`. Si el detalle fuera
    # un string suelto, cualquier cambio de redaccion romperia la app.
    return HTTPException(status, {"codigo": codigo, "mensaje": mensaje})


@router.post("/registrar", status_code=201)
async def registrar(cuerpo: RegistroIn, request: Request):
    pb = request.app.state.pb_admin
    # El id del aparato sale del token, no de una cabecera: una cabecera la elige el
    # cliente, y con el id de otra persona se registraria bajo SU accountId.
    token = request.headers.get("Authorization", "")
    if not token:
        raise _error(401, "sin_device", "falta la identidad del aparato")
    r = await pb.pedir_como(token, "POST", "/api/collections/devices/auth-refresh")
    if r.status_code != 200:
        raise _error(401, "sin_device", "este aparato no esta dado de alta")
    device_id = r.json()["record"]["id"]
    try:
        return await cuentas.registrar(pb, device_id, cuerpo.email, cuerpo.password, cuerpo.licencia)
    except cuentas.LicenciaInvalida:
        raise _error(400, "licencia_invalida", "ese codigo no existe, ya se uso o fue revocado")
    except cuentas.EmailEnUso:
        raise _error(409, "email_en_uso", "ese email ya tiene cuenta")
    except cuentas.DeviceDesconocido:
        raise _error(401, "sin_device", "este aparato no esta dado de alta")
```

Seguí con `POST /aparatos`, `GET /aparatos` y `DELETE /aparatos/{id}`, todos con `persona: Persona = Depends(require_sesion)`.

- [ ] **Step 4: Cablear en `app.py`**

Junto a `app.state.redis`, dentro del mismo bloque de arranque:

```python
        app.state.pb_admin = AdminPocketBase(
            app.state.http,
            resueltos.pocketbase_url,
            resueltos.pocketbase_admin_email,
            resueltos.pocketbase_admin_password,
        )
```

Y registrá el router nuevo donde se registran los demás.

- [ ] **Step 5: Correr los tests**

Run: `uv run pytest -q` → todo verde, incluida la suite previa completa

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/router/cuenta.py src/arkiv_api/app.py tests/test_router_cuenta.py
git commit -m "feat(identidad): endpoints de registro y de aparatos"
```

---

### Task 6: Cerrar la creación de cuentas y desplegar

**Files:**
- Create: `/Users/cristian/archive/docs/pocketbase/1786900000_updated_users_createrule.js`
- Modify: `.env.example` (las tres `POCKETBASE_*`)

Mientras `users.createRule` siga como está, todo lo anterior es decorativo: cualquiera con el APK crea una cuenta sin licencia, salteándose el endpoint nuevo. **Es un agujero abierto en producción ahora mismo.**

Las reglas de hoy, leídas de la instancia real el 2026-08-12 (no de la documentación):

| Colección | Regla | Valor actual |
|---|---|---|
| `users` | `createRule` | `@request.auth.id != "" && accountId = @request.auth.accountId` |
| `devices` | `createRule` | `@request.auth.id = "" \|\| accountId = @request.auth.accountId` |
| `devices` | `updateRule` | `accountId = @request.auth.accountId` |

- [ ] **Step 1: Migración que cierra la creación de `users`**

`createRule: null` — solo admin. La única creación legítima pasa por `POST /v1/cuenta/registrar`, que usa credenciales de admin. Cerrarlo ahora **no rompe nada**: la app solo crea `users` al registrarse, y hoy no se está registrando nadie.

- [ ] **Step 2: Aplicar la migración**

```bash
scp docs/pocketbase/1786900000_updated_users_createrule.js blog:~/pocketbase/pb_migrations/
ssh blog 'systemctl --user restart pocketbase'
```

- [ ] **Step 3: Verificar contra producción, no contra los tests**

Comprobá a mano, con `curl`, que:
- crear un `users` con el token de un device ahora da 403;
- `POST /v1/cuenta/registrar` con `TUXY-Q7EV-HHKF` crea la cuenta;
- un `GET /v1/search` con la llave vieja **sigue funcionando**.

- [ ] **Step 5: Desplegar**

```bash
rsync -az --delete --exclude '.git' --exclude '.env' --exclude '__pycache__' --exclude '.venv' --exclude '.pytest_cache' --exclude '*.pyc' src/ blog:~/arkiv-api/src/
ssh blog 'cd ~/arkiv-api && sudo docker compose up -d --build'
```

Antes del deploy, agregá las tres `POCKETBASE_*` al `.env` de `blog` si no están (el CLI de licencias ya las usa, así que deberían estar — verificalo, no lo supongas).

- [ ] **Step 6: Commit**

```bash
git add .env.example
git commit -m "chore(identidad): documentar las variables de PocketBase"
```

Las migraciones se commitean en el repo `archive`, con su propio commit.

---

## Lo que queda para el plan 3 (la app)

- Pantalla de entrada obligatoria al arranque; sin sesión no se compone nada.
- Registro que pide el código y pega a `POST /v1/cuenta/registrar`.
- TV sin login manual: solo pareo, y el pareo pasa por `POST /v1/cuenta/aparatos`.
- "Mis aparatos" en ajustes.
- 401/403 → pantalla de entrada; 5xx y sin red → aviso, sin cortar la sesión.
**Y algo que este plan NO cubre y el plan 3 tiene que agendar:** `require_sesion` hoy solo esta en `/v1/cuenta`. Los nueve routers de contenido siguen protegidos unicamente por `require_key`. Si el plan 3 retira la llave sin poner `require_sesion` en esos routers, los deja **sin ninguna autenticacion** — abiertos a internet. El retiro de la llave y el cableado de la sesion son la misma tarea, no dos.

`esteAparato` no se puede calcular desde un token de persona (no hay forma de saber cual de los aparatos es el que pregunta). Si la pantalla "Mis aparatos" lo necesita, la app tiene que mandar su propia identidad; queda para el plan 3 decidir como.

- **Última tarea de todas:** sacar `ARKIV_API_KEY` de los **7** archivos que hoy mandan `X-Arkiv-Key` (`ArkivApiClient`, `LiveApi`, `TmdbApi`, `SimklApi`, `MirrorApiClient`, `SubtitleApi`, `MagisLinkClient`) y del `buildConfigField`, y recién ahí retirar `require_key` del gateway. Ese es el corte limpio del spec.
