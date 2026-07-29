# Enrichment de series + anime + películas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Extender el enrichment (hoy solo películas) a **series + anime + películas**: traer el catálogo de series de hacktorrent (metadata) y descubrir/guardar torrents por episodio (parseando SxxEyy de los resultados) para series y anime, además de películas.

**Architecture:** Cambios sobre el repo existente `hacktorrent-mirror`: el crawler ingiere `/series` (metadata-only, kind='serie'); `enrich_title` usa query solo-título para episódico y parsea S/E por resultado; `run_enrich` cubre los 3 tipos con re-enriquecimiento periódico del episódico; `upsert_enriched_torrents` persiste season/episode/is_pack.

**Tech Stack:** Python 3 (local 3.9 / prod 3.12), PostgreSQL, requests+BeautifulSoup, FlareSolverr, pytest.

## Global Constraints

- **Repo:** `/Users/cristian/hacktorrent-mirror` (main; desplegado en blog). GitHub `lordmacu/hacktorrent-mirror`.
- **Identidad git:** `lordmacu` / `10134930+lordmacu@users.noreply.github.com`. SIN coautoría.
- **Python local 3.9:** `from __future__ import annotations` en módulos nuevos que usen `X | None`.
- **DB de test:** `docker run --rm -d --name htm-pg -e POSTGRES_PASSWORD=htm -e POSTGRES_DB=htm -p 5433:5432 postgres:16`; `export DATABASE_URL=postgresql://postgres:htm@localhost:5433/htm`.
- **Query episódico = solo título** (no los templates tv/anime per-episodio: queremos todos los episodios).
- **S/E**: reusar `mirror/episode.map_episode({}, result_name)` para parsear SxxEyy del nombre del resultado.
- **Precisión primero**: ante duda de matching, NO agregar.

---

## Task 1: Crawler ingiere el catálogo de series (metadata-only)

**Files:**
- Modify: `mirror/hacktorrent.py` (agregar 'series' al mapeo de listado)
- Modify: `mirror/crawler.py` (`run_full` recorre también series)
- Test: `tests/test_crawler.py` (agregar)

**Interfaces:**
- `HackTorrentClient.iter_slugs("series")` pagina `/series` (key de respuesta `"series"`).
- `run_full` ingiere `(("movies","movie"),("series","serie"),("animes","anime"))`.

- [ ] **Step 1: `_LIST_KEY` + get_detail** — en `mirror/hacktorrent.py`, agregar `"series": "series"` al dict `_LIST_KEY`. (get_detail ya toma cualquier `kind`: para series el endpoint es `/serie/{slug}` — pasar `detail_kind="serie"`.)

- [ ] **Step 2: run_full recorre series** — en `mirror/crawler.py::run_full`, cambiar el loop de kinds a:
```python
    for list_kind, detail_kind in (("movies","movie"), ("series","serie"), ("animes","anime")):
        slugs = [(detail_kind, s) for s in client.iter_slugs(list_kind)]
        t, tr, e = _ingest_slugs(conn, client, slugs, started)
        titles += t; torrents += tr; errors += e
    for k in ("movie", "serie", "anime"):
        dbmod.reconcile(conn, k, started)
```
(Antes reconciliaba movie+anime; ahora también serie. `build_torrents` de una serie devuelve [] — se ingiere metadata sola.)

- [ ] **Step 3: Write the failing test** — `tests/test_crawler.py` (agregar; con FakeClient que devuelve una serie sin downloads):
```python
def test_run_full_ingests_series_metadata(db):
    import datetime, json, pathlib
    from mirror import crawler
    MOVIE = json.load(open(pathlib.Path(__file__).parent/"fixtures"/"movie.json"))
    ANIME = json.load(open(pathlib.Path(__file__).parent/"fixtures"/"anime.json"))
    # serie fixture: como MOVIE pero type='serie', slug distinto y SIN downloads
    SERIE = {**MOVIE, "id": 999001, "slug":"breaking-bad-x", "type":"serie",
             "title":"Breaking Bad X", "downloads": []}
    class FC:
        def iter_slugs(self, kind):
            return iter({"movies":[MOVIE["slug"]], "series":[SERIE["slug"]], "animes":[ANIME["slug"]]}.get(kind, []))
        def get_detail(self, kind, slug):
            return {"movie":MOVIE, "serie":SERIE, "anime":ANIME}[kind]
    def now(): return datetime.datetime(2026,7,25,tzinfo=datetime.timezone.utc)
    crawler.run_full(db, FC(), now)
    with db.cursor() as cur:
        cur.execute("SELECT kind FROM titles WHERE slug=%s", (SERIE["slug"],))
        assert cur.fetchone()[0] == "serie"
        cur.execute("SELECT count(*) FROM torrents t JOIN titles ti ON ti.id=t.title_id WHERE ti.slug=%s", (SERIE["slug"],))
        assert cur.fetchone()[0] == 0   # metadata-only, sin torrents de hacktorrent
```

- [ ] **Step 4: Run** — `DATABASE_URL=... .venv/bin/python -m pytest tests/test_crawler.py -v` → PASS (RED primero si el test corre antes del cambio).

- [ ] **Step 5: Full suite + commit**
```bash
DATABASE_URL=... .venv/bin/python -m pytest -q
git add mirror/hacktorrent.py mirror/crawler.py tests/test_crawler.py
git commit -m "feat(enrich): ingerir catalogo de series (metadata-only) en el full crawl"
```

---

## Task 2: enrich_title episódico (query solo-título + parseo de S/E) + persistir S/E

**Files:**
- Modify: `mirror/enrich.py`
- Modify: `mirror/db.py` (`upsert_enriched_torrents` persiste season/episode/episode_end/is_pack)
- Test: `tests/test_enrich.py`, `tests/test_db_enrich.py` (agregar)

**Interfaces:**
- `enrich_title(providers, fetcher, title_row)`: para `kind in (serie,anime)` la query es el título solo; cada fila incluye `season`/`episode`/`episode_end`/`is_pack` parseados del nombre del resultado con `map_episode({}, name)`. Para pelis, sin S/E (como hoy).
- `upsert_enriched_torrents` inserta también `season,episode,episode_end,is_pack`.

- [ ] **Step 1: Write the failing test** — `tests/test_enrich.py` (agregar):
```python
def test_enrich_episodic_parses_se(monkeypatch):
    from mirror.providers.provider_config import HtmlProvider
    from mirror.providers.html_backend import RawResult
    from mirror import enrich
    prov = HtmlProvider(id="tv", name="TV", base_url="https://t", search_path="/s?q={query}",
                        keywords={"tv":"{title} S{season:2}E{episode:2}"}, language_tokens=[], parser={"rowSelector":"x"})
    r = RawResult("Breaking Bad S02E05 1080p Latino",
                  "magnet:?xt=urn:btih:452A779504892333BCE4270819A5910653292C16", None, None, 12, 1024)
    monkeypatch.setattr(enrich, "parse_list", lambda p, html, url, **k: [r])
    class FF:  # fetcher que devuelve algo no vacío
        def get(self, url, needs_render=False, charset="utf-8"): return "<html/>"
    SERIE = {"id":1,"kind":"serie","title":"Breaking Bad","original_title":"Breaking Bad","year":2008}
    rows, stats = enrich.enrich_title([prov], FF(), SERIE)
    assert len(rows) == 1
    assert rows[0]["season"] == 2 and rows[0]["episode"] == 5   # S/E parseado del nombre
```

- [ ] **Step 2: Run** → FAIL (season/episode no están en la fila).

- [ ] **Step 3: enrich.py** — en `enrich_title`:
  - Query por tipo: para `serie`/`anime` usar el **título solo**; para `movie`, el template `movie`.
```python
    kind = title_row.get("kind", "movie")
    ...
    for prov in providers:
        ...
        if kind in ("serie", "anime"):
            query = title_row.get("title") or ""      # solo título: queremos TODOS los episodios
        else:
            tmpl = prov.keywords.get("movie") or "{title}"
            query = render_keyword(tmpl, ctx)
        ...
```
  - Por cada resultado matcheado, parsear S/E si es episódico y agregarlo a la fila:
```python
                se = (None, None, None, False)
                if kind in ("serie", "anime"):
                    from mirror.episode import map_episode
                    se = map_episode({}, r.name)      # (season, episode, episode_end, is_pack)
                out.append({
                    "name": r.name, "infohash": infohash, "magnet": magnet, "torrent_url": r.torrent_url,
                    "quality": None, "size_bytes": r.size_bytes, "size_label": None,
                    "lang_raw": None, "lang_norm": norm_language(r.name),
                    "seeders": r.seeders, "source": prov.id, "match_score": score,
                    "download_type": "link" if magnet else "torrent",
                    "season": se[0], "episode": se[1], "episode_end": se[2], "is_pack": se[3],
                })
```
  (El matcher se llama con `season=None, episode=None` a nivel título — sin gate de S/E puntual, matchea por cobertura de título; ya es así porque enrich_title no pasa S/E.)

- [ ] **Step 4: db.py** — en `upsert_enriched_torrents`, agregar las columnas `season,episode,episode_end,is_pack` al INSERT (con `r.get("season")`, etc.) y a la lista de valores. El `ON CONFLICT ... DO UPDATE` puede setear también `season=EXCLUDED.season,episode=EXCLUDED.episode,episode_end=EXCLUDED.episode_end,is_pack=EXCLUDED.is_pack`. Mantener el `WHERE torrents.source <> 'hacktorrent'`.

- [ ] **Step 5: Test de db** — `tests/test_db_enrich.py` (agregar): upsert de una fila enriquecida con `season=2,episode=5` y verificar que se persiste.
```python
def test_upsert_enriched_persists_se(db):
    import datetime
    now = datetime.datetime.now(datetime.timezone.utc)
    db_mod.upsert_detail(db, MOVIE, now)
    row = {"name":"Show S02E05","infohash":"dddd779504892333bce4270819a5910653292c16","magnet":"magnet:?xt=urn:btih:DDDD",
           "torrent_url":None,"quality":None,"size_bytes":1,"size_label":None,"lang_raw":None,"lang_norm":"LATINO",
           "seeders":3,"source":"eztv","match_score":0.9,"download_type":"link","season":2,"episode":5,"episode_end":None,"is_pack":False}
    db_mod.upsert_enriched_torrents(db, MOVIE["id"], [row], now)
    with db.cursor() as cur:
        cur.execute("SELECT season, episode FROM torrents WHERE source='eztv'")
        assert cur.fetchone() == (2, 5)
```

- [ ] **Step 6: Run + commit**
```bash
DATABASE_URL=... .venv/bin/python -m pytest tests/test_enrich.py tests/test_db_enrich.py -q
DATABASE_URL=... .venv/bin/python -m pytest -q
git add mirror/enrich.py mirror/db.py tests/test_enrich.py tests/test_db_enrich.py
git commit -m "feat(enrich): episodico (query solo-titulo + parseo SxxEyy + persistir season/episode)"
```

---

## Task 3: run_enrich cubre los 3 tipos + re-enriquecimiento periódico del episódico

**Files:**
- Modify: `mirror/config.py` (`reenrich_days`), `mirror/crawler.py` (`run_enrich`)
- Test: `tests/test_crawler.py` (agregar)

**Interfaces:**
- `Config.reenrich_days` (env `REENRICH_DAYS`, default 7).
- `run_enrich` selecciona: pelis (`last_enriched IS NULL OR torrents hacktorrent cambiaron`) + series/anime (`last_enriched IS NULL OR last_enriched < now() - reenrich_days`).

- [ ] **Step 1: config** — agregar `reenrich_days: int` (`REENRICH_DAYS`, default 7) en `Config`/`from_env`.

- [ ] **Step 2: run_enrich query** — reemplazar el `WHERE active AND kind='movie' AND (...)` por:
```python
        cur.execute(
            "SELECT id FROM titles WHERE active AND ("
            "  (kind='movie' AND (last_enriched IS NULL OR EXISTS "
            "     (SELECT 1 FROM torrents x WHERE x.title_id=titles.id AND x.source='hacktorrent' AND x.last_seen > titles.last_enriched)))"
            "  OR (kind IN ('serie','anime') AND (last_enriched IS NULL OR last_enriched < now() - (%s || ' days')::interval))"
            ") ORDER BY last_enriched NULLS FIRST, year DESC NULLS LAST LIMIT %s",
            (str(reenrich_days), limit))
```
(Pasar `reenrich_days` a `run_enrich`; en `main()`/hooks usar `cfg.reenrich_days`.)
Nota: quitar el `AND kind='movie'` viejo. Las series metadata-only tienen `active=false` hasta tener torrents, así que el primer enrich de una serie necesita que entre aunque esté inactiva — **incluir `kind IN ('serie','anime')` sin exigir active para el caso `last_enriched IS NULL`**:
```python
            "SELECT id FROM titles WHERE ("
            "  (active AND kind='movie' AND (last_enriched IS NULL OR EXISTS (...)))"
            "  OR (kind IN ('serie','anime') AND (last_enriched IS NULL OR last_enriched < now() - (%s||' days')::interval))"
            ") ORDER BY last_enriched NULLS FIRST LIMIT %s"
```
Nota: la rama serie/anime NO exige `active` en el re-enrich → una serie que quedó vacía (0 torrents,
inactiva) igual se **re-intenta cada `reenrich_days`**, para agarrar torrents que aparezcan más tarde
(los trackers agregan capítulos con el tiempo). El costo es re-buscar series perpetuamente vacías cada N
días — acotado y aceptable para no perder recall.
```
(Una serie nueva `active=false last_enriched=NULL` entra por la 2da rama; tras enriquecerla, `deactivate_titles_without_torrents` la deja `active=true` si encontró torrents.)

- [ ] **Step 3: Write the failing test** — `tests/test_crawler.py` (agregar): una serie metadata-only (active=false, last_enriched NULL) es elegida por `run_enrich` y, tras enriquecerla (fake enrich con 1 torrent), queda `active=true`.
```python
def test_run_enrich_includes_inactive_series(db, monkeypatch):
    import datetime, json, pathlib
    from mirror import crawler, db as db_mod
    now = datetime.datetime.now(datetime.timezone.utc)
    # insertar una serie metadata-only inactiva
    with db.cursor() as cur:
        cur.execute("INSERT INTO titles (id,slug,kind,title,year,active,last_enriched) "
                    "VALUES (5550,'serie-x','serie','Serie X',2020,false,NULL)")
    monkeypatch.setattr(crawler, "enrich_title", lambda provs,f,row,**k: (
        [{"name":"Serie X S01E01","infohash":"eeee779504892333bce4270819a5910653292c16","magnet":"magnet:?xt=urn:btih:EEEE",
          "torrent_url":None,"quality":None,"size_bytes":1,"size_label":None,"lang_raw":None,"lang_norm":"LATINO",
          "seeders":5,"source":"eztv","match_score":0.9,"download_type":"link","season":1,"episode":1,"episode_end":None,"is_pack":False}],
        {"eztv":{"found":1,"matched":1,"error":False}}))
    crawler.run_enrich(db, providers=[object()], fetcher=object(), limit=10, reenrich_days=7)
    with db.cursor() as cur:
        cur.execute("SELECT active FROM titles WHERE id=5550")
        assert cur.fetchone()[0] is True          # ganó torrents -> activa
        cur.execute("SELECT season,episode FROM torrents WHERE title_id=5550")
        assert cur.fetchone() == (1,1)
```
(Ajustar la firma de `run_enrich` para aceptar `reenrich_days`.)

- [ ] **Step 4: Run + full suite + commit**
```bash
DATABASE_URL=... .venv/bin/python -m pytest tests/test_crawler.py -q
DATABASE_URL=... .venv/bin/python -m pytest -q
git add mirror/config.py mirror/crawler.py tests/test_crawler.py
git commit -m "feat(enrich): run_enrich cubre serie/anime + re-enrich periodico (REENRICH_DAYS)"
```

---

## Task 4: Validación en vivo + deploy

**Files:** ninguno de código.

- [ ] **Step 1: Deploy** — rsync (SIN `--delete`, excluir `.env`) + rebuild en blog:
```bash
rsync -az --exclude='.venv' --exclude='.git' --exclude='.env' --exclude='__pycache__' /Users/cristian/hacktorrent-mirror/ blog:/home/familia/hacktorrent-mirror/
ssh blog 'cd /home/familia/hacktorrent-mirror && sudo docker compose -f docker-compose.prod.yml up -d --build app'
```
- [ ] **Step 2: Traer series** — disparar un full (o esperar el timer): las 910 series se ingieren como metadata-only. Verificar `SELECT kind, count(*) FROM titles GROUP BY kind`.
- [ ] **Step 3: Validar episódico en vivo** — enriquecer una serie conocida ("Breaking Bad") y un anime, a mano (script en blog, como con Avatar). Revisar que trae episodios con S/E correcto de eztv/nyaa/españoles, sin falsos positivos (otra serie). Ajustar si hace falta.
- [ ] **Step 4: Verificar** — `/api/stats` muestra torrents por fuente para episódico; `/api/title/{slug}` de una serie agrupa por temporada.

---

## Self-Review (autor)
- Series al mirror (metadata-only) → Task 1. ✅
- Query episódico solo-título + parseo S/E → Task 2 (enrich) + persistir S/E (db). ✅
- run_enrich 3 tipos + re-enrich periódico + serie inactiva entra → Task 3. ✅
- Validación en vivo + deploy → Task 4. ✅
- Trackers nuevos (survey) y fix subtorrents/wolfmax4k: **follow-ups en curso (subagentes), se aplican aparte** — no bloquean este plan.
- Placeholder scan: sin TODOs; código real en cada task.
- Type consistency: `enrich_title` filas con season/episode/episode_end/is_pack → `upsert_enriched_torrents` (Task 2). `run_enrich(reenrich_days)` (Task 3).
