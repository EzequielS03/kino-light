# Backend de licencias — Plan de implementación (1 de 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Poder crear, listar, revocar y reactivar licencias desde `blog`, con las colecciones de PocketBase que las sostienen.

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
| `archive/docs/pocketbase/1786800100_created_users.js` | Migración: colección auth `users`. |
| `archive/docs/pocketbase/collections.md` | Documentar las dos colecciones nuevas. |
| `arkiv-api/src/arkiv_api/licencias/codigo.py` | Generar el código. Puro, sin red. |
| `arkiv-api/src/arkiv_api/licencias/cliente.py` | Cliente PocketBase admin: crear/listar/actualizar. |
| `arkiv-api/src/arkiv_api/licencias/__main__.py` | Los cuatro comandos y el parseo de argumentos. |
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

### Task 2: Colección auth `users`

**Files:**
- Create: `archive/docs/pocketbase/1786800100_created_users.js`
- Modify: `archive/docs/pocketbase/collections.md`

**Interfaces:**
- Consumes: colección `licencias` de la Task 1 (el campo `licencia` la referencia por código).
- Produces: colección auth `users` con `accountId` y `licencia`.

- [ ] **Step 1: Escribir la migración**

```javascript
/// <reference path="../pb_data/types.d.ts" />
// `users`: la PERSONA. Hasta ahora la identidad era el device (`devices`) y la persona no existia:
// `accountId` agrupaba devices pero nadie podia autenticarse "como esa persona".
//
// `licencia` guarda el CODIGO, no una relacion: el gateway resuelve la licencia por codigo en cada
// validacion, y una relation obligaria a expandirla en cada consulta sin darnos nada a cambio.
migrate((app) => {
  const users = new Collection({
    "name": "users",
    "type": "auth",
    "system": false,
    "listRule": "id = @request.auth.id",
    "viewRule": "id = @request.auth.id",
    "createRule": null,
    "updateRule": "id = @request.auth.id",
    "deleteRule": null,
    "passwordAuth": { "enabled": true, "identityFields": ["email"] },
    "indexes": [
      "CREATE INDEX `idx_users_accountId` ON `users` (`accountId`)"
    ],
    "fields": [
      { "name": "accountId", "type": "text", "required": true, "max": 64 },
      { "name": "licencia", "type": "text", "required": true, "max": 64 }
    ]
  })
  app.save(users)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("users"))
})
```

- [ ] **Step 2: Documentar**

Agregar la sección `## users` a `collections.md`, con los campos y esta nota: **`createRule: null` significa que nadie se registra solo desde la API.** El alta la hace el flujo de registro de la app pasando por el gateway (Plan 3), que valida la licencia antes de crear. Sin eso, cualquiera con la URL de PocketBase se crearía una cuenta.

- [ ] **Step 3: Commit**

```bash
cd /Users/cristian/archive
git add docs/pocketbase/1786800100_created_users.js docs/pocketbase/collections.md
git commit -m "feat(pocketbase): coleccion users, la persona"
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

### Task 5: Los cuatro comandos

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

### Task 6: Aplicar en `blog` y crear la primera licencia

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
scp docs/pocketbase/1786800000_created_licencias.js docs/pocketbase/1786800100_created_users.js blog:<ruta>/pb_migrations/
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

- [ ] **Step 7: Commit de lo que haya quedado sin commitear**

```bash
cd /Users/cristian/arkiv-api && git status --short
```

Si no hay cambios, no hay nada que commitear: esta task es despliegue y verificación.
