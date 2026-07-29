# HackTorrent Mirror — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espejar el catálogo de hacktorrent.to (pelis + anime, con sus torrents) en un PostgreSQL bien estructurado en `blog`, alimentado por un crawler idempotente, y exponerlo vía una API Flask en `torrents.comparadorinternet.co` para la app Arkiv.

**Architecture:** Repo Python nuevo `hacktorrent-mirror` con capas puras y testeables: `normalize` (parsers) → `mapping` (JSON de hacktorrent → filas) → `db` (UPSERT/reconcile en Postgres) → `crawler` (orquesta incremental/full) → `api` (Flask: cara drop-in que imita a hacktorrent + cara rica). Deploy como servicios `systemd --user` detrás de cloudflared.

**Tech Stack:** Python 3.10+, PostgreSQL 14+ (`unaccent`, `pg_trgm`), psycopg2, requests, Flask + gunicorn, pytest, systemd, cloudflared.

## Global Constraints

- **Repo del código:** `/Users/cristian/hacktorrent-mirror` (repo git nuevo, independiente del repo `archive`).
- **Python local = 3.9.6** (única versión utilizable en el Mac). PEP 604 (`X | None`) y otras
  anotaciones nuevas se habilitan haciendo que la PRIMERA línea de cada módulo `mirror/*.py`
  que use esas anotaciones sea `from __future__ import annotations` (vuelve las anotaciones
  perezosas → válidas en 3.9). Producción corre `python:3.12-slim` (Dockerfile), donde también
  funciona. Nota: `config.py` (Task 1, ya hecha) usa `Optional[str]` en vez de `str | None`; es
  equivalente y válido — no re-tocar.
- **Identidad git:** `user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`. Commits **sin** línea de coautoría.
- **Despliegue:** todo por **Coolify** (Postgres gestionado + app Flask Docker + crawler como Scheduled Task + dominio/SSL por Traefik). Nada de systemd/cloudflared a mano.
- **Fuente:** base `https://hacktorrent.to/wp-json/wpreact/v1`. Solo `movie`/`anime` (las series no traen `downloads`).
- **Sin seeds:** hacktorrent no da conteo de seeds; no inventar `seeders` en ninguna cara de la API.
- **Tamaños binarios:** GB/MB/KB en base 1024 (consistente con `TorrentResult.sizeLabel` del app, que divide por `1<<30`).
- **Idiomas normalizados:** enum consistente con `TorrentLang` del app: `LATINO, DUAL, CASTELLANO, JAP_SUB, ENGLISH, OTHER`.
- **DB de test:** las tareas de integración usan `DATABASE_URL` a un Postgres de prueba. Levantar uno local con:
  `docker run --rm -d --name htm-pg -e POSTGRES_PASSWORD=htm -e POSTGRES_DB=htm -p 5433:5432 postgres:16`
  y `export DATABASE_URL=postgresql://postgres:htm@localhost:5433/htm`.

---

## File Structure

```
hacktorrent-mirror/
  README.md
  requirements.txt
  .env.example
  sql/
    schema.sql                 # extensiones, tablas, índices, tsvector generado
  mirror/
    __init__.py
    config.py                  # carga de env (DSN, base URL, UA, rate limit, flaresolverr)
    normalize.py               # parse_size, parse_duration, parse_date, extract_infohash, norm_language, split_csv
    episode.py                 # map_episode (season/episode/pack)
    mapping.py                 # build_title, build_torrents, extract_people, extract_genres (JSON -> filas, puro)
    hacktorrent.py             # cliente HTTP: list_movies/animes/released, get_movie/get_anime, + fallback flaresolverr
    db.py                      # pool, upsert_title, upsert_torrents, upsert_people, upsert_genres, reconcile, sync_run_*
    crawler.py                 # incremental(), full(), CLI (python -m mirror.crawler --mode ...)
    serialize.py               # to_dropin_item/detail, to_rich_title (filas -> JSON de cada cara)
    repo.py                    # consultas de lectura para la API (search, get_title)
    api.py                     # Flask app (drop-in + rica)
  tests/
    conftest.py                # fixtures pytest: db de test, carga de schema
    fixtures/
      movie.json               # detalle real de /movie/matrix-revoluciones
      anime.json               # detalle real de /anime/that-time-i-got-reincarnated-as-a-slime
    test_config.py
    test_normalize.py
    test_episode.py
    test_mapping.py
    test_db.py                 # integración (DATABASE_URL)
    test_hacktorrent.py        # cliente con HTTP mockeado
    test_crawler.py            # integración: cliente mockeado + db de test
    test_serialize.py
    test_api.py                # integración: db de test seed + Flask test client
  Dockerfile                 # imagen de la app (gunicorn) — la usa Coolify
  gunicorn.conf.py
  docker-compose.yml         # SOLO dev local (postgres + api + crawler one-off)
  deploy/
    coolify.md               # runbook de despliegue en Coolify
```

---

## Task 1: Scaffold del repo + config

**Files:**
- Create: `/Users/cristian/hacktorrent-mirror/requirements.txt`
- Create: `/Users/cristian/hacktorrent-mirror/.env.example`
- Create: `/Users/cristian/hacktorrent-mirror/mirror/__init__.py`
- Create: `/Users/cristian/hacktorrent-mirror/mirror/config.py`
- Create: `/Users/cristian/hacktorrent-mirror/README.md`
- Test: `/Users/cristian/hacktorrent-mirror/tests/test_config.py`

**Interfaces:**
- Produces: `Config` dataclass con campos `database_url:str, base_url:str, user_agent:str, rate_limit_s:float, flaresolverr_url:str|None`, y `Config.from_env(env:dict)->Config`.

- [ ] **Step 1: Init repo + identidad git**

```bash
mkdir -p /Users/cristian/hacktorrent-mirror/{mirror,sql,tests/fixtures,deploy}
cd /Users/cristian/hacktorrent-mirror
git init -q
git config user.name lordmacu
git config user.email 10134930+lordmacu@users.noreply.github.com
printf '__pycache__/\n*.pyc\n.env\n.venv/\n' > .gitignore
```

- [ ] **Step 2: requirements + venv**

`requirements.txt`:
```
requests==2.32.3
psycopg2-binary==2.9.9
Flask==3.0.3
gunicorn==23.0.0
pytest==8.3.3
```

```bash
cd /Users/cristian/hacktorrent-mirror
python3 -m venv .venv && . .venv/bin/activate && pip install -q -r requirements.txt
```

- [ ] **Step 3: Write the failing test** — `tests/test_config.py`:

```python
from mirror.config import Config

def test_from_env_reads_values():
    env = {
        "DATABASE_URL": "postgresql://u:p@localhost/db",
        "HACKTORRENT_BASE_URL": "https://hacktorrent.to/wp-json/wpreact/v1",
        "USER_AGENT": "ArkivMirror/1.0",
        "RATE_LIMIT_S": "0.5",
    }
    cfg = Config.from_env(env)
    assert cfg.database_url == "postgresql://u:p@localhost/db"
    assert cfg.base_url.endswith("/wpreact/v1")
    assert cfg.rate_limit_s == 0.5
    assert cfg.flaresolverr_url is None

def test_from_env_defaults():
    cfg = Config.from_env({"DATABASE_URL": "x"})
    assert cfg.base_url == "https://hacktorrent.to/wp-json/wpreact/v1"
    assert cfg.rate_limit_s == 1.0
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd /Users/cristian/hacktorrent-mirror && . .venv/bin/activate && python -m pytest tests/test_config.py -v`
Expected: FAIL (ModuleNotFoundError: mirror.config)

- [ ] **Step 5: Implement `mirror/config.py`**

```python
from dataclasses import dataclass

DEFAULT_BASE = "https://hacktorrent.to/wp-json/wpreact/v1"

@dataclass(frozen=True)
class Config:
    database_url: str
    base_url: str = DEFAULT_BASE
    user_agent: str = "ArkivMirror/1.0"
    rate_limit_s: float = 1.0
    flaresolverr_url: str | None = None

    @classmethod
    def from_env(cls, env: dict) -> "Config":
        return cls(
            database_url=env["DATABASE_URL"],
            base_url=env.get("HACKTORRENT_BASE_URL", DEFAULT_BASE),
            user_agent=env.get("USER_AGENT", "ArkivMirror/1.0"),
            rate_limit_s=float(env.get("RATE_LIMIT_S", "1.0")),
            flaresolverr_url=env.get("FLARESOLVERR_URL") or None,
        )
```

`mirror/__init__.py`: archivo vacío.

`.env.example`:
```
DATABASE_URL=postgresql://mirror:CHANGEME@localhost:5432/hacktorrent
HACKTORRENT_BASE_URL=https://hacktorrent.to/wp-json/wpreact/v1
USER_AGENT=ArkivMirror/1.0
RATE_LIMIT_S=1.0
FLARESOLVERR_URL=
```

`README.md`: 3-4 líneas describiendo el proyecto y cómo correr tests.

- [ ] **Step 6: Run test to verify it passes**

Run: `python -m pytest tests/test_config.py -v`
Expected: PASS (2 passed)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: scaffold repo hacktorrent-mirror + config"
```

---

## Task 2: Normalizaciones primitivas

**Files:**
- Create: `mirror/normalize.py`
- Test: `tests/test_normalize.py`

**Interfaces:**
- Produces:
  - `parse_size(s:str) -> int` (bytes, base 1024; 0 si vacío/no parseable)
  - `parse_duration(s:str) -> int|None` (minutos)
  - `parse_date(s:str) -> datetime.date|None` (formato "YYYYMMDD")
  - `extract_infohash(magnet:str) -> str|None` (hex-40 lower; base32-32 → hex)
  - `norm_language(raw:str) -> str` (enum: LATINO/DUAL/CASTELLANO/JAP_SUB/ENGLISH/OTHER)
  - `split_csv(s:str) -> list[str]`

- [ ] **Step 1: Write the failing test** — `tests/test_normalize.py`:

```python
import datetime
from mirror.normalize import (
    parse_size, parse_duration, parse_date, extract_infohash, norm_language, split_csv,
)

def test_parse_size():
    assert parse_size("9.06 GB") == int(9.06 * 1024**3)
    assert parse_size("700 MB") == 700 * 1024**2
    assert parse_size("1,41 GB") == int(1.41 * 1024**3)  # coma decimal
    assert parse_size("") == 0
    assert parse_size("varios") == 0

def test_parse_duration():
    assert parse_duration("130m") == 130
    assert parse_duration("24m") == 24
    assert parse_duration("0m") == 0
    assert parse_duration("") is None

def test_parse_date():
    assert parse_date("20240105") == datetime.date(2024, 1, 5)
    assert parse_date("") is None
    assert parse_date("bad") is None

def test_extract_infohash_hex():
    m = "magnet:?xt=urn:btih:452A779504892333BCE4270819A5910653292C16&dn=x"
    assert extract_infohash(m) == "452a779504892333bce4270819a5910653292c16"

def test_extract_infohash_base32():
    # base32 de los mismos 20 bytes -> mismo hex-40
    m = "magnet:?xt=urn:btih:IUVHPFIERERTHPHEE4EBTJMRAZJSSLAW&dn=x"
    assert extract_infohash(m) == "452a779504892333bce4270819a5910653292c16"

def test_extract_infohash_none():
    assert extract_infohash("http://nope") is None

def test_norm_language():
    assert norm_language("Latino/Inglés") == "LATINO"
    assert norm_language("Latino") == "LATINO"
    assert norm_language("Dual") == "DUAL"
    assert norm_language("Castellano") == "CASTELLANO"
    assert norm_language("Japonés subtitulado") == "JAP_SUB"
    assert norm_language("Inglés") == "ENGLISH"
    assert norm_language("") == "OTHER"

def test_split_csv():
    assert split_csv("Acción, Aventura ,Suspense") == ["Acción", "Aventura", "Suspense"]
    assert split_csv("") == []
    assert split_csv("Solo") == ["Solo"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/test_normalize.py -v`
Expected: FAIL (ModuleNotFoundError)

- [ ] **Step 3: Implement `mirror/normalize.py`**

```python
from __future__ import annotations  # requerido: Python local 3.9 no soporta `X | None` nativo

import base64
import datetime
import re

_SIZE_RE = re.compile(r"([\d.,]+)\s*(GB|MB|KB|TB|B)", re.I)
_MULT = {"B": 1, "KB": 1024, "MB": 1024**2, "GB": 1024**3, "TB": 1024**4}

def parse_size(s: str) -> int:
    if not s:
        return 0
    m = _SIZE_RE.search(s)
    if not m:
        return 0
    num = float(m.group(1).replace(".", "").replace(",", ".")) if m.group(1).count(",") \
        else float(m.group(1).replace(",", "."))
    return int(num * _MULT[m.group(2).upper()])

def parse_duration(s: str) -> int | None:
    if not s:
        return None
    m = re.match(r"\s*(\d+)", s)
    return int(m.group(1)) if m else None

def parse_date(s: str) -> datetime.date | None:
    if not s or not re.fullmatch(r"\d{8}", s):
        return None
    try:
        return datetime.datetime.strptime(s, "%Y%m%d").date()
    except ValueError:
        return None

_BTIH_RE = re.compile(r"btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})")

def extract_infohash(magnet: str) -> str | None:
    if not magnet:
        return None
    m = _BTIH_RE.search(magnet)
    if not m:
        return None
    v = m.group(1)
    if len(v) == 40:
        return v.lower()
    raw = base64.b32decode(v.upper())  # 32 chars base32 -> 20 bytes
    return raw.hex()

_LANG_RULES = [
    ("LATINO", ("latino", "lat")),
    ("CASTELLANO", ("castellano", "español de españa", "espana")),
    ("DUAL", ("dual",)),
    ("JAP_SUB", ("japon", "jap", "vose", "subtitulado")),
    ("ENGLISH", ("inglés", "ingles", "english")),
]

def norm_language(raw: str) -> str:
    low = (raw or "").lower()
    for name, tokens in _LANG_RULES:
        if any(t in low for t in tokens):
            return name
    return "OTHER"

def split_csv(s: str) -> list[str]:
    if not s:
        return []
    return [p.strip() for p in s.split(",") if p.strip()]
```

Nota sobre `parse_size` con coma: simplificá la lógica del número a esto (reemplazá el `num = ...` de arriba por esta versión más clara):
```python
    raw = m.group(1)
    if "," in raw and "." in raw:      # "1.234,56" -> miles . decimal ,
        raw = raw.replace(".", "").replace(",", ".")
    else:
        raw = raw.replace(",", ".")     # "1,41" -> "1.41"
    num = float(raw)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/test_normalize.py -v`
Expected: PASS (8 passed). Si `test_extract_infohash_base32` falla, verificá que el base32 del test corresponde exactamente a los 20 bytes del hex (regenerar con: `python -c "import base64;print(base64.b32encode(bytes.fromhex('452a779504892333bce4270819a5910653292c16')).decode())"` y pegar el valor en el test).

- [ ] **Step 5: Commit**

```bash
git add mirror/normalize.py tests/test_normalize.py
git commit -m "feat: normalizaciones (size, duracion, fecha, infohash, idioma, csv)"
```

---

## Task 3: Mapeo de temporada/episodio (`map_episode`)

**Files:**
- Create: `mirror/episode.py`
- Test: `tests/test_episode.py`

**Interfaces:**
- Consumes: nada.
- Produces: `map_episode(download:dict, dn:str) -> tuple[int|None,int|None,int|None,bool]`
  devuelve `(season, episode, episode_end, is_pack)`. `download` es el dict crudo de la API
  (puede traer claves `season`/`episode`); `dn` es el nombre del magnet (`dn=` decodificado).

- [ ] **Step 1: Write the failing test** — `tests/test_episode.py`:

```python
from mirror.episode import map_episode

def test_uses_api_fields():
    dl = {"season": 3, "episode": 1}
    dn = "That.Time.S03E01.2024.WEB-DL.1080p-Dual-Lat"
    assert map_episode(dl, dn) == (3, 1, None, False)

def test_fallback_sxxeyy_when_api_missing():
    assert map_episode({}, "Clevatess.S01E02.2025.WEB-DL") == (1, 2, None, False)

def test_fallback_nxnn():
    assert map_episode({}, "Serie 1x05 HDTV") == (1, 5, None, False)

def test_fallback_spanish():
    assert map_episode({}, "Show Temporada 2 Capitulo 3") == (2, 3, None, False)

def test_pack_range():
    assert map_episode({}, "Show.S01E01-E12.1080p") == (1, 1, 12, True)

def test_pack_full_season_no_episode():
    # season detectada, sin episodio -> pack de temporada
    assert map_episode({}, "Show.S02.COMPLETE.1080p") == (2, None, None, True)

def test_nothing_found():
    assert map_episode({}, "Pelicula.2003.1080p") == (None, None, None, False)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/test_episode.py -v`
Expected: FAIL (ModuleNotFoundError)

- [ ] **Step 3: Implement `mirror/episode.py`**

```python
import re

_SXXEYY = re.compile(r"[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})(?:[ ._-]?[Ee](\d{1,3}))?")
_SEASON_ONLY = re.compile(r"[Ss](\d{1,2})(?![Ee0-9])")
_NXNN = re.compile(r"\b(\d{1,2})x(\d{1,3})\b")
_SPANISH = re.compile(r"[Tt]emporada\s*(\d{1,2}).*?[Cc]ap[íi]?[ít]?[uo]?[l]?[o]?\s*(\d{1,3})")

def _from_api(download: dict):
    s = download.get("season")
    e = download.get("episode")
    s = int(s) if s not in (None, "", 0, "0") else None
    e = int(e) if e not in (None, "", 0, "0") else None
    return s, e

def map_episode(download: dict, dn: str):
    dn = dn or ""
    s_api, e_api = _from_api(download)

    season = episode = episode_end = None
    is_pack = False

    m = _SXXEYY.search(dn)
    if m:
        season = int(m.group(1))
        episode = int(m.group(2))
        if m.group(3):
            episode_end = int(m.group(3))
            is_pack = episode_end != episode
    else:
        m = _NXNN.search(dn)
        if m:
            season, episode = int(m.group(1)), int(m.group(2))
        else:
            m = _SPANISH.search(dn)
            if m:
                season, episode = int(m.group(1)), int(m.group(2))
            else:
                m = _SEASON_ONLY.search(dn)
                if m:
                    season = int(m.group(1))
                    is_pack = True  # temporada sin episodio = pack

    # API manda; el dn completa/valida.
    if s_api is not None:
        season = s_api
    if e_api is not None:
        episode = e_api

    return season, episode, episode_end, is_pack
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/test_episode.py -v`
Expected: PASS (7 passed)

- [ ] **Step 5: Commit**

```bash
git add mirror/episode.py tests/test_episode.py
git commit -m "feat: map_episode (season/episode/pack, api + fallback por nombre)"
```

---

## Task 4: Mapeo JSON de detalle → filas (`mapping.py`)

**Files:**
- Create: `mirror/mapping.py`
- Create: `tests/fixtures/movie.json` (fetch real)
- Create: `tests/fixtures/anime.json` (fetch real)
- Test: `tests/test_mapping.py`

**Interfaces:**
- Consumes: `normalize.*`, `episode.map_episode`.
- Produces:
  - `build_title(detail:dict) -> dict` con claves: `id, slug, kind, title, original_title, overview, year, release_date, duration_min, tmdb_id, imdb_rating, country, director, language, img_background, img_featured, trailer, importer_version`.
  - `build_torrents(detail:dict) -> list[dict]` cada uno: `infohash, magnet, quality, size_bytes, size_label, lang_raw, lang_norm, subs, season, episode, episode_end, is_pack, source_date, download_type`.
  - `extract_people(detail:dict) -> list[tuple[str,str]]` → `(name, role)` con role in {actor,director}.
  - `extract_genres(detail:dict) -> list[str]`.
  - `kind_of(detail:dict) -> str` → "movie" o "anime" según `type` ("pelicula"→movie).

- [ ] **Step 1: Fetch fixtures reales**

```bash
cd /Users/cristian/hacktorrent-mirror
UA="ArkivMirror/1.0"; B="https://hacktorrent.to/wp-json/wpreact/v1"
curl -sL -H "User-Agent: $UA" "$B/movie/matrix-revoluciones" -o tests/fixtures/movie.json
curl -sL -H "User-Agent: $UA" "$B/anime/that-time-i-got-reincarnated-as-a-slime" -o tests/fixtures/anime.json
python -c "import json;print('movie dl',len(json.load(open('tests/fixtures/movie.json'))['downloads']));print('anime dl',len(json.load(open('tests/fixtures/anime.json'))['downloads']))"
```
Expected: `movie dl 4`, `anime dl 37` (o similar si el catálogo creció).

- [ ] **Step 2: Write the failing test** — `tests/test_mapping.py`:

```python
import json, datetime, pathlib
from mirror.mapping import build_title, build_torrents, extract_people, extract_genres, kind_of

FIX = pathlib.Path(__file__).parent / "fixtures"
MOVIE = json.load(open(FIX / "movie.json"))
ANIME = json.load(open(FIX / "anime.json"))

def test_kind_of():
    assert kind_of(MOVIE) == "movie"
    assert kind_of(ANIME) == "anime"

def test_build_title_movie():
    t = build_title(MOVIE)
    assert t["slug"] == "matrix-revoluciones"
    assert t["kind"] == "movie"
    assert t["tmdb_id"] == 605
    assert t["year"] == 2003
    assert t["duration_min"] == 130
    assert t["release_date"] == datetime.date(2003, 11, 5)
    assert t["original_title"] == "The Matrix Revolutions"

def test_build_torrents_movie():
    ts = build_torrents(MOVIE)
    assert len(ts) == len(MOVIE["downloads"])
    first = ts[0]
    assert first["infohash"] == "452a779504892333bce4270819a5910653292c16"
    assert first["size_bytes"] == int(9.06 * 1024**3)
    assert first["lang_norm"] == "LATINO"
    assert first["season"] is None and first["episode"] is None

def test_build_torrents_anime_has_episodes():
    ts = build_torrents(ANIME)
    assert all(t["season"] is not None and t["episode"] is not None for t in ts)
    s3e1 = [t for t in ts if t["season"] == 3 and t["episode"] == 1]
    assert len(s3e1) >= 1

def test_extract_people_and_genres():
    people = extract_people(MOVIE)
    assert ("Keanu Reeves", "actor") in people
    assert any(role == "director" for _, role in people)
    genres = extract_genres(MOVIE)
    assert "Acción" in genres
```

- [ ] **Step 3: Run test to verify it fails**

Run: `python -m pytest tests/test_mapping.py -v`
Expected: FAIL (ModuleNotFoundError)

- [ ] **Step 4: Implement `mirror/mapping.py`**

```python
import urllib.parse
import re
from mirror.normalize import (
    parse_size, parse_duration, parse_date, extract_infohash, norm_language, split_csv,
)
from mirror.episode import map_episode

def kind_of(detail: dict) -> str:
    return "anime" if detail.get("type") == "anime" else "movie"

def _int_or_none(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None

def _float_or_none(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None

def build_title(detail: dict) -> dict:
    return {
        "id": _int_or_none(detail.get("id")),
        "slug": detail.get("slug"),
        "kind": kind_of(detail),
        "title": detail.get("title"),
        "original_title": detail.get("original_title"),
        "overview": detail.get("overview"),
        "year": _int_or_none(detail.get("year")),
        "release_date": parse_date((detail.get("years") or "").replace("-", "")[:8])
                        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", detail.get("years") or "") else None,
        "duration_min": parse_duration(detail.get("duracion") or ""),
        "tmdb_id": _int_or_none(detail.get("tmdb_id")),
        "imdb_rating": _float_or_none(detail.get("imdb")),
        "country": detail.get("country"),
        "director": detail.get("director"),
        "language": detail.get("language"),
        "img_background": detail.get("background_image"),
        "img_featured": detail.get("featured"),
        "trailer": detail.get("trailer"),
        "importer_version": detail.get("importer_version"),
    }

def _dn_of(magnet: str) -> str:
    if not magnet:
        return ""
    q = urllib.parse.urlparse(magnet).query
    dn = urllib.parse.parse_qs(q).get("dn", [""])[0]
    return dn

def build_torrents(detail: dict) -> list[dict]:
    out = []
    for d in detail.get("downloads") or []:
        magnet = d.get("download_link") or ""
        dn = _dn_of(magnet)
        season, episode, episode_end, is_pack = map_episode(d, dn)
        raw = d.get("size") or ""
        out.append({
            "infohash": extract_infohash(magnet),
            "magnet": magnet,
            "quality": d.get("quality"),
            "size_bytes": parse_size(raw),
            "size_label": raw,
            "lang_raw": d.get("language"),
            "lang_norm": norm_language(d.get("language") or ""),
            "subs": bool(d.get("subs")),
            "season": season,
            "episode": episode,
            "episode_end": episode_end,
            "is_pack": is_pack,
            "source_date": parse_date(d.get("date") or ""),
            "download_type": d.get("download_type"),
        })
    return out

def extract_people(detail: dict) -> list[tuple[str, str]]:
    people = [(name, "actor") for name in split_csv(detail.get("actors") or "")]
    people += [(name, "director") for name in split_csv(detail.get("director") or "")]
    return people

def extract_genres(detail: dict) -> list[str]:
    return split_csv(detail.get("genres") or "")
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python -m pytest tests/test_mapping.py -v`
Expected: PASS (5 passed)

- [ ] **Step 6: Commit**

```bash
git add mirror/mapping.py tests/test_mapping.py tests/fixtures/
git commit -m "feat: mapeo JSON de detalle -> filas (titulo, torrents, people, genres) + fixtures reales"
```

---

## Task 5: Esquema SQL

**Files:**
- Create: `sql/schema.sql`
- Create: `tests/conftest.py`
- Test: `tests/test_db.py` (solo el test de schema en esta tarea)

**Interfaces:**
- Consumes: `DATABASE_URL`.
- Produces: schema aplicable idempotente (usa `CREATE TABLE IF NOT EXISTS`, `CREATE EXTENSION IF NOT EXISTS`). Tablas: `titles, torrents, people, title_people, genres, title_genres, sync_runs`.

- [ ] **Step 1: Levantar Postgres de test**

```bash
docker run --rm -d --name htm-pg -e POSTGRES_PASSWORD=htm -e POSTGRES_DB=htm -p 5433:5432 postgres:16
export DATABASE_URL=postgresql://postgres:htm@localhost:5433/htm
```

- [ ] **Step 2: Write `sql/schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent es STABLE, no IMMUTABLE: no se puede usar en columnas GENERATED ni en índices.
-- Wrapper IMMUTABLE para poder indexar y generar el tsvector sin acentos.
CREATE OR REPLACE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS
$$ SELECT public.unaccent('public.unaccent', $1) $$;

CREATE TABLE IF NOT EXISTS titles (
    id            bigint PRIMARY KEY,
    slug          text UNIQUE NOT NULL,
    kind          text NOT NULL,
    title         text,
    original_title text,
    overview      text,
    year          int,
    release_date  date,
    duration_min  int,
    tmdb_id       bigint,
    imdb_rating   numeric(3,1),
    country       text,
    director      text,
    language      text,
    img_background text,
    img_featured  text,
    trailer       text,
    importer_version text,
    actors_text   text,       -- CSV crudo para el tsvector
    genres_text   text,       -- CSV crudo para el tsvector
    active        boolean NOT NULL DEFAULT true,
    first_seen    timestamptz NOT NULL DEFAULT now(),
    last_seen     timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    search_doc    tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('spanish', f_unaccent(coalesce(title,''))), 'A') ||
        setweight(to_tsvector('spanish', f_unaccent(coalesce(original_title,''))), 'B') ||
        setweight(to_tsvector('spanish', f_unaccent(coalesce(actors_text,'') || ' ' ||
                                                    coalesce(director,''))), 'C') ||
        setweight(to_tsvector('spanish', f_unaccent(coalesce(genres_text,''))), 'D')
    ) STORED
);
CREATE INDEX IF NOT EXISTS idx_titles_tmdb ON titles(tmdb_id);
CREATE INDEX IF NOT EXISTS idx_titles_kind ON titles(kind);
CREATE INDEX IF NOT EXISTS idx_titles_year ON titles(year);
CREATE INDEX IF NOT EXISTS idx_titles_search ON titles USING GIN(search_doc);
CREATE INDEX IF NOT EXISTS idx_titles_title_trgm ON titles USING GIN(f_unaccent(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_titles_otitle_trgm ON titles USING GIN(f_unaccent(original_title) gin_trgm_ops);

CREATE TABLE IF NOT EXISTS torrents (
    id            bigserial PRIMARY KEY,
    title_id      bigint NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    infohash      text,
    magnet        text NOT NULL,
    quality       text,
    size_bytes    bigint,
    size_label    text,
    lang_raw      text,
    lang_norm     text,
    subs          boolean,
    season        int,
    episode       int,
    episode_end   int,
    is_pack       boolean NOT NULL DEFAULT false,
    source_date   date,
    download_type text,
    active        boolean NOT NULL DEFAULT true,
    first_seen    timestamptz NOT NULL DEFAULT now(),
    last_seen     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (title_id, magnet)
);
CREATE INDEX IF NOT EXISTS idx_torrents_title ON torrents(title_id);
CREATE INDEX IF NOT EXISTS idx_torrents_infohash ON torrents(infohash);
CREATE INDEX IF NOT EXISTS idx_torrents_lang ON torrents(lang_norm);
CREATE INDEX IF NOT EXISTS idx_torrents_quality ON torrents(quality);
CREATE INDEX IF NOT EXISTS idx_torrents_se ON torrents(title_id, season, episode);

CREATE TABLE IF NOT EXISTS people (
    id            serial PRIMARY KEY,
    name          text UNIQUE NOT NULL,
    name_unaccent text
);
CREATE INDEX IF NOT EXISTS idx_people_unaccent_trgm ON people USING GIN(name_unaccent gin_trgm_ops);

CREATE TABLE IF NOT EXISTS title_people (
    title_id  bigint NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    person_id int NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    role      text NOT NULL,
    PRIMARY KEY (title_id, person_id, role)
);

CREATE TABLE IF NOT EXISTS genres (
    id   serial PRIMARY KEY,
    name text UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS title_genres (
    title_id bigint NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    genre_id int NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
    PRIMARY KEY (title_id, genre_id)
);

CREATE TABLE IF NOT EXISTS sync_runs (
    id            serial PRIMARY KEY,
    kind          text NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    titles_seen   int NOT NULL DEFAULT 0,
    torrents_upserted int NOT NULL DEFAULT 0,
    errors        int NOT NULL DEFAULT 0,
    ok            boolean
);
```

Nota: `title`/`original_title`/`director`/`actors_text`/`genres_text` alimentan `search_doc`.
Por eso el mapeo debe persistir `actors_text` y `genres_text` (CSV crudo) en `titles`.

- [ ] **Step 3: Write `tests/conftest.py`**

```python
import os, pathlib, psycopg2, pytest

SCHEMA = (pathlib.Path(__file__).parent.parent / "sql" / "schema.sql").read_text()

def _dsn():
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        pytest.skip("DATABASE_URL no seteado (test de integración)")
    return dsn

@pytest.fixture()
def db():
    conn = psycopg2.connect(_dsn())
    conn.autocommit = True
    with conn.cursor() as cur:
        # DB limpia por test
        cur.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
        cur.execute(SCHEMA)
    yield conn
    conn.close()
```

- [ ] **Step 4: Write the failing test** — en `tests/test_db.py`:

```python
def test_schema_creates_tables(db):
    with db.cursor() as cur:
        cur.execute("""
            SELECT table_name FROM information_schema.tables
            WHERE table_schema='public' ORDER BY table_name
        """)
        tables = {r[0] for r in cur.fetchall()}
    assert {"titles","torrents","people","title_people","genres","title_genres","sync_runs"} <= tables
```

- [ ] **Step 5: Run test to verify it fails then passes**

Run: `python -m pytest tests/test_db.py::test_schema_creates_tables -v`
Expected: primero FAIL si `schema.sql` no existe; tras crearlo, PASS. (Si no hay `DATABASE_URL`, el test se SKIPea — levantá el Postgres del Step 1.)

- [ ] **Step 6: Commit**

```bash
git add sql/schema.sql tests/conftest.py tests/test_db.py
git commit -m "feat: esquema Postgres (titles, torrents, people, genres, sync_runs) + fixture de db"
```

---

## Task 6: Capa DB (UPSERT + reconcile + sync_runs)

**Files:**
- Create: `mirror/db.py`
- Test: `tests/test_db.py` (agregar tests de upsert/idempotencia/reconcile)

**Interfaces:**
- Consumes: filas de `mapping.build_title/build_torrents/extract_people/extract_genres`; `sql/schema.sql`.
- Produces:
  - `connect(dsn:str) -> conn`
  - `upsert_detail(conn, detail:dict, seen_at) -> int` — UPSERT título + torrents + people + genres; devuelve nº de torrents upserted. Marca `last_seen=seen_at`.
  - `start_run(conn, kind:str) -> run_id`
  - `finish_run(conn, run_id, titles_seen, torrents_upserted, errors, ok)`
  - `reconcile(conn, kind:str, run_started)` — marca `active=false` en titles/torrents del `kind` con `last_seen < run_started`.

- [ ] **Step 1: Write the failing tests** — agregar a `tests/test_db.py`:

```python
import json, pathlib, datetime
from mirror import db as db_mod   # alias: el parámetro-fixture `db` (connection) sombrearía el módulo

FIX = pathlib.Path(__file__).parent / "fixtures"
MOVIE = json.load(open(FIX / "movie.json"))
ANIME = json.load(open(FIX / "anime.json"))

def _count(conn, table):
    with conn.cursor() as cur:
        cur.execute(f"SELECT count(*) FROM {table}")
        return cur.fetchone()[0]

def test_upsert_detail_inserts(db):
    now = datetime.datetime.now(datetime.timezone.utc)
    n = db_mod.upsert_detail(db, MOVIE, now)
    assert n == len(MOVIE["downloads"])
    assert _count(db, "titles") == 1
    assert _count(db, "torrents") == len(MOVIE["downloads"])
    assert _count(db, "genres") >= 1
    assert _count(db, "people") >= 1

def test_upsert_idempotent(db):
    now = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, now)
    db_mod.upsert_detail(db, MOVIE, now)  # segunda vez no duplica
    assert _count(db, "titles") == 1
    assert _count(db, "torrents") == len(MOVIE["downloads"])

def test_upsert_reactivates_title(db):
    now = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, now)
    with db.cursor() as cur:                      # simular desactivación por reconcile
        cur.execute("UPDATE titles SET active=false")
    db_mod.upsert_detail(db, MOVIE, now)          # re-ver el título debe reactivarlo
    with db.cursor() as cur:
        cur.execute("SELECT active FROM titles WHERE slug=%s", (MOVIE["slug"],))
        assert cur.fetchone()[0] is True

def test_reconcile_deactivates_missing(db):
    old = datetime.datetime(2020,1,1, tzinfo=datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, old)
    run_start = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, ANIME, run_start)   # solo el anime se ve en esta corrida
    db_mod.reconcile(db, "movie", run_start)
    with db.cursor() as cur:
        cur.execute("SELECT active FROM titles WHERE slug=%s", (MOVIE["slug"],))
        assert cur.fetchone()[0] is False
        cur.execute("SELECT active FROM titles WHERE slug=%s", (ANIME["slug"],))
        assert cur.fetchone()[0] is True
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_db.py -v`
Expected: FAIL (mirror.db no existe)

- [ ] **Step 3: Implement `mirror/db.py`**

```python
import psycopg2
from mirror.mapping import build_title, build_torrents, extract_people, extract_genres

def connect(dsn: str):
    return psycopg2.connect(dsn)

_TITLE_COLS = ["id","slug","kind","title","original_title","overview","year","release_date",
    "duration_min","tmdb_id","imdb_rating","country","director","language","img_background",
    "img_featured","trailer","importer_version","actors_text","genres_text"]

def upsert_detail(conn, detail: dict, seen_at) -> int:
    t = build_title(detail)
    t["actors_text"] = detail.get("actors") or ""
    t["genres_text"] = detail.get("genres") or ""
    torrents = build_torrents(detail)
    people = extract_people(detail)
    genres = extract_genres(detail)
    with conn:
        with conn.cursor() as cur:
            cols = ",".join(_TITLE_COLS)
            ph = ",".join(["%s"] * len(_TITLE_COLS))
            updates = ",".join(f"{c}=EXCLUDED.{c}" for c in _TITLE_COLS if c not in ("id","slug"))
            cur.execute(
                f"INSERT INTO titles ({cols}, last_seen, updated_at) VALUES ({ph}, %s, now()) "
                f"ON CONFLICT (id) DO UPDATE SET {updates}, last_seen=EXCLUDED.last_seen, "
                f"updated_at=now(), active=true",   # reactivar: un título re-visto vuelve a estar activo
                [t[c] for c in _TITLE_COLS] + [seen_at],
            )
            title_id = t["id"]

            # torrents: UPSERT por (title_id, magnet)
            for tr in torrents:
                cur.execute(
                    "INSERT INTO torrents (title_id,infohash,magnet,quality,size_bytes,size_label,"
                    "lang_raw,lang_norm,subs,season,episode,episode_end,is_pack,source_date,"
                    "download_type,last_seen,active) VALUES "
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true) "
                    "ON CONFLICT (title_id, magnet) DO UPDATE SET "
                    "infohash=EXCLUDED.infohash,quality=EXCLUDED.quality,size_bytes=EXCLUDED.size_bytes,"
                    "size_label=EXCLUDED.size_label,lang_raw=EXCLUDED.lang_raw,lang_norm=EXCLUDED.lang_norm,"
                    "subs=EXCLUDED.subs,season=EXCLUDED.season,episode=EXCLUDED.episode,"
                    "episode_end=EXCLUDED.episode_end,is_pack=EXCLUDED.is_pack,source_date=EXCLUDED.source_date,"
                    "download_type=EXCLUDED.download_type,last_seen=EXCLUDED.last_seen,active=true",
                    [title_id, tr["infohash"], tr["magnet"], tr["quality"], tr["size_bytes"],
                     tr["size_label"], tr["lang_raw"], tr["lang_norm"], tr["subs"], tr["season"],
                     tr["episode"], tr["episode_end"], tr["is_pack"], tr["source_date"],
                     tr["download_type"], seen_at],
                )

            # people + join
            for name, role in people:
                cur.execute(
                    "INSERT INTO people (name, name_unaccent) VALUES (%s, unaccent(%s)) "
                    "ON CONFLICT (name) DO UPDATE SET name_unaccent=EXCLUDED.name_unaccent RETURNING id",
                    (name, name),
                )
                pid = cur.fetchone()[0]
                cur.execute(
                    "INSERT INTO title_people (title_id, person_id, role) VALUES (%s,%s,%s) "
                    "ON CONFLICT DO NOTHING", (title_id, pid, role),
                )
            # genres + join
            for g in genres:
                cur.execute(
                    "INSERT INTO genres (name) VALUES (%s) ON CONFLICT (name) DO UPDATE SET name=EXCLUDED.name RETURNING id",
                    (g,),
                )
                gid = cur.fetchone()[0]
                cur.execute(
                    "INSERT INTO title_genres (title_id, genre_id) VALUES (%s,%s) ON CONFLICT DO NOTHING",
                    (title_id, gid),
                )
    return len(torrents)

def start_run(conn, kind: str) -> int:
    with conn, conn.cursor() as cur:
        cur.execute("INSERT INTO sync_runs (kind) VALUES (%s) RETURNING id", (kind,))
        return cur.fetchone()[0]

def finish_run(conn, run_id, titles_seen, torrents_upserted, errors, ok):
    with conn, conn.cursor() as cur:
        cur.execute(
            "UPDATE sync_runs SET finished_at=now(), titles_seen=%s, torrents_upserted=%s, "
            "errors=%s, ok=%s WHERE id=%s",
            (titles_seen, torrents_upserted, errors, ok, run_id),
        )

def reconcile(conn, kind: str, run_started):
    with conn, conn.cursor() as cur:
        cur.execute(
            "UPDATE titles SET active=false WHERE kind=%s AND last_seen < %s", (kind, run_started))
        cur.execute(
            "UPDATE torrents t SET active=false FROM titles ti "
            "WHERE t.title_id=ti.id AND ti.kind=%s AND t.last_seen < %s", (kind, run_started))
```

Nota: el UPSERT fila-a-fila es suficiente para ~14k títulos.

- [ ] **Step 4: Run tests to verify pass**

Run: `python -m pytest tests/test_db.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Commit**

```bash
git add mirror/db.py tests/test_db.py
git commit -m "feat: capa DB (upsert idempotente, sync_runs, reconcile soft-delete)"
```

---

## Task 7: Cliente HTTP de hacktorrent

**Files:**
- Create: `mirror/hacktorrent.py`
- Test: `tests/test_hacktorrent.py`

**Interfaces:**
- Consumes: `Config`.
- Produces: clase `HackTorrentClient(cfg)` con:
  - `list_page(kind:str, page:int, per:int=20) -> dict` (kind in movies/animes/released)
  - `iter_slugs(kind:str) -> Iterator[str]` (pagina hasta `pages`)
  - `get_detail(kind:str, slug:str) -> dict` (kind in movie/anime)
  - fallback a flaresolverr si el status es 403/503 (challenge Cloudflare).

- [ ] **Step 1: Write the failing test** — `tests/test_hacktorrent.py`:

```python
import time
import pytest
from mirror.config import Config
from mirror.hacktorrent import HackTorrentClient

@pytest.fixture(autouse=True)
def _no_sleep(monkeypatch):
    # el cliente hace time.sleep(rate_limit_s) tras cada request; sin esto los tests dormirían de verdad
    monkeypatch.setattr(time, "sleep", lambda *a, **k: None)

class FakeResp:
    def __init__(self, status, payload): self.status_code=status; self._p=payload
    def json(self): return self._p
    def raise_for_status(self): pass   # _get llama r.raise_for_status() en respuestas 200
    @property
    def text(self): import json; return json.dumps(self._p)

def make_client(monkeypatch, responses):
    cfg = Config.from_env({"DATABASE_URL":"x"})
    client = HackTorrentClient(cfg)
    calls = {"n":0}
    def fake_get(url, **kw):
        calls["n"] += 1
        return responses.pop(0)
    monkeypatch.setattr(client._session, "get", fake_get)
    return client, calls

def test_iter_slugs_paginates(monkeypatch):
    responses = [
        FakeResp(200, {"movies":[{"slug":"a"},{"slug":"b"}], "total":3, "pages":2}),
        FakeResp(200, {"movies":[{"slug":"c"}], "total":3, "pages":2}),
    ]
    client, _ = make_client(monkeypatch, responses)
    slugs = list(client.iter_slugs("movies"))
    assert slugs == ["a","b","c"]

def test_get_detail(monkeypatch):
    responses = [FakeResp(200, {"slug":"x","downloads":[]})]
    client, _ = make_client(monkeypatch, responses)
    d = client.get_detail("movie", "x")
    assert d["slug"] == "x"
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_hacktorrent.py -v`
Expected: FAIL (ModuleNotFoundError)

- [ ] **Step 3: Implement `mirror/hacktorrent.py`**

```python
import time
import requests

_LIST_KEY = {"movies":"movies", "animes":"animes", "released":"movies"}

class HackTorrentClient:
    def __init__(self, cfg):
        self.cfg = cfg
        self._session = requests.Session()
        self._session.headers["User-Agent"] = cfg.user_agent

    def _get(self, url: str) -> dict:
        r = self._session.get(url, timeout=30)
        if r.status_code in (403, 503) and self.cfg.flaresolverr_url:
            return self._via_flaresolverr(url)
        r.raise_for_status()
        return r.json()

    def _via_flaresolverr(self, url: str) -> dict:
        import json
        resp = self._session.post(
            self.cfg.flaresolverr_url,
            json={"cmd": "request.get", "url": url, "maxTimeout": 60000},
            timeout=90,
        )
        resp.raise_for_status()
        html = resp.json()["solution"]["response"]
        start = html.find("{")
        return json.loads(html[start:])  # el body JSON viene dentro del HTML resuelto

    def list_page(self, kind: str, page: int, per: int = 20) -> dict:
        url = f"{self.cfg.base_url}/{kind}?posts_per_page={per}&page={page}"
        data = self._get(url)
        time.sleep(self.cfg.rate_limit_s)
        return data

    def iter_slugs(self, kind: str):
        key = _LIST_KEY[kind]
        page = 1
        pages = 1
        while page <= pages:
            data = self.list_page(kind, page, per=20)
            pages = int(data.get("pages") or 1)
            for item in data.get(key, []):
                yield item["slug"]
            page += 1

    def get_detail(self, kind: str, slug: str) -> dict:
        url = f"{self.cfg.base_url}/{kind}/{slug}"
        data = self._get(url)
        time.sleep(self.cfg.rate_limit_s)
        return data
```

Nota: en tests, `rate_limit_s` default es 1.0 — para que no ralenticen, seteá
`monkeypatch.setattr(time, "sleep", lambda *_: None)` en un fixture autouse de `test_hacktorrent.py`, o instanciá el Config con `RATE_LIMIT_S:"0"`.

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_hacktorrent.py -v`
Expected: PASS (2 passed)

- [ ] **Step 5: Commit**

```bash
git add mirror/hacktorrent.py tests/test_hacktorrent.py
git commit -m "feat: cliente HTTP de hacktorrent (paginado + detalle + fallback flaresolverr)"
```

---

## Task 8: Crawler (incremental + full + CLI)

**Files:**
- Create: `mirror/crawler.py`
- Test: `tests/test_crawler.py`

**Interfaces:**
- Consumes: `HackTorrentClient`, `db.*`, `mapping.kind_of`.
- Produces:
  - `run_full(conn, client, now_fn) -> dict` — pagina movies+animes, upsert cada detalle, reconcile por kind. Devuelve `{"titles":N,"torrents":M,"errors":E}`.
  - `run_incremental(conn, client, now_fn, window_pages:int=3) -> dict` — recorre `released` (window_pages), upsert detalles.
  - `main(argv)` — CLI: `--mode full|incremental`.

- [ ] **Step 1: Write the failing test** — `tests/test_crawler.py`:

```python
import json, pathlib, datetime
from mirror import crawler

FIX = pathlib.Path(__file__).parent / "fixtures"
MOVIE = json.load(open(FIX / "movie.json"))
ANIME = json.load(open(FIX / "anime.json"))

class FakeClient:
    def __init__(self):
        self.details = {("movie", MOVIE["slug"]): MOVIE, ("anime", ANIME["slug"]): ANIME}
    def iter_slugs(self, kind):
        if kind == "movies": return iter([MOVIE["slug"]])
        if kind == "animes": return iter([ANIME["slug"]])
        if kind == "released": return iter([MOVIE["slug"]])
        return iter([])
    def get_detail(self, kind, slug):
        return self.details[(kind, slug)]

def _fixed_now():
    return datetime.datetime(2026,7,24,12,0, tzinfo=datetime.timezone.utc)

def test_run_full_ingests_both(db):
    res = crawler.run_full(db, FakeClient(), _fixed_now)
    assert res["titles"] == 2
    with db.cursor() as cur:
        cur.execute("SELECT count(*) FROM titles WHERE active")
        assert cur.fetchone()[0] == 2

def test_run_incremental_uses_released(db):
    res = crawler.run_incremental(db, FakeClient(), _fixed_now)
    assert res["titles"] == 1
    with db.cursor() as cur:
        cur.execute("SELECT slug FROM titles")
        assert cur.fetchone()[0] == MOVIE["slug"]
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_crawler.py -v`
Expected: FAIL (mirror.crawler no existe)

- [ ] **Step 3: Implement `mirror/crawler.py`**

```python
import argparse
import datetime
import os
import sys
from mirror import db as dbmod
from mirror.config import Config
from mirror.hacktorrent import HackTorrentClient

def _now():
    return datetime.datetime.now(datetime.timezone.utc)

def _ingest_slugs(conn, client, slugs, seen_at):
    titles = torrents = errors = 0
    for kind, slug in slugs:
        try:
            detail = client.get_detail(kind, slug)
            n = dbmod.upsert_detail(conn, detail, seen_at)
            titles += 1
            torrents += n
        except Exception as e:  # un detalle roto no aborta el crawl
            errors += 1
            print(f"[crawler] error {kind}/{slug}: {e}", file=sys.stderr)
    return titles, torrents, errors

def run_full(conn, client, now_fn=_now) -> dict:
    started = now_fn()
    run_id = dbmod.start_run(conn, "full")
    titles = torrents = errors = 0
    for list_kind, detail_kind in (("movies","movie"), ("animes","anime")):
        slugs = [(detail_kind, s) for s in client.iter_slugs(list_kind)]
        t, tr, e = _ingest_slugs(conn, client, slugs, started)
        titles += t; torrents += tr; errors += e
    # Reconciliar (desactivar ausentes) SOLO si el crawl fue lo bastante completo. Si demasiados
    # detalles fallaron, no confiamos en las "ausencias" y evitamos desactivar en masa.
    if titles and errors <= titles * 0.1:
        dbmod.reconcile(conn, "movie", started)
        dbmod.reconcile(conn, "anime", started)
    else:
        print(f"[crawler] reconcile OMITIDO (crawl incompleto): errors={errors} titles={titles}",
              file=sys.stderr)
    dbmod.finish_run(conn, run_id, titles, torrents, errors, errors == 0)
    return {"titles": titles, "torrents": torrents, "errors": errors}

def run_incremental(conn, client, now_fn=_now) -> dict:
    started = now_fn()
    run_id = dbmod.start_run(conn, "incremental")
    # /released es un feed FIJO de los títulos más recientes: ignora page/posts_per_page y no
    # trae 'pages', así que iter_slugs devuelve ese conjunto chico (los ~N más nuevos) en 1 request.
    # Son películas; el anime episódico nuevo y el resto del catálogo los cubre el full semanal.
    slugs = [("movie", s) for s in client.iter_slugs("released")]
    titles, torrents, errors = _ingest_slugs(conn, client, slugs, started)
    dbmod.finish_run(conn, run_id, titles, torrents, errors, errors == 0)
    return {"titles": titles, "torrents": torrents, "errors": errors}

def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["full","incremental"], required=True)
    args = ap.parse_args(argv)
    cfg = Config.from_env(os.environ)
    conn = dbmod.connect(cfg.database_url)
    client = HackTorrentClient(cfg)
    res = run_full(conn, client) if args.mode == "full" else run_incremental(conn, client)
    print(f"[crawler] {args.mode}: {res}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_crawler.py -v`
Expected: PASS (2 passed)

- [ ] **Step 5: Smoke real (opcional, con red + DB)**

```bash
export DATABASE_URL=postgresql://postgres:htm@localhost:5433/htm
psql "$DATABASE_URL" -f sql/schema.sql
RATE_LIMIT_S=0.3 python -m mirror.crawler --mode incremental
psql "$DATABASE_URL" -c "SELECT count(*) FROM titles; SELECT count(*) FROM torrents;"
```

- [ ] **Step 6: Commit**

```bash
git add mirror/crawler.py tests/test_crawler.py
git commit -m "feat: crawler incremental/full + CLI + reconcile por kind"
```

---

## Task 9: Serializadores (cara drop-in + rica)

**Files:**
- Create: `mirror/serialize.py`
- Test: `tests/test_serialize.py`

**Interfaces:**
- Consumes: filas de DB (dicts) para un título + sus torrents.
- Produces:
  - `to_dropin_item(title_row:dict) -> dict` — item de `/search` con el shape de hacktorrent
    (`id, slug, title, original_title, overview, tmdb_id, year, type, language, background_image, featured, ...`).
  - `to_dropin_detail(title_row:dict, torrent_rows:list[dict]) -> dict` — detalle con `downloads[]`
    en el shape de hacktorrent (`quality, date, size, subs, download_type, download_link, language, season, episode`).
  - `to_rich_title(title_row:dict, torrent_rows, people, genres) -> dict` — anime agrupado por temporada.

- [ ] **Step 1: Write the failing test** — `tests/test_serialize.py`:

```python
import datetime
from mirror.serialize import to_dropin_item, to_dropin_detail, to_rich_title

TITLE = {
    "id": 5060, "slug": "matrix-revoluciones", "kind": "movie",
    "title": "Matrix: Revoluciones", "original_title": "The Matrix Revolutions",
    "overview": "…", "tmdb_id": 605, "year": 2003, "language": "Latino",
    "img_background": "bg.jpg", "img_featured": "ft.jpg", "release_date": datetime.date(2003,11,5),
}
TOR = [{
    "quality":"WEB-DL 1080p","source_date":datetime.date(2024,1,5),"size_label":"9.06 GB",
    "subs":True,"download_type":"link","magnet":"magnet:?xt=urn:btih:AB","lang_raw":"Latino/Inglés",
    "season":None,"episode":None,"episode_end":None,"is_pack":False,
}]

def test_dropin_item_type_pelicula():
    it = to_dropin_item(TITLE)
    assert it["slug"] == "matrix-revoluciones"
    assert it["type"] == "pelicula"          # movie -> "pelicula" (shape hacktorrent)
    assert it["tmdb_id"] == "605"            # hacktorrent devuelve tmdb_id como string
    assert it["background_image"] == "bg.jpg"

def test_dropin_detail_downloads_shape():
    d = to_dropin_detail(TITLE, TOR)
    dl = d["downloads"][0]
    assert dl["download_link"].startswith("magnet:")
    assert dl["size"] == "9.06 GB"
    assert dl["date"] == "20240105"
    assert dl["subs"] == 1
    assert dl["language"] == "Latino/Inglés"

def test_dropin_detail_anime_has_season_episode():
    anime_title = {**TITLE, "kind":"anime", "slug":"slime"}
    anime_tor = [{**TOR[0], "season":3, "episode":1}]
    d = to_dropin_detail(anime_title, anime_tor)
    assert d["type"] == "anime"
    assert d["downloads"][0]["season"] == 3
    assert d["downloads"][0]["episode"] == 1

def test_rich_title_groups_anime_by_season():
    anime_title = {**TITLE, "kind":"anime", "slug":"slime"}
    tor = [
        {**TOR[0], "season":1, "episode":1},
        {**TOR[0], "season":1, "episode":2},
        {**TOR[0], "season":2, "episode":1},
    ]
    r = to_rich_title(anime_title, tor, people=[], genres=[])
    seasons = {s["season"]: len(s["episodes"]) for s in r["seasons"]}
    assert seasons == {1:2, 2:1}

def test_rich_title_pack_separated_from_seasons():
    anime_title = {**TITLE, "kind":"anime", "slug":"slime"}
    tor = [
        {**TOR[0], "season":1, "episode":1, "is_pack":False},
        {**TOR[0], "season":1, "episode":None, "is_pack":True},   # pack de temporada
    ]
    r = to_rich_title(anime_title, tor, people=[], genres=[])
    assert len(r["packs"]) == 1                                   # el pack va en packs
    all_eps = [e for s in r["seasons"] for e in s["episodes"]]
    assert len(all_eps) == 1 and all(not e["is_pack"] for e in all_eps)  # y NO dentro de la temporada
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_serialize.py -v`
Expected: FAIL (ModuleNotFoundError)

- [ ] **Step 3: Implement `mirror/serialize.py`**

```python
def _type(kind: str) -> str:
    return "anime" if kind == "anime" else "pelicula"

def _date_str(d):
    return d.strftime("%Y%m%d") if d else ""

def to_dropin_item(t: dict) -> dict:
    return {
        "id": t.get("id"),
        "slug": t.get("slug"),
        "title": t.get("title"),
        "original_title": t.get("original_title"),
        "overview": t.get("overview"),
        "tmdb_id": str(t["tmdb_id"]) if t.get("tmdb_id") is not None else "",
        "year": str(t["year"]) if t.get("year") is not None else "",
        "type": _type(t.get("kind")),
        "language": t.get("language"),
        "background_image": t.get("img_background"),
        "featured": t.get("img_featured"),
    }

def _download(tr: dict) -> dict:
    d = {
        "quality": tr.get("quality"),
        "date": _date_str(tr.get("source_date")),
        "size": tr.get("size_label"),
        "subs": 1 if tr.get("subs") else 0,
        "total_download": "0",
        "download_type": tr.get("download_type") or "link",
        "download_link": tr.get("magnet"),
        "language": tr.get("lang_raw"),
    }
    if tr.get("season") is not None:
        d["season"] = tr["season"]
    if tr.get("episode") is not None:
        d["episode"] = tr["episode"]
    return d

def to_dropin_detail(t: dict, torrents: list) -> dict:
    base = to_dropin_item(t)
    base["downloads"] = [_download(tr) for tr in torrents]
    return base

def to_rich_title(t: dict, torrents: list, people: list, genres: list) -> dict:
    out = {
        "slug": t.get("slug"), "kind": t.get("kind"), "title": t.get("title"),
        "original_title": t.get("original_title"), "tmdb_id": t.get("tmdb_id"),
        "year": t.get("year"), "overview": t.get("overview"),
        "people": [{"name": n, "role": r} for n, r in people],
        "genres": list(genres),
    }
    if t.get("kind") == "anime":
        by_season = {}
        packs = []
        for tr in torrents:
            if tr.get("is_pack"):
                packs.append(tr)          # los packs van SOLO acá, no dentro de una temporada
            else:
                by_season.setdefault(tr.get("season"), []).append(tr)
        out["seasons"] = [
            {"season": s, "episodes": sorted(v, key=lambda x: (x.get("episode") or 0))}
            for s, v in sorted(by_season.items(), key=lambda kv: (kv[0] is None, kv[0]))
        ]
        out["packs"] = packs
    else:
        out["torrents"] = list(torrents)
    return out
```

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest tests/test_serialize.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: Commit**

```bash
git add mirror/serialize.py tests/test_serialize.py
git commit -m "feat: serializadores drop-in (shape hacktorrent) y rico (anime por temporada)"
```

---

## Task 10: Repo de lectura + API Flask

**Files:**
- Create: `mirror/repo.py`
- Create: `mirror/api.py`
- Test: `tests/test_api.py`

**Interfaces:**
- Consumes: conexión DB, `serialize.*`.
- Produces (`repo.py`):
  - `search_titles(conn, q=None, kind=None, year=None, genre=None, actor=None, director=None, lang=None, quality=None, tmdb_id=None, season=None, episode=None, page=1, page_size=20) -> list[dict]` — FTS con pesos + `ts_rank_cd`, fallback trigram (typos), orden por relevancia.
  - `suggest_titles(conn, q, limit=10) -> list[dict]` — autocompletar por trigram (slug, title, kind, year).
  - `get_title_by_slug(conn, slug) -> dict|None` (title row)
  - `get_torrents(conn, title_id, active_only=True) -> list[dict]`
  - `get_people(conn, title_id) -> list[tuple]`, `get_genres(conn, title_id) -> list[str]`
  - `last_sync(conn) -> dict|None`
- Produces (`api.py`): `create_app(dsn) -> Flask` con rutas drop-in y ricas.

- [ ] **Step 1: Write the failing test** — `tests/test_api.py`:

```python
import json, pathlib, datetime
from mirror import db, api

FIX = pathlib.Path(__file__).parent / "fixtures"
MOVIE = json.load(open(FIX / "movie.json"))
ANIME = json.load(open(FIX / "anime.json"))

def _seed(conn):
    now = datetime.datetime.now(datetime.timezone.utc)
    db.upsert_detail(conn, MOVIE, now)
    db.upsert_detail(conn, ANIME, now)

def _client(db_conn, dsn):
    _seed(db_conn)
    app = api.create_app(dsn)
    app.config.update(TESTING=True)
    return app.test_client()

def test_dropin_search_accent_insensitive(db, request):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    r = c.get("/wp-json/wpreact/v1/search?query=revoluciones")
    assert r.status_code == 200
    slugs = [x["slug"] for x in r.get_json()["results"]]
    assert "matrix-revoluciones" in slugs

def test_dropin_search_by_tmdb(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    r = c.get("/wp-json/wpreact/v1/search?tmdb_id=605")
    assert any(x["slug"] == "matrix-revoluciones" for x in r.get_json()["results"])

def test_dropin_movie_detail(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    r = c.get("/wp-json/wpreact/v1/movie/matrix-revoluciones")
    body = r.get_json()
    assert body["type"] == "pelicula"
    assert len(body["downloads"]) == len(MOVIE["downloads"])
    assert body["downloads"][0]["download_link"].startswith("magnet:")

def test_rich_search_filters(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    r = c.get("/api/search?kind=anime")
    slugs = [x["slug"] for x in r.get_json()["results"]]
    assert ANIME["slug"] in slugs and "matrix-revoluciones" not in slugs

def test_search_season_episode_same_row(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    slug = ANIME["slug"]  # slime: S3 tiene ep 1-24, S4 tiene ep 1-13
    # (season=3, episode=20) existe en una sola fila -> matchea
    r = c.get("/api/search?kind=anime&season=3&episode=20")
    assert slug in [x["slug"] for x in r.get_json()["results"]]
    # (season=4, episode=20) NO existe en ninguna fila (S4 llega a 13); no debe matchear por cruce
    r = c.get("/api/search?kind=anime&season=4&episode=20")
    assert slug not in [x["slug"] for x in r.get_json()["results"]]

def test_search_accent_insensitive_no_accents(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    # "revoluciones" sin acentos debe encontrar "Matrix: Revoluciones"
    r = c.get("/api/search?q=revolucion")
    slugs = [x["slug"] for x in r.get_json()["results"]]
    assert "matrix-revoluciones" in slugs

def test_suggest_typo_tolerant(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    # typo: "matriix" -> debe sugerir matrix por trigram
    r = c.get("/api/suggest?q=matriix")
    slugs = [s["slug"] for s in r.get_json()["suggestions"]]
    assert "matrix-revoluciones" in slugs

def test_health(db):
    import os
    c = _client(db, os.environ["DATABASE_URL"])
    r = c.get("/api/health")
    assert r.get_json()["ok"] is True
```

- [ ] **Step 2: Run to verify it fails**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL (mirror.api no existe)

- [ ] **Step 3: Implement `mirror/repo.py`**

```python
from psycopg2.extras import RealDictCursor

def _dict_cur(conn):
    return conn.cursor(cursor_factory=RealDictCursor)

def search_titles(conn, q=None, kind=None, year=None, genre=None, actor=None,
                  director=None, lang=None, quality=None, tmdb_id=None,
                  season=None, episode=None, page=1, page_size=20):
    # Named params (%(name)s): evita bugs de orden posicional al mezclar SELECT/WHERE/ORDER.
    p = {}
    where = ["t.active"]
    joins = []
    rank_expr = "0::float4"
    order = "t.imdb_rating DESC NULLS LAST, t.year DESC NULLS LAST"

    if tmdb_id:
        where.append("t.tmdb_id = %(tmdb_id)s"); p["tmdb_id"] = int(tmdb_id)
    if q:
        p["q"] = q
        # FTS con pesos (semántico) OR word_similarity (typos, incluso en títulos multi-palabra).
        # <%% = operador <% de pg_trgm escapado para psycopg2 (usa el índice GIN trigram).
        where.append("(t.search_doc @@ plainto_tsquery('spanish', f_unaccent(%(q)s)) "
                     "OR f_unaccent(%(q)s) <%% f_unaccent(t.title) "
                     "OR f_unaccent(%(q)s) <%% f_unaccent(t.original_title))")
        rank_expr = ("GREATEST("
                     "ts_rank_cd(t.search_doc, plainto_tsquery('spanish', f_unaccent(%(q)s))), "
                     "word_similarity(f_unaccent(%(q)s), f_unaccent(t.title)))")
        order = "rank DESC, " + order
    if kind:
        where.append("t.kind = %(kind)s"); p["kind"] = kind
    if year:
        where.append("t.year = %(year)s"); p["year"] = int(year)
    # Filtros de nivel-torrent (lang/quality/season/episode): deben cumplirse TODOS en la MISMA
    # fila de torrents -> un solo JOIN con todas las condiciones (no joins separados, que matchearían
    # season de una fila con episode de otra).
    tf_conds = []
    if lang:
        tf_conds.append("tf.lang_norm=%(lang)s"); p["lang"] = lang.upper()
    if quality:
        tf_conds.append("tf.quality ILIKE %(quality)s"); p["quality"] = f"%{quality}%"
    if season:
        tf_conds.append("tf.season=%(season)s"); p["season"] = int(season)
    if episode:
        tf_conds.append("tf.episode=%(episode)s"); p["episode"] = int(episode)
    if tf_conds:
        joins.append("JOIN torrents tf ON tf.title_id=t.id AND tf.active AND " + " AND ".join(tf_conds))
    if genre:
        joins.append("JOIN title_genres tg ON tg.title_id=t.id "
                     "JOIN genres g ON g.id=tg.genre_id AND f_unaccent(g.name) ILIKE f_unaccent(%(genre)s)")
        p["genre"] = f"%{genre}%"
    if actor:
        joins.append("JOIN title_people tpa ON tpa.title_id=t.id AND tpa.role='actor' "
                     "JOIN people pa ON pa.id=tpa.person_id AND pa.name_unaccent ILIKE f_unaccent(%(actor)s)")
        p["actor"] = f"%{actor}%"
    if director:
        where.append("f_unaccent(t.director) ILIKE f_unaccent(%(director)s)"); p["director"] = f"%{director}%"

    p["limit"] = int(page_size); p["offset"] = (int(page) - 1) * int(page_size)
    sql = (f"SELECT DISTINCT t.*, {rank_expr} AS rank FROM titles t " + " ".join(joins) +
           " WHERE " + " AND ".join(where) +
           f" ORDER BY {order} LIMIT %(limit)s OFFSET %(offset)s")
    with _dict_cur(conn) as cur:
        cur.execute(sql, p)
        return [dict(r) for r in cur.fetchall()]

def suggest_titles(conn, q, limit=10):
    # Autocompletar: word_similarity sobre f_unaccent(title) (tolera typos aun en títulos
    # multi-palabra, ej. "matriix" -> "Matrix: Revoluciones"). <%% = operador <% escapado.
    with _dict_cur(conn) as cur:
        cur.execute(
            "SELECT slug, title, kind, year FROM titles "
            "WHERE active AND f_unaccent(%(q)s) <%% f_unaccent(title) "
            "ORDER BY word_similarity(f_unaccent(%(q)s), f_unaccent(title)) DESC LIMIT %(limit)s",
            {"q": q, "limit": int(limit)})
        return [dict(r) for r in cur.fetchall()]

def get_title_by_slug(conn, slug):
    with _dict_cur(conn) as cur:
        cur.execute("SELECT * FROM titles WHERE slug=%s AND active", (slug,))
        r = cur.fetchone()
        return dict(r) if r else None

def get_torrents(conn, title_id, active_only=True):
    with _dict_cur(conn) as cur:
        cur.execute("SELECT * FROM torrents WHERE title_id=%s" +
                    (" AND active" if active_only else "") +
                    " ORDER BY season NULLS FIRST, episode NULLS FIRST, size_bytes DESC",
                    (title_id,))
        return [dict(r) for r in cur.fetchall()]

def get_people(conn, title_id):
    with conn.cursor() as cur:
        cur.execute("SELECT p.name, tp.role FROM title_people tp "
                    "JOIN people p ON p.id=tp.person_id WHERE tp.title_id=%s", (title_id,))
        return list(cur.fetchall())

def get_genres(conn, title_id):
    with conn.cursor() as cur:
        cur.execute("SELECT g.name FROM title_genres tg JOIN genres g ON g.id=tg.genre_id "
                    "WHERE tg.title_id=%s", (title_id,))
        return [r[0] for r in cur.fetchall()]

def last_sync(conn):
    with _dict_cur(conn) as cur:
        cur.execute("SELECT kind, finished_at, titles_seen, ok FROM sync_runs "
                    "WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1")
        r = cur.fetchone()
        return dict(r) if r else None
```

- [ ] **Step 4: Implement `mirror/api.py`**

```python
import os
from flask import Flask, request, jsonify, abort
from mirror import db, repo, serialize

def create_app(dsn=None) -> Flask:
    dsn = dsn or os.environ["DATABASE_URL"]
    app = Flask(__name__)

    def conn():
        return db.connect(dsn)

    # ---- Cara A: drop-in hacktorrent ----
    @app.get("/wp-json/wpreact/v1/search")
    def dropin_search():
        c = conn()
        try:
            rows = repo.search_titles(
                c, q=request.args.get("query"),
                tmdb_id=request.args.get("tmdb_id"), page_size=20)
            return jsonify({"results": [serialize.to_dropin_item(r) for r in rows]})
        finally:
            c.close()

    def _dropin_detail(kind, slug):
        c = conn()
        try:
            t = repo.get_title_by_slug(c, slug)
            if not t or t["kind"] != kind:
                abort(404)
            torrents = repo.get_torrents(c, t["id"])
            return jsonify(serialize.to_dropin_detail(t, torrents))
        finally:
            c.close()

    @app.get("/wp-json/wpreact/v1/movie/<slug>")
    def dropin_movie(slug):
        return _dropin_detail("movie", slug)

    @app.get("/wp-json/wpreact/v1/anime/<slug>")
    def dropin_anime(slug):
        return _dropin_detail("anime", slug)

    # ---- Cara B: API rica ----
    @app.get("/api/search")
    def rich_search():
        c = conn()
        try:
            args = request.args
            rows = repo.search_titles(
                c, q=args.get("q"), kind=args.get("kind"), year=args.get("year"),
                genre=args.get("genre"), actor=args.get("actor"), director=args.get("director"),
                lang=args.get("lang"), quality=args.get("quality"), tmdb_id=args.get("tmdb_id"),
                season=args.get("season"), episode=args.get("episode"),
                page=int(args.get("page", 1)), page_size=int(args.get("page_size", 20)))
            return jsonify({"results": [serialize.to_dropin_item(r) for r in rows]})
        finally:
            c.close()

    @app.get("/api/suggest")
    def rich_suggest():
        c = conn()
        try:
            q = request.args.get("q", "")
            if not q:
                return jsonify({"suggestions": []})
            rows = repo.suggest_titles(c, q, limit=int(request.args.get("limit", 10)))
            return jsonify({"suggestions": rows})
        finally:
            c.close()

    @app.get("/api/title/<slug>")
    def rich_title(slug):
        c = conn()
        try:
            t = repo.get_title_by_slug(c, slug)
            if not t:
                abort(404)
            torrents = repo.get_torrents(c, t["id"])
            people = repo.get_people(c, t["id"])
            genres = repo.get_genres(c, t["id"])
            return jsonify(serialize.to_rich_title(t, torrents, people, genres))
        finally:
            c.close()

    @app.get("/api/health")
    def health():
        c = conn()
        try:
            ls = repo.last_sync(c)
            with c.cursor() as cur:
                cur.execute("SELECT count(*) FROM titles WHERE active")
                titles = cur.fetchone()[0]
                cur.execute("SELECT count(*) FROM torrents WHERE active")
                torrents = cur.fetchone()[0]
            return jsonify({"ok": True, "last_sync": ls,
                            "counts": {"titles": titles, "torrents": torrents}})
        finally:
            c.close()

    return app

app = None
def wsgi():  # entrypoint gunicorn: mirror.api:wsgi()
    global app
    app = create_app()
    return app
```

Nota serialización de fechas: `jsonify` no serializa `datetime.date`. En `serialize.py`
las fechas ya se convierten a string (`_date_str`) para el drop-in; para la cara rica,
configurá el app con un JSON provider que convierta `date`/`datetime` a ISO, agregando en
`create_app`:
```python
from flask.json.provider import DefaultJSONProvider
class _JSON(DefaultJSONProvider):
    def default(self, o):
        import datetime
        if isinstance(o, (datetime.date, datetime.datetime)): return o.isoformat()
        return super().default(o)
app.json = _JSON(app)
```

- [ ] **Step 5: Run to verify pass**

Run: `python -m pytest tests/test_api.py -v`
Expected: PASS (5 passed)

- [ ] **Step 6: Commit**

```bash
git add mirror/repo.py mirror/api.py tests/test_api.py
git commit -m "feat: API Flask (drop-in hacktorrent + API rica) sobre repo de lectura"
```

---

## Task 11: Deploy en Coolify (Dockerfile + runbook)

**Files:**
- Create: `Dockerfile`
- Create: `gunicorn.conf.py`
- Create: `docker-compose.yml` (solo dev local)
- Create: `mirror/migrate.py` (aplica `sql/schema.sql` — idempotente)
- Create: `deploy/coolify.md`
- Test: `tests/test_migrate.py`

**Interfaces:**
- Consumes: `mirror.api:wsgi`, `mirror.crawler`, `sql/schema.sql`, `DATABASE_URL` (env del proyecto Coolify).
- Produces: `mirror.migrate.apply(dsn)` — ejecuta el schema; imagen Docker que corre gunicorn.

- [ ] **Step 1: `gunicorn.conf.py`** (en la raíz; escucha en 0.0.0.0 para Docker)

```python
bind = "0.0.0.0:8097"
workers = 2
threads = 2
timeout = 60
```

- [ ] **Step 2: `mirror/migrate.py` + test**

`tests/test_migrate.py`:
```python
from mirror import migrate

def test_migrate_is_idempotent(db):
    import os
    # el fixture db ya cargó el schema; aplicarlo de nuevo no debe fallar
    migrate.apply(os.environ["DATABASE_URL"])
    migrate.apply(os.environ["DATABASE_URL"])
    with db.cursor() as cur:
        cur.execute("SELECT to_regclass('public.titles')")
        assert cur.fetchone()[0] == "titles"
```

`mirror/migrate.py`:
```python
import pathlib
import sys
import psycopg2

SCHEMA = pathlib.Path(__file__).parent.parent / "sql" / "schema.sql"

def apply(dsn: str):
    sql = SCHEMA.read_text()
    conn = psycopg2.connect(dsn)
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute(sql)
    conn.close()

if __name__ == "__main__":
    import os
    apply(os.environ["DATABASE_URL"])
    print("[migrate] schema aplicado")
```

Run: `python -m pytest tests/test_migrate.py -v` → PASS (requiere `DATABASE_URL`).

- [ ] **Step 3: `Dockerfile`**

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8097
# Aplica el schema (idempotente) y arranca la API.
CMD ["sh", "-c", "python -m mirror.migrate && gunicorn -c gunicorn.conf.py 'mirror.api:wsgi()'"]
```

- [ ] **Step 4: `docker-compose.yml`** (SOLO dev local: levanta Postgres + API; corre un crawler one-off aparte)

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: htm
      POSTGRES_DB: htm
    ports: ["5433:5432"]
  api:
    build: .
    environment:
      DATABASE_URL: postgresql://postgres:htm@db:5432/htm
      RATE_LIMIT_S: "0.5"
    ports: ["8097:8097"]
    depends_on: [db]
```

Verificación local:
```bash
docker compose up --build -d
curl -s localhost:8097/api/health | python -m json.tool
docker compose run --rm api python -m mirror.crawler --mode incremental
curl -s "localhost:8097/api/search?q=matrix" | python -m json.tool   # typo tolerante
docker compose down
```

- [ ] **Step 5: `deploy/coolify.md`** (runbook)

Escribir estos pasos exactos:
```
Prerrequisito: el DNS de torrents.comparadorinternet.co debe apuntar al host de Coolify
(A/AAAA o vía Cloudflare). Confirmar el host de Coolify antes de empezar.

1) En Coolify, crear un Proyecto "hacktorrent-mirror" con un Environment (production).

2) Recurso Postgres:
   - Add Resource -> Databases -> PostgreSQL 16.
   - Anotar la Internal Connection URL (postgresql://...@<service>:5432/<db>);
     esa es la DATABASE_URL para los demás recursos del proyecto.
   - unaccent/pg_trgm: la imagen oficial de postgres las trae; el schema hace
     CREATE EXTENSION IF NOT EXISTS (se aplican solas al migrar).

3) Recurso Aplicación (la API):
   - Add Resource -> Application -> Public Repository (o conectar GitHub) -> repo hacktorrent-mirror.
   - Build Pack: Dockerfile. Port: 8097.
   - Environment variables: DATABASE_URL (la interna del paso 2),
     HACKTORRENT_BASE_URL=https://hacktorrent.to/wp-json/wpreact/v1, RATE_LIMIT_S=1.0,
     FLARESOLVERR_URL (opcional).
   - Domains: torrents.comparadorinternet.co (Coolify/Traefik emite SSL automático).
   - Deploy. El CMD del Dockerfile aplica el schema y arranca gunicorn.
   - Verificar: curl https://torrents.comparadorinternet.co/api/health

4) Primer full crawl (one-off):
   - En el recurso Application: Execute Command / Terminal ->
     python -m mirror.crawler --mode full
     (tarda; ~14k detalles con rate-limit). Alternativa: correrlo como Scheduled Task manual.

5) Scheduled Tasks (Coolify -> Application -> Scheduled Tasks):
   - "ingest-incremental": comando `python -m mirror.crawler --mode incremental`,
     frecuencia cron `0 */3 * * *` (cada 3h).
   - "ingest-full": comando `python -m mirror.crawler --mode full`,
     frecuencia cron `0 4 * * 0` (domingos 04:00).
   (Corren dentro del contenedor de la app, con la misma DATABASE_URL del proyecto.)

6) Verificación final:
   curl https://torrents.comparadorinternet.co/api/health
   curl "https://torrents.comparadorinternet.co/wp-json/wpreact/v1/search?query=matrix"
```

- [ ] **Step 6: Commit**

```bash
git add Dockerfile gunicorn.conf.py docker-compose.yml mirror/migrate.py tests/test_migrate.py deploy/coolify.md
git commit -m "feat: deploy en Coolify (Dockerfile, migrate idempotente, compose dev, runbook)"
```

- [ ] **Step 7: Verificación en device (post-deploy)**

Una vez vivo, swap de `baseUrl` a `https://torrents.comparadorinternet.co` en el
`providers.json` del repo `lordmacu/arkiv-providers` (hot-update) y probar en el app que
hacktorrent sigue devolviendo resultados (misma UX, ahora contra el mirror).

---

## Self-Review (autor)

**Cobertura del spec:**
- Enumeración/mirror (movies+anime) → Tasks 7, 8. ✅
- Esquema normalizado (titles, torrents, people, genres, sync_runs) → Task 5. ✅
- Búsqueda avanzada: FTS con pesos (A/B/C/D) + `ts_rank_cd`, fuzzy `pg_trgm`, `f_unaccent`,
  tmdb_id exacto, facetas (actor/director/género/año/idioma/calidad/season/episode), y
  `/api/suggest` → Task 5 (schema: f_unaccent + search_doc pesado + índices) + Task 10 (`search_titles`, `suggest_titles`). ✅
- Mapeo temporada/episodio + packs → Task 3 + Task 4 (`build_torrents`) + Task 9 (agrupado). ✅
- Sync incremental + full + reconcile → Task 8. ✅
- API drop-in + rica + suggest + health → Tasks 9, 10. ✅
- Deploy por Coolify (Postgres gestionado + app Docker + scheduled tasks + dominio/SSL) → Task 11
  (Dockerfile, `migrate.py` idempotente, compose dev, runbook `deploy/coolify.md`). ✅
- Testing (normalizaciones, mapeo, drop-in vs fixtures, filtros, ranking sin acentos, suggest typo, migrate idempotente) → Tasks 2,3,4,9,10,11. ✅

**Placeholder scan:** sin TBD/TODO; todo paso tiene código real.

**Type consistency:** `build_title`/`build_torrents` (Task 4) → columnas de `db.upsert_detail` (Task 6) → filas leídas por `repo` (Task 10) → `serialize` (Task 9). Nombres de campos consistentes (`img_background`, `size_label`, `lang_raw`, `season/episode/episode_end/is_pack`). `actors_text`/`genres_text` añadidos en Task 5 (schema) y persistidos en Task 6 (`upsert_detail`).
