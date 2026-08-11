# Canal en vivo (TV IP de Magis) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una sección "En vivo" en Arkiv con los canales de TV IP de Magis: guía de programación en el TV, grilla en el celular, favoritos, zapping y envío al TV/Chromecast.

**Architecture:** El gateway (`arkiv-api`) expone `/v1/live/*`: catálogo y EPG cacheados, resolución del canal y firma de respaldo. El dispositivo levanta un proxy HLS local que reescribe el m3u8 e inyecta `Content-Auth` en cada segmento, **firmando localmente**; si el CDN rechaza su firma de forma repetida, conmuta a pedírselas al gateway. **Los bytes de video nunca cruzan blog.**

**Tech Stack:** arkiv-api (Python 3.14, FastAPI, Redis, pytest) · Arkiv app (Kotlin, Compose, Room, OkHttp, libVLC).

## El punto de partida: la firma ya está resuelta

`magia` ya reimplementó el MD5 tweakeado en **Python puro** (`/Users/cristian/magia/tweaked_md5.py`, commit `42548e5`): 108 líneas de aritmética de 32 bits, **sin `unicorn` y sin `libranger-jni.so`**. Verificado: los 5 vectores reales dan 5/5 con `unicorn` bloqueado y el `.so` fuera de alcance.

El tweak completo, respecto de un MD5 de manual, son dos cosas:

1. El message schedule de la **1ª vuelta** es `[10,11,12,13,14,15,6,7,8,9,0,1,2,3,4,5]`. Las vueltas 2–4 son las estándar.
2. Cuatro constantes K cambiadas — rondas **42** (`d46f3085`), **45** (`e6bd99e5`), **54** (`ffecc47d`) y **62** (`2da7d2bb`) — con pinta de erratas de transcripción del MD5 original.

Todo lo demás (IV, F/G/H/I, shifts, padding little-endian, Davies-Meyer) es MD5 estándar. Por eso este plan **no tiene fase de ingeniería inversa**: la Tarea 1 copia ese archivo al gateway y la Tarea 2 lo porta a Kotlin.

## Global Constraints

- **Los bytes de video no cruzan el gateway.** blog es un NUC Celeron N3050 en swap. El gateway solo mueve JSON.
- **El portal de Magis corta a 1 llamada cada 1,5 s**, con bucket global en Redis (`store/ratelimit.py`). Aplica a `categories`, `channels`, `epg` y `resolve`. **No** aplica a `sign`.
- **Toda llamada al portal pasa por `session.throttle()` + `asyncio.to_thread`**, como `MagisAdapter._intento` (`adapters/magis/adapter.py:210`). El cliente vendorizado es síncrono.
- **El `.so` propietario y `unicorn` no entran a ningún lado**: ni a git, ni al APK, ni a blog. La firma es aritmética pura en los dos lados. El binario se queda en la máquina de Cristian como oráculo de los tests de `magia`, que es donde puede avisar si Magis cambia el algoritmo.
- **Commits sin coautoría de Claude**, identidad `lordmacu` (ya configurada en ambos repos).
- Textos de UI en español, tuteo, como el resto de la app ("En vivo", "Ahora", "A continuación").
- Dos repos distintos: `/Users/cristian/arkiv-api` y `/Users/cristian/archive`. Cada tarea dice en cuál trabaja. **Nunca `git add -A`** en `/Users/cristian/archive`: hay varias sesiones compartiendo el working tree.

---

# Fase 0 — La firma, en los dos lados

## Task 1: `tweaked_md5` y `sign_o3` en el gateway

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Create: `src/arkiv_api/adapters/magis/tweaked_md5.py` (copia de `/Users/cristian/magia/tweaked_md5.py`)
- Create: `src/arkiv_api/adapters/magis/sign_o3.py`
- Test: `tests/test_magis_sign_o3.py`

**Interfaces:**
- Consumes: nada. `tweaked_md5.py` no tiene dependencias — solo `struct`.
- Produce: `sign_o3(token: str, start_moment: int) -> str` (32 hex minúsculas) y `SALT: bytes`. Lo usa la Tarea 5.

- [ ] **Step 1: Escribir el test con los 5 vectores reales**

```python
from arkiv_api.adapters.magis.sign_o3 import sign_o3

# Capturados de la app en vivo (magia/sign_o3.py:213). Son el criterio de aceptacion:
# si los cinco pasan, la firma es correcta y no hay nada que adivinar.
VECTORES = [
    ("941d98961990d67e249dcd1ac57378c8", 1786228951248, "42eda1217c11706f8034f00831f11645"),
    ("941d98961990d67e249dcd1ac57378c8", 1786229028826, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
    ("941d98961990d67e249dcd1ac57378c8", 1786229709567, "0cccdfc85f900a6ee407eedd13003494"),
    ("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615, "2e055d6f2c0407c82017286e8f4a31ad"),
    ("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689, "095a0c6ebc25e6570705fd9d16c6b67b"),
]


def test_los_cinco_vectores_reales():
    for token, momento, esperado in VECTORES:
        assert sign_o3(token, momento) == esperado, f"momento {momento}"


def test_no_arrastra_unicorn_ni_el_binario_propietario():
    """El gateway corre en blog: nada de dependencias nativas ni blobs de 8 MB."""
    import arkiv_api.adapters.magis.sign_o3 as s
    import arkiv_api.adapters.magis.tweaked_md5 as t

    for modulo in (s, t):
        fuente = open(modulo.__file__).read()
        assert "unicorn" not in fuente.lower()
        assert "libranger" not in fuente.lower()
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_sign_o3.py -v`
Expected: FAIL con `ModuleNotFoundError: arkiv_api.adapters.magis.sign_o3`

- [ ] **Step 3: Copiar `tweaked_md5.py` tal cual**

```bash
cp /Users/cristian/magia/tweaked_md5.py /Users/cristian/arkiv-api/src/arkiv_api/adapters/magis/tweaked_md5.py
```

**Copiar, no reescribir.** Ese archivo ya está verificado contra el binario en 200 bloques aleatorios y en las fronteras de padding; reimplementarlo "más lindo" solo puede romperlo. Lo único que se le agrega es una línea al docstring diciendo de dónde vino:

```python
# Copiado de magia/tweaked_md5.py (commit 42548e5). Si Magis cambia el algoritmo, el
# oraculo para re-derivarlo (Unicorn + libranger-jni.so) vive alla, no aca.
```

- [ ] **Step 4: Escribir el envoltorio `sign_o3.py`**

```python
"""Firma `sign2` de los segmentos de TV en vivo de Magis.

El algoritmo es un MD5 con el message schedule de la 1a vuelta cambiado y cuatro
constantes K distintas; ver `tweaked_md5.py`. Los cinco vectores capturados de la app
son el test de aceptacion.
"""
from __future__ import annotations

import time

from .tweaked_md5 import digest_hex

SALT = b"salt3333=4" + bytes.fromhex("980d0a1532c9c3821708c0")


def sign_o3(token: str, start_moment: int) -> str:
    """`sign2` (32 hex minusculas) para un token de sesion y un momento en ms."""
    msg = (f"token={token}&sign2_method=sign_o3&instance=0"
           f"&start_moment={start_moment}").encode() + SALT
    return digest_hex(msg)


def now_moment_ms() -> int:
    return int(time.time() * 1000)
```

- [ ] **Step 5: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_sign_o3.py -v`
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/adapters/magis/tweaked_md5.py src/arkiv_api/adapters/magis/sign_o3.py tests/test_magis_sign_o3.py && git commit -m "feat(live): sign_o3 en el gateway, sin unicorn ni binario propietario"
```

---

## Task 2: `TweakedMd5` en Kotlin, para firmar en el dispositivo

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/TweakedMd5.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/TweakedMd5Test.kt`

**Interfaces:**
- Consumes: nada. Es aritmética de 32 bits pura.
- Produce: `object TweakedMd5` con `fun digestHex(msg: ByteArray): String` y `fun signO3(token: String, startMoment: Long): String`. Lo usa la Tarea 8.

Es el mismo algoritmo de la Tarea 1, en Kotlin. **Ojo con dos cosas** que en Python son gratis y acá no: los enteros de Kotlin son con signo (hay que usar `ushr` y máscaras) y `Int` desborda en silencio, que es justo lo que se quiere en MD5.

- [ ] **Step 1: Escribir el test con los mismos 5 vectores**

```kotlin
package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TweakedMd5Test {
    /** Los mismos vectores que verifican la implementación de Python, capturados de la app real. */
    private val vectores = listOf(
        Triple("941d98961990d67e249dcd1ac57378c8", 1786228951248L, "42eda1217c11706f8034f00831f11645"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229028826L, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229709567L, "0cccdfc85f900a6ee407eedd13003494"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615L, "2e055d6f2c0407c82017286e8f4a31ad"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689L, "095a0c6ebc25e6570705fd9d16c6b67b"),
    )

    @Test
    fun `los cinco vectores reales`() {
        vectores.forEach { (token, momento, esperado) ->
            assertEquals("momento $momento", esperado, TweakedMd5.signO3(token, momento))
        }
    }

    @Test
    fun `las fronteras del padding no se corren`() {
        // 55 y 56 bytes son el borde donde el padding pasa a necesitar un bloque extra;
        // un error de un byte ahí no lo detectan los vectores, que miden ~120 bytes.
        listOf(0, 55, 56, 63, 64, 65).forEach { n ->
            assertEquals("largo $n", 32, TweakedMd5.digestHex(ByteArray(n)).length)
        }
    }

    @Test
    fun `no es MD5 estandar`() {
        // Si alguien "arregla" las constantes tweakeadas creyendo que son erratas, esto lo caza.
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ByteArray(64)).joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertNotEquals(md5, TweakedMd5.digestHex(ByteArray(64)))
    }
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*TweakedMd5Test*"`
Expected: FAIL de compilación — `TweakedMd5` no existe.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.playback

/**
 * El MD5 modificado con el que Magis firma cada segmento de TV en vivo.
 *
 * Respecto de un MD5 de manual cambian **solo dos cosas** (derivadas emulando el binario
 * propietario; ver `magia/tweaked_md5.py`, que es la referencia y está verificada contra
 * el `.so` en 200 bloques aleatorios):
 *
 * 1. El message schedule de la 1ª vuelta es [ROUND1], no `0..15`. Las vueltas 2–4 son estándar.
 * 2. Cuatro constantes K distintas — rondas 42, 45, 54 y 62 — con pinta de erratas de
 *    transcripción del MD5 original.
 *
 * IV, funciones F/G/H/I, shifts, padding little-endian y feed-forward son los de MD5.
 * Los cinco vectores capturados de la app son el test de aceptación.
 */
object TweakedMd5 {
    private val SALT = "salt3333=4".toByteArray() +
        byteArrayOf(0x98.toByte(), 0x0d, 0x0a, 0x15, 0x32, 0xc9.toByte(),
                    0xc3.toByte(), 0x82.toByte(), 0x17, 0x08, 0xc0.toByte())

    private val K = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x0944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f,
    )

    // El tweak: cuatro constantes cambiadas. Se escriben en hexadecimal literal para que
    // se puedan cotejar de un vistazo contra la tabla del docstring de tweaked_md5.py.
    private val KT = K.copyOf().also {
        it[42] = 0xd46f3085.toInt()   // estándar d4ef3085
        it[45] = 0xe6bd99e5.toInt()   // estándar e6db99e5
        it[54] = 0xffecc47d.toInt()   // estándar ffeff47d
        it[62] = 0x2da7d2bb.toInt()   // estándar 2ad7d2bb
    }

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** El tweak: el schedule de la 1ª vuelta. Las otras tres son las fórmulas estándar. */
    private val ROUND1 = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)

    private val G = IntArray(64) { i ->
        when {
            i < 16 -> ROUND1[i]
            i < 32 -> (5 * i + 1) % 16
            i < 48 -> (3 * i + 5) % 16
            else -> (7 * i) % 16
        }
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun compress(estado: IntArray, bloque: ByteArray, off: Int) {
        val m = IntArray(16) { j ->
            val p = off + j * 4
            (bloque[p].toInt() and 0xff) or
                ((bloque[p + 1].toInt() and 0xff) shl 8) or
                ((bloque[p + 2].toInt() and 0xff) shl 16) or
                ((bloque[p + 3].toInt() and 0xff) shl 24)
        }
        var a = estado[0]; var b = estado[1]; var c = estado[2]; var d = estado[3]
        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val suma = f + a + KT[i] + m[G[i]]
            a = d; d = c; c = b
            b += rotl(suma, S[i])
        }
        estado[0] += a; estado[1] += b; estado[2] += c; estado[3] += d
    }

    fun digestHex(msg: ByteArray): String {
        // IV de MD5: 67452301 efcdab89 98badcfe 10325476, en little-endian.
        val estado = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)
        val resto = msg.size % 64
        var i = 0
        while (i + 64 <= msg.size - resto) { compress(estado, msg, i); i += 64 }

        val cola = msg.copyOfRange(msg.size - resto, msg.size)
        val relleno = ByteArray(((56 - (cola.size + 1)) % 64 + 64) % 64)
        val bits = msg.size.toLong() * 8
        val largo = ByteArray(8) { ((bits ushr (it * 8)) and 0xff).toByte() }
        val final = cola + byteArrayOf(0x80.toByte()) + relleno + largo
        var j = 0
        while (j < final.size) { compress(estado, final, j); j += 64 }

        val sb = StringBuilder(32)
        estado.forEach { palabra ->
            for (b in 0 until 4) sb.append("%02x".format((palabra ushr (b * 8)) and 0xff))
        }
        return sb.toString()
    }

    /** `sign2` para un token de sesión y un momento en milisegundos. */
    fun signO3(token: String, startMoment: Long): String = digestHex(
        "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMoment"
            .toByteArray() + SALT
    )
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*TweakedMd5Test*"`
Expected: 3 passed.

Si los vectores fallan, el sospechoso número uno es la tabla `K` en complemento a dos: verificarla generándola con `(Math.abs(Math.sin(i + 1.0)) * 4294967296.0).toLong().toInt()` y comparando contra la literal antes de tocar cualquier otra cosa.

- [ ] **Step 5: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/playback/TweakedMd5.kt app/src/test/java/com/arkiv/player/playback/TweakedMd5Test.kt && git commit -m "feat(vivo): MD5 tweakeado de Magis en Kotlin, verificado con los 5 vectores"
```

---


# Fase 1 — El gateway sirve el vivo

## Task 3: Adapter de catálogo en vivo (categorías y canales)

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Create: `src/arkiv_api/adapters/magis/live.py`
- Test: `tests/test_magis_live.py`

**Interfaces:**
- Consumes: `MagisSessions.for_account(cuenta)` y el patrón `_intento` de `adapters/magis/adapter.py:210`. Del cliente vendorizado: `live_categories()`, `live_data(column_id, page, size)`.
- Produce: `MagisLive` con `async categorias() -> list[dict]` (`{"id": int, "nombre": str}`) y `async canales(column_id: int, page: int, size: int) -> list[dict]` (`{"code": str, "nombre": str, "numero": int, "logo": str|None}`). Lo usan las Tareas 4, 5 y 6.

- [ ] **Step 1: Escribir el test**

```python
import fakeredis.aioredis
import pytest

from arkiv_api.adapters.magis.live import MagisLive
from arkiv_api.adapters.magis.session import MagisSessions
from arkiv_api.store.ratelimit import TokenBucket
from tests.test_magis_session import ClienteFalso

MAESTRA = "x" * 32


class ClienteConVivo(ClienteFalso):
    llamadas: list = []

    def live_categories(self):
        ClienteConVivo.llamadas.append("categories")
        return {"recommendList": [
            {"columnId": 76206, "name": "Deportes"},
            {"columnId": 76205, "name": "Cine y Series"},
        ]}

    def live_data(self, column_id=76182, page=1, size=1000, data_version="", expire=""):
        ClienteConVivo.llamadas.append(f"data:{column_id}")
        return {"channelList": [
            {"channelCode": "cyx_abc_720p", "name": "ESPN", "channelNumber": 501,
             "posterList": [{"url": "http://logo/espn.png"}]},
            {"channelCode": "cyx_def_720p", "name": "TNT Sports", "channelNumber": 502},
        ]}


@pytest.fixture(autouse=True)
def _reset():
    ClienteConVivo.llamadas = []


def _live() -> MagisLive:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    sessions = MagisSessions(redis, TokenBucket(redis, "magis", 1), ClienteConVivo, MAESTRA)
    return MagisLive(sessions, redis)


@pytest.mark.asyncio
async def test_categorias_normaliza_id_y_nombre():
    cats = await _live().categorias("anon")
    assert cats == [{"id": 76206, "nombre": "Deportes"},
                    {"id": 76205, "nombre": "Cine y Series"}]


@pytest.mark.asyncio
async def test_canales_normaliza_y_tolera_la_falta_de_logo():
    chans = await _live().canales("anon", 76206)
    assert chans[0] == {"code": "cyx_abc_720p", "nombre": "ESPN", "numero": 501,
                        "logo": "http://logo/espn.png"}
    assert chans[1]["logo"] is None


@pytest.mark.asyncio
async def test_la_segunda_llamada_sale_de_la_cache():
    live = _live()
    await live.categorias("anon")
    await live.categorias("anon")
    assert ClienteConVivo.llamadas.count("categories") == 1
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_live.py -v`
Expected: FAIL con `ModuleNotFoundError: arkiv_api.adapters.magis.live`

- [ ] **Step 3: Implementar**

```python
"""Catalogo de TV en vivo de Magis: categorias y canales.

Cachea fuerte a proposito. El portal corta a 1 llamada cada 1.5 s y el catalogo de
canales cambia de tanto en tanto, no minuto a minuto: pedirlo en cada apertura de la
seccion gastaria el presupuesto de llamadas que necesita la EPG.
"""
from __future__ import annotations

import asyncio
import json
import logging

_log = logging.getLogger(__name__)

_TTL_CATALOGO_S = 6 * 3600


def _logo(canal: dict) -> str | None:
    """La imagen del canal, si el portal la manda.

    NO esta confirmado que `channelList[]` traiga logo: el CLI de magia solo pinta
    nombre y numero. Se lee de forma tolerante y la UI cae a numero+nombre si falta.
    """
    for campo in ("logo", "icon", "logoUrl"):
        v = canal.get(campo)
        if isinstance(v, str) and v.strip():
            return v
    posters = canal.get("posterList") or []
    if posters and isinstance(posters[0], dict):
        u = posters[0].get("url")
        if isinstance(u, str) and u.strip():
            return u
    return None


class MagisLive:
    def __init__(self, sessions, redis) -> None:
        self._sessions = sessions
        self._r = redis

    async def _llamar(self, cuenta: str, metodo: str, *args, **kwargs):
        session = self._sessions.for_account(cuenta)
        cliente = await session.client()
        await session.throttle()
        return await asyncio.to_thread(getattr(cliente, metodo), *args, **kwargs)

    async def _cacheado(self, clave: str, produce):
        crudo = await self._r.get(clave)
        if crudo:
            return json.loads(crudo)
        valor = await produce()
        await self._r.set(clave, json.dumps(valor), ex=_TTL_CATALOGO_S)
        return valor

    async def categorias(self, cuenta: str) -> list[dict]:
        async def pedir():
            r = await self._llamar(cuenta, "live_categories")
            return [{"id": int(c.get("columnId", 0)), "nombre": c.get("name", "")}
                    for c in (r or {}).get("recommendList", []) if c.get("columnId")]

        return await self._cacheado("live:cats", pedir)

    async def canales(self, cuenta: str, column_id: int, page: int = 1,
                      size: int = 500) -> list[dict]:
        async def pedir():
            r = await self._llamar(cuenta, "live_data", column_id=column_id,
                                   page=page, size=size)
            return [{"code": c.get("channelCode", ""), "nombre": c.get("name", ""),
                     "numero": int(c.get("channelNumber") or 0), "logo": _logo(c)}
                    for c in (r or {}).get("channelList", []) if c.get("channelCode")]

        return await self._cacheado(f"live:chans:{column_id}:{page}:{size}", pedir)
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_live.py -v`
Expected: 3 passed.

- [ ] **Step 5: Anotar si el portal manda logo**

Este es el punto donde se resuelve la incógnita del spec. Con el gateway corriendo:

```bash
curl -s -H "X-Arkiv-Key: $ARKIV_KEY" "https://arkiv-api.../v1/live/channels?category=76206" | head -c 400
```

(Correrlo después de la Tarea 4, que expone la ruta.) Si `logo` viene `null` en todos, anotarlo en el spec y confirmar que la UI usa número+nombre.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/adapters/magis/live.py tests/test_magis_live.py && git commit -m "feat(live): catalogo de canales en vivo con cache de 6 h"
```

---

## Task 4: Rutas `/v1/live/categories` y `/v1/live/channels`

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Create: `src/arkiv_api/router/live.py`
- Modify: `src/arkiv_api/app.py:177` (agregar `live` al import y a la tupla de routers), `src/arkiv_api/app.py:174` (nuevo `app.state.live = None`)
- Test: `tests/test_router_live.py`

**Interfaces:**
- Consumes: `MagisLive` (Tarea 3), `require_key` de `..auth`, el patrón `_cuenta(request)` de `router/magis.py:29`.
- Produce: `GET /v1/live/categories` → `{"categorias": [...]}`; `GET /v1/live/channels?category=&page=&size=` → `{"canales": [...]}`. Los consume la Tarea 7.

- [ ] **Step 1: Escribir el test**

```python
import fakeredis.aioredis
from fastapi.testclient import TestClient

from arkiv_api.adapters.magis.live import MagisLive
from arkiv_api.adapters.magis.session import MagisSessions
from arkiv_api.app import create_app
from arkiv_api.config import Settings
from arkiv_api.store.ratelimit import TokenBucket
from tests.test_magis_live import ClienteConVivo

KEY = "k"
CAB = {"X-Arkiv-Key": KEY}
MAESTRA = "x" * 32


def _client(con_live: bool = True) -> TestClient:
    app = create_app(Settings(arkiv_api_keys=KEY, ref_signing_key=MAESTRA).validated())
    if con_live:
        redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
        sessions = MagisSessions(redis, TokenBucket(redis, "magis", 1), ClienteConVivo, MAESTRA)
        app.state.magis_sessions = sessions
        app.state.live = MagisLive(sessions, redis)
    else:
        app.state.live = None
    return TestClient(app)


def test_sin_llave_da_401():
    assert _client().get("/v1/live/categories").status_code == 401


def test_categorias():
    r = _client().get("/v1/live/categories", headers=CAB)
    assert r.status_code == 200
    assert {"id": 76206, "nombre": "Deportes"} in r.json()["categorias"]


def test_canales_de_una_categoria():
    r = _client().get("/v1/live/channels", params={"category": 76206}, headers=CAB)
    assert r.status_code == 200
    assert r.json()["canales"][0]["code"] == "cyx_abc_720p"


def test_sin_magis_configurado_da_503():
    r = _client(con_live=False).get("/v1/live/categories", headers=CAB)
    assert r.status_code == 503
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_router_live.py -v`
Expected: FAIL con `ModuleNotFoundError: arkiv_api.router.live`

- [ ] **Step 3: Implementar el router**

```python
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from ..auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])

_COLUMNA_TODOS = 76182  # "ChannelList": todos los canales del inicio


def _cuenta(request: Request) -> str:
    return request.headers.get("X-Arkiv-Account", "") or "anon"


def _live(request: Request):
    v = getattr(request.app.state, "live", None)
    if v is None:
        raise HTTPException(status_code=503, detail="magis no esta configurado")
    return v


@router.get("/live/categories")
async def categories(request: Request) -> dict:
    return {"categorias": await _live(request).categorias(_cuenta(request))}


@router.get("/live/channels")
async def channels(
    request: Request,
    category: int = Query(default=_COLUMNA_TODOS),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=500, ge=1, le=1000),
) -> dict:
    canales = await _live(request).canales(_cuenta(request), category, page, size)
    return {"canales": canales}
```

- [ ] **Step 4: Cablearlo en `app.py`**

En `src/arkiv_api/app.py`, agregar `live` al import de routers (línea 23) y a la tupla del `for` (línea 177):

```python
from .router import anime, catalog, health, live, magis, resolve, search, sources, stats, stream
```

```python
    for r in (health, search, resolve, sources, catalog, stats, stream, magis, anime, live):
```

Junto a `app.state.magis_sessions = None` (línea 174), agregar `app.state.live = None`. Y dentro del `lifespan`, después de que se construyen `magis_sessions`:

```python
        from .adapters.magis.live import MagisLive

        app.state.live = (
            MagisLive(app.state.magis_sessions, app.state.redis)
            if app.state.magis_sessions is not None
            else None
        )
```

- [ ] **Step 5: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_router_live.py tests/test_app_wiring.py -v`
Expected: todo passed. `test_app_wiring` cubre que el router quedó registrado.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/router/live.py src/arkiv_api/app.py tests/test_router_live.py && git commit -m "feat(live): rutas de categorias y canales en vivo"
```

---

## Task 5: `POST /v1/live/resolve` y `POST /v1/live/sign`

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Modify: `src/arkiv_api/adapters/magis/live.py` (agregar `resolver`), `src/arkiv_api/router/live.py` (dos rutas)
- Test: `tests/test_magis_live.py` (agregar), `tests/test_router_live.py` (agregar)

**Interfaces:**
- Consumes: `sign_o3` (Tarea 2); del cliente vendorizado `play_live(channel_code)` y `get_slb(type_="merge", live_codes=[code])`.
- Produce: `MagisLive.resolver(cuenta, code) -> {"cflHost","authBase","license","channel","expiresAt"}`; rutas `POST /v1/live/resolve` (cuerpo `{"channel": str}`) y `POST /v1/live/sign` (cuerpo `{"token": str, "count": int, "spread_ms": int}`) → `{"firmas": [{"moment": int, "sign2": str}]}`. Los consume la Tarea 7.

- [ ] **Step 1: Escribir los tests**

En `tests/test_magis_live.py`, agregar al `ClienteConVivo`:

```python
    def play_live(self, channel_code, column_id=0, type_="1"):
        ClienteConVivo.llamadas.append(f"play:{channel_code}")
        return {"liveAddressList": [{"license": "LIC-123", "playCode": "pc"}]}

    def get_slb(self, type_="merge", live_codes=None, has_pay="0"):
        ClienteConVivo.llamadas.append("slb")
        return {"cdn_list": [
            {"tag": "vod", "main_addr": "http://vodhost", "url_list": [{"url": "x"}]},
            {"tag": "live", "main_addr": "http://niguof.vynbszicd.com",
             "url_list": [{"url": "http://x/?a=1&sign_type=cfl&token=" + "A" * 32}]},
        ]}
```

Y los casos:

```python
@pytest.mark.asyncio
async def test_resolver_saca_host_token_y_licencia_de_la_entrada_cfl():
    r = await _live().resolver("anon", "cyx_abc_720p")
    assert r["cflHost"] == "niguof.vynbszicd.com"
    assert r["license"] == "LIC-123"
    assert "sign_type=cfl" in r["authBase"]
    assert r["channel"] == "cyx_abc_720p"


@pytest.mark.asyncio
async def test_resolver_sin_entrada_cfl_falla_claro():
    class SinCfl(ClienteConVivo):
        def get_slb(self, type_="merge", live_codes=None, has_pay="0"):
            return {"cdn_list": [{"tag": "vod", "main_addr": "http://v", "url_list": []}]}

    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    live = MagisLive(MagisSessions(redis, TokenBucket(redis, "magis", 1), SinCfl, MAESTRA), redis)
    with pytest.raises(RuntimeError, match="cfl"):
        await live.resolver("anon", "cyx_abc_720p")
```

En `tests/test_router_live.py`:

```python
def test_resolve_devuelve_lo_que_el_proxy_necesita():
    r = _client().post("/v1/live/resolve", json={"channel": "cyx_abc_720p"}, headers=CAB)
    assert r.status_code == 200
    for campo in ("cflHost", "authBase", "license", "channel", "expiresAt"):
        assert campo in r.json()


def test_sign_devuelve_el_lote_pedido_y_no_toca_el_portal():
    from tests.test_magis_live import ClienteConVivo

    ClienteConVivo.llamadas = []
    r = _client().post("/v1/live/sign", json={"token": "a" * 32, "count": 5}, headers=CAB)
    assert r.status_code == 200
    firmas = r.json()["firmas"]
    assert len(firmas) == 5
    assert all(len(f["sign2"]) == 32 for f in firmas)
    assert sorted(f["moment"] for f in firmas) == [f["moment"] for f in firmas]
    assert ClienteConVivo.llamadas == []


def test_sign_topa_el_lote_para_que_nadie_pida_diez_mil():
    r = _client().post("/v1/live/sign", json={"token": "a" * 32, "count": 9999}, headers=CAB)
    assert r.status_code == 422
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_live.py tests/test_router_live.py -v`
Expected: FAIL — `AttributeError: 'MagisLive' object has no attribute 'resolver'` y 404 en las rutas nuevas.

- [ ] **Step 3: Implementar `resolver` en `live.py`**

```python
import re
import time

_TTL_SESION_CANAL_S = 300  # conservador: el token del cfl no declara vencimiento


    async def resolver(self, cuenta: str, code: str) -> dict:
        """Todo lo que el proxy del dispositivo necesita para hablar con el CDN.

        Dos llamadas al portal, ~3 s por el rate-limit. El `main_addr` ROTA en cada
        llamada, asi que esto no se cachea: un host viejo da 403.
        """
        pl = await self._llamar(cuenta, "play_live", code)
        addrs = (pl or {}).get("liveAddressList") or []
        if not addrs:
            raise RuntimeError(f"play_live sin liveAddressList para {code}")
        license_str = addrs[0].get("license", "")

        slb = await self._llamar(cuenta, "get_slb", type_="merge", live_codes=[code])
        for cdn in (slb or {}).get("cdn_list", []):
            if cdn.get("tag") != "live":
                continue
            for u in cdn.get("url_list", []):
                url = u.get("url", "")
                if "sign_type=cfl" not in url:
                    continue
                host = (cdn.get("main_addr", "")
                        .replace("http://", "").replace("https://", "").split("/")[0])
                return {
                    "cflHost": host,
                    "authBase": url,
                    "license": license_str,
                    "channel": code,
                    "expiresAt": int(time.time()) + _TTL_SESION_CANAL_S,
                }
        raise RuntimeError(f"no hay entrada CDN cfl para el canal {code}")
```

- [ ] **Step 4: Implementar las rutas**

En `router/live.py`:

```python
import time

from pydantic import BaseModel, Field

from ..adapters.magis.sign_o3 import sign_o3


class ResolveIn(BaseModel):
    channel: str


class SignIn(BaseModel):
    token: str
    count: int = Field(default=1, ge=1, le=64)
    # Separacion entre momentos consecutivos. 0 = todos "ahora": es el modo seguro
    # mientras no este verificado que el CDN acepta momentos futuros.
    spread_ms: int = Field(default=0, ge=0, le=60_000)


@router.post("/live/resolve")
async def resolve(body: ResolveIn, request: Request) -> dict:
    try:
        return await _live(request).resolver(_cuenta(request), body.channel)
    except RuntimeError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e


@router.post("/live/sign")
async def sign(body: SignIn, request: Request) -> dict:
    """Firma pura: no habla con el portal, asi que no gasta turno del rate-limit."""
    _live(request)  # mismo 503 que el resto si magis no esta configurado
    ahora = int(time.time() * 1000)
    firmas = [
        {"moment": ahora + i * body.spread_ms,
         "sign2": sign_o3(body.token, ahora + i * body.spread_ms)}
        for i in range(body.count)
    ]
    return {"firmas": firmas}
```

- [ ] **Step 5: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_live.py tests/test_router_live.py -v`
Expected: todo passed.

- [ ] **Step 6: Verificar contra el portal real si el CDN acepta momentos futuros**

**Ya no es bloqueante**: el dispositivo firma en el instante (Tarea 2), así que esto solo decide el tamaño del lote del **camino de respaldo**. Si no se puede correr ahora, dejar `lote = 1` y seguir. Con el gateway desplegado:

```bash
python3 - <<'EOF'
import time, requests
BASE, KEY = "https://arkiv-api...", "..."
h = {"X-Arkiv-Key": KEY}
canal = requests.get(f"{BASE}/v1/live/channels", headers=h).json()["canales"][0]["code"]
r = requests.post(f"{BASE}/v1/live/resolve", json={"channel": canal}, headers=h).json()
tok = r["authBase"].split("token=")[1][:32]
for adelanto in (0, 30_000, 120_000):
    mom = int(time.time()*1000) + adelanto
    s = requests.post(f"{BASE}/v1/live/sign", json={"token": tok, "count": 1}, headers=h).json()
    auth = f"{r['authBase']}&sign2_method=sign_o3&instance=0&start_moment={mom}&sign2={s['firmas'][0]['sign2']}"
    resp = requests.get(f"http://{r['cflHost']}/live/{canal}.m3u8",
                        headers={"Content-Auth": auth, "Content-License": r["license"],
                                 "User-Agent": "Ranger/4.9.4-17294ac0"}, timeout=15)
    print(f"adelanto={adelanto}ms -> {resp.status_code}")
EOF
```

Anotar el resultado en el spec. Si los adelantados dan 200, la Tarea 8 pide lotes de 20 con `spread_ms` de unos segundos; si dan 403, pide `count=1` por ventana. **El código de la Tarea 8 sirve para los dos casos**: solo cambian dos constantes.

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/adapters/magis/live.py src/arkiv_api/router/live.py tests/test_magis_live.py tests/test_router_live.py && git commit -m "feat(live): resolve del canal y firma de segmentos"
```

---

## Task 6: EPG con caché en Redis y worker de fondo

**Repo:** `/Users/cristian/arkiv-api`

**Files:**
- Create: `src/arkiv_api/adapters/magis/epg.py`
- Modify: `src/arkiv_api/router/live.py` (ruta `/live/epg`), `src/arkiv_api/app.py` (arrancar el worker en el `lifespan`)
- Test: `tests/test_magis_epg.py`

**Interfaces:**
- Consumes: `MagisLive._llamar` (Tarea 3); del cliente vendorizado `epg(channel_code, column_id=0, type_="2")`.
- Produce: `EpgStore` con `async programacion(cuenta, codes: list[str]) -> {"epg": {code: [prog]}, "missing": [code]}` y `async worker()`. Cada `prog` es `{"titulo": str, "inicio": int, "fin": int, "sinopsis": str}` con tiempos en epoch **segundos**. Ruta `GET /v1/live/epg?channels=a,b,c`. Lo consume la Tarea 7.

- [ ] **Step 1: Escribir el test**

```python
import asyncio

import fakeredis.aioredis
import pytest

from arkiv_api.adapters.magis.epg import EpgStore
from arkiv_api.adapters.magis.session import MagisSessions
from arkiv_api.store.ratelimit import TokenBucket
from tests.test_magis_live import ClienteConVivo

MAESTRA = "x" * 32


class ClienteConEpg(ClienteConVivo):
    pedidos: list = []

    def epg(self, channel_code, column_id=0, type_="2"):
        ClienteConEpg.pedidos.append(channel_code)
        return {"programList": [
            {"name": "Partido", "startTime": 1786230000, "endTime": 1786237200,
             "introduce": "Fecha 5"},
        ]}


@pytest.fixture(autouse=True)
def _reset():
    ClienteConEpg.pedidos = []


def _store() -> EpgStore:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    sessions = MagisSessions(redis, TokenBucket(redis, "magis", 1), ClienteConEpg, MAESTRA)
    return EpgStore(sessions, redis)


@pytest.mark.asyncio
async def test_lo_que_no_esta_en_cache_sale_como_missing_sin_bloquear():
    r = await _store().programacion("anon", ["a", "b"])
    assert r["epg"] == {}
    assert sorted(r["missing"]) == ["a", "b"]
    assert ClienteConEpg.pedidos == []  # no llamo al portal en caliente


@pytest.mark.asyncio
async def test_el_worker_llena_la_cache_y_la_segunda_lectura_la_encuentra():
    store = _store()
    await store.programacion("anon", ["a"])          # encola
    await store.procesar_pendientes("anon", limite=5)  # una vuelta del worker
    r = await store.programacion("anon", ["a"])
    assert r["missing"] == []
    assert r["epg"]["a"][0] == {"titulo": "Partido", "inicio": 1786230000,
                                "fin": 1786237200, "sinopsis": "Fecha 5"}


@pytest.mark.asyncio
async def test_los_favoritos_se_atienden_antes_que_el_resto():
    store = _store()
    await store.programacion("anon", ["z1", "z2", "z3"])
    await store.priorizar(["z3"])
    await store.procesar_pendientes("anon", limite=1)
    assert ClienteConEpg.pedidos == ["z3"]


@pytest.mark.asyncio
async def test_mil_canales_no_disparan_mil_llamadas_de_golpe():
    store = _store()
    await store.programacion("anon", [f"c{i}" for i in range(1000)])
    await store.procesar_pendientes("anon", limite=10)
    assert len(ClienteConEpg.pedidos) == 10
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/test_magis_epg.py -v`
Expected: FAIL con `ModuleNotFoundError: arkiv_api.adapters.magis.epg`

- [ ] **Step 3: Implementar**

```python
"""Programacion (EPG) de los canales en vivo.

`v3/getProgram` es UNA llamada por canal y el portal corta a 1 cada 1.5 s: barrer
1.000 canales toma ~25 minutos. Por eso la lectura NUNCA llama al portal — devuelve
lo que hay en Redis y encola lo que falta — y un worker de fondo va llenando la
cache respetando el ritmo, atendiendo primero lo que alguien pidio de verdad.
"""
from __future__ import annotations

import asyncio
import json
import logging

_log = logging.getLogger(__name__)

_TTL_EPG_S = 3 * 3600
_COLA = "live:epg:cola"        # lista: pendientes normales
_COLA_PRIO = "live:epg:prio"   # lista: favoritos y lo que se acaba de pedir


def _programa(p: dict) -> dict:
    return {
        "titulo": p.get("name", ""),
        "inicio": int(p.get("startTime") or 0),
        "fin": int(p.get("endTime") or 0),
        "sinopsis": p.get("introduce", "") or "",
    }


class EpgStore:
    def __init__(self, sessions, redis) -> None:
        self._sessions = sessions
        self._r = redis

    async def _llamar(self, cuenta: str, metodo: str, *args, **kwargs):
        session = self._sessions.for_account(cuenta)
        cliente = await session.client()
        await session.throttle()
        return await asyncio.to_thread(getattr(cliente, metodo), *args, **kwargs)

    async def programacion(self, cuenta: str, codes: list[str]) -> dict:
        """Lee de Redis y encola lo que falte. No llama al portal: no bloquea nunca."""
        epg: dict[str, list] = {}
        faltan: list[str] = []
        for code in codes:
            crudo = await self._r.get(f"live:epg:{code}")
            if crudo:
                epg[code] = json.loads(crudo)
            else:
                faltan.append(code)
        if faltan:
            await self.priorizar(faltan)
        return {"epg": epg, "missing": faltan}

    async def priorizar(self, codes: list[str]) -> None:
        """Al frente de la cola de prioridad, sin repetir."""
        pendientes = set(await self._r.lrange(_COLA_PRIO, 0, -1))
        nuevos = [c for c in codes if c not in pendientes]
        if nuevos:
            await self._r.rpush(_COLA_PRIO, *nuevos)

    async def procesar_pendientes(self, cuenta: str, limite: int = 10) -> int:
        """Una vuelta del worker: hasta `limite` canales, prioridad primero."""
        hechos = 0
        while hechos < limite:
            code = await self._r.lpop(_COLA_PRIO) or await self._r.lpop(_COLA)
            if not code:
                break
            try:
                r = await self._llamar(cuenta, "epg", code)
                progs = [_programa(p) for p in (r or {}).get("programList", [])]
                await self._r.set(f"live:epg:{code}", json.dumps(progs), ex=_TTL_EPG_S)
            except Exception as e:  # noqa: BLE001 — un canal que falla no frena la cola
                _log.warning("epg de %s fallo: %s", code, e)
            hechos += 1
        return hechos

    async def worker(self, cuenta: str = "anon") -> None:
        """Vive todo el proceso. El ritmo real lo pone el bucket de 1.5 s."""
        while True:
            try:
                hechos = await self.procesar_pendientes(cuenta, limite=10)
            except Exception as e:  # noqa: BLE001
                _log.warning("worker de epg: %s", e)
                hechos = 0
            if hechos == 0:
                await asyncio.sleep(5)  # cola vacia: no quemar CPU
```

- [ ] **Step 4: Agregar la ruta**

En `router/live.py`:

```python
def _epg(request: Request):
    v = getattr(request.app.state, "epg", None)
    if v is None:
        raise HTTPException(status_code=503, detail="magis no esta configurado")
    return v


@router.get("/live/epg")
async def epg(request: Request, channels: str = Query(default="")) -> dict:
    codes = [c for c in channels.split(",") if c.strip()]
    if not codes:
        return {"epg": {}, "missing": []}
    return await _epg(request).programacion(_cuenta(request), codes)
```

En `app.py`, junto a `app.state.live`, agregar `app.state.epg = None`, y dentro del `lifespan`:

```python
        from .adapters.magis.epg import EpgStore

        app.state.epg = (
            EpgStore(app.state.magis_sessions, app.state.redis)
            if app.state.magis_sessions is not None
            else None
        )
        tarea_epg = asyncio.create_task(app.state.epg.worker()) if app.state.epg else None
```

y en el cierre del `lifespan`, antes de los `aclose()`:

```python
        if tarea_epg is not None:
            tarea_epg.cancel()
```

(`import asyncio` ya al tope del módulo si no está.)

- [ ] **Step 5: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/arkiv-api && uv run pytest tests/ -v`
Expected: toda la suite passed, incluidos los tests viejos.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/arkiv-api && git add src/arkiv_api/adapters/magis/epg.py src/arkiv_api/router/live.py src/arkiv_api/app.py tests/test_magis_epg.py && git commit -m "feat(live): EPG cacheada en Redis con worker de fondo priorizado"
```

- [ ] **Step 7: Desplegar y probar de punta a punta**

El gateway **no se despliega con `git pull`**: es rsync + build.

```bash
cd /Users/cristian/arkiv-api && rsync -az --delete --exclude .git --exclude .venv ./ blog:~/arkiv-api/ && ssh blog 'cd ~/arkiv-api && docker compose up -d --build'
```

Verificar:

```bash
curl -s -H "X-Arkiv-Key: $ARKIV_KEY" "https://arkiv-api.../v1/live/categories" | head -c 300
```

---

# Fase 2 — El dispositivo reproduce

## Task 7: Cliente del gateway para el vivo

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/gateway/LiveApi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/gateway/LiveApiTest.kt`

**Interfaces:**
- Consumes: `OkHttpClient`, y el patrón de cabeceras de `ArkivApiClient.pedido()` (`data/gateway/ArkivApiClient.kt:59`).
- Produce:
  - `data class LiveChannel(val code: String, val nombre: String, val numero: Int, val logo: String?)`
  - `data class LiveCategory(val id: Int, val nombre: String)`
  - `data class LiveProgram(val titulo: String, val inicio: Long, val fin: Long, val sinopsis: String)`
  - `data class LiveSession(val cflHost: String, val authBase: String, val license: String, val channel: String, val expiresAt: Long)`
  - `data class LiveSignature(val moment: Long, val sign2: String)`
  - `class LiveApi(baseUrl, apiKey, http, magisAccountId)` con `suspend fun categorias(): List<LiveCategory>`, `suspend fun canales(categoria: Int): List<LiveChannel>`, `suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>>`, `suspend fun resolver(code: String): LiveSession`, `suspend fun firmar(token: String, count: Int, spreadMs: Long): List<LiveSignature>`.
  - Las usan las Tareas 8, 11, 12, 13.

- [ ] **Step 1: Escribir el test con `MockWebServer`**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveApiTest {
    private fun api(server: MockWebServer) = LiveApi(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        apiKey = { "k" },
        http = OkHttpClient(),
    )

    @Test
    fun `canales sin logo quedan en null`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"canales":[{"code":"c1","nombre":"ESPN","numero":501,"logo":null}]}"""
        ))
        server.start()
        val canales = api(server).canales(76206)
        assertEquals("ESPN", canales[0].nombre)
        assertEquals(501, canales[0].numero)
        assertNull(canales[0].logo)
        server.shutdown()
    }

    @Test
    fun `la llave del gateway viaja en la cabecera`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"categorias":[]}"""))
        server.start()
        api(server).categorias()
        assertEquals("k", server.takeRequest().getHeader("X-Arkiv-Key"))
        server.shutdown()
    }

    @Test
    fun `epg separa lo que llego de lo que falta`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"epg":{"c1":[{"titulo":"Partido","inicio":100,"fin":200,"sinopsis":"s"}]},"missing":["c2"]}"""
        ))
        server.start()
        val (epg, faltan) = api(server).epg(listOf("c1", "c2"))
        assertEquals("Partido", epg["c1"]!![0].titulo)
        assertEquals(listOf("c2"), faltan)
        server.shutdown()
    }

    @Test
    fun `firmar pide el lote y devuelve los pares`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"firmas":[{"moment":1,"sign2":"aa"},{"moment":2,"sign2":"bb"}]}"""
        ))
        server.start()
        val firmas = api(server).firmar("t", 2, 0)
        assertEquals(2, firmas.size)
        assertEquals("bb", firmas[1].sign2)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveApiTest*"`
Expected: FAIL de compilación — `LiveApi` no existe.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LiveCategory(val id: Int, val nombre: String)

data class LiveChannel(val code: String, val nombre: String, val numero: Int, val logo: String?)

/** Tiempos en epoch **segundos**, como los manda el portal. */
data class LiveProgram(val titulo: String, val inicio: Long, val fin: Long, val sinopsis: String)

/**
 * Lo que el proxy local necesita para hablarle al CDN. Es la excepción consciente al
 * patrón de `ref` opaco del gateway: acá la app sí necesita los datos en claro para
 * armar las cabeceras de cada segmento.
 */
data class LiveSession(
    val cflHost: String,
    val authBase: String,
    val license: String,
    val channel: String,
    val expiresAt: Long,
) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

data class LiveSignature(val moment: Long, val sign2: String)

class LiveApi(
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val http: OkHttpClient,
    private val magisAccountId: () -> String? = { null },
) {
    private val json = "application/json".toMediaType()

    private fun pedido(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("X-Arkiv-Key", apiKey())
        magisAccountId()?.takeIf { it.isNotBlank() }?.let { b.header("X-Arkiv-Account", it) }
        return b
    }

    private suspend fun cuerpo(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val texto = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GatewayException("live: el gateway respondio ${r.code}")
            JSONObject(texto)
        }
    }

    private fun <T> JSONArray.mapear(f: (JSONObject) -> T): List<T> =
        (0 until length()).map { f(getJSONObject(it)) }

    suspend fun categorias(): List<LiveCategory> =
        cuerpo(pedido("${baseUrl()}/v1/live/categories").get().build())
            .getJSONArray("categorias")
            .mapear { LiveCategory(it.getInt("id"), it.getString("nombre")) }

    suspend fun canales(categoria: Int): List<LiveChannel> {
        val url = "${baseUrl()}/v1/live/channels".toHttpUrl().newBuilder()
            .addQueryParameter("category", categoria.toString())
            .build().toString()
        return cuerpo(pedido(url).get().build()).getJSONArray("canales").mapear {
            LiveChannel(
                code = it.getString("code"),
                nombre = it.getString("nombre"),
                numero = it.optInt("numero"),
                logo = it.optString("logo").takeIf { s -> s.isNotBlank() && s != "null" },
            )
        }
    }

    suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        if (codes.isEmpty()) return emptyMap<String, List<LiveProgram>>() to emptyList()
        val url = "${baseUrl()}/v1/live/epg".toHttpUrl().newBuilder()
            .addQueryParameter("channels", codes.joinToString(","))
            .build().toString()
        val o = cuerpo(pedido(url).get().build())
        val epg = o.getJSONObject("epg")
        val mapa = epg.keys().asSequence().associateWith { code ->
            epg.getJSONArray(code).mapear {
                LiveProgram(
                    titulo = it.optString("titulo"),
                    inicio = it.optLong("inicio"),
                    fin = it.optLong("fin"),
                    sinopsis = it.optString("sinopsis"),
                )
            }
        }
        val faltan = o.getJSONArray("missing").let { a -> (0 until a.length()).map { a.getString(it) } }
        return mapa to faltan
    }

    suspend fun resolver(code: String): LiveSession {
        val req = pedido("${baseUrl()}/v1/live/resolve")
            .post(JSONObject(mapOf("channel" to code)).toString().toRequestBody(json)).build()
        val o = cuerpo(req)
        return LiveSession(
            cflHost = o.getString("cflHost"),
            authBase = o.getString("authBase"),
            license = o.getString("license"),
            channel = o.getString("channel"),
            expiresAt = o.optLong("expiresAt"),
        )
    }

    suspend fun firmar(token: String, count: Int, spreadMs: Long): List<LiveSignature> {
        val req = pedido("${baseUrl()}/v1/live/sign").post(
            JSONObject(mapOf("token" to token, "count" to count, "spread_ms" to spreadMs))
                .toString().toRequestBody(json)
        ).build()
        return cuerpo(req).getJSONArray("firmas")
            .mapear { LiveSignature(it.getLong("moment"), it.getString("sign2")) }
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveApiTest*"`
Expected: 4 passed.

- [ ] **Step 5: Cablearlo en `AppGraph`**

En `app/src/main/java/com/arkiv/player/AppGraph.kt`, junto a `arkivApiClient` (línea 68):

```kotlin
    val liveApi: com.arkiv.player.data.gateway.LiveApi by lazy {
        com.arkiv.player.data.gateway.LiveApi(
            baseUrl = { settings.arkivApiBaseUrl.value },
            apiKey = { settings.arkivApiKey.value },
            http = okHttp,
            magisAccountId = { accountManager.magisAccountId() },
        )
    }
```

Copiar los nombres exactos de `baseUrl`, `okHttp` y `magisAccountId` de cómo los pasa `arkivApiClient` en las líneas 68-75; si difieren, mandan los de ahí.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/data/gateway/LiveApi.kt app/src/test/java/com/arkiv/player/data/gateway/LiveApiTest.kt app/src/main/java/com/arkiv/player/AppGraph.kt && git commit -m "feat(vivo): cliente del gateway para canales, EPG y firma"
```

---

## Task 8: `LiveHlsProxy` — proxy HLS local que firma en el dispositivo

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/playback/LiveHlsProxy.kt`, `app/src/main/java/com/arkiv/player/playback/FirmaDeSegmentos.kt`
- Test: `app/src/test/java/com/arkiv/player/playback/LiveHlsProxyTest.kt`, `app/src/test/java/com/arkiv/player/playback/FirmaDeSegmentosTest.kt`

**Interfaces:**
- Consumes: `TweakedMd5.signO3` (Tarea 2), `LiveApi.firmar` y `LiveSession` (Tarea 7). Sigue el patrón de servidor de `ArchiveCacheProxy.start()` (`playback/ArchiveCacheProxy.kt:118`).
- Produce:
  - `interface FirmaDeSegmentos { suspend fun firmar(token: String): LiveSignature; fun rechazada() {} }`
  - `class FirmaLocal : FirmaDeSegmentos` — firma con `TweakedMd5` en el instante.
  - `class FirmaDelGateway(api: LiveApi, lote: Int = 1, spreadMs: Long = 0) : FirmaDeSegmentos` — pide lotes al gateway.
  - `class FirmaConRespaldo(local: FirmaDeSegmentos, remota: FirmaDeSegmentos, umbral: Int = 2) : FirmaDeSegmentos` — usa la local y, tras `umbral` rechazos seguidos, conmuta a la remota **por lo que resta de la reproducción**; `val usandoRespaldo: Boolean` lo expone para diagnóstico.
  - `class LiveHlsProxy(private val firmas: FirmaDeSegmentos)` con `fun start(bindLan: Boolean = false): Int`, `fun stop()`, `fun urlPara(sesion: LiveSession): String` (no es `suspend`) y `val port: Int`.
  - Lo usan las Tareas 9 y 15.

**Por qué hay dos caminos.** El dispositivo firma solo: es aritmética local, así que no hay ida y vuelta por segmento y el vivo sigue andando aunque el gateway esté caído. El respaldo existe para un caso concreto: si Magis cambia el algoritmo, el gateway se arregla con un redespliegue y los aparatos que no actualizaron el APK siguen funcionando. Como un camino de respaldo que nunca corre es un camino que se pudre, lleva test propio (paso 3) y un ajuste para forzarlo a mano (paso 7).

- [ ] **Step 1: Escribir el test de las estrategias de firma**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmaDeSegmentosTest {
    private class Contadora(private val marca: String) : FirmaDeSegmentos {
        var veces = 0
        override suspend fun firmar(token: String): LiveSignature {
            veces++
            return LiveSignature(veces.toLong(), marca)
        }
    }

    @Test
    fun `la firma local coincide con el algoritmo verificado`() = runBlocking {
        val token = "941d98961990d67e249dcd1ac57378c8"
        val f = FirmaLocal().firmar(token)
        assertEquals(TweakedMd5.signO3(token, f.moment), f.sign2)
    }

    @Test
    fun `mientras nadie rechace, no se le pide nada al gateway`() = runBlocking {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota)
        repeat(10) { f.firmar("t") }
        assertEquals(0, remota.veces)
        assertFalse(f.usandoRespaldo)
    }

    @Test
    fun `tras dos rechazos seguidos conmuta al gateway`() = runBlocking {
        val local = Contadora("local")
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(local, remota, umbral = 2)
        f.firmar("t"); f.rechazada()
        f.firmar("t"); f.rechazada()
        assertEquals("remota", f.firmar("t").sign2)
        assertTrue(f.usandoRespaldo)
    }

    @Test
    fun `un rechazo suelto entre firmas buenas no conmuta`() = runBlocking {
        // Un 403 aislado es una firma que llegó tarde, no un algoritmo roto.
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 2)
        f.firmar("t"); f.rechazada()
        f.firmar("t")            // esta salió bien: el contador vuelve a cero
        f.firmar("t"); f.rechazada()
        assertEquals(0, remota.veces)
    }

    @Test
    fun `una vez en el respaldo se queda ahi`() = runBlocking {
        val remota = Contadora("remota")
        val f = FirmaConRespaldo(Contadora("local"), remota, umbral = 1)
        f.firmar("t"); f.rechazada()
        repeat(3) { f.firmar("t") }
        assertEquals(3, remota.veces)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*FirmaDeSegmentosTest*"`
Expected: FAIL de compilación — `FirmaDeSegmentos` no existe.

- [ ] **Step 3: Implementar las tres estrategias**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** De dónde sale el `sign2` de cada petición al CDN. */
interface FirmaDeSegmentos {
    suspend fun firmar(token: String): LiveSignature

    /** El CDN rechazó la última firma entregada. */
    fun rechazada() {}
}

/** Firma en el aparato. Es aritmética local: ni red, ni espera, ni pool. */
class FirmaLocal : FirmaDeSegmentos {
    override suspend fun firmar(token: String): LiveSignature {
        val momento = System.currentTimeMillis()
        return LiveSignature(momento, TweakedMd5.signO3(token, momento))
    }
}

/**
 * Firma en el gateway. Pide de a lotes para no hacer una llamada por segmento; con
 * [lote] = 1 pide una firma por petición, que es el modo seguro mientras no esté
 * verificado que el CDN acepta `start_moment` futuros (Tarea 5, paso 6).
 */
class FirmaDelGateway(
    private val api: LiveApi,
    private val lote: Int = 1,
    private val spreadMs: Long = 0,
) : FirmaDeSegmentos {
    private val cerrojo = Mutex()
    private val pendientes = ArrayDeque<LiveSignature>()

    override suspend fun firmar(token: String): LiveSignature = cerrojo.withLock {
        if (pendientes.isEmpty()) pendientes.addAll(api.firmar(token, lote, spreadMs))
        if (pendientes.size == 1) pendientes.first() else pendientes.removeFirst()
    }

    override fun rechazada() {
        pendientes.clear()  // lo que quedaba en el lote ya no sirve
    }
}

/**
 * Firma en el aparato y, si el CDN rechaza [umbral] firmas **seguidas**, pasa a pedírselas
 * al gateway por lo que resta de la reproducción.
 *
 * El contador se reinicia con cada firma aceptada a propósito: un 403 aislado es una firma
 * que llegó tarde, no un algoritmo roto. Lo que se quiere detectar es el caso en que Magis
 * cambió la firma — ahí fallan todas, y el gateway (que se arregla con un redespliegue,
 * sin publicar APK) toma la posta.
 */
class FirmaConRespaldo(
    private val local: FirmaDeSegmentos,
    private val remota: FirmaDeSegmentos,
    private val umbral: Int = 2,
) : FirmaDeSegmentos {
    @Volatile var usandoRespaldo: Boolean = false
        private set
    private var rechazosSeguidos = 0

    override suspend fun firmar(token: String): LiveSignature {
        val elegida = if (usandoRespaldo) remota else local
        return elegida.firmar(token).also { if (!usandoRespaldo) rechazosSeguidos = 0 }
    }

    override fun rechazada() {
        if (usandoRespaldo) { remota.rechazada(); return }
        rechazosSeguidos++
        if (rechazosSeguidos >= umbral) usandoRespaldo = true
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*FirmaDeSegmentosTest*"`
Expected: 5 passed.

- [ ] **Step 5: Escribir el test del proxy**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LiveHlsProxyTest {
    /** Firma predecible, para poder afirmar qué `Content-Auth` salió en cada petición. */
    private class FirmasFalsas : FirmaDeSegmentos {
        var entregadas = 0
        var rechazos = 0
        override suspend fun firmar(token: String): LiveSignature {
            entregadas++
            return LiveSignature(1000L, "firma%02d".format(entregadas))
        }
        override fun rechazada() { rechazos++ }
    }

    private fun leer(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val cuerpo = runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
        return c.responseCode to cuerpo
    }

    @Test
    fun `reescribe los segmentos absolutos hacia el propio proxy`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody(
            "#EXTM3U\n#EXTINF:6,\nhttp://seg1.cdn/live/c/c_1.ts\n#EXTINF:6,\nhttp://seg2.cdn/live/c/c_2.ts\n"
        ))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        val puerto = proxy.start()
        val sesion = LiveSession(
            cflHost = "${upstream.hostName}:${upstream.port}",
            authBase = "http://x/?a=1&token=${"A".repeat(32)}",
            license = "LIC", channel = "c", expiresAt = 0,
        )
        val (codigo, cuerpo) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertTrue(cuerpo.contains("http://127.0.0.1:$puerto/seg?u="))
        assertTrue("no debe quedar ninguna URL del CDN sin reescribir", !cuerpo.contains("seg1.cdn/live"))
        assertEquals(2, cuerpo.lines().count { it.startsWith("http://127.0.0.1") })
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `pone las tres cabeceras al pedir el playlist`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setBody("#EXTM3U\n"))
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        leer(proxy.urlPara(sesion))

        val req = upstream.takeRequest()
        assertEquals("LIC", req.getHeader("Content-License"))
        assertEquals("Ranger/4.9.4-17294ac0", req.getHeader("User-Agent"))
        val auth = req.getHeader("Content-Auth")!!
        assertTrue(auth.contains("sign2_method=sign_o3"))
        assertTrue(auth.contains("start_moment=1000"))
        assertTrue(auth.contains("sign2=firma01"))
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `ante un 403 pide firma fresca y reintenta exactamente una vez`() = runBlocking {
        val upstream = MockWebServer()
        var pedidos = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pedidos++
                return if (pedidos == 1) MockResponse().setResponseCode(403)
                else MockResponse().setBody("#EXTM3U\n")
            }
        }
        upstream.start()

        val firmas = FirmasFalsas()
        val proxy = LiveHlsProxy(firmas)
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))

        assertEquals(200, codigo)
        assertEquals("un 403 y su reintento, nada mas", 2, pedidos)
        assertEquals("el 403 se le avisa a la fuente de firmas", 1, firmas.rechazos)
        proxy.stop(); upstream.shutdown()
    }

    @Test
    fun `dos 403 seguidos se rinden en vez de reintentar para siempre`() = runBlocking {
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        upstream.start()

        val proxy = LiveHlsProxy(FirmasFalsas())
        proxy.start()
        val sesion = LiveSession("${upstream.hostName}:${upstream.port}",
            "http://x/?a=1&token=${"A".repeat(32)}", "LIC", "c", 0)
        val (codigo, _) = leer(proxy.urlPara(sesion))
        assertEquals(502, codigo)
        proxy.stop(); upstream.shutdown()
    }
}
```

- [ ] **Step 6: Correr los tests del proxy y verificar que fallan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveHlsProxyTest*"`
Expected: FAIL de compilación — `LiveHlsProxy` no existe.

- [ ] **Step 7: Implementar el proxy**

```kotlin
package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveSession
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Proxy HLS local para la TV en vivo de Magis.
 *
 * Existe porque VLC solo deja pasar `:http-referrer` y `:http-user-agent` (ver
 * [VlcPlayer]), y el CDN del vivo exige `Content-Auth` y `Content-License`. Además el
 * `Content-Auth` **caduca en segundos**: cada segmento necesita una firma fresca, así
 * que no alcanza con entregarle a VLC un m3u8 estático.
 *
 * El proxy baja el playlist, reescribe las URLs absolutas de los `.ts` hacia sí mismo
 * y pone las cabeceras en cada petición al origen. VLC solo ve `127.0.0.1`.
 */
class LiveHlsProxy(private val firmas: FirmaDeSegmentos) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var sesion: LiveSession? = null

    val port: Int get() = server?.localPort ?: -1

    private suspend fun contentAuth(): String {
        val s = sesion ?: error("no hay sesión de canal")
        val f = firmas.firmar(s.token)
        return "${s.authBase}&sign2_method=sign_o3&instance=0" +
            "&start_moment=${f.moment}&sign2=${f.sign2}"
    }

    /** Idempotente, igual que [ArchiveCacheProxy.start]. [bindLan] es para el Chromecast. */
    @Synchronized
    fun start(bindLan: Boolean = false): Int {
        server?.let { if (running && !it.isClosed) return it.localPort }
        val sock = if (bindLan) ServerSocket(0) else ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = sock
        running = true
        Thread {
            while (running && !sock.isClosed) {
                val s = try { sock.accept() } catch (_: Exception) { break }
                Thread { atender(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return sock.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        sesion = null
    }

    /** Fija la sesión del canal y devuelve la URL que se le pasa a VLC. */
    fun urlPara(nueva: LiveSession): String {
        sesion = nueva
        if (port <= 0) start()
        return "http://127.0.0.1:$port/live.m3u8"
    }

    private fun atender(socket: Socket) = socket.use { s ->
        val entrada = s.getInputStream().bufferedReader()
        val linea = entrada.readLine() ?: return
        val ruta = linea.split(" ").getOrNull(1) ?: return
        val salida = s.getOutputStream()
        when {
            ruta.startsWith("/live.m3u8") -> servirPlaylist(salida)
            ruta.startsWith("/seg?") -> servirSegmento(ruta, salida)
            else -> salida.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
    }

    /**
     * Pide al origen con la firma vigente y, ante un 403, refresca la firma y reintenta
     * **una** vez. Un 403 que sobrevive al reintento significa que caducó la sesión del
     * canal, no la firma: quien reproduce debe re-resolver (ver [PlayerViewModel]).
     */
    private fun pedirAlOrigen(url: String): HttpURLConnection? {
        repeat(2) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Auth", runBlocking { contentAuth() })
                setRequestProperty("Content-License", sesion!!.license)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("App", APP)
                setRequestProperty("App-Version", APP_VERSION)
                setRequestProperty("X-Buffer", "0")
            }
            if (c.responseCode != 403) return c
            // El aviso es lo que permite a FirmaConRespaldo detectar que el algoritmo
            // dejó de servir y conmutar al gateway. Sin esto, el respaldo nunca entra.
            firmas.rechazada()
            c.disconnect()
        }
        return null
    }

    private fun servirPlaylist(salida: java.io.OutputStream) {
        val s = sesion ?: return
        val c = pedirAlOrigen("http://${s.cflHost}/live/${s.channel}.m3u8")
        if (c == null || c.responseCode != 200) {
            salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val cuerpo = c.inputStream.bufferedReader().readText().lineSequence().joinToString("\n") { ln ->
            val t = ln.trim()
            if (t.startsWith("http") && t.contains(".ts")) {
                "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(t, "UTF-8")}"
            } else ln
        } + "\n"
        val bytes = cuerpo.toByteArray()
        salida.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
        )
        salida.write(bytes)
    }

    private fun servirSegmento(ruta: String, salida: java.io.OutputStream) {
        val u = URLDecoder.decode(ruta.substringAfter("u=").substringBefore("&"), "UTF-8")
        val c = pedirAlOrigen(u)
        if (c == null) {
            salida.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        salida.write("HTTP/1.1 ${c.responseCode} OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
        runCatching { c.inputStream.copyTo(salida, 64 * 1024) }
    }

    companion object {
        const val UA = "Ranger/4.9.4-17294ac0"
        private const val APP = "com.android.msandroid"
        private const val APP_VERSION = "49902"
    }
}
```

- [ ] **Step 8: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveHlsProxyTest*" --tests "*FirmaDeSegmentosTest*"`
Expected: 9 passed (5 de firma + 4 del proxy).

- [ ] **Step 9: Dejar el respaldo ejercitable a mano**

En Ajustes, un interruptor **"Firmar en el servidor"** que fuerza `FirmaDelGateway` en vez de `FirmaConRespaldo`. Guardarlo en `SettingsStore` junto al resto de las preferencias y leerlo al construir la fuente de firmas en `AppGraph` (Tarea 9, paso 5).

Es la mitigación del riesgo que tiene este diseño: un camino de respaldo que nunca se ejecuta se pudre en silencio y falla justo el día que hace falta. Con el interruptor se puede comprobar en un minuto que el camino del gateway sigue sirviendo, sin esperar a que Magis cambie el algoritmo.

- [ ] **Step 10: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/playback/LiveHlsProxy.kt app/src/main/java/com/arkiv/player/playback/FirmaDeSegmentos.kt app/src/test/java/com/arkiv/player/playback/ && git commit -m "feat(vivo): proxy HLS local que firma en el aparato, con respaldo en el gateway"
```

---

## Task 9: Reproducir un canal en VLC, en modo vivo

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/live/LiveController.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (registrar `liveHlsProxy` y `liveController`)
- Test: `app/src/test/java/com/arkiv/player/ui/live/LiveControllerTest.kt`

**Interfaces:**
- Consumes: `LiveApi` (Tarea 7), `LiveHlsProxy` (Tarea 8).
- Produce: `class LiveController(resolver: suspend (String) -> LiveSession, urlPara: (LiveSession) -> String, ahora: () -> Long = ...)` con `suspend fun abrir(code: String): String` (devuelve la URL local para VLC), `suspend fun precalentar(code: String)`, `fun cerrar()`. **Las dependencias entran como funciones, no como `LiveApi`/`LiveHlsProxy`**, para poder probarlo sin red ni sockets; el cableado real se hace en `AppGraph` (paso 5). Lo usan las Tareas 11, 13, 14, 15.

- [ ] **Step 1: Escribir el test**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControllerTest {
    @Test
    fun `abrir un canal devuelve una url local para VLC`() = runBlocking {
        val ctrl = LiveController(
            resolver = { code -> LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { s -> "http://127.0.0.1:9999/live.m3u8?c=${s.channel}" },
        )
        assertTrue(ctrl.abrir("c1").startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `precalentar el vecino no vuelve a resolver cuando se abre`() = runBlocking {
        var resoluciones = 0
        val ctrl = LiveController(
            resolver = { code -> resoluciones++; LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, 0) },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
        )
        ctrl.precalentar("c2")
        ctrl.abrir("c2")
        assertEquals("el canal precalentado ya estaba resuelto", 1, resoluciones)
    }

    @Test
    fun `una sesion vencida se vuelve a resolver`() = runBlocking {
        var resoluciones = 0
        val ctrl = LiveController(
            resolver = { code ->
                resoluciones++
                LiveSession("h", "http://x/?token=${"A".repeat(32)}", "L", code, expiresAt = 1)
            },
            urlPara = { "http://127.0.0.1:9999/live.m3u8" },
            ahora = { 999_999 },
        )
        ctrl.precalentar("c3")
        ctrl.abrir("c3")
        assertEquals(2, resoluciones)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveControllerTest*"`
Expected: FAIL de compilación.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Abre canales en vivo: resuelve contra el gateway y le entrega a VLC la URL del proxy
 * local.
 *
 * Guarda la última sesión resuelta por canal porque **resolver cuesta ~3 s** (dos
 * llamadas al portal a 1,5 s cada una). Eso es lo que hace que el zapping no se sienta
 * lento: el vecino se precalienta mientras el overlay está quieto.
 *
 * Las dependencias entran como funciones para poder probarlo sin red ni sockets.
 */
class LiveController(
    private val resolver: suspend (String) -> LiveSession,
    private val urlPara: (LiveSession) -> String,
    private val ahora: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Mutex()
    private val sesiones = mutableMapOf<String, LiveSession>()

    private fun vigente(code: String): LiveSession? =
        sesiones[code]?.takeIf { it.expiresAt == 0L || it.expiresAt > ahora() }

    suspend fun abrir(code: String): String {
        val s = lock.withLock {
            vigente(code) ?: resolver(code).also { sesiones[code] = it }
        }
        return urlPara(s)
    }

    /** Best-effort: si falla, el canal se resolverá normalmente al abrirlo. */
    suspend fun precalentar(code: String) {
        if (vigente(code) != null) return
        runCatching { lock.withLock { sesiones[code] = resolver(code) } }
    }

    fun cerrar() = sesiones.clear()
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveControllerTest*"`
Expected: 3 passed.

- [ ] **Step 5: Cablear en `AppGraph`**

```kotlin
    val liveHlsProxy: com.arkiv.player.playback.LiveHlsProxy by lazy {
        val remota = com.arkiv.player.playback.FirmaDelGateway(liveApi)
        // El interruptor de Ajustes (Tarea 8, paso 9) permite forzar el camino del
        // gateway para comprobar que el respaldo sigue vivo.
        val fuente = if (settings.firmarEnServidor.value) remota
        else com.arkiv.player.playback.FirmaConRespaldo(
            local = com.arkiv.player.playback.FirmaLocal(),
            remota = remota,
        )
        com.arkiv.player.playback.LiveHlsProxy(fuente)
    }

    val liveController: com.arkiv.player.ui.live.LiveController by lazy {
        com.arkiv.player.ui.live.LiveController(
            resolver = { code -> liveApi.resolver(code) },
            urlPara = { sesion -> liveHlsProxy.urlPara(sesion) },
        )
    }
```

- [ ] **Step 6: Probar en el celular con un canal real**

Aún no hay UI, así que se prueba desde el reproductor existente. Instalar por WiFi (ver la nota de ADB en las memorias) y usar el camino de "pegar URL" del reproductor con la URL que devuelve `liveController.abrir(code)`, o agregar temporalmente un botón de prueba. **Confirmar que el video arranca y sigue andando más de 2 minutos** — ahí es donde se ve si la firma local aguanta segmento tras segmento.

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/live/LiveController.kt app/src/test/java/com/arkiv/player/ui/live/LiveControllerTest.kt app/src/main/java/com/arkiv/player/AppGraph.kt && git commit -m "feat(vivo): abrir canales en vivo con precalentado del vecino"
```

---

# Fase 3 — La interfaz

## Task 10: Favoritos y recientes en Room, con sync

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Entities.kt` (dos entidades), `Daos.kt` (dos DAOs), `ArkivDatabase.kt:11-25` (entidades + `version = 20`), `SyncTriggers.kt:26` (dos tablas), `app/src/main/java/com/arkiv/player/sync/SyncSnapshot.kt` (incluirlas), `app/src/main/java/com/arkiv/player/sync/SyncMerge.kt` (merge LWW)
- Test: `app/src/test/java/com/arkiv/player/sync/SyncLiveFavoritesTest.kt`

**Interfaces:**
- Produce:
  - `LiveFavoriteEntity(code: String @PrimaryKey, nombre: String, numero: Int, logo: String?, updatedAt: Long = 0, deleted: Boolean = false)`
  - `LiveRecentEntity(code: String @PrimaryKey, nombre: String, vistoAt: Long, updatedAt: Long = 0)`
  - `LiveChannelCacheEntity(code: String @PrimaryKey, categoria: Int, nombre: String, numero: Int, logo: String?, guardadoAt: Long)` — caché local del catálogo, **no** viaja por el sync.
  - `LiveFavoriteDao`: `flowTodos(): Flow<List<LiveFavoriteEntity>>`, `suspend fun guardar(f: LiveFavoriteEntity)`, `suspend fun borrar(code: String)`, `suspend fun esFavorito(code: String): Boolean`.
  - `LiveRecentDao`: `flowUltimos(limite: Int = 20): Flow<List<LiveRecentEntity>>`, `suspend fun anotar(r: LiveRecentEntity)`.
  - `LiveChannelCacheDao`: `suspend fun deCategoria(categoria: Int): List<LiveChannelCacheEntity>`, `suspend fun reemplazar(categoria: Int, filas: List<LiveChannelCacheEntity>)`.
  - Los usan las Tareas 11, 12, 13 y 14.

- [ ] **Step 1: Leer cómo viaja `skip_markers` por el sync**

`skip_markers` es la tabla más simple que ya se sincroniza. Leer los tres puntos donde aparece: `SyncTriggers.kt:30`, `SyncSnapshot.kt:24` y su merge en `SyncMerge.kt`. **Copiar ese patrón exacto**, incluido cómo maneja `updatedAt` y los tombstones — el sync de Arkiv es LWW con tombstones y ya tuvo bugs por desviarse de eso.

- [ ] **Step 2: Escribir el test del merge**

```kotlin
package com.arkiv.player.sync

import com.arkiv.player.data.db.LiveFavoriteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLiveFavoritesTest {
    @Test
    fun `gana la escritura mas nueva`() {
        val local = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 100, deleted = false)
        val remoto = LiveFavoriteEntity("c1", "ESPN HD", 501, null, updatedAt = 200, deleted = false)
        assertEquals("ESPN HD", ganador(local, remoto).nombre)
    }

    @Test
    fun `un borrado mas nuevo no revive por un alta vieja`() {
        val local = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 300, deleted = true)
        val remoto = LiveFavoriteEntity("c1", "ESPN", 501, null, updatedAt = 100, deleted = false)
        assertTrue(ganador(local, remoto).deleted)
    }
}
```

`ganador(a, b)` debe ser la misma función de resolución que ya usa el merge para las otras tablas: si tiene otro nombre, usar ese y ajustar el test. **No escribir una segunda implementación de LWW.**

- [ ] **Step 3: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*SyncLiveFavoritesTest*"`
Expected: FAIL de compilación — las entidades no existen.

- [ ] **Step 4: Agregar entidades, DAOs y la versión de la base**

En `Entities.kt`:

```kotlin
@Entity(tableName = "live_favorites")
data class LiveFavoriteEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** Últimos canales vistos. No lleva tombstone: se poda por antigüedad, no se borra a mano. */
@Entity(tableName = "live_recents")
data class LiveRecentEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val vistoAt: Long,
    val updatedAt: Long = 0,
)

/**
 * Caché local del catálogo de canales, para que la sección abra al instante y siga
 * mostrando la grilla aunque el gateway esté lento o caído. **No viaja por el sync**:
 * es caché reconstruible, no datos del usuario, y meterla al snapshot sería mandar
 * 1.000 filas entre dispositivos para nada.
 */
@Entity(tableName = "live_channels_cache")
data class LiveChannelCacheEntity(
    @PrimaryKey val code: String,
    val categoria: Int,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val guardadoAt: Long,
)
```

En `Daos.kt`:

```kotlin
@Dao
interface LiveFavoriteDao {
    @Query("SELECT * FROM live_favorites WHERE deleted = 0 ORDER BY numero")
    fun flowTodos(): Flow<List<LiveFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(f: LiveFavoriteEntity)

    @Query("UPDATE live_favorites SET deleted = 1, updatedAt = 0 WHERE code = :code")
    suspend fun borrar(code: String)

    @Query("SELECT EXISTS(SELECT 1 FROM live_favorites WHERE code = :code AND deleted = 0)")
    suspend fun esFavorito(code: String): Boolean
}

@Dao
interface LiveRecentDao {
    @Query("SELECT * FROM live_recents ORDER BY vistoAt DESC LIMIT :limite")
    fun flowUltimos(limite: Int = 20): Flow<List<LiveRecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun anotar(r: LiveRecentEntity)
}

@Dao
interface LiveChannelCacheDao {
    @Query("SELECT * FROM live_channels_cache WHERE categoria = :categoria ORDER BY numero")
    suspend fun deCategoria(categoria: Int): List<LiveChannelCacheEntity>

    @Query("DELETE FROM live_channels_cache WHERE categoria = :categoria")
    suspend fun limpiar(categoria: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(filas: List<LiveChannelCacheEntity>)

    @Transaction
    suspend fun reemplazar(categoria: Int, filas: List<LiveChannelCacheEntity>) {
        limpiar(categoria)
        guardar(filas)
    }
}
```

En `ArkivDatabase.kt`: agregar `LiveFavoriteEntity::class`, `LiveRecentEntity::class` y `LiveChannelCacheEntity::class` a `entities`, subir `version = 19` a `version = 20`, y agregar `abstract fun liveFavoriteDao(): LiveFavoriteDao`, `abstract fun liveRecentDao(): LiveRecentDao` y `abstract fun liveChannelCacheDao(): LiveChannelCacheDao`.

En `SyncTriggers.kt:26`, agregar a `TABLAS` **solo las dos que viajan** — la caché no:

```kotlin
        "live_favorites" to "code",
        "live_recents" to "code",
```

- [ ] **Step 5: Sumarlas al snapshot y al merge**

En `SyncSnapshot.kt` y `SyncMerge.kt`, replicar exactamente lo que hacen para `markers`/`skip_markers`: incluir las dos listas en el snapshot que se envía y resolverlas por LWW al recibir. Los nombres de campo son `code` (clave), `updatedAt` (reloj) y `deleted` (tombstone, solo favoritos).

- [ ] **Step 6: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest`
Expected: toda la suite passed. Si Room falla al compilar con KSP, es el problema conocido de JDK: confirmar que `room.generateKotlin` sigue en `gradle.properties`.

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/data/db/ app/src/main/java/com/arkiv/player/sync/ app/src/test/java/com/arkiv/player/sync/SyncLiveFavoritesTest.kt && git commit -m "feat(vivo): favoritos y recientes de canales, sincronizados"
```

---

## Task 11: Celular — pestaña "En vivo" con la grilla de canales

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/live/LiveViewModel.kt`, `app/src/main/java/com/arkiv/player/ui/live/LiveScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt:96-103` (nuevo `Tab`), y el `NavHost` del mismo archivo (nueva ruta `live`)
- Test: `app/src/test/java/com/arkiv/player/ui/live/LiveViewModelTest.kt`

**Interfaces:**
- Consumes: `LiveApi` (Tarea 7), `LiveController` (Tarea 9), `LiveFavoriteDao`/`LiveRecentDao` (Tarea 10).
- Produce: `LiveViewModel(api: LiveApi, favoritosDao: LiveFavoriteDao, cacheDao: LiveChannelCacheDao)` con `val estado: StateFlow<LiveUiState>`, `fun elegirCategoria(id: Int)`, `fun buscar(texto: String)`, `fun alternarFavorito(c: LiveChannel)`, `fun pedirEpgDe(codes: List<String>)`; y `LiveUiState` con `categorias`, `categoriaActiva`, `canales`, `favoritos`, `ahora: Map<String, LiveProgram?>` (el programa en curso, para la grilla), `programacion: Map<String, List<LiveProgram>>` (el día completo, para la guía de la Tarea 12), `busqueda`, `cargando`, `error` y la derivada `visibles`. Lo usa la Tarea 12.

- [ ] **Step 1: Escribir el test del filtrado**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveViewModelTest {
    private val canales = listOf(
        LiveChannel("c1", "ESPN", 501, null),
        LiveChannel("c2", "TNT Sports", 502, null),
        LiveChannel("c3", "Caracol", 101, null),
    )

    @Test
    fun `busca por nombre sin importar mayusculas ni tildes`() {
        assertEquals(listOf("c3"), filtrar(canales, "caracol").map { it.code })
    }

    @Test
    fun `busca por numero de canal`() {
        assertEquals(listOf("c2"), filtrar(canales, "502").map { it.code })
    }

    @Test
    fun `sin texto devuelve todo en el orden que vino`() {
        assertEquals(canales, filtrar(canales, "  "))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveViewModelTest*"`
Expected: FAIL de compilación — `filtrar` no existe.

- [ ] **Step 3: Implementar el ViewModel**

```kotlin
package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

const val CATEGORIA_TODOS = 76182
const val CATEGORIA_FAVORITOS = -1

private fun String.plano(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Por nombre (sin tildes ni mayúsculas) o por número exacto de canal. */
fun filtrar(canales: List<LiveChannel>, texto: String): List<LiveChannel> {
    val q = texto.trim()
    if (q.isEmpty()) return canales
    val plano = q.plano()
    return canales.filter { it.nombre.plano().contains(plano) || it.numero.toString() == q }
}

data class LiveUiState(
    val categorias: List<LiveCategory> = emptyList(),
    val categoriaActiva: Int = CATEGORIA_TODOS,
    val canales: List<LiveChannel> = emptyList(),
    val favoritos: Set<String> = emptySet(),
    /** El programa en curso por canal, para la grilla. */
    val ahora: Map<String, LiveProgram?> = emptyMap(),
    /** El día completo por canal, para la guía (Tarea 12). */
    val programacion: Map<String, List<LiveProgram>> = emptyMap(),
    val busqueda: String = "",
    val cargando: Boolean = false,
    val error: String? = null,
) {
    val visibles: List<LiveChannel> get() = filtrar(canales, busqueda)
}

class LiveViewModel(
    private val api: LiveApi,
    private val favoritosDao: LiveFavoriteDao,
    private val cacheDao: LiveChannelCacheDao,
) : ViewModel() {
    private val _estado = MutableStateFlow(LiveUiState())
    val estado: StateFlow<LiveUiState> = _estado

    private val todosLosCanales = mutableListOf<LiveChannel>()

    init {
        viewModelScope.launch {
            favoritosDao.flowTodos().collect { favs ->
                _estado.update { it.copy(favoritos = favs.map { f -> f.code }.toSet()) }
            }
        }
        cargar(CATEGORIA_TODOS)
    }

    fun elegirCategoria(id: Int) = cargar(id)

    fun buscar(texto: String) = _estado.update { it.copy(busqueda = texto) }

    /**
     * Pinta primero lo que hay en la caché local y después refresca contra el gateway.
     * Así la sección abre al instante y sigue mostrando la grilla si el gateway está
     * lento o caído — en ese caso solo falla al reproducir, con un mensaje concreto.
     */
    private fun cargar(categoria: Int) {
        viewModelScope.launch {
            _estado.update { it.copy(cargando = true, error = null, categoriaActiva = categoria) }

            if (categoria == CATEGORIA_FAVORITOS) {
                val favs = favoritosDao.flowTodos().first()
                val canales = favs.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
                _estado.update { it.copy(canales = canales, cargando = false) }
                pedirEpgDe(canales.take(40).map { it.code })
                return@launch
            }

            val cacheados = cacheDao.deCategoria(categoria)
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
            if (cacheados.isNotEmpty()) {
                todosLosCanales.clear(); todosLosCanales.addAll(cacheados)
                _estado.update { it.copy(canales = cacheados, cargando = false) }
                pedirEpgDe(cacheados.take(40).map { it.code })
            }

            runCatching {
                if (_estado.value.categorias.isEmpty()) {
                    val cats = api.categorias()
                    _estado.update { it.copy(categorias = cats) }
                }
                api.canales(categoria)
            }.onSuccess { frescos ->
                todosLosCanales.clear(); todosLosCanales.addAll(frescos)
                val ahoraMs = System.currentTimeMillis()
                cacheDao.reemplazar(categoria, frescos.map {
                    LiveChannelCacheEntity(it.code, categoria, it.nombre, it.numero, it.logo, ahoraMs)
                })
                _estado.update { it.copy(canales = frescos, cargando = false, error = null) }
                pedirEpgDe(frescos.take(40).map { it.code })
            }.onFailure {
                // Con caché ya pintada, un gateway caído no vacía la pantalla.
                _estado.update {
                    it.copy(
                        cargando = false,
                        error = if (it.canales.isEmpty()) "No se pudo cargar los canales" else null,
                    )
                }
            }
        }
    }

    /**
     * Programación de esos canales: guarda el día completo (lo usa la guía) y deriva el
     * programa en curso (lo usa la grilla). Lo que el gateway todavía no tenga llega en
     * una vuelta posterior; acá no se espera a nadie.
     */
    fun pedirEpgDe(codes: List<String>) {
        val faltantes = codes.filter { it !in _estado.value.programacion }
        if (faltantes.isEmpty()) return
        viewModelScope.launch {
            runCatching { api.epg(faltantes) }.onSuccess { (mapa, _) ->
                val instante = System.currentTimeMillis() / 1000
                val enCurso = mapa.mapValues { (_, progs) ->
                    progs.firstOrNull { p -> instante >= p.inicio && instante < p.fin }
                }
                _estado.update {
                    it.copy(programacion = it.programacion + mapa, ahora = it.ahora + enCurso)
                }
            }
        }
    }

    fun alternarFavorito(c: LiveChannel) {
        viewModelScope.launch {
            if (c.code in _estado.value.favoritos) favoritosDao.borrar(c.code)
            else favoritosDao.guardar(LiveFavoriteEntity(c.code, c.nombre, c.numero, c.logo))
        }
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveViewModelTest*"`
Expected: 3 passed.

- [ ] **Step 5: Escribir la pantalla**

`LiveScreen.kt`: `Scaffold` con campo de búsqueda arriba, `LazyRow` de chips de categoría (con **Favoritos** y **Recientes** de primeros, luego las del gateway), y `LazyVerticalGrid` de tarjetas.

Cada tarjeta: logo con `AsyncImage` si `logo != null`; **si es null, el número grande y el nombre debajo**, con el mismo tratamiento que las tarjetas sin póster de `TvComponents.kt` (`CardPlaceholder`). Debajo, "Ahora: <título>" en `ArkivTextSecondary` con una barra fina de avance (`(ahora - inicio) / (fin - inicio)`), y nada si no hay EPG todavía. `combinedClickable`: tap reproduce, long-press alterna favorito.

Estados: `cargando` → placeholders; `error` → texto con botón "Reintentar"; sin cuenta Magis → texto explicativo con acción a Ajustes.

- [ ] **Step 6: Agregar la pestaña**

En `ArkivRoot.kt`, en `TABS` (línea 96), **después de Inicio**:

```kotlin
    Tab("live", "En vivo") { Icon(Icons.Default.LiveTv, contentDescription = "En vivo") },
```

(`import androidx.compose.material.icons.filled.LiveTv`.) Y en el `NavHost`, un `composable("live") { LiveScreen(...) }` con las mismas dependencias que usan las otras rutas.

- [ ] **Step 7: Probar en el celular**

Instalar por WiFi y verificar: la pestaña aparece junto a Inicio, los chips cambian de categoría, la búsqueda por nombre y por número filtra, el long-press marca favorito y el favorito sobrevive a cerrar y abrir la app. **Confirmar antes que Cristian no esté usando el celular** si se va a automatizar con ADB.

- [ ] **Step 8: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/live/ app/src/main/java/com/arkiv/player/ui/ArkivRoot.kt && git commit -m "feat(vivo): pestana En vivo en el celular con grilla, busqueda y favoritos"
```

---

## Task 12: Celular — guía vertical

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/live/LiveGuideList.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/live/LiveScreen.kt` (botón "Guía"), `LiveViewModel.kt` (cargar la programación del día)

**Interfaces:**
- Consumes: `LiveApi.epg` (Tarea 7), `LiveUiState` (Tarea 11).
- Produce: `@Composable fun LiveGuideList(canales: List<LiveChannel>, programacion: Map<String, List<LiveProgram>>, onVer: (LiveChannel) -> Unit, onPedirEpg: (List<String>) -> Unit)`, más los helpers `enCurso(progs, instante): LiveProgram?` y `avance(p, instante): Float` en `package com.arkiv.player.ui.live`. Los helpers los usa también la Tarea 13.

`programacion` es el mapa del mismo nombre de `LiveUiState` (Tarea 11) y `onPedirEpg` es `viewModel::pedirEpgDe`.

- [ ] **Step 1: Escribir el test de la partición del día**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGuideListTest {
    private val progs = listOf(
        LiveProgram("Anterior", 100, 200, ""),
        LiveProgram("Ahora", 200, 300, ""),
        LiveProgram("Después", 300, 400, ""),
    )

    @Test
    fun `el programa en curso es el que contiene el instante`() {
        assertEquals("Ahora", enCurso(progs, 250)?.titulo)
    }

    @Test
    fun `el borde de fin ya pertenece al siguiente`() {
        assertEquals("Después", enCurso(progs, 300)?.titulo)
    }

    @Test
    fun `fuera de la grilla no hay programa`() {
        assertNull(enCurso(progs, 50))
        assertNull(enCurso(progs, 999))
    }

    @Test
    fun `el avance va de cero a uno`() {
        assertEquals(0.5f, avance(progs[1], 250), 0.001f)
        assertEquals(0f, avance(progs[1], 200), 0.001f)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveGuideListTest*"`
Expected: FAIL de compilación.

- [ ] **Step 3: Implementar los helpers y la lista**

```kotlin
/** El programa que contiene [instante] (epoch segundos), o null si no hay. */
fun enCurso(progs: List<LiveProgram>, instante: Long): LiveProgram? =
    progs.firstOrNull { instante >= it.inicio && instante < it.fin }

/** Cuánto lleva corrido un programa, de 0 a 1. */
fun avance(p: LiveProgram, instante: Long): Float {
    val total = (p.fin - p.inicio).toFloat()
    if (total <= 0f) return 0f
    return ((instante - p.inicio).toFloat() / total).coerceIn(0f, 1f)
}
```

`LiveGuideList`: `LazyColumn` de canales. Cada fila muestra logo/número, nombre y el programa en curso; al tocarla se expande (`AnimatedVisibility`) mostrando la programación del día en una columna de `hora — título`, con el actual resaltado en `ArkivRed`. Un botón "Ver ahora" en la cabecera expandida llama a `onVer`.

`onPedirEpg` se dispara para los códigos visibles cuando el índice del `LazyListState` cambia, y **solo para los que aún no están en el mapa** — así la guía se llena sola sin pedir dos veces lo mismo.

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveGuideListTest*"`
Expected: 4 passed.

- [ ] **Step 5: Enganchar el botón "Guía"**

En `LiveScreen`, un `IconButton` junto al buscador que alterna entre la grilla y `LiveGuideList`, guardando el modo en `rememberSaveable` para que sobreviva a la rotación.

- [ ] **Step 6: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/live/ && git commit -m "feat(vivo): guia vertical de programacion en el celular"
```

---

## Task 13: TV — botón "En vivo" y guía EPG en timeline

**Repo:** `/Users/cristian/archive`

**Files:**
- Create: `app/src/main/java/com/arkiv/player/ui/tv/TvLiveGuideScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt:244-254` (botón), `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt` (ruta `live`)
- Test: `app/src/test/java/com/arkiv/player/ui/tv/TvLiveGuideTest.kt`

**Interfaces:**
- Consumes: `LiveViewModel` (Tarea 11), `LiveProgram` (Tarea 7), `enCurso`/`avance` (Tarea 12), `TvNavButton` (existente en `TvHomeScreen.kt`).
- Produce: `@Composable fun TvLiveGuideScreen(onVerCanal: (LiveChannel) -> Unit, onVolver: () -> Unit)` y los helpers de layout del timeline.

- [ ] **Step 1: Escribir el test del layout del timeline**

```kotlin
package com.arkiv.player.ui.tv

import com.arkiv.player.data.gateway.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLiveGuideTest {
    @Test
    fun `media hora mide la mitad que una hora`() {
        val hora = LiveProgram("h", 0, 3600, "")
        val media = LiveProgram("m", 0, 1800, "")
        assertEquals(anchoDp(hora) / 2, anchoDp(media), 0.01f)
    }

    @Test
    fun `un programa mas corto que el minimo igual se puede enfocar`() {
        assertTrue(anchoDp(LiveProgram("corto", 0, 30, "")) >= 40f)
    }

    @Test
    fun `la ventana arranca en la hora en punto anterior`() {
        // 1786230061 = 00:01:01 de algun dia; la ventana debe arrancar a las 00:00:00
        assertEquals(1786230000L, ventanaDe(1786230061L).first)
    }
}
```

Ajustar la constante `1786230000` a la hora en punto real que corresponda (`ventanaDe` redondea hacia abajo a la hora).

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*TvLiveGuideTest*"`
Expected: FAIL de compilación.

- [ ] **Step 3: Implementar los helpers**

```kotlin
/** Dp por hora del timeline. 300 dp/h deja ver ~4 h en una pantalla de TV de 1280 dp. */
const val DP_POR_HORA = 300f
private const val ANCHO_MINIMO_DP = 40f

/** Ancho del bloque de un programa, con un mínimo para que siempre se pueda enfocar. */
fun anchoDp(p: LiveProgram): Float =
    (((p.fin - p.inicio) / 3600f) * DP_POR_HORA).coerceAtLeast(ANCHO_MINIMO_DP)

/** La ventana visible: desde la hora en punto anterior a [instante], por 6 horas. */
fun ventanaDe(instante: Long): Pair<Long, Long> {
    val inicio = instante - (instante % 3600)
    return inicio to (inicio + 6 * 3600)
}
```

- [ ] **Step 4: Implementar la pantalla**

Estructura: `Column` con chips de categoría arriba (Favoritos y Recientes primero), luego la cabecera de horas, luego un `LazyColumn` de filas de canal. Cada fila es `Row { CanalFijo(280.dp) ; LazyRow(programas) }`, y **todas las `LazyRow` comparten un `scrollState`** para que el tiempo quede alineado entre filas.

La línea del ahora es un `Box` de 2 dp en `ArkivRed` posicionado con `offset(x = ((ahora - ventanaInicio) / 3600f * DP_POR_HORA).dp)`.

D-pad: arriba/abajo mueve el `LazyColumn` (foco entre canales), izquierda/derecha mueve la `LazyRow` compartida; en el borde izquierdo, `dpadFocusEscape()` (ya existe en `TvComponents.kt:55`) devuelve el foco a los chips.

Al enfocar una fila, se pide su EPG si falta. Las filas sin datos muestran un bloque gris con "Cargando programación…" — **nunca un spinner que bloquee**.

OK sobre el programa en curso → `onVerCanal`. OK sobre uno futuro → `AlertDialog` con título, horario, sinopsis y un botón "Ver canal ahora"; sin botón de grabar ni recordatorio, porque no existen.

- [ ] **Step 5: Agregar el botón en el home del TV**

En `TvHomeScreen.kt`, después del botón de "Mi biblioteca" (línea 250):

```kotlin
                    TvNavButton(icon = Icons.Default.LiveTv, label = "En vivo", onClick = onOpenLive)
```

Agregar `onOpenLive: () -> Unit` a la firma de `TvHomeScreen` (línea 93) e `import androidx.compose.material.icons.filled.LiveTv`. En `ArkivTvRoot.kt`, pasar `onOpenLive = { navController.navigate("live") }` y registrar `composable("live") { TvLiveGuideScreen(...) }`.

- [ ] **Step 6: Correr los tests y verificar que pasan**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*TvLiveGuideTest*"`
Expected: 3 passed.

- [ ] **Step 7: Probar en el Fire TV Stick**

Instalar por ADB de red (puerto 5555) y recorrer la guía **solo con el mando**: que el foco no se pierda, que las flechas hagan lo que dice el paso 4, que se pueda volver a los chips y salir con Atrás. Verificar que las filas sin EPG se llenan solas al quedarse quieto unos segundos.

- [ ] **Step 8: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/tv/ app/src/test/java/com/arkiv/player/ui/tv/TvLiveGuideTest.kt && git commit -m "feat(vivo): guia de programacion en el TV con navegacion por mando"
```

---

## Task 14: Reproductor en modo vivo, con zapping

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (modo vivo y zapping), `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (overlay y controles)
- Create: `app/src/main/java/com/arkiv/player/ui/live/LiveZapping.kt`
- Test: `app/src/test/java/com/arkiv/player/ui/live/LiveZappingTest.kt`

**Interfaces:**
- Consumes: `LiveController` (Tarea 9), `LiveRecentDao` (Tarea 10).
- Produce: `class LiveZapping(lista: List<LiveChannel>, indiceInicial: Int)` con `val actual: LiveChannel`, `fun siguiente(): LiveChannel`, `fun anterior(): LiveChannel`, `fun vecinos(): List<LiveChannel>` (vacío si hay un solo canal).

- [ ] **Step 1: Escribir el test**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZappingTest {
    private val lista = listOf(
        LiveChannel("c1", "Uno", 1, null),
        LiveChannel("c2", "Dos", 2, null),
        LiveChannel("c3", "Tres", 3, null),
    )

    @Test
    fun `avanza y da la vuelta al llegar al final`() {
        val z = LiveZapping(lista, 2)
        assertEquals("c1", z.siguiente().code)
    }

    @Test
    fun `retrocede y da la vuelta al llegar al principio`() {
        val z = LiveZapping(lista, 0)
        assertEquals("c3", z.anterior().code)
    }

    @Test
    fun `los vecinos son el de antes y el de despues`() {
        assertEquals(setOf("c1", "c3"), LiveZapping(lista, 1).vecinos().map { it.code }.toSet())
    }

    @Test
    fun `con un solo canal el zapping no se mueve ni falla`() {
        val z = LiveZapping(listOf(lista[0]), 0)
        assertEquals("c1", z.siguiente().code)
        assertEquals(emptyList<LiveChannel>(), z.vecinos())
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveZappingTest*"`
Expected: FAIL de compilación.

- [ ] **Step 3: Implementar**

```kotlin
package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel

/**
 * Recorre **la lista con la que se entró** al canal (la categoría, los favoritos o el
 * resultado de la búsqueda), que es la que el usuario tiene en la cabeza. Da la vuelta
 * en los extremos, como un decodificador.
 */
class LiveZapping(private val lista: List<LiveChannel>, indiceInicial: Int) {
    private var indice = indiceInicial.coerceIn(0, (lista.size - 1).coerceAtLeast(0))

    val actual: LiveChannel get() = lista[indice]

    fun siguiente(): LiveChannel {
        indice = (indice + 1) % lista.size
        return actual
    }

    fun anterior(): LiveChannel {
        indice = (indice - 1 + lista.size) % lista.size
        return actual
    }

    /** Vacío si hay un solo canal: no hay a dónde zapear ni qué precalentar. */
    fun vecinos(): List<LiveChannel> {
        if (lista.size < 2) return emptyList()
        return listOf(lista[(indice + 1) % lista.size], lista[(indice - 1 + lista.size) % lista.size])
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest --tests "*LiveZappingTest*"`
Expected: 4 passed.

- [ ] **Step 5: Modo vivo en el reproductor**

En `PlayerViewModel`, una bandera `enVivo`. Cuando está activa:

- **No** se sondea la duración (`TsDurationProbe`), **no** se guarda progreso y **no** se ofrece "continuar viendo". En vivo no hay duración: sondearla es justo lo que produjo la barra llena y el congelamiento al reanudar con el CDN de Magis.
- Al abrir un canal, anotar en `LiveRecentDao`.
- Al quedarse el overlay quieto ~1 s, llamar a `liveController.precalentar(code)` para cada uno de `zapping.vecinos()`. Best-effort y cancelable: si el usuario zapea antes, se cancela.
- Al zapear: `liveController.abrir(nuevo.code)` y cambiar el media de VLC sin recrear el reproductor.

En `PlayerScreen`, con `enVivo`: ocultar la barra de progreso y el seek, mostrar el distintivo **EN VIVO** en `ArkivRed`, y el overlay de 3 s con número, nombre, logo, "Ahora" y "A continuación". Arriba/abajo (TV) y swipe vertical (celular) zapean.

- [ ] **Step 6: Probar en el celular y en el TV**

Reproducir un canal, dejarlo **más de 5 minutos** (ahí se ve si la sesión del canal caduca y si el proxy la re-resuelve solo), zapear arriba y abajo varias veces seguidas y confirmar que el cambio no tarda los ~3 s completos cuando el vecino estaba precalentado.

- [ ] **Step 7: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/live/LiveZapping.kt app/src/test/java/com/arkiv/player/ui/live/LiveZappingTest.kt app/src/main/java/com/arkiv/player/ui/player/ && git commit -m "feat(vivo): modo en vivo del reproductor con zapping y precalentado"
```

---

## Task 15: Enviar un canal al TV o al Chromecast

**Repo:** `/Users/cristian/archive`

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/ui/live/LiveScreen.kt` (diálogo de destino), `app/src/main/java/com/arkiv/player/remote/` (comando de canal en vivo), `app/src/main/java/com/arkiv/player/playback/LiveHlsProxy.kt` (bind a la LAN)

**Interfaces:**
- Consumes: `LiveController` (Tarea 9), `LiveHlsProxy.start(bindLan = true)` (Tarea 8), el control remoto y el cast ya existentes.
- Produce: un comando remoto `live:<channelCode>` que el TV interpreta abriendo el canal.

- [ ] **Step 1: Leer cómo viaja hoy un comando al TV**

Revisar `ui/remote/` y `remote/` para ver cómo se manda "reproducir esto en la TV" para VOD. **Reusar ese camino**: el vivo solo agrega un tipo de destino nuevo, no un mecanismo nuevo.

- [ ] **Step 2: Mandar el canal al TV pareado**

En el diálogo de destino de `LiveScreen`, la opción "En la TV" manda `live:<code>`. Del lado del TV, al recibirlo: navegar a la ruta `live` y abrir ese canal con `LiveController`. **El TV resuelve por su cuenta** — no se le manda la URL del proxy del celular, que no es alcanzable ni tendría sentido.

- [ ] **Step 3: Chromecast**

El Chromecast necesita alcanzar el proxy por la red, así que en este camino se arranca con `start(bindLan = true)` y la URL se arma con la IP LAN del teléfono en vez de `127.0.0.1`.

**Advertencia que hay que verificar en device:** varios canales traen audio AC-3, que el Chromecast no reproduce — se ve la imagen y no se oye. Si pasa, aplicar el transcode de solo audio con libVLC que ya se resolvió para los MKV. Si no se puede resolver en esta tarea, **dejar la opción de Chromecast deshabilitada con un texto que lo explique** en vez de ofrecer algo que no suena.

- [ ] **Step 4: Probar los dos destinos**

Desde el celular: mandar un canal al Fire TV Stick pareado (debe abrirse en el TV) y a un Chromecast (verificar imagen **y sonido**). Confirmar que la barra del miniplayer remoto refleja el canal en vivo.

- [ ] **Step 5: Commit**

```bash
cd /Users/cristian/archive && git add app/src/main/java/com/arkiv/player/ui/live/ app/src/main/java/com/arkiv/player/remote/ app/src/main/java/com/arkiv/player/playback/LiveHlsProxy.kt && git commit -m "feat(vivo): enviar un canal en vivo al TV pareado o al Chromecast"
```

---

## Cierre

- [ ] **Correr todo y confirmar que está verde**

```bash
cd /Users/cristian/arkiv-api && uv run pytest tests/ -q
```

```bash
cd /Users/cristian/archive && ./gradlew :app:testDebugUnitTest
```

- [ ] **Anotar en el spec lo que se descubrió**

Dos incógnitas se resuelven durante la ejecución y hay que dejarlas escritas en `docs/superpowers/specs/2026-08-11-canal-en-vivo-design.md`: si el portal manda logo de canal (Tarea 3, paso 5) y si el CDN acepta `start_moment` futuros (Tarea 5, paso 6). Si los momentos futuros funcionan, subir `lote` y `spreadMs` en `FirmaDelGateway` y decirlo en el spec.

Y un tercero: **si `FirmaConRespaldo` conmutó alguna vez en uso real**. Que `usandoRespaldo` se ponga en `true` significa que Magis cambió el algoritmo de firma — el momento de volver a `magia` con el `.so` y el oráculo de Unicorn a re-derivar el tweak.
