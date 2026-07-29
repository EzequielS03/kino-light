# Enriquecimiento con otros trackers (server-side) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cuando un título se agrega/cambia en un crawl del mirror, buscar torrents del mismo título en los otros trackers del app (bitsearch, nyaa, eztv, elitetorrent, dontorrent, divxtotal, subtorrents, wolfmax4k), matchearlos con fidelidad y guardarlos como torrents extra con su `source`. Sin tocar el app.

**Architecture:** Paquete nuevo `mirror/providers/` con un backend HTML declarativo genérico (port de `HtmlParser.kt`/`DeclarativeHtmlBackend.kt`) manejado por el mismo `providers.json` que usa el app; un `matcher` fiel al app; y `mirror/enrich.py` que integra el enriquecimiento al crawler, acotado a títulos nuevos/cambiados + un presupuesto de backfill.

**Tech Stack:** Python 3 (local 3.9, prod 3.12), PostgreSQL, requests, BeautifulSoup4 + soupsieve + lxml, FlareSolverr (blog), pytest.

## Global Constraints

- **Repo:** `/Users/cristian/hacktorrent-mirror` (main; ya desplegado). GitHub `lordmacu/hacktorrent-mirror`.
- **Identidad git:** `lordmacu` / `10134930+lordmacu@users.noreply.github.com`. Commits SIN coautoría.
- **Python local 3.9:** cada módulo nuevo que use `X | None` empieza con `from __future__ import annotations`.
- **DB de test:** `docker run --rm -d --name htm-pg -e POSTGRES_PASSWORD=htm -e POSTGRES_DB=htm -p 5433:5432 postgres:16` y `export DATABASE_URL=postgresql://postgres:htm@localhost:5433/htm`. El schema base (Task del mirror) ya existe en `sql/schema.sql`.
- **Referencias a leer (portar, no inventar):**
  - App Kotlin: `/Users/cristian/archive/app/src/main/java/com/arkiv/player/data/catalog/providers/{HtmlParser,DeclarativeHtmlBackend,ProviderDefinition,SearchModels}.kt`
  - `providers.json` real: `/Users/cristian/archive/app/src/main/assets/providers.json` (raw remoto: `https://raw.githubusercontent.com/lordmacu/arkiv-providers/main/providers.json`).
  - Alfa (Python): `/Users/cristian/archive/alfa-addon/plugin.video.alfa/channels/{eztv,elitetorrent,dontorrent,divxtotal,subtorrents,wolfmax4k}.py`
  - Balandro (Python): `/Users/cristian/archive/balandro-addon/plugin.video.balandro/channels/` (bitsearch/nyaa y otros).
- **Precisión sobre recall:** ante duda de matching, NO agregar (mejor perder un torrent que pegar el equivocado).
- **Enrichment arranca detrás de flag** `ENRICH_ENABLED` (default false) hasta validar en vivo.

---

## File Structure

```
hacktorrent-mirror/
  requirements.txt            # + beautifulsoup4, soupsieve, lxml
  assets/providers.json       # copia bundleada (fallback)
  sql/schema.sql              # + columnas source/seeders/match_score/last_enriched/enrich_error + índice infohash
  mirror/
    config.py                 # + enrich_enabled, enrich_budget, providers_url
    db.py                     # + source en upsert; upsert_enriched_torrents; reconcile source-scoped; deactivate_titles_without_torrents
    crawler.py                # + integración enrich + --mode enrich + backfill budget
    enrich.py                 # orquesta enrich de un título
    providers/
      __init__.py
      provider_config.py      # carga providers.json (remoto+fallback), defs HTML
      fetch.py                # requests + FlareSolverr, rate-limit por host, timeouts/retries
      html_backend.py         # port de HtmlParser: apply_rule + parse_list + follow_detail
      matcher.py              # build_query + match/score (fiel al app)
  tests/
    fixtures/html/            # HTML real por tracker (bitsearch.html, nyaa.html, ...)
    test_provider_config.py
    test_html_backend.py
    test_matcher.py
    test_fetch.py
    test_enrich.py
    test_db_enrich.py
```

---

## Task 1: Deps + migración de schema (aditiva) + source en hacktorrent

**Files:**
- Modify: `requirements.txt`
- Modify: `sql/schema.sql`
- Modify: `mirror/db.py` (poner `source='hacktorrent'` en el upsert de torrents)
- Test: `tests/test_db_enrich.py`

**Interfaces:**
- Produces: columnas `torrents.source`, `torrents.seeders`, `torrents.match_score`, `titles.last_enriched`, `titles.enrich_error`; índice `UNIQUE(title_id, infohash) WHERE infohash IS NOT NULL`.

- [ ] **Step 1: deps** — agregar a `requirements.txt`:
```
beautifulsoup4==4.12.3
soupsieve==2.6
lxml==5.3.0
```
Instalar: `cd /Users/cristian/hacktorrent-mirror && .venv/bin/pip install -q -r requirements.txt`

- [ ] **Step 2: schema aditivo** — agregar al FINAL de `sql/schema.sql` (idempotente):
```sql
ALTER TABLE torrents ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'hacktorrent';
ALTER TABLE torrents ADD COLUMN IF NOT EXISTS seeders int;
ALTER TABLE torrents ADD COLUMN IF NOT EXISTS match_score numeric(4,3);
ALTER TABLE titles  ADD COLUMN IF NOT EXISTS last_enriched timestamptz;
ALTER TABLE titles  ADD COLUMN IF NOT EXISTS enrich_error text;
CREATE INDEX IF NOT EXISTS idx_torrents_source ON torrents(source);
CREATE UNIQUE INDEX IF NOT EXISTS uq_torrents_title_infohash
  ON torrents(title_id, infohash) WHERE infohash IS NOT NULL;

-- Observabilidad: stats por provider y corrida de enrich (para analizar qué trackers funcionan)
CREATE TABLE IF NOT EXISTS provider_stats (
    id        bigserial PRIMARY KEY,
    provider  text NOT NULL,
    run_at    timestamptz NOT NULL DEFAULT now(),
    titles    int NOT NULL DEFAULT 0,
    found     int NOT NULL DEFAULT 0,
    matched   int NOT NULL DEFAULT 0,
    added     int NOT NULL DEFAULT 0,
    errors    int NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_provider_stats_provider ON provider_stats(provider, run_at DESC);
```

- [ ] **Step 3: Write the failing test** — `tests/test_db_enrich.py`:
```python
from mirror import db as db_mod

def test_schema_has_enrich_columns(db):
    with db.cursor() as cur:
        cur.execute("""SELECT column_name FROM information_schema.columns
                       WHERE table_name='torrents'""")
        cols = {r[0] for r in cur.fetchall()}
    assert {"source","seeders","match_score"} <= cols
    with db.cursor() as cur:
        cur.execute("""SELECT column_name FROM information_schema.columns
                       WHERE table_name='titles'""")
        tcols = {r[0] for r in cur.fetchall()}
    assert {"last_enriched","enrich_error"} <= tcols
```
(El fixture `db` de `tests/conftest.py` ya aplica `sql/schema.sql`.)

- [ ] **Step 4: Run** — `DATABASE_URL=... .venv/bin/python -m pytest tests/test_db_enrich.py -v` → PASS tras el schema.

- [ ] **Step 5: source + dedup por infohash en el upsert de hacktorrent** — en `mirror/db.py`, en el loop de torrents de `upsert_detail`:
  1. La INSERT incluye `source` con `'hacktorrent'`; el `ON CONFLICT ... DO UPDATE` NO toca `source` (que no lo pise el enrich).
  2. **Elegir el conflict target por fila**: `(title_id, infohash)` cuando el torrent tiene `infohash`, si no `(title_id, magnet)`. (El índice parcial `uq_torrents_title_infohash` que agregó el Step 2 haría fallar el `ON CONFLICT (title_id, magnet)` fijo si dos torrents comparten infohash con magnet distinto — hacktorrent recrawl con trackers reordenados, o enrich con mismo infohash.)
  3. Agregar `magnet=EXCLUDED.magnet` al `DO UPDATE SET` (para que en conflicto-por-infohash el magnet se actualice).
  Código del loop:
```python
            for tr in torrents:
                # OJO Postgres: un índice único PARCIAL exige repetir el predicado en el ON CONFLICT
                conflict = "(title_id, infohash) WHERE infohash IS NOT NULL" if tr["infohash"] else "(title_id, magnet)"
                cur.execute(
                    "INSERT INTO torrents (title_id,infohash,magnet,quality,size_bytes,size_label,"
                    "lang_raw,lang_norm,subs,season,episode,episode_end,is_pack,source_date,"
                    "download_type,last_seen,active,source) VALUES "
                    "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true,'hacktorrent') "
                    f"ON CONFLICT {conflict} DO UPDATE SET "
                    "magnet=EXCLUDED.magnet,infohash=EXCLUDED.infohash,quality=EXCLUDED.quality,"
                    "size_bytes=EXCLUDED.size_bytes,size_label=EXCLUDED.size_label,lang_raw=EXCLUDED.lang_raw,"
                    "lang_norm=EXCLUDED.lang_norm,subs=EXCLUDED.subs,season=EXCLUDED.season,episode=EXCLUDED.episode,"
                    "episode_end=EXCLUDED.episode_end,is_pack=EXCLUDED.is_pack,source_date=EXCLUDED.source_date,"
                    "download_type=EXCLUDED.download_type,last_seen=EXCLUDED.last_seen,active=true",
                    [title_id, tr["infohash"], tr["magnet"], tr["quality"], tr["size_bytes"],
                     tr["size_label"], tr["lang_raw"], tr["lang_norm"], tr["subs"], tr["season"],
                     tr["episode"], tr["episode_end"], tr["is_pack"], tr["source_date"],
                     tr["download_type"], seen_at],
                )
```
  Test de regresión (agregar a `tests/test_db_enrich.py`):
```python
def test_upsert_detail_infohash_conflict_no_crash(db):
    import copy, datetime, json, pathlib
    MOVIE = json.load(open(pathlib.Path(__file__).parent/"fixtures"/"movie.json"))
    now = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, now)
    m2 = copy.deepcopy(MOVIE)
    dl = m2["downloads"][0]
    dl["download_link"] = dl["download_link"].split("&dn=")[0] + "&dn=CHANGED&tr=udp://x:1/announce"
    db_mod.upsert_detail(db, m2, now)   # mismo btih, magnet distinto -> NO debe crashear
    with db.cursor() as cur:
        cur.execute("SELECT count(*) FROM torrents t JOIN titles ti ON ti.id=t.title_id "
                    "WHERE ti.slug=%s", (MOVIE["slug"],))
        assert cur.fetchone()[0] == len(MOVIE["downloads"])   # sin duplicar por el magnet cambiado
```
  Verificar que `tests/test_db.py` (mirror) sigue verde.

- [ ] **Step 6: Run full suite** — `DATABASE_URL=... .venv/bin/python -m pytest -q` → todo verde.

- [ ] **Step 7: Commit**
```bash
git add requirements.txt sql/schema.sql mirror/db.py tests/test_db_enrich.py
git commit -m "feat(enrich): deps scraping + schema aditivo (source, seeders, match_score, last_enriched)"
```

---

## Task 2: provider_config — cargar providers.json (defs HTML)

**Files:**
- Create: `mirror/providers/__init__.py` (vacío)
- Create: `mirror/providers/provider_config.py`
- Create: `assets/providers.json` (copia del real)
- Test: `tests/test_provider_config.py`

**Interfaces:**
- Consumes: `providers.json`.
- Produces:
  - `HtmlProvider` dataclass: `id, name, base_url, search_path, keywords:dict, language_tokens:list, needs_cloudflare:bool, render_js:bool, charset:str, parser:dict, detail:dict|None, host_alt:list`.
  - `load_html_providers(source:str|dict) -> list[HtmlProvider]` — dado un path a json / dict, devuelve SOLO los providers HTML habilitados (excluye los que tienen `jsonApi`, y los `enabled=false`).

- [ ] **Step 1: bundlear providers.json**
```bash
cp /Users/cristian/archive/app/src/main/assets/providers.json /Users/cristian/hacktorrent-mirror/assets/providers.json
```

- [ ] **Step 2: Write the failing test** — `tests/test_provider_config.py`:
```python
import json, pathlib
from mirror.providers.provider_config import load_html_providers

PJ = str(pathlib.Path(__file__).parent.parent / "assets" / "providers.json")

def test_loads_only_enabled_html_providers():
    provs = load_html_providers(PJ)
    ids = {p.id for p in provs}
    # HTML providers presentes; hacktorrent (jsonApi) excluido
    assert "bitsearch" in ids and "elitetorrent" in ids
    assert "hacktorrent" not in ids
    bit = next(p for p in provs if p.id == "bitsearch")
    assert bit.base_url == "https://bitsearch.to"
    assert bit.parser["rowSelector"]
    assert "movie" in bit.keywords

def test_detail_providers_have_detail():
    provs = {p.id: p for p in load_html_providers(PJ)}
    assert provs["elitetorrent"].detail is not None
    assert provs["bitsearch"].detail is None
```

- [ ] **Step 3: Run** → FAIL (módulo falta).

- [ ] **Step 4: Implement `mirror/providers/provider_config.py`**
```python
from __future__ import annotations

import json
from dataclasses import dataclass, field

@dataclass
class HtmlProvider:
    id: str
    name: str
    base_url: str
    search_path: str
    keywords: dict
    language_tokens: list
    parser: dict
    needs_cloudflare: bool = False
    render_js: bool = False
    charset: str = "utf-8"
    detail: dict | None = None
    host_alt: list = field(default_factory=list)

def _providers_list(source) -> list:
    if isinstance(source, (dict, list)):
        data = source
    else:
        data = json.load(open(source, encoding="utf-8"))
    return data if isinstance(data, list) else data.get("providers", [])

def load_html_providers(source) -> list[HtmlProvider]:
    out = []
    for p in _providers_list(source):
        if not isinstance(p, dict):
            continue
        if "jsonApi" in p:                    # hacktorrent u otros JSON: no son HTML
            continue
        if p.get("enabled") is False:
            continue
        if not p.get("parser"):               # sin parser declarativo no se puede scrapear
            continue
        out.append(HtmlProvider(
            id=p["id"], name=p.get("name", p["id"]), base_url=p["baseUrl"],
            search_path=p.get("searchPath", ""), keywords=p.get("keywords", {}),
            language_tokens=p.get("languageTokens", []), parser=p["parser"],
            needs_cloudflare=bool(p.get("needsCloudflare")),
            render_js=bool(p.get("renderJs") or p.get("render")),
            charset=p.get("charset", "utf-8"), detail=p.get("detail"),
            host_alt=p.get("hostAlt", []),
        ))
    return out
```

- [ ] **Step 5: Run** → PASS.

- [ ] **Step 6: Commit**
```bash
git add mirror/providers/__init__.py mirror/providers/provider_config.py assets/providers.json tests/test_provider_config.py
git commit -m "feat(enrich): provider_config carga defs HTML de providers.json"
```

---

## Task 3: html_backend — parser declarativo (port de HtmlParser.kt)

**Files:**
- Create: `mirror/providers/html_backend.py`
- Test: `tests/test_html_backend.py`
- Create: `tests/fixtures/html/*.html` (fetch real)

**Interfaces:**
- Consumes: `HtmlProvider`.
- Produces:
  - `RawResult` dataclass: `name:str, magnet:str|None, infohash:str|None, torrent_url:str|None, seeders:int, size_bytes:int`.
  - `apply_rule(row, rule:dict, base_url:str) -> str|None` — port de `HtmlParser.applyRule`.
  - `parse_list(provider:HtmlProvider, html:str, page_url:str, max_rows:int=50) -> list[RawResult]` — port de `parseList`.

**PORTING NOTE (leer antes):** leer `app/.../providers/HtmlParser.kt` para replicar exactamente: selector vacío→row; attr `""`/`"text"`→text; `resolve:"absolute"` + href/src→urljoin; `regex`→group(1) si existe si no group(0); seeds sin regla→1, con regla sin match→0; name obligatorio no-vacío; requiere al menos uno de magnet/infohash/torrent_url; parseSize binario/decimal. Usar `BeautifulSoup(html, "lxml")` y `soup.select(...)` (soupsieve soporta `:has()`, `nth-of-type`, `[a^=b]`).

- [ ] **Step 1: fetch fixtures de trackers sin Cloudflare** (los CF se hacen luego con FlareSolverr)
```bash
cd /Users/cristian/hacktorrent-mirror && mkdir -p tests/fixtures/html
UA="Mozilla/5.0 ArkivMirror"
# ej. bitsearch (ajustar query a algo con resultados):
curl -sL -A "$UA" "https://bitsearch.to/search?q=matrix" -o tests/fixtures/html/bitsearch.html
# repetir para nyaa, elitetorrent, dontorrent, divxtotal, subtorrents con una query que devuelva filas.
# Verificar que el rowSelector del provider matchea >0 filas en cada fixture antes de escribir el test.
```

- [ ] **Step 2: Write the failing test** — `tests/test_html_backend.py` (usar el fixture de bitsearch, cuyo parser trae magnet directo):
```python
import pathlib
from mirror.providers.provider_config import load_html_providers
from mirror.providers.html_backend import parse_list

PJ = str(pathlib.Path(__file__).parent.parent / "assets" / "providers.json")
HTML = pathlib.Path(__file__).parent / "fixtures" / "html"

def _prov(pid):
    return next(p for p in load_html_providers(PJ) if p.id == pid)

def test_parse_bitsearch_extracts_rows():
    p = _prov("bitsearch")
    html = (HTML / "bitsearch.html").read_text(encoding="utf-8", errors="ignore")
    rows = parse_list(p, html, p.base_url + "/search?q=matrix")
    assert len(rows) > 0
    r = rows[0]
    assert r.name
    assert (r.magnet and r.magnet.startswith("magnet:")) or r.infohash or r.torrent_url
    assert r.size_bytes >= 0 and r.seeders >= 0
```

- [ ] **Step 3: Run** → FAIL.

- [ ] **Step 4: Implement `mirror/providers/html_backend.py`** (port fiel):
```python
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin
from bs4 import BeautifulSoup

_SIZE_RE = re.compile(r"([\d]+[.,]?[\d]*)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)", re.I)
_MULT = {"TB": 1 << 40, "TIB": 1 << 40, "GB": 1 << 30, "GIB": 1 << 30,
         "MB": 1 << 20, "MIB": 1 << 20, "KB": 1 << 10, "KIB": 1 << 10, "B": 1}

def parse_size(text: str) -> int:
    m = _SIZE_RE.search(text or "")
    if not m:
        return 0
    num = m.group(1).replace(",", ".")
    try:
        return int(float(num) * _MULT[m.group(2).upper()])
    except ValueError:
        return 0

@dataclass
class RawResult:
    name: str
    magnet: str | None
    infohash: str | None
    torrent_url: str | None
    seeders: int
    size_bytes: int

def apply_rule(row, rule: dict, base_url: str) -> str | None:
    try:
        sel = rule.get("selector", "")
        el = row if not sel else (row.select_one(sel))
        if el is None:
            return None
        attr = rule.get("attr", "text")
        if attr in ("", "text"):
            value = el.get_text(strip=False)
        else:
            value = el.get(attr, "") or ""
            if rule.get("resolve") == "absolute" and attr in ("href", "src") and value:
                value = urljoin(base_url, value)
        rx = rule.get("regex")
        if rx:
            m = re.search(rx, value)
            value = (m.group(1) if (m and m.groups()) else (m.group(0) if m else "")) if True else ""
        if rule.get("resolve") == "absolute" and value and not value.startswith(("http", "magnet:")):
            value = urljoin(base_url + "/", value.lstrip("/"))
        return value.strip()
    except Exception:
        return None

def parse_list(provider, html: str, page_url: str, max_rows: int = 50) -> list[RawResult]:
    p = provider.parser
    if not p:
        return []
    soup = BeautifulSoup(html or "", "lxml")
    rows = soup.select(p["rowSelector"])
    out = []
    for row in rows[:max_rows]:
        name = apply_rule(row, p["name"], provider.base_url)
        if not name or not name.strip():
            continue
        magnet = apply_rule(row, p["magnet"], provider.base_url) if p.get("magnet") else None
        magnet = magnet if (magnet and magnet.startswith("magnet:")) else None
        infohash = (apply_rule(row, p["infohash"], provider.base_url) or None) if p.get("infohash") else None
        turl = (apply_rule(row, p["torrentUrl"], provider.base_url) or None) if p.get("torrentUrl") else None
        if not (magnet or infohash or turl):
            continue
        if not p.get("seeds"):
            seeds = 1
        else:
            sv = apply_rule(row, p["seeds"], provider.base_url) or ""
            m = re.search(r"\d+", sv)
            seeds = int(m.group(0)) if m else 0
        size = parse_size(apply_rule(row, p["size"], provider.base_url) or "") if p.get("size") else 0
        out.append(RawResult(name.strip(), magnet, infohash, turl, seeds, size))
    return out
```

- [ ] **Step 5: Run** → PASS. Si el fixture de bitsearch no trae filas (sitio cambió/CF), elegir otro tracker sin CF cuyo fixture sí traiga filas y ajustar el test a ese; documentar cuál.

- [ ] **Step 6: Commit**
```bash
git add mirror/providers/html_backend.py tests/test_html_backend.py tests/fixtures/html/
git commit -m "feat(enrich): html_backend declarativo (port de HtmlParser: apply_rule + parse_list)"
```

---

## Task 4: fetch — capa de red (requests + FlareSolverr, rate-limit por host)

**Files:**
- Create: `mirror/providers/fetch.py`
- Test: `tests/test_fetch.py`

**Interfaces:**
- Consumes: `Config` (user_agent, flaresolverr_url).
- Produces: clase `Fetcher(cfg, per_host_delay=1.0)`:
  - `get(url:str, needs_render:bool=False, charset:str="utf-8") -> str|None` — devuelve HTML. Si `needs_render` (Cloudflare/renderJs) usa FlareSolverr (`request.get`); si no, `requests` normal. Timeout, 1 retry con backoff, rate-limit por host (sleep si el mismo host se pidió hace < per_host_delay). Devuelve None ante fallo (aislamiento).

- [ ] **Step 1: Write the failing test** — `tests/test_fetch.py`:
```python
import time, pytest
from mirror.config import Config
from mirror.providers.fetch import Fetcher

@pytest.fixture(autouse=True)
def _no_sleep(monkeypatch):
    monkeypatch.setattr(time, "sleep", lambda *a, **k: None)

class Resp:
    def __init__(self, status, text): self.status_code=status; self.text=text
    def json(self): import json; return json.loads(self.text)
    def raise_for_status(self): pass

def test_plain_get(monkeypatch):
    f = Fetcher(Config.from_env({"DATABASE_URL":"x"}))
    monkeypatch.setattr(f._session, "get", lambda url, **k: Resp(200, "<html>ok</html>"))
    assert f.get("https://bitsearch.to/x") == "<html>ok</html>"

def test_render_uses_flaresolverr(monkeypatch):
    f = Fetcher(Config.from_env({"DATABASE_URL":"x","FLARESOLVERR_URL":"http://fs/v1"}))
    called = {}
    def fake_post(url, **k):
        called["url"]=url
        return Resp(200, '{"solution": {"response": "<html>rendered</html>"}}')
    monkeypatch.setattr(f._session, "post", fake_post)
    out = f.get("https://eztv.to/x", needs_render=True)
    assert out == "<html>rendered</html>" and called["url"] == "http://fs/v1"

def test_get_failure_returns_none(monkeypatch):
    f = Fetcher(Config.from_env({"DATABASE_URL":"x"}))
    def boom(url, **k): raise RuntimeError("net")
    monkeypatch.setattr(f._session, "get", boom)
    assert f.get("https://x.y/z") is None
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement `mirror/providers/fetch.py`**
```python
from __future__ import annotations

import time
from urllib.parse import urlparse
import requests

class Fetcher:
    def __init__(self, cfg, per_host_delay: float = 1.0, timeout: int = 25):
        self.cfg = cfg
        self.per_host_delay = per_host_delay
        self.timeout = timeout
        self._session = requests.Session()
        self._session.headers["User-Agent"] = cfg.user_agent
        self._last_hit: dict = {}

    def _throttle(self, url: str):
        host = urlparse(url).netloc
        last = self._last_hit.get(host, 0.0)
        wait = self.per_host_delay - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)
        self._last_hit[host] = time.monotonic()

    def get(self, url: str, needs_render: bool = False, charset: str = "utf-8") -> str | None:
        self._throttle(url)
        for attempt in range(2):
            try:
                if needs_render and self.cfg.flaresolverr_url:
                    r = self._session.post(self.cfg.flaresolverr_url,
                        json={"cmd": "request.get", "url": url, "maxTimeout": 60000},
                        timeout=90)
                    r.raise_for_status()
                    return r.json()["solution"]["response"]
                r = self._session.get(url, timeout=self.timeout)
                r.raise_for_status()
                r.encoding = charset or r.encoding
                return r.text
            except Exception:
                if attempt == 0:
                    time.sleep(1.0)
                    continue
                return None
        return None
```
Nota: `time` se importa arriba; en `_throttle` se usa `time.monotonic()` (no lo bloquea el monkeypatch de `time.sleep`).

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**
```bash
git add mirror/providers/fetch.py tests/test_fetch.py
git commit -m "feat(enrich): fetch (requests + FlareSolverr, rate-limit por host, aislamiento)"
```

---

## Task 5: matcher — query + pertenencia (fiel al app; precisión)

**Files:**
- Create: `mirror/providers/matcher.py`
- Test: `tests/test_matcher.py`

**PORTING NOTE:** revisar cómo Alfa/Balandro y el app filtran resultados por año (pelis) y S/E (series/anime). Replicar esa intención. Preferir precisión.

**Interfaces:**
- Produces:
  - `render_keyword(template:str, ctx:dict) -> str` — expande `{title}`, `{year}`, `{season:2}`, `{episode:2}`, `{episode_abs:2}` (zero-pad).
  - `normalize(s:str) -> str` — sin acentos, minúsculas, `[._\-]`→espacio, colapsa espacios.
  - `match_score(result_name:str, title:str, original_title:str|None, year:int|None, kind:str, season:int|None, episode:int|None) -> float|None` — devuelve score 0..1 si el resultado corresponde al título, o `None` si se rechaza (año/SE no coincide o similitud < umbral). Umbral por defecto 0.6.

- [ ] **Step 1: Write the failing test** — `tests/test_matcher.py`:
```python
from mirror.providers.matcher import render_keyword, normalize, match_score

def test_render_keyword():
    ctx = {"title":"The Matrix","year":1999,"season":1,"episode":5,"episode_abs":5}
    assert render_keyword("{title} {year}", ctx) == "The Matrix 1999"
    assert render_keyword("{title} S{season:2}E{episode:2}", ctx) == "The Matrix S01E05"

def test_normalize():
    assert normalize("The.Matrix.1999.1080p") == "the matrix 1999 1080p"
    assert normalize("Amélie") == "amelie"

def test_movie_requires_year():
    # match correcto
    assert match_score("The Matrix 1999 1080p BluRay", "The Matrix", "The Matrix", 1999, "movie", None, None) is not None
    # año equivocado (remake/otra) -> rechazo
    assert match_score("The Matrix Resurrections 2021", "The Matrix", "The Matrix", 1999, "movie", None, None) is None
    # título distinto -> rechazo
    assert match_score("Shrek 1999 1080p", "The Matrix", "The Matrix", 1999, "movie", None, None) is None

def test_anime_requires_season_episode():
    ok = match_score("That Time I Got Reincarnated as a Slime S03E01 1080p", "That Time I Got Reincarnated as a Slime", None, 2018, "anime", 3, 1)
    assert ok is not None
    bad = match_score("That Time I Got Reincarnated as a Slime S03E02 1080p", "That Time I Got Reincarnated as a Slime", None, 2018, "anime", 3, 1)
    assert bad is None  # episodio no coincide

def test_full_release_name_short_title_matches():
    # cobertura (no Jaccard): un nombre de release completo con codec/grupo NO debe rechazar el match
    assert match_score("The Matrix 1999 1080p BluRay x264-GROUP", "The Matrix", "The Matrix", 1999, "movie", None, None) is not None
    assert match_score("Breaking Bad S05E14 1080p WEB-DL DD5.1 H264-GROUP", "Breaking Bad", None, 2008, "serie", 5, 14) is not None

def test_partial_season_or_episode_still_gated():
    # solo episode dado: episodio distinto se rechaza (no hay bypass parcial)
    assert match_score("Slime S03E07 1080p", "Slime", None, 2018, "anime", None, 7) is not None
    assert match_score("Slime S03E07 1080p", "Slime", None, 2018, "anime", None, 1) is None
    # solo season dado: otra season se rechaza
    assert match_score("Slime S05E01 1080p", "Slime", None, 2018, "anime", 3, None) is None

def test_punctuation_title_matches():
    # normalize quita ':' -> "Mission Impossible" matchea
    assert match_score("Mission Impossible 1996 1080p", "Mission: Impossible", "Mission: Impossible", 1996, "movie", None, None) is not None

def test_short_title_needs_corroboration():
    # título de 1 palabra SIN año/SE que corrobore -> rechazo (evita falsos positivos por fragmento)
    assert match_score("Matrix Analysis for Engineers 2015", "Matrix", None, None, "movie", None, None) is None
    assert match_score("Grow Up Documentary 2012 1080p", "Up", None, None, "movie", None, None) is None
    # con año que coincide en el nombre -> sí matchea
    assert match_score("Up 2009 1080p BluRay", "Up", "Up", 2009, "movie", None, None) is not None
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement `mirror/providers/matcher.py`**
```python
from __future__ import annotations

import re
import unicodedata

def render_keyword(template: str, ctx: dict) -> str:
    def repl(m):
        key = m.group(1); pad = m.group(2)
        val = ctx.get(key, "")
        if pad and isinstance(val, int):
            return str(val).zfill(int(pad))
        return str(val)
    return re.sub(r"\{(\w+)(?::(\d+))?\}", repl, template).strip()

def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)   # quita TODA la puntuación (: ' , . _ - etc.)
    s = re.sub(r"\s+", " ", s)
    return s.strip()

_SXXEYY = re.compile(r"[sS](\d{1,2})[ ._-]?[eE](\d{1,3})")

def _coverage(title_norm: str, result_tokens: set) -> float:
    # Fracción de tokens del TÍTULO presentes en el resultado. Acotada por el largo del título:
    # no se diluye cuando el nombre del release suma año/resolución/codec/grupo (a diferencia de Jaccard).
    tt = title_norm.split()
    if not tt:
        return 0.0
    return sum(1 for t in tt if t in result_tokens) / len(tt)

def match_score(result_name, title, original_title, year, kind, season, episode, threshold=0.8):
    rn = normalize(result_name)
    if not rn:
        return None
    rtokens = set(rn.split())
    # 1a) gate por año (pelis): si el resultado trae un año y no coincide (±1), rechazar
    if kind == "movie" and year:
        years = set(re.findall(r"\b(19\d\d|20\d\d)\b", rn))
        if years and str(year) not in years and str(year+1) not in years and str(year-1) not in years:
            return None
    # 1b) gate por season/episode (series/anime): cada uno INDEPENDIENTE si viene dado (no bypass parcial)
    if kind in ("anime", "serie") and (season is not None or episode is not None):
        m = _SXXEYY.search(result_name)
        if season is not None and (not m or int(m.group(1)) != int(season)):
            return None
        if episode is not None and (not m or int(m.group(2)) != int(episode)):
            return None
    # 2) score = cobertura de tokens del título (vs title y original_title, gana el mejor)
    cands = [normalize(title)]
    if original_title:
        cands.append(normalize(original_title))
    best = 0.0
    best_ntokens = 0
    for c in cands:
        cov = _coverage(c, rtokens)
        if cov > best:
            best, best_ntokens = cov, len(c.split())
    if best < threshold:
        return None
    # 3) Guarda para títulos de 1 token: una sola palabra es evidencia débil (podría ser un fragmento
    # de un nombre no relacionado, ej. "Up" en "Grow Up Documentary"). Exigir corroboración: año
    # presente y coincidente (peli) o SxxEyy (serie/anime); si no hay ninguna señal, rechazar.
    if best_ntokens < 2:
        if kind == "movie" and year:
            yrs = set(re.findall(r"\b(19\d\d|20\d\d)\b", rn))
            if not (str(year) in yrs or str(year+1) in yrs or str(year-1) in yrs):
                return None
        elif kind in ("anime", "serie") and (season is not None or episode is not None):
            if _SXXEYY.search(result_name) is None:
                return None
        else:
            return None   # 1 token y sin señal que corrobore -> demasiado débil
    return round(best, 3)
```
Nota precisión: la cobertura exige que (casi) todos los tokens del título estén en el nombre del
resultado, y el gate de año/SE descarta secuelas/remakes/episodio equivocado. Umbral 0.8 = tolera que
falte a lo sumo ~1 de cada 5 tokens del título (typos/artículos). Se valida/tunea en vivo en Task 9.

- [ ] **Step 4: Run** → PASS. Ajustar umbral/tokens si algún caso real falla (documentar).

- [ ] **Step 5: Commit**
```bash
git add mirror/providers/matcher.py tests/test_matcher.py
git commit -m "feat(enrich): matcher (query por keywords + pertenencia por año/SE, precision-first)"
```

---

## Task 6: enrich — orquestar el enriquecimiento de un título

**Files:**
- Create: `mirror/enrich.py`
- Test: `tests/test_enrich.py`

**Interfaces:**
- Consumes: `HtmlProvider`s, `Fetcher`, `parse_list`, `matcher`, `db`.
- Produces:
  - `build_ctx(title_row:dict, season:int|None=None, episode:int|None=None) -> dict` — ctx para keywords desde una fila `titles`.
  - `enrich_title(providers, fetcher, title_row) -> tuple[list[dict], dict]` — corre cada provider (aislado), matchea, dedup por infohash/magnet. Devuelve `(rows, stats)`: `rows` = torrents nuevos `{infohash, magnet, quality, size_bytes, size_label, lang_raw, lang_norm, seeders, source, match_score, download_type, torrent_url}`; `stats` = `{provider_id: {"found":int, "matched":int, "error":bool}}` (para observabilidad). NO escribe DB (eso lo hace db en Task 7). Extrae infohash del magnet con `normalize.extract_infohash`; clasifica idioma con `normalize.norm_language` sobre el nombre + languageTokens.

- [ ] **Step 1: Write the failing test** — `tests/test_enrich.py` (con un provider fake y HTML fake, sin red):
```python
from mirror.providers.provider_config import HtmlProvider
from mirror.providers.html_backend import RawResult
from mirror import enrich

MOVIE = {"slug":"the-matrix","kind":"movie","title":"The Matrix","original_title":"The Matrix","year":1999,"id":603}

class FakeFetcher:
    def get(self, url, needs_render=False, charset="utf-8"): return "<html/>"

def test_enrich_matches_and_dedupes(monkeypatch):
    prov = HtmlProvider(id="t", name="T", base_url="https://t", search_path="/s?q={query}",
                        keywords={"movie":"{title} {year}"}, language_tokens=[], parser={"rowSelector":"x"})
    good = RawResult("The Matrix 1999 1080p Latino",
                     "magnet:?xt=urn:btih:452A779504892333BCE4270819A5910653292C16", None, None, 20, 2*1024**3)
    bad  = RawResult("Shrek 1999 1080p", "magnet:?xt=urn:btih:AAAA779504892333BCE4270819A5910653292C16", None, None, 5, 1024**3)
    monkeypatch.setattr(enrich, "parse_list", lambda p, html, url, **k: [good, bad])
    out, stats = enrich.enrich_title([prov], FakeFetcher(), MOVIE)
    assert len(out) == 1                     # solo el que matchea (Shrek rechazado)
    assert out[0]["infohash"] == "452a779504892333bce4270819a5910653292c16"
    assert out[0]["seeders"] == 20 and out[0]["source"] == "t"
    assert 0 < out[0]["match_score"] <= 1
    assert stats["t"]["found"] == 2 and stats["t"]["matched"] == 1   # observabilidad
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement `mirror/enrich.py`**
```python
from __future__ import annotations

from urllib.parse import quote
from mirror.normalize import extract_infohash, norm_language
from mirror.providers.html_backend import parse_list
from mirror.providers.matcher import render_keyword, match_score

def build_ctx(title_row: dict, season=None, episode=None) -> dict:
    return {
        "title": title_row.get("title") or "",
        "year": title_row.get("year") or "",
        "season": season, "episode": episode, "episode_abs": episode,
    }

def _kind_key(kind: str) -> str:
    return "anime" if kind == "anime" else "movie"

def enrich_title(providers, fetcher, title_row, season=None, episode=None):
    kind = title_row.get("kind", "movie")
    ctx = build_ctx(title_row, season, episode)
    seen = set()
    out = []
    stats = {}
    for prov in providers:
        st = stats.setdefault(prov.id, {"found": 0, "matched": 0, "error": False})
        try:
            tmpl = prov.keywords.get(_kind_key(kind)) or prov.keywords.get("movie")
            if not tmpl:
                continue
            query = render_keyword(tmpl, ctx)
            url = prov.base_url + prov.search_path.replace("{query}", quote(query))
            html = fetcher.get(url, needs_render=(prov.needs_cloudflare or prov.render_js), charset=prov.charset)
            if not html:
                st["error"] = True
                continue
            results = parse_list(prov, html, url)
            st["found"] += len(results)
            for r in results:
                score = match_score(r.name, title_row.get("title"), title_row.get("original_title"),
                                    title_row.get("year"), kind, season, episode)
                if score is None:
                    continue
                infohash = extract_infohash(r.magnet or "") if r.magnet else (r.infohash or None)
                if infohash:
                    infohash = infohash.lower()
                key = infohash or r.magnet or r.torrent_url or r.name
                if key in seen:
                    continue
                seen.add(key)
                st["matched"] += 1
                out.append({
                    "name": r.name, "infohash": infohash, "magnet": r.magnet, "torrent_url": r.torrent_url,
                    "quality": None, "size_bytes": r.size_bytes, "size_label": None,
                    "lang_raw": None, "lang_norm": norm_language(r.name),
                    "seeders": r.seeders, "source": prov.id, "match_score": score,
                    "download_type": "link" if r.magnet else "torrent",
                })
        except Exception:
            st["error"] = True
            continue  # aislamiento por provider
    return out, stats
```
Nota: los providers con `detail` de 2 pasos (magnet en la página de detalle) se resuelven en una iteración posterior; para v1, si `parse_list` no trajo magnet pero sí `torrent_url`, se guarda `torrent_url` y se resuelve al reproducir (igual que hace el app). El seguir-a-detalle server-side queda como mejora (Task 6b futura) para no bloquear v1.

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**
```bash
git add mirror/enrich.py tests/test_enrich.py
git commit -m "feat(enrich): enrich_title (query+match+dedup, aislamiento por provider)"
```

---

## Task 7: DB — persistir enriquecidos + reconcile source-scoped + título activo multi-fuente

**Files:**
- Modify: `sql/schema.sql` (columna `name`), `mirror/db.py`
- Test: `tests/test_db_enrich.py` (agregar)

**Nota columna `name` (pedido del usuario):** agregar al final de `sql/schema.sql`:
`ALTER TABLE torrents ADD COLUMN IF NOT EXISTS name text;`
Guarda el nombre del release scrapeado (visibilidad + respaldo de dedup cuando no hay infohash). El
dedup real sigue siendo por infohash. `upsert_enriched_torrents` persiste `name` (ver INSERT abajo).

**Interfaces:**
- Consumes: filas de `enrich_title`.
- Produces:
  - `upsert_enriched_torrents(conn, title_id:int, rows:list[dict], seen_at) -> int` — UPSERT dedup por `(title_id, infohash)` (o `(title_id, magnet)` si sin infohash); setea `source`, `seeders`, `match_score`. NO pisa filas source='hacktorrent'.
  - `mark_enriched(conn, title_id, ok:bool, error:str|None)` — setea `last_enriched=now()`, `enrich_error`.
  - `record_provider_stats(conn, agg:dict)` — inserta una fila en `provider_stats` por provider, con `{titles,found,matched,added,errors}` (observabilidad).
  - Modificar `reconcile` → solo desactiva torrents `source='hacktorrent'`.
  - `deactivate_titles_without_torrents(conn)` — `active=false` para titles sin ningún torrent activo; y reactiva los que tienen. (Reemplaza la desactivación de título por ausencia en hacktorrent.)

- [ ] **Step 1: Write the failing tests** — agregar a `tests/test_db_enrich.py`:
```python
import datetime, json, pathlib
FIX = pathlib.Path(__file__).parent / "fixtures"
MOVIE = json.load(open(FIX / "movie.json"))

def _rows():
    return [{"name":"The Matrix 1999 1080p Latino","infohash":"aaaa779504892333bce4270819a5910653292c16",
             "magnet":"magnet:?xt=urn:btih:AAAA...","torrent_url":None,"quality":None,"size_bytes":1024,
             "size_label":None,"lang_raw":None,"lang_norm":"LATINO","seeders":10,"source":"bitsearch",
             "match_score":0.9,"download_type":"link"}]

def test_upsert_enriched_and_idempotent(db):
    now = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, now)
    tid = MOVIE["id"]
    n1 = db_mod.upsert_enriched_torrents(db, tid, _rows(), now)
    n2 = db_mod.upsert_enriched_torrents(db, tid, _rows(), now)  # idempotente
    with db.cursor() as cur:
        cur.execute("SELECT count(*), max(name) FROM torrents WHERE title_id=%s AND source='bitsearch'", (tid,))
        cnt, name = cur.fetchone()
        assert cnt == 1 and name == "The Matrix 1999 1080p Latino"   # dedup + nombre guardado

def test_reconcile_only_hacktorrent(db):
    now = datetime.datetime.now(datetime.timezone.utc)
    old = datetime.datetime(2020,1,1,tzinfo=datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, old)
    db_mod.upsert_enriched_torrents(db, MOVIE["id"], _rows(), old)
    run_start = datetime.datetime.now(datetime.timezone.utc)
    db_mod.reconcile(db, "movie", run_start)          # ni hacktorrent visto ahora
    with db.cursor() as cur:
        cur.execute("SELECT active FROM torrents WHERE source='bitsearch'")
        assert cur.fetchone()[0] is True              # el enriquecido NO se toca
```

- [ ] **Step 2–3: Run (FAIL) → implementar en `mirror/db.py`:**
```python
def upsert_enriched_torrents(conn, title_id, rows, seen_at) -> int:
    n = 0
    with conn, conn.cursor() as cur:
        for r in rows:
            if r.get("infohash"):
                conflict = "(title_id, infohash) WHERE infohash IS NOT NULL"  # predicado del índice parcial
            else:
                conflict = "(title_id, magnet)"
            cur.execute(
                f"INSERT INTO torrents (title_id,name,infohash,magnet,quality,size_bytes,size_label,"
                f"lang_raw,lang_norm,subs,seeders,source,match_score,download_type,last_seen,active) "
                f"VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,false,%s,%s,%s,%s,%s,true) "
                f"ON CONFLICT {conflict} DO UPDATE SET name=EXCLUDED.name,seeders=EXCLUDED.seeders,"
                f"match_score=EXCLUDED.match_score,size_bytes=EXCLUDED.size_bytes,"
                f"last_seen=EXCLUDED.last_seen,active=true "
                f"WHERE torrents.source <> 'hacktorrent'",   # no pisar hacktorrent
                [title_id, r.get("name"), r.get("infohash"), r.get("magnet") or r.get("torrent_url"),
                 r.get("quality"), r.get("size_bytes"), r.get("size_label"), r.get("lang_raw"),
                 r.get("lang_norm"), r.get("seeders"), r.get("source"), r.get("match_score"),
                 r.get("download_type"), seen_at])
            n += 1
    return n

def mark_enriched(conn, title_id, ok, error=None):
    with conn, conn.cursor() as cur:
        cur.execute("UPDATE titles SET last_enriched=now(), enrich_error=%s WHERE id=%s",
                    (None if ok else (error or "error"), title_id))

def deactivate_titles_without_torrents(conn):
    with conn, conn.cursor() as cur:
        cur.execute("""UPDATE titles t SET active = EXISTS
            (SELECT 1 FROM torrents x WHERE x.title_id=t.id AND x.active)""")

def record_provider_stats(conn, agg: dict):
    if not agg:
        return
    with conn, conn.cursor() as cur:
        for provider, a in agg.items():
            cur.execute(
                "INSERT INTO provider_stats (provider, titles, found, matched, added, errors) "
                "VALUES (%s,%s,%s,%s,%s,%s)",
                (provider, a.get("titles", 0), a.get("found", 0), a.get("matched", 0),
                 a.get("added", 0), a.get("errors", 0)))
```
Y modificar `reconcile` para que el UPDATE de torrents lleve `AND t.source='hacktorrent'`.

- [ ] **Step 4: Run full suite** → verde (revisar que `test_reconcile_deactivates_missing` de Task mirror siga válido: sigue desactivando torrents hacktorrent).

- [ ] **Step 5: Commit**
```bash
git add mirror/db.py tests/test_db_enrich.py
git commit -m "feat(enrich): persistir enriquecidos + reconcile solo-hacktorrent + titulo activo multi-fuente"
```

---

## Task 8: Integración al crawler (flag, in-scope, backfill budget, --mode enrich) + /api/stats

**Files:**
- Modify: `mirror/config.py`, `mirror/crawler.py`, `mirror/repo.py`, `mirror/api.py`
- Test: `tests/test_crawler.py` (agregar), `tests/test_api.py` (agregar `/api/stats`)

**Interfaces:**
- `Config`: + `enrich_enabled:bool` (`ENRICH_ENABLED`, default False), `enrich_budget:int` (`ENRICH_BUDGET`, default 200), `providers_url:str` (`PROVIDERS_URL`, default el raw de arkiv-providers).
- `crawler`:
  - Tras un upsert de título nuevo/cambiado en incremental/full: si `enrich_enabled`, encolar el título.
  - Backfill: al final de la corrida, tomar hasta `enrich_budget` títulos con `last_enriched IS NULL` (activos) y enriquecerlos.
  - `run_enrich(conn, providers, fetcher, limit) -> dict` — enriquece un lote; usado por backfill y por `--mode enrich`.
  - CLI: agregar `--mode enrich`.

- [ ] **Step 1: Write the failing test** — `tests/test_crawler.py` (agregar; con providers fake + fetcher fake, monkeypatch enrich_title):
```python
def test_run_enrich_processes_unenriched(db, monkeypatch):
    import datetime, json, pathlib
    from mirror import crawler, db as db_mod
    MOVIE = json.load(open(pathlib.Path(__file__).parent/"fixtures"/"movie.json"))
    db_mod.upsert_detail(db, MOVIE, datetime.datetime.now(datetime.timezone.utc))
    def fake_enrich(provs, f, row, **k):
        rows = [{"infohash":"bbbb779504892333bce4270819a5910653292c16",
            "magnet":"magnet:?xt=urn:btih:BBBB","torrent_url":None,"quality":None,"size_bytes":1,
            "size_label":None,"lang_raw":None,"lang_norm":"LATINO","seeders":3,"source":"nyaa",
            "match_score":0.8,"download_type":"link"}]
        stats = {"nyaa":{"found":4,"matched":1,"error":False}}
        return rows, stats
    monkeypatch.setattr(crawler, "enrich_title", fake_enrich)
    res = crawler.run_enrich(db, providers=[object()], fetcher=object(), limit=10)
    assert res["enriched"] >= 1
    with db.cursor() as cur:
        cur.execute("SELECT count(*) FROM torrents WHERE source='nyaa'")
        assert cur.fetchone()[0] == 1
        cur.execute("SELECT last_enriched IS NOT NULL FROM titles WHERE id=%s", (MOVIE["id"],))
        assert cur.fetchone()[0] is True
        cur.execute("SELECT found, matched, added FROM provider_stats WHERE provider='nyaa'")
        f, m, a = cur.fetchone()
        assert f == 4 and m == 1 and a == 1        # observabilidad registrada
```

- [ ] **Step 2–3: Run (FAIL) → implementar.** En `config.py` agregar los 3 campos (parseando env). En `crawler.py`:
```python
from mirror.enrich import enrich_title
from mirror.providers.provider_config import load_html_providers
from mirror.providers.fetch import Fetcher
from mirror import repo

def run_enrich(conn, providers, fetcher, limit) -> dict:
    import datetime
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM titles WHERE active AND last_enriched IS NULL "
                    "ORDER BY year DESC NULLS LAST LIMIT %s", (limit,))
        ids = [r[0] for r in cur.fetchall()]
    enriched = added = 0
    agg = {}  # provider -> {titles,found,matched,added,errors}
    for tid in ids:
        with conn.cursor() as cur:
            cur.execute("SELECT id,slug,kind,title,original_title,year FROM titles WHERE id=%s", (tid,))
            c = cur.fetchone()
            row = {"id":c[0],"slug":c[1],"kind":c[2],"title":c[3],"original_title":c[4],"year":c[5]}
        try:
            rows, stats = enrich_title(providers, fetcher, row)
            n = dbmod.upsert_enriched_torrents(conn, tid, rows, datetime.datetime.now(datetime.timezone.utc))
            added += n
            dbmod.mark_enriched(conn, tid, True)
            enriched += 1
            added_by_src = {}
            for r in rows:
                added_by_src[r["source"]] = added_by_src.get(r["source"], 0) + 1
            for pid, s in stats.items():
                a = agg.setdefault(pid, {"titles":0,"found":0,"matched":0,"added":0,"errors":0})
                a["titles"] += 1; a["found"] += s["found"]; a["matched"] += s["matched"]
                a["added"] += added_by_src.get(pid, 0); a["errors"] += 1 if s.get("error") else 0
        except Exception as e:
            dbmod.mark_enriched(conn, tid, False, str(e))
    dbmod.record_provider_stats(conn, agg)
    dbmod.deactivate_titles_without_torrents(conn)
    return {"enriched": enriched, "added": added, "providers": agg}
```
En `main()`: agregar `--mode enrich` que arma `providers = load_html_providers(cfg.providers_url_or_fallback)`, `fetcher = Fetcher(cfg)`, y llama `run_enrich(conn, providers, fetcher, cfg.enrich_budget)`. En `run_full`/`run_incremental`: si `cfg.enrich_enabled`, tras la ingesta llamar `run_enrich(conn, providers, fetcher, cfg.enrich_budget)`. (Cargar providers con fallback: intentar `PROVIDERS_URL` remoto, si falla usar `assets/providers.json`.)

- [ ] **Step 3b: endpoint `/api/stats`** (observabilidad para ver sin entrar a la DB).

En `mirror/repo.py` agregar:
```python
def enrich_stats(conn):
    with _dict_cur(conn) as cur:
        cur.execute("SELECT source, count(*) AS torrents, round(avg(match_score),3) AS avg_score, "
                    "round(avg(seeders),1) AS avg_seeders FROM torrents WHERE source<>'hacktorrent' "
                    "AND active GROUP BY source ORDER BY torrents DESC")
        by_source = [dict(r) for r in cur.fetchall()]
        cur.execute("SELECT DISTINCT ON (provider) provider, run_at, found, matched, added, errors "
                    "FROM provider_stats ORDER BY provider, run_at DESC")
        providers = [dict(r) for r in cur.fetchall()]
        cur.execute("SELECT count(*) FILTER (WHERE last_enriched IS NOT NULL) AS enriched, "
                    "count(*) FILTER (WHERE last_enriched IS NULL AND active) AS pending FROM titles")
        c = cur.fetchone()
    return {"by_source": by_source, "providers": providers,
            "titles_enriched": c["enriched"], "titles_pending": c["pending"]}
```
En `mirror/api.py`, dentro de `create_app`, agregar la ruta:
```python
    @app.get("/api/stats")
    def stats():
        c = conn()
        try:
            return jsonify(repo.enrich_stats(c))
        finally:
            c.close()
```
Test (agregar a `tests/test_api.py`), tras sembrar un torrent enriquecido:
```python
def test_api_stats(db):
    import os, datetime
    from mirror import db as db_mod
    c = _client(db, os.environ["DATABASE_URL"])
    db_mod.upsert_enriched_torrents(db, MOVIE["id"], [{"infohash":"cccc779504892333bce4270819a5910653292c16",
        "magnet":"magnet:?xt=urn:btih:CCCC","torrent_url":None,"quality":None,"size_bytes":1,"size_label":None,
        "lang_raw":None,"lang_norm":"LATINO","seeders":7,"source":"bitsearch","match_score":0.9,
        "download_type":"link"}], datetime.datetime.now(datetime.timezone.utc))
    r = c.get("/api/stats").get_json()
    assert any(s["source"]=="bitsearch" and s["torrents"]==1 for s in r["by_source"])
```
(`MOVIE` ya está cargado en `test_api.py` por `_seed`.)

- [ ] **Step 4: Run full suite** → verde.

- [ ] **Step 5: Commit**
```bash
git add mirror/config.py mirror/crawler.py tests/test_crawler.py
git commit -m "feat(enrich): integracion al crawler (flag, backfill budget, --mode enrich)"
```

---

## Task 9: Validación en vivo + deploy

**Files:** ninguno de código (validación + despliegue).

- [ ] **Step 1: Fixtures CF + validación de parsers reales.** Para eztv/wolfmax4k (Cloudflare/renderJs), obtener HTML vía FlareSolverr de blog y guardarlo como fixture; verificar que sus `rowSelector` traen filas. Si wolfmax4k por FlareSolverr no trae contenido, marcarlo `enabled:false` en el fallback y documentar (queda para navegador headless futuro).

- [ ] **Step 2: Validación de precisión (offline+vivo).** Correr `enrich_title` sobre 5-10 títulos reales (ej. "The Matrix", una serie, un anime) con `Fetcher` real (desde el Mac para los no-CF; vía blog para CF) y revisar A MANO que los torrents matcheados sean correctos (no falsos positivos). Ajustar umbral del matcher si hace falta. Documentar resultados.

- [ ] **Step 3: Deploy a blog.**
```bash
rsync -az --delete --exclude='.venv' --exclude='.git' --exclude='__pycache__' \
  /Users/cristian/hacktorrent-mirror/ blog:/home/familia/hacktorrent-mirror/
ssh blog 'cd /home/familia/hacktorrent-mirror && sudo docker compose -f docker-compose.prod.yml up -d --build'
# migrate corre solo al arrancar (aplica columnas nuevas). Verificar:
ssh blog 'curl -s http://127.0.0.1:8097/api/health'
```

- [ ] **Step 4: Backfill controlado + habilitar.** Con `ENRICH_ENABLED=false` aún, correr un enrich manual acotado y revisar:
```bash
ssh blog 'cd /home/familia/hacktorrent-mirror && sudo docker compose -f docker-compose.prod.yml run --rm -e ENRICH_BUDGET=20 app python -m mirror.crawler --mode enrich'
ssh blog 'sudo docker compose -f /home/familia/hacktorrent-mirror/docker-compose.prod.yml exec -T db psql -U mirror -d hacktorrent -c "SELECT source, count(*) FROM torrents GROUP BY source;"'
```
Revisar precisión de los enriquecidos. Si OK, setear `ENRICH_ENABLED=true` en el `.env` de blog y `up -d` → el backfill corre solo en las corridas horarias hasta cubrir el catálogo.

- [ ] **Step 5: Commit de docs/estado** (si aplica) y actualizar memoria.

---

## Self-Review (autor)

**Cobertura del spec:**
- Config providers = mismo providers.json (remoto+fallback) → Task 2 + Task 8. ✅
- Backend HTML declarativo (port HtmlParser) → Task 3. ✅
- Fetch + FlareSolverr + rate-limit → Task 4. ✅
- Matcher fiel (año/SE, precisión) → Task 5. ✅
- enrich_title (dedup, aislamiento) → Task 6. ✅
- Storage (source/seeders/match_score/last_enriched) + dedup infohash → Task 1 + Task 7. ✅
- Observabilidad (source por torrent, `provider_stats` por corrida, `/api/stats`, last_enriched) →
  Task 1 (tabla) + Task 6 (enrich_title devuelve stats) + Task 7 (record_provider_stats) + Task 8 (/api/stats). ✅
- Reconcile source-scoped + título activo multi-fuente → Task 7. ✅
- Trigger nuevos/cambiados + backfill budget + --mode enrich + flag → Task 8. ✅
- Validación en vivo + deploy → Task 9. ✅
- wolfmax4k vía FlareSolverr (probar, degradar si no) → Task 9 Step 1. ✅

**Placeholder scan:** el seguir-a-detalle de 2 pasos (elitetorrent/dontorrent/divxtotal/subtorrents) se
guarda `torrent_url` y se resuelve al reproducir en v1 (igual que el app); el resolver server-side es
mejora futura explícita (Task 6 nota), no un placeholder abierto.

**Type consistency:** `RawResult` (Task 3) → `enrich_title` filas dict (Task 6) → `upsert_enriched_torrents`
(Task 7) → columnas de Task 1. `HtmlProvider` (Task 2) usado por html_backend/enrich. Consistente.
