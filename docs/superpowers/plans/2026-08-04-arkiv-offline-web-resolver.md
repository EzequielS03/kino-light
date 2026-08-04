# arkiv-offline: descargas WEB (ffmpeg) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar el soporte de jobs `kind='web'` en `arkiv-offline` (hoy stub que falla todo de
inmediato): resolver cada `page_url` contra el servicio `web-resolver` YA desplegado en `blog`
(`127.0.0.1:8123`, sin tocarlo) y bajar el resultado a disco con `ffmpeg`, uno a la vez, reusando
toda la maquinaria de jobs/progreso/stall-timeout/cancelación ya construida en el sub-proyecto #1.

**Architecture:** Dos módulos nuevos, chicos y con interfaz inyectable (mismo patrón que
`Aria2Client`): `WebResolverClient` (HTTP GET a `/resolve`) y `ffmpeg_download` (subproceso
`ffmpeg -c copy`, sin RPC). El worker gana un tercer `kind` en `dispatch_job`/`poll_job`, simétrico
al de `archive` (items independientes, uno puede fallar sin tumbar los demás) pero con su propia
secuenciación: como no hay una cola externa tipo `aria2` sirviendo de límite, el propio worker
lanza el `ffmpeg` de UN item pendiente por vez y espera a que termine antes de lanzar el siguiente.

**Tech Stack:** Python 3.12, `subprocess` (stdlib, sin librería nueva), `requests` (ya en
`requirements.txt`, reusado para el cliente HTTP del resolver), `ffmpeg` (paquete del sistema,
nuevo en el Dockerfile).

## Global Constraints

- `web-resolver.service` (Node.js/Playwright, `~/web-resolver/server.js` en `blog`) **no se toca**
  — se consume tal cual vía `GET http://127.0.0.1:8123/resolve?url=<page_url>`.
- La descarga SIEMPRE usa `proxyUrl` de la respuesta del resolver (nunca `streamUrl` directo) —
  el proxy ya inyecta `Referer` del lado del servidor, así `arkiv-offline` no maneja headers.
- `ffmpeg` cubre HLS y archivo directo con el MISMO comando (`-c copy`, remux sin recodificar) — sin
  ramas por tipo de stream.
- **Nunca más de un `ffmpeg` corriendo a la vez**, sin excepciones — mismo espíritu que la regla de
  "1 descarga a la vez" del sub-proyecto #1, acá implementada a mano porque no hay `aria2` de por
  medio sirviendo de cola externa.
- Los jobs `web` son **por-item independientes** (como `archive`), NO atómicos (como `torrent`): un
  item puede fallar sin tumbar los demás.
- Un `ffmpeg` lanzado como subproceso **muere con el proceso padre** — a diferencia de `aria2`
  (demonio externo), no hay nada que reconciliar por gid al reiniciar: un job `web` en
  `downloading` al arrancar el servicio se marca `failed` directo, sin ambigüedad.
- Reusar el `progress_tracker`/chequeo de estancado YA construido (sub-proyecto #1) — la señal de
  progreso para `web` es el tamaño en disco del archivo que `ffmpeg` está escribiendo, sondeado
  cada ciclo, mismo mecanismo que ya usa `archive` con `completedLength`.
- No exponer `arkiv-offline` por el túnel de Cloudflare en este plan (decisión explícita, queda
  para sub-proyecto #3).
- Commit identity: `user.name=lordmacu`, `user.email=10134930+lordmacu@users.noreply.github.com`,
  sin trailer de coautoría de IA. Repo `/Users/cristian/arkiv-offline`, ya configurado.

---

## File Structure

- `offline/web_resolver_client.py` (nuevo) — `WebResolverClient.resolve(page_url) -> dict`.
- `offline/ffmpeg_download.py` (nuevo) — `start_download(...)`, `FfmpegDownload` (poll/output_size/
  stderr_tail/kill).
- `offline/config.py` (modificar) — agrega `web_resolver_url`.
- `offline/worker.py` (modificar) — rama `web` en `dispatch_job`/`poll_job`, reconciliación al
  reiniciar.
- `offline/app.py` (modificar) — hilo el `WebResolverClient` y el dict `web_downloads` (estado en
  memoria de los `ffmpeg` en curso, igual patrón que `progress_tracker`) a través de
  `_worker_cycle`/`_worker_loop`.
- `offline/api.py` (modificar) — `DELETE /jobs/<id>` mata el `ffmpeg` en curso si el job es `web`.
- `Dockerfile` (modificar) — agrega `ffmpeg` al `apt-get install`.

---

### Task 1: Config — `web_resolver_url`

**Files:**
- Modify: `offline/config.py`
- Modify: `tests/test_config.py`

**Interfaces:**
- Produces: `Config.web_resolver_url: str` (default `"http://127.0.0.1:8123"`).

- [ ] **Step 1: Escribir el test que falla**

Agregar a `tests/test_config.py`, dentro de `test_from_env_reads_all_fields` (agregar la clave al
`env` dict y el assert) y `test_from_env_defaults` (agregar el assert del default):

```python
# en test_from_env_reads_all_fields, agregar al dict env:
        "WEB_RESOLVER_URL": "http://127.0.0.1:9999",
# y el assert:
    assert cfg.web_resolver_url == "http://127.0.0.1:9999"

# en test_from_env_defaults, agregar:
    assert cfg.web_resolver_url == "http://127.0.0.1:8123"
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd /Users/cristian/arkiv-offline && .venv/bin/python -m pytest tests/test_config.py -v`
Expected: FAIL — `Config` no tiene `web_resolver_url`.

- [ ] **Step 3: Implementar**

En `offline/config.py`, agregar el campo al dataclass (después de `aria2_rpc_url`) y a
`from_env` (mismo patrón que los demás `env.get`):

```python
    web_resolver_url: str = "http://127.0.0.1:8123"
```
```python
            web_resolver_url=env.get("WEB_RESOLVER_URL", cls.web_resolver_url),
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `.venv/bin/python -m pytest tests/test_config.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Config.web_resolver_url"
```

---

### Task 2: `WebResolverClient`

**Files:**
- Create: `offline/web_resolver_client.py`
- Create: `tests/test_web_resolver_client.py`

**Interfaces:**
- Produces: `WebResolverClient(base_url: str)` con `.resolve(page_url: str) -> dict` — devuelve el
  JSON crudo de `/resolve` (`{"ok": True, "proxyUrl": ..., ...}` o `{"ok": False, "error": ...}`),
  sin transformarlo (el worker decide qué hacer con cada forma).

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_web_resolver_client.py`:
```python
from unittest.mock import patch, MagicMock
from offline.web_resolver_client import WebResolverClient


@patch("offline.web_resolver_client.requests.get")
def test_resolve_ok_returns_parsed_json(mock_get):
    mock_get.return_value = MagicMock(
        status_code=200,
        json=lambda: {"ok": True, "streamUrl": "https://x/master.m3u8",
                       "proxyUrl": "https://jackett.comparadorinternet.co/proxy?url=x&referer=y",
                       "headers": {}, "subtitles": []},
    )
    client = WebResolverClient("http://127.0.0.1:8123")
    result = client.resolve("https://pelisplushd.bz/anime/death-note/temporada/1/capitulo/1")
    assert result["ok"] is True
    assert result["proxyUrl"].startswith("https://jackett.comparadorinternet.co/proxy")
    call_args = mock_get.call_args
    assert call_args.args[0] == "http://127.0.0.1:8123/resolve"
    assert call_args.kwargs["params"] == {"url": "https://pelisplushd.bz/anime/death-note/temporada/1/capitulo/1"}
    assert call_args.kwargs["timeout"] == 90


@patch("offline.web_resolver_client.requests.get")
def test_resolve_failure_returns_ok_false(mock_get):
    mock_get.return_value = MagicMock(
        status_code=200, json=lambda: {"ok": False, "error": "no se detecto stream"},
    )
    client = WebResolverClient("http://127.0.0.1:8123")
    result = client.resolve("https://x/dead-page")
    assert result["ok"] is False
    assert result["error"] == "no se detecto stream"
```

- [ ] **Step 2: Correr y verificar que fallan**

Run: `.venv/bin/python -m pytest tests/test_web_resolver_client.py -v`
Expected: FAIL — `offline.web_resolver_client` no existe.

- [ ] **Step 3: Implementar**

`offline/web_resolver_client.py`:
```python
from __future__ import annotations

import requests


class WebResolverClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def resolve(self, page_url: str) -> dict:
        resp = requests.get(f"{self.base_url}/resolve", params={"url": page_url}, timeout=90)
        resp.raise_for_status()
        return resp.json()
```

- [ ] **Step 4: Correr y verificar que pasan**

Run: `.venv/bin/python -m pytest tests/test_web_resolver_client.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: WebResolverClient (GET /resolve del web-resolver ya desplegado)"
```

---

### Task 3: `ffmpeg_download` — subproceso inyectable

**Files:**
- Create: `offline/ffmpeg_download.py`
- Create: `tests/test_ffmpeg_download.py`

**Interfaces:**
- Produces: `build_ffmpeg_command(input_url: str, output_path: str) -> list[str]`,
  `start_download(input_url: str, output_path: str, popen=subprocess.Popen) -> FfmpegDownload`,
  `FfmpegDownload` con `.poll() -> int | None` (None = sigue corriendo), `.output_size() -> int`,
  `.stderr_tail(n=20) -> str`, `.kill() -> None`.

- [ ] **Step 1: Escribir los tests que fallan**

`tests/test_ffmpeg_download.py`:
```python
import os
import tempfile
from offline.ffmpeg_download import build_ffmpeg_command, start_download


def test_build_ffmpeg_command_uses_copy_codec():
    cmd = build_ffmpeg_command("https://x/master.m3u8", "/data/downloads/a/ep1.mkv")
    assert cmd == ["ffmpeg", "-y", "-i", "https://x/master.m3u8", "-c", "copy", "/data/downloads/a/ep1.mkv"]


class FakeProcess:
    def __init__(self, returncode=None):
        self.returncode = returncode
        self._killed = False
        self.stderr = FakeStderr()

    def poll(self):
        return self.returncode

    def kill(self):
        self._killed = True


class FakeStderr:
    def read(self):
        return "frame=100 ffmpeg diagnostic output\nstream not found\n"


def test_start_download_creates_output_dir_and_calls_popen():
    calls = {}

    def fake_popen(cmd, **kwargs):
        calls["cmd"] = cmd
        calls["kwargs"] = kwargs
        return FakeProcess()

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "sub", "ep1.mkv")
        dl = start_download("https://x/master.m3u8", out, popen=fake_popen)
        assert os.path.isdir(os.path.dirname(out))
        assert calls["cmd"][0] == "ffmpeg"
        assert dl.output_path == out
        assert dl.poll() is None


def test_output_size_zero_when_file_missing():
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "ep1.mkv")
        dl = start_download("https://x/m.m3u8", out, popen=lambda cmd, **kw: FakeProcess())
        assert dl.output_size() == 0


def test_output_size_reflects_bytes_written():
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "ep1.mkv")
        with open(out, "wb") as f:
            f.write(b"x" * 5000)
        dl = start_download("https://x/m.m3u8", out, popen=lambda cmd, **kw: FakeProcess())
        assert dl.output_size() == 5000


def test_poll_returns_exit_code_when_finished():
    def fake_popen(cmd, **kw):
        return FakeProcess(returncode=1)
    with tempfile.TemporaryDirectory() as d:
        dl = start_download("https://x/m.m3u8", os.path.join(d, "ep1.mkv"), popen=fake_popen)
        assert dl.poll() == 1


def test_stderr_tail_reads_process_stderr():
    def fake_popen(cmd, **kw):
        return FakeProcess(returncode=1)
    with tempfile.TemporaryDirectory() as d:
        dl = start_download("https://x/m.m3u8", os.path.join(d, "ep1.mkv"), popen=fake_popen)
        assert "stream not found" in dl.stderr_tail()


def test_kill_calls_process_kill():
    proc = FakeProcess()

    def fake_popen(cmd, **kw):
        return proc
    with tempfile.TemporaryDirectory() as d:
        dl = start_download("https://x/m.m3u8", os.path.join(d, "ep1.mkv"), popen=fake_popen)
        dl.kill()
        assert proc._killed is True
```

- [ ] **Step 2: Correr y verificar que fallan**

Run: `.venv/bin/python -m pytest tests/test_ffmpeg_download.py -v`
Expected: FAIL — `offline.ffmpeg_download` no existe.

- [ ] **Step 3: Implementar**

`offline/ffmpeg_download.py`:
```python
from __future__ import annotations

import os
import subprocess


def build_ffmpeg_command(input_url: str, output_path: str) -> list[str]:
    return ["ffmpeg", "-y", "-i", input_url, "-c", "copy", output_path]


class FfmpegDownload:
    def __init__(self, process, output_path: str):
        self.process = process
        self.output_path = output_path

    def poll(self) -> int | None:
        return self.process.poll()

    def output_size(self) -> int:
        try:
            return os.path.getsize(self.output_path)
        except OSError:
            return 0

    def stderr_tail(self, n: int = 20) -> str:
        try:
            data = self.process.stderr.read() or ""
        except Exception:
            return ""
        lines = data.splitlines()
        return "\n".join(lines[-n:])

    def kill(self) -> None:
        self.process.kill()


def start_download(input_url: str, output_path: str, popen=subprocess.Popen) -> FfmpegDownload:
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    process = popen(
        build_ffmpeg_command(input_url, output_path),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    return FfmpegDownload(process, output_path)
```

- [ ] **Step 4: Correr y verificar que pasan**

Run: `.venv/bin/python -m pytest tests/test_ffmpeg_download.py -v`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ffmpeg_download (subproceso inyectable, sin RPC)"
```

---

### Task 4: Worker — dispatch y poll de jobs `web`

**Files:**
- Modify: `offline/worker.py`
- Modify: `tests/test_worker.py`

**Interfaces:**
- Consumes: `WebResolverClient.resolve` (Task 2), `start_download`/`FfmpegDownload` (Task 3).
- Produces: `dispatch_job` y `poll_job` ganan soporte real para `job["kind"] == "web"` (hoy lo
  stubean como fallo inmediato — ESE stub se reemplaza acá). Ambas funciones ganan parámetros
  nuevos `web_resolver=None` y `web_downloads: dict | None = None` (el segundo, mismo rol que
  `progress_tracker`: estado en memoria compartido entre llamadas, dueño real en `app.py`).

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `tests/test_worker.py` (junto al resto de fakes ya presentes en el archivo):

```python
class FakeWebResolver:
    def __init__(self):
        self.responses = {}   # page_url -> dict de respuesta

    def resolve(self, page_url):
        return self.responses.get(page_url, {"ok": False, "error": "no configurado en el fake"})


class FakeFfmpegProcess:
    def __init__(self, returncode=None):
        self.returncode = returncode
        self.killed = False
        self.stderr = type("S", (), {"read": lambda self_: "diagnostico ffmpeg"})()

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True


def _fake_popen_factory(processes_by_output):
    """processes_by_output: dict output_path -> FakeFfmpegProcess a devolver."""
    def popen(cmd, **kw):
        output_path = cmd[-1]
        return processes_by_output[output_path]
    return popen


def test_dispatch_web_job_starts_ffmpeg_for_first_pending_item_only(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "https://site/ep2"},
        ],
    )
    resolver = FakeWebResolver()
    resolver.responses["https://site/ep1"] = {"ok": True, "proxyUrl": "https://proxy/x1"}
    web_downloads = {}
    proc1 = FakeFfmpegProcess()
    popen = _fake_popen_factory({str(tmp_path / "a" / "ep-1.mkv"): proc1})
    worker.dispatch_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=resolver, web_downloads=web_downloads,
        download_dir=str(tmp_path), popen=popen,
    )
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "downloading"
    items = {it["episode"]: it for it in job["items"]}
    assert items[1]["status"] == "pending"   # sigue pending hasta que ffmpeg termine
    assert items[2]["status"] == "pending"   # NO se disparo (solo 1 a la vez)
    assert items[1]["id"] in web_downloads
    assert items[2]["id"] not in web_downloads


def test_dispatch_web_job_item_resolve_failure_marks_item_failed_and_advances(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/dead"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "https://site/ep2"},
        ],
    )
    resolver = FakeWebResolver()
    resolver.responses["https://site/dead"] = {"ok": False, "error": "no se detecto stream"}
    resolver.responses["https://site/ep2"] = {"ok": True, "proxyUrl": "https://proxy/x2"}
    web_downloads = {}
    proc2 = FakeFfmpegProcess()
    popen = _fake_popen_factory({str(tmp_path / "a" / "ep-2.mkv"): proc2})
    # dispatch_job debe intentar el item 1 (falla resolve), marcarlo failed, y seguir con el item 2
    # DENTRO del mismo dispatch (un resolve fallido no cuenta como "cupo usado").
    worker.dispatch_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=resolver, web_downloads=web_downloads,
        download_dir=str(tmp_path), popen=popen,
    )
    job = dbmod.get_job(conn, job_id)
    items = {it["episode"]: it for it in job["items"]}
    assert items[1]["status"] == "failed"
    assert items[1]["error"] == "no se detecto stream"
    assert items[2]["id"] in web_downloads   # sí se disparo el siguiente


def test_poll_web_job_still_running_stays_downloading(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    proc = FakeFfmpegProcess(returncode=None)
    output_path = str(tmp_path / "ep1.mkv")
    with open(output_path, "wb") as f:
        f.write(b"x" * 1000)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {item_id: FfmpegDownload(proc, output_path)}
    worker.poll_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=FakeWebResolver(), web_downloads=web_downloads,
        progress_tracker={}, stall_timeout_s=1800,
    )
    assert dbmod.get_job(conn, job_id)["status"] == "downloading"
    assert item_id in web_downloads   # sigue activo


def test_poll_web_job_ffmpeg_success_marks_item_done_with_real_path_and_size(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    output_path = str(tmp_path / "ep1.mkv")
    with open(output_path, "wb") as f:
        f.write(b"x" * 734003200)
    proc = FakeFfmpegProcess(returncode=0)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {item_id: FfmpegDownload(proc, output_path)}
    worker.poll_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=FakeWebResolver(), web_downloads=web_downloads,
        progress_tracker={}, stall_timeout_s=1800,
    )
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "done"
    assert job["items"][0]["status"] == "done"
    assert job["items"][0]["file_path"] == output_path
    assert job["items"][0]["size_bytes"] == 734003200
    assert item_id not in web_downloads   # se libero el cupo


def test_poll_web_job_ffmpeg_failure_marks_item_failed_and_deletes_partial_file(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    output_path = str(tmp_path / "ep1.mkv")
    with open(output_path, "wb") as f:
        f.write(b"partial garbage")
    proc = FakeFfmpegProcess(returncode=1)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {item_id: FfmpegDownload(proc, output_path)}
    worker.poll_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=FakeWebResolver(), web_downloads=web_downloads,
        progress_tracker={}, stall_timeout_s=1800,
    )
    job = dbmod.get_job(conn, job_id)
    assert job["items"][0]["status"] == "failed"
    assert "diagnostico ffmpeg" in job["items"][0]["error"]
    assert not os.path.exists(output_path)


def test_poll_web_job_advances_to_next_item_after_one_finishes(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[
            {"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"},
            {"season": 1, "episode": 2, "episode_name": "E2", "source_ref": "https://site/ep2"},
        ],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    items = dbmod.get_job(conn, job_id)["items"]
    output1 = str(tmp_path / "ep1.mkv")
    with open(output1, "wb") as f:
        f.write(b"x" * 100)
    proc1 = FakeFfmpegProcess(returncode=0)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {items[0]["id"]: FfmpegDownload(proc1, output1)}
    resolver = FakeWebResolver()
    resolver.responses["https://site/ep2"] = {"ok": True, "proxyUrl": "https://proxy/x2"}
    output2 = str(tmp_path / "a" / "ep-2.mkv")
    proc2 = FakeFfmpegProcess()
    popen = _fake_popen_factory({output2: proc2})
    worker.poll_job(
        conn, aria2=None, job=dbmod.get_job(conn, job_id),
        web_resolver=resolver, web_downloads=web_downloads,
        progress_tracker={}, stall_timeout_s=1800,
        download_dir=str(tmp_path), popen=popen,
    )
    job = dbmod.get_job(conn, job_id)
    assert job["items"][0]["status"] == "done"
    assert job["status"] == "downloading"   # falta el item 2
    assert job["items"][1]["id"] in web_downloads   # ya se disparo


def test_reconcile_on_startup_fails_orphaned_web_job_immediately(conn):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    n = worker.reconcile_on_startup(conn, aria2=None)
    assert n == 1
    assert dbmod.get_job(conn, job_id)["status"] == "failed"
```

(Estos tests asumen `import os` ya al tope de `tests/test_worker.py` — si no está, agregarlo.)

- [ ] **Step 2: Correr y verificar que fallan**

Run: `.venv/bin/python -m pytest tests/test_worker.py -v -k web`
Expected: FAIL — `dispatch_job`/`poll_job` no aceptan los parámetros nuevos, y la rama `web` sigue
siendo el stub viejo.

- [ ] **Step 3: Implementar**

En `offline/worker.py`, agregar imports:
```python
import os
from offline.web_resolver_client import WebResolverClient  # solo para type hints/documentacion, no es estrictamente necesario importarlo
from offline.ffmpeg_download import start_download
```

Reemplazar el bloque `if job["kind"] == "web":` de `dispatch_job` (el stub que marca todo failed) y
extender la firma de `dispatch_job` y `poll_job` con los parámetros nuevos:

```python
def dispatch_job(conn, aria2, job, web_resolver=None, web_downloads=None, download_dir="/data/downloads", popen=None):
    if job["kind"] == "web":
        _dispatch_next_web_item(conn, web_resolver, job, web_downloads, download_dir, popen)
        return
    # ... el resto (torrent/archive) queda exactamente igual, sin tocar ...
```

```python
def _output_path_for(download_dir, job, item):
    safe_series = job["series_id"]
    return os.path.join(download_dir, safe_series, f"ep-{item['episode']}.mkv")


def _dispatch_next_web_item(conn, web_resolver, job, web_downloads, download_dir, popen):
    import subprocess
    popen = popen or subprocess.Popen
    for item in job["items"]:
        if item["status"] != "pending" or item["id"] in web_downloads:
            continue
        result = web_resolver.resolve(item["source_ref"])
        if not result.get("ok"):
            dbmod.update_item_status(conn, item["id"], "failed", error=result.get("error", "resolve fallo"))
            continue   # probar el siguiente item pendiente en la MISMA pasada
        output_path = _output_path_for(download_dir, job, item)
        download = start_download(result["proxyUrl"], output_path, popen=popen)
        web_downloads[item["id"]] = download
        dbmod.update_job_status(conn, job["id"], "downloading")
        return   # 1 a la vez: no seguir con más items en esta pasada
    # Si llegamos aca sin haber lanzado nada, o ya no quedan items pending, o todos fallaron el resolve.
    refreshed = dbmod.get_job(conn, job["id"])
    derived = jobstate.derive_job_status_from_items(refreshed["items"])
    if derived != "downloading":
        dbmod.update_job_status(conn, job["id"], derived)
```

En `poll_job`, agregar la rama `web` (mismo nivel que las ramas `torrent`/`archive` existentes,
antes o después según cómo esté organizado el `if job["kind"] == ...` actual):

```python
def poll_job(conn, aria2, job, stall_timeout_s=None, progress_tracker=None, now=None,
             web_resolver=None, web_downloads=None, download_dir="/data/downloads", popen=None):
    if job["status"] != "downloading":
        return

    if job["kind"] == "web":
        _poll_web_job(conn, web_resolver, job, web_downloads, download_dir, popen)
        return

    # ... torrent/archive existentes, sin tocar ...
```

```python
def _poll_web_job(conn, web_resolver, job, web_downloads, download_dir, popen):
    active_item = next((it for it in job["items"] if it["id"] in web_downloads), None)
    if active_item is None:
        _dispatch_next_web_item(conn, web_resolver, job, web_downloads, download_dir, popen)
        return
    download = web_downloads[active_item["id"]]
    exit_code = download.poll()
    if exit_code is None:
        return   # sigue corriendo; el progreso/estancado se cablea en Task 5
    del web_downloads[active_item["id"]]
    if exit_code == 0:
        size = download.output_size()
        dbmod.update_item_status(conn, active_item["id"], "done", file_path=download.output_path, size_bytes=size)
    else:
        error = download.stderr_tail()
        try:
            if os.path.exists(download.output_path):
                os.remove(download.output_path)
        except OSError:
            pass
        dbmod.update_item_status(conn, active_item["id"], "failed", error=error)
    refreshed = dbmod.get_job(conn, job["id"])
    derived = jobstate.derive_job_status_from_items(refreshed["items"])
    if derived != "downloading":
        dbmod.update_job_status(conn, job["id"], derived)
```

En `reconcile_on_startup`, agregar el caso `web` (los jobs `web` en `downloading` SIEMPRE se
reconcilian a `failed` — su `ffmpeg` no sobrevive un reinicio del proceso, no hay nada que
verificar por gid como con `aria2`):

```python
def reconcile_on_startup(conn, aria2) -> int:
    n = 0
    for job in dbmod.jobs_stuck_downloading(conn):
        if job["kind"] == "web":
            dbmod.update_job_status(conn, job["id"], "failed", error="huerfano tras reinicio del servicio")
            n += 1
            continue
        # ... rama torrent/archive existente, sin tocar ...
    return n
```

- [ ] **Step 4: Correr y verificar que pasan**

Run: `.venv/bin/python -m pytest tests/test_worker.py -v`
Expected: PASS (todos, incluidos los nuevos de `web`).

- [ ] **Step 5: Correr la suite completa**

Run: `.venv/bin/python -m pytest -v`
Expected: PASS, sin regresiones en `torrent`/`archive`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: worker soporta jobs web (dispatch secuencial + poll + reconciliacion)"
```

---

### Task 5: Progreso y estancado para jobs `web`

**Files:**
- Modify: `offline/worker.py`
- Modify: `tests/test_worker.py`

**Interfaces:**
- Consumes: `progress_tracker` (ya existe, sub-proyecto #1), `download.output_size()` (Task 3).
- Produces: `_poll_web_job` ahora también alimenta `progress_tracker` mientras el `ffmpeg` activo
  sigue corriendo, y cancela (mata el proceso + borra el archivo parcial) si se detecta estancado
  — mismo criterio que ya usa `torrent`/`archive`.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `tests/test_worker.py`:

```python
def test_poll_web_job_progressing_bytes_not_marked_stalled(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    output_path = str(tmp_path / "ep1.mkv")
    proc = FakeFfmpegProcess(returncode=None)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {item_id: FfmpegDownload(proc, output_path)}
    tracker = {}
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)

    with open(output_path, "wb") as f:
        f.write(b"x" * 100)
    worker.poll_job(conn, aria2=None, job=dbmod.get_job(conn, job_id), web_resolver=FakeWebResolver(),
                     web_downloads=web_downloads, progress_tracker=tracker, stall_timeout_s=60, now=t0)
    assert dbmod.get_job(conn, job_id)["status"] == "downloading"

    with open(output_path, "wb") as f:
        f.write(b"x" * 500)   # crecio -> no esta estancado
    worker.poll_job(conn, aria2=None, job=dbmod.get_job(conn, job_id), web_resolver=FakeWebResolver(),
                     web_downloads=web_downloads, progress_tracker=tracker, stall_timeout_s=60,
                     now=t0 + timedelta(seconds=61))
    assert dbmod.get_job(conn, job_id)["status"] == "downloading"


def test_poll_web_job_frozen_bytes_past_timeout_marks_failed_and_kills_process(conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    output_path = str(tmp_path / "ep1.mkv")
    with open(output_path, "wb") as f:
        f.write(b"x" * 100)
    proc = FakeFfmpegProcess(returncode=None)
    from offline.ffmpeg_download import FfmpegDownload
    web_downloads = {item_id: FfmpegDownload(proc, output_path)}
    tracker = {}
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)

    worker.poll_job(conn, aria2=None, job=dbmod.get_job(conn, job_id), web_resolver=FakeWebResolver(),
                     web_downloads=web_downloads, progress_tracker=tracker, stall_timeout_s=60, now=t0)
    # mismos 100 bytes, 61s despues -> estancado
    worker.poll_job(conn, aria2=None, job=dbmod.get_job(conn, job_id), web_resolver=FakeWebResolver(),
                     web_downloads=web_downloads, progress_tracker=tracker, stall_timeout_s=60,
                     now=t0 + timedelta(seconds=61))
    job = dbmod.get_job(conn, job_id)
    assert job["status"] == "failed"
    assert proc.killed is True
    assert not os.path.exists(output_path)
    assert item_id not in web_downloads
```

(Requiere `from datetime import datetime, timezone, timedelta` al tope de `tests/test_worker.py`
si no está ya — el resto de tests de estancado de torrent/archive ya deberían haberlo agregado en
el sub-proyecto #1; si no, agregarlo.)

- [ ] **Step 2: Correr y verificar que fallan**

Run: `.venv/bin/python -m pytest tests/test_worker.py -v -k "web and (progress or stall or frozen)"`
Expected: FAIL — `_poll_web_job` todavía no llama al chequeo de estancado ni recibe `now`.

- [ ] **Step 3: Implementar**

Extender `_poll_web_job` para aceptar `progress_tracker`/`stall_timeout_s`/`now` y usar la MISMA
función de trackeo de progreso que ya usan `torrent`/`archive` (revisar el nombre exacto ya
existente en `worker.py` del sub-proyecto #1 — algo como `_track_progress(progress_tracker,
job["id"], value, now, stall_timeout_s)` que devuelve `True` si está estancado; reusarla tal cual,
sin reimplementarla):

```python
def _poll_web_job(conn, web_resolver, job, web_downloads, download_dir, popen,
                   progress_tracker=None, stall_timeout_s=None, now=None):
    active_item = next((it for it in job["items"] if it["id"] in web_downloads), None)
    if active_item is None:
        _dispatch_next_web_item(conn, web_resolver, job, web_downloads, download_dir, popen)
        return
    download = web_downloads[active_item["id"]]
    exit_code = download.poll()
    if exit_code is None:
        if progress_tracker is not None and stall_timeout_s:
            stalled = _track_progress(progress_tracker, job["id"], download.output_size(), now, stall_timeout_s)
            if stalled:
                download.kill()
                try:
                    if os.path.exists(download.output_path):
                        os.remove(download.output_path)
                except OSError:
                    pass
                del web_downloads[active_item["id"]]
                _forget_progress(progress_tracker, job["id"])
                dbmod.update_item_status(conn, active_item["id"], "failed", error="estancado: sin progreso en mas de %ss" % stall_timeout_s)
                refreshed = dbmod.get_job(conn, job["id"])
                derived = jobstate.derive_job_status_from_items(refreshed["items"])
                dbmod.update_job_status(conn, job["id"], derived)
        return
    _forget_progress(progress_tracker, job["id"]) if progress_tracker is not None else None
    del web_downloads[active_item["id"]]
    # ... resto igual que Task 4 (done/failed + derive_job_status_from_items) ...
```

Actualizar la llamada a `_poll_web_job` dentro de `poll_job` para pasar los 3 parámetros nuevos.

Nota: los nombres exactos `_track_progress`/`_forget_progress` deben confirmarse leyendo el
`worker.py` real del sub-proyecto #1 antes de escribir esta implementación — si difieren del
supuesto acá, usar los reales (son los mismos que ya usan las ramas `torrent`/`archive`, no hay que
crear una versión nueva).

- [ ] **Step 4: Correr y verificar que pasan**

Run: `.venv/bin/python -m pytest tests/test_worker.py -v`
Expected: PASS (todos).

- [ ] **Step 5: Correr la suite completa**

Run: `.venv/bin/python -m pytest -v`
Expected: PASS, pristino.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: progreso y estancado para jobs web (reusa progress_tracker existente)"
```

---

### Task 6: Cancelación — `DELETE /jobs/<id>` para jobs `web`

**Files:**
- Modify: `offline/api.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Consumes: `web_downloads` (dict compartido, ya construido en Tasks 4-5) — `create_app` gana un
  parámetro `web_downloads` (mismo patrón ya usado para `aria2`).
- Produces: `DELETE /jobs/<id>` sobre un job `web` en `downloading` mata el `ffmpeg` activo y borra
  su archivo parcial, antes de borrar las filas de la DB — mismo comportamiento ya construido para
  `torrent`/`archive` en el sub-proyecto #1, extendido al tercer `kind`.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `tests/test_api.py` (revisar primero cómo el fixture `client`/`app` actual construye
`create_app` — probablemente ya recibe `aria2`; agregarle `web_downloads={}` al fixture y a este
test específico):

```python
def test_delete_job_web_kills_active_ffmpeg_and_removes_partial_file(client, conn, tmp_path):
    job_id = dbmod.create_job(
        conn, kind="web", series_id="a", show_title="A", poster_url="", magnet=None,
        items=[{"season": 1, "episode": 1, "episode_name": "E1", "source_ref": "https://site/ep1"}],
    )
    dbmod.update_job_status(conn, job_id, "downloading")
    item_id = dbmod.get_job(conn, job_id)["items"][0]["id"]
    output_path = str(tmp_path / "ep1.mkv")
    with open(output_path, "wb") as f:
        f.write(b"partial")
    from offline.ffmpeg_download import FfmpegDownload

    class FakeProc:
        def __init__(self): self.killed = False
        def kill(self): self.killed = True
    proc = FakeProc()
    # el fixture `client` debe compartir el MISMO dict web_downloads que ve la app bajo prueba
    client.application.web_downloads[item_id] = FfmpegDownload(proc, output_path)

    resp = client.delete(f"/jobs/{job_id}", headers={"X-Api-Key": "secret123"})
    assert resp.status_code == 204
    assert proc.killed is True
    assert not os.path.exists(output_path)
```

(Este test asume que `create_app` guarda el dict recibido como `app.web_downloads` para que el test
pueda inyectar el download activo desde afuera — confirmar/ajustar según cómo termine la firma real
de `create_app` en el Step 3.)

- [ ] **Step 2: Correr y verificar que falla**

Run: `.venv/bin/python -m pytest tests/test_api.py -v -k web`
Expected: FAIL — `create_app` no acepta `web_downloads`, o el `DELETE` no mata nada para `web`.

- [ ] **Step 3: Implementar**

En `offline/api.py`, extender `create_app(cfg, conn, aria2, web_downloads=None)` (agregar el
parámetro; guardarlo como `app.web_downloads = web_downloads if web_downloads is not None else
{}` para que quede accesible). En la ruta `DELETE /jobs/<int:job_id>`, extender el bloque que hoy
cancela por `aria2` (torrent/archive) para que, si `job["kind"] == "web"`, en cambio busque en
`web_downloads` el item activo de ese job y llame a `.kill()` + borre su `output_path`:

```python
    @app.delete("/jobs/<int:job_id>")
    def delete_job(job_id):
        auth = require_api_key()
        if auth:
            return auth
        job = dbmod.get_job(conn, job_id)
        if job is None:
            return jsonify({"error": "not found"}), 404
        if job["status"] == "downloading":
            if job["kind"] == "web":
                for item in job["items"]:
                    dl = web_downloads.get(item["id"]) if web_downloads else None
                    if dl is not None:
                        dl.kill()
                        try:
                            if os.path.exists(dl.output_path):
                                os.remove(dl.output_path)
                        except OSError:
                            pass
                        web_downloads.pop(item["id"], None)
            else:
                pass  # logica existente de cancel_download por aria2, sin tocar
        dbmod.delete_job(conn, job_id)
        return "", 204
```

(Integrar esto en el bloque `DELETE` real sin duplicar la lógica ya existente para torrent/archive
— leer el archivo actual antes de editar, esta es la forma conceptual del cambio, no un
reemplazo literal de toda la función.)

- [ ] **Step 4: Correr y verificar que pasa**

Run: `.venv/bin/python -m pytest tests/test_api.py -v`
Expected: PASS (todos, incluido el nuevo).

- [ ] **Step 5: Correr la suite completa**

Run: `.venv/bin/python -m pytest -v`
Expected: PASS, pristino.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: DELETE /jobs/<id> cancela ffmpeg activo para jobs web"
```

---

### Task 7: Wiring en `app.py` + Docker (`ffmpeg`)

**Files:**
- Modify: `offline/app.py`
- Modify: `Dockerfile`

**Interfaces:**
- Consumes: `WebResolverClient` (Task 2), `web_downloads` (Tasks 4-6).
- Produces: el proceso real (`create_wsgi_app`) construye un `WebResolverClient` a partir de
  `cfg.web_resolver_url`, un dict `web_downloads` compartido entre el worker y `create_app`, y los
  hila a través de `_worker_cycle`/`_worker_loop` (mismo patrón ya usado para `progress_tracker`).

- [ ] **Step 1: Implementar (sin test dedicado — wiring de infraestructura, cubierto por el smoke
  test manual del Task 8)**

En `offline/app.py`:
```python
from offline.web_resolver_client import WebResolverClient
```

En `create_wsgi_app()`, construir el cliente y el dict compartido, y pasarlos tanto a `create_app`
como al hilo del worker:
```python
    web_resolver = WebResolverClient(cfg.web_resolver_url)
    web_downloads = {}
    ...
    t = threading.Thread(target=_worker_loop, args=(cfg, conn, aria2, web_resolver, web_downloads, stop_event), daemon=True)
    t.start()
    return create_app(cfg, conn, aria2, web_downloads=web_downloads)
```

Actualizar `_worker_loop`/`_worker_cycle` para recibir `web_resolver`/`web_downloads` y pasarlos a
cada llamada de `dispatch_job`/`poll_job` (junto con `download_dir=cfg.download_dir`).

- [ ] **Step 2: `Dockerfile` — agregar `ffmpeg`**

```dockerfile
RUN apt-get update \
 && apt-get install -y --no-install-recommends aria2 ffmpeg \
 && rm -rf /var/lib/apt/lists/*
```

(Un solo `apt-get install` con ambos paquetes, no dos capas separadas.)

- [ ] **Step 3: Correr la suite completa**

Run: `.venv/bin/python -m pytest -v`
Expected: PASS, pristino (este task no agrega tests propios, solo conecta lo ya probado).

- [ ] **Step 4: Build local (smoke, sin correr aún)**

Run: `cd /Users/cristian/arkiv-offline && docker compose build`
Expected: build exitoso, sin errores de `apt-get`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: wiring de WebResolverClient/web_downloads en app.py + ffmpeg en Dockerfile"
```

---

### Task 8: Verificación manual en `blog` (integración real, sin mocks)

**Files:** ninguno (solo pasos manuales).

- [ ] **Step 1: Deploy a blog**

```bash
rsync -av --exclude='.git' --exclude='.venv' --exclude='.env' --exclude='__pycache__' --exclude='.pytest_cache' \
  /Users/cristian/arkiv-offline/ blog:~/arkiv-offline/
ssh blog "cd ~/arkiv-offline && sudo docker compose -f docker-compose.prod.yml up -d --build"
```

- [ ] **Step 2: Confirmar que el código nuevo llegó**

```bash
ssh blog "sudo docker exec arkiv-offline-app-1 grep -n 'web_resolver' /app/offline/worker.py | head -3"
```

- [ ] **Step 3: Job real contra Death Note/pelisplus** (mismo `page_url` real ya usado en la
  verificación en vivo del sub-proyecto #1)

```bash
API_KEY=$(ssh blog "grep API_KEY ~/arkiv-offline/.env | cut -d= -f2")
ssh blog "curl -s -X POST http://localhost:8099/jobs -H 'X-Api-Key: $API_KEY' -H 'Content-Type: application/json' -d '{\"kind\":\"web\",\"series_id\":\"test-web-death-note\",\"show_title\":\"Death Note (test web)\",\"poster_url\":\"\",\"items\":[{\"season\":1,\"episode\":1,\"episode_name\":\"E1\",\"source_ref\":\"https://pelisplushd.bz/anime/death-note/temporada/1/capitulo/1\"}]}'"
```

- [ ] **Step 4: Seguir el progreso hasta `done` (puede tardar — el resolve solo son ~10-60s, pero
  bajar el episodio completo con `ffmpeg` depende del ancho de banda real)**

```bash
ssh blog "curl -s http://localhost:8099/jobs/1 -H \"X-Api-Key: $API_KEY\""
```

Verificar que el `status` pasa de `queued`→`downloading`→`done`, que `progress` avanza (no se
queda fijo en 0 durante minutos — si eso pasa, revisar logs: `sudo docker logs arkiv-offline-app-1
--tail 50`).

- [ ] **Step 5: Verificar el archivo real y que `/stream` lo sirve**

```bash
ssh blog "sudo docker exec arkiv-offline-app-1 find /data/downloads -name '*.mkv' -exec ls -lh {} \;"
ssh blog "curl -s -I http://localhost:8099/stream/1"
```

- [ ] **Step 6: Limpiar**

```bash
API_KEY=$(ssh blog "grep API_KEY ~/arkiv-offline/.env | cut -d= -f2")
ssh blog "curl -s -X DELETE http://localhost:8099/jobs/1 -H \"X-Api-Key: $API_KEY\""
ssh blog "sudo docker exec arkiv-offline-app-1 find /data/downloads -type f"
```

Expected: vacío (todo limpio).

---

## Self-Review (hecho al escribir este plan)

- **Cobertura del spec:** cliente del resolver (Task 2) sin tocar `web-resolver` — cumple; descarga
  con `ffmpeg` vía `proxyUrl` (Task 3) — cumple; secuenciación 1-a-la-vez manejada por el worker
  mismo, no por una cola externa (Tasks 4-5) — cumple, documentado explícito en el código
  (`_dispatch_next_web_item` solo lanza 1 por pasada); progreso reusando `progress_tracker`
  existente (Task 5) — cumple, sin mecanismo nuevo; reconciliación simple al reiniciar (sin
  ambigüedad de gid, Task 4) — cumple; cancelación mata el proceso + borra parcial (Task 6) —
  cumple; `ffmpeg` en Docker (Task 7) — cumple; verificación real en `blog` contra el mismo
  `page_url` ya usado en el sub-proyecto #1 (Task 8) — cumple.
- **Placeholders:** ninguno en el código dado; el único punto marcado explícitamente como
  "confirmar antes de escribir" es el nombre exacto de `_track_progress`/`_forget_progress` en
  Task 5 (dependen de cómo haya quedado nombrado el helper interno del sub-proyecto #1) — no es un
  placeholder de lo que hay que construir, es una instrucción de "leé el código real antes de
  copiar el nombre", ya aclarado en el texto de la tarea.
- **Consistencia de tipos:** `WebResolverClient.resolve()` devuelve el dict crudo del JSON en las 3
  tareas que lo consumen (Task 2 lo define, Task 4 lo consume); `FfmpegDownload` tiene la misma
  interfaz (`.poll()`/`.output_size()`/`.stderr_tail()`/`.kill()`) en Task 3 (donde se define) y en
  Tasks 4-6 (donde se usa) — ningún método inventado sobre la marcha con un nombre distinto.
