# Capítulos de Magis enriquecidos — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un capítulo de una serie de Magis muestre su imagen, su nombre y su sinopsis reales en
toda la app, en vez de una tarjeta negra que dice `Dragon Ball Daima T1_5`.

**Architecture:** El portal no tiene esos datos, pero su `detail` trae el **ID de IMDb** de la serie
(`keyWords`) y el número de temporada (`sameSeasonSeriesList`). El **gateway** —que ya llama a
`detail` para listar capítulos— resuelve IMDb → TMDB y devuelve cada capítulo enriquecido. La app
guarda eso en `episode_still` (tabla local, no sincronizada) y las pantallas que ya leen esa tabla se
prenden solas. Todo es best-effort: si TMDB falla, los capítulos salen como hoy.

**Tech Stack:** Gateway: Python 3.14, FastAPI, httpx, redis, pytest (`asyncio_mode = "auto"`).
App: Kotlin, Android, Jetpack Compose (+ Compose for TV), Room (KSP), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-11-magis-capitulos-enriquecidos-design.md`

## Global Constraints

**Este plan toca DOS repos.** Cada tarea dice en cuál trabaja. No mezclar commits entre ellos.

**Repo del gateway — `/Users/cristian/arkiv-api`** (rama `main`, remoto `lordmacu/arkiv-api`):
- Tests: `uv run pytest` desde la raíz del repo. `asyncio_mode = "auto"`: los tests async no llevan
  decorador.
- **El deploy NO es `git pull`**: en `blog` esa carpeta no es un repo git. Se copia:
  ```
  rsync -a --exclude '__pycache__' --exclude '.pytest_cache' --exclude '.venv' \
    --exclude '.git' --exclude '.env' \
    src tests Dockerfile docker-compose.yml pyproject.toml README.md blog:~/arkiv-api/
  ssh blog 'cd ~/arkiv-api && sudo docker compose up -d --build'
  ```
- **Excluir `.env` SIEMPRE**: las credenciales del portal viven solo en `blog`; localmente solo hay
  `.env.example`. Un rsync sin ese exclude deja el gateway sin poder autenticarse.
- Comentarios y docstrings en español, **sin acentos** (el código del gateway está escrito así:
  "capitulos", "numeracion"). Respetar esa convención.

**Repo de la app — `/Users/cristian/archive`** (rama `main`):
- **Otras sesiones de Claude comparten este working tree y se trabaja directo sobre `main`.** Nunca
  `git add -A` ni `git add .`; commitear SIEMPRE con pathspec explícito
  (`git commit -m "..." -- <rutas>`) y mirar `git diff --cached --stat` antes.
- No cambiar de rama, no `git pull`, `git rebase` ni `git reset`. Si un commit falla por conflicto,
  PARAR y reportar.
- Comentarios y KDoc en español (acá **sí** con acentos), explicando POR QUÉ. Tests en snake_case
  español sin backticks.
- Tests: `./gradlew testDebugUnitTest --tests "<clase>"`; al final
  `./gradlew :app:compileDebugKotlin testDebugUnitTest`.

**Los dos:**
- NUNCA agregar `Co-Authored-By` ni ningún pie de coautoría.
- El enriquecimiento es **best-effort**: ningún fallo de TMDB puede romper la lista de capítulos ni
  la reproducción. Ante la duda, devolver el dato del portal.

## Estructura de archivos

**Gateway:**

| Archivo | Responsabilidad |
|---|---|
| `src/arkiv_api/catalog/tmdb.py` | Cliente TMDB. Se le suman `find_by_imdb` y `season`, y un TTL por llamada. |
| `src/arkiv_api/adapters/magis/adapter.py` | Conserva imdb+temporada del `detail` y enriquece la lista de capítulos. |
| `src/arkiv_api/app.py` | Le pasa el cliente TMDB al adapter de Magis. |
| `src/arkiv_api/router/resolve.py` | Acepta que un adapter devuelva lista o dict con `series`. |
| `tests/test_magis_enriquecido.py` (crear) | Los casos del enriquecimiento, con TMDB falso. |

**App:**

| Archivo | Responsabilidad |
|---|---|
| `data/gateway/GatewayModels.kt` | `GatewayEpisode` + campos nuevos; `GatewaySerie`. |
| `data/gateway/ArkivApiClient.kt` | Parseo tolerante de lo nuevo. |
| `data/db/Entities.kt`, `ArkivDatabase.kt`, `Daos.kt` | Columna `overview`, migración 19→20, y el cruce de "Continuar viendo". |
| `data/ArkivRepository.kt` | `addMagisSeason` guarda stills y el `tmdbId` del ítem. |
| `ui/tv/TvDetailScreen.kt`, `ui/detail/DetailScreen.kt` | La sinopsis del capítulo. |
| `ui/tv/TvSearchScreen.kt`, `ui/catalog/MagisSeasonDialog.kt` | La lista de capítulos del buscador. |
| `ui/home/HomeScreen.kt`, `ui/tv/TvHomeScreen.kt` | "Continuar viendo" con still y nombre reales. |

---

### Task 1: TMDB — buscar por IMDb y traer una temporada

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Modify: `src/arkiv_api/catalog/tmdb.py`
- Test: `tests/test_tmdb_client.py` (crear si no existe; si existe, agregar)

**Interfaces:**
- Consumes: nada.
- Produces:
  - `TmdbClient.find_by_imdb(imdb_id: str) -> int | None` — el `id` de `tv_results[0]`, o `None`.
  - `TmdbClient.season(tmdb_id: int, numero: int, language: str | None = None) -> dict` — la
    respuesta cruda de la temporada; `language` la pide en otro idioma (para el fallback al inglés).
  - `_get` acepta `ttl_s` y `stale_s` opcionales (por defecto los de hoy).

- [ ] **Step 1: Escribir los tests que fallan**

Crear `tests/test_tmdb_client.py`:

```python
import httpx
import pytest

from arkiv_api.catalog.tmdb import TmdbClient
from tests.fakes import FakeCache  # si no existe, ver Step 2


def _cliente(respuestas: dict) -> TmdbClient:
    """TmdbClient con un transporte falso: {path: json} -> respuestas."""
    def handler(request: httpx.Request) -> httpx.Response:
        cuerpo = respuestas.get(request.url.path)
        if cuerpo is None:
            return httpx.Response(404, json={"status_message": "Not found"})
        return httpx.Response(200, json=cuerpo)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return TmdbClient(http, FakeCache(), "LLAVE")


async def test_find_by_imdb_devuelve_el_id_de_la_serie():
    c = _cliente({"/3/find/tt29485149": {"tv_results": [{"id": 236994, "name": "Dragon Ball Daima"}]}})
    assert await c.find_by_imdb("tt29485149") == 236994


async def test_find_by_imdb_sin_resultados_de_tv_da_none():
    # Una pelicula matchea en movie_results, no en tv_results: no sirve para una serie.
    c = _cliente({"/3/find/tt1234567": {"tv_results": [], "movie_results": [{"id": 9}]}})
    assert await c.find_by_imdb("tt1234567") is None


async def test_season_trae_los_capitulos():
    c = _cliente({"/3/tv/236994/season/1": {"episodes": [{"episode_number": 1, "name": "La conspiracion"}]}})
    d = await c.season(236994, 1)
    assert d["episodes"][0]["episode_number"] == 1
```

Si `tests/fakes.py` no expone un caché falso, agregarle uno mínimo (mirar primero qué hay ahí: el
resto de los tests del repo ya usa `fakes.py`, hay que seguir su estilo, no inventar otro):

```python
class FakeCache:
    """Cache en memoria con la misma forma que `store.cache.Cache`."""

    def __init__(self) -> None:
        self.datos: dict = {}

    async def get_json(self, clave: str):
        return self.datos.get(clave), False

    async def set_json(self, clave: str, valor, ttl_s: int, stale_s: int) -> None:
        self.datos[clave] = valor
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_tmdb_client.py -v`
Expected: FAIL — `AttributeError: 'TmdbClient' object has no attribute 'find_by_imdb'`.

- [ ] **Step 3: Implementar**

En `src/arkiv_api/catalog/tmdb.py`, `_get` acepta TTLs opcionales:

```python
    async def _get(self, path: str, clave: str, ttl_s: int | None = None, stale_s: int | None = None, **params) -> dict:
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
        await self._cache.set_json(clave, datos, ttl_s or _TTL_S, stale_s or _STALE_S)
        return datos
```

Y los dos metodos nuevos:

```python
    async def find_by_imdb(self, imdb_id: str) -> int | None:
        """El id de TMDB de la SERIE con ese id de IMDb, o None.

        Es un match exacto, no una busqueda por titulo: el portal de magis publica el id de IMDb en
        `keyWords`, asi que no hay que adivinar. Se cachea 30 dias porque un identificador no cambia.
        """
        datos = await self._get(
            f"/find/{imdb_id}",
            f"tmdb:find:{imdb_id}",
            ttl_s=30 * 24 * 3600,
            stale_s=90 * 24 * 3600,
            external_source="imdb_id",
        )
        resultados = (datos or {}).get("tv_results") or []
        return resultados[0].get("id") if resultados else None

    async def season(self, tmdb_id: int, numero: int, language: str | None = None) -> dict:
        """Una temporada con sus capitulos (nombre, sinopsis y still de cada uno).

        [language] permite pedirla en otro idioma; se usa para el respaldo en ingles cuando TMDB
        devuelve la sinopsis vacia en espanol, cosa que pasa seguido. Va en la clave de cache para
        que los dos idiomas no se pisen.

        El TTL depende de si la temporada esta completa: si a algun capitulo le falta el `still_path`
        la serie esta en emision y el still llega a TMDB dias despues del estreno, asi que conviene
        volver a preguntar pronto. Una temporada terminada ya no se mueve.
        """
        idioma = language or self._lang
        clave = f"tmdb:season:{tmdb_id}:{numero}:{idioma}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado
        r = await self._http.get(
            f"{_BASE}/tv/{tmdb_id}/season/{numero}",
            params={"api_key": self._key, "language": idioma},
            timeout=10,
        )
        r.raise_for_status()
        datos = r.json()
        capitulos = (datos or {}).get("episodes") or []
        completa = bool(capitulos) and all(c.get("still_path") for c in capitulos)
        await self._cache.set_json(
            clave, datos, (14 if completa else 1) * 24 * 3600, 90 * 24 * 3600
        )
        return datos
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_tmdb_client.py -v`
Expected: PASS (3)

- [ ] **Step 5: Correr toda la suite (que `_get` no rompió a nadie)**

Run: `cd /Users/cristian/arkiv-api && uv run pytest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/catalog/tmdb.py tests/test_tmdb_client.py tests/fakes.py && git diff --cached --stat && git commit -m "feat(tmdb): buscar serie por id de imdb y traer una temporada"
```

---

### Task 2: El gateway enriquece la lista de capítulos

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Modify: `src/arkiv_api/adapters/magis/adapter.py` (`_capitulos_crudos` ~línea 399, `episodes` ~línea 421, `_VERSION_CACHE`)
- Modify: `src/arkiv_api/app.py` (línea ~105, el `reg.register(MagisAdapter(...))`)
- Modify: `src/arkiv_api/router/resolve.py` (línea ~45-53)
- Test: `tests/test_magis_enriquecido.py` (crear)

**Interfaces:**
- Consumes: `TmdbClient.find_by_imdb(imdb) -> int | None`, `TmdbClient.season(tmdb_id, n) -> dict` (Task 1).
- Produces: `/v1/episodes` devuelve
  `{"episodes": [{number, title, ref, still?, tmdb_title?, overview?}], "series": {imdb_id, tmdb_id, season_number}?}`.

- [ ] **Step 1: Escribir los tests que fallan**

Crear `tests/test_magis_enriquecido.py`. Mirar primero `tests/test_adapter_magis.py` para reusar su
forma de construir el adapter (sesiones falsas, `Cache`, `fakeredis`) — **no inventar otro andamiaje**:

```python
"""El enriquecimiento con TMDB de los capitulos de magis.

El portal NO manda imagen ni nombre real por capitulo (`posterList` llega vacio en todas las series
medidas), pero SI manda el id de IMDb de la serie en `keyWords`. De ahi sale todo esto.
"""


DETALLE_SERIE = {
    "assetData": {
        "contentId": "SERIE1",
        "name": "Dragon Ball Daima T1",
        "keyWords": "tt29485149",
        "sameSeasonSeriesList": [{"contentId": "SERIE1", "seasonNumber": 1}],
        "simpleProgramList": [
            {"contentId": "c1", "name": "Dragon Ball Daima T1_1", "seriesNumber": 1, "posterList": []},
            {"contentId": "c2", "name": "Dragon Ball Daima T1_2", "seriesNumber": 2, "posterList": []},
        ],
    }
}

TEMPORADA_TMDB = {
    "episodes": [
        {"episode_number": 1, "name": "La conspiracion", "overview": "Goku y sus amigos...",
         "still_path": "/uno.jpg"},
        {"episode_number": 2, "name": "Glorio", "overview": "", "still_path": "/dos.jpg"},
    ]
}


class TmdbFalso:
    def __init__(self, tmdb_id=236994, temporada=None, ingles=None, explota=False):
        self._id, self._temporada, self._ingles = tmdb_id, temporada, ingles
        self._explota = explota
        self.pedidos = []

    async def find_by_imdb(self, imdb_id):
        if self._explota:
            raise RuntimeError("TMDB caido")
        self.pedidos.append(("find", imdb_id))
        return self._id

    async def season(self, tmdb_id, numero, language=None):
        if self._explota:
            raise RuntimeError("TMDB caido")
        self.pedidos.append(("season", tmdb_id, numero, language))
        if language and language.startswith("en"):
            return self._ingles or {}
        return self._temporada or {}


async def test_los_capitulos_salen_con_still_nombre_y_sinopsis():
    salida = await _episodios(DETALLE_SERIE, TmdbFalso(temporada=TEMPORADA_TMDB))
    caps = salida["episodes"]
    assert [c["number"] for c in caps] == [1, 2]
    assert caps[0]["tmdb_title"] == "La conspiracion"
    assert caps[0]["still"].endswith("/uno.jpg")
    assert caps[0]["overview"].startswith("Goku")
    assert salida["series"] == {"imdb_id": "tt29485149", "tmdb_id": 236994, "season_number": 1}


async def test_el_titulo_del_portal_no_se_pisa():
    # `title` sigue siendo el del portal y el de TMDB va aparte: si manana TMDB deja de resolver,
    # la app no se queda sin nombre.
    caps = (await _episodios(DETALLE_SERIE, TmdbFalso(temporada=TEMPORADA_TMDB)))["episodes"]
    assert caps[0]["title"] == "Dragon Ball Daima T1_1"


async def test_sin_imdb_valido_no_se_enriquece_ni_se_rompe():
    for valor in ("", "12345", None):
        detalle = _con_keywords(DETALLE_SERIE, valor)
        caps = (await _episodios(detalle, TmdbFalso(temporada=TEMPORADA_TMDB)))["episodes"]
        assert len(caps) == 2
        assert "still" not in caps[0]


async def test_si_tmdb_no_conoce_la_serie_no_se_enriquece():
    caps = (await _episodios(DETALLE_SERIE, TmdbFalso(tmdb_id=None)))["episodes"]
    assert len(caps) == 2 and "still" not in caps[0]


async def test_si_tmdb_explota_los_capitulos_salen_igual():
    # Es el camino por el que se reproduce: no puede caerse porque un servicio de metadatos falle.
    caps = (await _episodios(DETALLE_SERIE, TmdbFalso(explota=True)))["episodes"]
    assert [c["number"] for c in caps] == [1, 2]


async def test_sin_nuestro_contentid_en_la_lista_de_temporadas_no_se_adivina():
    detalle = json.loads(json.dumps(DETALLE_SERIE))
    detalle["assetData"]["sameSeasonSeriesList"] = [{"contentId": "OTRA", "seasonNumber": 3}]
    caps = (await _episodios(detalle, TmdbFalso(temporada=TEMPORADA_TMDB)))["episodes"]
    assert "still" not in caps[0]


async def test_un_capitulo_que_tmdb_no_tiene_queda_sin_enriquecer():
    temporada = {"episodes": [TEMPORADA_TMDB["episodes"][0]]}   # solo el 1
    caps = (await _episodios(DETALLE_SERIE, TmdbFalso(temporada=temporada)))["episodes"]
    assert caps[0]["tmdb_title"] == "La conspiracion"
    assert "tmdb_title" not in caps[1]


async def test_guard_de_numeracion_el_portal_con_mas_capitulos_no_enriquece_nada():
    # One Piece "Temp.1" en el portal trae 8 capitulos y la temporada 1 de TMDB tiene 61: las
    # numeraciones no son la misma y cruzarlas pondria stills equivocados, que es peor que ninguno.
    detalle = json.loads(json.dumps(DETALLE_SERIE))
    detalle["assetData"]["simpleProgramList"].append(
        {"contentId": "c3", "name": "T1_3", "seriesNumber": 3, "posterList": []}
    )
    caps = (await _episodios(detalle, TmdbFalso(temporada=TEMPORADA_TMDB)))["episodes"]
    assert all("still" not in c for c in caps)


async def test_sinopsis_vacia_en_espanol_cae_al_ingles():
    # TMDB devuelve `overview` vacio en es-MX muy seguido; el nombre suele venir igual. El capitulo 2
    # de TEMPORADA_TMDB tiene overview vacio a proposito.
    tmdb = TmdbFalso(
        temporada=TEMPORADA_TMDB,
        ingles={"episodes": [{"episode_number": 2, "name": "Glorio", "overview": "Glorio appears."}]},
    )
    caps = (await _episodios(DETALLE_SERIE, tmdb))["episodes"]
    assert caps[1]["overview"] == "Glorio appears."
    # El nombre en espanol NO se pisa con el ingles: solo se rellena la sinopsis que falta.
    assert caps[1]["tmdb_title"] == "Glorio"


async def test_sin_sinopsis_faltantes_no_se_pide_el_ingles():
    # El respaldo cuesta una llamada mas: no debe dispararse cuando no hace falta.
    tmdb = TmdbFalso(temporada={"episodes": [
        {"episode_number": 1, "name": "La conspiracion", "overview": "Hay sinopsis", "still_path": "/u.jpg"},
        {"episode_number": 2, "name": "Glorio", "overview": "Tambien", "still_path": "/d.jpg"},
    ]})
    await _episodios(DETALLE_SERIE, tmdb)
    assert not any(p[0] == "season" and p[3] for p in tmdb.pedidos)
```

El helper `_episodios(detalle, tmdb, ingles=None)` construye el `MagisAdapter` con una sesión falsa
que devuelve `detalle` cuando le piden `detail`, le inyecta el TMDB falso, y llama a
`adapter.episodes({"content_id": "SERIE1"})`. Copiar el andamiaje de `tests/test_adapter_magis.py`.
`_con_keywords(detalle, valor)` devuelve una copia con ese `keyWords`.

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_enriquecido.py -v`
Expected: FAIL — el adapter todavía no acepta un cliente TMDB ni devuelve `series`.

- [ ] **Step 3: Guardar imdb y temporada al leer el detalle**

En `adapter.py`, subir la versión de caché (la forma de lo guardado cambia) y hacer que
`_capitulos_crudos` conserve los tres datos:

```python
    async def _capitulos_crudos(self, session, serie_id: str) -> dict:
        """Capitulos de una temporada tal como los da el portal, mas lo que hace falta para TMDB.

        Devuelve `{"items": [...], "imdb": "ttNNN", "season": N}`. El `keyWords` del detalle ES el id
        de IMDb de la serie y `sameSeasonSeriesList` dice que numero de temporada es esta: son los
        dos datos con los que se resuelve TMDB sin adivinar por titulo.
        """
        clave = f"magis:eps:v{_VERSION_CACHE}:{serie_id}"
        cacheado, _stale = await self._cache.get_json(clave)
        if cacheado is not None:
            return cacheado

        detalle = await self._llamar(session, "detail", "detail", serie_id, type_="0")
        datos = (detalle or {}).get("assetData", {}) or {}
        crudos = datos.get("simpleProgramList", []) or []
        temporadas = datos.get("sameSeasonSeriesList") or []
        numero = next(
            (t.get("seasonNumber") for t in temporadas if t.get("contentId") == serie_id), None
        )
        salida = {"items": crudos, "imdb": str(datos.get("keyWords") or ""), "season": numero}

        # Solo se cachea si hay capitulos: guardar una lista vacia por un fallo transitorio
        # dejaria la serie sin capitulos durante 6 h.
        if crudos:
            await self._cache.set_json(clave, salida, _TTL_BUSQUEDA_S, _TTL_STALE_S)
        return salida
```

`_capitulo` (que hoy hace `episodios = await self._capitulos_crudos(...)`) pasa a usar
`(await self._capitulos_crudos(...))["items"]`. Buscar TODOS los llamadores y actualizarlos.

- [ ] **Step 4: Enriquecer en `episodes()`**

```python
_IMDB = re.compile(r"^tt\d+$")
_IMG = "https://image.tmdb.org/t/p/w300"


    async def _enriquecer(self, imdb: str, temporada: int | None, cuantos: int) -> dict[int, dict]:
        """`numero de capitulo -> {still, tmdb_title, overview}`. Vacio si no se puede.

        Best-effort de punta a punta: cualquier fallo devuelve un mapa vacio y los capitulos salen
        con lo del portal. Esta es la llamada por la que se reproduce, no puede depender de TMDB.
        """
        if self._tmdb is None or not _IMDB.match(imdb or "") or temporada is None:
            return {}
        try:
            tmdb_id = await self._tmdb.find_by_imdb(imdb)
            if not tmdb_id:
                return {}
            datos = await self._tmdb.season(tmdb_id, temporada)
        except Exception as e:   # noqa: BLE001 - metadatos opcionales, nunca deben romper la lista
            _log.info("magis: sin enriquecer (%s)", e)
            return {}

        capitulos = (datos or {}).get("episodes") or []
        # Guard de numeracion: si el portal tiene MAS capitulos que TMDB, las numeraciones no son la
        # misma (One Piece "Temp.1" trae 8 en el portal y 61 en TMDB) y cruzarlas por numero pondria
        # stills equivocados. Un still equivocado es peor que ninguno: el usuario no puede saberlo.
        if cuantos > len(capitulos):
            return {}

        out = {}
        for c in capitulos:
            n = c.get("episode_number")
            if n is None:
                continue
            fila = {}
            if c.get("still_path"):
                fila["still"] = _IMG + c["still_path"]
            if c.get("name"):
                fila["tmdb_title"] = c["name"]
            if c.get("overview"):
                fila["overview"] = c["overview"]
            if fila:
                out[int(n)] = fila

        # Respaldo en ingles SOLO para las sinopsis que quedaron vacias: TMDB devuelve el `overview`
        # vacio en es-MX muy seguido (el nombre suele venir igual). Se pide una vez para toda la
        # temporada y solo si falta alguna, asi que no cuesta nada en el caso normal.
        faltan = [n for n, f in out.items() if "overview" not in f]
        if faltan:
            try:
                en = await self._tmdb.season(tmdb_id, temporada, language="en-US")
            except Exception:   # noqa: BLE001 - el respaldo es opcional
                return out
            for c in (en or {}).get("episodes") or []:
                n = c.get("episode_number")
                if n is not None and int(n) in out and c.get("overview"):
                    out[int(n)]["overview"] = c["overview"]
        return out
```

Y `episodes()`:

```python
    async def episodes(self, payload: dict) -> dict:
        """Capitulos de una temporada, cada uno con su propio ref resoluble.

        Ademas del ref, cada capitulo sale con la imagen, el nombre y la sinopsis de TMDB cuando se
        pudieron resolver: el portal no los tiene (su `posterList` por capitulo llega siempre vacio),
        pero publica el id de IMDb de la serie y con eso el match es exacto.
        """
        cid = payload["content_id"]
        acc = payload.get("account_id", "")
        session, _ = await self._sessions.for_request(acc)
        crudos = await self._capitulos_crudos(session, cid)
        items = crudos["items"]
        extra = await self._enriquecer(crudos.get("imdb", ""), crudos.get("season"), len(items))

        salida = []
        for ep in items:
            numero = int(ep.get("seriesNumber") or 0)
            cap = {
                "number": numero,
                "title": ep.get("name") or f"Capitulo {numero}",
                "ref": encode_ref(
                    self.name,
                    {"content_id": cid, "program_type": "teleplay", "episode": numero},
                    self._key,
                    ttl_s=_TTL_REF_S,
                ),
            }
            cap.update(extra.get(numero, {}))
            salida.append(cap)

        serie = None
        if extra:
            serie = {"imdb_id": crudos["imdb"], "season_number": crudos["season"]}
        return {"episodes": salida, "series": serie} if serie else {"episodes": salida}
```

Para que `series` incluya el `tmdb_id`, `_enriquecer` tiene que devolverlo también. Cambiar su
retorno a la tupla `(mapa, tmdb_id)` y armar
`serie = {"imdb_id": ..., "tmdb_id": tmdb_id, "season_number": ...}`. Ajustar los tests del Step 1 si
hace falta, pero **sin cambiar lo que afirman**.

Agregar arriba del archivo: `import re` y `_log = logging.getLogger(__name__)` si no existen.

- [ ] **Step 5: Cablear el cliente TMDB y el router**

En `app.py`, el catálogo se construye en `build_catalog` y los adapters en la función de arriba. El
adapter necesita el mismo `TmdbClient`. Pasarlo al construir el adapter:

```python
        reg.register(MagisAdapter(
            sessions_magis, Cache(redis), settings.ref_signing_key,
            tmdb=TmdbClient(http, Cache(redis), settings.tmdb_api_key) if settings.tmdb_api_key else None,
        ))
```

Si esa función no recibe `http`, pasárselo desde donde se la llama (`app.state.http` ya existe:
`build_catalog` lo recibe igual). `MagisAdapter.__init__` suma `tmdb=None` como último parámetro
opcional, para no romper a los tests que ya lo construyen con tres argumentos.

En `router/resolve.py`, tolerar las dos formas:

```python
    try:
        res = await listar(payload)
    except UpstreamError as e:
        _log.warning("episodes fuente=%s → FALLO upstream: %s", fuente, e)
        raise HTTPException(status_code=502, detail=str(e)) from e

    # Un adapter puede devolver la lista pelada (contrato viejo) o un dict con `episodes` y datos de
    # la serie. El router es compartido: no debe asumir que solo magis expone capitulos.
    if isinstance(res, dict):
        caps, serie = res.get("episodes") or [], res.get("series")
    else:
        caps, serie = res, None
    _log.info("episodes fuente=%s → %d capitulos", fuente, len(caps))
    salida = {"episodes": caps}
    if serie:
        salida["series"] = serie
    return salida
```

- [ ] **Step 6: Correr los tests**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_enriquecido.py -v && uv run pytest`
Expected: PASS — los nuevos y toda la suite (en especial `test_adapter_magis.py` y
`test_app_wiring.py`, que construyen el adapter).

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/adapters/magis/adapter.py src/arkiv_api/app.py src/arkiv_api/router/resolve.py tests/test_magis_enriquecido.py && git diff --cached --stat && git commit -m "feat(magis): capitulos con still, nombre y sinopsis de TMDB via id de imdb"
```

---

### Task 3: Desplegar y comprobar contra el portal real

**Repo:** `/Users/cristian/arkiv-api`. **No toca código** salvo que la comprobación encuentre algo.

Va ANTES del trabajo en la app a propósito: valida el contrato con datos reales, y si el portal
sorprende (una serie sin `keyWords`, una numeración rara), es mucho más barato enterarse ahora.

- [ ] **Step 1: Desplegar**

```bash
cd /Users/cristian/arkiv-api && rsync -a --exclude '__pycache__' --exclude '.pytest_cache' --exclude '.venv' --exclude '.git' --exclude '.env' src tests Dockerfile docker-compose.yml pyproject.toml README.md blog:~/arkiv-api/
```

```bash
ssh blog 'cd ~/arkiv-api && sudo docker compose up -d --build' 2>&1 | tail -5
```

- [ ] **Step 2: Comprobar con Dragon Ball Daima**

La llave sale de `/Users/cristian/archive/.env` y va en `X-Arkiv-Key`. Hay que conseguir un `ref` de
la temporada (buscando) y pedirle los capítulos:

```bash
K=$(grep -E '^ARKIV_API_KEY=' /Users/cristian/archive/.env | cut -d= -f2)
REF=$(curl -s -H "X-Arkiv-Key: $K" "https://api.comparadorinternet.co/v1/search?q=Dragon%20Ball%20Daima&type=tv&sources=magis&budget_ms=60000" | grep -o '"ref":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -H "X-Arkiv-Key: $K" -H 'Content-Type: application/json' -d "{\"ref\":\"$REF\"}" https://api.comparadorinternet.co/v1/episodes | head -c 1200
```

Expected: los 20 capítulos, los primeros con `"tmdb_title":"La conspiración"`, `"still":"https://image.tmdb.org/t/p/w300/..."`, `"overview"` no vacío, y un `"series":{"imdb_id":"tt29485149","tmdb_id":236994,"season_number":1}`.

- [ ] **Step 3: Comprobar los casos que NO deben enriquecerse**

Repetir con **One Piece** (el portal trae 8 capítulos donde TMDB tiene 61: el guard de numeración
tiene que dejarlo sin enriquecer, con los 8 capítulos intactos) y con **Breaking Bad T5** (16
capítulos, temporada 5: tiene que enriquecerse con los nombres de la T5, no los de la T1 — si
salieran los de la temporada 1, el número de temporada se está resolviendo mal).

- [ ] **Step 4: Anotar el resultado**

Si algo no calza, PARAR y reportar antes de seguir con la app: el contrato es la base de las 6 tareas
que vienen.

---

### Task 4: La app entiende los campos nuevos

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt` (línea ~60)
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` (`episodes`, línea ~123)
- Test: `app/src/test/java/com/arkiv/player/data/gateway/` (mirar qué hay ahí y seguir su estilo)

**Interfaces:**
- Consumes: el JSON de la Task 2.
- Produces:
  - `GatewayEpisode(number, title, ref, still: String? = null, tmdbTitle: String? = null, overview: String? = null)`
  - `GatewaySerie(imdbId: String, tmdbId: Int, seasonNumber: Int)`
  - `ArkivApiClient.episodes(ref)` sigue devolviendo `List<GatewayEpisode>`; se suma
    `episodesConSerie(ref): Pair<List<GatewayEpisode>, GatewaySerie?>`.

- [ ] **Step 1: Escribir los tests que fallan**

Mirar primero `app/src/test/java/com/arkiv/player/data/gateway/` para ver cómo se testea ahí el
parseo (si hay un helper que arme el cliente contra un servidor falso, usarlo; si el parseo está en
una función suelta, testearla directo). Los casos, sobre el JSON crudo del gateway:

```kotlin
    private val CON_TODO = """
        {"episodes":[
           {"number":1,"title":"Dragon Ball Daima T1_1","ref":"r1",
            "still":"https://image.tmdb.org/t/p/w300/uno.jpg",
            "tmdb_title":"La conspiración","overview":"Goku y sus amigos…"},
           {"number":2,"title":"Dragon Ball Daima T1_2","ref":"r2"}],
         "series":{"imdb_id":"tt29485149","tmdb_id":236994,"season_number":1}}
    """.trimIndent()

    /** Lo que devuelve un gateway sin desplegar, o uno al que TMDB le falló. */
    private val SOLO_LO_VIEJO = """
        {"episodes":[{"number":1,"title":"Dragon Ball Daima T1_1","ref":"r1"}]}
    """.trimIndent()

    @Test fun un_capitulo_enriquecido_trae_still_nombre_y_sinopsis() {
        val (caps, _) = parsear(CON_TODO)
        assertEquals("https://image.tmdb.org/t/p/w300/uno.jpg", caps[0].still)
        assertEquals("La conspiración", caps[0].tmdbTitle)
        assertEquals("Goku y sus amigos…", caps[0].overview)
        // El título del portal se conserva aparte: es el que se guarda como displayName.
        assertEquals("Dragon Ball Daima T1_1", caps[0].title)
    }

    @Test fun un_capitulo_sin_enriquecer_deja_los_campos_nuevos_en_null() {
        val (caps, _) = parsear(CON_TODO)
        assertNull(caps[1].still)
        assertNull(caps[1].tmdbTitle)
        assertNull(caps[1].overview)
    }

    @Test fun el_bloque_series_se_lee_entero() {
        val (_, serie) = parsear(CON_TODO)
        assertEquals("tt29485149", serie?.imdbId)
        assertEquals(236994, serie?.tmdbId)
        assertEquals(1, serie?.seasonNumber)
    }

    @Test fun una_respuesta_vieja_sin_los_campos_nuevos_sigue_funcionando() {
        // Compatibilidad hacia atrás: no puede tirar excepción ni perder el capítulo.
        val (caps, serie) = parsear(SOLO_LO_VIEJO)
        assertEquals(1, caps.size)
        assertEquals("r1", caps[0].ref)
        assertNull(caps[0].still)
        assertNull(serie)
    }

    @Test fun un_still_vacio_se_lee_como_null_y_no_como_cadena_vacia() {
        // Si quedara "" la UI intentaría cargar una imagen inexistente en vez de caer al respaldo.
        val (caps, _) = parsear("""{"episodes":[{"number":1,"title":"t","ref":"r","still":"","tmdb_title":""}]}""")
        assertNull(caps[0].still)
        assertNull(caps[0].tmdbTitle)
    }
```

`parsear(json)` es el helper del propio test que llama a la función de parseo del cliente y devuelve
`Pair<List<GatewayEpisode>, GatewaySerie?>`.

- [ ] **Step 2: Correr y verificar que fallan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.gateway.*"`
Expected: FAIL

- [ ] **Step 3: Implementar**

En `GatewayModels.kt`:

```kotlin
/**
 * Un capítulo de una temporada de Magis.
 *
 * [still], [tmdbTitle] y [overview] los agrega el gateway cruzando el id de IMDb que publica el
 * portal contra TMDB: el portal NO tiene imagen ni nombre real por capítulo (su `posterList` por
 * capítulo llega siempre vacío). Son opcionales a propósito — si TMDB no resolvió, el capítulo se
 * muestra con [title], que es el del portal.
 */
data class GatewayEpisode(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
)

/** La serie a la que pertenece una temporada, cuando el gateway la pudo identificar. */
data class GatewaySerie(val imdbId: String, val tmdbId: Int, val seasonNumber: Int)
```

En `ArkivApiClient.episodes`, parsear los campos nuevos con el mismo criterio tolerante que ya usa
(campo ausente o vacío = null, nunca excepción) y agregar `episodesConSerie`. `episodes(ref)` se
mantiene delegando en la nueva, para no tocar a sus llamadores.

- [ ] **Step 4: Correr y verificar que pasan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.gateway.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt app/src/test/java/com/arkiv/player/data/gateway" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(magis): la app entiende still, nombre y sinopsis de los capitulos" -- $RUTAS
```

---

### Task 5: Guardar lo que llega

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt` (`EpisodeStillEntity`, línea ~238)
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt` (`version = 19` → 20, nueva migración)
- Modify: `app/src/main/java/com/arkiv/player/data/MagisEntities.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`addMagisSeason`)
- Modify: `app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt` (`playMagisSeason`)
- Test: `app/src/test/java/com/arkiv/player/data/MagisEntitiesTest.kt`

**Interfaces:**
- Consumes: `GatewayEpisode` con los campos nuevos, `GatewaySerie` (Task 4).
- Produces:
  - `EpisodeStillEntity` con `overview: String? = null`.
  - `MagisEntities.stillsDeTemporada(itemId, capitulos, ahora): List<EpisodeStillEntity>` — solo de
    los capítulos que traen algo.
  - `ArkivRepository.addMagisSeason(...)` acepta `tmdbId: Int?` y guarda los stills.

- [ ] **Step 1: Escribir los tests que fallan**

En `MagisEntitiesTest.kt`, con el `CapituloDeTemporada` extendido con los campos nuevos:

```kotlin
    @Test fun un_capitulo_enriquecido_deja_su_fila_de_still() {
        val filas = MagisEntities.stillsDeTemporada(
            itemId = "magis:ABC",
            capitulos = listOf(
                CapituloDeTemporada(1, "T1_1", "ref-1", still = "https://img/1.jpg", tmdbTitle = "La conspiración", overview = "Goku…"),
                CapituloDeTemporada(2, "T1_2", "ref-2"),
            ),
            ahora = 1_000L,
        )
        assertEquals(1, filas.size)
        assertEquals("magis:ABC::e1", filas[0].episodeId)
        assertEquals("https://img/1.jpg", filas[0].stillUrl)
        assertEquals("La conspiración", filas[0].title)
        assertEquals("Goku…", filas[0].overview)
    }

    @Test fun un_capitulo_sin_enriquecer_no_deja_fila() {
        // Sin fila, la UI cae al displayName del portal. Con una fila vacía mostraría un hueco.
        assertEquals(0, MagisEntities.stillsDeTemporada("magis:ABC", listOf(CapituloDeTemporada(1, "T1_1", "ref-1")), 1_000L).size)
    }

    @Test fun el_id_de_la_fila_calza_con_el_del_episodio() {
        // La fila se cruza por episodeId: si no calzara, la imagen no aparecería nunca.
        val (_, eps) = temporada()
        val filas = MagisEntities.stillsDeTemporada("magis:ABC", listOf(CapituloDeTemporada(2, "x", "r", still = "u")), 1_000L)
        assertEquals(eps.first { it.episode == 2 }.id, filas[0].episodeId)
    }
```

- [ ] **Step 2: Correr y verificar que fallan**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.MagisEntitiesTest"`
Expected: FAIL — `stillsDeTemporada` no existe.

- [ ] **Step 3: Implementar la columna y la migración**

`Entities.kt`, en `EpisodeStillEntity`:

```kotlin
    /**
     * Sinopsis del capítulo según TMDB. Vive acá y no en `episodes` por lo mismo que [stillUrl] y
     * [title]: es dato derivable y esa tabla tiene triggers de sync. Null = no se pudo resolver.
     */
    val overview: String? = null,
```

`ArkivDatabase.kt`: `version = 20` y

```kotlin
        /**
         * v19 -> v20: la sinopsis del capítulo, que llega junto al still y al título desde el
         * gateway. `episode_still` es caché local derivable y NO está entre las tablas que
         * sincroniza `SyncTriggers`, así que esta columna no toca el sync.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_still ADD COLUMN overview TEXT")
            }
        }
```

y sumarla donde se registran las demás (buscar `MIGRATION_18_19` y agregarla al lado, en la lista de
`addMigrations`).

- [ ] **Step 4: Implementar el armado y el guardado**

`CapituloDeTemporada` suma `still: String? = null, tmdbTitle: String? = null, overview: String? = null`.

`MagisEntities.stillsDeTemporada(itemId, capitulos, ahora)` devuelve una `EpisodeStillEntity` por
capítulo que tenga al menos uno de los tres campos, con `episodeId = "$itemId::e$number"` — el MISMO
id que arma `capituloDe`, que es lo que hace que se crucen.

`MagisEntities.buildSeason` suma el parámetro `tmdbId: Int? = null` **antes** de `existente` y el
ítem pasa a escribir `tmdbId = tmdbId ?: existente?.tmdbId`. Ese `?:` importa: una llamada sin
`tmdbId` (porque TMDB no resolvió esta vez) **no puede borrar** el que ya estaba guardado. Actualizar
los tests que ya existen de `buildSeason` con el caso: `tmdbId = null` sobre un existente con
`tmdbId = 123` deja 123.

`ArkivRepository.addMagisSeason` suma el parámetro `tmdbId: Int? = null`, se lo pasa a `buildSeason`
y guarda las filas con `episodeStillDao.upsertAll(MagisEntities.stillsDeTemporada(...))` — solo si la
lista no vino vacía, para no hacer una escritura de más en el caso sin enriquecer.

`SearchPlayback.playMagisSeason` usa `episodesConSerie` y mapea los campos nuevos a
`CapituloDeTemporada`, pasando el `tmdbId` de la serie.

- [ ] **Step 5: Correr los tests y compilar**

Run: `./gradlew testDebugUnitTest --tests "com.arkiv.player.data.MagisEntitiesTest" && ./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/db/Entities.kt app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt app/src/main/java/com/arkiv/player/data/MagisEntities.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/ui/search/SearchPlayback.kt app/src/test/java/com/arkiv/player/data/MagisEntitiesTest.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(magis): guardar imagen, nombre y sinopsis de cada capitulo" -- $RUTAS
```

---

### Task 6: La sinopsis en el detalle (TV y celu)

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (junto a `observeEpisodeTitles`)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt` (el bloque de info, líneas ~194-215)
- Modify: `app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt`

**Interfaces:**
- Consumes: `episode_still.overview` (Task 5).
- Produces: `ArkivRepository.observeEpisodeOverviews(itemId): Flow<Map<String, String>>`.

Las imágenes y los nombres **no requieren cambios acá**: `TvDetailScreen`, `DetailScreen` y
`PlayerScreen` ya leen `observeEpisodeStills`/`observeEpisodeTitles` y se prenden solos con los datos
de la Task 5. Esta tarea agrega únicamente la sinopsis.

- [ ] **Step 1: El flow de sinopsis**

En `ArkivRepository`, al lado de `observeEpisodeTitles` (mismo molde: mapear la tabla a
`episodeId -> texto`, descartando los null).

- [ ] **Step 2: El TV**

En `TvDetailScreen`, con un capítulo enfocado, hoy se **oculta** la sinopsis de la serie con este
comentario:

```kotlin
// Sin capítulo enfocado, la sinopsis de la serie. Con uno enfocado no se muestra:
// la de la serie no describe ESE capítulo, y TMDB no nos da la del episodio acá.
```

Ese hueco es exactamente lo que ahora se llena. La regla queda: con capítulo enfocado, su sinopsis si
la hay (si no, nada); sin capítulo enfocado, la de la serie. Actualizar ese comentario para que
explique la regla nueva — hoy afirma algo que dejó de ser cierto.

- [ ] **Step 3: El celu**

En `DetailScreen`, la sinopsis del capítulo en su fila, recortada a 2 líneas con
`overflow = TextOverflow.Ellipsis`, solo cuando exista.

- [ ] **Step 4: Compilar y correr la suite**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/ui/tv/TvDetailScreen.kt app/src/main/java/com/arkiv/player/ui/detail/DetailScreen.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(detalle): la sinopsis del capitulo enfocado" -- $RUTAS
```

---

### Task 7: "Continuar viendo" con el capítulo real

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (`ContinueRow` línea ~11, `observeContinueWatching` línea ~191)
- Modify: `app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt`, `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`

**Interfaces:**
- Consumes: `episode_still` (Task 5).
- Produces: `ContinueRow` con `stillUrl: String?` y `episodeTitle: String?`.

Es la única superficie que NO se prende sola: sale de su propia query, que no conoce `episode_still`.

- [ ] **Step 1: La query**

`ContinueRow` suma dos campos nullables:

```kotlin
    /** Still y título del capítulo según TMDB, cuando se pudieron resolver (hoy solo Magis). */
    val stillUrl: String? = null,
    val episodeTitle: String? = null,
```

y `observeContinueWatching` pasa a:

```kotlin
    @Query(
        """
        SELECT p.episodeId AS episodeId, e.itemId AS itemId, i.title AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl, i.description AS itemDescription,
               p.positionMs AS positionMs, p.durationMs AS durationMs,
               p.lastPlayedAt AS lastPlayedAt,
               s.stillUrl AS stillUrl, s.title AS episodeTitle
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        LEFT JOIN episode_still s ON s.episodeId = p.episodeId
        WHERE p.watched = 0 AND p.positionMs > :minPositionMs AND i.deleted = 0 AND p.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT 60
        """
    )
    fun observeContinueWatching(minPositionMs: Long): Flow<List<ContinueRow>>
```

**LEFT** join a propósito: un capítulo sin fila de still tiene que seguir apareciendo en "Continuar
viendo" exactamente como hoy. El `WHERE`, el `ORDER BY` y el `LIMIT` no se tocan.

- [ ] **Step 2: Las dos Home**

La tarjeta usa `stillUrl` si existe y si no cae a lo de hoy (`thumbPath` → `itemThumbnailUrl`), y el
texto usa `episodeTitle` si existe y si no `displayName`.

- [ ] **Step 3: Compilar y correr la suite**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/ui/home/HomeScreen.kt app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(home): continuar viendo con la imagen y el nombre del capitulo" -- $RUTAS
```

---

### Task 8: La lista de capítulos del buscador

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt` (`TvMagisEpisodeRow`, línea ~1659)
- Modify: `app/src/main/java/com/arkiv/player/ui/catalog/MagisSeasonDialog.kt` (`EpisodeRow`, línea ~179)

Acá todavía no hay ítem guardado, pero **no hace falta ninguna llamada extra**: los campos vienen en
la misma respuesta de `/v1/episodes` que ya se pide para listar.

- [ ] **Step 1: La fila del TV**

Hoy `TvMagisEpisodeRow` pinta:

```kotlin
"E${cap.number}  " + (cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() } ?: "Capítulo ${cap.number}")
```

Pasa a preferir `cap.tmdbTitle` cuando exista, manteniendo el resto de la regla (el comentario que ya
está explica por qué el título del portal a veces no aporta: repite el nombre de la temporada). Y
suma la miniatura `cap.still` a la izquierda cuando exista, con el mismo `AsyncImage` que usan las
otras filas del archivo.

**El número manda**: sigue diciendo `E5` y el nombre va al lado, nunca en su lugar — si TMDB
estuviera corrido, el número sigue siendo cierto.

- [ ] **Step 2: La fila del celu**

Lo mismo en `EpisodeRow` de `MagisSeasonDialog`, respetando que la casilla de guardar es un objetivo
aparte del gesto de reproducir (ver el comentario que ya está ahí).

- [ ] **Step 3: Compilar y correr la suite**

Run: `./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
RUTAS="app/src/main/java/com/arkiv/player/ui/tv/TvSearchScreen.kt app/src/main/java/com/arkiv/player/ui/catalog/MagisSeasonDialog.kt" && git add $RUTAS && git diff --cached --stat && git commit -m "feat(buscador): capitulos de magis con nombre real y miniatura" -- $RUTAS
```

---

### Task 9: Verificación en device

**Repo:** `/Users/cristian/archive`. No toca código salvo que aparezca un problema.

Compilar (`./gradlew assembleDebug`) e instalar en el Fire TV Stick (ADB de red, puerto 5555; la IP
es dinámica). El APK pesa ~150 MB y por WiFi la instalación directa se corrompe: hay que `push` a
`/data/local/tmp`, comparar md5 y `pm install -r` desde ahí.

- [ ] Buscar Dragon Ball Daima y abrir la temporada: la lista tiene que decir `E1 La conspiración`,
      `E5 Panzy`… con miniatura, en vez de `Dragon Ball Daima T1_5`.
- [ ] Tocar el E5: guarda la temporada y reproduce el 5 (no puede romperse nada de lo que ya andaba).
- [ ] Detalle en el TV: tarjetas con imagen y nombre real; al enfocar una, su sinopsis.
- [ ] Detalle en el celu: lo mismo en la lista.
- [ ] Pausar el reproductor: el overlay con imagen y nombre real.
- [ ] Home: la tarjeta de "Continuar viendo" con el still y el nombre del capítulo.
- [ ] Una serie que TMDB no conozca: todo sigue funcionando con los nombres del portal.
- [ ] Revisar la base del device (`run-as com.arkiv.player cat databases/arkiv.db`, **con el `-wal`**,
      que si no no se ve lo recién escrito) y confirmar que `episode_still` tiene las filas y que
      `items.tmdbId` quedó puesto.
