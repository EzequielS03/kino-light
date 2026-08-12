# Backend de licencias — Plan de implementación (1 de 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poder crear, listar, revocar, reactivar, reasignar y liberar licencias desde `blog`, con las colecciones de PocketBase que las sostienen.

**Architecture:** Dos colecciones nuevas en PocketBase (`licencias` y `users`) creadas con migraciones JS, siguiendo el patrón que ya usan `devices` y `episode_frames`. Un CLI en Python dentro del repo `arkiv-api` que habla con PocketBase como superusuario. La generación del código es una función pura, separada del cliente HTTP, para poder testearla sin red.

**Tech Stack:** PocketBase (migraciones JS), Python 3.12 + httpx (ya es dependencia de `arkiv-api`), pytest + respx para los tests.

## Global Constraints

- Spec de referencia: `docs/superpowers/specs/2026-08-12-licencias-y-login-obligatorio-design.md`.
- Repo del CLI: `/Users/cristian/arkiv-api`. Repo de las migraciones: `/Users/cristian/archive`.
- Los commits van con `user.name = lordmacu`, sin pie de coautoría.
- **Nunca** exponer la creación de licencias por HTTP: el CLI es el único camino.
- Alfabeto del código sin caracteres ambiguos: sin `0`, `O`, `1`, `l`, `I`.
- El deploy a `blog` es rsync + `docker compose up -d --build`, **no** `git pull`.

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `archive/docs/pocketbase/1786800000_created_licencias.js` | Migración: colección `licencias`. |
| `archive/docs/pocketbase/1786800100_updated_users_licencia.js` | Migración: suma `licencia` a la `users` que ya existe. |
| `archive/docs/pocketbase/collections.md` | Documentar las dos colecciones nuevas. |
| `arkiv-api/src/arkiv_api/licencias/codigo.py` | Generar el código. Puro, sin red. |
| `arkiv-api/src/arkiv_api/licencias/cliente.py` | Cliente PocketBase admin: crear/listar/actualizar. |
| `arkiv-api/src/arkiv_api/licencias/__main__.py` | Los seis comandos y el parseo de argumentos. |
| `arkiv-api/tests/test_licencias_codigo.py` | Tests del generador. |
| `arkiv-api/tests/test_licencias_cliente.py` | Tests del cliente, con respx. |

---

### Task 1: Colección `licencias`

**Files:**
- Create: `archive/docs/pocketbase/1786800000_created_licencias.js`
- Modify: `archive/docs/pocketbase/collections.md`

**Interfaces:**
- Produces: colección `licencias` con campos `codigo`, `estado`, `maxCelulares`, `maxTvs`, `usadaPor`, `notas`.

- [ ] **Step 1: Escribir la migración**

Copiar el estilo de `1786500000_created_episode_frames.js` (comentario de cabecera explicando el porqué, luego `migrate((app) => {...}, (app) => {...})`).

```javascript
/// <reference path="../pb_data/types.d.ts" />
// Licencias: el derecho de uso que se da y se quita. NO es un codigo de invitacion -- se consume al
// registrarse pero sigue vivo, y el gateway lo mira en cada pedido. Por eso revocarla echa a la
// persona aunque ya este adentro.
//
// Reglas CERRADAS a proposito: nadie puede listar ni crear licencias desde la API. El unico camino
// es el CLI de `arkiv-api`, que entra como superusuario. Una licencia que se pueda crear desde
// internet es exactamente el agujero que esto viene a cerrar.
migrate((app) => {
  const licencias = new Collection({
    "name": "licencias",
    "type": "base",
    "system": false,
    "listRule": null,
    "viewRule": null,
    "createRule": null,
    "updateRule": null,
    "deleteRule": null,
    "indexes": [
      "CREATE UNIQUE INDEX `idx_licencias_codigo` ON `licencias` (`codigo`)"
    ],
    "fields": [
      { "name": "codigo", "type": "text", "required": true, "max": 64 },
      { "name": "estado", "type": "select", "required": true, "maxSelect": 1,
        "values": ["activa", "revocada"] },
      { "name": "maxCelulares", "type": "number", "required": true, "onlyInt": true },
      { "name": "maxTvs", "type": "number", "required": true, "onlyInt": true },
      { "name": "usadaPor", "type": "text", "max": 64 },
      { "name": "notas", "type": "text", "max": 200 }
    ]
  })
  app.save(licencias)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("licencias"))
})
```

- [ ] **Step 2: Documentar la colección**

Agregar a `docs/pocketbase/collections.md`, antes de la sección "Pendientes", una sección `## licencias` con la tabla de campos, las reglas (`null` = solo superusuario) y la frase de por qué están cerradas.

- [ ] **Step 3: Commit**

```bash
cd /Users/cristian/archive
git add docs/pocketbase/1786800000_created_licencias.js docs/pocketbase/collections.md
git commit -m "feat(pocketbase): coleccion licencias, cerrada a la API"
```

---

### Task 2: Agregar `licencia` a la colección `users` existente

**Files:**
- Create: `archive/docs/pocketbase/1786800100_updated_users_licencia.js`
- Modify: `archive/docs/pocketbase/collections.md`

**Interfaces:**
- Consumes: colección `licencias` de la Task 1.
- Produces: el campo `licencia` en `users`.

**LEER ESTO ANTES DE EMPEZAR — la versión anterior de esta task estaba equivocada.** Decía "crear la
colección `users`". **`users` YA EXISTE** en la instancia real: es la colección por defecto de
PocketBase, ya adaptada por dos migraciones previas (`1786369101_updated_users.js` le agregó
`accountId` y su índice; `1786369470_updated_users_createrule.js` endureció su `createRule`).
Intentar crearla de nuevo haría fallar la migración. Esta task la **modifica**.

Antes de escribir nada, leé esas dos migraciones para no pisar lo que ya hacen:

```bash
ssh blog "cat ~/pocketbase/pb_migrations/1786369101_updated_users.js ~/pocketbase/pb_migrations/1786369470_updated_users_createrule.js"
```

**Qué NO hace esta task:** no toca `createRule`. Hoy esa regla exige un device autenticado pero
ninguna licencia, y ese agujero se cierra en el Plan 3, cuando el registro pase por el gateway (que
es quien puede validar "la licencia existe, está activa y no fue usada" — una regla de PocketBase no
puede expresar esas tres condiciones sobre la misma licencia de forma confiable). Dejarlo acá a
medias sería peor: una regla que valida el código pero no su estado da falsa sensación de cierre.

- [ ] **Step 1: Escribir la migración**

```javascript
/// <reference path="../pb_data/types.d.ts" />
// Suma `licencia` a `users`: que persona esta habilitada por que licencia. El gateway lo lee en cada
// pedido para poder revocar (ver el spec de licencias).
//
// Es un UPDATE y no un create: `users` ya existe -- es la coleccion por defecto de PocketBase, ya
// adaptada por 1786369101_updated_users.js. Crearla de nuevo falla.
//
// Guarda el CODIGO y no una relation: el gateway resuelve la licencia por codigo en cada validacion,
// y una relation lo obligaria a expandirla en cada consulta sin darle nada a cambio.
//
// Va sin `required`: los records que ya existen no tienen licencia, y marcarlo obligatorio los
// dejaria invalidos. Que no falte de verdad lo garantiza el registro, no el esquema.
migrate((app) => {
  const users = app.findCollectionByNameOrId("users")
  users.fields.add(new Field({
    "hidden": false,
    "id": "text_licencia_ark",
    "max": 64,
    "min": 0,
    "name": "licencia",
    "presentable": false,
    "primaryKey": false,
    "required": false,
    "system": false,
    "type": "text"
  }))
  return app.save(users)
}, (app) => {
  const users = app.findCollectionByNameOrId("users")
  users.fields.removeByName("licencia")
  return app.save(users)
})
```

- [ ] **Step 2: Documentar**

En `docs/pocketbase/collections.md`, en la sección que ya existe de `users`, agregar el campo
`licencia` a la tabla y una nota con las dos razones de arriba: por qué guarda el código y no una
relación, y por qué no es `required`.

- [ ] **Step 3: Commit**

```bash
cd /Users/cristian/archive
git add docs/pocketbase/1786800100_updated_users_licencia.js docs/pocketbase/collections.md
git commit -m "feat(pocketbase): users suma el campo licencia"
```

---

### Task 3: Generador de códigos

**Files:**
- Create: `arkiv-api/src/arkiv_api/licencias/__init__.py` (vacío)
- Create: `arkiv-api/src/arkiv_api/licencias/codigo.py`
- Test: `arkiv-api/tests/test_licencias_codigo.py`

**Interfaces:**
- Produces: `generar(grupos: int = 3, largo: int = 4, aleatorio=secrets) -> str` y la constante `ALFABETO`.

- [ ] **Step 1: Escribir el test que falla**

```python
import re

from arkiv_api.licencias.codigo import ALFABETO, generar


def test_el_formato_es_por_grupos_para_poder_dictarlo():
    # Se dicta por telefono: en grupos cortos separados por guiones se lee sin perderse.
    assert re.fullmatch(r"[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}", generar())


def test_no_usa_caracteres_ambiguos():
    # 0/O y 1/l/I son los que hacen que el codigo dictado llegue mal.
    for prohibido in "01OIl":
        assert prohibido not in ALFABETO


def test_dos_codigos_seguidos_no_son_iguales():
    assert generar() != generar()


def test_el_tamano_es_configurable():
    assert re.fullmatch(r"[A-Z2-9]{5}-[A-Z2-9]{5}", generar(grupos=2, largo=5))
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_codigo.py -q`
Expected: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.licencias'`

- [ ] **Step 3: Implementar**

```python
"""Generacion del codigo de licencia."""

from __future__ import annotations

import secrets

# Sin 0/O ni 1/I/L: el codigo se dicta por telefono y esos son los que llegan mal.
ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def generar(grupos: int = 3, largo: int = 4, aleatorio=secrets) -> str:
    """Codigo nuevo, en grupos separados por guiones.

    `aleatorio` se inyecta para poder fijarlo en un test; por defecto es `secrets`, que es el que
    corresponde para algo que da acceso.
    """
    partes = [
        "".join(aleatorio.choice(ALFABETO) for _ in range(largo))
        for _ in range(grupos)
    ]
    return "-".join(partes)
```

- [ ] **Step 4: Correr los tests**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_codigo.py -q`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/licencias/ tests/test_licencias_codigo.py
git commit -m "feat(licencias): generador de codigos sin caracteres ambiguos"
```

---

### Task 4: Cliente PocketBase de licencias

**Files:**
- Create: `arkiv-api/src/arkiv_api/licencias/cliente.py`
- Test: `arkiv-api/tests/test_licencias_cliente.py`

**Interfaces:**
- Consumes: nada de tasks previas.
- Produces: `ClienteLicencias(base_url, email, password, http)` con `crear(codigo, notas, max_celulares=1, max_tvs=1) -> dict`, `listar() -> list[dict]`, `cambiar_estado(codigo, estado) -> dict`.

- [ ] **Step 1: Escribir el test que falla**

```python
import httpx
import pytest
import respx

from arkiv_api.licencias.cliente import ClienteLicencias, LicenciaNoExiste

BASE = "https://db.example"


def _cliente(http):
    return ClienteLicencias(BASE, "admin@x", "clave", http)


@respx.mock
@pytest.mark.asyncio
async def test_crear_autentica_como_superusuario_y_guarda_la_licencia():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    alta = respx.post(f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"id": "r1", "codigo": "AAAA-BBBB-CCCC"})
    )
    async with httpx.AsyncClient() as http:
        r = await _cliente(http).crear("AAAA-BBBB-CCCC", notas="hermana")
    assert r["codigo"] == "AAAA-BBBB-CCCC"
    cuerpo = alta.calls[0].request.content.decode()
    assert '"estado":"activa"' in cuerpo.replace(" ", "")
    # El tope por tipo vive en la licencia, no en el codigo del gateway: se puede aflojar
    # para una persona sin tocar el server.
    assert '"maxCelulares":1' in cuerpo.replace(" ", "")
    assert alta.calls[0].request.headers["authorization"] == "TOK"


@respx.mock
@pytest.mark.asyncio
async def test_revocar_una_que_no_existe_avisa_claro():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    respx.get(url__startswith=f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"items": []})
    )
    async with httpx.AsyncClient() as http:
        with pytest.raises(LicenciaNoExiste):
            await _cliente(http).cambiar_estado("NO-EXISTE", "revocada")
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q`
Expected: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.licencias.cliente'`

- [ ] **Step 3: Implementar**

```python
"""Cliente PocketBase para las licencias. Entra como superusuario: estas colecciones tienen las
reglas cerradas y no se pueden tocar desde la API con un token normal."""

from __future__ import annotations

import httpx


class LicenciaNoExiste(RuntimeError):
    """El codigo pedido no esta en la coleccion."""


class ClienteLicencias:
    def __init__(self, base_url: str, email: str, password: str, http: httpx.AsyncClient) -> None:
        self._base = base_url.rstrip("/")
        self._email = email
        self._password = password
        self._http = http
        self._token: str | None = None

    async def _auth(self) -> str:
        if self._token:
            return self._token
        r = await self._http.post(
            f"{self._base}/api/collections/_superusers/auth-with-password",
            json={"identity": self._email, "password": self._password},
        )
        r.raise_for_status()
        self._token = r.json()["token"]
        return self._token

    async def _cab(self) -> dict[str, str]:
        return {"Authorization": await self._auth()}

    async def crear(self, codigo: str, notas: str = "", max_celulares: int = 1, max_tvs: int = 1) -> dict:
        r = await self._http.post(
            f"{self._base}/api/collections/licencias/records",
            headers=await self._cab(),
            json={
                "codigo": codigo,
                "estado": "activa",
                "maxCelulares": max_celulares,
                "maxTvs": max_tvs,
                "notas": notas,
                "usadaPor": "",
            },
        )
        r.raise_for_status()
        return r.json()

    async def listar(self) -> list[dict]:
        r = await self._http.get(
            f"{self._base}/api/collections/licencias/records",
            headers=await self._cab(),
            params={"perPage": 200, "sort": "-created"},
        )
        r.raise_for_status()
        return r.json().get("items", [])

    async def _buscar(self, codigo: str) -> dict:
        # El codigo se valida ANTES de armar el filtro: llega tipeado a mano desde el CLI, y una
        # comilla doble cierra el literal del filtro de PocketBase antes de tiempo -- el filtro
        # pasaria a matchear OTRO registro y se revocaria la licencia equivocada en silencio.
        if not _CODIGO_VALIDO.fullmatch(codigo):
            raise CodigoInvalido(codigo)
        r = await self._http.get(
            f"{self._base}/api/collections/licencias/records",
            headers=await self._cab(),
            params={"filter": f'codigo="{codigo}"', "perPage": 1},
        )
        r.raise_for_status()
        items = r.json().get("items", [])
        if not items:
            raise LicenciaNoExiste(codigo)
        return items[0]

    async def cambiar_estado(self, codigo: str, estado: str) -> dict:
        registro = await self._buscar(codigo)
        r = await self._http.patch(
            f"{self._base}/api/collections/licencias/records/{registro['id']}",
            headers=await self._cab(),
            json={"estado": estado},
        )
        r.raise_for_status()
        return r.json()
```

- [ ] **Step 4: Correr los tests**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q`
Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/licencias/cliente.py tests/test_licencias_cliente.py
git commit -m "feat(licencias): cliente PocketBase como superusuario"
```

---

### Task 5: Los comandos crear, listar, revocar y reactivar

**Files:**
- Create: `arkiv-api/src/arkiv_api/licencias/__main__.py`
- Modify: `arkiv-api/README.md` (sección nueva "Licencias")

**Interfaces:**
- Consumes: `generar` (Task 3), `ClienteLicencias` y `LicenciaNoExiste` (Task 4).
- Produces: `python -m arkiv_api.licencias {crear,listar,revocar,reactivar}`.

- [ ] **Step 1: Implementar el CLI**

No lleva test propio: es cableado entre dos piezas que ya están testeadas, y su valor se comprueba corriéndolo de verdad en la Task 6.

```python
"""CLI de licencias. Se corre en `blog`, donde viven las credenciales de admin de PocketBase.

Es el UNICO camino para crear una licencia. No hay endpoint equivalente a proposito: crear licencias
es la operacion mas sensible del sistema, y un endpoint hay que autenticarlo, exponerlo y cuidarlo.
Esto solo lo puede correr quien ya tiene SSH al servidor.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys

import httpx

from .cliente import ClienteLicencias, LicenciaNoExiste
from .codigo import generar


def _cliente(http: httpx.AsyncClient) -> ClienteLicencias:
    faltan = [v for v in ("POCKETBASE_URL", "POCKETBASE_ADMIN_EMAIL", "POCKETBASE_ADMIN_PASSWORD")
              if not os.environ.get(v)]
    if faltan:
        sys.exit(f"faltan variables de entorno: {', '.join(faltan)}")
    return ClienteLicencias(
        os.environ["POCKETBASE_URL"],
        os.environ["POCKETBASE_ADMIN_EMAIL"],
        os.environ["POCKETBASE_ADMIN_PASSWORD"],
        http,
    )


async def _crear(args) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        r = await _cliente(http).crear(generar(), notas=args.notas)
    print(r["codigo"])


async def _listar(_args) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        licencias = await _cliente(http).listar()
    if not licencias:
        print("sin licencias")
        return
    print(f"{'CODIGO':<16} {'ESTADO':<9} {'USADA POR':<24} NOTAS")
    for l in licencias:
        print(f"{l['codigo']:<16} {l['estado']:<9} {(l.get('usadaPor') or '-'):<24} {l.get('notas', '')}")


async def _cambiar(args, estado: str) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        try:
            await _cliente(http).cambiar_estado(args.codigo, estado)
        except LicenciaNoExiste:
            sys.exit(f"no existe la licencia {args.codigo}")
    print(f"{args.codigo} → {estado}")


def main() -> None:
    p = argparse.ArgumentParser(prog="licencias", description="Licencias de Arkiv")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("crear", help="genera una licencia nueva y la imprime")
    c.add_argument("--notas", default="", help='para acordarte: "hermana", "TV del living"')

    sub.add_parser("listar", help="todas las licencias con su estado")

    r = sub.add_parser("revocar", help="deja a esa persona afuera en <=60 s")
    r.add_argument("codigo")

    a = sub.add_parser("reactivar", help="revierte un revocar")
    a.add_argument("codigo")

    args = p.parse_args()
    if args.cmd == "crear":
        asyncio.run(_crear(args))
    elif args.cmd == "listar":
        asyncio.run(_listar(args))
    elif args.cmd == "revocar":
        asyncio.run(_cambiar(args, "revocada"))
    elif args.cmd == "reactivar":
        asyncio.run(_cambiar(args, "activa"))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verificar que la ayuda se arma sin tocar la red**

Run: `cd /Users/cristian/arkiv-api && uv run python -m arkiv_api.licencias --help`
Expected: se listan los cuatro comandos.

- [ ] **Step 3: Correr toda la suite (no se rompió nada)**

Run: `cd /Users/cristian/arkiv-api && uv run pytest -q`
Expected: todos verdes.

- [ ] **Step 4: Documentar en el README**

Agregar una sección "Licencias" con los cuatro comandos, las tres variables de entorno y la advertencia de que el `crear` es el único camino.

- [ ] **Step 5: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/licencias/__main__.py README.md
git commit -m "feat(licencias): CLI crear/listar/revocar/reactivar"
```

---

### Task 6: Comando `liberar`

**Files:**
- Modify: `arkiv-api/src/arkiv_api/licencias/cliente.py`
- Modify: `arkiv-api/src/arkiv_api/licencias/__main__.py`
- Test: `arkiv-api/tests/test_licencias_cliente.py`

**Interfaces:**
- Consumes: `ClienteLicencias` y `LicenciaNoExiste` (Task 4).
- Produces: `ClienteLicencias.liberar(codigo) -> dict` y `python -m arkiv_api.licencias liberar <codigo> --si`.

**Por qué existe:** el código es de un solo uso *para registrarse*, y el Spec 1 dejó fuera recuperar
contraseña. Sin esto, alguien que olvida su clave queda en un callejón: no puede recuperarla ni
volver a registrarse, porque su licencia figura consumida.

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de `tests/test_licencias_cliente.py`:

```python
@respx.mock
@pytest.mark.asyncio
async def test_liberar_borra_la_cuenta_y_deja_la_licencia_lista_de_nuevo():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    respx.get(url__startswith=f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"items": [
            {"id": "lic1", "codigo": "AAAA-BBBB-CCCC", "usadaPor": "user9"}
        ]})
    )
    borrado = respx.delete(f"{BASE}/api/collections/users/records/user9").mock(
        return_value=httpx.Response(204)
    )
    limpiado = respx.patch(f"{BASE}/api/collections/licencias/records/lic1").mock(
        return_value=httpx.Response(200, json={"id": "lic1", "usadaPor": ""})
    )
    async with httpx.AsyncClient() as http:
        await _cliente(http).liberar("AAAA-BBBB-CCCC")
    assert borrado.called
    assert '"usadaPor":""' in limpiado.calls[0].request.content.decode().replace(" ", "")


@respx.mock
@pytest.mark.asyncio
async def test_liberar_una_sin_usar_no_borra_ninguna_cuenta():
    # Sin `usadaPor` no hay a quien borrar. Mandar un DELETE a /records/ (sin id) borraria
    # cualquier cosa o daria un error confuso.
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    respx.get(url__startswith=f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"items": [
            {"id": "lic1", "codigo": "AAAA-BBBB-CCCC", "usadaPor": ""}
        ]})
    )
    borrado = respx.delete(url__startswith=f"{BASE}/api/collections/users/records").mock(
        return_value=httpx.Response(204)
    )
    limpiado = respx.patch(f"{BASE}/api/collections/licencias/records/lic1").mock(
        return_value=httpx.Response(200, json={"id": "lic1"})
    )
    async with httpx.AsyncClient() as http:
        await _cliente(http).liberar("AAAA-BBBB-CCCC")
    assert not borrado.called
    assert limpiado.called
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q -k liberar`
Expected: FAIL con `AttributeError: 'ClienteLicencias' object has no attribute 'liberar'`

- [ ] **Step 3: Implementar en el cliente**

Agregar a `ClienteLicencias`:

```python
    async def liberar(self, codigo: str) -> dict:
        """Borra la cuenta que uso esta licencia y la deja lista para registrarse de nuevo.

        Es la salida del callejon "olvide mi contrasena": no hay recuperacion de clave, y el codigo
        ya figura consumido. DESTRUCTIVO -- se lleva la cuenta puesta; los datos sincronizados de esa
        persona quedan en el server bajo su viejo accountId, huerfanos.
        """
        registro = await self._buscar(codigo)
        usada_por = registro.get("usadaPor") or ""
        if usada_por:
            r = await self._http.delete(
                f"{self._base}/api/collections/users/records/{usada_por}",
                headers=await self._cab(),
            )
            r.raise_for_status()
        r = await self._http.patch(
            f"{self._base}/api/collections/licencias/records/{registro['id']}",
            headers=await self._cab(),
            json={"usadaPor": ""},
        )
        r.raise_for_status()
        return r.json()
```

- [ ] **Step 4: Correr los tests**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q`
Expected: 4 passed

- [ ] **Step 5: Agregar el comando al CLI**

En `__main__.py`, agregar la función y el subparser. La confirmación **no** es ceremonia: este
comando borra una cuenta, y un tipeo no puede alcanzar para eso.

```python
async def _liberar(args) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        cli = _cliente(http)
        try:
            if not args.si:
                sys.exit(
                    f"esto BORRA la cuenta que uso {args.codigo} y deja la licencia libre.\n"
                    f"si es lo que queres: licencias liberar {args.codigo} --si"
                )
            await cli.liberar(args.codigo)
        except LicenciaNoExiste:
            sys.exit(f"no existe la licencia {args.codigo}")
    print(f"{args.codigo} liberada: se puede volver a registrar")
```

En `main()`, junto a los otros subparsers:

```python
    lb = sub.add_parser("liberar", help="borra la cuenta y deja la licencia lista de nuevo")
    lb.add_argument("codigo")
    lb.add_argument("--si", action="store_true", help="confirma que se borra la cuenta")
```

Y en el despacho:

```python
    elif args.cmd == "liberar":
        asyncio.run(_liberar(args))
```

- [ ] **Step 6: Verificar que sin `--si` no hace nada**

Run: `cd /Users/cristian/arkiv-api && uv run python -m arkiv_api.licencias liberar AAAA-BBBB-CCCC`
Expected: imprime la advertencia y sale con código distinto de 0, **sin** tocar la red.

- [ ] **Step 7: Correr toda la suite**

Run: `cd /Users/cristian/arkiv-api && uv run pytest -q`
Expected: todos verdes.

- [ ] **Step 8: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/licencias/ tests/test_licencias_cliente.py
git commit -m "feat(licencias): comando liberar para el callejon de la contrasena olvidada"
```

---

### Task 7: Comando `reasignar`

**Files:**
- Modify: `arkiv-api/src/arkiv_api/licencias/cliente.py`
- Modify: `arkiv-api/src/arkiv_api/licencias/__main__.py`
- Test: `arkiv-api/tests/test_licencias_cliente.py`

**Interfaces:**
- Consumes: `ClienteLicencias` (Task 4), `generar` (Task 3).
- Produces: `ClienteLicencias.reasignar(email, codigo_nuevo) -> str` y
  `python -m arkiv_api.licencias reasignar <email>`.

**Por qué existe:** cambiarle la licencia a alguien **sin borrarle la cuenta**. Sirve cuando se
revocó por error, o cuando el código se filtró y hay que cortarlo sin castigar a la persona. Es lo
opuesto de `liberar`: acá la cuenta y sus datos quedan intactos y lo único que cambia es qué
licencia la habilita.

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de `tests/test_licencias_cliente.py`:

```python
@respx.mock
@pytest.mark.asyncio
async def test_reasignar_da_una_licencia_nueva_y_revoca_la_vieja():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    # La cuenta existe y hoy usa la vieja.
    respx.get(url__startswith=f"{BASE}/api/collections/users/records").mock(
        return_value=httpx.Response(200, json={"items": [
            {"id": "user9", "email": "her@x", "licencia": "VIEJA-VIEJA-VIEJA"}
        ]})
    )
    respx.get(url__startswith=f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"items": [
            {"id": "licVieja", "codigo": "VIEJA-VIEJA-VIEJA", "usadaPor": "user9"}
        ]})
    )
    alta = respx.post(f"{BASE}/api/collections/licencias/records").mock(
        return_value=httpx.Response(200, json={"id": "licNueva", "codigo": "NUEV-AAAA-BBBB"})
    )
    parche_vieja = respx.patch(f"{BASE}/api/collections/licencias/records/licVieja").mock(
        return_value=httpx.Response(200, json={"id": "licVieja"})
    )
    parche_user = respx.patch(f"{BASE}/api/collections/users/records/user9").mock(
        return_value=httpx.Response(200, json={"id": "user9"})
    )
    async with httpx.AsyncClient() as http:
        codigo = await _cliente(http).reasignar("her@x", "NUEV-AAAA-BBBB")

    assert codigo == "NUEV-AAAA-BBBB"
    # La nueva nace ya ligada a esa cuenta: nadie mas puede usarla para registrarse.
    assert '"usadaPor":"user9"' in alta.calls[0].request.content.decode().replace(" ", "")
    # La vieja se revoca: si el motivo fue una filtracion, dejarla activa no arregla nada.
    assert '"estado":"revocada"' in parche_vieja.calls[0].request.content.decode().replace(" ", "")
    assert '"licencia":"NUEV-AAAA-BBBB"' in parche_user.calls[0].request.content.decode().replace(" ", "")


@respx.mock
@pytest.mark.asyncio
async def test_reasignar_a_un_email_que_no_existe_avisa_claro():
    respx.post(f"{BASE}/api/collections/_superusers/auth-with-password").mock(
        return_value=httpx.Response(200, json={"token": "TOK"})
    )
    respx.get(url__startswith=f"{BASE}/api/collections/users/records").mock(
        return_value=httpx.Response(200, json={"items": []})
    )
    async with httpx.AsyncClient() as http:
        with pytest.raises(CuentaNoExiste):
            await _cliente(http).reasignar("nadie@x", "NUEV-AAAA-BBBB")
```

Agregar `CuentaNoExiste` al import de arriba del archivo:

```python
from arkiv_api.licencias.cliente import ClienteLicencias, CuentaNoExiste, LicenciaNoExiste
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q -k reasignar`
Expected: FAIL con `ImportError: cannot import name 'CuentaNoExiste'`

- [ ] **Step 3: Implementar en el cliente**

Agregar la excepción arriba, junto a `LicenciaNoExiste`:

```python
class CuentaNoExiste(RuntimeError):
    """No hay ninguna cuenta con ese email."""
```

Y el método en `ClienteLicencias`:

```python
    async def _buscar_cuenta(self, email: str) -> dict:
        # Mismo cuidado que en _buscar: el email llega tipeado desde el CLI y se mete en un literal
        # del filtro de PocketBase. Una comilla doble lo cierra antes de tiempo y el filtro pasaria
        # a matchear otra cuenta -- se le reasignaria la licencia a la persona equivocada.
        if '"' in email or "\\" in email:
            raise CuentaNoExiste(email)
        r = await self._http.get(
            f"{self._base}/api/collections/users/records",
            headers=await self._cab(),
            params={"filter": f'email="{email}"', "perPage": 1},
        )
        r.raise_for_status()
        items = r.json().get("items", [])
        if not items:
            raise CuentaNoExiste(email)
        return items[0]

    async def reasignar(self, email: str, codigo_nuevo: str) -> str:
        """Le da una licencia NUEVA a una cuenta existente y revoca la que tenia.

        La cuenta y sus datos quedan intactos: lo unico que cambia es que licencia la habilita. La
        vieja se revoca porque el motivo tipico para reasignar es que se filtro, y dejarla activa no
        arreglaria nada.
        """
        cuenta = await self._buscar_cuenta(email)
        vieja = cuenta.get("licencia") or ""

        r = await self._http.post(
            f"{self._base}/api/collections/licencias/records",
            headers=await self._cab(),
            json={
                "codigo": codigo_nuevo,
                "estado": "activa",
                "maxCelulares": 1,
                "maxTvs": 1,
                "notas": f"reasignada a {email}",
                "usadaPor": cuenta["id"],
            },
        )
        r.raise_for_status()

        if vieja:
            registro = await self._buscar(vieja)
            r = await self._http.patch(
                f"{self._base}/api/collections/licencias/records/{registro['id']}",
                headers=await self._cab(),
                json={"estado": "revocada"},
            )
            r.raise_for_status()

        r = await self._http.patch(
            f"{self._base}/api/collections/users/records/{cuenta['id']}",
            headers=await self._cab(),
            json={"licencia": codigo_nuevo},
        )
        r.raise_for_status()
        return codigo_nuevo
```

- [ ] **Step 4: Correr los tests**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_licencias_cliente.py -q`
Expected: 6 passed

- [ ] **Step 5: Agregar el comando al CLI**

En `__main__.py`, importar `CuentaNoExiste` junto a las otras dos y agregar:

```python
async def _reasignar(args) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        try:
            codigo = await _cliente(http).reasignar(args.email, generar())
        except CuentaNoExiste:
            sys.exit(f"no hay ninguna cuenta con el email {args.email}")
    print(codigo)
```

En `main()`:

```python
    ra = sub.add_parser("reasignar", help="licencia nueva para una cuenta que ya existe")
    ra.add_argument("email")
```

Y en el despacho:

```python
    elif args.cmd == "reasignar":
        asyncio.run(_reasignar(args))
```

- [ ] **Step 6: Correr toda la suite**

Run: `cd /Users/cristian/arkiv-api && uv run pytest -q`
Expected: todos verdes.

- [ ] **Step 7: Documentar los dos comandos en el README**

Agregar `reasignar` y `liberar` a la sección "Licencias", con la diferencia explícita: `reasignar`
conserva la cuenta y cambia su licencia; `liberar` **borra la cuenta**.

- [ ] **Step 8: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/licencias/ tests/test_licencias_cliente.py README.md
git commit -m "feat(licencias): reasignar da una licencia nueva sin borrar la cuenta"
```

---

### Task 8: Aplicar en `blog` y crear la primera licencia

**Files:**
- Modify: `blog:~/arkiv-api/.env` (agregar las tres variables)
- Copiar: las dos migraciones al `pb_migrations` de PocketBase en `blog`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: las colecciones creadas y la primera licencia, que es la que desbloquea el Plan 3.

- [ ] **Step 1: Ubicar el directorio de migraciones de PocketBase**

Run: `ssh blog "ls ~/pocketbase* -d 2>/dev/null; systemctl --user status pocketbase --no-pager | head -5"`

Anotar la ruta real antes de copiar nada. **No adivinar**: copiar una migración al lugar equivocado no falla, simplemente no hace nada, y el síntoma aparece recién al usar el CLI.

- [ ] **Step 2: Copiar las migraciones y reiniciar PocketBase**

```bash
scp docs/pocketbase/1786800000_created_licencias.js docs/pocketbase/1786800100_updated_users_licencia.js blog:<ruta>/pb_migrations/
ssh blog "systemctl --user restart pocketbase"
```

- [ ] **Step 3: Verificar que las colecciones existen**

Run: `ssh blog "sudo journalctl --user -u pocketbase -n 20 --no-pager | grep -i migrat"`
Expected: las dos migraciones aplicadas, sin error.

- [ ] **Step 4: Poner las credenciales de admin en el `.env` de `arkiv-api`**

```bash
ssh blog "printf 'POCKETBASE_URL=https://db.comparadorinternet.co\nPOCKETBASE_ADMIN_EMAIL=<email>\nPOCKETBASE_ADMIN_PASSWORD=<clave>\n' >> ~/arkiv-api/.env"
```

- [ ] **Step 5: Desplegar el CLI y crear la primera licencia**

```bash
cd /Users/cristian/arkiv-api
rsync -a --exclude '__pycache__' --exclude '.pytest_cache' --exclude '.venv' --exclude '.git' --exclude '.env' \
  src tests Dockerfile docker-compose.yml pyproject.toml README.md blog:~/arkiv-api/
ssh blog "cd ~/arkiv-api && sudo docker compose up -d --build"
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias crear --notas 'dueño'"
```

Expected: imprime un código con la forma `XXXX-XXXX-XXXX`. **Guardarlo**: es el que se usa en el Plan 3 para registrarse.

- [ ] **Step 6: Verificar el ciclo completo contra la instancia real**

```bash
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias listar"
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias revocar <codigo>"
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias listar"
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias reactivar <codigo>"
```

Expected: la licencia aparece, pasa a `revocada`, y vuelve a `activa`. Verificar también que revocar un código inexistente imprime el error y sale con código distinto de 0.

Y que `liberar` sin `--si` avisa en vez de borrar:

```bash
ssh blog "cd ~/arkiv-api && sudo docker compose exec -T api python -m arkiv_api.licencias liberar <codigo>"
```

Expected: la advertencia y salida distinta de 0. **No** correr con `--si` sobre la licencia del dueño: todavía no hay cuenta registrada, pero el día que la haya, ese comando se la lleva.

- [ ] **Step 7: Commit de lo que haya quedado sin commitear**

```bash
cd /Users/cristian/arkiv-api && git status --short
```

Si no hay cambios, no hay nada que commitear: esta task es despliegue y verificación.
