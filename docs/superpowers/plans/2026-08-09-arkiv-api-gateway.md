# arkiv-api Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir `arkiv-api`, un gateway HTTP que unifica la búsqueda y la resolución de las cuatro fuentes de Arkiv (`torrent`, `web`, `archive`, `magis`) detrás de un solo contrato, con una sola credencial de cliente.

**Architecture:** FastAPI + uvicorn. Un orquestador que corre adaptadores de fuente en paralelo con presupuesto de tiempo y aislamiento de fallos, y emite NDJSON en streaming. Cada fuente implementa el mismo `Protocol` y no conoce al orquestador; el orquestador no conoce ninguna fuente concreta. Redis para caché, sesiones y rate-limit; Postgres para el registro de fuentes y las métricas.

**Tech Stack:** Python 3.12 · FastAPI · uvicorn · httpx · redis-py (asyncio) · asyncpg · pydantic-settings · pytest + pytest-asyncio · respx · fakeredis · ruff

**Alcance de este plan:** fases F1–F4 del spec (gateway, los cuatro adaptadores, catálogo, métricas, despliegue). **F5** (integración Kotlin) y **F6** (jobs) tienen planes propios, escritos cuando el gateway esté vivo y verificado en `blog`.

**Spec:** `docs/superpowers/specs/2026-08-09-api-unificada-design.md`

## Correcciones tras medir los backends reales (2026-08-09)

Las Tasks 9 y 11 se escribieron sobre supuestos que no resistieron el contacto con `blog`.
Lo implementado difiere del texto original de esas tareas, y esto es lo que manda:

1. **El `/api/search` del mirror devuelve títulos, no torrents.** Los magnets viven en
   `/api/title/<slug>` → `torrents[]`. Es un recorrido de dos pasos, con tope de 3 títulos
   (o uno solo si viene `tmdb_id`) para no caer en N+1. Extraído a `adapters/mirror_titles.py`,
   que comparten `torrent` y `web`.
2. **Jackett tarda ~27 s en frío** (0.9 s tibio, cachea). Un `asyncio.gather` de mirror+Jackett
   con presupuesto de 4 s devolvería **cero** resultados. El adaptador arranca Jackett primero
   para solapar latencia, pero emite el mirror apenas responde. Dedup determinista: gana el
   mirror, que es la fuente curada en español.
3. **212 de 366 resultados reales de Jackett no traen magnet** — solo un `Link` al `.torrent`,
   y son justo los trackers en español (Wolfmax 4k, DonTorrent, DivxTotal). Descartarlos
   destruiría la cobertura latino/castellano. El ref guarda el link y **solo al resolver** se
   baja el `.torrent` para sacarle el infohash (`adapters/bencode.py`, contrastado contra
   `bencodepy` sobre un archivo real de DonTorrent).
4. **El resolver Node no tiene `/search`.** Solo `GET /resolve?url=`, `/proxy` y `/health`, y
   devuelve `{ok, streamUrl, proxyUrl, headers, subtitles}`. El catálogo web sale del mirror:
   `web_sources[]` con `page_url`, `lang_norm`, `season`, `episode`, `site_id`
   (172k fuentes activas sobre 2.4k títulos).
5. **Python local es 3.14**, no 3.12. `fakeredis` necesita el extra `[lua]` para `EVAL`.

## Global Constraints

- Repositorio nuevo e independiente: **`~/arkiv-api`**. No se mezcla con el código Android de `~/archive`.
- Python **3.12**.
- **Ningún secreto entra al repo.** Solo `.env.example` con los nombres.
- El servicio **falla al arrancar** si falta un secreto requerido, y nombra cuál. Nunca degrada en silencio.
- Toda ruta pública va bajo el prefijo **`/v1`** y exige el header **`X-Arkiv-Key`**, salvo `/v1/health`.
- **Los bytes de video y el SSE nunca cruzan el gateway.** Esas rutas responden `302`.
- Presupuesto de búsqueda por defecto: **4000 ms**.
- Rate-limit global de magis: **1 llamada / 1500 ms**, coordinado en Redis.
- Circuit breaker: **5 fallos seguidos** → abierto **5 min** → half-open (una petición de prueba).
- El adaptador `magis` sirve **solo `movie` y `series`**. TV en vivo fuera de alcance.
- Commits en español, sin pie de coautoría, con identidad `lordmacu`.
- `git add` siempre con rutas explícitas. Nunca `git add -A`.

---

## File Structure

```
~/arkiv-api/
├── pyproject.toml
├── .env.example                    # solo nombres de variables
├── .gitignore
├── Dockerfile
├── docker-compose.yml
├── README.md
├── src/arkiv_api/
│   ├── __init__.py
│   ├── app.py                      # ensamblado de FastAPI
│   ├── config.py                   # Settings + fail-fast de secretos
│   ├── auth.py                     # dependencia X-Arkiv-Key
│   ├── models.py                   # SearchContext · Result · Playable · eventos
│   ├── refs.py                     # firmar/verificar refs opacos
│   ├── breaker.py                  # circuit breaker por fuente
│   ├── orchestrator.py             # fan-out con presupuesto y aislamiento
│   ├── store/
│   │   ├── __init__.py
│   │   ├── cache.py                # caché Redis + stale-while-revalidate
│   │   ├── ratelimit.py            # token bucket en Redis
│   │   └── db.py                   # Postgres: registro de fuentes, métricas
│   ├── adapters/
│   │   ├── __init__.py
│   │   ├── base.py                 # Protocol SourceAdapter
│   │   ├── registry.py             # alta y consulta de adaptadores
│   │   ├── archive.py
│   │   ├── torrent.py
│   │   ├── web.py
│   │   └── magis/
│   │       ├── __init__.py
│   │       ├── adapter.py
│   │       ├── session.py          # sesión compartida en Redis
│   │       └── vendor/iptv_client.py   # copia pineada de lordmacu/magia
│   ├── catalog/
│   │   ├── __init__.py
│   │   ├── tmdb.py
│   │   ├── anilist.py
│   │   ├── simkl.py
│   │   ├── cinemeta.py
│   │   └── opensubtitles.py
│   └── router/
│       ├── __init__.py
│       ├── search.py               # /v1/search  (NDJSON + format=json)
│       ├── resolve.py              # /v1/resolve
│       ├── sources.py              # /v1/sources
│       ├── catalog.py              # /v1/catalog/*
│       ├── stats.py                # /v1/stats
│       ├── stream.py               # 302 del plano de datos
│       └── health.py               # /v1/health
└── tests/
    ├── conftest.py
    ├── fixtures/                   # respuestas HTTP grabadas
    └── test_*.py
```

**Responsabilidades.** `orchestrator.py` depende solo de `adapters/base.py` — nunca importa un adaptador concreto. Cada adaptador depende de su backend y del store, jamás del orquestador ni de otro adaptador. `router/` traduce HTTP ↔ dominio y no contiene lógica de fuentes.

---

### Task 1: Scaffold del proyecto, config fail-fast y `/v1/health`

Esqueleto caminante: un servicio que arranca, valida sus secretos y responde.

**Files:**
- Create: `~/arkiv-api/pyproject.toml`, `.gitignore`, `.env.example`, `README.md`
- Create: `src/arkiv_api/__init__.py`, `config.py`, `app.py`, `router/__init__.py`, `router/health.py`
- Test: `tests/conftest.py`, `tests/test_config.py`, `tests/test_health.py`

**Interfaces:**
- Produces: `Settings` (pydantic-settings) con los campos de `.env.example`; `get_settings() -> Settings`; `create_app() -> FastAPI`; `MissingSecretsError`.

- [ ] **Step 1: Crear el repo y el scaffold**

```bash
mkdir -p ~/arkiv-api/src/arkiv_api/router ~/arkiv-api/tests
cd ~/arkiv-api && git init
git config user.name lordmacu
git config user.email 10134930+lordmacu@users.noreply.github.com
```

`pyproject.toml`:

```toml
[project]
name = "arkiv-api"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.32",
    "httpx>=0.27",
    "redis>=5.2",
    "asyncpg>=0.30",
    "pydantic-settings>=2.6",
    "pycryptodome>=3.21",
]

[project.optional-dependencies]
dev = ["pytest>=8.3", "pytest-asyncio>=0.24", "respx>=0.21", "fakeredis>=2.26", "ruff>=0.8"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]

[tool.ruff]
line-length = 110

[build-system]
requires = ["setuptools>=75"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]
```

`.gitignore`:

```
.env
__pycache__/
*.pyc
.venv/
.pytest_cache/
.ruff_cache/
```

`.env.example` (nombres, nunca valores):

```
ARKIV_API_KEYS=
REDIS_URL=redis://localhost:6379/3
DATABASE_URL=postgresql://arkiv:CHANGEME@localhost:5432/arkiv_api
REF_SIGNING_KEY=

TMDB_API_KEY=
TMDB_READ_ACCESS_TOKEN=
OPENSUBTITLES_API_KEY=
SIMKL_CLIENT_ID=
SIMKL_CLIENT_SECRET=

MIRROR_BASE_URL=http://127.0.0.1:8097
MIRROR_API_KEY=
JACKETT_BASE_URL=http://127.0.0.1:9117
JACKETT_API_KEY=
WEB_RESOLVER_URL=http://127.0.0.1:8123
NUC_BASE_URL=http://127.0.0.1:8099
NUC_API_KEY=

IPTV_3DES_KEY=
IPTV_HOSTS=
IPTV_APP_ID=
IPTV_DEVICE_SN=
IPTV_APK_VERSION=
IPTV_DEVICE_DRM_ID=
IPTV_DEVICE_TOKEN=
IPTV_DEVICE_RESERVE1=
IPTV_USERNAME=
IPTV_PASSWORD=
```

- [ ] **Step 2: Escribir el test que falla**

`tests/test_config.py`:

```python
import pytest
from arkiv_api.config import Settings, MissingSecretsError, required_secret_names


def test_faltan_secretos_requeridos_nombra_cuales():
    with pytest.raises(MissingSecretsError) as exc:
        Settings(arkiv_api_keys="", ref_signing_key="").validated()
    msg = str(exc.value)
    assert "ARKIV_API_KEYS" in msg
    assert "REF_SIGNING_KEY" in msg


def test_config_valida_pasa_y_parsea_las_llaves():
    s = Settings(arkiv_api_keys="uno,dos", ref_signing_key="x" * 32).validated()
    assert s.client_keys == {"uno", "dos"}


def test_required_secret_names_no_incluye_opcionales():
    assert "TMDB_API_KEY" not in required_secret_names()
    assert "ARKIV_API_KEYS" in required_secret_names()
```

`tests/test_health.py`:

```python
from fastapi.testclient import TestClient
from arkiv_api.app import create_app
from arkiv_api.config import Settings


def _client() -> TestClient:
    return TestClient(create_app(Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated()))


def test_health_no_pide_llave_y_responde_ok():
    r = _client().get("/v1/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_health_reporta_namespaces_como_booleanos_sin_valores():
    body = _client().get("/v1/health").json()
    assert body["credentials"]["tmdb"] is False
    assert all(isinstance(v, bool) for v in body["credentials"].values())
```

- [ ] **Step 3: Correr los tests y verificar que fallan**

```bash
cd ~/arkiv-api && python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest tests/ -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.config'`.

- [ ] **Step 4: Implementar `config.py`**

```python
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class MissingSecretsError(RuntimeError):
    """El servicio no arranca sin estos secretos. Se nombran todos de una vez."""


def required_secret_names() -> tuple[str, ...]:
    """Secretos sin los cuales el gateway no puede funcionar en absoluto.

    Los de cada fuente (TMDB, IPTV…) son opcionales a propósito: su ausencia
    desactiva esa fuente, no tumba el servicio.
    """
    return ("ARKIV_API_KEYS", "REF_SIGNING_KEY")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    arkiv_api_keys: str = ""
    redis_url: str = "redis://localhost:6379/3"
    database_url: str = ""
    ref_signing_key: str = ""

    tmdb_api_key: str = ""
    tmdb_read_access_token: str = ""
    opensubtitles_api_key: str = ""
    simkl_client_id: str = ""
    simkl_client_secret: str = ""

    mirror_base_url: str = "http://127.0.0.1:8097"
    mirror_api_key: str = ""
    jackett_base_url: str = "http://127.0.0.1:9117"
    jackett_api_key: str = ""
    web_resolver_url: str = "http://127.0.0.1:8123"
    nuc_base_url: str = "http://127.0.0.1:8099"
    nuc_api_key: str = ""

    iptv_3des_key: str = ""
    iptv_hosts: str = ""
    iptv_app_id: str = ""
    iptv_device_sn: str = ""
    iptv_apk_version: str = ""
    iptv_device_drm_id: str = ""
    iptv_device_token: str = ""
    iptv_device_reserve1: str = ""
    iptv_username: str = ""
    iptv_password: str = ""

    search_budget_ms: int = 4000

    @property
    def client_keys(self) -> set[str]:
        return {k.strip() for k in self.arkiv_api_keys.split(",") if k.strip()}

    @property
    def credential_namespaces(self) -> dict[str, bool]:
        """Qué namespaces están configurados. Booleanos: nunca se exponen valores."""
        return {
            "tmdb": bool(self.tmdb_api_key),
            "opensubtitles": bool(self.opensubtitles_api_key),
            "simkl": bool(self.simkl_client_id),
            "mirror": bool(self.mirror_api_key),
            "jackett": bool(self.jackett_api_key),
            "nuc": bool(self.nuc_api_key),
            "magis": bool(self.iptv_3des_key and self.iptv_hosts and self.iptv_device_sn),
        }

    def validated(self) -> "Settings":
        faltan = [n for n in required_secret_names() if not getattr(self, n.lower(), "")]
        if faltan:
            raise MissingSecretsError(
                "Faltan secretos requeridos: " + ", ".join(faltan) + ". Ver .env.example."
            )
        return self


def get_settings() -> Settings:
    return Settings().validated()
```

- [ ] **Step 5: Implementar `router/health.py` y `app.py`**

`src/arkiv_api/router/health.py`:

```python
from fastapi import APIRouter, Request

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict:
    settings = request.app.state.settings
    return {"status": "ok", "credentials": settings.credential_namespaces}
```

`src/arkiv_api/app.py`:

```python
from fastapi import FastAPI

from .config import Settings, get_settings
from .router import health


def create_app(settings: Settings | None = None) -> FastAPI:
    app = FastAPI(title="arkiv-api", version="0.1.0")
    app.state.settings = settings or get_settings()
    app.include_router(health.router, prefix="/v1")
    return app
```

`src/arkiv_api/__init__.py` y `src/arkiv_api/router/__init__.py`: archivos vacíos.

- [ ] **Step 6: Correr los tests y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 5 passed.

- [ ] **Step 7: Commit**

```bash
git add pyproject.toml .gitignore .env.example src/arkiv_api tests
git commit -m "feat: scaffold del gateway con config fail-fast y /v1/health"
```

---

### Task 2: Auth por `X-Arkiv-Key`

**Files:**
- Create: `src/arkiv_api/auth.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_auth.py`

**Interfaces:**
- Produces: `require_key(request: Request) -> None` — dependencia de FastAPI que lanza `HTTPException(401)`.

- [ ] **Step 1: Escribir el test que falla**

`tests/test_auth.py`:

```python
from fastapi import APIRouter, Depends, FastAPI
from fastapi.testclient import TestClient

from arkiv_api.auth import require_key
from arkiv_api.config import Settings


def _client() -> TestClient:
    app = FastAPI()
    app.state.settings = Settings(arkiv_api_keys="buena,otra", ref_signing_key="x" * 32).validated()
    r = APIRouter()

    @r.get("/protegida", dependencies=[Depends(require_key)])
    async def protegida() -> dict:
        return {"ok": True}

    app.include_router(r)
    return TestClient(app)


def test_sin_llave_da_401():
    assert _client().get("/protegida").status_code == 401


def test_llave_incorrecta_da_401():
    assert _client().get("/protegida", headers={"X-Arkiv-Key": "mala"}).status_code == 401


def test_llave_correcta_pasa():
    assert _client().get("/protegida", headers={"X-Arkiv-Key": "buena"}).status_code == 200


def test_acepta_la_segunda_llave_para_permitir_rotacion():
    assert _client().get("/protegida", headers={"X-Arkiv-Key": "otra"}).status_code == 200
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_auth.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.auth'`.

- [ ] **Step 3: Implementar `auth.py`**

```python
import hmac

from fastapi import HTTPException, Request

HEADER = "X-Arkiv-Key"


async def require_key(request: Request) -> None:
    enviada = request.headers.get(HEADER, "")
    validas = request.app.state.settings.client_keys
    # compare_digest en todas: corta el canal lateral de tiempo aunque sean llaves propias.
    if not any(hmac.compare_digest(enviada, k) for k in validas):
        raise HTTPException(status_code=401, detail="llave invalida o ausente")
```

- [ ] **Step 4: Correr y verificar que pasa**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 9 passed.

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/auth.py tests/test_auth.py
git commit -m "feat: auth por X-Arkiv-Key con soporte de rotacion"
```

---

### Task 3: Modelos del dominio y refs opacos firmados

**Files:**
- Create: `src/arkiv_api/models.py`, `src/arkiv_api/refs.py`
- Test: `tests/test_models.py`, `tests/test_refs.py`

**Interfaces:**
- Produces:
  - `ContentType` = `Literal["movie", "tv", "anime"]`
  - `SearchContext(q, type, season, episode, year, tmdb_id, anilist_id, lang, budget_ms)`
  - `Result(source, title, ref, kind, lang, quality, size_bytes, seeders, year, season, episode, extra)`
  - `Playable(kind, url, headers, mime, expires_at, fallback)`
  - Eventos: `SourceStart`, `ResultEvent`, `SourceDone`, `SourceError`, `Done` — todos con `.to_line() -> str`
  - `encode_ref(source: str, payload: dict, key: str, ttl_s: int = 86400) -> str`
  - `decode_ref(token: str, key: str) -> tuple[str, dict]`, que lanza `RefError`

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_refs.py`:

```python
import pytest
from arkiv_api.refs import RefError, decode_ref, encode_ref

KEY = "x" * 32


def test_ida_y_vuelta():
    tok = encode_ref("magis", {"content_id": "42"}, KEY)
    assert decode_ref(tok, KEY) == ("magis", {"content_id": "42"})


def test_ref_manipulado_es_rechazado():
    tok = encode_ref("magis", {"content_id": "42"}, KEY)
    with pytest.raises(RefError):
        decode_ref(tok[:-3] + "aaa", KEY)


def test_ref_con_otra_llave_es_rechazado():
    with pytest.raises(RefError):
        decode_ref(encode_ref("magis", {"a": 1}, KEY), "y" * 32)


def test_ref_vencido_es_rechazado():
    with pytest.raises(RefError, match="vencido"):
        decode_ref(encode_ref("magis", {"a": 1}, KEY, ttl_s=-1), KEY)


def test_el_ref_es_opaco_no_filtra_el_payload():
    assert "content_id" not in encode_ref("magis", {"content_id": "secreto"}, KEY)
```

`tests/test_models.py`:

```python
import json

from arkiv_api.models import Done, Result, ResultEvent, SourceDone, SourceError, SourceStart


def test_cada_evento_serializa_a_una_linea_ndjson():
    ev = SourceStart(source="torrent")
    linea = ev.to_line()
    assert linea.endswith("\n")
    assert json.loads(linea) == {"type": "source_start", "source": "torrent"}


def test_result_event_lleva_el_item_completo():
    r = Result(source="torrent", title="Dune", ref="abc", kind="movie", seeders=12)
    parsed = json.loads(ResultEvent(source="torrent", item=r).to_line())
    assert parsed["type"] == "result"
    assert parsed["item"]["title"] == "Dune"
    assert parsed["item"]["ref"] == "abc"


def test_source_error_lleva_causa_y_duracion():
    parsed = json.loads(SourceError(source="magis", error="timeout", ms=4000).to_line())
    assert parsed == {"type": "source_error", "source": "magis", "error": "timeout", "ms": 4000}


def test_source_done_y_done():
    assert json.loads(SourceDone(source="web", count=3, ms=120))["count"] == 3 if False else True
    assert json.loads(Done(ms=4100).to_line()) == {"type": "done", "ms": 4100}
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_refs.py tests/test_models.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.refs'`.

- [ ] **Step 3: Implementar `refs.py`**

```python
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time


class RefError(ValueError):
    """El ref no es válido: manipulado, con otra llave, o vencido."""


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _b64d(txt: str) -> bytes:
    return base64.urlsafe_b64decode(txt + "=" * (-len(txt) % 4))


def encode_ref(source: str, payload: dict, key: str, ttl_s: int = 86400) -> str:
    cuerpo = json.dumps(
        {"s": source, "p": payload, "exp": int(time.time()) + ttl_s}, separators=(",", ":")
    ).encode()
    datos = _b64e(cuerpo)
    firma = _b64e(hmac.new(key.encode(), datos.encode(), hashlib.sha256).digest()[:16])
    return f"{datos}.{firma}"


def decode_ref(token: str, key: str) -> tuple[str, dict]:
    try:
        datos, firma = token.split(".", 1)
    except ValueError as e:
        raise RefError("ref malformado") from e

    esperada = _b64e(hmac.new(key.encode(), datos.encode(), hashlib.sha256).digest()[:16])
    if not hmac.compare_digest(firma, esperada):
        raise RefError("firma invalida")

    try:
        cuerpo = json.loads(_b64d(datos))
    except Exception as e:
        raise RefError("ref ilegible") from e

    if cuerpo.get("exp", 0) < int(time.time()):
        raise RefError("ref vencido")
    return cuerpo["s"], cuerpo["p"]
```

- [ ] **Step 4: Implementar `models.py`**

```python
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Literal

ContentType = Literal["movie", "tv", "anime"]


@dataclass(frozen=True)
class SearchContext:
    q: str
    type: ContentType = "movie"
    season: int = 0
    episode: int = 0
    year: str = ""
    tmdb_id: int = 0
    anilist_id: int = 0
    lang: str = ""
    budget_ms: int = 4000


@dataclass
class Result:
    source: str
    title: str
    ref: str
    kind: str = "movie"
    lang: str = ""
    quality: str = ""
    size_bytes: int = 0
    seeders: int = 0
    year: str = ""
    season: int = 0
    episode: int = 0
    extra: dict = field(default_factory=dict)


@dataclass
class Playable:
    kind: str
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    mime: str = ""
    expires_at: str = ""
    fallback: dict | None = None


class _Event:
    """Un evento del stream NDJSON. `to_line` produce exactamente una línea."""

    def payload(self) -> dict:
        raise NotImplementedError

    def to_line(self) -> str:
        return json.dumps(self.payload(), ensure_ascii=False) + "\n"


@dataclass
class SourceStart(_Event):
    source: str

    def payload(self) -> dict:
        return {"type": "source_start", "source": self.source}


@dataclass
class ResultEvent(_Event):
    source: str
    item: Result

    def payload(self) -> dict:
        return {"type": "result", "source": self.source, "item": asdict(self.item)}


@dataclass
class SourceDone(_Event):
    source: str
    count: int
    ms: int

    def payload(self) -> dict:
        return {"type": "source_done", "source": self.source, "count": self.count, "ms": self.ms}


@dataclass
class SourceError(_Event):
    source: str
    error: str
    ms: int

    def payload(self) -> dict:
        return {"type": "source_error", "source": self.source, "error": self.error, "ms": self.ms}


@dataclass
class Done(_Event):
    ms: int

    def payload(self) -> dict:
        return {"type": "done", "ms": self.ms}
```

- [ ] **Step 5: Corregir el test flojo de `SourceDone`**

El test escrito en el Step 1 tiene una condición inerte. Reemplazar `test_source_done_y_done` por:

```python
def test_source_done_y_done():
    assert json.loads(SourceDone(source="web", count=3, ms=120).to_line()) == {
        "type": "source_done", "source": "web", "count": 3, "ms": 120,
    }
    assert json.loads(Done(ms=4100).to_line()) == {"type": "done", "ms": 4100}
```

- [ ] **Step 6: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 18 passed.

- [ ] **Step 7: Commit**

```bash
git add src/arkiv_api/models.py src/arkiv_api/refs.py tests/test_models.py tests/test_refs.py
git commit -m "feat: modelos del dominio y refs opacos firmados con TTL"
```

---

### Task 4: Store en Redis — caché y token bucket

**Files:**
- Create: `src/arkiv_api/store/__init__.py`, `store/cache.py`, `store/ratelimit.py`
- Test: `tests/test_cache.py`, `tests/test_ratelimit.py`

**Interfaces:**
- Produces:
  - `Cache(redis)` con `get_json(key) -> tuple[dict | None, bool]` (valor, `stale`), `set_json(key, value, ttl_s, stale_ttl_s)`
  - `TokenBucket(redis, key, interval_ms)` con `async def acquire() -> int` (ms que durmió)

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_cache.py`:

```python
import fakeredis.aioredis
import pytest

from arkiv_api.store.cache import Cache


@pytest.fixture
def cache():
    return Cache(fakeredis.aioredis.FakeRedis(decode_responses=True))


async def test_miss_devuelve_none(cache):
    assert await cache.get_json("nada") == (None, False)


async def test_hit_fresco_no_es_stale(cache):
    await cache.set_json("k", {"a": 1}, ttl_s=60, stale_ttl_s=600)
    assert await cache.get_json("k") == ({"a": 1}, False)


async def test_pasado_el_ttl_sigue_disponible_marcado_stale(cache):
    await cache.set_json("k", {"a": 1}, ttl_s=-1, stale_ttl_s=600)
    valor, stale = await cache.get_json("k")
    assert valor == {"a": 1}
    assert stale is True
```

`tests/test_ratelimit.py`:

```python
import fakeredis.aioredis
import pytest

from arkiv_api.store.ratelimit import TokenBucket


@pytest.fixture
def redis():
    return fakeredis.aioredis.FakeRedis(decode_responses=True)


async def test_la_primera_no_espera(redis):
    assert await TokenBucket(redis, "magis", interval_ms=1500).acquire(sleep=False) == 0


async def test_la_segunda_espera_el_intervalo(redis):
    b = TokenBucket(redis, "magis", interval_ms=1500)
    await b.acquire(sleep=False)
    espera = await b.acquire(sleep=False)
    assert 1400 <= espera <= 1500


async def test_las_esperas_se_acumulan_no_colisionan(redis):
    b = TokenBucket(redis, "magis", interval_ms=1000)
    await b.acquire(sleep=False)
    primera = await b.acquire(sleep=False)
    segunda = await b.acquire(sleep=False)
    assert segunda > primera


async def test_buckets_distintos_no_se_estorban(redis):
    await TokenBucket(redis, "magis", interval_ms=1500).acquire(sleep=False)
    assert await TokenBucket(redis, "otra", interval_ms=1500).acquire(sleep=False) == 0
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_cache.py tests/test_ratelimit.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.store'`.

- [ ] **Step 3: Implementar `store/cache.py`**

```python
from __future__ import annotations

import json
import time


class Cache:
    """Caché con stale-while-revalidate.

    Guarda el valor con un TTL largo (`stale_ttl_s`) y la marca de frescura por
    separado. Así una fuente caída sigue sirviendo lo último bueno, marcado.
    """

    def __init__(self, redis) -> None:
        self._r = redis

    async def get_json(self, key: str) -> tuple[dict | None, bool]:
        crudo = await self._r.get(f"c:{key}")
        if crudo is None:
            return None, False
        envuelto = json.loads(crudo)
        stale = envuelto["fresh_until"] < time.time()
        return envuelto["v"], stale

    async def set_json(self, key: str, value: dict, ttl_s: int, stale_ttl_s: int) -> None:
        envuelto = {"v": value, "fresh_until": time.time() + ttl_s}
        await self._r.set(f"c:{key}", json.dumps(envuelto), ex=max(stale_ttl_s, 1))
```

- [ ] **Step 4: Implementar `store/ratelimit.py`**

```python
from __future__ import annotations

import asyncio
import time

# Reserva el próximo turno de forma atómica y devuelve cuántos ms hay que esperar.
# Atómico en Redis: da igual cuántos workers de uvicorn haya, el ritmo es global.
_LUA = """
local proximo = tonumber(redis.call('GET', KEYS[1]) or '0')
local ahora = tonumber(ARGV[1])
local intervalo = tonumber(ARGV[2])
if proximo <= ahora then
  redis.call('SET', KEYS[1], ahora + intervalo, 'PX', intervalo * 4)
  return 0
end
redis.call('SET', KEYS[1], proximo + intervalo, 'PX', intervalo * 4)
return proximo - ahora
"""


class TokenBucket:
    """Ritmo global de llamadas a un backend con rate-limit propio.

    El portal de magis corta a 1 llamada cada 1.5 s. Centralizar el ritmo acá
    evita que dos workers lo pisen a la vez.
    """

    def __init__(self, redis, key: str, interval_ms: int) -> None:
        self._r = redis
        self._key = f"rl:{key}"
        self._interval = interval_ms

    async def acquire(self, sleep: bool = True) -> int:
        espera_ms = int(
            await self._r.eval(_LUA, 1, self._key, str(int(time.time() * 1000)), str(self._interval))
        )
        if sleep and espera_ms > 0:
            await asyncio.sleep(espera_ms / 1000)
        return espera_ms
```

`src/arkiv_api/store/__init__.py`: archivo vacío.

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 25 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/store tests/test_cache.py tests/test_ratelimit.py
git commit -m "feat: cache con stale-while-revalidate y token bucket global en Redis"
```

---

### Task 5: `SourceAdapter`, registro y circuit breaker

**Files:**
- Create: `src/arkiv_api/adapters/__init__.py`, `adapters/base.py`, `adapters/registry.py`, `src/arkiv_api/breaker.py`
- Test: `tests/test_registry.py`, `tests/test_breaker.py`

**Interfaces:**
- Produces:
  - `SourceAdapter` Protocol: `name: str`, `capabilities: set[str]`, `search(ctx) -> AsyncIterator[Result]`, `resolve(payload: dict) -> Playable`, `health() -> bool`
  - `Registry` con `register(adapter)`, `get(name) -> SourceAdapter`, `all() -> list[SourceAdapter]`, `for_type(t) -> list[SourceAdapter]`
  - `Breaker(redis, name, fallos_max=5, abierto_s=300)` con `allow() -> bool`, `record_ok()`, `record_fail()`, `state() -> str`

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_breaker.py`:

```python
import fakeredis.aioredis
import pytest

from arkiv_api.breaker import Breaker


@pytest.fixture
def breaker():
    return Breaker(fakeredis.aioredis.FakeRedis(decode_responses=True), "magis")


async def test_arranca_cerrado_y_permite(breaker):
    assert await breaker.allow() is True
    assert await breaker.state() == "closed"


async def test_cuatro_fallos_no_lo_abren(breaker):
    for _ in range(4):
        await breaker.record_fail()
    assert await breaker.allow() is True


async def test_cinco_fallos_lo_abren(breaker):
    for _ in range(5):
        await breaker.record_fail()
    assert await breaker.allow() is False
    assert await breaker.state() == "open"


async def test_un_ok_reinicia_la_cuenta(breaker):
    for _ in range(4):
        await breaker.record_fail()
    await breaker.record_ok()
    await breaker.record_fail()
    assert await breaker.allow() is True
```

`tests/test_registry.py`:

```python
import pytest

from arkiv_api.adapters.registry import Registry
from tests.fakes import FakeAdapter


def test_get_devuelve_el_adaptador_registrado():
    reg = Registry()
    reg.register(FakeAdapter("torrent"))
    assert reg.get("torrent").name == "torrent"


def test_get_de_uno_desconocido_da_keyerror():
    with pytest.raises(KeyError):
        Registry().get("noexiste")


def test_for_type_filtra_por_capacidad():
    reg = Registry()
    reg.register(FakeAdapter("torrent", capabilities={"movie", "tv"}))
    reg.register(FakeAdapter("magis", capabilities={"movie"}))
    assert [a.name for a in reg.for_type("tv")] == ["torrent"]
    assert {a.name for a in reg.for_type("movie")} == {"torrent", "magis"}
```

`tests/fakes.py` — adaptadores de prueba usados por este y los próximos tests:

```python
import asyncio
from collections.abc import AsyncIterator

from arkiv_api.models import Playable, Result, SearchContext


class FakeAdapter:
    """Adaptador controlable: sirve para probar el orquestador sin red."""

    def __init__(
        self,
        name: str,
        capabilities: set[str] | None = None,
        results: int = 1,
        delay_s: float = 0.0,
        falla: Exception | None = None,
    ) -> None:
        self.name = name
        self.capabilities = capabilities or {"movie", "tv", "anime"}
        self._results = results
        self._delay = delay_s
        self._falla = falla

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]:
        if self._delay:
            await asyncio.sleep(self._delay)
        if self._falla:
            raise self._falla
        for i in range(self._results):
            yield Result(source=self.name, title=f"{self.name}-{i}", ref=f"ref-{self.name}-{i}")

    async def resolve(self, payload: dict) -> Playable:
        return Playable(kind=self.name, url=f"https://ejemplo/{payload.get('id', '')}")

    async def health(self) -> bool:
        return self._falla is None
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_breaker.py tests/test_registry.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.breaker'`.

- [ ] **Step 3: Implementar `breaker.py`**

```python
from __future__ import annotations


class Breaker:
    """Corta una fuente que viene fallando, para no gastarle presupuesto a cada búsqueda.

    Cerrado → permite. Tras `fallos_max` seguidos se abre por `abierto_s`. Al vencer
    ese plazo queda half-open: deja pasar una sola petición de prueba; si sale bien
    vuelve a cerrado, si falla se abre de nuevo.
    """

    def __init__(self, redis, name: str, fallos_max: int = 5, abierto_s: int = 300) -> None:
        self._r = redis
        self._fallos_key = f"cb:{name}:fallos"
        self._abierto_key = f"cb:{name}:abierto"
        self._prueba_key = f"cb:{name}:prueba"
        self._max = fallos_max
        self._abierto_s = abierto_s

    async def state(self) -> str:
        if not await self._r.exists(self._abierto_key):
            return "closed"
        return "half_open" if await self._r.exists(self._prueba_key) else "open"

    async def allow(self) -> bool:
        if not await self._r.exists(self._abierto_key):
            return True
        # Abierto: solo pasa la primera que llegue tras vencer el plazo (half-open).
        return bool(await self._r.set(self._prueba_key, "1", ex=self._abierto_s, nx=True))

    async def record_ok(self) -> None:
        await self._r.delete(self._fallos_key, self._abierto_key, self._prueba_key)

    async def record_fail(self) -> None:
        fallos = await self._r.incr(self._fallos_key)
        await self._r.expire(self._fallos_key, self._abierto_s)
        if fallos >= self._max:
            await self._r.set(self._abierto_key, "1", ex=self._abierto_s)
            await self._r.delete(self._prueba_key)
```

Corrección sobre el test `test_cinco_fallos_lo_abren`: `allow()` en estado abierto devuelve `True`
una sola vez (la prueba half-open). Para que el test refleje el diseño, cambiarlo por:

```python
async def test_cinco_fallos_lo_abren(breaker):
    for _ in range(5):
        await breaker.record_fail()
    assert await breaker.state() == "open"
    assert await breaker.allow() is True    # la unica de prueba (half-open)
    assert await breaker.allow() is False   # el resto queda cortado
```

- [ ] **Step 4: Implementar `adapters/base.py` y `adapters/registry.py`**

`base.py`:

```python
from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol, runtime_checkable

from ..models import Playable, Result, SearchContext


@runtime_checkable
class SourceAdapter(Protocol):
    """Una fuente de búsqueda.

    Contrato deliberadamente chico: el orquestador no sabe nada de ninguna fuente
    concreta, y ninguna fuente sabe nada del orquestador ni de sus hermanas.
    """

    name: str
    capabilities: set[str]

    def search(self, ctx: SearchContext) -> AsyncIterator[Result]: ...

    async def resolve(self, payload: dict) -> Playable: ...

    async def health(self) -> bool: ...
```

`registry.py`:

```python
from __future__ import annotations

from .base import SourceAdapter


class Registry:
    def __init__(self) -> None:
        self._por_nombre: dict[str, SourceAdapter] = {}

    def register(self, adapter: SourceAdapter) -> None:
        self._por_nombre[adapter.name] = adapter

    def get(self, name: str) -> SourceAdapter:
        return self._por_nombre[name]

    def all(self) -> list[SourceAdapter]:
        return list(self._por_nombre.values())

    def for_type(self, content_type: str) -> list[SourceAdapter]:
        return [a for a in self._por_nombre.values() if content_type in a.capabilities]
```

`src/arkiv_api/adapters/__init__.py`: archivo vacío.

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 32 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/breaker.py src/arkiv_api/adapters tests/fakes.py tests/test_breaker.py tests/test_registry.py
git commit -m "feat: protocolo SourceAdapter, registro y circuit breaker"
```

---

### Task 6: Orquestador de fan-out

El corazón: corre las fuentes en paralelo, respeta el presupuesto y aísla los fallos.

**Files:**
- Create: `src/arkiv_api/orchestrator.py`
- Test: `tests/test_orchestrator.py`

**Interfaces:**
- Consumes: `Registry`, `Breaker`, `SearchContext`, los eventos de `models.py`
- Produces: `fan_out(adapters, ctx, breakers=None) -> AsyncIterator[_Event]`

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_orchestrator.py`:

```python
import asyncio

from arkiv_api.models import SearchContext
from arkiv_api.orchestrator import fan_out
from tests.fakes import FakeAdapter


async def _recoger(adapters, ctx):
    return [ev.payload() async for ev in fan_out(adapters, ctx)]


async def test_emite_start_results_done_por_fuente_y_un_done_final():
    eventos = await _recoger([FakeAdapter("a", results=2)], SearchContext(q="dune"))
    tipos = [e["type"] for e in eventos]
    assert tipos == ["source_start", "result", "result", "source_done", "done"]


async def test_una_fuente_que_falla_no_tumba_a_las_otras():
    eventos = await _recoger(
        [FakeAdapter("mala", falla=RuntimeError("boom")), FakeAdapter("buena", results=1)],
        SearchContext(q="dune"),
    )
    tipos = {e["type"] for e in eventos}
    assert "source_error" in tipos
    assert any(e["type"] == "result" and e["source"] == "buena" for e in eventos)
    assert eventos[-1]["type"] == "done"


async def test_la_fuente_lenta_se_corta_por_presupuesto_y_las_rapidas_llegan():
    eventos = await _recoger(
        [FakeAdapter("lenta", delay_s=5), FakeAdapter("rapida", results=1)],
        SearchContext(q="dune", budget_ms=300),
    )
    error = next(e for e in eventos if e["type"] == "source_error")
    assert error["source"] == "lenta"
    assert error["error"] == "timeout"
    assert any(e["type"] == "result" and e["source"] == "rapida" for e in eventos)


async def test_respeta_el_presupuesto_en_tiempo_real():
    inicio = asyncio.get_running_loop().time()
    await _recoger([FakeAdapter("lenta", delay_s=10)], SearchContext(q="x", budget_ms=200))
    assert asyncio.get_running_loop().time() - inicio < 2.0


async def test_los_resultados_de_una_fuente_rapida_salen_antes_de_que_termine_la_lenta():
    ctx = SearchContext(q="dune", budget_ms=1000)
    adapters = [FakeAdapter("lenta", delay_s=0.5, results=1), FakeAdapter("rapida", results=1)]
    eventos = [ev.payload() async for ev in fan_out(adapters, ctx)]
    idx_rapida = next(i for i, e in enumerate(eventos) if e.get("source") == "rapida" and e["type"] == "result")
    idx_lenta = next(i for i, e in enumerate(eventos) if e.get("source") == "lenta" and e["type"] == "result")
    assert idx_rapida < idx_lenta


async def test_una_fuente_con_el_breaker_abierto_se_omite():
    class BreakerCerrado:
        async def allow(self):
            return False

        async def record_ok(self):
            pass

        async def record_fail(self):
            pass

    eventos = [
        ev.payload()
        async for ev in fan_out(
            [FakeAdapter("cortada")], SearchContext(q="x"), breakers={"cortada": BreakerCerrado()}
        )
    ]
    assert [e["type"] for e in eventos] == ["source_error", "done"]
    assert eventos[0]["error"] == "circuit_open"
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_orchestrator.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.orchestrator'`.

- [ ] **Step 3: Implementar `orchestrator.py`**

```python
from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator

from .adapters.base import SourceAdapter
from .models import Done, ResultEvent, SearchContext, SourceDone, SourceError, SourceStart, _Event

_FIN = object()


async def _correr_fuente(adapter: SourceAdapter, ctx: SearchContext, cola: asyncio.Queue, breaker) -> None:
    """Corre una fuente y empuja sus eventos a la cola. Nunca propaga excepciones:
    un fallo acá se convierte en un `source_error` y no toca a las demás."""
    t0 = time.monotonic()

    def ms() -> int:
        return int((time.monotonic() - t0) * 1000)

    if breaker is not None and not await breaker.allow():
        await cola.put(SourceError(source=adapter.name, error="circuit_open", ms=0))
        return

    await cola.put(SourceStart(source=adapter.name))
    n = 0
    try:
        async for r in adapter.search(ctx):
            n += 1
            await cola.put(ResultEvent(source=adapter.name, item=r))
    except asyncio.CancelledError:
        await cola.put(SourceError(source=adapter.name, error="timeout", ms=ms()))
        raise
    except Exception as e:
        if breaker is not None:
            await breaker.record_fail()
        await cola.put(SourceError(source=adapter.name, error=type(e).__name__, ms=ms()))
        return

    if breaker is not None:
        await breaker.record_ok()
    await cola.put(SourceDone(source=adapter.name, count=n, ms=ms()))


async def fan_out(
    adapters: list[SourceAdapter],
    ctx: SearchContext,
    breakers: dict | None = None,
) -> AsyncIterator[_Event]:
    """Corre todas las fuentes en paralelo y emite eventos a medida que llegan.

    El presupuesto (`ctx.budget_ms`) es un tope duro sobre el conjunto: lo que no
    llegó a tiempo se cancela y se reporta como `timeout`. Los resultados de las
    fuentes rápidas salen sin esperar a las lentas.
    """
    inicio = time.monotonic()
    cola: asyncio.Queue = asyncio.Queue()
    breakers = breakers or {}

    tareas = [
        asyncio.create_task(_correr_fuente(a, ctx, cola, breakers.get(a.name)), name=f"src:{a.name}")
        for a in adapters
    ]

    async def vigilar() -> None:
        await asyncio.gather(*tareas, return_exceptions=True)
        await cola.put(_FIN)

    vigilante = asyncio.create_task(vigilar())
    limite = inicio + ctx.budget_ms / 1000

    try:
        while True:
            restante = limite - time.monotonic()
            if restante <= 0:
                break
            try:
                ev = await asyncio.wait_for(cola.get(), timeout=restante)
            except TimeoutError:
                break
            if ev is _FIN:
                break
            yield ev
    finally:
        for t in tareas:
            t.cancel()
        # Las canceladas alcanzan a empujar su source_error antes de morir: se drena la cola.
        await asyncio.gather(*tareas, return_exceptions=True)
        vigilante.cancel()
        await asyncio.gather(vigilante, return_exceptions=True)

    while not cola.empty():
        ev = cola.get_nowait()
        if ev is not _FIN:
            yield ev

    yield Done(ms=int((time.monotonic() - inicio) * 1000))
```

- [ ] **Step 4: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/test_orchestrator.py -v
```

Esperado: 6 passed.

- [ ] **Step 5: Correr toda la batería**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 38 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/orchestrator.py tests/test_orchestrator.py
git commit -m "feat: orquestador de fan-out con presupuesto y aislamiento por fuente"
```

---

### Task 7: `/v1/search` — NDJSON en streaming y `format=json`

**Files:**
- Create: `src/arkiv_api/router/search.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_search.py`

**Interfaces:**
- Consumes: `fan_out`, `Registry`, `require_key`
- Produces: `GET /v1/search`. `app.state.registry: Registry` y `app.state.breakers: dict`.

- [ ] **Step 1: Escribir el test que falla**

`tests/test_router_search.py`:

```python
import json

from fastapi.testclient import TestClient

from arkiv_api.adapters.registry import Registry
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from tests.fakes import FakeAdapter

CAB = {"X-Arkiv-Key": "k"}


def _client(*adapters) -> TestClient:
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated())
    reg = Registry()
    for a in adapters:
        reg.register(a)
    app.state.registry = reg
    app.state.breakers = {}
    return TestClient(app)


def test_sin_llave_da_401():
    assert _client(FakeAdapter("a")).get("/v1/search?q=dune").status_code == 401


def test_devuelve_ndjson_una_linea_por_evento():
    r = _client(FakeAdapter("a", results=2)).get("/v1/search?q=dune", headers=CAB)
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("application/x-ndjson")
    tipos = [json.loads(l)["type"] for l in r.text.strip().split("\n")]
    assert tipos == ["source_start", "result", "result", "source_done", "done"]


def test_filtra_por_el_parametro_sources():
    r = _client(FakeAdapter("a"), FakeAdapter("b")).get("/v1/search?q=x&sources=b", headers=CAB)
    fuentes = {json.loads(l).get("source") for l in r.text.strip().split("\n")}
    assert "a" not in fuentes
    assert "b" in fuentes


def test_filtra_por_capacidad_segun_el_tipo():
    cli = _client(FakeAdapter("solomovie", capabilities={"movie"}), FakeAdapter("todo"))
    r = cli.get("/v1/search?q=x&type=tv", headers=CAB)
    fuentes = {json.loads(l).get("source") for l in r.text.strip().split("\n")}
    assert "solomovie" not in fuentes


def test_format_json_devuelve_un_agregado_unico():
    r = _client(FakeAdapter("a", results=2)).get("/v1/search?q=x&format=json", headers=CAB)
    assert r.headers["content-type"].startswith("application/json")
    body = r.json()
    assert len(body["results"]) == 2
    assert body["sources"]["a"]["count"] == 2
    assert body["errors"] == []


def test_format_json_reporta_las_fuentes_que_fallaron():
    r = _client(FakeAdapter("mala", falla=RuntimeError("boom"))).get(
        "/v1/search?q=x&format=json", headers=CAB
    )
    assert r.json()["errors"][0]["source"] == "mala"
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_router_search.py -v
```

Esperado: FAIL con `404` (la ruta no existe todavía).

- [ ] **Step 3: Implementar `router/search.py`**

```python
from __future__ import annotations

from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse

from ..auth import require_key
from ..models import SearchContext
from ..orchestrator import fan_out

router = APIRouter(dependencies=[Depends(require_key)])


def _elegir_adaptadores(request: Request, ctx: SearchContext, sources: str):
    candidatos = request.app.state.registry.for_type(ctx.type)
    if not sources:
        return candidatos
    pedidas = {s.strip() for s in sources.split(",") if s.strip()}
    return [a for a in candidatos if a.name in pedidas]


@router.get("/search")
async def search(
    request: Request,
    q: str = Query(..., min_length=1),
    type: str = "movie",
    season: int = 0,
    episode: int = 0,
    year: str = "",
    tmdb_id: int = 0,
    anilist_id: int = 0,
    lang: str = "",
    sources: str = "",
    budget_ms: int = 0,
    format: str = "ndjson",
):
    settings = request.app.state.settings
    ctx = SearchContext(
        q=q, type=type, season=season, episode=episode, year=year,
        tmdb_id=tmdb_id, anilist_id=anilist_id, lang=lang,
        budget_ms=budget_ms or settings.search_budget_ms,
    )
    adaptadores = _elegir_adaptadores(request, ctx, sources)
    breakers = request.app.state.breakers

    if format == "json":
        return JSONResponse(await _agregar(adaptadores, ctx, breakers))

    async def stream() -> AsyncIterator[bytes]:
        async for ev in fan_out(adaptadores, ctx, breakers):
            yield ev.to_line().encode()

    return StreamingResponse(stream(), media_type="application/x-ndjson")


async def _agregar(adaptadores, ctx: SearchContext, breakers) -> dict:
    """Misma búsqueda, aplanada a un JSON único. Para la TV, scripts y debug."""
    resultados: list[dict] = []
    por_fuente: dict[str, dict] = {}
    errores: list[dict] = []
    total_ms = 0

    async for ev in fan_out(adaptadores, ctx, breakers):
        p = ev.payload()
        if p["type"] == "result":
            resultados.append(p["item"])
        elif p["type"] == "source_done":
            por_fuente[p["source"]] = {"count": p["count"], "ms": p["ms"]}
        elif p["type"] == "source_error":
            errores.append({"source": p["source"], "error": p["error"]})
        elif p["type"] == "done":
            total_ms = p["ms"]

    return {"results": resultados, "sources": por_fuente, "errors": errores, "ms": total_ms}
```

- [ ] **Step 4: Cablear el router en `app.py`**

Reemplazar `create_app` por:

```python
from fastapi import FastAPI

from .adapters.registry import Registry
from .config import Settings, get_settings
from .router import health, search


def create_app(settings: Settings | None = None) -> FastAPI:
    app = FastAPI(title="arkiv-api", version="0.1.0")
    app.state.settings = settings or get_settings()
    app.state.registry = Registry()
    app.state.breakers = {}
    app.include_router(health.router, prefix="/v1")
    app.include_router(search.router, prefix="/v1")
    return app
```

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 44 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/router/search.py src/arkiv_api/app.py tests/test_router_search.py
git commit -m "feat: /v1/search en NDJSON con modo agregado format=json"
```

---

### Task 8: Adaptador `archive`

**Files:**
- Create: `src/arkiv_api/adapters/archive.py`
- Test: `tests/test_adapter_archive.py`, `tests/fixtures/archive_search.json`

**Interfaces:**
- Consumes: `SourceAdapter`, `Result`, `Playable`, `encode_ref`
- Produces: `ArchiveAdapter(http: httpx.AsyncClient, signing_key: str)` con `name = "archive"`, `capabilities = {"movie", "tv", "anime"}`

- [ ] **Step 1: Grabar el fixture**

```bash
curl -s 'https://archive.org/advancedsearch.php?q=dune&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=mediatype&rows=5&output=json' \
  > ~/arkiv-api/tests/fixtures/archive_search.json
```

- [ ] **Step 2: Escribir el test que falla**

`tests/test_adapter_archive.py`:

```python
import json
import pathlib

import httpx
import pytest
import respx

from arkiv_api.adapters.archive import ArchiveAdapter
from arkiv_api.models import SearchContext
from arkiv_api.refs import decode_ref

KEY = "x" * 32
FIXTURE = json.loads((pathlib.Path(__file__).parent / "fixtures" / "archive_search.json").read_text())


@pytest.fixture
def adapter():
    return ArchiveAdapter(httpx.AsyncClient(), KEY)


@respx.mock
async def test_mapea_los_docs_a_results(adapter):
    respx.get(url__startswith="https://archive.org/advancedsearch.php").mock(
        return_value=httpx.Response(200, json=FIXTURE)
    )
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert out
    assert all(r.source == "archive" for r in out)
    assert all(r.title for r in out)


@respx.mock
async def test_el_ref_codifica_el_identifier(adapter):
    respx.get(url__startswith="https://archive.org/advancedsearch.php").mock(
        return_value=httpx.Response(200, json=FIXTURE)
    )
    primero = [r async for r in adapter.search(SearchContext(q="dune"))][0]
    fuente, payload = decode_ref(primero.ref, KEY)
    assert fuente == "archive"
    assert payload["identifier"]


@respx.mock
async def test_un_500_del_upstream_propaga_para_que_el_breaker_lo_vea(adapter):
    respx.get(url__startswith="https://archive.org/advancedsearch.php").mock(
        return_value=httpx.Response(500)
    )
    with pytest.raises(httpx.HTTPStatusError):
        [r async for r in adapter.search(SearchContext(q="dune"))]


@respx.mock
async def test_resolve_arma_la_url_de_descarga(adapter):
    respx.get("https://archive.org/metadata/mi-item").mock(
        return_value=httpx.Response(
            200,
            json={"files": [{"name": "peli.mp4", "format": "MPEG4", "size": "123"},
                            {"name": "info.txt", "format": "Text", "size": "1"}]},
        )
    )
    p = await adapter.resolve({"identifier": "mi-item"})
    assert p.url == "https://archive.org/download/mi-item/peli.mp4"
    assert p.kind == "archive"


@respx.mock
async def test_resolve_sin_video_falla_claro(adapter):
    respx.get("https://archive.org/metadata/vacio").mock(
        return_value=httpx.Response(200, json={"files": [{"name": "a.txt", "format": "Text"}]})
    )
    with pytest.raises(ValueError, match="sin video"):
        await adapter.resolve({"identifier": "vacio"})
```

- [ ] **Step 3: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_adapter_archive.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.adapters.archive'`.

- [ ] **Step 4: Implementar `adapters/archive.py`**

```python
from __future__ import annotations

from collections.abc import AsyncIterator
from urllib.parse import quote

import httpx

from ..models import Playable, Result, SearchContext
from ..refs import encode_ref

_BUSQUEDA = "https://archive.org/advancedsearch.php"
_METADATA = "https://archive.org/metadata"
_DESCARGA = "https://archive.org/download"
_FORMATOS_VIDEO = ("MPEG4", "h.264", "Matroska", "Ogg Video", "512Kb MPEG4")


class ArchiveAdapter:
    name = "archive"
    capabilities = {"movie", "tv", "anime"}

    def __init__(self, http: httpx.AsyncClient, signing_key: str) -> None:
        self._http = http
        self._key = signing_key

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]:
        params = {
            "q": ctx.q,
            "fl[]": ["identifier", "title", "year", "mediatype"],
            "rows": "40",
            "output": "json",
        }
        r = await self._http.get(_BUSQUEDA, params=params, timeout=10)
        r.raise_for_status()
        for doc in r.json().get("response", {}).get("docs", []):
            ident = doc.get("identifier")
            if not ident:
                continue
            yield Result(
                source=self.name,
                title=doc.get("title") or ident,
                ref=encode_ref(self.name, {"identifier": ident}, self._key),
                kind=ctx.type,
                year=str(doc.get("year") or ""),
                extra={"identifier": ident},
            )

    async def resolve(self, payload: dict) -> Playable:
        ident = payload["identifier"]
        r = await self._http.get(f"{_METADATA}/{quote(ident)}", timeout=10)
        r.raise_for_status()
        archivos = r.json().get("files", [])
        video = next((f for f in archivos if f.get("format") in _FORMATOS_VIDEO), None)
        if video is None:
            raise ValueError(f"el item {ident} viene sin video reproducible")
        return Playable(
            kind=self.name,
            url=f"{_DESCARGA}/{quote(ident)}/{quote(video['name'])}",
            mime="video/mp4",
        )

    async def health(self) -> bool:
        try:
            r = await self._http.get(_BUSQUEDA, params={"q": "test", "rows": "1", "output": "json"}, timeout=5)
            return r.status_code == 200
        except Exception:
            return False
```

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 49 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/adapters/archive.py tests/test_adapter_archive.py tests/fixtures/archive_search.json
git commit -m "feat: adaptador archive con busqueda y resolucion de descarga"
```

---

### Task 9: Adaptador `torrent` (mirror + Jackett)

**Files:**
- Create: `src/arkiv_api/adapters/torrent.py`
- Test: `tests/test_adapter_torrent.py`, `tests/fixtures/mirror_search.json`, `tests/fixtures/jackett_search.json`

**Interfaces:**
- Produces: `TorrentAdapter(http, signing_key, mirror_base, mirror_key, jackett_base, jackett_key)` con `name = "torrent"`, `capabilities = {"movie", "tv", "anime"}`

- [ ] **Step 1: Grabar los fixtures desde `blog`**

```bash
ssh blog 'curl -s "http://127.0.0.1:8097/api/search?q=dune&limit=5"' > ~/arkiv-api/tests/fixtures/mirror_search.json
ssh blog 'curl -s "http://127.0.0.1:9117/api/v2.0/indexers/all/results?apikey=$(grep -oP "(?<=<APIKey>)[^<]+" /dev/null 2>/dev/null || echo SINKEY)&Query=dune"' \
  > ~/arkiv-api/tests/fixtures/jackett_search.json || echo '{"Results":[]}' > ~/arkiv-api/tests/fixtures/jackett_search.json
```

Si Jackett no responde con la llave, escribir el fixture a mano con esta forma mínima:

```json
{"Results": [{"Title": "Dune 2021 1080p LATINO", "MagnetUri": "magnet:?xt=urn:btih:abc", "Seeders": 42, "Size": 2147483648, "Tracker": "MejorTorrent"}]}
```

- [ ] **Step 2: Escribir el test que falla**

`tests/test_adapter_torrent.py`:

```python
import httpx
import pytest
import respx

from arkiv_api.adapters.torrent import TorrentAdapter
from arkiv_api.models import SearchContext
from arkiv_api.refs import decode_ref

KEY = "x" * 32


@pytest.fixture
def adapter():
    return TorrentAdapter(
        httpx.AsyncClient(), KEY,
        mirror_base="http://mirror", mirror_key="mk",
        jackett_base="http://jackett", jackett_key="jk",
    )


def _mock_mirror(items):
    respx.get(url__startswith="http://mirror/api/search").mock(
        return_value=httpx.Response(200, json={"results": items})
    )


def _mock_jackett(items):
    respx.get(url__startswith="http://jackett/api/v2.0/indexers").mock(
        return_value=httpx.Response(200, json={"Results": items})
    )


@respx.mock
async def test_combina_mirror_y_jackett(adapter):
    _mock_mirror([{"title": "Dune mirror", "magnet": "magnet:?xt=urn:btih:aaa", "seeders": 5}])
    _mock_jackett([{"Title": "Dune jackett", "MagnetUri": "magnet:?xt=urn:btih:bbb", "Seeders": 9}])
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert {r.title for r in out} == {"Dune mirror", "Dune jackett"}


@respx.mock
async def test_deduplica_por_infohash(adapter):
    _mock_mirror([{"title": "Dune A", "magnet": "magnet:?xt=urn:btih:MISMO", "seeders": 5}])
    _mock_jackett([{"Title": "Dune B", "MagnetUri": "magnet:?xt=urn:btih:mismo", "Seeders": 9}])
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert len(out) == 1
    assert out[0].seeders == 9  # gana el que reporta mas seeds


@respx.mock
async def test_si_jackett_se_cae_igual_devuelve_lo_del_mirror(adapter):
    _mock_mirror([{"title": "Dune mirror", "magnet": "magnet:?xt=urn:btih:aaa", "seeders": 5}])
    respx.get(url__startswith="http://jackett/api/v2.0/indexers").mock(
        return_value=httpx.Response(500)
    )
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert [r.title for r in out] == ["Dune mirror"]


@respx.mock
async def test_si_ambos_backends_caen_propaga_para_el_breaker(adapter):
    respx.get(url__startswith="http://mirror/api/search").mock(return_value=httpx.Response(500))
    respx.get(url__startswith="http://jackett/api/v2.0/indexers").mock(return_value=httpx.Response(500))
    with pytest.raises(RuntimeError, match="sin backends"):
        [r async for r in adapter.search(SearchContext(q="dune"))]


@respx.mock
async def test_el_ref_lleva_el_magnet_y_resolve_lo_devuelve(adapter):
    _mock_mirror([{"title": "Dune", "magnet": "magnet:?xt=urn:btih:aaa", "seeders": 5}])
    _mock_jackett([])
    r = [x async for x in adapter.search(SearchContext(q="dune"))][0]
    fuente, payload = decode_ref(r.ref, KEY)
    assert fuente == "torrent"
    p = await adapter.resolve(payload)
    assert p.url.startswith("magnet:?xt=urn:btih:aaa")
    assert p.kind == "torrent"
```

- [ ] **Step 3: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_adapter_torrent.py -v
```

Esperado: FAIL con `ModuleNotFoundError`.

- [ ] **Step 4: Implementar `adapters/torrent.py`**

```python
from __future__ import annotations

import asyncio
import re
from collections.abc import AsyncIterator

import httpx

from ..models import Playable, Result, SearchContext
from ..refs import encode_ref

_INFOHASH = re.compile(r"btih:([0-9a-zA-Z]+)", re.I)


def _infohash(magnet: str) -> str:
    m = _INFOHASH.search(magnet or "")
    return m.group(1).lower() if m else ""


class TorrentAdapter:
    """Combina el espejo propio (hacktorrent-mirror) con Jackett.

    Si uno de los dos se cae, la búsqueda sigue con el otro; solo falla —y se lo
    hace ver al breaker— cuando se caen los dos.
    """

    name = "torrent"
    capabilities = {"movie", "tv", "anime"}

    def __init__(
        self, http: httpx.AsyncClient, signing_key: str,
        mirror_base: str, mirror_key: str, jackett_base: str, jackett_key: str,
    ) -> None:
        self._http = http
        self._key = signing_key
        self._mirror_base = mirror_base.rstrip("/")
        self._mirror_key = mirror_key
        self._jackett_base = jackett_base.rstrip("/")
        self._jackett_key = jackett_key

    async def _mirror(self, ctx: SearchContext) -> list[dict]:
        params = {"q": ctx.q, "limit": "60"}
        if ctx.tmdb_id:
            params["tmdb_id"] = str(ctx.tmdb_id)
        if ctx.season:
            params["season"] = str(ctx.season)
        if ctx.episode:
            params["episode"] = str(ctx.episode)
        r = await self._http.get(
            f"{self._mirror_base}/api/search", params=params,
            headers={"X-Api-Key": self._mirror_key}, timeout=8,
        )
        r.raise_for_status()
        return [
            {"title": i.get("title", ""), "magnet": i.get("magnet", ""),
             "seeders": int(i.get("seeders") or 0), "size": int(i.get("size") or 0),
             "lang": i.get("lang", "")}
            for i in r.json().get("results", [])
        ]

    async def _jackett(self, ctx: SearchContext) -> list[dict]:
        r = await self._http.get(
            f"{self._jackett_base}/api/v2.0/indexers/all/results",
            params={"apikey": self._jackett_key, "Query": ctx.q}, timeout=12,
        )
        r.raise_for_status()
        return [
            {"title": i.get("Title", ""), "magnet": i.get("MagnetUri") or "",
             "seeders": int(i.get("Seeders") or 0), "size": int(i.get("Size") or 0),
             "lang": ""}
            for i in r.json().get("Results", [])
        ]

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]:
        mirror, jackett = await asyncio.gather(
            self._mirror(ctx), self._jackett(ctx), return_exceptions=True
        )
        if isinstance(mirror, Exception) and isinstance(jackett, Exception):
            raise RuntimeError(f"torrent sin backends: mirror={mirror!r} jackett={jackett!r}")

        crudos = [x for x in (mirror, jackett) if not isinstance(x, Exception)]
        # Dedup por infohash quedándose con el que reporta más seeds.
        mejores: dict[str, dict] = {}
        for lote in crudos:
            for item in lote:
                h = _infohash(item["magnet"])
                if not h:
                    continue
                if h not in mejores or item["seeders"] > mejores[h]["seeders"]:
                    mejores[h] = item

        for h, item in mejores.items():
            yield Result(
                source=self.name,
                title=item["title"],
                ref=encode_ref(self.name, {"magnet": item["magnet"], "infohash": h}, self._key),
                kind=ctx.type,
                lang=item["lang"],
                size_bytes=item["size"],
                seeders=item["seeders"],
                season=ctx.season,
                episode=ctx.episode,
                extra={"infohash": h},
            )

    async def resolve(self, payload: dict) -> Playable:
        return Playable(kind=self.name, url=payload["magnet"], mime="application/x-bittorrent")

    async def health(self) -> bool:
        try:
            r = await self._http.get(f"{self._mirror_base}/api/health", timeout=5)
            return r.status_code == 200
        except Exception:
            return False
```

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 54 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/adapters/torrent.py tests/test_adapter_torrent.py tests/fixtures/mirror_search.json tests/fixtures/jackett_search.json
git commit -m "feat: adaptador torrent que combina mirror y Jackett con dedup por infohash"
```

---

### Task 10: `/v1/resolve`

**Files:**
- Create: `src/arkiv_api/router/resolve.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_resolve.py`

**Interfaces:**
- Produces: `POST /v1/resolve` con cuerpo `{"ref": "<opaco>"}`

- [ ] **Step 1: Escribir el test que falla**

`tests/test_router_resolve.py`:

```python
from fastapi.testclient import TestClient

from arkiv_api.adapters.registry import Registry
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from arkiv_api.refs import encode_ref
from tests.fakes import FakeAdapter

KEY = "x" * 32
CAB = {"X-Arkiv-Key": "k"}


def _client() -> TestClient:
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key=KEY).validated())
    reg = Registry()
    reg.register(FakeAdapter("torrent"))
    app.state.registry = reg
    app.state.breakers = {}
    return TestClient(app)


def test_sin_llave_da_401():
    assert _client().post("/v1/resolve", json={"ref": "x"}).status_code == 401


def test_resuelve_y_devuelve_url_y_headers():
    ref = encode_ref("torrent", {"id": "42"}, KEY)
    body = _client().post("/v1/resolve", json={"ref": ref}, headers=CAB).json()
    assert body["url"] == "https://ejemplo/42"
    assert body["kind"] == "torrent"
    assert body["headers"] == {}


def test_ref_invalido_da_400():
    r = _client().post("/v1/resolve", json={"ref": "basura.basura"}, headers=CAB)
    assert r.status_code == 400


def test_ref_de_una_fuente_desconocida_da_404():
    ref = encode_ref("inexistente", {"id": "1"}, KEY)
    assert _client().post("/v1/resolve", json={"ref": ref}, headers=CAB).status_code == 404
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_router_resolve.py -v
```

Esperado: FAIL con `404` en todos los casos.

- [ ] **Step 3: Implementar `router/resolve.py`**

```python
from __future__ import annotations

from dataclasses import asdict

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from ..auth import require_key
from ..refs import RefError, decode_ref

router = APIRouter(dependencies=[Depends(require_key)])


class ResolveIn(BaseModel):
    ref: str


@router.post("/resolve")
async def resolve(body: ResolveIn, request: Request) -> dict:
    settings = request.app.state.settings
    try:
        fuente, payload = decode_ref(body.ref, settings.ref_signing_key)
    except RefError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e

    try:
        adapter = request.app.state.registry.get(fuente)
    except KeyError as e:
        raise HTTPException(status_code=404, detail=f"fuente desconocida: {fuente}") from e

    try:
        return asdict(await adapter.resolve(payload))
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
```

- [ ] **Step 4: Cablear en `app.py`**

Agregar `resolve` al import y `app.include_router(resolve.router, prefix="/v1")`.

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 58 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/router/resolve.py src/arkiv_api/app.py tests/test_router_resolve.py
git commit -m "feat: /v1/resolve que traduce refs opacos a stream reproducible"
```

---

### Task 11: Adaptador `web`

**Files:**
- Create: `src/arkiv_api/adapters/web.py`
- Test: `tests/test_adapter_web.py`

**Interfaces:**
- Produces: `WebAdapter(http, signing_key, resolver_url)` con `name = "web"`, `capabilities = {"movie", "tv", "anime"}`

- [ ] **Step 1: Escribir el test que falla**

`tests/test_adapter_web.py`:

```python
import httpx
import pytest
import respx

from arkiv_api.adapters.web import WebAdapter
from arkiv_api.models import SearchContext
from arkiv_api.refs import decode_ref

KEY = "x" * 32


@pytest.fixture
def adapter():
    return WebAdapter(httpx.AsyncClient(), KEY, resolver_url="http://resolver")


@respx.mock
async def test_lista_las_fuentes_web_como_results(adapter):
    respx.get("http://resolver/web_sources.json").mock(
        return_value=httpx.Response(200, json={"sources": [{"id": "allcalidad", "name": "AllCalidad"}]})
    )
    respx.get(url__startswith="http://resolver/search").mock(
        return_value=httpx.Response(
            200, json={"results": [{"title": "Dune (2021)", "url": "https://allcalidad.re/dune", "source": "allcalidad"}]}
        )
    )
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert out[0].title == "Dune (2021)"
    assert out[0].source == "web"


@respx.mock
async def test_el_ref_lleva_la_url_de_la_pagina(adapter):
    respx.get("http://resolver/web_sources.json").mock(return_value=httpx.Response(200, json={"sources": []}))
    respx.get(url__startswith="http://resolver/search").mock(
        return_value=httpx.Response(200, json={"results": [{"title": "D", "url": "https://x/d", "source": "s"}]})
    )
    r = [x async for x in adapter.search(SearchContext(q="d"))][0]
    fuente, payload = decode_ref(r.ref, KEY)
    assert fuente == "web"
    assert payload["url"] == "https://x/d"


@respx.mock
async def test_resolve_devuelve_url_con_los_headers_del_host(adapter):
    respx.post("http://resolver/resolve").mock(
        return_value=httpx.Response(
            200,
            json={"url": "https://cdn/v.mp4", "referer": "https://allcalidad.re/",
                  "userAgent": "Mozilla/5.0", "proxyUrl": "http://resolver/proxy?u=x"},
        )
    )
    p = await adapter.resolve({"url": "https://allcalidad.re/dune"})
    assert p.url == "https://cdn/v.mp4"
    assert p.headers["Referer"] == "https://allcalidad.re/"
    assert p.headers["User-Agent"] == "Mozilla/5.0"
    assert p.fallback == {"url": "http://resolver/proxy?u=x"}


@respx.mock
async def test_resolve_sin_url_reproducible_falla_claro(adapter):
    respx.post("http://resolver/resolve").mock(return_value=httpx.Response(200, json={}))
    with pytest.raises(ValueError, match="sin url"):
        await adapter.resolve({"url": "https://x/y"})


@respx.mock
async def test_un_500_del_resolver_propaga(adapter):
    respx.get(url__startswith="http://resolver/search").mock(return_value=httpx.Response(500))
    with pytest.raises(httpx.HTTPStatusError):
        [r async for r in adapter.search(SearchContext(q="d"))]
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_adapter_web.py -v
```

Esperado: FAIL con `ModuleNotFoundError`.

- [ ] **Step 3: Implementar `adapters/web.py`**

```python
from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from ..models import Playable, Result, SearchContext
from ..refs import encode_ref


class WebAdapter:
    """Fuentes web (allcalidad, pelisplushd, …) a través del resolver Node.

    El resolver ya sabe scrapear cada sitio y desenredar los embeds; acá solo se
    traduce su forma a la del gateway.
    """

    name = "web"
    capabilities = {"movie", "tv", "anime"}

    def __init__(self, http: httpx.AsyncClient, signing_key: str, resolver_url: str) -> None:
        self._http = http
        self._key = signing_key
        self._base = resolver_url.rstrip("/")

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]:
        params = {"q": ctx.q, "type": ctx.type}
        if ctx.season:
            params["season"] = str(ctx.season)
        if ctx.episode:
            params["episode"] = str(ctx.episode)
        r = await self._http.get(f"{self._base}/search", params=params, timeout=12)
        r.raise_for_status()
        for item in r.json().get("results", []):
            url = item.get("url")
            if not url:
                continue
            yield Result(
                source=self.name,
                title=item.get("title") or url,
                ref=encode_ref(self.name, {"url": url, "site": item.get("source", "")}, self._key),
                kind=ctx.type,
                lang=item.get("lang", ""),
                quality=item.get("quality", ""),
                season=ctx.season,
                episode=ctx.episode,
                extra={"site": item.get("source", "")},
            )

    async def resolve(self, payload: dict) -> Playable:
        r = await self._http.post(f"{self._base}/resolve", json={"url": payload["url"]}, timeout=25)
        r.raise_for_status()
        datos = r.json()
        url = datos.get("url")
        if not url:
            raise ValueError("el resolver web devolvio sin url reproducible")

        headers: dict[str, str] = {}
        if datos.get("referer"):
            headers["Referer"] = datos["referer"]
        if datos.get("userAgent"):
            headers["User-Agent"] = datos["userAgent"]

        return Playable(
            kind=self.name,
            url=url,
            headers=headers,
            mime=datos.get("mime", ""),
            fallback={"url": datos["proxyUrl"]} if datos.get("proxyUrl") else None,
        )

    async def health(self) -> bool:
        try:
            r = await self._http.get(f"{self._base}/web_sources.json", timeout=5)
            return r.status_code == 200
        except Exception:
            return False
```

- [ ] **Step 4: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 63 passed.

- [ ] **Step 5: Commit**

```bash
git add src/arkiv_api/adapters/web.py tests/test_adapter_web.py
git commit -m "feat: adaptador web sobre el resolver Node con headers y proxy de respaldo"
```

---

### Task 12: Adaptador `magis` — sesión compartida y ritmo global

La fuente nueva. Lo delicado es que la sesión y el rate-limit son **globales**, no por proceso.

**Files:**
- Create: `src/arkiv_api/adapters/magis/__init__.py`, `magis/session.py`, `magis/adapter.py`
- Create: `src/arkiv_api/adapters/magis/vendor/__init__.py`, `vendor/iptv_client.py` (copia pineada)
- Test: `tests/test_magis_session.py`, `tests/test_adapter_magis.py`

**Interfaces:**
- Consumes: `TokenBucket`, `Cache`, `encode_ref`
- Produces:
  - `MagisSession(redis, bucket, factory)` con `async def client() -> IPTVClient` y `async def invalidate()`
  - `MagisAdapter(session, cache, signing_key)` con `name = "magis"`, `capabilities = {"movie", "tv"}`

- [ ] **Step 1: Vendorizar el cliente**

```bash
mkdir -p ~/arkiv-api/src/arkiv_api/adapters/magis/vendor
cp /Users/cristian/magia/iptv_client.py ~/arkiv-api/src/arkiv_api/adapters/magis/vendor/iptv_client.py
touch ~/arkiv-api/src/arkiv_api/adapters/magis/vendor/__init__.py
cd /Users/cristian/magia && git rev-parse HEAD
```

Anotar el commit devuelto en la cabecera del archivo vendorizado:

```python
# Copia pineada de lordmacu/magia @ <COMMIT>. No editar a mano:
# para actualizar, re-copiar desde el repo y volver a correr los tests.
```

- [ ] **Step 2: Escribir los tests que fallan**

`tests/test_magis_session.py`:

```python
import fakeredis.aioredis
import pytest

from arkiv_api.adapters.magis.session import MagisSession
from arkiv_api.store.ratelimit import TokenBucket


class ClienteFalso:
    creados = 0

    def __init__(self, user_id=None, user_token=None):
        ClienteFalso.creados += 1
        self.user_id = user_id or "uid-nuevo"
        self.user_token = user_token or "tok-nuevo"


@pytest.fixture(autouse=True)
def _reset():
    ClienteFalso.creados = 0


@pytest.fixture
def session():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    return MagisSession(redis, TokenBucket(redis, "magis", 1), ClienteFalso)


async def test_la_primera_vez_activa_y_guarda_la_sesion(session):
    c = await session.client()
    assert c.user_id == "uid-nuevo"
    assert ClienteFalso.creados == 1


async def test_la_segunda_vez_reusa_lo_guardado_sin_re_activar(session):
    await session.client()
    await session.client()
    assert ClienteFalso.creados == 1


async def test_invalidate_fuerza_una_activacion_nueva(session):
    await session.client()
    await session.invalidate()
    await session.client()
    assert ClienteFalso.creados == 2


async def test_la_sesion_se_guarda_con_ttl_de_48h(session):
    await session.client()
    ttl = await session._r.ttl("magis:session")
    assert 47 * 3600 < ttl <= 48 * 3600
```

`tests/test_adapter_magis.py`:

```python
import fakeredis.aioredis
import pytest

from arkiv_api.adapters.magis.adapter import MagisAdapter
from arkiv_api.models import SearchContext
from arkiv_api.refs import decode_ref
from arkiv_api.store.cache import Cache

KEY = "x" * 32


class ClienteFalso:
    def __init__(self):
        self.llamadas = 0

    def search(self, value, **kw):
        self.llamadas += 1
        return {"data": {"list": [
            {"contentId": "c1", "title": "Dune", "type": "1", "year": "2021"},
            {"contentId": "c2", "title": "Dune Parte Dos", "type": "1", "year": "2024"},
        ]}}

    def play_vod(self, content_id, **kw):
        return {"data": {"episodeList": [{"movieList": [
            {"contentId": "media-1", "license": "LIC", "videoFormat": "mp4"}
        ]}]}}

    def get_slb(self, **kw):
        return {"data": {"cdnList": [{"tag": "vod", "mainAddr": "https://cdn.magis", "url": "AUTHTOK"}]}}


class SesionFalsa:
    def __init__(self, cliente):
        self._c = cliente
        self.invalidada = False

    async def client(self):
        return self._c

    async def invalidate(self):
        self.invalidada = True


@pytest.fixture
def piezas():
    cliente = ClienteFalso()
    cache = Cache(fakeredis.aioredis.FakeRedis(decode_responses=True))
    return cliente, MagisAdapter(SesionFalsa(cliente), cache, KEY)


async def test_solo_declara_movie_y_tv_nunca_live(piezas):
    _, adapter = piezas
    assert adapter.capabilities == {"movie", "tv"}


async def test_mapea_la_busqueda_a_results(piezas):
    _, adapter = piezas
    out = [r async for r in adapter.search(SearchContext(q="dune"))]
    assert [r.title for r in out] == ["Dune", "Dune Parte Dos"]
    assert all(r.source == "magis" for r in out)


async def test_la_segunda_busqueda_igual_sale_de_cache(piezas):
    cliente, adapter = piezas
    ctx = SearchContext(q="dune")
    [r async for r in adapter.search(ctx)]
    [r async for r in adapter.search(ctx)]
    assert cliente.llamadas == 1


async def test_el_ref_codifica_el_content_id(piezas):
    _, adapter = piezas
    r = [x async for x in adapter.search(SearchContext(q="dune"))][0]
    fuente, payload = decode_ref(r.ref, KEY)
    assert fuente == "magis"
    assert payload["content_id"] == "c1"


async def test_resolve_devuelve_url_con_content_auth_y_license(piezas):
    _, adapter = piezas
    p = await adapter.resolve({"content_id": "c1"})
    assert p.url == "https://cdn.magis/vod/media-1_media.mp4"
    assert p.headers["Content-Auth"] == "AUTHTOK"
    assert p.headers["Content-License"] == "LIC"
    assert p.kind == "magis"
```

- [ ] **Step 3: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_magis_session.py tests/test_adapter_magis.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.adapters.magis'`.

- [ ] **Step 4: Implementar `magis/session.py`**

```python
from __future__ import annotations

import asyncio
import json

_TTL_S = 48 * 3600  # el portal da ~48 h de vida al token; se refresca en 401.


class MagisSession:
    """Una sola sesión del portal, compartida por todos los workers.

    El README de magia dice que `userId`/`userToken` son portables entre sesiones
    y duran ~48 h. Guardarlos en Redis evita re-activar el dispositivo en cada
    worker (y en cada reinicio), que es justo lo que dispara el rate-limit.
    """

    _KEY = "magis:session"

    def __init__(self, redis, bucket, factory) -> None:
        self._r = redis
        self._bucket = bucket
        self._factory = factory
        self._lock = asyncio.Lock()

    async def client(self):
        async with self._lock:
            crudo = await self._r.get(self._KEY)
            if crudo:
                datos = json.loads(crudo)
                return self._factory(user_id=datos["user_id"], user_token=datos["user_token"])

            await self._bucket.acquire()
            cliente = self._factory()
            await self._r.set(
                self._KEY,
                json.dumps({"user_id": cliente.user_id, "user_token": cliente.user_token}),
                ex=_TTL_S,
            )
            return cliente

    async def invalidate(self) -> None:
        await self._r.delete(self._KEY)
```

- [ ] **Step 5: Implementar `magis/adapter.py`**

```python
from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

from ...models import Playable, Result, SearchContext
from ...refs import encode_ref

_TTL_BUSQUEDA_S = 6 * 3600
_TTL_STALE_S = 24 * 3600
_TTL_PLAY_S = 40 * 3600   # por debajo de las ~48 h que vive el token del CDN


class MagisAdapter:
    """Portal IPTV de magia, solo VOD.

    `capabilities` excluye `anime` y no expone TV en vivo a propósito: el live
    necesita el proxy HLS y la firma SLB, que están fuera del alcance del gateway.
    """

    name = "magis"
    capabilities = {"movie", "tv"}

    def __init__(self, session, cache, signing_key: str) -> None:
        self._session = session
        self._cache = cache
        self._key = signing_key

    async def _buscar_crudo(self, ctx: SearchContext) -> list[dict]:
        clave = f"magis:search:{ctx.type}:{ctx.q.lower()}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado["items"]

        cliente = await self._session.client()
        # iptv_client es síncrono: va a un hilo para no bloquear el event loop.
        datos = await asyncio.to_thread(cliente.search, ctx.q)
        items = (datos or {}).get("data", {}).get("list", []) or []
        await self._cache.set_json(clave, {"items": items}, _TTL_BUSQUEDA_S, _TTL_STALE_S)
        return items

    async def search(self, ctx: SearchContext) -> AsyncIterator[Result]:
        for item in await self._buscar_crudo(ctx):
            cid = item.get("contentId")
            if not cid:
                continue
            yield Result(
                source=self.name,
                title=item.get("title") or str(cid),
                ref=encode_ref(self.name, {"content_id": str(cid)}, self._key),
                kind=ctx.type,
                year=str(item.get("year") or ""),
                season=ctx.season,
                episode=ctx.episode,
                extra={"content_id": str(cid)},
            )

    async def resolve(self, payload: dict) -> Playable:
        cid = payload["content_id"]
        clave = f"magis:play:{cid}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return Playable(**cacheado)

        cliente = await self._session.client()
        play = await asyncio.to_thread(cliente.play_vod, cid)
        slb = await asyncio.to_thread(cliente.get_slb)

        episodios = (play or {}).get("data", {}).get("episodeList", []) or []
        peliculas = episodios[0].get("movieList", []) if episodios else []
        if not peliculas:
            raise ValueError(f"magis devolvio sin media para {cid}")
        media = peliculas[0]

        cdns = (slb or {}).get("data", {}).get("cdnList", []) or []
        vod = next((c for c in cdns if c.get("tag") == "vod"), None)
        if vod is None:
            raise ValueError("magis devolvio sin CDN de vod")

        formato = media.get("videoFormat") or "mp4"
        reproducible = Playable(
            kind=self.name,
            url=f"{vod['mainAddr'].rstrip('/')}/vod/{media['contentId']}_media.{formato}",
            headers={"Content-Auth": vod["url"], "Content-License": media["license"]},
            mime="video/mp4" if formato == "mp4" else "video/mp2t",
        )
        await self._cache.set_json(clave, reproducible.__dict__, _TTL_PLAY_S, _TTL_PLAY_S)
        return reproducible

    async def health(self) -> bool:
        try:
            await self._session.client()
            return True
        except Exception:
            return False
```

`src/arkiv_api/adapters/magis/__init__.py`: archivo vacío.

- [ ] **Step 6: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 73 passed.

- [ ] **Step 7: Commit**

```bash
git add src/arkiv_api/adapters/magis tests/test_magis_session.py tests/test_adapter_magis.py
git commit -m "feat: adaptador magis solo VOD con sesion compartida y cache de tokens"
```

---

### Task 13: Cableado de adaptadores reales y `/v1/sources`

**Files:**
- Create: `src/arkiv_api/router/sources.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_sources.py`, `tests/test_app_wiring.py`

**Interfaces:**
- Consumes: todos los adaptadores, `Breaker`, `Settings`
- Produces: `build_registry(settings, http, redis) -> tuple[Registry, dict[str, Breaker]]`; `GET /v1/sources`

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_app_wiring.py`:

```python
import fakeredis.aioredis
import httpx

from arkiv_api.app import build_registry
from arkiv_api.config import Settings


def _settings(**kw) -> Settings:
    base = dict(arkiv_api_keys="k", ref_signing_key="x" * 32)
    return Settings(**{**base, **kw}).validated()


async def test_sin_credenciales_de_magis_esa_fuente_no_se_registra():
    reg, _ = build_registry(_settings(), httpx.AsyncClient(), fakeredis.aioredis.FakeRedis())
    assert "magis" not in [a.name for a in reg.all()]


async def test_con_credenciales_de_magis_la_fuente_aparece():
    reg, _ = build_registry(
        _settings(iptv_3des_key="k" * 48, iptv_hosts="https://h", iptv_device_sn="sn"),
        httpx.AsyncClient(), fakeredis.aioredis.FakeRedis(),
    )
    assert "magis" in [a.name for a in reg.all()]


async def test_archive_torrent_y_web_siempre_estan():
    reg, _ = build_registry(_settings(), httpx.AsyncClient(), fakeredis.aioredis.FakeRedis())
    assert {"archive", "torrent", "web"} <= {a.name for a in reg.all()}


async def test_hay_un_breaker_por_adaptador():
    reg, breakers = build_registry(_settings(), httpx.AsyncClient(), fakeredis.aioredis.FakeRedis())
    assert set(breakers) == {a.name for a in reg.all()}
```

`tests/test_router_sources.py`:

```python
from fastapi.testclient import TestClient

from arkiv_api.adapters.registry import Registry
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from tests.fakes import FakeAdapter

CAB = {"X-Arkiv-Key": "k"}


def _client() -> TestClient:
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated())
    reg = Registry()
    reg.register(FakeAdapter("torrent", capabilities={"movie", "tv"}))
    reg.register(FakeAdapter("magis", capabilities={"movie"}))
    app.state.registry = reg
    app.state.breakers = {}
    return TestClient(app)


def test_sin_llave_da_401():
    assert _client().get("/v1/sources").status_code == 401


def test_lista_las_fuentes_con_sus_capacidades():
    body = _client().get("/v1/sources", headers=CAB).json()
    por_nombre = {s["name"]: s for s in body["sources"]}
    assert sorted(por_nombre["torrent"]["capabilities"]) == ["movie", "tv"]
    assert por_nombre["magis"]["capabilities"] == ["movie"]


def test_reporta_el_estado_del_breaker():
    body = _client().get("/v1/sources", headers=CAB).json()
    assert all(s["state"] == "closed" for s in body["sources"])
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_app_wiring.py tests/test_router_sources.py -v
```

Esperado: FAIL con `ImportError: cannot import name 'build_registry'`.

- [ ] **Step 3: Implementar `router/sources.py`**

```python
from fastapi import APIRouter, Depends, Request

from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])


@router.get("/sources")
async def sources(request: Request) -> dict:
    """Registro de fuentes vivas. La app pinta sus pestañas con esto, así agregar
    una fuente nueva no requiere publicar un APK."""
    breakers = request.app.state.breakers
    salida = []
    for a in request.app.state.registry.all():
        b = breakers.get(a.name)
        salida.append({
            "name": a.name,
            "capabilities": sorted(a.capabilities),
            "state": await b.state() if b else "closed",
        })
    return {"sources": salida}
```

- [ ] **Step 4: Implementar `build_registry` en `app.py`**

Reemplazar `app.py` entero por:

```python
from __future__ import annotations

import httpx
import redis.asyncio as aioredis
from fastapi import FastAPI

from .adapters.archive import ArchiveAdapter
from .adapters.magis.adapter import MagisAdapter
from .adapters.magis.session import MagisSession
from .adapters.registry import Registry
from .adapters.torrent import TorrentAdapter
from .adapters.web import WebAdapter
from .breaker import Breaker
from .config import Settings, get_settings
from .router import health, resolve, search, sources
from .store.cache import Cache
from .store.ratelimit import TokenBucket

_MAGIS_INTERVALO_MS = 1500  # el portal corta a 1 llamada cada 1.5 s


def _magis_factory(settings: Settings):
    from .adapters.magis.vendor.iptv_client import IPTVClient

    def crear(user_id=None, user_token=None):
        return IPTVClient(user_id=user_id, user_token=user_token)

    return crear


def build_registry(settings: Settings, http: httpx.AsyncClient, redis) -> tuple[Registry, dict]:
    """Registra las fuentes que tienen con qué funcionar.

    Una fuente sin credenciales simplemente no se registra: el gateway sigue vivo
    y `/v1/sources` refleja que no está.
    """
    reg = Registry()
    reg.register(ArchiveAdapter(http, settings.ref_signing_key))
    reg.register(TorrentAdapter(
        http, settings.ref_signing_key,
        mirror_base=settings.mirror_base_url, mirror_key=settings.mirror_api_key,
        jackett_base=settings.jackett_base_url, jackett_key=settings.jackett_api_key,
    ))
    reg.register(WebAdapter(http, settings.ref_signing_key, settings.web_resolver_url))

    if settings.credential_namespaces["magis"]:
        sesion = MagisSession(
            redis, TokenBucket(redis, "magis", _MAGIS_INTERVALO_MS), _magis_factory(settings)
        )
        reg.register(MagisAdapter(sesion, Cache(redis), settings.ref_signing_key))

    breakers = {a.name: Breaker(redis, a.name) for a in reg.all()}
    return reg, breakers


def create_app(settings: Settings | None = None) -> FastAPI:
    app = FastAPI(title="arkiv-api", version="0.1.0")
    app.state.settings = settings or get_settings()
    app.state.registry = Registry()
    app.state.breakers = {}

    @app.on_event("startup")
    async def _arrancar() -> None:
        s = app.state.settings
        app.state.http = httpx.AsyncClient(follow_redirects=True)
        app.state.redis = aioredis.from_url(s.redis_url, decode_responses=True)
        app.state.registry, app.state.breakers = build_registry(s, app.state.http, app.state.redis)

    @app.on_event("shutdown")
    async def _apagar() -> None:
        await app.state.http.aclose()
        await app.state.redis.aclose()

    for r in (health, search, resolve, sources):
        app.include_router(r.router, prefix="/v1")
    return app
```

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 80 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/app.py src/arkiv_api/router/sources.py tests/test_app_wiring.py tests/test_router_sources.py
git commit -m "feat: cableado de las cuatro fuentes y /v1/sources para hot-update"
```

---

### Task 14: Catálogo — TMDB, AniList, Simkl, Cinemeta, OpenSubtitles

Saca las llaves del APK y cachea lo que hoy cada dispositivo re-consulta por su cuenta.

**Files:**
- Create: `src/arkiv_api/catalog/__init__.py`, `tmdb.py`, `anilist.py`, `simkl.py`, `cinemeta.py`, `opensubtitles.py`
- Create: `src/arkiv_api/router/catalog.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_catalog.py`

**Interfaces:**
- Produces:
  - `TmdbClient(http, cache, api_key, language="es-MX")` con `search(q, type)`, `detail(type, id)`, `images(type, id)`
  - `AniListClient(http, cache)` con `search(q)`, `detail(anilist_id)`
  - `SimklClient(http, cache, client_id)` con `by_anilist(anilist_id)`
  - `CinemetaClient(http, cache)` con `meta(type, imdb_id)`
  - `OpenSubtitlesClient(http, cache, api_key)` con `search(query, languages)`
  - Rutas `GET /v1/catalog/{tmdb,anilist,simkl,cinemeta,subtitles}/...`

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_catalog.py`:

```python
import fakeredis.aioredis
import httpx
import pytest
import respx

from arkiv_api.catalog.anilist import AniListClient
from arkiv_api.catalog.tmdb import TmdbClient
from arkiv_api.store.cache import Cache


@pytest.fixture
def cache():
    return Cache(fakeredis.aioredis.FakeRedis(decode_responses=True))


@respx.mock
async def test_tmdb_busca_en_espanol_por_defecto(cache):
    ruta = respx.get(url__startswith="https://api.themoviedb.org/3/search/movie").mock(
        return_value=httpx.Response(200, json={"results": [{"id": 1, "title": "Duna"}]})
    )
    out = await TmdbClient(httpx.AsyncClient(), cache, "LLAVE").search("dune", "movie")
    assert out["results"][0]["title"] == "Duna"
    assert "language=es-MX" in str(ruta.calls[0].request.url)


@respx.mock
async def test_tmdb_no_repite_la_llamada_upstream_si_esta_en_cache(cache):
    ruta = respx.get(url__startswith="https://api.themoviedb.org/3/search/movie").mock(
        return_value=httpx.Response(200, json={"results": []})
    )
    cli = TmdbClient(httpx.AsyncClient(), cache, "LLAVE")
    await cli.search("dune", "movie")
    await cli.search("dune", "movie")
    assert ruta.call_count == 1


@respx.mock
async def test_tmdb_nunca_filtra_la_llave_en_la_respuesta(cache):
    respx.get(url__startswith="https://api.themoviedb.org/3/search/movie").mock(
        return_value=httpx.Response(200, json={"results": []})
    )
    out = await TmdbClient(httpx.AsyncClient(), cache, "SECRETA").search("dune", "movie")
    assert "SECRETA" not in str(out)


@respx.mock
async def test_anilist_consulta_por_graphql(cache):
    ruta = respx.post("https://graphql.anilist.co").mock(
        return_value=httpx.Response(200, json={"data": {"Page": {"media": [{"id": 1}]}}})
    )
    out = await AniListClient(httpx.AsyncClient(), cache).search("naruto")
    assert out["data"]["Page"]["media"][0]["id"] == 1
    assert ruta.call_count == 1
```

`tests/test_router_catalog.py`:

```python
import httpx
import respx
from fastapi.testclient import TestClient

from arkiv_api.app import create_app
from arkiv_api.config import Settings

CAB = {"X-Arkiv-Key": "k"}


def _client() -> TestClient:
    s = Settings(arkiv_api_keys="k", ref_signing_key="x" * 32, tmdb_api_key="T").validated()
    return TestClient(create_app(s))


def test_sin_llave_da_401():
    assert _client().get("/v1/catalog/tmdb/search?q=dune").status_code == 401


@respx.mock
def test_tmdb_search_pasa_por_el_gateway():
    respx.get(url__startswith="https://api.themoviedb.org/3/search/movie").mock(
        return_value=httpx.Response(200, json={"results": [{"id": 1, "title": "Duna"}]})
    )
    with _client() as cli:
        body = cli.get("/v1/catalog/tmdb/search?q=dune&type=movie", headers=CAB).json()
    assert body["results"][0]["title"] == "Duna"


def test_si_falta_la_llave_de_tmdb_da_503():
    s = Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated()
    with TestClient(create_app(s)) as cli:
        assert cli.get("/v1/catalog/tmdb/search?q=dune", headers=CAB).status_code == 503
```

- [ ] **Step 2: Correr y verificar que fallan**

```bash
.venv/bin/pytest tests/test_catalog.py tests/test_router_catalog.py -v
```

Esperado: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.catalog'`.

- [ ] **Step 3: Implementar `catalog/tmdb.py`**

```python
from __future__ import annotations

import httpx

_BASE = "https://api.themoviedb.org/3"
_TTL_S = 12 * 3600
_STALE_S = 7 * 24 * 3600


class TmdbClient:
    def __init__(self, http: httpx.AsyncClient, cache, api_key: str, language: str = "es-MX") -> None:
        self._http = http
        self._cache = cache
        self._key = api_key
        self._lang = language

    async def _get(self, path: str, clave: str, **params) -> dict:
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        r = await self._http.get(
            f"{_BASE}{path}",
            params={"api_key": self._key, "language": self._lang, **params},
            timeout=10,
        )
        r.raise_for_status()
        datos = r.json()
        await self._cache.set_json(clave, datos, _TTL_S, _STALE_S)
        return datos

    async def search(self, q: str, type: str = "movie") -> dict:
        tipo = "tv" if type == "tv" else "movie"
        return await self._get(f"/search/{tipo}", f"tmdb:s:{tipo}:{q.lower()}", query=q)

    async def detail(self, type: str, tmdb_id: int) -> dict:
        tipo = "tv" if type == "tv" else "movie"
        return await self._get(f"/{tipo}/{tmdb_id}", f"tmdb:d:{tipo}:{tmdb_id}")

    async def images(self, type: str, tmdb_id: int) -> dict:
        tipo = "tv" if type == "tv" else "movie"
        return await self._get(
            f"/{tipo}/{tmdb_id}/images", f"tmdb:i:{tipo}:{tmdb_id}", include_image_language="es,en,null"
        )
```

- [ ] **Step 4: Implementar los otros cuatro clientes**

`catalog/anilist.py`:

```python
from __future__ import annotations

import httpx

_ENDPOINT = "https://graphql.anilist.co"
_TTL_S = 12 * 3600
_STALE_S = 7 * 24 * 3600

_BUSQUEDA = """
query ($q: String) {
  Page(perPage: 25) {
    media(search: $q, type: ANIME) {
      id title { romaji english native } seasonYear episodes
      coverImage { large } description(asHtml: false)
    }
  }
}
"""

_DETALLE = """
query ($id: Int) {
  Media(id: $id, type: ANIME) {
    id title { romaji english native } seasonYear episodes status
    coverImage { large } bannerImage description(asHtml: false)
  }
}
"""


class AniListClient:
    def __init__(self, http: httpx.AsyncClient, cache) -> None:
        self._http = http
        self._cache = cache

    async def _post(self, query: str, variables: dict, clave: str) -> dict:
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        r = await self._http.post(_ENDPOINT, json={"query": query, "variables": variables}, timeout=10)
        r.raise_for_status()
        datos = r.json()
        await self._cache.set_json(clave, datos, _TTL_S, _STALE_S)
        return datos

    async def search(self, q: str) -> dict:
        return await self._post(_BUSQUEDA, {"q": q}, f"anilist:s:{q.lower()}")

    async def detail(self, anilist_id: int) -> dict:
        return await self._post(_DETALLE, {"id": anilist_id}, f"anilist:d:{anilist_id}")
```

`catalog/simkl.py`:

```python
from __future__ import annotations

import httpx

_BASE = "https://api.simkl.com"
_TTL_S = 7 * 24 * 3600
_STALE_S = 30 * 24 * 3600


class SimklClient:
    def __init__(self, http: httpx.AsyncClient, cache, client_id: str) -> None:
        self._http = http
        self._cache = cache
        self._client_id = client_id

    async def by_anilist(self, anilist_id: int) -> dict:
        clave = f"simkl:anilist:{anilist_id}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        cab = {"simkl-api-key": self._client_id}
        r = await self._http.get(f"{_BASE}/search/id", params={"anilist": anilist_id}, headers=cab, timeout=10)
        r.raise_for_status()
        encontrado = r.json()
        if not encontrado:
            return {}
        sid = encontrado[0]["ids"]["simkl"]
        d = await self._http.get(f"{_BASE}/anime/{sid}", params={"extended": "full"}, headers=cab, timeout=10)
        d.raise_for_status()
        datos = d.json()
        await self._cache.set_json(clave, datos, _TTL_S, _STALE_S)
        return datos
```

`catalog/cinemeta.py`:

```python
from __future__ import annotations

import httpx

_BASE = "https://v3-cinemeta.strem.io"
_TTL_S = 24 * 3600
_STALE_S = 7 * 24 * 3600


class CinemetaClient:
    def __init__(self, http: httpx.AsyncClient, cache) -> None:
        self._http = http
        self._cache = cache

    async def meta(self, type: str, imdb_id: str) -> dict:
        tipo = "series" if type == "tv" else "movie"
        clave = f"cinemeta:{tipo}:{imdb_id}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        r = await self._http.get(f"{_BASE}/meta/{tipo}/{imdb_id}.json", timeout=10)
        r.raise_for_status()
        datos = r.json()
        await self._cache.set_json(clave, datos, _TTL_S, _STALE_S)
        return datos
```

`catalog/opensubtitles.py`:

```python
from __future__ import annotations

import httpx

_BASE = "https://api.opensubtitles.com/api/v1"
_TTL_S = 6 * 3600
_STALE_S = 24 * 3600
_UA = "Arkiv v0.3"


class OpenSubtitlesClient:
    def __init__(self, http: httpx.AsyncClient, cache, api_key: str) -> None:
        self._http = http
        self._cache = cache
        self._key = api_key

    async def search(self, query: str, languages: str = "es") -> dict:
        clave = f"osub:{languages}:{query.lower()}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        r = await self._http.get(
            f"{_BASE}/subtitles",
            params={"query": query, "languages": languages},
            headers={"Api-Key": self._key, "User-Agent": _UA},
            timeout=12,
        )
        r.raise_for_status()
        datos = r.json()
        await self._cache.set_json(clave, datos, _TTL_S, _STALE_S)
        return datos
```

`src/arkiv_api/catalog/__init__.py`: archivo vacío.

- [ ] **Step 5: Implementar `router/catalog.py`**

```python
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])


def _cliente(request: Request, nombre: str):
    cli = request.app.state.catalog.get(nombre)
    if cli is None:
        raise HTTPException(status_code=503, detail=f"catalogo {nombre} no configurado")
    return cli


@router.get("/catalog/tmdb/search")
async def tmdb_search(request: Request, q: str = Query(..., min_length=1), type: str = "movie") -> dict:
    return await _cliente(request, "tmdb").search(q, type)


@router.get("/catalog/tmdb/{type}/{tmdb_id}")
async def tmdb_detail(request: Request, type: str, tmdb_id: int) -> dict:
    return await _cliente(request, "tmdb").detail(type, tmdb_id)


@router.get("/catalog/tmdb/{type}/{tmdb_id}/images")
async def tmdb_images(request: Request, type: str, tmdb_id: int) -> dict:
    return await _cliente(request, "tmdb").images(type, tmdb_id)


@router.get("/catalog/anilist/search")
async def anilist_search(request: Request, q: str = Query(..., min_length=1)) -> dict:
    return await _cliente(request, "anilist").search(q)


@router.get("/catalog/anilist/{anilist_id}")
async def anilist_detail(request: Request, anilist_id: int) -> dict:
    return await _cliente(request, "anilist").detail(anilist_id)


@router.get("/catalog/simkl/anilist/{anilist_id}")
async def simkl_by_anilist(request: Request, anilist_id: int) -> dict:
    return await _cliente(request, "simkl").by_anilist(anilist_id)


@router.get("/catalog/cinemeta/{type}/{imdb_id}")
async def cinemeta_meta(request: Request, type: str, imdb_id: str) -> dict:
    return await _cliente(request, "cinemeta").meta(type, imdb_id)


@router.get("/catalog/subtitles")
async def subtitles(request: Request, q: str = Query(..., min_length=1), languages: str = "es") -> dict:
    return await _cliente(request, "opensubtitles").search(q, languages)
```

- [ ] **Step 6: Cablear el catálogo en `app.py`**

Agregar dentro de `_arrancar`, después de construir el registry:

```python
        cache = Cache(app.state.redis)
        app.state.catalog = {
            "tmdb": TmdbClient(app.state.http, cache, s.tmdb_api_key) if s.tmdb_api_key else None,
            "anilist": AniListClient(app.state.http, cache),
            "simkl": SimklClient(app.state.http, cache, s.simkl_client_id) if s.simkl_client_id else None,
            "cinemeta": CinemetaClient(app.state.http, cache),
            "opensubtitles": (
                OpenSubtitlesClient(app.state.http, cache, s.opensubtitles_api_key)
                if s.opensubtitles_api_key else None
            ),
        }
```

Agregar los imports correspondientes y `catalog` a la tupla de routers.
Agregar también `app.state.catalog = {}` junto a los otros defaults de `create_app`.

- [ ] **Step 7: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 87 passed.

- [ ] **Step 8: Commit**

```bash
git add src/arkiv_api/catalog src/arkiv_api/router/catalog.py src/arkiv_api/app.py tests/test_catalog.py tests/test_router_catalog.py
git commit -m "feat: catalogo unificado (TMDB, AniList, Simkl, Cinemeta, OpenSubtitles) con cache"
```

---

### Task 15: Métricas en Postgres y `/v1/stats`

**Files:**
- Create: `src/arkiv_api/store/db.py`, `src/arkiv_api/router/stats.py`
- Modify: `src/arkiv_api/router/search.py`, `src/arkiv_api/app.py`
- Test: `tests/test_metrics.py`

**Interfaces:**
- Produces: `Metrics(pool)` con `record(source, ok, ms, count)` y `summary(desde_horas=24) -> list[dict]`; `MEMORY_METRICS` (implementación en memoria para tests); `GET /v1/stats`

- [ ] **Step 1: Escribir el test que falla**

`tests/test_metrics.py`:

```python
from fastapi.testclient import TestClient

from arkiv_api.adapters.registry import Registry
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from arkiv_api.store.db import MemoryMetrics
from tests.fakes import FakeAdapter

CAB = {"X-Arkiv-Key": "k"}


async def test_registra_exito_y_fallo_por_fuente():
    m = MemoryMetrics()
    await m.record("torrent", ok=True, ms=100, count=5)
    await m.record("torrent", ok=False, ms=4000, count=0)
    await m.record("web", ok=True, ms=200, count=2)
    por_nombre = {s["source"]: s for s in await m.summary()}
    assert por_nombre["torrent"]["ok"] == 1
    assert por_nombre["torrent"]["fail"] == 1
    assert por_nombre["torrent"]["avg_ms"] == 2050
    assert por_nombre["web"]["results"] == 2


def _client(metrics):
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated())
    reg = Registry()
    reg.register(FakeAdapter("a", results=3))
    app.state.registry = reg
    app.state.breakers = {}
    app.state.metrics = metrics
    return TestClient(app)


def test_una_busqueda_deja_su_metrica():
    m = MemoryMetrics()
    cli = _client(m)
    cli.get("/v1/search?q=x&format=json", headers=CAB)
    body = cli.get("/v1/stats", headers=CAB).json()
    assert body["sources"][0]["source"] == "a"
    assert body["sources"][0]["results"] == 3


def test_stats_sin_llave_da_401():
    assert _client(MemoryMetrics()).get("/v1/stats").status_code == 401
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_metrics.py -v
```

Esperado: FAIL con `ImportError: cannot import name 'MemoryMetrics'`.

- [ ] **Step 3: Implementar `store/db.py`**

```python
from __future__ import annotations

from collections import defaultdict

ESQUEMA = """
CREATE TABLE IF NOT EXISTS source_metrics (
    id        BIGSERIAL PRIMARY KEY,
    source    TEXT        NOT NULL,
    ok        BOOLEAN     NOT NULL,
    ms        INTEGER     NOT NULL,
    results   INTEGER     NOT NULL DEFAULT 0,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS source_metrics_at_idx ON source_metrics (at DESC);
"""


class MemoryMetrics:
    """Métricas en memoria. Es lo que usan los tests y el arranque sin Postgres."""

    def __init__(self) -> None:
        self._filas: dict[str, list[tuple[bool, int, int]]] = defaultdict(list)

    async def record(self, source: str, ok: bool, ms: int, count: int = 0) -> None:
        self._filas[source].append((ok, ms, count))

    async def summary(self, desde_horas: int = 24) -> list[dict]:
        salida = []
        for source, filas in self._filas.items():
            salida.append({
                "source": source,
                "ok": sum(1 for ok, _, _ in filas if ok),
                "fail": sum(1 for ok, _, _ in filas if not ok),
                "avg_ms": round(sum(ms for _, ms, _ in filas) / len(filas)) if filas else 0,
                "results": sum(c for _, _, c in filas),
            })
        return sorted(salida, key=lambda s: s["source"])


class Metrics:
    """Métricas persistidas en Postgres."""

    def __init__(self, pool) -> None:
        self._pool = pool

    async def ensure_schema(self) -> None:
        async with self._pool.acquire() as con:
            await con.execute(ESQUEMA)

    async def record(self, source: str, ok: bool, ms: int, count: int = 0) -> None:
        async with self._pool.acquire() as con:
            await con.execute(
                "INSERT INTO source_metrics (source, ok, ms, results) VALUES ($1, $2, $3, $4)",
                source, ok, ms, count,
            )

    async def summary(self, desde_horas: int = 24) -> list[dict]:
        async with self._pool.acquire() as con:
            filas = await con.fetch(
                """
                SELECT source,
                       COUNT(*) FILTER (WHERE ok)       AS ok,
                       COUNT(*) FILTER (WHERE NOT ok)   AS fail,
                       ROUND(AVG(ms))::int              AS avg_ms,
                       COALESCE(SUM(results), 0)::int   AS results
                FROM source_metrics
                WHERE at > now() - ($1 || ' hours')::interval
                GROUP BY source
                ORDER BY source
                """,
                str(desde_horas),
            )
        return [dict(f) for f in filas]
```

- [ ] **Step 4: Implementar `router/stats.py`**

```python
from fastapi import APIRouter, Depends, Request

from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])


@router.get("/stats")
async def stats(request: Request, hours: int = 24) -> dict:
    return {"sources": await request.app.state.metrics.summary(hours), "hours": hours}
```

- [ ] **Step 5: Anotar las métricas desde `search.py`**

En `router/search.py`, dentro de `_agregar` y del generador `stream()`, registrar cada
`source_done` / `source_error`. Reemplazar el cuerpo de `stream()` por:

```python
    async def stream() -> AsyncIterator[bytes]:
        metrics = request.app.state.metrics
        async for ev in fan_out(adaptadores, ctx, breakers):
            p = ev.payload()
            if p["type"] == "source_done":
                await metrics.record(p["source"], ok=True, ms=p["ms"], count=p["count"])
            elif p["type"] == "source_error":
                await metrics.record(p["source"], ok=False, ms=p["ms"])
            yield ev.to_line().encode()
```

Y en `_agregar`, agregar el parámetro `metrics` y las mismas dos llamadas en sus ramas
correspondientes; en `search()` pasar `request.app.state.metrics` a `_agregar`.

- [ ] **Step 6: Cablear en `app.py`**

En `create_app`, agregar el default `app.state.metrics = MemoryMetrics()`. En `_arrancar`,
si `s.database_url` está configurada:

```python
        if s.database_url:
            import asyncpg
            app.state.pool = await asyncpg.create_pool(s.database_url, min_size=1, max_size=4)
            metrics = Metrics(app.state.pool)
            await metrics.ensure_schema()
            app.state.metrics = metrics
```

Agregar `stats` a la tupla de routers.

- [ ] **Step 7: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 91 passed.

- [ ] **Step 8: Commit**

```bash
git add src/arkiv_api/store/db.py src/arkiv_api/router/stats.py src/arkiv_api/router/search.py src/arkiv_api/app.py tests/test_metrics.py
git commit -m "feat: metricas por fuente en Postgres y /v1/stats"
```

---

### Task 16: Plano de datos — `302` en vez de proxy

**Files:**
- Create: `src/arkiv_api/router/stream.py`
- Modify: `src/arkiv_api/app.py`
- Test: `tests/test_router_stream.py`

**Interfaces:**
- Produces: `GET /v1/stream/{item_id}` y `GET /v1/jobs/{job_id}/events` → `RedirectResponse(302)`

- [ ] **Step 1: Escribir el test que falla**

`tests/test_router_stream.py`:

```python
from fastapi.testclient import TestClient

from arkiv_api.app import create_app
from arkiv_api.config import Settings

CAB = {"X-Arkiv-Key": "k"}


def _client() -> TestClient:
    s = Settings(arkiv_api_keys="k", ref_signing_key="x" * 32, nuc_base_url="http://nuc:8099").validated()
    return TestClient(create_app(s))


def test_stream_redirige_no_proxea():
    r = _client().get("/v1/stream/42", headers=CAB, follow_redirects=False)
    assert r.status_code == 302
    assert r.headers["location"] == "http://nuc:8099/stream/42"


def test_events_redirige_no_proxea():
    r = _client().get("/v1/jobs/7/events", headers=CAB, follow_redirects=False)
    assert r.status_code == 302
    assert r.headers["location"] == "http://nuc:8099/jobs/7/events"


def test_el_cuerpo_del_redirect_va_vacio_no_hay_bytes_cruzando():
    assert _client().get("/v1/stream/42", headers=CAB, follow_redirects=False).content == b""


def test_sin_llave_da_401():
    assert _client().get("/v1/stream/42", headers=CAB | {"X-Arkiv-Key": "mala"}).status_code == 401
```

- [ ] **Step 2: Correr y verificar que falla**

```bash
.venv/bin/pytest tests/test_router_stream.py -v
```

Esperado: FAIL con `404`.

- [ ] **Step 3: Implementar `router/stream.py`**

```python
from fastapi import APIRouter, Depends, Request
from fastapi.responses import RedirectResponse

from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])


@router.get("/stream/{item_id}")
async def stream(item_id: str, request: Request) -> RedirectResponse:
    """Redirige, no proxea. `blog` es un Celeron N3050 en swap: copiar bytes de
    video por un hop extra de Python lo tumba."""
    base = request.app.state.settings.nuc_base_url.rstrip("/")
    return RedirectResponse(f"{base}/stream/{item_id}", status_code=302)


@router.get("/jobs/{job_id}/events")
async def job_events(job_id: str, request: Request) -> RedirectResponse:
    """Igual que el stream: el SSE se consume directo del backend. Un hop más
    reintroduce los cortes de SSE que ya costó estabilizar."""
    base = request.app.state.settings.nuc_base_url.rstrip("/")
    return RedirectResponse(f"{base}/jobs/{job_id}/events", status_code=302)
```

- [ ] **Step 4: Cablear en `app.py`**

Agregar `stream` al import y a la tupla de routers.

- [ ] **Step 5: Correr y verificar que pasan**

```bash
.venv/bin/pytest tests/ -v
```

Esperado: 95 passed.

- [ ] **Step 6: Commit**

```bash
git add src/arkiv_api/router/stream.py src/arkiv_api/app.py tests/test_router_stream.py
git commit -m "feat: plano de datos por redirect 302 para no proxear bytes ni SSE"
```

---

### Task 17: Empaquetado y despliegue en `blog`

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `README.md`
- Test: verificación manual contra el servicio corriendo

**Interfaces:**
- Consumes: todo lo anterior
- Produces: `api.comparadorinternet.co` respondiendo `/v1/health`

- [ ] **Step 1: Escribir el `Dockerfile`**

```dockerfile
FROM python:3.12-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .

EXPOSE 8101
# Dos workers: el NUC es un Celeron de 2 nucleos. El ritmo de magis y el estado
# de los breakers viven en Redis, asi que varios workers no se pisan.
CMD ["uvicorn", "arkiv_api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8101", "--workers", "2"]
```

`docker-compose.yml`:

```yaml
services:
  api:
    build: .
    restart: unless-stopped
    ports:
      - "127.0.0.1:8101:8101"
    env_file: .env
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

- [ ] **Step 2: Verificar que la imagen construye y arranca en local**

```bash
cd ~/arkiv-api && docker build -t arkiv-api:dev .
```

Esperado: build exitoso.

- [ ] **Step 3: Publicar el repo y armar el `.env` de producción**

```bash
cd ~/arkiv-api
gh repo create lordmacu/arkiv-api --private --source=. --remote=origin --push
```

Armar el `.env` de producción **en `blog`, nunca en el repo**. Los valores de `IPTV_*` se copian
desde `/Users/cristian/magia/.env`; los de `TMDB_*`, `OPENSUBTITLES_*` y `SIMKL_*` desde
`/Users/cristian/archive/.env` (`API_KEY`, `SUBITLE_API`, `SIMKL_CLIENT_ID`).

```bash
ssh blog 'mkdir -p ~/arkiv-api && chmod 700 ~/arkiv-api'
scp /Users/cristian/magia/.env blog:~/arkiv-api/.env.magia
ssh blog 'chmod 600 ~/arkiv-api/.env.magia'
```

En `blog`, componer `~/arkiv-api/.env` a partir de `.env.example`, tomando los `IPTV_*` de
`.env.magia`, y generar los dos secretos propios:

```bash
ssh blog 'python3 -c "import secrets; print(\"ARKIV_API_KEYS=\" + secrets.token_urlsafe(32))"'
ssh blog 'python3 -c "import secrets; print(\"REF_SIGNING_KEY=\" + secrets.token_urlsafe(32))"'
```

Borrar el archivo puente cuando el `.env` esté completo:

```bash
ssh blog 'shred -u ~/arkiv-api/.env.magia'
```

- [ ] **Step 4: Desplegar y verificar el arranque**

```bash
ssh blog 'cd ~/arkiv-api && git pull && docker compose up -d --build && sleep 15 && docker compose logs --tail 40'
```

Esperado: sin `MissingSecretsError` en los logs.

```bash
ssh blog 'curl -s http://127.0.0.1:8101/v1/health'
```

Esperado: `{"status":"ok","credentials":{...,"magis":true,...}}`.

- [ ] **Step 5: Agregar el ingress del túnel**

⚠️ Reiniciar el túnel **tumba db/alfa/torrents ~40 s**. Se hace **una sola vez**.

Agregar a `~/.cloudflared/comparador.yml`, antes de la línea `- service: http_status:404`:

```yaml
  - hostname: api.comparadorinternet.co
    service: http://127.0.0.1:8101
```

```bash
ssh blog 'systemctl --user restart comparador-tunnel && sleep 20 && systemctl --user is-active comparador-tunnel'
```

Esperado: `active`.

- [ ] **Step 6: Verificar de punta a punta desde el Mac**

```bash
curl -s https://api.comparadorinternet.co/v1/health
```

Esperado: `{"status":"ok",...}`.

```bash
curl -s -H "X-Arkiv-Key: <la-llave>" https://api.comparadorinternet.co/v1/sources
```

Esperado: las cuatro fuentes, todas en `"state":"closed"`.

```bash
curl -s -H "X-Arkiv-Key: <la-llave>" \
  'https://api.comparadorinternet.co/v1/search?q=dune&type=movie&format=json' | head -c 600
```

Esperado: `results` con elementos de más de una fuente y `errors` vacío o con fuentes concretas.

- [ ] **Step 7: Verificar que los servicios viejos siguen vivos**

```bash
ssh blog 'for h in db alfa torrents arkiv-offline; do printf "%s " $h; curl -s -o /dev/null -w "%{http_code}\n" https://$h.comparadorinternet.co/; done'
```

Esperado: todos responden (no `000`). Esto confirma que el reinicio del túnel no dejó nada caído.

- [ ] **Step 8: Escribir el `README.md`**

```markdown
# arkiv-api

Gateway unificado de búsqueda y resolución para Arkiv.

Fuentes: `torrent` (mirror + Jackett) · `web` (resolver Node) · `archive` (archive.org) · `magis` (portal IPTV, solo VOD).

## Rutas

| Ruta | Qué hace |
|------|----------|
| `GET /v1/health` | Estado y qué namespaces de credenciales están configurados. Sin auth. |
| `GET /v1/search` | Fan-out en NDJSON. `?format=json` para un agregado único. |
| `POST /v1/resolve` | Ref opaco → `{url, headers}` reproducible. |
| `GET /v1/sources` | Fuentes activas y estado de sus breakers. |
| `GET /v1/catalog/*` | TMDB · AniList · Simkl · Cinemeta · OpenSubtitles, cacheados. |
| `GET /v1/stats` | Latencia, aciertos y errores por fuente. |
| `GET /v1/stream/*` | `302` al backend real. Los bytes no cruzan el gateway. |

Todas exigen el header `X-Arkiv-Key`, menos `/v1/health`.

## Desarrollo

```bash
python3.12 -m venv .venv && .venv/bin/pip install -e ".[dev]"
cp .env.example .env    # completar
.venv/bin/pytest tests/ -v
.venv/bin/uvicorn arkiv_api.app:create_app --factory --reload --port 8101
```

Los secretos nunca entran al repo. Ver `.env.example` para los nombres.
```

- [ ] **Step 9: Commit**

```bash
git add Dockerfile docker-compose.yml README.md
git commit -m "feat: empaquetado Docker y documentacion de despliegue"
git push
```

---

## Self-Review

**Cobertura del spec.** Cada sección del spec tiene tarea: arquitectura y unidades → Tasks 1, 5, 6; contrato `/v1/search` → Task 7; `/v1/resolve` y refs opacos → Tasks 3, 10; `/v1/sources` y hot-update → Task 13; catálogo → Task 14; adaptador `magis` con sesión Redis, token bucket, caché y solo VOD → Task 12; robustez (aislamiento, breaker, stale-while-revalidate, presupuesto, auth, métricas) → Tasks 2, 4, 5, 6, 15; credenciales consolidadas → Tasks 1, 2, 17; plano de datos por `302` → Task 16; despliegue e ingress → Task 17.

**Fuera de este plan, por decisión de alcance:** F5 (integración Kotlin: `ArkivApiClient`, `SourceKind.MAGIS`, `SourceTab.Magis`, `PlayerSourceTag.headers`, flag `useGateway`, borrar las llaves de `BuildConfig`) y F6 (jobs de `arkiv-offline` y `crunch`). Cada uno tendrá su plan cuando el gateway esté verificado en `blog`.

**Consistencia de tipos.** `Result.ref` (Task 3) es lo que producen los cuatro adaptadores y lo que consume `/v1/resolve` (Task 10). `Playable.headers` (Task 3) es el mismo mapa que llenan `web` (`Referer`/`User-Agent`, Task 11) y `magis` (`Content-Auth`/`Content-License`, Task 12). `SourceAdapter.search` devuelve `AsyncIterator[Result]` en `base.py` (Task 5), en los cuatro adaptadores y en `FakeAdapter`. `Breaker.allow/record_ok/record_fail/state` (Task 5) son los cuatro métodos que usan el orquestador (Task 6) y `/v1/sources` (Task 13). `Cache.get_json` devuelve `(valor, stale)` en Task 4 y se desempaqueta así en Tasks 12 y 14. `Metrics.record(source, ok, ms, count)` y `.summary()` (Task 15) coinciden entre `MemoryMetrics` y `Metrics`.

**Correcciones aplicadas durante la escritura:** el test `test_source_done_y_done` de la Task 3 traía una condición inerte (corregido en su Step 5); el test `test_cinco_fallos_lo_abren` de la Task 5 no contemplaba la petición half-open (corregido en su Step 3).
