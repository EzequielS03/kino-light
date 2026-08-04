# arkiv-offline Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `arkiv-offline` backend — a new Flask+SQLite service that accepts "download this
pack" jobs, drives `aria2c` to fetch torrent packs (and, once accepted, archive.org items) to local
disk on the NUC, and serves the finished files back over HTTP with range-request support, so the
Arkiv app can eventually stream from the NUC instead of the live source.

**Architecture:** Small standalone Python service (own repo, own SQLite DB, own Docker deploy — does
NOT touch `hacktorrent-mirror`'s Postgres or codebase). A sequential worker loop drains a job queue
one job at a time, driving `aria2c` over its JSON-RPC API. Flask exposes the job/library/stream
contract. Torrent jobs are atomic (one magnet, one swarm); archive jobs are per-episode (independent
HTTP downloads). `web`-kind jobs are accepted but stubbed as unsupported (sub-project #2, separate
plan) — every item in a `web` job is immediately marked `failed` with a clear error, never silently
dropped.

**Tech Stack:** Python 3.12, Flask, gunicorn, `sqlite3` (stdlib), `requests` (aria2 JSON-RPC + HTTP),
pytest, aria2c (system package in the Docker image).

## Global Constraints

- New, separate repo/service — do NOT modify `hacktorrent-mirror` or any file under
  `/Users/cristian/hacktorrent-mirror` or `/Users/cristian/archive` in this plan.
- Sequential download queue: **1 job in flight at a time**, no exceptions (2-core host).
- `X-Api-Key` header required on every endpoint EXCEPT `GET /library`, `GET /stream/<item_id>` (those
  stay open — the app polls/streams from them constantly and they're read-only).
- Job/item identity: `series_id + season + episode` — `series_id` reuses the app's existing schemes
  verbatim (`imdbId`/`tmdb<id>` for TV, `anilist<id>` for anime), never a new scheme.
- Torrent jobs are atomic (whole-job success/failure); web/archive jobs are per-item
  (`job_items`) independent success/failure.
- `MAX_OFFLINE_STORAGE_GB` default **100** (env-overridable). No automatic eviction/LRU in this plan —
  deletion is manual via `DELETE /library/<item_id>`.
- A job stuck in `downloading` across a service restart must be reconciled to `failed` on startup, not
  left dangling forever.
- Deploying to `blog` (the production NUC, shared with other live services) is explicitly **OUT OF
  SCOPE for automated task execution** in this plan — the last task produces a working, tested Docker
  image locally; actually deploying to `blog` happens afterward as a separate, human-confirmed step
  (same pattern already used this session for `hacktorrent-mirror` changes).
- Commit identity: `user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`, no
  AI co-author trailers. This is a BRAND NEW repo — Task 1 must configure this identity locally
  before the first commit (it will NOT be inherited from `archive` or any other repo).

---

## File Structure

New repo at `/Users/cristian/arkiv-offline/` (created in Task 1), following the same layout
`hacktorrent-mirror` already uses (Flask package + `tests/` + `Dockerfile` + `docker-compose*.yml`):

- `offline/config.py` — env-var configuration (`Config.from_env`).
- `offline/db.py` — SQLite connection + schema migration + CRUD for `jobs`/`job_items`.
- `offline/storage.py` — disk usage accounting against `MAX_OFFLINE_STORAGE_GB`.
- `offline/jobstate.py` — pure state-transition logic (valid status moves, deriving a job's overall
  status from its items for web/archive).
- `offline/aria2client.py` — thin JSON-RPC client wrapper for `aria2c` (add magnet/URI, poll status).
- `offline/worker.py` — job dispatch, progress polling, startup reconciliation (uses `aria2client` +
  `db`, no Flask dependency).
- `offline/api.py` — Flask app: auth middleware + all HTTP routes from the spec's contract.
- `offline/app.py` — WSGI entrypoint (creates the Flask app + starts the worker loop in a background
  thread) — used by gunicorn/docker.
- `tests/` — one test file per module above, plus `tests/conftest.py` for shared fixtures (temp SQLite
  DB, temp download dir).
- `Dockerfile`, `docker-compose.yml` (dev), `docker-compose.prod.yml`, `gunicorn.conf.py`,
  `requirements.txt`, `.env.example`, `.gitignore`, `README.md`.

---

### Task 1: Repo scaffold + config module

**Files:**
- Create: `/Users/cristian/arkiv-offline/` (new git repo)
- Create: `offline/__init__.py`, `offline/config.py`
- Create: `tests/__init__.py`, `tests/test_config.py`
- Create: `requirements.txt`, `.gitignore`, `README.md`, `.env.example`

**Interfaces:**
- Produces: `Config` dataclass with fields `api_key: str`, `download_dir: str`,
  `max_storage_gb: float`, `aria2_rpc_url: str`, `db_path: str`, `torrent_stall_timeout_s: int`, and
  classmethod `Config.from_env(env: dict) -> Config`.

- [ ] **Step 1: Create the repo and its structure**

```bash
mkdir -p /Users/cristian/arkiv-offline/offline /Users/cristian/arkiv-offline/tests
cd /Users/cristian/arkiv-offline
git init
git config user.name lordmacu
git config user.email "10134930+lordmacu@users.noreply.github.com"
```

- [ ] **Step 2: `.gitignore`, `requirements.txt`, `README.md`, `.env.example`**

`.gitignore`:
```
__pycache__/
*.pyc
.venv/
.pytest_cache/
*.db
.env
downloads/
```

`requirements.txt`:
```
Flask==3.0.3
gunicorn==23.0.0
requests==2.32.3
pytest==8.3.3
```

`README.md`:
```markdown
# arkiv-offline

Backend de descargas server-side para Arkiv: recibe jobs de "bajar este pack" (torrent/archive.org;
web pendiente, ver sub-proyecto #2), los baja con aria2c en la NUC, y sirve los archivos ya
descargados por HTTP con soporte de range-requests.

Ver spec: `docs/superpowers/specs/2026-08-03-arkiv-offline-backend-design.md` en el repo `archive`.

## Desarrollo

```
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest
```
```

`.env.example`:
```
API_KEY=changeme
DOWNLOAD_DIR=/data/downloads
MAX_STORAGE_GB=100
ARIA2_RPC_URL=http://localhost:6800/jsonrpc
DB_PATH=/data/offline.db
TORRENT_STALL_TIMEOUT_S=1800
```

- [ ] **Step 3: Write the failing test for `Config.from_env`**

`tests/test_config.py`:
```python
from offline.config import Config


def test_from_env_reads_all_fields():
    env = {
        "API_KEY": "secret123",
        "DOWNLOAD_DIR": "/data/downloads",
        "MAX_STORAGE_GB": "50",
        "ARIA2_RPC_URL": "http://localhost:6800/jsonrpc",
        "DB_PATH": "/data/offline.db",
        "TORRENT_STALL_TIMEOUT_S": "600",
    }
    cfg = Config.from_env(env)
    assert cfg.api_key == "secret123"
    assert cfg.download_dir == "/data/downloads"
    assert cfg.max_storage_gb == 50.0
    assert cfg.aria2_rpc_url == "http://localhost:6800/jsonrpc"
    assert cfg.db_path == "/data/offline.db"
    assert cfg.torrent_stall_timeout_s == 600


def test_from_env_defaults():
    cfg = Config.from_env({"API_KEY": "x"})
    assert cfg.max_storage_gb == 100.0
    assert cfg.torrent_stall_timeout_s == 1800
    assert cfg.aria2_rpc_url == "http://localhost:6800/jsonrpc"


def test_from_env_requires_api_key():
    try:
        Config.from_env({})
        assert False, "esperaba ValueError"
    except ValueError as e:
        assert "API_KEY" in str(e)
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /Users/cristian/arkiv-offline && python -m pytest tests/test_config.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'offline'` or `ImportError` (Config doesn't exist yet).

- [ ] **Step 5: Implement `offline/config.py`**

```python
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Config:
    api_key: str
    download_dir: str = "/data/downloads"
    max_storage_gb: float = 100.0
    aria2_rpc_url: str = "http://localhost:6800/jsonrpc"
    db_path: str = "/data/offline.db"
    torrent_stall_timeout_s: int = 1800

    @classmethod
    def from_env(cls, env: dict) -> "Config":
        api_key = env.get("API_KEY")
        if not api_key:
            raise ValueError("API_KEY es obligatorio")
        return cls(
            api_key=api_key,
            download_dir=env.get("DOWNLOAD_DIR", cls.download_dir),
            max_storage_gb=float(env.get("MAX_STORAGE_GB", cls.max_storage_gb)),
            aria2_rpc_url=env.get("ARIA2_RPC_URL", cls.aria2_rpc_url),
            db_path=env.get("DB_PATH", cls.db_path),
            torrent_stall_timeout_s=int(env.get("TORRENT_STALL_TIMEOUT_S", cls.torrent_stall_timeout_s)),
        )
```

`offline/__init__.py` and `tests/__init__.py`: empty files.

- [ ] **Step 6: Run the test to verify it passes**

Run: `python -m pytest tests/test_config.py -v`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: scaffold del repo + Config.from_env"
```

---

### Task 2: SQLite schema + `db.py` CRUD

**Files:**
- Create: `offline/db.py`
- Create: `tests/test_db.py`
- Create: `tests/conftest.py`

**Interfaces:**
- Consumes: nothing from Task 1 directly (takes a raw `db_path` string, not `Config`).
- Produces: `connect(db_path) -> sqlite3.Connection`, `migrate(conn)`,
  `create_job(conn, kind, series_id, show_title, poster_url, magnet, items) -> int` (items = list of
  dicts with `season, episode, episode_name, source_ref`; returns `job_id`),
  `get_job(conn, job_id) -> dict | None` (includes nested `items: list[dict]`),
  `update_job_status(conn, job_id, status, error=None)`,
  `update_item_status(conn, item_id, status, file_path=None, size_bytes=None, error=None)`,
  `list_library(conn, series_id) -> list[dict]` (only items with status='done'),
  `delete_job(conn, job_id)`, `delete_item(conn, item_id)`,
  `jobs_stuck_downloading(conn) -> list[dict]` (for startup reconciliation).

- [ ] **Step 1: `tests/conftest.py` — shared temp-DB fixture**

```python
import sqlite3
import tempfile
import os
import pytest
from offline import db as dbmod


@pytest.fixture
def conn():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    c = dbmod.connect(path)
    dbmod.migrate(c)
    yield c
    c.close()
    os.unlink(path)
```

- [ ] **Step 2: Write the failing tests**

`tests/test_db.py`:
```python
from offline import db as dbmod


def test_create_job_and_get_job_roundtrip(conn):
    job_id = dbmod.create_job(
        conn, kind="torrent", series_id="anilist123", show_title="Blood+",
        poster_url="http://x/p.jpg", magnet="magnet:?xt=urn:btih:abc",
        items=[
            {"season": 1, "episode": 1, "episode_name": "Ep 1", "source_ref": "0"},
            {"season": 1, "episode": 2, "episode_name": "Ep 2", "source_ref": "1"},
        ],
    )
    job = dbmod.get_job(conn, job_id)
    assert job["kind"] == "torrent"
    assert job["series_id"] == "anilist123"
    assert job["status"] == "queued"
    assert len(job["items"]) == 2
    assert job["items"][0]["episode"] == 1
    assert job["items"][0]["status"] == "pending"


def test_get_job_missing_returns_none(conn):
    assert dbmod.get_job(conn, 9999) is None


def test_update_job_status(conn):
    job_id = dbmod.create_job(conn, kind="archive", series_id="tmdb1", show_title="X",
                               poster_url="", magnet=None, items=[])
    dbmod.update_job_status(conn, job_id, "downloading")
    assert dbmod.get_job(conn, job_id)["status"] == "downloading"
    dbmod.update_job_status(conn, job_id, "failed", error="sin espacio")
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "failed"
    assert job["error"] == "sin espacio"


def test_update_item_status(conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="tmdb1", show_title="X", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "id1"}],
    )
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    dbmod.update_item_status(conn, item_id, "done", file_path="tmdb1/S01E01.mp4", size_bytes=12345)
    item = dbmod.get_job(conn, job_id)["items"][0]
    assert item["status"] == "done"
    assert item["file_path"] == "tmdb1/S01E01.mp4"
    assert item["size_bytes"] == 12345


def test_list_library_only_done_items(conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="tmdb1", show_title="X", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "id1"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "id2"},
        ],
    )
    items = dbmod.get_job(conn, job_id)["items"]
    dbmod.update_item_status(conn, items[0]["id"], "done", file_path="a.mp4", size_bytes=10)
    dbmod.update_item_status(conn, items[1]["id"], "failed", error="404")
    lib = dbmod.list_library(conn, "tmdb1")
    assert len(lib) == 1
    assert lib[0]["episode"] == 1


def test_delete_job_cascades_items(conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="tmdb1", show_title="X", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "id1"}],
    )
    dbmod.delete_job(conn, job_id)
    assert dbmod.get_job(conn, job_id) is None


def test_jobs_stuck_downloading(conn):
    j1 = dbmod.create_job(conn, kind="torrent", series_id="a", show_title="A", poster_url="",
                           magnet="magnet:?xt=x", items=[])
    j2 = dbmod.create_job(conn, kind="torrent", series_id="b", show_title="B", poster_url="",
                           magnet="magnet:?xt=y", items=[])
    dbmod.update_job_status(conn, j1, "downloading")
    dbmod.update_job_status(conn, j2, "done")
    stuck = dbmod.jobs_stuck_downloading(conn)
    assert [j["id"] for j in stuck] == [j1]
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `python -m pytest tests/test_db.py -v`
Expected: FAIL — `offline.db` has no `connect`/`migrate`/etc.

- [ ] **Step 4: Implement `offline/db.py`**

```python
from __future__ import annotations

import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
  id INTEGER PRIMARY KEY,
  kind TEXT NOT NULL,
  series_id TEXT NOT NULL,
  show_title TEXT NOT NULL,
  poster_url TEXT,
  magnet TEXT,
  status TEXT NOT NULL DEFAULT 'queued',
  error TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS job_items (
  id INTEGER PRIMARY KEY,
  job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  season INTEGER,
  episode INTEGER NOT NULL,
  episode_name TEXT,
  source_ref TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  file_path TEXT,
  size_bytes INTEGER,
  error TEXT,
  UNIQUE(job_id, episode, season)
);
"""


def connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def migrate(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    conn.commit()


def create_job(conn, kind, series_id, show_title, poster_url, magnet, items) -> int:
    with conn:
        cur = conn.execute(
            "INSERT INTO jobs (kind, series_id, show_title, poster_url, magnet, status) "
            "VALUES (?, ?, ?, ?, ?, 'queued')",
            (kind, series_id, show_title, poster_url, magnet),
        )
        job_id = cur.lastrowid
        for it in items:
            conn.execute(
                "INSERT INTO job_items (job_id, season, episode, episode_name, source_ref, status) "
                "VALUES (?, ?, ?, ?, ?, 'pending')",
                (job_id, it.get("season"), it["episode"], it.get("episode_name"), it.get("source_ref")),
            )
    return job_id


def _row_to_job(row: sqlite3.Row) -> dict:
    return dict(row)


def get_job(conn, job_id) -> dict | None:
    row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if row is None:
        return None
    job = _row_to_job(row)
    items = conn.execute(
        "SELECT * FROM job_items WHERE job_id = ? ORDER BY season, episode", (job_id,)
    ).fetchall()
    job["items"] = [dict(i) for i in items]
    return job


def update_job_status(conn, job_id, status, error=None) -> None:
    with conn:
        conn.execute(
            "UPDATE jobs SET status = ?, error = ?, updated_at = datetime('now') WHERE id = ?",
            (status, error, job_id),
        )


def update_item_status(conn, item_id, status, file_path=None, size_bytes=None, error=None) -> None:
    with conn:
        conn.execute(
            "UPDATE job_items SET status = ?, file_path = ?, size_bytes = ?, error = ? WHERE id = ?",
            (status, file_path, size_bytes, error, item_id),
        )


def list_library(conn, series_id) -> list[dict]:
    rows = conn.execute(
        "SELECT ji.* FROM job_items ji JOIN jobs j ON j.id = ji.job_id "
        "WHERE j.series_id = ? AND ji.status = 'done' ORDER BY ji.season, ji.episode",
        (series_id,),
    ).fetchall()
    return [dict(r) for r in rows]


def delete_job(conn, job_id) -> None:
    with conn:
        conn.execute("DELETE FROM jobs WHERE id = ?", (job_id,))


def delete_item(conn, item_id) -> None:
    with conn:
        conn.execute("DELETE FROM job_items WHERE id = ?", (item_id,))


def jobs_stuck_downloading(conn) -> list[dict]:
    rows = conn.execute("SELECT * FROM jobs WHERE status = 'downloading'").fetchall()
    return [dict(r) for r in rows]
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_db.py -v`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: esquema SQLite + CRUD de jobs/job_items"
```

---

### Task 3: Disk space accounting (`storage.py`)

**Files:**
- Create: `offline/storage.py`
- Create: `tests/test_storage.py`

**Interfaces:**
- Produces: `used_bytes(download_dir: str) -> int` (recursive sum of file sizes),
  `fits(download_dir: str, max_storage_gb: float, incoming_bytes: int) -> bool`.

- [ ] **Step 1: Write the failing tests**

`tests/test_storage.py`:
```python
import os
import tempfile
import shutil
import pytest
from offline import storage


@pytest.fixture
def tmp_download_dir():
    d = tempfile.mkdtemp()
    yield d
    shutil.rmtree(d, ignore_errors=True)


def test_used_bytes_empty_dir(tmp_download_dir):
    assert storage.used_bytes(tmp_download_dir) == 0


def test_used_bytes_sums_nested_files(tmp_download_dir):
    os.makedirs(os.path.join(tmp_download_dir, "show1"))
    with open(os.path.join(tmp_download_dir, "show1", "a.mp4"), "wb") as f:
        f.write(b"x" * 1000)
    with open(os.path.join(tmp_download_dir, "b.mp4"), "wb") as f:
        f.write(b"y" * 500)
    assert storage.used_bytes(tmp_download_dir) == 1500


def test_used_bytes_missing_dir_is_zero():
    assert storage.used_bytes("/nonexistent/path/xyz") == 0


def test_fits_within_budget(tmp_download_dir):
    # 0 usado, límite 1GB, entra 500MB
    assert storage.fits(tmp_download_dir, max_storage_gb=1.0, incoming_bytes=500 * 1024 * 1024) is True


def test_fits_exceeds_budget(tmp_download_dir):
    with open(os.path.join(tmp_download_dir, "big.mp4"), "wb") as f:
        f.write(b"x" * (900 * 1024 * 1024))  # 900MB ya usados
    # límite 1GB, entran 200MB más -> 1100MB > 1024MB, no entra
    assert storage.fits(tmp_download_dir, max_storage_gb=1.0, incoming_bytes=200 * 1024 * 1024) is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_storage.py -v`
Expected: FAIL — `offline.storage` no existe.

- [ ] **Step 3: Implement `offline/storage.py`**

```python
from __future__ import annotations

import os


def used_bytes(download_dir: str) -> int:
    if not os.path.isdir(download_dir):
        return 0
    total = 0
    for root, _dirs, files in os.walk(download_dir):
        for name in files:
            path = os.path.join(root, name)
            try:
                total += os.path.getsize(path)
            except OSError:
                continue
    return total


def fits(download_dir: str, max_storage_gb: float, incoming_bytes: int) -> bool:
    limit_bytes = int(max_storage_gb * 1024 * 1024 * 1024)
    return used_bytes(download_dir) + incoming_bytes <= limit_bytes
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_storage.py -v`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: chequeo de espacio en disco contra MAX_STORAGE_GB"
```

---

### Task 4: Job state machine (`jobstate.py`)

**Files:**
- Create: `offline/jobstate.py`
- Create: `tests/test_jobstate.py`

**Interfaces:**
- Produces: `VALID_TRANSITIONS: dict[str, set[str]]`,
  `can_transition(current: str, new: str) -> bool`,
  `derive_job_status_from_items(items: list[dict]) -> str` (for web/archive: `'done'` if every item is
  `done` or `failed` AND at least one is `done`; `'failed'` if every item is `failed`; `'downloading'`
  otherwise).

- [ ] **Step 1: Write the failing tests**

`tests/test_jobstate.py`:
```python
from offline import jobstate


def test_valid_transitions_queued_to_downloading():
    assert jobstate.can_transition("queued", "downloading") is True


def test_valid_transitions_downloading_to_done():
    assert jobstate.can_transition("downloading", "done") is True


def test_valid_transitions_downloading_to_failed():
    assert jobstate.can_transition("downloading", "failed") is True


def test_invalid_transition_done_to_downloading():
    assert jobstate.can_transition("done", "downloading") is False


def test_invalid_transition_failed_to_done():
    assert jobstate.can_transition("failed", "done") is False


def test_any_state_can_be_canceled():
    assert jobstate.can_transition("queued", "canceled") is True
    assert jobstate.can_transition("downloading", "canceled") is True


def test_derive_status_all_done():
    items = [{"status": "done"}, {"status": "done"}]
    assert jobstate.derive_job_status_from_items(items) == "done"


def test_derive_status_mixed_done_and_failed_is_done():
    # al menos uno bajó bien -> el job se considera terminado (no perfecto, pero completo)
    items = [{"status": "done"}, {"status": "failed"}]
    assert jobstate.derive_job_status_from_items(items) == "done"


def test_derive_status_all_failed():
    items = [{"status": "failed"}, {"status": "failed"}]
    assert jobstate.derive_job_status_from_items(items) == "failed"


def test_derive_status_still_pending():
    items = [{"status": "done"}, {"status": "pending"}]
    assert jobstate.derive_job_status_from_items(items) == "downloading"


def test_derive_status_empty_items_is_failed():
    assert jobstate.derive_job_status_from_items([]) == "failed"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_jobstate.py -v`
Expected: FAIL — `offline.jobstate` no existe.

- [ ] **Step 3: Implement `offline/jobstate.py`**

```python
from __future__ import annotations

VALID_TRANSITIONS: dict[str, set[str]] = {
    "queued": {"downloading", "canceled", "failed"},
    "downloading": {"done", "failed", "canceled"},
    "done": set(),
    "failed": set(),
    "canceled": set(),
}


def can_transition(current: str, new: str) -> bool:
    return new in VALID_TRANSITIONS.get(current, set())


def derive_job_status_from_items(items: list[dict]) -> str:
    if not items:
        return "failed"
    statuses = {it["status"] for it in items}
    if statuses <= {"done", "failed"}:
        return "done" if "done" in statuses else "failed"
    return "downloading"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_jobstate.py -v`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: maquina de estados de jobs (transiciones + derivacion desde items)"
```

---

### Task 5: aria2 JSON-RPC client (`aria2client.py`)

**Files:**
- Create: `offline/aria2client.py`
- Create: `tests/test_aria2client.py`

**Interfaces:**
- Produces: `build_select_file_option(indices: list[int] | None) -> dict` (returns `{}` if `indices`
  is `None`, else `{"select-file": "1,3,5"}` — aria2 file indices are 1-based),
  `Aria2Client(rpc_url: str)` with methods `add_magnet(magnet: str, options: dict) -> str` (returns
  gid), `add_uri(url: str, options: dict) -> str` (returns gid),
  `tell_status(gid: str) -> dict` (returns `{"status": "active"|"complete"|"error"|"removed", "completedLength": int, "totalLength": int, "files": [...]}` — the raw-ish aria2 shape, callers translate it).

- [ ] **Step 1: Write the failing tests**

`tests/test_aria2client.py`:
```python
from unittest.mock import patch, MagicMock
from offline.aria2client import Aria2Client, build_select_file_option


def test_build_select_file_option_none():
    assert build_select_file_option(None) == {}


def test_build_select_file_option_indices_are_1_based_csv():
    # el caller pasa indices 0-based (como PackFileRow del app); aria2 los quiere 1-based
    assert build_select_file_option([0, 2, 4]) == {"select-file": "1,3,5"}


@patch("offline.aria2client.requests.post")
def test_add_magnet_returns_gid(mock_post):
    mock_post.return_value = MagicMock(
        status_code=200, json=lambda: {"jsonrpc": "2.0", "id": "1", "result": "gid123"}
    )
    client = Aria2Client("http://localhost:6800/jsonrpc")
    gid = client.add_magnet("magnet:?xt=urn:btih:abc", {"select-file": "1,3"})
    assert gid == "gid123"
    call_args = mock_post.call_args
    body = call_args.kwargs["json"]
    assert body["method"] == "aria2.addUri"
    assert body["params"][1] == ["magnet:?xt=urn:btih:abc"]
    assert body["params"][2] == {"select-file": "1,3"}


@patch("offline.aria2client.requests.post")
def test_add_uri_returns_gid(mock_post):
    mock_post.return_value = MagicMock(
        status_code=200, json=lambda: {"jsonrpc": "2.0", "id": "1", "result": "gid456"}
    )
    client = Aria2Client("http://localhost:6800/jsonrpc")
    gid = client.add_uri("https://archive.org/download/x/x.mp4", {})
    assert gid == "gid456"


@patch("offline.aria2client.requests.post")
def test_tell_status_returns_result_dict(mock_post):
    mock_post.return_value = MagicMock(
        status_code=200,
        json=lambda: {
            "jsonrpc": "2.0", "id": "1",
            "result": {"status": "active", "completedLength": "500", "totalLength": "1000", "files": []},
        },
    )
    client = Aria2Client("http://localhost:6800/jsonrpc")
    status = client.tell_status("gid123")
    assert status["status"] == "active"
    assert status["completedLength"] == "500"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_aria2client.py -v`
Expected: FAIL — `offline.aria2client` no existe.

- [ ] **Step 3: Implement `offline/aria2client.py`**

```python
from __future__ import annotations

import requests


def build_select_file_option(indices: list[int] | None) -> dict:
    if not indices:
        return {}
    # aria2 usa indices de archivo 1-based; el resto del sistema (PackFileRow) usa 0-based.
    return {"select-file": ",".join(str(i + 1) for i in indices)}


class Aria2Client:
    def __init__(self, rpc_url: str):
        self.rpc_url = rpc_url

    def _call(self, method: str, params: list):
        resp = requests.post(self.rpc_url, json={
            "jsonrpc": "2.0", "id": "1", "method": method, "params": params,
        }, timeout=15)
        resp.raise_for_status()
        return resp.json()["result"]

    def add_magnet(self, magnet: str, options: dict) -> str:
        return self._call("aria2.addUri", ["token:", [magnet], options])

    def add_uri(self, url: str, options: dict) -> str:
        return self._call("aria2.addUri", ["token:", [url], options])

    def tell_status(self, gid: str) -> dict:
        return self._call("aria2.tellStatus", ["token:", gid])
```

Note: `"token:"` is a placeholder first param slot — aria2's RPC secret (if configured) goes there as
`f"token:{secret}"`. This plan doesn't configure an RPC secret (aria2 only listens on localhost inside
the same container/network, per Task 9's Docker setup) — leaving it as the literal string `"token:"`
matches aria2's "no secret" convention. If Task 9 ends up requiring the RPC secret (aria2c default
config may reject unauthenticated calls even locally), amend `Aria2Client` to accept a `secret` param
threaded from `Config` — flag this as a concern in Task 9's report if it comes up, don't guess a fix
here.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_aria2client.py -v`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: cliente JSON-RPC de aria2 (add magnet/uri, tell_status)"
```

---

### Task 6: Worker — dispatch, polling, reconciliation

**Files:**
- Create: `offline/worker.py`
- Create: `tests/test_worker.py`

**Interfaces:**
- Consumes: `offline.db` (Task 2), `offline.jobstate` (Task 4), `offline.aria2client.Aria2Client`
  (Task 5, injected — tests pass a fake).
- Produces: `dispatch_job(conn, aria2, job: dict) -> None` (starts the aria2 download(s) for a queued
  job, moves it to `downloading`, stores the aria2 `gid` — for torrent jobs, one gid on the job row via
  a new `aria2_gid` column; for archive jobs, one gid per item via a new `job_items.aria2_gid` column;
  for web jobs, immediately marks every item `failed` with `error='web aun no soportado'` and the job
  `failed` via `jobstate.derive_job_status_from_items`, no aria2 call),
  `poll_job(conn, aria2, job: dict) -> None` (checks aria2 status for a `downloading` job and updates
  item/job status accordingly — `complete` → `done`, `error`/`removed` → `failed`),
  `reconcile_on_startup(conn, aria2) -> int` (marks `downloading` jobs `failed` if aria2 doesn't
  recognize their gid anymore; returns count reconciled).

- [ ] **Step 1: Extend the schema for `aria2_gid` (small addition to Task 2's schema)**

Add to `offline/db.py`'s `SCHEMA` string (edit the existing `jobs` and `job_items` table definitions —
add one column to each, right after `magnet TEXT,` in `jobs` and right after `source_ref TEXT,` in
`job_items`):

```sql
  aria2_gid TEXT,
```

(One line added to each `CREATE TABLE`. `sqlite3` re-running `CREATE TABLE IF NOT EXISTS` on an
already-migrated dev DB won't retroactively add the column — that's fine, this repo has no deployed
data yet, `migrate()` always runs against a fresh DB in tests and in the not-yet-deployed prod
instance.)

- [ ] **Step 2: Write the failing tests**

`tests/test_worker.py`:
```python
from offline import db as dbmod
from offline import worker


class FakeAria2:
    def __init__(self):
        self.added_magnets = []
        self.added_uris = []
        self.statuses = {}
        self._next_gid = 1

    def add_magnet(self, magnet, options):
        gid = f"gid{self._next_gid}"
        self._next_gid += 1
        self.added_magnets.append((magnet, options, gid))
        return gid

    def add_uri(self, url, options):
        gid = f"gid{self._next_gid}"
        self._next_gid += 1
        self.added_uris.append((url, options, gid))
        return gid

    def tell_status(self, gid):
        return self.statuses[gid]


def test_dispatch_torrent_job_adds_one_magnet(conn):
    job_id = dbmod.create_job(
        conn, kind="torrent", series_id="a", show_title="A", poster_url="",
        magnet="magnet:?xt=urn:btih:abc",
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "0"}],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    assert len(aria2.added_magnets) == 1
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "downloading"
    assert job["aria2_gid"] == "gid1"


def test_dispatch_archive_job_adds_one_uri_per_item(conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://archive.org/x/1.mp4"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "https://archive.org/x/2.mp4"},
        ],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    assert len(aria2.added_uris) == 2
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "downloading"
    assert all(it["aria2_gid"] for it in job["items"])


def test_dispatch_web_job_marks_everything_failed_without_calling_aria2(conn):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    assert aria2.added_magnets == []
    assert aria2.added_uris == []
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "failed"
    assert job["items"][0]["status"] == "failed"
    assert "no soportado" in job["items"][0]["error"]


def test_poll_torrent_job_complete_marks_done(conn):
    job_id = dbmod.create_job(
        conn, kind="torrent", series_id="a", show_title="A", poster_url="",
        magnet="magnet:?xt=urn:btih:abc",
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "0"}],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    gid = dbmod.get_job(conn, job_id)["aria2_gid"]
    aria2.statuses[gid] = {
        "status": "complete", "completedLength": "1000", "totalLength": "1000",
        "files": [{"path": "/data/downloads/a/ep1.mkv", "length": "1000", "selected": "true"}],
    }
    worker.poll_job(conn, aria2, dbmod.get_job(conn, job_id))
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "done"
    assert job["items"][0]["status"] == "done"
    assert job["items"][0]["file_path"] == "/data/downloads/a/ep1.mkv"


def test_poll_torrent_job_error_marks_failed(conn):
    job_id = dbmod.create_job(
        conn, kind="torrent", series_id="a", show_title="A", poster_url="",
        magnet="magnet:?xt=urn:btih:abc",
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "0"}],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    gid = dbmod.get_job(conn, job_id)["aria2_gid"]
    aria2.statuses[gid] = {"status": "error", "completedLength": "0", "totalLength": "1000", "files": []}
    worker.poll_job(conn, aria2, dbmod.get_job(conn, job_id))
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "failed"


def test_poll_torrent_job_still_active_stays_downloading(conn):
    job_id = dbmod.create_job(
        conn, kind="torrent", series_id="a", show_title="A", poster_url="",
        magnet="magnet:?xt=urn:btih:abc",
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "0"}],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    gid = dbmod.get_job(conn, job_id)["aria2_gid"]
    aria2.statuses[gid] = {"status": "active", "completedLength": "500", "totalLength": "1000", "files": []}
    worker.poll_job(conn, aria2, dbmod.get_job(conn, job_id))
    assert dbmod.get_job(conn, job_id)["status"] == "downloading"


def test_poll_archive_job_one_item_done_one_failed_job_completes(conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "https://x/2.mp4"},
        ],
    )
    aria2 = FakeAria2()
    worker.dispatch_job(conn, aria2, dbmod.get_job(conn, job_id))
    items = dbmod.get_job(conn, job_id)["items"]
    aria2.statuses[items[0]["aria2_gid"]] = {
        "status": "complete", "completedLength": "10", "totalLength": "10",
        "files": [{"path": "/data/downloads/a/ep1.mp4", "length": "10", "selected": "true"}],
    }
    aria2.statuses[items[1]["aria2_gid"]] = {"status": "error", "completedLength": "0", "totalLength": "10", "files": []}
    worker.poll_job(conn, aria2, dbmod.get_job(conn, job_id))
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "done"
    assert job["items"][0]["status"] == "done"
    assert job["items"][1]["status"] == "failed"


def test_reconcile_on_startup_marks_orphaned_downloading_jobs_failed(conn):
    job_id = dbmod.create_job(conn, kind="torrent", series_id="a", show_title="A", poster_url="",
                               magnet="magnet:?xt=x", items=[])
    dbmod.update_job_status(conn, job_id, "downloading")
    aria2 = FakeAria2()  # tell_status(gid) -> KeyError porque no lo conoce

    class RaisingAria2(FakeAria2):
        def tell_status(self, gid):
            raise KeyError(gid)

    n = worker.reconcile_on_startup(conn, RaisingAria2())
    assert n == 1
    assert dbmod.get_job(conn, job_id)["status"] == "failed"
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `python -m pytest tests/test_worker.py -v`
Expected: FAIL — `offline.worker` no existe, y el esquema aún no tiene `aria2_gid` (falla también si
el Step 1 de este task no se aplicó).

- [ ] **Step 4: Implement `offline/worker.py`**

```python
from __future__ import annotations

from offline import db as dbmod
from offline import jobstate
from offline.aria2client import build_select_file_option


def dispatch_job(conn, aria2, job: dict) -> None:
    if job["kind"] == "web":
        for item in job["items"]:
            dbmod.update_item_status(conn, item["id"], "failed", error="web aun no soportado")
        dbmod.update_job_status(conn, job["id"], "failed", error="web aun no soportado")
        return

    if job["kind"] == "torrent":
        indices = [int(it["source_ref"]) for it in job["items"] if it.get("source_ref") is not None]
        options = build_select_file_option(indices) if indices else {}
        gid = aria2.add_magnet(job["magnet"], options)
        with conn:
            conn.execute("UPDATE jobs SET aria2_gid = ? WHERE id = ?", (gid, job["id"]))
        dbmod.update_job_status(conn, job["id"], "downloading")
        return

    # archive: una uri por item
    for item in job["items"]:
        gid = aria2.add_uri(item["source_ref"], {})
        with conn:
            conn.execute("UPDATE job_items SET aria2_gid = ? WHERE id = ?", (gid, item["id"]))
    dbmod.update_job_status(conn, job["id"], "downloading")


def _finished_file_path(status: dict) -> str | None:
    files = status.get("files") or []
    for f in files:
        if f.get("selected") in ("true", True):
            return f.get("path")
    return files[0]["path"] if files else None


def poll_job(conn, aria2, job: dict) -> None:
    if job["status"] != "downloading":
        return

    if job["kind"] == "torrent":
        status = aria2.tell_status(job["aria2_gid"])
        if status["status"] == "complete":
            path = _finished_file_path(status)
            size = int(status.get("totalLength") or 0)
            for item in job["items"]:
                dbmod.update_item_status(conn, item["id"], "done", file_path=path, size_bytes=size)
            dbmod.update_job_status(conn, job["id"], "done")
        elif status["status"] in ("error", "removed"):
            dbmod.update_job_status(conn, job["id"], "failed", error=f"aria2: {status['status']}")
        return

    # archive: consultar cada item independientemente
    for item in job["items"]:
        if item["status"] != "pending":
            continue
        status = aria2.tell_status(item["aria2_gid"])
        if status["status"] == "complete":
            path = _finished_file_path(status)
            size = int(status.get("totalLength") or 0)
            dbmod.update_item_status(conn, item["id"], "done", file_path=path, size_bytes=size)
        elif status["status"] in ("error", "removed"):
            dbmod.update_item_status(conn, item["id"], "failed", error=f"aria2: {status['status']}")

    refreshed = dbmod.get_job(conn, job["id"])
    derived = jobstate.derive_job_status_from_items(refreshed["items"])
    if derived != "downloading":
        dbmod.update_job_status(conn, job["id"], derived)


def reconcile_on_startup(conn, aria2) -> int:
    n = 0
    for job in dbmod.jobs_stuck_downloading(conn):
        try:
            gid = job.get("aria2_gid")
            if gid:
                aria2.tell_status(gid)
                continue  # aria2 lo conoce, lo deja para que poll_job lo siga
        except Exception:
            pass
        dbmod.update_job_status(conn, job["id"], "failed", error="huerfano tras reinicio del servicio")
        n += 1
    return n
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_worker.py -v`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full suite so far**

Run: `python -m pytest -v`
Expected: PASS (all tests from Tasks 1-6).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: worker (dispatch/poll/reconciliacion) + columna aria2_gid"
```

---

### Task 7: Flask API — auth + job endpoints

**Files:**
- Create: `offline/api.py`
- Create: `tests/test_api.py`

**Interfaces:**
- Consumes: `offline.config.Config`, `offline.db`, `offline.storage`.
- Produces: `create_app(cfg: Config, conn) -> Flask` (dependency-injected — tests build their own
  `conn`/`cfg`, production wiring happens in Task 9's `app.py`). Routes: `POST /jobs`,
  `GET /jobs/<id>`, `DELETE /jobs/<id>`.

- [ ] **Step 1: Write the failing tests**

`tests/test_api.py`:
```python
import pytest
from offline.config import Config
from offline.api import create_app
from offline import db as dbmod


@pytest.fixture
def client(conn, tmp_path):
    cfg = Config.from_env({"API_KEY": "secret123", "DOWNLOAD_DIR": str(tmp_path), "MAX_STORAGE_GB": "1"})
    app = create_app(cfg, conn)
    app.config["TESTING"] = True
    return app.test_client()


def test_post_jobs_requires_api_key(client):
    resp = client.post("/jobs", json={"kind": "archive", "series_id": "a", "show_title": "A", "items": []})
    assert resp.status_code == 401


def test_post_jobs_creates_job(client):
    resp = client.post(
        "/jobs", headers={"X-Api-Key": "secret123"},
        json={
            "kind": "archive", "series_id": "a", "show_title": "A", "poster_url": "",
            "items": [{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
        },
    )
    assert resp.status_code == 201
    assert "job_id" in resp.get_json()


def test_get_job_requires_api_key(client):
    resp = client.get("/jobs/1")
    assert resp.status_code == 401


def test_get_job_returns_created_job(client):
    post = client.post(
        "/jobs", headers={"X-Api-Key": "secret123"},
        json={"kind": "archive", "series_id": "a", "show_title": "A", "poster_url": "", "items": []},
    )
    job_id = post.get_json()["job_id"]
    resp = client.get(f"/jobs/{job_id}", headers={"X-Api-Key": "secret123"})
    assert resp.status_code == 200
    assert resp.get_json()["status"] == "queued"


def test_get_job_not_found(client):
    resp = client.get("/jobs/9999", headers={"X-Api-Key": "secret123"})
    assert resp.status_code == 404


def test_delete_job_removes_it(client):
    post = client.post(
        "/jobs", headers={"X-Api-Key": "secret123"},
        json={"kind": "archive", "series_id": "a", "show_title": "A", "poster_url": "", "items": []},
    )
    job_id = post.get_json()["job_id"]
    resp = client.delete(f"/jobs/{job_id}", headers={"X-Api-Key": "secret123"})
    assert resp.status_code == 204
    assert client.get(f"/jobs/{job_id}", headers={"X-Api-Key": "secret123"}).status_code == 404


def test_post_jobs_rejects_when_over_storage_budget(client):
    resp = client.post(
        "/jobs", headers={"X-Api-Key": "secret123"},
        json={
            "kind": "archive", "series_id": "a", "show_title": "A", "poster_url": "",
            "estimated_bytes": 2 * 1024 * 1024 * 1024,  # 2GB, límite del fixture es 1GB
            "items": [{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
        },
    )
    assert resp.status_code == 409
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL — `offline.api` no existe.

- [ ] **Step 3: Implement `offline/api.py`**

```python
from __future__ import annotations

from flask import Flask, jsonify, request

from offline import db as dbmod
from offline import storage


def create_app(cfg, conn) -> Flask:
    app = Flask(__name__)

    def require_api_key():
        if request.headers.get("X-Api-Key") != cfg.api_key:
            return jsonify({"error": "unauthorized"}), 401
        return None

    @app.post("/jobs")
    def post_jobs():
        auth = require_api_key()
        if auth:
            return auth
        body = request.get_json(force=True)
        estimated_bytes = body.get("estimated_bytes", 0)
        if estimated_bytes and not storage.fits(cfg.download_dir, cfg.max_storage_gb, estimated_bytes):
            return jsonify({"error": "sin espacio suficiente en la NUC"}), 409
        job_id = dbmod.create_job(
            conn, kind=body["kind"], series_id=body["series_id"], show_title=body["show_title"],
            poster_url=body.get("poster_url", ""), magnet=body.get("magnet"), items=body.get("items", []),
        )
        return jsonify({"job_id": job_id}), 201

    @app.get("/jobs/<int:job_id>")
    def get_job(job_id):
        auth = require_api_key()
        if auth:
            return auth
        job = dbmod.get_job(conn, job_id)
        if job is None:
            return jsonify({"error": "not found"}), 404
        return jsonify(job), 200

    @app.delete("/jobs/<int:job_id>")
    def delete_job(job_id):
        auth = require_api_key()
        if auth:
            return auth
        if dbmod.get_job(conn, job_id) is None:
            return jsonify({"error": "not found"}), 404
        dbmod.delete_job(conn, job_id)
        return "", 204

    return app
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_api.py -v`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: API Flask - auth + POST/GET/DELETE /jobs"
```

---

### Task 8: Flask API — library + stream endpoints

**Files:**
- Modify: `offline/api.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Consumes: `offline.db.list_library`, `offline.db.delete_item`, `offline.db.get_job` (via a new
  `offline.db.get_item(conn, item_id) -> dict | None` — add this small function).
- Produces: routes `GET /library?series_id=X` (no auth), `GET /stream/<item_id>` (no auth, supports
  `Range` header), `DELETE /library/<item_id>` (auth required).

- [ ] **Step 1: Add `get_item` to `offline/db.py`**

```python
def get_item(conn, item_id) -> dict | None:
    row = conn.execute("SELECT * FROM job_items WHERE id = ?", (item_id,)).fetchone()
    return dict(row) if row else None
```

- [ ] **Step 2: Write the failing tests**

Append to `tests/test_api.py`:
```python
import os


def test_get_library_no_api_key_needed(client):
    resp = client.get("/library?series_id=a")
    assert resp.status_code == 200
    assert resp.get_json() == []


def test_get_library_returns_done_items(client, conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="showX", show_title="X", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
    )
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    dbmod.update_item_status(conn, item_id, "done", file_path="showX/ep1.mp4", size_bytes=999)
    resp = client.get("/library?series_id=showX")
    body = resp.get_json()
    assert len(body) == 1
    assert body[0]["episode"] == 1


def test_stream_serves_file_with_range_support(client, conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="showY", show_title="Y", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
    )
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    video_path = tmp_path / "ep1.mp4"
    video_path.write_bytes(b"0123456789")
    dbmod.update_item_status(conn, item_id, "done", file_path=str(video_path), size_bytes=10)

    resp = client.get(f"/stream/{item_id}")
    assert resp.status_code == 200
    assert resp.data == b"0123456789"

    resp_range = client.get(f"/stream/{item_id}", headers={"Range": "bytes=2-5"})
    assert resp_range.status_code == 206
    assert resp_range.data == b"2345"
    assert resp_range.headers["Content-Range"] == "bytes 2-5/10"


def test_stream_not_found(client):
    resp = client.get("/stream/9999")
    assert resp.status_code == 404


def test_delete_library_item_requires_api_key(client, conn):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
    )
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    resp = client.delete(f"/library/{item_id}")
    assert resp.status_code == 401


def test_delete_library_item_removes_it(client, conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="archive", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://x/1.mp4"}],
    )
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    video_path = tmp_path / "ep1.mp4"
    video_path.write_bytes(b"data")
    dbmod.update_item_status(conn, item_id, "done", file_path=str(video_path), size_bytes=4)
    resp = client.delete(f"/library/{item_id}", headers={"X-Api-Key": "secret123"})
    assert resp.status_code == 204
    assert not os.path.exists(video_path)
    assert dbmod.get_item(conn, item_id) is None
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL — las rutas nuevas no existen.

- [ ] **Step 4: Implement the new routes in `offline/api.py`**

Add these imports at the top: `import os` and `from flask import send_file, Response`.

Add these routes inside `create_app`, before `return app`:

```python
    @app.get("/library")
    def get_library():
        series_id = request.args.get("series_id", "")
        items = dbmod.list_library(conn, series_id)
        return jsonify(items), 200

    @app.get("/stream/<int:item_id>")
    def stream(item_id):
        item = dbmod.get_item(conn, item_id)
        if item is None or item["status"] != "done" or not item["file_path"]:
            return jsonify({"error": "not found"}), 404
        path = item["file_path"]
        if not os.path.exists(path):
            return jsonify({"error": "not found"}), 404

        range_header = request.headers.get("Range")
        file_size = os.path.getsize(path)
        if not range_header:
            return send_file(path)

        start_s, end_s = range_header.replace("bytes=", "").split("-")
        start = int(start_s)
        end = int(end_s) if end_s else file_size - 1
        length = end - start + 1
        with open(path, "rb") as f:
            f.seek(start)
            data = f.read(length)
        resp = Response(data, 206, mimetype="video/mp4", direct_passthrough=True)
        resp.headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
        resp.headers["Accept-Ranges"] = "bytes"
        resp.headers["Content-Length"] = str(length)
        return resp

    @app.delete("/library/<int:item_id>")
    def delete_library_item(item_id):
        auth = require_api_key()
        if auth:
            return auth
        item = dbmod.get_item(conn, item_id)
        if item is None:
            return jsonify({"error": "not found"}), 404
        if item["file_path"] and os.path.exists(item["file_path"]):
            os.remove(item["file_path"])
        dbmod.delete_item(conn, item_id)
        return "", 204
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_api.py -v`
Expected: PASS (13 tests total in this file).

- [ ] **Step 6: Run the full suite**

Run: `python -m pytest -v`
Expected: PASS (every test from Tasks 1-8).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: API Flask - GET /library, GET /stream con range requests, DELETE /library"
```

---

### Task 9: Docker packaging + local smoke test

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `gunicorn.conf.py`, `offline/app.py`

**Interfaces:**
- Consumes: everything from Tasks 1-8.
- Produces: a runnable Docker image; no new Python interfaces (this task has no unit tests — its
  deliverable is verified by a manual smoke test, per the spec's own testing section: "Integración
  manual en blog"). This task builds and smoke-tests LOCALLY (Docker Desktop on the Mac) — it does
  **not** touch `blog`.

- [ ] **Step 1: `offline/app.py` — WSGI entrypoint + background worker thread**

```python
from __future__ import annotations

import os
import threading
import time

from offline.config import Config
from offline.db import connect, migrate
from offline.aria2client import Aria2Client
from offline.api import create_app
from offline import worker as workermod


def _worker_loop(cfg: Config, conn, aria2: Aria2Client, stop_event: threading.Event) -> None:
    workermod.reconcile_on_startup(conn, aria2)
    while not stop_event.is_set():
        from offline import db as dbmod
        row = conn.execute("SELECT id FROM jobs WHERE status = 'queued' LIMIT 1").fetchone()
        if row:
            job = dbmod.get_job(conn, row["id"])
            workermod.dispatch_job(conn, aria2, job)
        for row in conn.execute("SELECT id FROM jobs WHERE status = 'downloading'").fetchall():
            job = dbmod.get_job(conn, row["id"])
            try:
                workermod.poll_job(conn, aria2, job)
            except Exception:
                pass  # el proximo ciclo reintenta; no tumbar el worker por un job puntual
        time.sleep(5)


def create_wsgi_app():
    cfg = Config.from_env(os.environ)
    conn = connect(cfg.db_path)
    migrate(conn)
    aria2 = Aria2Client(cfg.aria2_rpc_url)
    stop_event = threading.Event()
    t = threading.Thread(target=_worker_loop, args=(cfg, conn, aria2, stop_event), daemon=True)
    t.start()
    return create_app(cfg, conn)


app = create_wsgi_app()
```

- [ ] **Step 2: `gunicorn.conf.py`**

```python
bind = "0.0.0.0:8099"
workers = 1  # una sola instancia: SQLite + el worker-thread en memoria no toleran multi-worker
threads = 4
timeout = 120
```

(`workers = 1` es obligatorio, no una opción de tuning: el worker-thread que drena la cola vive DENTRO
del proceso de gunicorn — con 2+ workers habría 2 loops compitiendo por la misma cola secuencial.)

- [ ] **Step 3: `Dockerfile`**

```dockerfile
FROM python:3.12-slim
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends aria2 \
 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY offline/ offline/
COPY gunicorn.conf.py .
EXPOSE 8099
CMD sh -c "aria2c --enable-rpc --rpc-listen-all=false --rpc-listen-port=6800 --daemon=true --dir=${DOWNLOAD_DIR:-/data/downloads} && gunicorn -c gunicorn.conf.py offline.app:app"
```

- [ ] **Step 4: `docker-compose.yml` (dev)**

```yaml
services:
  app:
    build: .
    ports:
      - "8099:8099"
    environment:
      API_KEY: dev-secret
      DOWNLOAD_DIR: /data/downloads
      MAX_STORAGE_GB: "5"
      ARIA2_RPC_URL: http://localhost:6800/jsonrpc
      DB_PATH: /data/offline.db
    volumes:
      - offline-data:/data

volumes:
  offline-data:
```

- [ ] **Step 5: Build and smoke-test locally**

```bash
cd /Users/cristian/arkiv-offline
docker compose build
docker compose up -d
sleep 3
curl -s -X POST http://localhost:8099/jobs -H "X-Api-Key: dev-secret" -H "Content-Type: application/json" \
  -d '{"kind":"archive","series_id":"smoke","show_title":"Smoke Test","poster_url":"","items":[{"season":1,"episode":1,"episode_name":"E1","source_ref":"https://ia801504.us.archive.org/generated_files/dummy.mp4"}]}'
```

Expected: `201` with a `job_id`. Then:

```bash
curl -s http://localhost:8099/jobs/1 -H "X-Api-Key: dev-secret"
```

Expected: JSON with `"status"` transitioning from `queued`/`downloading` toward `done` or `failed`
over the next ~5-10s (poll a couple of times). If the smoke-test URL 404s (dummy placeholder — the
executing agent should substitute a real small public file URL, e.g. a known small archive.org item's
direct file URL), that's fine — the point is confirming the container boots, aria2 answers RPC calls,
and the job reaches `failed` cleanly rather than hanging forever. Report the actual observed behavior
in the task report; do not silently swap in a URL that "makes it pass" without saying so.

```bash
docker compose down
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: empaquetado Docker (Dockerfile, compose dev, entrypoint WSGI+worker)"
```

---

## Self-Review (hecho al escribir este plan)

- **Cobertura del spec:** arquitectura (servicio separado, SQLite, aria2, cola secuencial) → Tasks 1-6;
  contrato API completo (`/jobs` POST/GET/DELETE, `/library`, `/stream` con range, `/library` DELETE)
  → Tasks 7-8; límite de disco → Task 3 + enforced en Task 7's `POST /jobs`; reconciliación al
  reiniciar → Task 6; empaquetado Docker → Task 9. `web`-kind stub (falla honesto) → Task 6. Deploy a
  `blog` explícitamente diferido fuera de este plan (Global Constraints) — se hace después, a mano, con
  confirmación humana, mismo patrón ya usado en esta sesión.
- **Placeholders:** ninguno — cada step tiene código completo. La única nota "amarilla" (RPC secret de
  aria2 en Task 5) está marcada explícitamente como una incógnita a resolver EN Task 9 si aparece, no
  dejada como TODO silencioso — el step 5 de Task 9 la ejercita end-to-end y la reportaría si aria2
  rechaza la llamada sin secret.
- **Consistencia de tipos:** `Config` (Task 1) se usa igual en Tasks 7/8/9; `conn` (sqlite3.Connection,
  Task 2) es el mismo objeto inyectado en `worker.py` (Task 6) y `api.py` (Task 7); `Aria2Client`
  (Task 5) tiene la misma interfaz (`add_magnet`/`add_uri`/`tell_status`) que `worker.py` consume y que
  el `FakeAria2` de los tests replica; `jobstate.derive_job_status_from_items` (Task 4) es la MISMA
  función que `worker.poll_job` llama para jobs no-torrent (Task 6) — no hay una segunda
  implementación de esa lógica en ningún lado.
