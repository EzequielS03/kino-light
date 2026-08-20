# Saltar intro y outro automáticos — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que los botones "Saltar intro" y "Saltar outro" aparezcan solos, con tiempos por episodio, sin que nadie teclee `mm:ss` nunca más.

**Architecture:** El gateway traduce `tmdbId → mal_id` con el dataset de Fribb que ya descarga, le pide los tiempos a AniSkip y los cachea en Redis. La app pide los marcadores del capítulo al reproducirlo, los guarda en `skip_markers` (que ya se sincroniza) y dibuja el botón. Ningún modelo de lenguaje entra acá.

**Tech Stack:** Python 3.12 + FastAPI + Redis (`arkiv-api`); Kotlin + Room + Compose (`archive`); PocketBase 0.39 en `blog`.

**Spec:** [docs/superpowers/specs/2026-08-19-saltar-intro-automatico-design.md](../specs/2026-08-19-saltar-intro-automatico-design.md)

## Global Constraints

- **Cero tokens de modelo.** Esta feature no llama a ningún LLM. Si una tarea necesita uno, está mal planteada.
- **Nunca falla hacia el cliente.** Sin datos, sin `mal_id` o con AniSkip caído, el gateway responde `{}` y la app se comporta exactamente como hoy: sin botón, sin error, sin hueco en la UI. Mismo criterio que `/v1/trivia`.
- **El botón nunca salta solo.** Sigue siendo un botón que se toca. Saltar automático no entra en este plan.
- **Tiempos en milisegundos en la app, segundos en AniSkip.** La conversión ocurre UNA vez, en el gateway (Tarea 3), y de ahí para adentro todo es `Long` en ms.
- **`arkiv-api` se despliega con rsync + `docker compose build`, nunca con `git pull`.** El comando exacto está en la Tarea 4.
- **Identidad de git:** `user.name = lordmacu`, `user.email = 10134930+lordmacu@users.noreply.github.com`. Sin pie de coautoría en los commits.

---

### Tarea 1: Comprobar el desfase contra un capítulo real (SIN CÓDIGO)

Es la primera tarea a propósito. El spec identifica el desfase de release como **el** riesgo: los tiempos de AniSkip son del release canónico, y si la copia de Magis arranca con un logo de distribuidora el marcador queda corrido. Todo lo demás depende de que esto calce, así que se mide antes de escribir una línea.

**Files:** ninguno. La salida es una decisión.

**Interfaces:**
- Consumes: nada.
- Produces: la respuesta go/no-go para las Tareas 2-7, y el offset si hiciera falta.

- [ ] **Paso 1: Elegir el capítulo de prueba**

Death Note tiene 4/4 de cobertura en AniSkip y está en la biblioteca. Su MAL id es `1535`.

```bash
curl -s "https://api.aniskip.com/v2/skip-times/1535/1?types=op&types=ed&episodeLength=0" | python3 -m json.tool
```

Anotar `intervalo.op.startTime` y `endTime`.

- [ ] **Paso 2: Reproducir ese mismo capítulo en el Fire TV y medir a mano**

```bash
adb connect 192.168.1.22:5555
adb -s 192.168.1.22:5555 shell "am start -n com.arkiv.player/.MainActivity"
```

Navegar con el D-pad (`input keyevent 19/20/21/22`, OK es `23`; `input tap` NO funciona en Fire TV), reproducir el capítulo 1, y capturar la pantalla cuando arranca el opening y cuando termina:

```bash
adb -s 192.168.1.22:5555 exec-out screencap -p > /tmp/op.png
```

La barra de progreso del player muestra el tiempo; comparar con lo que dijo AniSkip.

- [ ] **Paso 3: Decidir**

- **Desfase menor a ~2 s** → seguir con la Tarea 2 tal cual.
- **Desfase constante** (por ejemplo siempre +12 s) → seguir, y anotar acá el valor: la Tarea 3 tendrá que sumarlo por fuente. Repetir con un segundo capítulo para confirmar que es constante.
- **Desfase errático** → **parar y avisar**. AniSkip no sirve para el video de Magis y el plan entero hay que repensarlo. No seguir "a ver si igual sirve".

- [ ] **Paso 4: Escribir el resultado en el spec**

Añadir al final del apartado "Riesgos" del spec una línea con lo medido (capítulo, lo que dijo AniSkip, lo que se vio, diferencia) y commitear:

```bash
git add docs/superpowers/specs/2026-08-19-saltar-intro-automatico-design.md
git commit -m "docs(spec): medido el desfase de AniSkip contra un capítulo real"
```

---

### Tarea 2: El dataset de Fribb conserva el `mal_id` (gateway)

**Files:**
- Modify: `arkiv-api/src/arkiv_api/anime/meta.py` (función `parsear_fribb`, ~L70-81)
- Test: `arkiv-api/tests/test_anime_meta.py`

**Interfaces:**
- Consumes: nada.
- Produces: `parsear_fribb(datos) -> dict[int, dict]` donde cada valor gana la clave `"mal_id": int | None`, y `mal_por_tmdb(datos) -> dict[int, int]`, el índice inverso que usa la Tarea 3.

- [ ] **Paso 1: Escribir los tests que fallan**

Añadir al final de `tests/test_anime_meta.py`:

```python
from arkiv_api.anime.meta import mal_por_tmdb, parsear_fribb


def test_el_indice_conserva_el_mal_id():
    """AniSkip usa ids de MyAnimeList y el dataset ya los trae; hoy se tiraban."""
    idx = parsear_fribb([{"anilist_id": 30, "mal_id": 30, "themoviedb_id": {"tv": 890}}])
    assert idx[30]["mal_id"] == 30


def test_sin_mal_id_la_entrada_igual_entra():
    """El resto de lo que hace este índice (temporada TVDB, tmdb_id) no depende del mal_id."""
    idx = parsear_fribb([{"anilist_id": 30, "themoviedb_id": {"tv": 890}}])
    assert idx[30]["mal_id"] is None
    assert idx[30]["tmdb_id"] == 890


def test_indice_inverso_de_tmdb_a_mal():
    idx = mal_por_tmdb([
        {"anilist_id": 30, "mal_id": 30, "themoviedb_id": {"tv": 890}},
        {"anilist_id": 21, "mal_id": 21, "themoviedb_id": {"tv": 37854}},
    ])
    assert idx == {890: 30, 37854: 21}


def test_el_inverso_ignora_lo_que_no_tiene_los_dos_ids():
    """Sin tmdb no hay por dónde entrar; sin mal no hay a quién preguntarle."""
    assert mal_por_tmdb([{"anilist_id": 30, "themoviedb_id": {"tv": 890}}]) == {}
    assert mal_por_tmdb([{"anilist_id": 30, "mal_id": 30}]) == {}


def test_el_inverso_se_queda_con_la_primera_entrada_de_un_tmdb_repetido():
    """Una serie con varias temporadas comparte tmdb_id y tiene un mal_id por temporada. Este
    índice es para el caso simple; la temporada la resuelve el llamador."""
    idx = mal_por_tmdb([
        {"anilist_id": 1, "mal_id": 100, "themoviedb_id": {"tv": 5}},
        {"anilist_id": 2, "mal_id": 200, "themoviedb_id": {"tv": 5}},
    ])
    assert idx == {5: 100}
```

- [ ] **Paso 2: Correr los tests y verlos fallar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_anime_meta.py -q`
Expected: FAIL con `ImportError: cannot import name 'mal_por_tmdb'`

- [ ] **Paso 3: Implementar**

En `src/arkiv_api/anime/meta.py`, cambiar el cuerpo del bucle de `parsear_fribb`:

```python
        salida[anilist] = {
            "tvdb_season": _entero(o.get("season")),
            "tmdb_id": _tmdb_de(o),
            # AniSkip pregunta por id de MyAnimeList y el dataset ya lo trae: tirarlo obligaba a
            # una fuente de mapeo aparte para algo que ya estaba descargado.
            "mal_id": _entero(o.get("mal_id")),
        }
```

Y añadir después de `parsear_fribb`:

```python
def mal_por_tmdb(datos: list) -> dict[int, int]:
    """`{tmdb_id: mal_id}`, para ir de lo que la biblioteca sabe a lo que AniSkip entiende.

    Se queda con la PRIMERA entrada de cada `tmdb_id`: una serie con varias temporadas comparte el
    id de TMDB y tiene un `mal_id` por temporada. Este índice resuelve el caso simple (una entrada
    por obra) y quien necesite la temporada exacta la resuelve por su cuenta -- inventar acá una
    regla de temporadas sin datos que la respalden seria peor que no tenerla.
    """
    salida: dict[int, int] = {}
    for entrada in parsear_fribb(datos).values():
        tmdb, mal = entrada.get("tmdb_id"), entrada.get("mal_id")
        if tmdb and mal and tmdb not in salida:
            salida[tmdb] = mal
    return salida
```

- [ ] **Paso 4: Correr los tests y verlos pasar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest -q && uv run ruff check src/arkiv_api/anime/`
Expected: PASS, y `All checks passed!`

- [ ] **Paso 5: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/anime/meta.py tests/test_anime_meta.py
git commit -m "feat(anime): el índice de Fribb conserva el mal_id y expone el inverso tmdb→mal"
```

---

### Tarea 3: La fuente AniSkip (gateway)

**Files:**
- Create: `arkiv-api/src/arkiv_api/marcadores/__init__.py` (vacío)
- Create: `arkiv-api/src/arkiv_api/marcadores/aniskip.py`
- Test: `arkiv-api/tests/test_marcadores_aniskip.py`

**Interfaces:**
- Consumes: nada (recibe el `mal_id` ya resuelto).
- Produces: `Marcadores(opening_start_ms: int | None, opening_end_ms: int | None, ending_start_ms: int | None)` y `async def de_aniskip(http, mal_id: int, episodio: int) -> Marcadores | None`.

- [ ] **Paso 1: Escribir los tests que fallan**

Crear `tests/test_marcadores_aniskip.py`:

```python
import httpx
import pytest

from arkiv_api.marcadores.aniskip import Marcadores, de_aniskip

RESPUESTA = {
    "found": True,
    "results": [
        {"interval": {"startTime": 0, "endTime": 90}, "skipType": "op", "episodeLength": 1402},
        {"interval": {"startTime": 1319, "endTime": 1394}, "skipType": "ed", "episodeLength": 1402},
    ],
    "statusCode": 200,
}


def _http(cuerpo, status=200):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=cuerpo)
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


@pytest.mark.asyncio
async def test_trae_el_opening_y_el_final_en_milisegundos():
    """La app trabaja en ms; AniSkip contesta en segundos. La conversión pasa UNA vez, acá."""
    m = await de_aniskip(_http(RESPUESTA), 30, 1)
    assert m == Marcadores(opening_start_ms=0, opening_end_ms=90_000, ending_start_ms=1_319_000)


@pytest.mark.asyncio
async def test_sin_datos_devuelve_none():
    """`found: false` es la respuesta normal de AniSkip para un capítulo que nadie marcó: no es un
    error y no debe loguearse como tal."""
    assert await de_aniskip(_http({"found": False, "results": []}), 30, 1) is None


@pytest.mark.asyncio
async def test_solo_opening_tambien_sirve():
    """Media respuesta vale: el botón de intro se dibuja y el de outro no."""
    cuerpo = {"found": True, "results": [RESPUESTA["results"][0]]}
    m = await de_aniskip(_http(cuerpo), 30, 1)
    assert m.opening_end_ms == 90_000
    assert m.ending_start_ms is None


@pytest.mark.asyncio
async def test_un_error_de_red_no_revienta():
    """Esto cuelga de una reproducción: que AniSkip esté caído no puede romper el player."""
    def handler(request):
        raise httpx.ConnectError("sin red")
    assert await de_aniskip(httpx.AsyncClient(transport=httpx.MockTransport(handler)), 30, 1) is None


@pytest.mark.asyncio
async def test_una_respuesta_con_forma_rara_no_revienta():
    assert await de_aniskip(_http({"results": [{"interval": "no soy un dict"}]}), 30, 1) is None


@pytest.mark.asyncio
async def test_un_5xx_no_revienta():
    assert await de_aniskip(_http({}, status=503), 30, 1) is None
```

- [ ] **Paso 2: Correr los tests y verlos fallar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_marcadores_aniskip.py -q`
Expected: FAIL con `ModuleNotFoundError: No module named 'arkiv_api.marcadores'`

- [ ] **Paso 3: Implementar**

Crear `src/arkiv_api/marcadores/__init__.py` vacío y `src/arkiv_api/marcadores/aniskip.py`:

```python
"""Tiempos de opening y ending por capitulo, de la base comunitaria AniSkip.

Sin llave y sin login: es un GET publico. Medido sobre 12 series de la biblioteca real el
2026-08-19, cubre 37 de 48 capitulos (77 %). Lo que no cubre no muestra boton.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

_log = logging.getLogger("arkiv_api.marcadores")
_BASE = "https://api.aniskip.com/v2/skip-times"


@dataclass(frozen=True)
class Marcadores:
    """En MILISEGUNDOS, que es como los guarda la app. AniSkip contesta en segundos."""

    opening_start_ms: int | None = None
    opening_end_ms: int | None = None
    ending_start_ms: int | None = None

    @property
    def vacio(self) -> bool:
        return self.opening_end_ms is None and self.ending_start_ms is None


def _ms(valor) -> int | None:
    """Segundos (int o float) a milisegundos. Cualquier otra cosa es None."""
    if isinstance(valor, (int, float)) and valor >= 0:
        return int(round(valor * 1000))
    return None


async def de_aniskip(http: httpx.AsyncClient, mal_id: int, episodio: int) -> Marcadores | None:
    """Los tiempos de ese capitulo, o None si no se saben.

    None y no una excepcion pase lo que pase: esto cuelga de una reproduccion, y que AniSkip este
    caido o conteste algo con forma rara no puede romper el player. `found: false` es la respuesta
    NORMAL para un capitulo que nadie marco -- se devuelve None sin loguear nada, porque no es un
    fallo.
    """
    url = f"{_BASE}/{mal_id}/{episodio}"
    try:
        r = await http.get(url, params={"types": ["op", "ed"], "episodeLength": 0}, timeout=8)
        if r.status_code != 200:
            return None
        datos = r.json()
        if not datos.get("found"):
            return None
        campos: dict[str, int | None] = {}
        for res in datos.get("results") or []:
            intervalo = res.get("interval")
            if not isinstance(intervalo, dict):
                continue
            if res.get("skipType") == "op":
                campos["opening_start_ms"] = _ms(intervalo.get("startTime"))
                campos["opening_end_ms"] = _ms(intervalo.get("endTime"))
            elif res.get("skipType") == "ed":
                campos["ending_start_ms"] = _ms(intervalo.get("startTime"))
        m = Marcadores(**campos)
        return None if m.vacio else m
    except Exception as e:   # red, timeout, JSON roto, forma inesperada
        _log.info("aniskip mal=%s ep=%s -> %s", mal_id, episodio, e)
        return None
```

- [ ] **Paso 4: Correr los tests y verlos pasar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_marcadores_aniskip.py -q && uv run ruff check src/arkiv_api/marcadores/`
Expected: PASS, `All checks passed!`

- [ ] **Paso 5: Commit**

```bash
cd /Users/cristian/arkiv-api
git add src/arkiv_api/marcadores tests/test_marcadores_aniskip.py
git commit -m "feat(marcadores): fuente AniSkip de opening y ending por capítulo"
```

---

### Tarea 4: El endpoint `GET /v1/marcadores` (gateway) y su despliegue

**Files:**
- Create: `arkiv-api/src/arkiv_api/router/marcadores.py`
- Modify: `arkiv-api/src/arkiv_api/app.py` (lista de imports de routers ~L30-44, `for r in (...)` ~L299-302, y el lifespan junto a `app.state.anime` ~L223)
- Test: `arkiv-api/tests/test_router_marcadores.py`

**Interfaces:**
- Consumes: `Marcadores` y `de_aniskip` (Tarea 3); `mal_por_tmdb` (Tarea 2).
- Produces: `GET /v1/marcadores?tmdbId=&temporada=&episodio=` → `{"openingStartMs": …, "openingEndMs": …, "endingStartMs": …}` o `{}`.

- [ ] **Paso 1: Escribir los tests que fallan**

Crear `tests/test_router_marcadores.py`:

```python
import fakeredis.aioredis
from fastapi.testclient import TestClient

from arkiv_api.app import create_app
from arkiv_api.config import Settings
from arkiv_api.marcadores.aniskip import Marcadores

from .conftest import SESION_HEADERS, con_sesion_valida


def _client(marcadores=None, mal=None, llamadas=None):
    app = create_app(Settings(arkiv_api_keys="k", ref_signing_key="x" * 32).validated())
    con_sesion_valida(app)
    app.state.redis = fakeredis.aioredis.FakeRedis(decode_responses=True)

    async def fuente(http, mal_id, episodio):
        if llamadas is not None:
            llamadas.append((mal_id, episodio))
        return marcadores

    app.state.marcadores_fuente = fuente
    app.state.mal_de_tmdb = (lambda tmdb: mal) if mal is not None else (lambda tmdb: None)
    return TestClient(app)


CON_DATOS = Marcadores(opening_start_ms=0, opening_end_ms=90_000, ending_start_ms=1_319_000)


def test_sin_sesion_da_401():
    r = _client().get("/v1/marcadores", params={"tmdbId": 890, "episodio": 1})
    assert r.status_code == 401


def test_devuelve_los_tiempos_en_milisegundos():
    r = _client(marcadores=CON_DATOS, mal=30).get(
        "/v1/marcadores", params={"tmdbId": 890, "temporada": 1, "episodio": 1},
        headers=SESION_HEADERS,
    )
    assert r.status_code == 200
    assert r.json() == {"openingStartMs": 0, "openingEndMs": 90_000, "endingStartMs": 1_319_000}


def test_una_obra_que_no_es_anime_contesta_vacio_sin_preguntar():
    """Sin mal_id no hay a quién preguntarle, y salir antes ahorra la llamada de red."""
    llamadas = []
    r = _client(marcadores=CON_DATOS, mal=None, llamadas=llamadas).get(
        "/v1/marcadores", params={"tmdbId": 999, "episodio": 1}, headers=SESION_HEADERS,
    )
    assert r.json() == {}
    assert llamadas == []


def test_un_capitulo_sin_datos_contesta_vacio():
    r = _client(marcadores=None, mal=30).get(
        "/v1/marcadores", params={"tmdbId": 890, "episodio": 1}, headers=SESION_HEADERS,
    )
    assert r.status_code == 200
    assert r.json() == {}


def test_la_segunda_consulta_no_vuelve_a_preguntarle_a_aniskip():
    """Los tiempos de un capítulo no cambian y no son datos de nadie: la caché se comparte entre
    aparatos y entre cuentas."""
    llamadas = []
    c = _client(marcadores=CON_DATOS, mal=30, llamadas=llamadas)
    p = {"tmdbId": 890, "temporada": 1, "episodio": 1}
    c.get("/v1/marcadores", params=p, headers=SESION_HEADERS)
    c.get("/v1/marcadores", params=p, headers=SESION_HEADERS)
    assert llamadas == [(30, 1)]


def test_el_vacio_tambien_se_cachea():
    """Si no se cacheara, cada reproducción de un capítulo sin datos pagaría la ida a AniSkip."""
    llamadas = []
    c = _client(marcadores=None, mal=30, llamadas=llamadas)
    p = {"tmdbId": 890, "episodio": 1}
    c.get("/v1/marcadores", params=p, headers=SESION_HEADERS)
    c.get("/v1/marcadores", params=p, headers=SESION_HEADERS)
    assert len(llamadas) == 1


def test_un_episodio_invalido_no_llega_a_la_fuente():
    llamadas = []
    r = _client(marcadores=CON_DATOS, mal=30, llamadas=llamadas).get(
        "/v1/marcadores", params={"tmdbId": 890, "episodio": 0}, headers=SESION_HEADERS,
    )
    assert r.json() == {}
    assert llamadas == []
```

- [ ] **Paso 2: Correr los tests y verlos fallar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_router_marcadores.py -q`
Expected: FAIL con 404 en todas las rutas (el router no existe).

- [ ] **Paso 3: Implementar el router**

Crear `src/arkiv_api/router/marcadores.py`:

```python
"""Tiempos de opening y ending de un capitulo, para los botones de saltar.

Vive en el gateway y no en la app por tres motivos, en orden de peso: el puente `tmdbId -> mal_id`
sale del dataset de Fribb que solo esta aca; la cache se comparte entre todos los aparatos y todas
las cuentas (los tiempos de un capitulo no son datos de nadie); y la fuente se puede cambiar sin
tocar la app, que pide "los marcadores de este capitulo" y no "esto a AniSkip".
"""

import json
import logging

from fastapi import APIRouter, Depends, Query, Request

from arkiv_api.identidad.sesion import require_sesion

_log = logging.getLogger(__name__)
router = APIRouter(dependencies=[Depends(require_sesion)])

#: 30 dias. Los tiempos de un capitulo publicado no cambian.
_TTL_S = 30 * 24 * 3600
_CLAVE = "arkiv:marcadores:{}:{}:{}"


@router.get("/marcadores")
async def marcadores(
    request: Request,
    tmdbId: int = Query(0),
    temporada: int = Query(0),
    episodio: int = Query(0),
) -> dict:
    if tmdbId <= 0 or episodio <= 0:
        return {}

    redis = request.app.state.redis
    clave = _CLAVE.format(tmdbId, temporada, episodio)
    guardado = await redis.get(clave)
    if guardado is not None:
        # El vacio TAMBIEN se cachea (se guarda como "{}"): sin eso, cada reproduccion de un
        # capitulo sin datos -- que son 1 de cada 4 -- paga la ida a AniSkip otra vez.
        return json.loads(guardado)

    mal_id = request.app.state.mal_de_tmdb(tmdbId)
    if not mal_id:
        # No es anime, o Fribb no lo conoce. Se cachea igual: la respuesta no va a cambiar.
        await redis.set(clave, "{}", ex=_TTL_S)
        return {}

    m = await request.app.state.marcadores_fuente(request.app.state.http, mal_id, episodio)
    salida = {} if m is None else {
        k: v for k, v in {
            "openingStartMs": m.opening_start_ms,
            "openingEndMs": m.opening_end_ms,
            "endingStartMs": m.ending_start_ms,
        }.items() if v is not None
    }
    await redis.set(clave, json.dumps(salida), ex=_TTL_S)
    return salida
```

- [ ] **Paso 4: Cablearlo en `app.py`**

En el bloque `from .router import (...)`, añadir `marcadores,` en orden alfabético (entre `live,` y `magis,`).

En `for r in (health, search, resolve, sources, catalog, stats, stream, magis, anime, live, recomendaciones, trivia, titulos,):` añadir `marcadores,`.

En el lifespan, justo después de `app.state.anime = MetaProvider(...)`, añadir:

```python
        # Marcadores de intro/outro. `mal_de_tmdb` se resuelve contra el mismo dataset de Fribb que
        # ya usa el resolver de anime: es un dict en memoria, no una llamada de red.
        from .anime.meta import mal_por_tmdb
        from .marcadores.aniskip import de_aniskip

        app.state.marcadores_fuente = de_aniskip
        _mal_cache: dict[int, int] = {}

        def _mal_de_tmdb(tmdb_id: int) -> int | None:
            if not _mal_cache:
                _mal_cache.update(mal_por_tmdb(app.state.anime.fribb_crudo()))
            return _mal_cache.get(tmdb_id)

        app.state.mal_de_tmdb = _mal_de_tmdb
```

Y en el `create_app` sin lifespan (donde están los defaults `app.state.redis = None`), añadir los defaults para que los tests que no levantan lifespan no revienten:

```python
    app.state.marcadores_fuente = None
    app.state.mal_de_tmdb = lambda tmdb_id: None
```

- [ ] **Paso 5: Exponer el dataset crudo en `MapeoFribb`**

`_mal_de_tmdb` necesita la lista cruda. En `src/arkiv_api/anime/meta.py`, añadir a `MapeoFribb`:

```python
    def crudo(self) -> list:
        """El dataset tal como se descargo, para quien necesite indexarlo de otra forma (hoy,
        `mal_por_tmdb`). Devuelve [] si todavia no se cargo: quien llame decide si reintenta."""
        return self._crudo
```

y guardar `self._crudo: list = []` en `__init__` y `self._crudo = datos` donde hoy se asigna `self._datos`. Añadir en `MetaProvider` un `def fribb_crudo(self) -> list: return self._fribb.crudo()`.

- [ ] **Paso 6: Correr los tests y verlos pasar**

Run: `cd /Users/cristian/arkiv-api && uv run pytest -q && uv run ruff check src/arkiv_api/router/marcadores.py src/arkiv_api/anime/meta.py src/arkiv_api/app.py`
Expected: PASS en toda la suite, `All checks passed!`

- [ ] **Paso 7: Commit y desplegar**

```bash
cd /Users/cristian/arkiv-api
git add -A && git commit -m "feat(marcadores): endpoint /v1/marcadores con caché de 30 días"
rsync -a --delete --exclude='__pycache__' --exclude='*.pyc' src/ blog:/home/familia/arkiv-api/src/
ssh blog 'cd ~/arkiv-api && sudo -n docker compose build -q && sudo -n docker compose up -d'
```

- [ ] **Paso 8: Comprobar contra el gateway real**

```bash
ssh blog 'sudo -n docker exec arkiv-api python -c "
import asyncio, httpx
from arkiv_api.marcadores.aniskip import de_aniskip
async def m():
    async with httpx.AsyncClient() as h:
        print(await de_aniskip(h, 30, 1))
asyncio.run(m())
"'
```

Expected: `Marcadores(opening_start_ms=0, opening_end_ms=90000, ending_start_ms=1319000)`

---

### Tarea 5: `skip_markers` pasa a ser por episodio (app + PocketBase)

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt` (`SkipMarkerEntity`, ~L113-121)
- Modify: `app/src/main/java/com/arkiv/player/data/db/ArkivDatabase.kt` (`version`, nueva migración, lista de `addMigrations`)
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (`SkipMarkerDao`, ~L431-457)
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/SyncMappers.kt` (`markerToFields`/`recordToMarker`, ~L146-163)
- Modify: `app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt` (`keyField` de `COL_MARKERS`, ~L215)
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`observeSkipMarker`/`getSkipMarker`, ~L1454-1456)
- Create: `app/src/test/java/com/arkiv/player/data/MarcadorDeCapituloTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `SkipMarkerEntity(id, itemId, episodeId, openingStartMs, openingEndMs, endingStartMs, updatedAt, deleted)`; `MarcadorDeCapitulo.idDe(itemId, episodeId): String`; `MarcadorDeCapitulo.elegir(delCapitulo, deLaSerie): SkipMarkerEntity?`; `SkipMarkerDao.observeDeCapitulo(itemId, episodeId): Flow<List<SkipMarkerEntity>>`.

- [ ] **Paso 1: Escribir los tests que fallan**

Crear `app/src/test/java/com/arkiv/player/data/MarcadorDeCapituloTest.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué marcador manda para un capítulo.
 *
 * El caso que obliga a esto, medido contra AniSkip el 2026-08-19: en Demon Slayer el opening del
 * capítulo 1 empieza a los 1270 s y el del 2 a los 57 s. Con un marcador por SERIE, "Saltar intro"
 * te tiraría a la mitad del capítulo.
 */
class MarcadorDeCapituloTest {

    private fun marcador(itemId: String, episodeId: String, opEnd: Long) = SkipMarkerEntity(
        id = MarcadorDeCapitulo.idDe(itemId, episodeId),
        itemId = itemId,
        episodeId = episodeId,
        openingStartMs = 0,
        openingEndMs = opEnd,
        endingStartMs = null,
    )

    @Test fun la_llave_junta_el_item_y_el_capitulo() {
        assertEquals("magis:ABC|magis:ABC::e1", MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e1"))
    }

    @Test fun el_marcador_de_toda_la_serie_lleva_el_capitulo_vacio() {
        assertEquals("magis:ABC|", MarcadorDeCapitulo.idDe("magis:ABC", ""))
    }

    @Test fun dos_capitulos_de_la_misma_serie_no_comparten_llave() {
        assertNotEquals(
            MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e1"),
            MarcadorDeCapitulo.idDe("magis:ABC", "magis:ABC::e2"),
        )
    }

    @Test fun el_del_capitulo_le_gana_al_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = marcador("magis:ABC", "magis:ABC::e1", opEnd = 147_000),
            deLaSerie = marcador("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(147_000, elegido!!.openingEndMs)
    }

    /** Lo puesto a mano sigue valiendo donde no hay dato automático: nadie pierde su trabajo. */
    @Test fun sin_marcador_de_capitulo_manda_el_de_la_serie() {
        val elegido = MarcadorDeCapitulo.elegir(
            delCapitulo = null,
            deLaSerie = marcador("magis:ABC", "", opEnd = 90_000),
        )
        assertEquals(90_000, elegido!!.openingEndMs)
    }

    @Test fun sin_ninguno_no_hay_marcador() {
        assertNull(MarcadorDeCapitulo.elegir(delCapitulo = null, deLaSerie = null))
    }

    /** Una fila sin ningún tiempo no es un marcador: dibujaría un botón que no lleva a ningún lado. */
    @Test fun un_marcador_sin_tiempos_no_cuenta() {
        val vacio = SkipMarkerEntity(
            id = "x", itemId = "magis:ABC", episodeId = "magis:ABC::e1",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
        )
        assertNull(MarcadorDeCapitulo.elegir(delCapitulo = vacio, deLaSerie = null))
    }
}
```

- [ ] **Paso 2: Correr y ver fallar**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.MarcadorDeCapituloTest"`
Expected: FAIL de compilación, `Unresolved reference 'MarcadorDeCapitulo'`

- [ ] **Paso 3: Implementar la parte pura**

Crear `app/src/main/java/com/arkiv/player/data/MarcadorDeCapitulo.kt`:

```kotlin
package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity

/**
 * Qué marcador de intro/outro le toca a un capítulo.
 *
 * Los tiempos son POR CAPÍTULO y no por serie: medido contra AniSkip, en Demon Slayer el opening
 * del capítulo 1 arranca a los 1270 s y el del 2 a los 57 s (cold opens largos, recapitulaciones,
 * especiales). Un solo marcador por serie mandaría el salto a la mitad del capítulo.
 *
 * El marcador de serie (`episodeId` vacío) NO desaparece: es el que se pone a mano, y sigue
 * valiendo para los capítulos que no tienen uno propio.
 */
object MarcadorDeCapitulo {

    /**
     * La llave de la fila. Derivada y no compuesta a propósito: `CloudSyncManager.pushRows` busca
     * la fila remota por UN campo natural por colección, así que una clave compuesta obligaría a
     * cambiar ese mecanismo para todas. Mismo criterio que `episodes`, que sincroniza por `epId`.
     */
    fun idDe(itemId: String, episodeId: String): String = "$itemId|$episodeId"

    /** El que manda: el del capítulo si trae algo, si no el de la serie. */
    fun elegir(delCapitulo: SkipMarkerEntity?, deLaSerie: SkipMarkerEntity?): SkipMarkerEntity? =
        delCapitulo?.takeIf { it.tieneTiempos } ?: deLaSerie?.takeIf { it.tieneTiempos }

    private val SkipMarkerEntity.tieneTiempos: Boolean
        get() = openingEndMs != null || endingStartMs != null
}
```

- [ ] **Paso 4: Cambiar la entidad, la migración y el DAO**

En `Entities.kt`, reemplazar `SkipMarkerEntity` por:

```kotlin
/**
 * Tiempos de intro/outro. La llave es derivada (`"<itemId>|<episodeId>"`, ver
 * [com.arkiv.player.data.MarcadorDeCapitulo.idDe]) porque el sync empuja cada colección por UN
 * campo natural y una clave compuesta rompería ese mecanismo.
 *
 * [episodeId] vacío = vale para toda la serie: es el marcador que se pone a mano en el diálogo.
 */
@Entity(tableName = "skip_markers")
data class SkipMarkerEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val episodeId: String = "",
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)
```

En `ArkivDatabase.kt`: subir `version = 27` a `version = 28`, añadir la migración después de `MIGRATION_26_27` y sumarla a `addMigrations(...)`:

```kotlin
        /**
         * v27 -> v28: los marcadores pasan a ser por capítulo. La tabla se RECREA en vez de
         * migrarse: en producción tiene 0 filas (verificado contra PocketBase el 2026-08-19), así
         * que no hay nada que preservar — y recrearla evita inventar un `id` para filas viejas.
         */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS skip_markers")
                db.execSQL(
                    "CREATE TABLE skip_markers (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "itemId TEXT NOT NULL, " +
                        "episodeId TEXT NOT NULL DEFAULT '', " +
                        "openingStartMs INTEGER, openingEndMs INTEGER, endingStartMs INTEGER, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "deleted INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }
```

En `Daos.kt`, `SkipMarkerDao`: cambiar las consultas que filtran por `itemId` para que devuelvan el par que necesita `elegir`:

```kotlin
    /** El del capítulo y el de la serie, en una sola consulta. `elegir` decide cuál manda. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    fun observeDeCapitulo(itemId: String, episodeId: String): Flow<List<SkipMarkerEntity>>

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    suspend fun getDeCapitulo(itemId: String, episodeId: String): List<SkipMarkerEntity>
```

Dejar `get(itemId)`/`observe(itemId)` apuntando al de la serie:

```kotlin
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun get(itemId: String): SkipMarkerEntity?

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    fun observe(itemId: String): Flow<SkipMarkerEntity?>
```

y `softDeleteMarker(itemId)` pasa a `WHERE itemId = :itemId` (borra la serie entera, que es lo que hace hoy al quitar un ítem).

- [ ] **Paso 5: Ajustar quien crea marcadores a mano**

Buscar dónde se guarda el marcador del diálogo y pasarle los campos nuevos:

```bash
grep -rn "SkipMarkerEntity(" app/src/main/java
```

En cada sitio, construirlo con `id = MarcadorDeCapitulo.idDe(itemId, "")`, `itemId = itemId`, `episodeId = ""`.

- [ ] **Paso 6: Sync**

En `SyncMappers.kt`:

```kotlin
fun markerToFields(entity: SkipMarkerEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    // `markerId` es el campo natural con el que el sync busca la fila remota: tiene que ser el
    // mismo que la PK local, o cada aparato crearía una fila nueva en cada empuje.
    "markerId" to entity.id,
    "itemId" to entity.itemId,
    "episodeId" to entity.episodeId,
    "openingStartMs" to entity.openingStartMs,
    "openingEndMs" to entity.openingEndMs,
    "endingStartMs" to entity.endingStartMs,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToMarker(json: JSONObject): SkipMarkerEntity {
    val itemId = json.optString("itemId")
    val episodeId = json.optString("episodeId")
    return SkipMarkerEntity(
        // Un gateway/registro viejo no manda `markerId`: se recalcula igual, para que una fila
        // creada antes de este cambio no entre con la llave en blanco.
        id = json.optStringOrNull("markerId") ?: MarcadorDeCapitulo.idDe(itemId, episodeId),
        itemId = itemId,
        episodeId = episodeId,
        openingStartMs = json.optLongOrNull("openingStartMs"),
        openingEndMs = json.optLongOrNull("openingEndMs"),
        endingStartMs = json.optLongOrNull("endingStartMs"),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optBoolean("deleted"),
    )
}
```

En `CloudSyncManager.kt`, en el `when (col)` de `keyField`, cambiar `COL_MARKERS -> "itemId"` por `COL_MARKERS -> "markerId"`, y actualizar el KDoc de `pushRows` (`markers=itemId` → `markers=markerId`). La lambda `key = { it.itemId }` de la llamada de `COL_MARKERS` pasa a `key = { it.id }`.

- [ ] **Paso 7: PocketBase**

Primero averiguar el id interno de la colección `markers` (las migraciones lo referencian por id, no por nombre):

```bash
ssh blog 'cd ~/arkiv-api && set -a && . ./.env && set +a && TOKEN=$(curl -s -X POST "$POCKETBASE_URL/api/collections/_superusers/auth-with-password" -H "Content-Type: application/json" -d "{\"identity\":\"$POCKETBASE_ADMIN_EMAIL\",\"password\":\"$POCKETBASE_ADMIN_PASSWORD\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)[\"token\"])") && curl -s "$POCKETBASE_URL/api/collections/markers" -H "Authorization: $TOKEN" | python3 -c "import sys,json; c=json.load(sys.stdin); print(\"id:\", c[\"id\"]); print(\"campos:\", [f[\"name\"] for f in c[\"fields\"]]); print(\"indices:\", c.get(\"indexes\"))"'
```

Escribir el archivo, reemplazando `PBC_MARKERS` por el id que devolvió el comando de arriba:

```javascript
/// <reference path="../pb_data/types.d.ts" />
migrate((app) => {
  const collection = app.findCollectionByNameOrId("PBC_MARKERS")

  // La llave natural con la que el sync busca la fila: "<itemId>|<episodeId>".
  collection.fields.add(new Field({
    "autogeneratePattern": "", "help": "", "hidden": false, "id": "text3901884412",
    "max": 0, "min": 0, "name": "markerId", "pattern": "", "presentable": false,
    "primaryKey": false, "required": false, "system": false, "type": "text"
  }))

  // Vacío = el marcador vale para toda la serie (el que se pone a mano).
  collection.fields.add(new Field({
    "autogeneratePattern": "", "help": "", "hidden": false, "id": "text2087341166",
    "max": 0, "min": 0, "name": "episodeId", "pattern": "", "presentable": false,
    "primaryKey": false, "required": false, "system": false, "type": "text"
  }))

  // El índice único pasa de (accountId, itemId) a (accountId, markerId): ahora hay una fila por
  // CAPÍTULO, así que el viejo rechazaría el segundo capítulo de cada serie.
  collection.indexes = [
    "CREATE UNIQUE INDEX `idx_markers_cuenta_marker` ON `markers` (`accountId`, `markerId`)"
  ]

  return app.save(collection)
}, (app) => {
  const collection = app.findCollectionByNameOrId("PBC_MARKERS")
  collection.fields.removeById("text3901884412")
  collection.fields.removeById("text2087341166")
  collection.indexes = [
    "CREATE UNIQUE INDEX `idx_markers_cuenta_item` ON `markers` (`accountId`, `itemId`)"
  ]
  return app.save(collection)
})
```

Subir con marca de tiempo (las migraciones se aplican en orden por el prefijo), reiniciar y verificar:

```bash
TS=$(ssh blog 'date +%s')
scp mig_markers.js "blog:~/pocketbase/pb_migrations/${TS}_updated_markers_por_capitulo.js"
ssh blog 'systemctl --user restart pocketbase && sleep 4 && systemctl --user is-active pocketbase'
cp mig_markers.js /Users/cristian/arkiv-api/deploy/pocketbase/pb_migrations/
```

Comprobar que el campo quedó (repetir el comando del principio del paso: `markerId` y `episodeId` tienen que salir en la lista de campos).

- [ ] **Paso 8: Correr toda la suite**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, sin tests rojos.

- [ ] **Paso 9: Commit**

```bash
cd /Users/cristian/archive
git add app/src/main/java/com/arkiv/player/data app/src/main/java/com/arkiv/player/cloudsync app/src/test/java/com/arkiv/player/data/MarcadorDeCapituloTest.kt
git commit -m "feat(marcadores): los tiempos de intro/outro pasan a ser por capítulo"
```

---

### Tarea 6: La app pide los marcadores al reproducir

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` (junto a `episodesConSerie`, ~L141)
- Create: `app/src/main/java/com/arkiv/player/data/marcadores/BuscadorDeMarcadores.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (junto a `agregadorDeRecomendaciones`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (donde se resuelve el episodio a reproducir)
- Create: `app/src/test/java/com/arkiv/player/data/marcadores/MarcadoresDelGatewayTest.kt`

**Interfaces:**
- Consumes: `MarcadorDeCapitulo.idDe` (Tarea 5); `GET /v1/marcadores` (Tarea 4).
- Produces: `ArkivApiClient.marcadores(tmdbId: Int, temporada: Int, episodio: Int): GatewayMarcadores?`; `BuscadorDeMarcadores.asegurar(itemId, episodeId, tmdbId, temporada, episodio)`.

- [ ] **Paso 1: Escribir el test del parseo**

Crear `app/src/test/java/com/arkiv/player/data/marcadores/MarcadoresDelGatewayTest.kt`:

```kotlin
package com.arkiv.player.data.marcadores

import com.arkiv.player.data.gateway.parseMarcadores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarcadoresDelGatewayTest {

    @Test fun trae_los_tres_tiempos() {
        val m = parseMarcadores("""{"openingStartMs":0,"openingEndMs":90000,"endingStartMs":1319000}""")
        assertEquals(0L, m!!.openingStartMs)
        assertEquals(90_000L, m.openingEndMs)
        assertEquals(1_319_000L, m.endingStartMs)
    }

    /** El gateway contesta `{}` cuando no sabe: no es un error, es "este capítulo no tiene". */
    @Test fun un_objeto_vacio_es_no_hay_marcadores() {
        assertNull(parseMarcadores("{}"))
    }

    /** Media respuesta vale: sale el botón de intro y no el de outro. */
    @Test fun solo_opening_tambien_sirve() {
        val m = parseMarcadores("""{"openingStartMs":0,"openingEndMs":90000}""")
        assertEquals(90_000L, m!!.openingEndMs)
        assertNull(m.endingStartMs)
    }

    @Test fun un_json_roto_no_revienta() {
        assertNull(parseMarcadores("no soy json"))
    }
}
```

- [ ] **Paso 2: Correr y ver fallar**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.marcadores.MarcadoresDelGatewayTest"`
Expected: FAIL, `Unresolved reference 'parseMarcadores'`

- [ ] **Paso 3: Implementar el parseo y el cliente**

En `app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt`:

```kotlin
/** Tiempos de intro/outro de un capítulo, en ms. Null en los que el gateway no supo. */
data class GatewayMarcadores(
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
)

/**
 * `{}` -> null: el gateway contesta un objeto vacío cuando no sabe, y eso no es un error sino
 * "este capítulo no tiene marcadores". Un JSON roto también da null: esto cuelga de una
 * reproducción y no puede tirar el player.
 */
fun parseMarcadores(json: String): GatewayMarcadores? = runCatching {
    val o = JSONObject(json)
    val m = GatewayMarcadores(
        openingStartMs = if (o.has("openingStartMs")) o.getLong("openingStartMs") else null,
        openingEndMs = if (o.has("openingEndMs")) o.getLong("openingEndMs") else null,
        endingStartMs = if (o.has("endingStartMs")) o.getLong("endingStartMs") else null,
    )
    if (m.openingEndMs == null && m.endingStartMs == null) null else m
}.getOrNull()
```

En `ArkivApiClient.kt`, junto a `episodesConSerie`:

```kotlin
    /** Tiempos de intro/outro del capítulo, o null. Nunca lanza: es un extra sobre la reproducción. */
    suspend fun marcadores(tmdbId: Int, temporada: Int, episodio: Int): GatewayMarcadores? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${baseUrl()}/v1/marcadores?tmdbId=$tmdbId&temporada=$temporada&episodio=$episodio"
                parseMarcadores(ejecutar(pedido(url).get().build()))
            }.getOrNull()
        }
```

- [ ] **Paso 4: El buscador que guarda**

Crear `app/src/main/java/com/arkiv/player/data/marcadores/BuscadorDeMarcadores.kt`:

```kotlin
package com.arkiv.player.data.marcadores

import android.util.Log
import com.arkiv.player.data.MarcadorDeCapitulo
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.ArkivApiClient
import kotlinx.coroutines.CancellationException

/**
 * Trae del gateway los tiempos de intro/outro de un capítulo y los guarda.
 *
 * Perezoso: se pide al reproducir, no en un barrido de la serie entera — Dragon Ball son 153
 * capítulos y se ven en orden. Y como `skip_markers` se sincroniza, lo resuelto en el celu ya está
 * en el TV sin volver a preguntar, y sin red la segunda vez.
 */
class BuscadorDeMarcadores(
    private val dao: SkipMarkerDao,
    private val gateway: ArkivApiClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** No devuelve nada: quien dibuja el botón lee la base, que es la fuente única. */
    suspend fun asegurar(itemId: String, episodeId: String, tmdbId: Int, temporada: Int, episodio: Int) {
        if (tmdbId <= 0 || episodio <= 0 || itemId.isBlank() || episodeId.isBlank()) return
        val id = MarcadorDeCapitulo.idDe(itemId, episodeId)
        // Ya preguntado: la fila existe aunque esté vacía. No se vuelve a preguntar por algo que
        // el gateway ya dijo que no sabe -- eso ya lo cachea él, pero la ida de red igual cuesta.
        if (dao.getPorId(id) != null) return
        val m = try {
            gateway.marcadores(tmdbId, temporada, episodio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("ArkivMarcadores", "sin marcadores para $episodeId: ${e.message}")
            return
        } ?: return
        dao.upsert(
            SkipMarkerEntity(
                id = id, itemId = itemId, episodeId = episodeId,
                openingStartMs = m.openingStartMs, openingEndMs = m.openingEndMs,
                endingStartMs = m.endingStartMs, updatedAt = clock(),
            ),
        )
    }
}
```

Añadir a `SkipMarkerDao`:

```kotlin
    @Query("SELECT * FROM skip_markers WHERE id = :id")
    suspend fun getPorId(id: String): SkipMarkerEntity?
```

- [ ] **Paso 5: Cablear**

En `AppGraph.kt`, junto a `agregadorDeRecomendaciones`:

```kotlin
    val buscadorDeMarcadores by lazy {
        com.arkiv.player.data.marcadores.BuscadorDeMarcadores(
            dao = database.skipMarkerDao(),
            gateway = arkivApiClient,
        )
    }
```

En `PlayerViewModel`, donde ya se conocen el ítem y el episodio en curso, lanzar en el scope del ViewModel:

```kotlin
        viewModelScope.launch {
            graph.buscadorDeMarcadores.asegurar(
                itemId = item.identifier,
                episodeId = episodio.id,
                tmdbId = item.tmdbId ?: 0,
                temporada = episodio.season ?: 0,
                episodio = episodio.episode ?: 0,
            )
        }
```

- [ ] **Paso 6: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Paso 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player app/src/test/java/com/arkiv/player/data/marcadores
git commit -m "feat(marcadores): la app pide los tiempos al reproducir y los guarda"
```

---

### Tarea 7: Destapar el botón en TV y en torrent, y verificar en el aparato

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (~L2612 el gate, y el bloque de los botones)

**Interfaces:**
- Consumes: `MarcadorDeCapitulo.elegir` y `SkipMarkerDao.observeDeCapitulo` (Tarea 5).
- Produces: nada (es la punta de la cadena).

- [ ] **Paso 1: Cambiar la fuente del marcador en la pantalla**

Donde hoy se observa el marcador por ítem, pasar a observar el par y elegir:

```kotlin
val marcadoresDelCapitulo by dao.observeDeCapitulo(itemId, episodeId)
    .collectAsStateWithLifecycle(initialValue = emptyList())
val d = MarcadorDeCapitulo.elegir(
    delCapitulo = marcadoresDelCapitulo.firstOrNull { it.episodeId == episodeId },
    deLaSerie = marcadoresDelCapitulo.firstOrNull { it.episodeId.isEmpty() },
)
```

- [ ] **Paso 2: Quitar los dos candados**

```kotlin
// Botones flotantes de saltar intro/outro. Antes solo salían en el teléfono y fuera de torrents:
// los marcadores se ponían a mano por serie y solo para archive. Ahora salen de la identidad de la
// obra (tmdbId + capítulo), así que valen igual en el Fire TV -- que es donde se ve el anime, el
// caso que motivó todo esto -- y en un capítulo bajado por torrent.
if (d != null && !marcadores.marcando && estadoDlna.activo == null) {
```

- [ ] **Paso 3: Compilar y correr la suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Paso 4: Instalar en el Fire TV y verificar**

Comprobar antes que nadie esté viendo algo (`dumpsys window | grep mCurrentFocus` y una captura):

```bash
adb connect 192.168.1.22:5555
cp app/build/outputs/apk/debug/app-debug.apk /tmp/arkiv.apk
adb -s 192.168.1.22:5555 push /tmp/arkiv.apk /data/local/tmp/arkiv.apk
# comparar md5 local contra `adb shell md5sum` ANTES de instalar: por WiFi la transferencia se corrompe
adb -s 192.168.1.22:5555 shell pm install -r /data/local/tmp/arkiv.apk
adb -s 192.168.1.22:5555 shell "am start -n com.arkiv.player/.MainActivity"
```

Reproducir un capítulo de Death Note (4/4 de cobertura) y comprobar los criterios de aceptación 1 y 3 del spec: que el botón aparezca en el momento correcto y que un capítulo sin datos no lo muestre.

- [ ] **Paso 5: Verificar el dato en la base del aparato**

Room usa WAL: hay que bajar los tres archivos o se leen datos viejos.

```bash
cd /tmp
for f in "" "-wal" "-shm"; do
  adb -s 192.168.1.22:5555 exec-out "run-as com.arkiv.player cat databases/arkiv.db$f" > "y.db$f"
done
sqlite3 -header y.db "SELECT id, episodeId, openingStartMs, openingEndMs, endingStartMs FROM skip_markers;"
rm -f y.db y.db-wal y.db-shm   # es la biblioteca personal: no se deja la copia
```

Expected: una fila por capítulo reproducido, con tiempos distintos entre capítulos.

- [ ] **Paso 6: Verificar que el marcador viaja al otro aparato (criterio 5 del spec)**

Es el único criterio de aceptación que no cubre ningún test: el sync solo se puede comprobar con dos aparatos de verdad.

Tras reproducir el capítulo en el Fire TV, mirar que la fila llegó a la nube:

```bash
ssh blog 'cd ~/arkiv-api && set -a && . ./.env && set +a && TOKEN=$(curl -s -X POST "$POCKETBASE_URL/api/collections/_superusers/auth-with-password" -H "Content-Type: application/json" -d "{\"identity\":\"$POCKETBASE_ADMIN_EMAIL\",\"password\":\"$POCKETBASE_ADMIN_PASSWORD\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)[\"token\"])") && curl -s --get "$POCKETBASE_URL/api/collections/markers/records" --data-urlencode "perPage=20" -H "Authorization: $TOKEN" | python3 -m json.tool | head -40'
```

Expected: filas con `markerId`, `episodeId` y los tiempos. Si `markerId` sale vacío, el `keyField` del Paso 6 de la Tarea 5 quedó mal y cada empuje va a crear filas nuevas.

- [ ] **Paso 7: Commit y push**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt
git commit -m "feat(player): saltar intro y outro también en TV y en torrents"
git push origin main
```

---

## Notas para quien ejecute

- **Si la Tarea 1 sale con desfase errático, parar.** No seguir con las demás: el plan entero se apoya en que los tiempos de AniSkip calcen con el video de Magis.
- **`arkiv-api` y `archive` son dos repos distintos.** Las tareas 2, 3 y 4 son del gateway (`/Users/cristian/arkiv-api`); las 5, 6 y 7 de la app (`/Users/cristian/archive`).
- **Otras sesiones comparten el árbol de `archive`.** Nunca `git add -A` ahí: añadir los archivos por nombre, y comprobar la rama antes de commitear.
- **Los 54 errores de `ruff check src/ tests/` en `arkiv-api` son preexistentes.** Lintear solo los archivos tocados.
