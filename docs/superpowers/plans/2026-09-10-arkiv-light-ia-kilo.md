# Sub-proyecto 4: IA en el aparato con Kilo — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app genere el dato curioso del reproductor y la fila "Para ti" hablándole directo a los modelos gratis de Kilo, sin servidor propio y sin ningún secreto embebido.

**Architecture:** Un cliente de IA en `data/ia/` que descubre los modelos gratis de Kilo, salta entre ellos y recuerda cuáles funcionan en este aparato. Encima, dos funciones independientes: el dato curioso (`data/trivia/`, con la UI restaurada de git) y "Para ti" (`data/recomendaciones/`, con el historial local, la cascada de verificación del gateway y el árbitro). Las dos se apagan solas si Kilo no contesta.

**Tech Stack:** Kotlin, Compose, OkHttp, `org.json` (en los tests de JVM viene de `org.json:json`), Room 2.6.1, SharedPreferences, JUnit 4, kotlinx-coroutines-test, MockWebServer. `unitTests.isReturnDefaultValues = true`, así que `android.util.Log` no rompe los tests.

**Spec:** `docs/superpowers/specs/2026-09-10-arkiv-light-ia-kilo-design.md`

## Global Constraints

- **Base de Kilo, exacta:** `https://api.kilo.ai/api/gateway`. Catálogo en `GET /models`, pedidos en `POST /chat/completions` (dialecto OpenAI: `{model, messages}`, respuesta en `choices[0].message.content`).
- **Nunca se manda la cabecera `Authorization` a Kilo.** El tier anónimo depende de que no viaje (`llm-libre/src/llm_libre/client.py`: "Empty key => do NOT send Authorization. Kilo's anonymous tier depends on this.").
- **No hay ningún secreto nuevo.** Nada va al `.env` ni a `BuildConfig`.
- Un modelo es candidato si: `pricing.prompt` convertido a número es exactamente `0.0`; `"tools"` está en `supported_parameters`; y ni su `name` ni su `description` calzan con las expresiones de descarte de la Task 2 (copiadas de `llm-libre/src/llm_libre/catalog.py`).
- **Máximo 3 modelos por pedido.** Timeout de 45 s por intento. Catálogo guardado 6 h.
- Espera por modelo: **429** → lo que diga `Retry-After` (segundos) o **10 min**; **5xx / error de red / timeout** → **5 min**; **respuesta ilegible** → sin espera y sin castigo.
- Dato curioso: **8** datos, **220** caracteres como máximo cada uno, caché **30 días** por obra, **un fallo no se guarda**. No en canales en vivo, no en contenido de adultos, no en archivos descargados.
- "Para ti": pide **20** candidatos, guarda **10**; como mucho **una vez cada 24 h**, **15 min** si falló el modelo; umbral de abandono **0.10**; **un fallo nunca borra las recomendaciones anteriores**; se genera **solo en el TV** (la fila solo existe en el home del TV).
- Los tres prompts (trivia, recomendaciones, árbitro) se copian **tal cual** del spec.
- **Magis y Caracol tienen que seguir reproduciendo exactamente igual.**
- **Español de Bogotá** en KDoc, comentarios, UI y commits: `tú`, `tienes`. Nunca `vos`/`tenés`.
- **Ningún comentario puede afirmar algo que no se haya verificado en el árbol** (con `command grep` o leyendo el archivo).
- **Ningún commit lleva pie de coautoría** (ni `Co-Authored-By` ni `Claude-Session`). Autor: `lordmacu <10134930+lordmacu@users.noreply.github.com>`.
- **Verificación de cada tarea:** `./gradlew :app:testDebugUnitTest --rerun`; las tareas que tocan Android (`AppGraph`, pantallas, Room) además `:app:assembleDebug`. **Todo en primer plano**, nunca en segundo plano. **`git status --short` vacío al terminar.**
- En este entorno `grep` y `cat` están interceptados por un hook y pueden devolver resultados incompletos: usar `command grep` / `command cat` o la herramienta Read.

## Mapa de archivos

| Archivo | Responsabilidad | Tarea |
|---|---|---|
| `data/ia/JsonDelModelo.kt` | Sacar el JSON aunque venga envuelto | 1 |
| `data/ia/CatalogoDeKilo.kt` | Qué modelos de `/models` sirven | 2 |
| `data/ia/MemoriaDeModelos.kt` | Orden por éxitos/fallos y esperas, persistido | 3 |
| `data/ia/ClienteDeIa.kt`, `data/ia/AlmacenEnPreferencias.kt` | El transporte a Kilo | 4 |
| `data/model/TipoDeObra.kt` | Serie o película para TMDB (una sola regla) | 5 |
| `data/trivia/DatosCuriosos.kt` | Prompt, limpieza y caché del dato curioso | 5 |
| `ui/player/TriviaDelPlayer.kt` (restaurado) | Rotación y UI del dato curioso | 6 |
| `data/recomendaciones/SenalesDeHistorial.kt` | Historial local → señales para el modelo | 7 |
| `data/recomendaciones/VerificacionParaTi.kt` | Cascada TMDB → ya vistos → fuentes → árbitro | 8 |
| `data/recomendaciones/GuardadoDeRecomendacion.kt`, `AgregadorDeRecomendaciones.kt` | Guardar cada recomendación con su fuente real | 9 |
| `data/recomendaciones/GeneradorParaTi.kt` | Cuándo generar, pedir, verificar y guardar | 10 |

(Todas las rutas de código van bajo `app/src/main/java/com/arkiv/player/`; los tests, bajo `app/src/test/java/com/arkiv/player/`.)

---

### Task 1: `JsonDelModelo` — leer el JSON aunque venga envuelto

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ia/JsonDelModelo.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ia/JsonDelModeloTest.kt`

**Interfaces:**
- Produces: `internal class JsonIlegible(mensaje: String) : Exception(mensaje)`; `internal object JsonDelModelo { fun arreglo(texto: String): JSONArray; fun objeto(texto: String): JSONObject }` — los dos lanzan `JsonIlegible`.

Port de `arkiv-api/src/arkiv_api/llm_json.py`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonDelModeloTest {

    @Test fun `un arreglo pelado`() {
        assertEquals(2, JsonDelModelo.arreglo("""["a","b"]""").length())
    }

    @Test fun `un arreglo envuelto en cerco de codigo`() {
        val texto = "```json\n[\"uno\", \"dos\"]\n```"
        assertEquals("dos", JsonDelModelo.arreglo(texto).getString(1))
    }

    @Test fun `un arreglo con texto alrededor`() {
        val texto = "Claro, aquí van:\n[1, 2, 3]\nEspero que sirvan."
        assertEquals(3, JsonDelModelo.arreglo(texto).length())
    }

    @Test fun `sin arreglo es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.arreglo("no sé") }
    }

    @Test fun `un arreglo roto es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.arreglo("[1, 2,") }
    }

    @Test fun `un objeto envuelto`() {
        val texto = "```\n{\"titulo\": \"Coco\"}\n```"
        assertEquals("Coco", JsonDelModelo.objeto(texto).getString("titulo"))
    }

    /** Quien pide un objeto no puede recibir una lista y reventar después en otra parte. */
    @Test fun `un arreglo donde se pidio un objeto es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.objeto("""[{"titulo": "Coco"}]""") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*JsonDelModeloTest*'`
Expected: FAIL — no compila, `JsonDelModelo` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ia

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** No se pudo sacar el JSON pedido del texto del modelo. */
internal class JsonIlegible(mensaje: String) : Exception(mensaje)

/**
 * Sacar el JSON de lo que contesta un modelo, aunque venga envuelto.
 *
 * Port de `arkiv-api/src/arkiv_api/llm_json.py`: pedirle al modelo que no envuelva en
 * ```` ```json ```` no alcanza, y tirar una respuesta buena por el envoltorio sería perder el
 * trabajo que ya se hizo.
 */
internal object JsonDelModelo {

    private val CERCO = Regex("^```(?:json)?|```$", RegexOption.MULTILINE)

    private fun sinCerco(texto: String): String = texto.trim().replace(CERCO, "").trim()

    fun arreglo(texto: String): JSONArray {
        val crudo = sinCerco(texto)
        val inicio = crudo.indexOf('[')
        val fin = crudo.lastIndexOf(']')
        if (inicio < 0 || fin < inicio) throw JsonIlegible("no vino ningún arreglo JSON")
        return try {
            JSONArray(crudo.substring(inicio, fin + 1))
        } catch (e: JSONException) {
            throw JsonIlegible("JSON inválido: ${e.message}")
        }
    }

    /**
     * Como [arreglo], pero para UN objeto. Un arreglo NO cuenta: si un corchete abre antes que la
     * primera llave, lo que llegó ES una lista, y el objeto encontrado sería un elemento suyo.
     */
    fun objeto(texto: String): JSONObject {
        val crudo = sinCerco(texto)
        val inicio = crudo.indexOf('{')
        val fin = crudo.lastIndexOf('}')
        if (inicio < 0 || fin < inicio) throw JsonIlegible("no vino ningún objeto JSON")
        val corchete = crudo.indexOf('[')
        if (corchete in 0 until inicio) throw JsonIlegible("vino un arreglo donde iba un objeto")
        return try {
            JSONObject(crudo.substring(inicio, fin + 1))
        } catch (e: JSONException) {
            throw JsonIlegible("JSON inválido: ${e.message}")
        }
    }
}
```

Antes de dar esto por hecho, lee `arkiv-api/src/arkiv_api/llm_json.py` (`/Users/cristian/arkiv-api/src/arkiv_api/llm_json.py`): si tiene una regla que este port no cubre, agrégala con su test y dilo en el informe.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*JsonDelModeloTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ia/JsonDelModelo.kt app/src/test/java/com/arkiv/player/data/ia/JsonDelModeloTest.kt
git commit -m "feat(ia): leer el JSON del modelo aunque venga envuelto"
```

---

### Task 2: `CatalogoDeKilo` — qué modelos sirven

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ia/CatalogoDeKilo.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ia/CatalogoDeKiloTest.kt`

**Interfaces:**
- Produces: `internal data class ModeloDeKilo(val id: String)`; `internal object CatalogoDeKilo { fun candidatos(json: JSONObject): List<ModeloDeKilo>; fun esGratis(modelo: JSONObject): Boolean }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoDeKiloTest {

    private fun modelo(
        id: String,
        precio: Any? = "0",
        tools: Boolean = true,
        nombre: String = id,
        descripcion: String = "a general chat model",
    ): String {
        val params = if (tools) """["tools","temperature"]""" else """["temperature"]"""
        val pricing = if (precio == null) "{}" else """{"prompt": ${JSONObject.quote(precio.toString())}}"""
        return """{"id":"$id","name":${JSONObject.quote(nombre)},"description":${JSONObject.quote(descripcion)},
                   "pricing":$pricing,"supported_parameters":$params}"""
    }

    private fun catalogo(vararg modelos: String) = JSONObject("""{"data":[${modelos.joinToString(",")}]}""")

    @Test fun `gratis y con tools entra`() {
        val c = CatalogoDeKilo.candidatos(catalogo(modelo("nvidia/nemotron-3-super:free")))
        assertEquals(listOf("nvidia/nemotron-3-super:free"), c.map { it.id })
    }

    @Test fun `de pago no entra`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("pago/modelo", precio = "0.0000015"))).isEmpty())
    }

    @Test fun `gratis sin tools no entra`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("sin/tools:free", tools = false))).isEmpty())
    }

    @Test fun `sin precio no es gratis`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("sin/precio", precio = null))).isEmpty())
    }

    @Test fun `un precio con decimales en cero es gratis`() {
        assertTrue(CatalogoDeKilo.esGratis(JSONObject(modelo("x", precio = "0.0000"))))
    }

    /** El clasificador que en llm-libre llegó a ser el primero del ranking respondiendo "safe" a todo. */
    @Test fun `un clasificador de seguridad no entra`() {
        val m = modelo(
            "nvidia/nemotron-3.5-content-safety:free",
            descripcion = "A content safety classifier that flags unsafe prompts",
        )
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `un meta router al azar no entra`() {
        val m = modelo("kilo-auto/free", descripcion = "Rotates through available free models")
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `un modelo de embeddings no entra`() {
        val m = modelo("x/emb:free", nombre = "Text Embeddings Small")
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `conserva el orden del catalogo`() {
        val c = CatalogoDeKilo.candidatos(catalogo(modelo("b:free"), modelo("a:free")))
        assertEquals(listOf("b:free", "a:free"), c.map { it.id })
    }

    @Test fun `un catalogo sin data da vacio`() {
        assertTrue(CatalogoDeKilo.candidatos(JSONObject("{}")).isEmpty())
    }

    @Test fun `esGratis con precio numerico`() {
        assertTrue(CatalogoDeKilo.esGratis(JSONObject("""{"pricing":{"prompt":0}}""")))
        assertFalse(CatalogoDeKilo.esGratis(JSONObject("""{"pricing":{"prompt":0.1}}""")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*CatalogoDeKiloTest*'`
Expected: FAIL — `CatalogoDeKilo` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ia

import org.json.JSONObject

/** Un modelo de Kilo que sirve para esta app: gratis, con `tools`, y de chat de verdad. */
internal data class ModeloDeKilo(val id: String)

/**
 * Qué modelos del catálogo de Kilo sirven. Las reglas son las de
 * `llm-libre/src/llm_libre/catalog.py`.
 *
 * - **Gratis** es `pricing.prompt == 0`, nada más.
 * - **`tools`** no es porque se usen: exigirlo deja afuera los frentes que empalman su propio texto
 *   dentro del `content`, y la app dibuja ese texto tal cual en la pantalla.
 * - **Descarte por lo que el modelo dice de sí mismo** (`name` y `description`), no por su id: una
 *   lista negra de ids se pudre; un guardrail que aparezca mañana con otro nombre se va a seguir
 *   describiendo como guardrail. En llm-libre, `nvidia/nemotron-3.5-content-safety:free` —un
 *   clasificador que responde "User Safety: safe" a todo— llegó a ser el primero del ranking.
 */
internal object CatalogoDeKilo {

    private val DESCARTE = Regex(
        listOf(
            // Especialidades que no son chat.
            "guardrail", "content safety", "\\bmoderation\\b", "\\bmoderates\\b", "\\bclassifier\\b",
            "\\breranker\\b", "\\bre-ranker\\b", "\\breranking\\b",
            "embeddings? model", "text embeddings?\\b",
            "speech[- ]to[- ]text", "text[- ]to[- ]speech",
            // Meta-routers: no son un modelo, son una lotería entre otros.
            "\\bmodels? router\\b", "\\bis a router\\b", "rotates through",
            "\\brouter\\b.{0,60}\\bselects\\b", "selects .{0,40}\\bmodels\\b.{0,20}at random",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun candidatos(json: JSONObject): List<ModeloDeKilo> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!esGratis(m)) return@mapNotNull null
            if (!aceptaTools(m)) return@mapNotNull null
            if (DESCARTE.containsMatchIn(m.optString("name") + " " + m.optString("description"))) {
                return@mapNotNull null
            }
            ModeloDeKilo(id)
        }
    }

    fun esGratis(modelo: JSONObject): Boolean {
        val precio = modelo.optJSONObject("pricing")?.opt("prompt") ?: return false
        return precio.toString().toDoubleOrNull() == 0.0
    }

    private fun aceptaTools(modelo: JSONObject): Boolean {
        val params = modelo.optJSONArray("supported_parameters") ?: return false
        return (0 until params.length()).any { params.optString(it) == "tools" }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*CatalogoDeKiloTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ia/CatalogoDeKilo.kt app/src/test/java/com/arkiv/player/data/ia/CatalogoDeKiloTest.kt
git commit -m "feat(ia): que modelos gratis de Kilo sirven, con las reglas de llm-libre"
```

---

### Task 3: `MemoriaDeModelos` — el orden y la espera

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ia/MemoriaDeModelos.kt`
- Test: `app/src/test/java/com/arkiv/player/data/ia/MemoriaDeModelosTest.kt`

**Interfaces:**
- Consumes: `ModeloDeKilo` (Task 2).
- Produces:
  - `internal interface AlmacenDeMemoria { fun leer(): String?; fun guardar(json: String) }`
  - `internal sealed interface Falla { data class Limite(val retryAfterMs: Long?) : Falla; data object Servidor : Falla; data object Ilegible : Falla }`
  - `internal class MemoriaDeModelos(almacen: AlmacenDeMemoria, ahoraMs: () -> Long)` con `fun ordenar(modelos: List<ModeloDeKilo>): List<ModeloDeKilo>`, `fun exito(id: String)`, `fun fallo(id: String, falla: Falla)`. No es segura entre hilos: quien la usa (Task 4) la protege con un `Mutex`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriaDeModelosTest {

    private class AlmacenEnMemoria : AlmacenDeMemoria {
        var json: String? = null
        override fun leer() = json
        override fun guardar(json: String) { this.json = json }
    }

    private var ahora = 1_000_000L
    private val almacen = AlmacenEnMemoria()
    private fun memoria() = MemoriaDeModelos(almacen) { ahora }
    private val a = ModeloDeKilo("a")
    private val b = ModeloDeKilo("b")
    private val c = ModeloDeKilo("c")

    @Test fun `sin historia se conserva el orden del catalogo`() {
        assertEquals(listOf(a, b, c), memoria().ordenar(listOf(a, b, c)))
    }

    @Test fun `el que respondio bien sube`() {
        val m = memoria()
        m.exito("c")
        assertEquals(c, m.ordenar(listOf(a, b, c)).first())
    }

    @Test fun `el que fallo baja`() {
        val m = memoria()
        m.fallo("a", Falla.Servidor)
        ahora += 6 * 60 * 1000L // ya pasó su espera de 5 min
        assertEquals(a, m.ordenar(listOf(a, b, c)).last())
    }

    @Test fun `un 429 sin Retry-After espera 10 minutos`() {
        val m = memoria()
        m.fallo("a", Falla.Limite(retryAfterMs = null))
        ahora += 9 * 60 * 1000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2 * 60 * 1000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    @Test fun `un 429 con Retry-After espera lo que dice`() {
        val m = memoria()
        m.fallo("a", Falla.Limite(retryAfterMs = 30_000L))
        ahora += 29_000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2_000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    @Test fun `un 5xx espera 5 minutos`() {
        val m = memoria()
        m.fallo("a", Falla.Servidor)
        ahora += 4 * 60 * 1000L
        assertEquals(listOf(b, c), m.ordenar(listOf(a, b, c)))
        ahora += 2 * 60 * 1000L
        assertTrue(a in m.ordenar(listOf(a, b, c)))
    }

    /** Un fallo del lado del cliente no puede excluir un modelo (lección de llm-libre). */
    @Test fun `una respuesta ilegible no castiga`() {
        val m = memoria()
        m.fallo("a", Falla.Ilegible)
        assertEquals(listOf(a, b, c), m.ordenar(listOf(a, b, c)))
    }

    @Test fun `todos en espera da vacio`() {
        val m = memoria()
        listOf("a", "b").forEach { m.fallo(it, Falla.Servidor) }
        assertTrue(m.ordenar(listOf(a, b)).isEmpty())
    }

    @Test fun `la memoria sobrevive entre sesiones`() {
        memoria().exito("c")
        assertEquals(c, memoria().ordenar(listOf(a, b, c)).first())
    }

    @Test fun `un almacen roto no tumba nada`() {
        almacen.json = "{esto no es json"
        assertEquals(listOf(a, b), memoria().ordenar(listOf(a, b)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*MemoriaDeModelosTest*'`
Expected: FAIL — `MemoriaDeModelos` no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.ia

import org.json.JSONObject

/** Dónde se persiste la memoria. En producción, SharedPreferences (ver `AlmacenEnPreferencias`). */
internal interface AlmacenDeMemoria {
    fun leer(): String?
    fun guardar(json: String)
}

/** Por qué falló un modelo, que decide cuánto se lo deja en espera. */
internal sealed interface Falla {
    /** Un 429. [retryAfterMs] es lo que dijo `Retry-After`, o null si no dijo nada. */
    data class Limite(val retryAfterMs: Long?) : Falla
    /** Un 5xx, un error de red o una demora de más de 45 s. */
    data object Servidor : Falla
    /** Contestó, pero no se pudo leer. NO castiga: ver el KDoc de [MemoriaDeModelos]. */
    data object Ilegible : Falla
}

/**
 * Qué modelos probar primero y cuáles dejar en espera, según lo que funcionó en ESTE aparato.
 *
 * No hay sondas ni puntajes de servidor: se cuentan éxitos y fallos por modelo, persistidos, y el
 * orden sale de ahí. El tope ([TOPE_CUENTA]) evita que una historia larga deje a un modelo
 * enterrado para siempre.
 *
 * **Una respuesta ilegible no castiga.** En llm-libre costó nueve rondas de revisión aprender que un
 * fallo del lado del cliente no puede excluir una ruta.
 *
 * No es segura entre hilos: [ClienteDeIa] la usa siempre bajo su `Mutex`.
 */
internal class MemoriaDeModelos(
    private val almacen: AlmacenDeMemoria,
    private val ahoraMs: () -> Long,
) {
    private data class Registro(val exitos: Int = 0, val fallos: Int = 0, val esperaHastaMs: Long = 0L)

    private val registros: MutableMap<String, Registro> = cargar()

    fun ordenar(modelos: List<ModeloDeKilo>): List<ModeloDeKilo> {
        val ahora = ahoraMs()
        return modelos
            .filter { (registros[it.id]?.esperaHastaMs ?: 0L) <= ahora }
            // `sortedByDescending` es estable: sin historia se conserva el orden del catálogo.
            .sortedByDescending { puntaje(registros[it.id]) }
    }

    fun exito(id: String) {
        val r = registros[id] ?: Registro()
        registros[id] = r.copy(exitos = (r.exitos + 1).coerceAtMost(TOPE_CUENTA), esperaHastaMs = 0L)
        guardar()
    }

    fun fallo(id: String, falla: Falla) {
        val ahora = ahoraMs()
        val r = registros[id] ?: Registro()
        registros[id] = when (falla) {
            is Falla.Limite -> r.copy(esperaHastaMs = ahora + (falla.retryAfterMs ?: ESPERA_POR_LIMITE_MS))
            Falla.Servidor -> r.copy(
                fallos = (r.fallos + 1).coerceAtMost(TOPE_CUENTA),
                esperaHastaMs = ahora + ESPERA_POR_SERVIDOR_MS,
            )
            Falla.Ilegible -> return
        }
        guardar()
    }

    private fun puntaje(r: Registro?): Int = if (r == null) 0 else r.exitos - 2 * r.fallos

    private fun cargar(): MutableMap<String, Registro> {
        val salida = mutableMapOf<String, Registro>()
        val json = runCatching { almacen.leer()?.let { JSONObject(it) } }.getOrNull() ?: return salida
        for (id in json.keys()) {
            val o = json.optJSONObject(id) ?: continue
            salida[id] = Registro(o.optInt("e"), o.optInt("f"), o.optLong("h"))
        }
        return salida
    }

    private fun guardar() {
        val json = JSONObject()
        registros.forEach { (id, r) ->
            json.put(id, JSONObject().put("e", r.exitos).put("f", r.fallos).put("h", r.esperaHastaMs))
        }
        runCatching { almacen.guardar(json.toString()) }
    }

    internal companion object {
        const val ESPERA_POR_LIMITE_MS = 10 * 60 * 1000L
        const val ESPERA_POR_SERVIDOR_MS = 5 * 60 * 1000L
        const val TOPE_CUENTA = 20
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*MemoriaDeModelosTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ia/MemoriaDeModelos.kt app/src/test/java/com/arkiv/player/data/ia/MemoriaDeModelosTest.kt
git commit -m "feat(ia): recordar que modelos funcionan en este aparato y cuales esperan"
```

---

### Task 4: `ClienteDeIa` — la app hablándole a Kilo

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/ia/ClienteDeIa.kt`
- Create: `app/src/main/java/com/arkiv/player/data/ia/AlmacenEnPreferencias.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (agregar `clienteDeIa`)
- Modify: `CLAUDE.md` (Kilo como host permitido)
- Test: `app/src/test/java/com/arkiv/player/data/ia/ClienteDeIaTest.kt`

**Interfaces:**
- Consumes: `CatalogoDeKilo.candidatos`, `ModeloDeKilo` (Task 2); `MemoriaDeModelos`, `Falla`, `AlmacenDeMemoria` (Task 3).
- Produces:
  - `internal sealed interface RespuestaDeIa { data class Texto(val texto: String, val modelo: String) : RespuestaDeIa; data object NoPude : RespuestaDeIa }`
  - `internal class ClienteDeIa(baseUrl: String = ClienteDeIa.BASE, http: OkHttpClient = …, memoria: MemoriaDeModelos, ahoraMs: () -> Long = { System.currentTimeMillis() })` con `suspend fun preguntar(instruccion: String): RespuestaDeIa` — **nunca lanza** (salvo la cancelación de la corrutina, que se relanza siempre).
  - En `AppGraph`: `internal val clienteDeIa: com.arkiv.player.data.ia.ClienteDeIa`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.ia

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClienteDeIaTest {

    private lateinit var server: MockWebServer
    private var ahora = 1_000_000L
    private val almacen = object : AlmacenDeMemoria {
        var json: String? = null
        override fun leer() = json
        override fun guardar(json: String) { this.json = json }
    }

    /** Respuesta por modelo; lo que no esté acá contesta un chat válido. */
    private val porModelo = mutableMapOf<String, MockResponse>()
    private var catalogo: MockResponse = MockResponse().setBody(
        """{"data":[
          {"id":"a:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"b:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"c:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]},
          {"id":"d:free","pricing":{"prompt":"0"},"supported_parameters":["tools"]}
        ]}""",
    )
    private val pedidosDeChat = mutableListOf<String>()
    private var pedidosDeCatalogo = 0

    @Before fun arranca() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/models")) {
                    pedidosDeCatalogo++
                    return catalogo
                }
                val cuerpo = request.body.readUtf8()
                val modelo = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(cuerpo)!!.groupValues[1]
                pedidosDeChat += modelo
                return porModelo[modelo] ?: MockResponse().setBody(
                    """{"model":"$modelo","choices":[{"message":{"content":"hola desde $modelo"}}]}""",
                )
            }
        }
        server.start()
    }

    @After fun apaga() = server.shutdown()

    private fun cliente() = ClienteDeIa(
        baseUrl = server.url("/api/gateway").toString().trimEnd('/'),
        memoria = MemoriaDeModelos(almacen) { ahora },
        ahoraMs = { ahora },
    )

    @Test fun `contesta con el primer modelo que sirve`() = runTest {
        val r = cliente().preguntar("di hola")
        assertEquals(RespuestaDeIa.Texto("hola desde a:free", "a:free"), r)
    }

    /** El tier anónimo de Kilo depende de que esta cabecera NO viaje. */
    @Test fun `nunca manda Authorization`() = runTest {
        cliente().preguntar("di hola")
        repeat(server.requestCount) {
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `el pedido va en dialecto OpenAI`() = runTest {
        cliente().preguntar("di hola")
        server.takeRequest() // /models
        val chat = server.takeRequest()
        assertTrue(chat.path!!.endsWith("/chat/completions"))
        val cuerpo = chat.body.readUtf8()
        assertTrue(cuerpo.contains("\"role\":\"user\""))
        assertTrue(cuerpo.contains("di hola"))
    }

    @Test fun `un 429 salta al siguiente modelo`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(429)
        val r = cliente().preguntar("di hola")
        assertEquals("b:free", (r as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `un 500 salta al siguiente modelo`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(500)
        assertEquals("b:free", (cliente().preguntar("x") as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `una respuesta sin contenido salta sin castigar`() = runTest {
        porModelo["a:free"] = MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}""")
        val c = cliente()
        assertEquals("b:free", (c.preguntar("x") as RespuestaDeIa.Texto).modelo)
        // No quedó en espera ni bajó: con b ya arriba por su éxito, a sigue estando en la lista.
        porModelo.remove("a:free")
        pedidosDeChat.clear()
        porModelo["b:free"] = MockResponse().setResponseCode(500)
        assertEquals("a:free", (c.preguntar("y") as RespuestaDeIa.Texto).modelo)
    }

    @Test fun `prueba como maximo tres modelos`() = runTest {
        listOf("a:free", "b:free", "c:free").forEach { porModelo[it] = MockResponse().setResponseCode(500) }
        assertEquals(RespuestaDeIa.NoPude, cliente().preguntar("x"))
        assertEquals(listOf("a:free", "b:free", "c:free"), pedidosDeChat)
    }

    @Test fun `el catalogo se guarda seis horas`() = runTest {
        val c = cliente()
        c.preguntar("x")
        c.preguntar("y")
        assertEquals(1, pedidosDeCatalogo)
        ahora += 6 * 60 * 60 * 1000L + 1
        c.preguntar("z")
        assertEquals(2, pedidosDeCatalogo)
    }

    @Test fun `si el catalogo falla al renovarse sigue con el ultimo`() = runTest {
        val c = cliente()
        c.preguntar("x")
        ahora += 6 * 60 * 60 * 1000L + 1
        catalogo = MockResponse().setResponseCode(503)
        assertTrue(c.preguntar("y") is RespuestaDeIa.Texto)
    }

    @Test fun `sin catalogo no puede`() = runTest {
        catalogo = MockResponse().setResponseCode(503)
        assertEquals(RespuestaDeIa.NoPude, cliente().preguntar("x"))
    }

    @Test fun `el modelo que respondio bien se prueba primero la proxima vez`() = runTest {
        porModelo["a:free"] = MockResponse().setResponseCode(500)
        cliente().preguntar("x") // a falla, b responde
        ahora += 6 * 60 * 1000L // a ya salió de su espera
        porModelo.remove("a:free")
        pedidosDeChat.clear()
        cliente().preguntar("y")
        assertEquals("b:free", pedidosDeChat.first())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*ClienteDeIaTest*'`
Expected: FAIL — `ClienteDeIa` no existe.

- [ ] **Step 3: Write the implementation**

`ClienteDeIa.kt`:

```kotlin
package com.arkiv.player.data.ia

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Lo que devuelve [ClienteDeIa]: el texto del modelo, o que no se pudo. Nunca una excepción. */
internal sealed interface RespuestaDeIa {
    data class Texto(val texto: String, val modelo: String) : RespuestaDeIa
    data object NoPude : RespuestaDeIa
}

/**
 * La app hablándole a los modelos gratis de Kilo, sin servidor propio y sin llave.
 *
 * **Nunca manda `Authorization`**: el tier anónimo de Kilo depende de que no viaje (así lo usa
 * `llm-libre`, que omite la cabecera cuando la llave está vacía). Por eso no hay ningún secreto que
 * embeber.
 *
 * Descubre los modelos en `/models` ([CatalogoDeKilo]), los prueba en el orden de lo que funcionó en
 * este aparato ([MemoriaDeModelos]) y salta al siguiente si uno falla. Como mucho [MAX_INTENTOS]
 * por pedido: medido en vivo, un modelo gratis tarda ~20 s en contestar. Es solo transporte: no
 * sabe nada de películas.
 */
internal class ClienteDeIa(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build(),
    private val memoria: MemoriaDeModelos,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Protege [memoria] y el catálogo en memoria: la trivia y "Para ti" pueden preguntar a la vez. */
    private val candado = Mutex()
    private var catalogo: List<ModeloDeKilo> = emptyList()
    private var catalogoTraidoEnMs: Long? = null

    suspend fun preguntar(instruccion: String): RespuestaDeIa = withContext(Dispatchers.IO) {
        val modelos = catalogoVigente()
        for (modelo in candado.withLock { memoria.ordenar(modelos) }.take(MAX_INTENTOS)) {
            val texto = intentar(modelo, instruccion) ?: continue
            candado.withLock { memoria.exito(modelo.id) }
            return@withContext RespuestaDeIa.Texto(texto, modelo.id)
        }
        RespuestaDeIa.NoPude
    }

    /** Un intento contra un modelo: su texto, o null tras anotar la falla en [memoria]. */
    private suspend fun intentar(modelo: ModeloDeKilo, instruccion: String): String? {
        val cuerpo = JSONObject()
            .put("model", modelo.id)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", instruccion)))
            .toString()
        val pedido = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(cuerpo.toRequestBody(JSON))
            .build()
        val falla: Falla = try {
            http.newCall(pedido).execute().use { resp ->
                when {
                    resp.code == 429 -> Falla.Limite(resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000))
                    !resp.isSuccessful -> Falla.Servidor
                    else -> {
                        val texto = runCatching {
                            JSONObject(resp.body?.string().orEmpty())
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                        }.getOrNull()?.trim()
                        if (!texto.isNullOrEmpty()) return texto
                        Falla.Ilegible
                    }
                }
            }
        } catch (e: IOException) {
            // Incluye el timeout de 45 s (`InterruptedIOException` es un `IOException`).
            Falla.Servidor
        }
        Log.w(TAG, "${modelo.id}: $falla")
        candado.withLock { memoria.fallo(modelo.id, falla) }
        return null
    }

    /** El catálogo de las últimas [VIGENCIA_CATALOGO_MS]; si renovarlo falla, el último que había. */
    private suspend fun catalogoVigente(): List<ModeloDeKilo> {
        candado.withLock {
            val traido = catalogoTraidoEnMs
            if (traido != null && ahoraMs() - traido < VIGENCIA_CATALOGO_MS) return catalogo
        }
        val nuevo = try {
            http.newCall(Request.Builder().url("$baseUrl/models").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) null
                else CatalogoDeKilo.candidatos(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Red caída o un JSON roto (`JSONException`): lo mismo que un 5xx, sigue el último.
            Log.w(TAG, "catálogo: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        return candado.withLock {
            if (!nuevo.isNullOrEmpty()) {
                catalogo = nuevo
                catalogoTraidoEnMs = ahoraMs()
            }
            catalogo
        }
    }

    internal companion object {
        const val BASE = "https://api.kilo.ai/api/gateway"
        const val MAX_INTENTOS = 3
        const val TIMEOUT_S = 45L
        const val VIGENCIA_CATALOGO_MS = 6 * 60 * 60 * 1000L
        private val JSON = "application/json".toMediaType()
        private const val TAG = "ArkivIA"
    }
}
```

`AlmacenEnPreferencias.kt`:

```kotlin
package com.arkiv.player.data.ia

import android.content.Context

/** La memoria de modelos, persistida en SharedPreferences. Perderla solo cuesta volver a aprender. */
internal class AlmacenEnPreferencias(context: Context) : AlmacenDeMemoria {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_ia", Context.MODE_PRIVATE)
    override fun leer(): String? = prefs.getString(CLAVE, null)
    override fun guardar(json: String) { prefs.edit().putString(CLAVE, json).apply() }
    private companion object { const val CLAVE = "memoria_de_modelos" }
}
```

En `AppGraph.kt`, justo después de `val applicationScope …` (hoy en la línea ~355; `appContext` es el `private val` de la línea ~23):

```kotlin
    /** El cliente de los modelos gratis de Kilo (sub-proyecto 4). Sin llave: ver su KDoc. */
    internal val clienteDeIa: com.arkiv.player.data.ia.ClienteDeIa by lazy {
        com.arkiv.player.data.ia.ClienteDeIa(
            memoria = com.arkiv.player.data.ia.MemoriaDeModelos(
                com.arkiv.player.data.ia.AlmacenEnPreferencias(appContext),
            ) { System.currentTimeMillis() },
        )
    }
```

- [ ] **Step 4: The branch rule in `CLAUDE.md`**

En `CLAUDE.md`, en la lista numerada de hosts permitidos, agrega después del punto 7 (Caracol):

```markdown
  8. Directo a **Kilo** (`api.kilo.ai`), tercero público y **sin llave** (tier anónimo: nunca se
     manda `Authorization`): alimenta el dato curioso y la fila "Para ti"
     (`app/src/main/java/com/arkiv/player/data/ia/ClienteDeIa.kt`). No hay ningún secreto embebido.
```

Cambia la frase "Ninguno de los siete es servidor propio" por "Ninguno de los ocho es servidor propio", y "no esté en la lista de siete" por "no esté en la lista de ocho". Después corre el barrido documentado ahí mismo (`command grep -roE "https?://[a-zA-Z0-9._-]+" app/src/main/java | sort -u`) y confirma en el informe que `api.kilo.ai` aparece y que no hay ningún host nuevo sin listar.

- [ ] **Step 5: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/ia/ClienteDeIa.kt app/src/main/java/com/arkiv/player/data/ia/AlmacenEnPreferencias.kt app/src/test/java/com/arkiv/player/data/ia/ClienteDeIaTest.kt app/src/main/java/com/arkiv/player/AppGraph.kt CLAUDE.md
git commit -m "feat(ia): la app le habla directo a los modelos gratis de Kilo, sin llave"
```

---

### Task 5: el dato curioso — qué obra, qué preguntar, dónde guardarlo

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/model/TipoDeObra.kt`
- Create: `app/src/main/java/com/arkiv/player/data/trivia/DatosCuriosos.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`obraParaDatos` y `nombreDeObra`)
- Test: `app/src/test/java/com/arkiv/player/data/model/TipoDeObraTest.kt`
- Test: `app/src/test/java/com/arkiv/player/data/trivia/DatosCuriososTest.kt`

**Interfaces:**
- Consumes: `RespuestaDeIa` (Task 4), `JsonDelModelo`, `JsonIlegible` (Task 1).
- Produces:
  - `object TipoDeObra { fun de(tipoDelItem: String?, categoryOverride: String?, episodio: Int?): String }` — `"tv"` o `"movie"`. La usan la Task 7 y el repositorio.
  - `internal data class ObraDeDatos(val tipo: String, val tmdbId: Int?, val tituloCanonico: String?, val temporada: Int?, val episodio: Int?)` con `val clave: String` y `companion fun de(...): ObraDeDatos?` (null si no hay ni `tmdbId` ni `tituloCanonico`).
  - `internal object PreguntaDeDatos { const val CUANTOS = 8; const val LARGO_MAXIMO = 220; fun instruccion(nombre: String, temporada: Int?, episodio: Int?): String; fun limpiar(arreglo: JSONArray): List<String> }`
  - `internal interface CacheDeDatos { fun leer(clave: String): List<String>?; fun guardar(clave: String, datos: List<String>) }` y `internal class CacheDeDatosEnDisco(dir: File, ahoraMs: () -> Long) : CacheDeDatos`
  - `internal class DatosCuriosos(ia: suspend (String) -> RespuestaDeIa, cache: CacheDeDatos)` con `suspend fun de(obra: ObraDeDatos, nombre: suspend () -> String?): List<String>` — nunca lanza. El nombre se pide **solo si no hay caché**: puede costar una llamada a TMDB.
  - En `ArkivRepository`: `internal suspend fun obraParaDatos(episodeId: String): ObraDeDatos?` (sin red) e `internal suspend fun nombreDeObra(obra: ObraDeDatos): String?` (con red).

La regla del tipo de obra ya existió: vivía en `TriviaDelPlayer.tipoDe` (se ve con `git show e161b231^:app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt`) y se borró con la trivia. Vuelve a la capa de datos porque ahora la usan también "Para ti" y el repositorio; la Task 6 restaura la UI **sin** ella.

- [ ] **Step 1: Write the failing tests**

`TipoDeObraTest.kt` (son los tests de `tipoDe` de `e161b231^`, portados):

```kotlin
package com.arkiv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TipoDeObraTest {

    @Test fun `el tipo lo dice el item cuando lo sabe`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = "tv", categoryOverride = null, episodio = null))
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "movie", categoryOverride = null, episodio = 7))
    }

    @Test fun `sin tipo manda que haya numero de episodio`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = null, categoryOverride = null, episodio = 7))
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "", categoryOverride = null, episodio = null))
    }

    /**
     * Medido en producción: se pidió `movie:82452` para Avatar. En TMDB, tv:82452 es "Avatar: La
     * leyenda de Aang" y movie:82452 es "Savage Water", una película de rafting de 1979.
     */
    @Test fun `una serie sin numero de capitulo NO se pide como pelicula`() {
        assertEquals("tv", TipoDeObra.de(tipoDelItem = null, categoryOverride = "series", episodio = null))
    }

    @Test fun `el tipo del item manda sobre categoryOverride`() {
        assertEquals("movie", TipoDeObra.de(tipoDelItem = "movie", categoryOverride = "series", episodio = 3))
    }

    @Test fun `sin ninguna senal sigue siendo pelicula`() {
        assertEquals("movie", TipoDeObra.de(null, categoryOverride = null, episodio = null))
    }
}
```

`DatosCuriososTest.kt`:

```kotlin
package com.arkiv.player.data.trivia

import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatosCuriososTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val pelicula = ObraDeDatos(tipo = "movie", tmdbId = 354912, tituloCanonico = null, temporada = null, episodio = null)

    private class CacheEnMemoria : CacheDeDatos {
        val datos = mutableMapOf<String, List<String>>()
        override fun leer(clave: String) = datos[clave]
        override fun guardar(clave: String, datos: List<String>) { this.datos[clave] = datos }
    }

    @Test fun `la clave usa el tmdbId cuando lo hay`() {
        assertEquals("movie:354912:0:0", pelicula.clave)
        assertEquals("tv:46260:1:2", ObraDeDatos("tv", 46260, "Naruto", 1, 2).clave)
    }

    @Test fun `sin tmdbId la clave usa el titulo canonico`() {
        assertEquals("tv:naruto:1:2", ObraDeDatos("tv", null, "Naruto", 1, 2).clave)
    }

    /** Preguntar a ciegas es la forma más rápida de que el modelo invente. */
    @Test fun `sin tmdbId ni titulo canonico no hay obra`() {
        assertNull(ObraDeDatos.de("movie", tmdbId = null, tituloCanonico = " ", temporada = null, episodio = null))
        assertNull(ObraDeDatos.de("movie", tmdbId = 0, tituloCanonico = null, temporada = null, episodio = null))
    }

    @Test fun `la instruccion de una pelicula nombra la obra y las reglas`() {
        val i = PreguntaDeDatos.instruccion("Coco", temporada = null, episodio = null)
        assertTrue(i.startsWith("Dame 8 datos curiosos y verificables sobre Coco."))
        assertTrue(i.contains("de menos de 220 caracteres"))
        assertTrue(i.contains("SIN SPOILERS"))
    }

    @Test fun `la instruccion de un capitulo nombra temporada y episodio`() {
        assertTrue(PreguntaDeDatos.instruccion("Naruto", 1, 2).contains("sobre Naruto, temporada 1, episodio 2."))
    }

    @Test fun `sin temporada nombra solo el episodio`() {
        assertTrue(PreguntaDeDatos.instruccion("Dragon Ball", null, 35).contains("sobre Dragon Ball, episodio 35."))
    }

    @Test fun `limpiar se queda con cadenas cortas y como maximo ocho`() {
        val largo = "x".repeat(221)
        val arr = JSONArray(listOf(" uno ", 2, largo, "", "tres") + (4..12).map { "dato $it" })
        val limpio = PreguntaDeDatos.limpiar(arr)
        assertEquals("uno", limpio.first())
        assertTrue(limpio.none { it.length > 220 || it.isBlank() })
        assertEquals(8, limpio.size)
    }

    @Test fun `una respuesta buena se limpia y se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("""["a","b"]""", "m") }, cache = cache)
        assertEquals(listOf("a", "b"), datos.de(pelicula) { "Coco" })
        assertEquals(listOf("a", "b"), cache.datos[pelicula.clave])
    }

    /** El nombre puede costar una llamada a TMDB: con caché no se pide. */
    @Test fun `con cache no se pregunta ni se pide el nombre`() = runTest {
        val cache = CacheEnMemoria().apply { datos[pelicula.clave] = listOf("guardado") }
        var preguntas = 0
        var nombres = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = cache)
        assertEquals(listOf("guardado"), datos.de(pelicula) { nombres++; "Coco" })
        assertEquals(0, preguntas)
        assertEquals(0, nombres)
    }

    @Test fun `sin nombre no se pregunta`() = runTest {
        var preguntas = 0
        val datos = DatosCuriosos(ia = { preguntas++; RespuestaDeIa.NoPude }, cache = CacheEnMemoria())
        assertTrue(datos.de(pelicula) { null }.isEmpty())
        assertEquals(0, preguntas)
    }

    /** Sellar un fallo dejaría a la obra sin trivia un mes por una caída de treinta segundos. */
    @Test fun `un fallo no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.NoPude }, cache = cache)
        assertTrue(datos.de(pelicula) { "Coco" }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    @Test fun `una respuesta ilegible no se guarda`() = runTest {
        val cache = CacheEnMemoria()
        val datos = DatosCuriosos(ia = { RespuestaDeIa.Texto("no sé", "m") }, cache = cache)
        assertTrue(datos.de(pelicula) { "Coco" }.isEmpty())
        assertNull(cache.datos[pelicula.clave])
    }

    @Test fun `el cache en disco vence a los treinta dias`() {
        var ahora = 0L
        val cache = CacheDeDatosEnDisco(carpeta.root) { ahora }
        cache.guardar("movie:1:0:0", listOf("dato"))
        ahora += 30L * 24 * 60 * 60 * 1000 - 1
        assertEquals(listOf("dato"), cache.leer("movie:1:0:0"))
        ahora += 2
        assertNull(cache.leer("movie:1:0:0"))
    }

    @Test fun `una clave con caracteres raros se guarda igual`() {
        val cache = CacheDeDatosEnDisco(carpeta.root) { 0L }
        cache.guardar("tv:el/niño:1:2", listOf("x"))
        assertEquals(listOf("x"), cache.leer("tv:el/niño:1:2"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*TipoDeObraTest*' --tests '*DatosCuriososTest*'`
Expected: FAIL — no compila.

- [ ] **Step 3: Write `TipoDeObra.kt`**

```kotlin
package com.arkiv.player.data.model

/**
 * Si una obra es serie (`"tv"`) o película (`"movie"`) para TMDB.
 *
 * **Equivocarse acá no da "sin datos", da datos de OTRA OBRA**: un id de TMDB solo significa algo
 * dentro de su catálogo. Medido en producción: se pidió `movie:82452` para Avatar, y en TMDB
 * `tv:82452` es "Avatar: La leyenda de Aang" mientras que `movie:82452` es "Savage Water", una
 * película de rafting de 1979.
 *
 * Por eso se miran todas las señales, de la más confiable a la más débil:
 *  1. `tipoDelItem`: el tipo que trajo la fuente (`ItemEntity.tipo`).
 *  2. `categoryOverride`, que es lo que la app ya usa para decidir si algo es serie y puede venir
 *     corregido a mano por la persona.
 *  3. Que ESTE capítulo traiga número.
 *
 * Vivía en `TriviaDelPlayer.tipoDe` hasta `e161b231`; volvió a la capa de datos porque ahora la
 * usan el dato curioso y "Para ti".
 */
object TipoDeObra {
    fun de(tipoDelItem: String?, categoryOverride: String?, episodio: Int?): String = when {
        tipoDelItem == "tv" || tipoDelItem == "movie" -> tipoDelItem
        categoryOverride == "series" -> "tv"
        categoryOverride == "movie" -> "movie"
        episodio != null -> "tv"
        else -> "movie"
    }
}
```

- [ ] **Step 4: Write `DatosCuriosos.kt`**

```kotlin
package com.arkiv.player.data.trivia

import android.util.Log
import com.arkiv.player.data.ia.JsonDelModelo
import com.arkiv.player.data.ia.JsonIlegible
import com.arkiv.player.data.ia.RespuestaDeIa
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * La obra de la que se piden datos curiosos: su identidad, sin su nombre. El nombre se busca aparte
 * y solo si hace falta (puede costar una llamada a TMDB): ver [DatosCuriosos.de].
 */
internal data class ObraDeDatos(
    val tipo: String,
    val tmdbId: Int?,
    val tituloCanonico: String?,
    val temporada: Int?,
    val episodio: Int?,
) {
    /** tipo + tmdbId (o el título canónico) + temporada + capítulo: la clave del caché. */
    val clave: String
        get() {
            val quien = tmdbId?.takeIf { it > 0 }?.toString() ?: tituloCanonico.orEmpty().trim().lowercase()
            return "$tipo:$quien:${temporada ?: 0}:${episodio ?: 0}"
        }

    internal companion object {
        /**
         * Null si no hay forma de nombrarla bien —ni `tmdbId` ni `tituloCanonico`—: preguntarle al
         * modelo a ciegas es la forma más rápida de que invente.
         */
        fun de(tipo: String, tmdbId: Int?, tituloCanonico: String?, temporada: Int?, episodio: Int?): ObraDeDatos? {
            val id = tmdbId?.takeIf { it > 0 }
            val titulo = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() }
            if (id == null && titulo == null) return null
            return ObraDeDatos(tipo, id, titulo, temporada, episodio)
        }
    }
}

/** El prompt del gateway (`arkiv-api/src/arkiv_api/trivia/datos.py`), tal cual, y su limpieza. */
internal object PreguntaDeDatos {
    const val CUANTOS = 8
    /** Va en el código y no solo en el prompt: "corto" es algo que un modelo respeta a veces. */
    const val LARGO_MAXIMO = 220

    /**
     * Si es un capítulo, se pregunta por ESE capítulo: el gateway midió que así salen datos del
     * capítulo (director, guionista, estreno) y no genéricos de la serie.
     */
    fun instruccion(nombre: String, temporada: Int?, episodio: Int?): String {
        val obra = when {
            temporada != null && episodio != null -> "$nombre, temporada $temporada, episodio $episodio"
            episodio != null -> "$nombre, episodio $episodio"
            else -> nombre
        }
        return "Dame $CUANTOS datos curiosos y verificables sobre $obra. Cada uno UNA sola frase " +
            "corta, en español de Colombia, sin voseo, de menos de $LARGO_MAXIMO caracteres. " +
            "SIN SPOILERS: nada de lo que pasa en la trama, ni finales, ni giros. Habla de " +
            "producción, doblaje, música, reparto, rodaje, recepción o contexto histórico. " +
            "Responde SOLO un arreglo JSON de cadenas, sin texto alrededor."
    }

    fun limpiar(arreglo: JSONArray): List<String> =
        (0 until arreglo.length())
            .mapNotNull { arreglo.opt(it) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= LARGO_MAXIMO }
            .take(CUANTOS)
}

internal interface CacheDeDatos {
    fun leer(clave: String): List<String>?
    fun guardar(clave: String, datos: List<String>)
}

/**
 * El caché en archivos del aparato, 30 días por obra. Es solo un caché: perderlo cuesta volver a
 * preguntar, así que no merece una tabla de Room ni su migración.
 */
internal class CacheDeDatosEnDisco(private val dir: File, private val ahoraMs: () -> Long) : CacheDeDatos {

    override fun leer(clave: String): List<String>? = runCatching {
        val f = archivo(clave).takeIf { it.exists() } ?: return null
        val json = JSONObject(f.readText())
        if (ahoraMs() - json.getLong("t") >= VIGENCIA_MS) return null
        val arr = json.getJSONArray("d")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()

    override fun guardar(clave: String, datos: List<String>) {
        runCatching {
            dir.mkdirs()
            archivo(clave).writeText(JSONObject().put("t", ahoraMs()).put("d", JSONArray(datos)).toString())
        }
    }

    /** La clave puede traer títulos con cualquier carácter: el nombre del archivo es su hash. */
    private fun archivo(clave: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(clave.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    internal companion object {
        const val VIGENCIA_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Los datos curiosos de una obra, o vacío. Nunca lanza: sin datos no hay botón, que es el fallo bueno
 * para algo accesorio.
 *
 * **Un fallo no se guarda**: sellarlo dejaría a la obra sin trivia un mes por una caída de treinta
 * segundos. Una respuesta que se lee pero queda vacía tras limpiar tampoco.
 */
internal class DatosCuriosos(
    private val ia: suspend (String) -> RespuestaDeIa,
    private val cache: CacheDeDatos,
) {
    suspend fun de(obra: ObraDeDatos, nombre: suspend () -> String?): List<String> {
        cache.leer(obra.clave)?.let { return it }
        val cual = nombre()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val r = ia(PreguntaDeDatos.instruccion(cual, obra.temporada, obra.episodio))
        if (r !is RespuestaDeIa.Texto) return emptyList()
        val datos = try {
            PreguntaDeDatos.limpiar(JsonDelModelo.arreglo(r.texto))
        } catch (e: JsonIlegible) {
            Log.w(TAG, "respuesta ilegible de ${r.modelo}: ${e.message}")
            return emptyList()
        }
        if (datos.isNotEmpty()) cache.guardar(obra.clave, datos)
        return datos
    }

    private companion object { const val TAG = "ArkivTrivia" }
}
```

- [ ] **Step 5: The repository**

En `ArkivRepository.kt` (el TMDB del repositorio es el parámetro `tmdbApi: TmdbApi?` del constructor, línea ~119; `itemDao` ya existe), agrega junto a los otros métodos de lectura:

```kotlin
    /**
     * La identidad de la obra de la que pedir datos curiosos, o null si no hay forma de nombrarla
     * bien (ver [com.arkiv.player.data.trivia.ObraDeDatos.de]). Sin red: el nombre lo busca aparte
     * [nombreDeObra], y solo si no hay caché.
     *
     * Temporada y capítulo salen primero de los campos que escriben Magis y Caracol al guardar
     * (`EpisodeEntity.season` / `.episode`), y si no, del nombre y la sección, como antes.
     */
    internal suspend fun obraParaDatos(episodeId: String): com.arkiv.player.data.trivia.ObraDeDatos? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        val episodio = ep.episode?.takeIf { it > 0 }
            ?: com.arkiv.player.data.model.EpisodeNumbering.episodeOf(ep.displayName)
        val temporada = ep.season?.takeIf { it > 0 }
            ?: com.arkiv.player.data.model.EpisodeNumbering.seasonOf(ep.section)
        return com.arkiv.player.data.trivia.ObraDeDatos.de(
            tipo = com.arkiv.player.data.model.TipoDeObra.de(item.tipo, item.categoryOverride, episodio),
            tmdbId = item.tmdbId,
            tituloCanonico = item.tituloCanonico,
            temporada = temporada,
            episodio = episodio,
        )
    }

    /**
     * Cómo decirle al modelo qué obra es: el nombre de TMDB cuando hay `tmdbId`, y si no (o si TMDB
     * no contesta) el título canónico. Null si no hay ninguno.
     */
    internal suspend fun nombreDeObra(obra: com.arkiv.player.data.trivia.ObraDeDatos): String? {
        val deTmdb = obra.tmdbId?.let { id ->
            try {
                tmdbApi?.detail(obra.tipo, id)?.title
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return deTmdb?.takeIf { it.isNotBlank() } ?: obra.tituloCanonico
    }
```

- [ ] **Step 6: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/model/TipoDeObra.kt app/src/main/java/com/arkiv/player/data/trivia/DatosCuriosos.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/test/java/com/arkiv/player/data/model/TipoDeObraTest.kt app/src/test/java/com/arkiv/player/data/trivia/DatosCuriososTest.kt
git commit -m "feat(trivia): pedir los datos curiosos a Kilo, limpiarlos y guardarlos un mes"
```

---

### Task 6: el dato curioso vuelve al reproductor

**Files:**
- Restore: `app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt` y `app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt` (desde `e161b231^`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerFoco.kt`, `PlayerViewModel.kt`, `PlayerScreen.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`

**Interfaces:**
- Consumes: `DatosCuriosos`, `CacheDeDatosEnDisco` (Task 5); `ArkivRepository.obraParaDatos`, `nombreDeObra` (Task 5); `AppGraph.clienteDeIa` (Task 4).
- Produces: `AppGraph.datosCuriosos`; `PlayerViewModel.trivia: StateFlow<List<String>>`; `TriviaDelPlayer.pideDatos(episodeId: String, kind: SourceKind): Boolean`.

La UI **se restaura de git, no se reescribe**. El commit `e161b231` la quitó entera (`git show e161b231` muestra cada pieza); esta tarea la vuelve a poner y le cambia la fuente de los datos (antes el gateway, ahora `DatosCuriosos`). **El comportamiento es el de antes**: el dato avanza **a pulsación** (flecha ↑ en el TV con los controles ocultos, el botón "i", o tocar el cartel en el celular), no con el reloj.

- [ ] **Step 1: Restore the UI and its test**

```bash
git show e161b231^:app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt > app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt
git show e161b231^:app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt > app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt
```

Léelos enteros y ajusta:
1. **Borra `TriviaDelPlayer.tipoDe` y sus cinco tests** (`el tipo lo dice el item cuando lo sabe`, `sin tipo manda que haya numero de episodio`, `una serie sin numero de capitulo NO se pide como pelicula`, `el tipo canonico manda sobre categoryOverride`, `sin ninguna senal sigue siendo pelicula`): la regla vive ahora en `TipoDeObra` (Task 5), con esos mismos tests.
2. El KDoc de `object TriviaDelPlayer` dice que los datos se piden "ver `/v1/trivia` en el gateway" y que el modelo tarda "3 a 20 segundos": cámbialo a que se piden a `DatosCuriosos` (Kilo, desde el aparato) y que tarda ~20 s.
3. Agrega a `object TriviaDelPlayer` la decisión de cuándo se piden datos, con su test:

```kotlin
    /**
     * Si este episodio lleva dato curioso: películas y capítulos de Magis o de Caracol. Un canal en
     * vivo no es una obra (lo que pasa cambia cada media hora), y el contenido efímero de Magis es
     * el de adultos, que no tiene fila en la biblioteca con qué nombrarlo. Los archivos descargados
     * no llegan acá: [PlayerViewModel.load] los desvía antes a `loadLocal`.
     */
    fun pideDatos(episodeId: String, kind: com.arkiv.player.playback.SourceKind): Boolean = when (kind) {
        com.arkiv.player.playback.SourceKind.MAGIS -> !com.arkiv.player.playback.MagisEfimero.esEfimero(episodeId)
        com.arkiv.player.playback.SourceKind.DITU -> !com.arkiv.player.playback.DituVivo.esVivo(episodeId)
        else -> false
    }
```

Verifica con `command grep -n "^package\|object MagisEfimero\|object DituVivo\|enum class SourceKind" app/src/main/java/com/arkiv/player/playback/*.kt` que los tres viven en `com.arkiv.player.playback` antes de usar esos nombres; si alguno vive en otro paquete, usa el suyo.

Tests nuevos en `TriviaDelPlayerTest` (los prefijos son los `const val PREFIX` reales: `MagisEfimero.PREFIX = "magis:efimero:"`, `DituVivo.PREFIX = "ditu:vivo:"`):

```kotlin
    @Test
    fun `lleva datos una pelicula o capitulo de Magis o de Caracol`() {
        assertTrue(TriviaDelPlayer.pideDatos("magis:C42::e3", SourceKind.MAGIS))
        assertTrue(TriviaDelPlayer.pideDatos("ditu:99::e1", SourceKind.DITU))
    }

    @Test
    fun `no lleva datos un vivo ni lo de adultos ni otra fuente`() {
        assertFalse(TriviaDelPlayer.pideDatos("ditu:vivo:canal1", SourceKind.DITU))
        assertFalse(TriviaDelPlayer.pideDatos("magis:efimero:C42", SourceKind.MAGIS))
        assertFalse(TriviaDelPlayer.pideDatos("live:1", SourceKind.LIVE))
        assertFalse(TriviaDelPlayer.pideDatos("algo", SourceKind.ARCHIVE))
    }
```

(con `import com.arkiv.player.playback.SourceKind`).

- [ ] **Step 2: Run the restored test**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*TriviaDelPlayerTest*'`
Expected: PASS

- [ ] **Step 3: Wire AppGraph**

En `AppGraph.kt`, después de `clienteDeIa`:

```kotlin
    /** El dato curioso del reproductor (sub-proyecto 4): Kilo, desde el aparato, un mes de caché. */
    internal val datosCuriosos: com.arkiv.player.data.trivia.DatosCuriosos by lazy {
        com.arkiv.player.data.trivia.DatosCuriosos(
            ia = { clienteDeIa.preguntar(it) },
            cache = com.arkiv.player.data.trivia.CacheDeDatosEnDisco(
                java.io.File(appContext.filesDir, "datos-curiosos"),
            ) { System.currentTimeMillis() },
        )
    }
```

- [ ] **Step 4: Wire the ViewModel**

En `PlayerViewModel.kt`, agrega el parámetro al FINAL del constructor, después de `hayCuentaDeMagis`:

```kotlin
    /** El dato curioso (sub-proyecto 4). Null en los tests que no lo usan: sin él no hay botón. */
    private val datosCuriosos: com.arkiv.player.data.trivia.DatosCuriosos? = null,
```

Junto a los otros `StateFlow` (después de `webExtras`):

```kotlin
    /**
     * Datos curiosos de lo que se está viendo, o vacío. Se piden TODOS DE UNA al arrancar y la
     * pantalla avanza entre ellos a pulsación (ver [TriviaDelPlayer]): pasar al siguiente no puede
     * costar los ~20 s que tarda el modelo, ni fallar a mitad de una película.
     */
    private val _trivia = MutableStateFlow<List<String>>(emptyList())
    val trivia: StateFlow<List<String>> = _trivia.asStateFlow()

    /** Cancelable: al saltar de capítulo, la tanda del anterior ya no sirve. */
    private var triviaJob: kotlinx.coroutines.Job? = null
```

Y junto a los otros métodos privados:

```kotlin
    /** Lo del episodio anterior no puede quedarse en pantalla con el siguiente. */
    private fun apagarTrivia() {
        triviaJob?.cancel()
        _trivia.value = emptyList()
    }

    /**
     * Pide la tanda de datos curiosos, best-effort. Se traga cualquier fallo: sin datos no se dibuja
     * el botón, que es el fallo bueno para algo accesorio. `CancellationException` no se traga:
     * dejaría corriendo una corrutina que su scope ya dio por muerta.
     */
    private fun cargarTrivia(episodeId: String) {
        apagarTrivia()
        val fuenteDeDatos = datosCuriosos ?: return
        triviaJob = viewModelScope.launch {
            val obra = try {
                repo.obraParaDatos(episodeId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: no se pudo identificar la obra: ${e.message}")
                null
            } ?: run {
                // Sin esta línea la trivia se apagaría en silencio: sin botón y sin nada en el log
                // que diga por qué (el ítem no tiene ni tmdbId ni título canónico).
                Log.w(PLAY, "trivia: sin obra con nombre para $episodeId → no se pide")
                return@launch
            }
            _trivia.value = try {
                fuenteDeDatos.de(obra) { repo.nombreDeObra(obra) }
                    .also { Log.w(PLAY, "trivia: ${it.size} datos para ${obra.clave}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }
    }
```

En `fun load(episodeId: String)`:
1. Justo después de `ditu.nuevoPedido(episodeId)` (antes del corte del vivo): `apagarTrivia()`.
2. Dentro del `viewModelScope.launch`, después de la línea `if (local != null) { loadLocal(episodeId, local); return@launch }` (el cierre de su `if (kind != SourceKind.ARCHIVE) { … }`) y antes del `Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")`:

```kotlin
            // Después del desvío a lo descargado a propósito: un archivo en el aparato no lleva
            // dato curioso (ver [TriviaDelPlayer.pideDatos]).
            if (TriviaDelPlayer.pideDatos(episodeId, kind)) cargarTrivia(episodeId)
```

- [ ] **Step 5: Wire PlayerFoco and PlayerScreen**

En `PlayerFoco.kt`, dentro de `FocosDelOverlay`, después de `val subirBrillo = FocusRequester()`:

```kotlin

    val trivia = FocusRequester()
```

En `PlayerScreen.kt`, en la fábrica del `PlayerViewModel` (la línea `dituFuente = graph.dituFuente,`), agrega `datosCuriosos = graph.datosCuriosos,` después de `hayCuentaDeMagis = …`. Después vuelve a poner las piezas que quitó `e161b231` (compáralas con `git show e161b231 -- app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt`). `enVivo` ya existe (línea ~429) y hoy vale para Magis Y Caracol, que es lo correcto: ningún canal lleva datos.

1. El import, junto a `import androidx.compose.material.icons.filled.Pause`:
   ```kotlin
   import androidx.compose.material.icons.filled.Info
   ```
2. Después de `val webExtras by vm.webExtras.collectAsStateWithLifecycle()`:
   ```kotlin
    val trivia by vm.trivia.collectAsStateWithLifecycle()
   ```
3. Después de `val espejo = rememberEspejoDelPlayer()`:
   ```kotlin

    // --- Datos curiosos ---
    // El dato avanza a PULSACIÓN, no con el reloj: cada `arriba` (o el botón "i", o tocar el
    // cartel en el teléfono) muestra el siguiente, y al pasar el último vuelve el primero. El
    // estado y las dos piezas de interfaz viven en `TriviaDelPlayer.kt`.
    val estadoTrivia = rememberEstadoDeTrivia()
    EfectosDeTrivia(estadoTrivia, trivia.size, episodeId)
   ```
4. Justo ANTES de `BackHandler(enabled = !enVivo && controles.visible && …)`:
   ```kotlin
    // BACK cierra el panel del dato curioso antes que nada. Va ANTES del handler de los controles
    // para quedar más adentro en la pila: con el panel abierto, BACK lo cierra y no sale del video.
    BackHandler(enabled = estadoTrivia.panelAbierto) { estadoTrivia.cerrarPanel() }

   ```
5. En el listener de teclas del video, justo después de `if (controles.visible) return@setOnKeyListener false`:
   ```kotlin
                            // ARRIBA con los controles ocultos y datos cargados: en vez de abrir el
                            // overlay, despliega el dato curioso que sigue. Va ANTES que el bloque
                            // de vivo porque ahí arriba zapea, y un canal no lleva datos igual.
                            if (!enVivo && keyCode == KeyEvent.KEYCODE_DPAD_UP && TriviaDelPlayer.hayBoton(trivia)) {
                                estadoTrivia.mostrarSiguiente(trivia.size)
                                return@setOnKeyListener true
                            }
   ```
6. Justo antes del comentario `// Casteando a Chromecast.`:
   ```kotlin
        // Cartel "Dato curioso" arriba y centrado, y el panel que despliega el texto (ver sus KDoc
        // en `TriviaDelPlayer.kt`). En el teléfono el cartel es tocable; en TV se abre con la
        // flecha arriba, ver el listener del video.
        CartelDeTrivia(
            estado = estadoTrivia,
            onTocar = if (isTv) null else ({ estadoTrivia.mostrarSiguiente(trivia.size) }),
        )
        PanelDeTrivia(estadoTrivia, trivia)

   ```
7. En el botón de subir brillo del TV, cambia `right = if (hayMarcadoresQueCorregir) focos.marcadores else focos.subirBrillo` por:
   ```kotlin
                                            right = when {
                                                TriviaDelPlayer.hayBoton(trivia) -> focos.trivia
                                                hayMarcadoresQueCorregir -> focos.marcadores
                                                else -> focos.subirBrillo
                                            }
   ```
   y, justo después del cierre de ese botón (antes del comentario `// Corregir los tiempos del capítulo en curso.`), el botón del TV:
   ```kotlin
                                // Datos curiosos: solo existe si hay datos. Va al FINAL de la fila a
                                // propósito -- insertarlo en el medio obligaría a reescribir varios
                                // eslabones de esta cadena de foco. El `right` del botón de arriba
                                // ya lo tiene previsto.
                                if (TriviaDelPlayer.hayBoton(trivia)) {
                                    TvTransportButton(
                                        icon = Icons.Default.Info,
                                        contentDescription = "Dato curioso",
                                        onClick = { estadoTrivia.mostrarSiguiente(trivia.size) },
                                        iconSize = 24.dp,
                                        tint = Color.White,
                                        // Último de la fila: su `right` apunta a sí mismo (tope derecho).
                                        modifier = Modifier
                                            .focusRequester(focos.trivia)
                                            .focusProperties {
                                                left = focos.subirBrillo
                                                right = if (hayMarcadoresQueCorregir) focos.marcadores else focos.trivia
                                                up = focos.barra
                                                down = focos.trivia
                                            },
                                    )
                                }
   ```
8. En el `focusProperties` del menú de marcadores, cambia `left = focos.subirBrillo` por:
   ```kotlin
                                                left = if (TriviaDelPlayer.hayBoton(trivia)) focos.trivia else focos.subirBrillo
   ```
9. En la fila del celular (la de `IconButton`, no la de `TvTransportButton`), justo después del ÚLTIMO botón del modo noche —hoy `tint = if (dimNivel > 0) ArkivRed else Color.White,` aparece cuatro veces: dos en la fila del TV (~2670, ~2685) y dos en la del celular (~2747, ~2754); va después del bloque que contiene la de ~2754, al final de esa fila:
   ```kotlin
                                // El mismo botón de dato curioso que en TV, al final de la fila.
                                if (TriviaDelPlayer.hayBoton(trivia)) {
                                    IconButton(onClick = { estadoTrivia.mostrarSiguiente(trivia.size) }) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = "Dato curioso",
                                            tint = Color.White,
                                        )
                                    }
                                }
   ```

Si una de estas anclas ya no existe tal cual, busca la pieza equivalente en el diff de `e161b231` y dilo en el informe; no inventes una cadena de foco nueva.

- [ ] **Step 6: Verify**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde, incluidos los tests de Magis y de Caracol.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt app/src/main/java/com/arkiv/player/ui/player/PlayerFoco.kt app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(trivia): el dato curioso vuelve al reproductor, ahora desde Kilo"
```

---

### Task 7: el historial local para "Para ti"

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (una consulta nueva en `PlaybackDao` y su fila)
- Create: `app/src/main/java/com/arkiv/player/data/recomendaciones/SenalesDeHistorial.kt`
- Test: `app/src/test/java/com/arkiv/player/data/recomendaciones/SenalesDeHistorialTest.kt`

**Interfaces:**
- Consumes: `TipoDeObra.de` (Task 5).
- Produces:
  - `data class FilaDeHistorial(val episodeId: String, val positionMs: Long, val durationMs: Long, val watched: Boolean, val lastPlayedAt: Long, val episodio: Int?, val itemId: String, val titulo: String, val tituloCanonico: String?, val tipo: String?, val categoryOverride: String?, val tmdbId: Int?)` en `com.arkiv.player.data.db`
  - En `PlaybackDao`: `suspend fun historialReciente(tope: Int): List<FilaDeHistorial>`
  - `internal data class Vista(val titulo: String, val tipo: String, val estado: String)`
  - `internal object SenalesDeHistorial { const val UMBRAL_ABANDONO = 0.10; const val TOPE = 30; fun de(filas: List<FilaDeHistorial>): List<Vista>; fun renglones(vistas: List<Vista>): String }`

Port de `arkiv-api/src/arkiv_api/recomendaciones/historial.py` sobre la base local. **Se pierde la señal *repetido***: la app guarda solo la última reproducción de cada capítulo. El renglón es el que armaba el gateway en `recomendaciones/modelo.py`: `f"- {v.titulo} ({v.tipo}): {v.estado}"`, con el tipo en `tv`/`movie`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.FilaDeHistorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenalesDeHistorialTest {

    private var reloj = 1_000L
    private fun fila(
        item: String,
        pos: Long,
        dur: Long,
        visto: Boolean = false,
        titulo: String = item,
        tipo: String? = "movie",
        episodio: Int? = null,
    ) = FilaDeHistorial(
        episodeId = "$item::$reloj", positionMs = pos, durationMs = dur, watched = visto,
        lastPlayedAt = reloj--, episodio = episodio, itemId = item, titulo = titulo,
        tituloCanonico = null, tipo = tipo, categoryOverride = null, tmdbId = null,
    )

    @Test fun `visto es terminado`() {
        assertEquals("terminado", SenalesDeHistorial.de(listOf(fila("a", 100, 100, visto = true))).single().estado)
    }

    @Test fun `menos del diez por ciento es abandonado`() {
        assertEquals("abandonado", SenalesDeHistorial.de(listOf(fila("a", 5, 100))).single().estado)
    }

    /** A mitad de camino no dice nada: lo estás viendo ahora. */
    @Test fun `a mitad de camino no cuenta`() {
        assertTrue(SenalesDeHistorial.de(listOf(fila("a", 50, 100))).isEmpty())
    }

    @Test fun `a mitad de camino deja decidir a una fila anterior del mismo item`() {
        val v = SenalesDeHistorial.de(listOf(fila("a", 50, 100), fila("a", 100, 100, visto = true)))
        assertEquals(listOf("terminado"), v.map { it.estado })
    }

    @Test fun `un item cuenta una sola vez`() {
        val v = SenalesDeHistorial.de(listOf(fila("a", 100, 100, visto = true), fila("a", 5, 100)))
        assertEquals(1, v.size)
    }

    @Test fun `sin duracion no se sabe si se abandono`() {
        assertTrue(SenalesDeHistorial.de(listOf(fila("a", 0, 0))).isEmpty())
    }

    @Test fun `se queda con los mas recientes hasta el tope`() {
        val filas = (1..40).map { fila("i$it", 100, 100, visto = true) }
        val v = SenalesDeHistorial.de(filas)
        assertEquals(SenalesDeHistorial.TOPE, v.size)
        assertEquals("i1", v.first().titulo)
    }

    @Test fun `el titulo canonico gana`() {
        val f = fila("a", 100, 100, visto = true, titulo = "Shin seiki Temp.1").copy(tituloCanonico = "Neon Genesis Evangelion")
        assertEquals("Neon Genesis Evangelion", SenalesDeHistorial.de(listOf(f)).single().titulo)
    }

    @Test fun `el tipo sale de la misma regla que el dato curioso`() {
        val f = fila("a", 100, 100, visto = true, tipo = null, episodio = 3)
        assertEquals("tv", SenalesDeHistorial.de(listOf(f)).single().tipo)
    }

    @Test fun `los renglones tienen la forma del gateway`() {
        val r = SenalesDeHistorial.renglones(listOf(Vista("Coco", "movie", "terminado"), Vista("Naruto", "tv", "abandonado")))
        assertEquals("- Coco (movie): terminado\n- Naruto (tv): abandonado", r)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*SenalesDeHistorialTest*'`
Expected: FAIL — `FilaDeHistorial` y `SenalesDeHistorial` no existen.

- [ ] **Step 3: Add the query**

En `Daos.kt`, fuera de las interfaces (no es una entidad: no crea tabla ni pide migración):

```kotlin
/** Una reproducción con su capítulo y su ítem, para el historial de "Para ti". Solo lectura. */
data class FilaDeHistorial(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    val episodio: Int?,
    val itemId: String,
    val titulo: String,
    val tituloCanonico: String?,
    val tipo: String?,
    val categoryOverride: String?,
    val tmdbId: Int?,
)
```

Dentro de `interface PlaybackDao` (las tablas son `playback`, `episodes` e `items`; la llave del ítem es `identifier`; las tres tienen `deleted`):

```kotlin
    /** Lo último que se reprodujo, con su ítem, del más reciente al más viejo. Para "Para ti". */
    @Query(
        """
        SELECT p.episodeId AS episodeId, p.positionMs AS positionMs, p.durationMs AS durationMs,
               p.watched AS watched, p.lastPlayedAt AS lastPlayedAt, e.episode AS episodio,
               i.identifier AS itemId, i.title AS titulo, i.tituloCanonico AS tituloCanonico,
               i.tipo AS tipo, i.categoryOverride AS categoryOverride, i.tmdbId AS tmdbId
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.deleted = 0 AND e.deleted = 0 AND i.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT :tope
        """
    )
    suspend fun historialReciente(tope: Int): List<FilaDeHistorial>
```

Room valida esta consulta al compilar: si una columna se llama distinto, falla `assembleDebug`.

- [ ] **Step 4: Write the signals**

```kotlin
package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.FilaDeHistorial
import com.arkiv.player.data.model.TipoDeObra

/** Lo que el modelo necesita saber de algo que viste. [tipo] es `"tv"` o `"movie"`. */
internal data class Vista(val titulo: String, val tipo: String, val estado: String)

/**
 * El historial local → las señales que le importan al modelo. Port de
 * `arkiv-api/src/arkiv_api/recomendaciones/historial.py`, sobre la base de la app.
 *
 * - **terminado**: marcado como visto.
 * - **abandonado**: menos de [UMBRAL_ABANDONO] visto.
 * - A mitad de camino no dice nada (lo estás viendo ahora): se salta y decide una fila más vieja
 *   del mismo ítem, si la hay.
 *
 * **Se pierde *repetido***: la app guarda solo la última reproducción de cada capítulo, no un
 * historial de reproducciones. El contenido de adultos no aparece por construcción: la app no
 * escribe su progreso.
 */
internal object SenalesDeHistorial {
    const val UMBRAL_ABANDONO = 0.10
    const val TOPE = 30

    fun de(filas: List<FilaDeHistorial>): List<Vista> {
        val decididos = mutableSetOf<String>()
        val salida = mutableListOf<Vista>()
        for (f in filas.sortedByDescending { it.lastPlayedAt }) {
            if (f.itemId in decididos) continue
            val estado = when {
                f.watched -> "terminado"
                f.durationMs > 0 && f.positionMs.toDouble() / f.durationMs < UMBRAL_ABANDONO -> "abandonado"
                else -> continue
            }
            decididos += f.itemId
            val titulo = f.tituloCanonico?.takeIf { it.isNotBlank() } ?: f.titulo
            salida += Vista(titulo, TipoDeObra.de(f.tipo, f.categoryOverride, f.episodio), estado)
            if (salida.size >= TOPE) break
        }
        return salida
    }

    /** El renglón que el gateway le mandaba al modelo (`recomendaciones/modelo.py`). */
    fun renglones(vistas: List<Vista>): String =
        vistas.joinToString("\n") { "- ${it.titulo} (${it.tipo}): ${it.estado}" }
}
```

- [ ] **Step 5: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde (el build valida la consulta de Room).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/recomendaciones/SenalesDeHistorial.kt app/src/test/java/com/arkiv/player/data/recomendaciones/SenalesDeHistorialTest.kt
git commit -m "feat(para-ti): el historial para el modelo sale de la base del aparato"
```

---

### Task 8: la cascada de verificación y el árbitro

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/recomendaciones/VerificacionParaTi.kt`
- Test: `app/src/test/java/com/arkiv/player/data/recomendaciones/VerificacionParaTiTest.kt`

**Interfaces:**
- Consumes: `RespuestaDeIa` (Task 4), `JsonDelModelo`, `JsonIlegible` (Task 1), `GatewayResult` (`data/gateway/GatewayModels.kt`: `source`, `title`, `ref`, `kind`, `quality`, `year`…), `TmdbItem` (`data/catalog/TmdbApi.kt`: `id`, `type`, `title`, `originalTitle`, `posterUrl`, `year`).
- Produces:
  - `internal data class Candidato(val titulo: String, val anio: String, val tipo: String, val porque: String)`
  - `internal data class Verificada(val candidato: Candidato, val tmdbId: Int, val tipo: String, val titulo: String, val posterUrl: String, val ref: String)`
  - `internal fun interface BuscadorEnTmdb { suspend fun buscar(tipo: String, titulo: String): TmdbItem? }` — el PRIMER resultado de TMDB para ese tipo, o null.
  - `internal fun interface BuscadorEnFuentes { suspend fun buscar(titulo: String, tipo: String, anio: String, tmdbId: Int): List<GatewayResult> }`
  - `internal fun interface Arbitro { suspend fun cuales(titulo: String, anio: String, tipo: String, resultados: List<GatewayResult>): List<Int>? }` — null = no contestó.
  - `internal class ArbitroDeIa(ia: suspend (String) -> RespuestaDeIa) : Arbitro`
  - `internal object NormalizarTitulo { fun de(texto: String): String }`
  - `internal class VerificacionParaTi(tmdb: BuscadorEnTmdb, fuentes: BuscadorEnFuentes, arbitro: Arbitro)` con `suspend fun verificar(candidatos: List<Candidato>, yaVistos: Set<String>, tope: Int = 10): List<Verificada>`

Port de `arkiv-api/src/arkiv_api/recomendaciones/verificacion.py` y `arkiv-api/src/arkiv_api/arbitro.py` (en `/Users/cristian/arkiv-api/src/arkiv_api/`). Tres detalles del gateway que este port conserva:
- **El tipo real** (`_buscar_tipo_real`): se busca primero con el tipo que propuso el modelo; si su primer resultado calza EXACTO con el título (normalizado), se queda; si no, se prueba el otro tipo, y un calce exacto ahí gana; si ninguno calza exacto, gana el primer resultado que haya aparecido.
- **Normalizar** (`normalizar_titulo`): minúsculas, NFKD sin marcas combinantes, todo lo que no sea letra, dígito o espacio pasa a espacio, y sin espacios repetidos. Letras de cualquier alfabeto cuentan (un título en japonés no queda vacío).
- **La fila del árbitro** (`_fila`): `"<i>. [<source>] <title>"` y, si hay alguno de año, tipo o calidad, `" (<año>, <tipo>, <calidad>)"` con los que haya. Los índices válidos son enteros (no booleanos) dentro de los primeros 25.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificacionParaTiTest {

    private fun tmdb(id: Int, tipo: String, titulo: String, anio: String = "2017") =
        TmdbItem(id = id, type = tipo, title = titulo, originalTitle = titulo, posterUrl = "p$id", year = anio)

    private fun resultado(titulo: String, ref: String = "magis1:movie:0:$titulo") =
        GatewayResult(source = "magis", title = titulo, ref = ref)

    private val coco = Candidato("Coco", "2017", "movie", "porque viste Encanto")

    private fun verificacion(
        enTmdb: (String, String) -> TmdbItem? = { t, titulo -> tmdb(1, t, titulo) },
        enFuentes: (String) -> List<GatewayResult> = { listOf(resultado(it)) },
        arbitro: Arbitro = Arbitro { _, _, _, _ -> listOf(0) },
    ) = VerificacionParaTi(
        tmdb = BuscadorEnTmdb { tipo, titulo -> enTmdb(tipo, titulo) },
        fuentes = BuscadorEnFuentes { titulo, _, _, _ -> enFuentes(titulo) },
        arbitro = arbitro,
    )

    @Test fun `un candidato que pasa todo queda con su ref y los datos de TMDB`() = runTest {
        val v = verificacion().verificar(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
        assertEquals("magis1:movie:0:Coco", v.ref)
        assertEquals("p1", v.posterUrl)
        assertEquals("porque viste Encanto", v.candidato.porque)
    }

    @Test fun `lo que TMDB no conoce era una alucinacion`() = runTest {
        assertTrue(verificacion(enTmdb = { _, _ -> null }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    /** El tipo que vale de ahí en adelante es el que confirmó TMDB, no el que propuso el modelo. */
    @Test fun `si no aparece con su tipo se busca con el otro`() = runTest {
        val v = verificacion(enTmdb = { t, titulo -> if (t == "tv") tmdb(9, "tv", titulo) else null })
            .verificar(listOf(coco), emptySet()).single()
        assertEquals("tv", v.tipo)
    }

    /** El homónimo del tipo equivocado es como una película terminó abriendo la pantalla de temporada. */
    @Test fun `un calce exacto del otro tipo gana sobre un homonimo`() = runTest {
        val v = verificacion(enTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco")
        }).verificar(listOf(coco), emptySet()).single()
        assertEquals(2, v.tmdbId)
        assertEquals("tv", v.tipo)
    }

    @Test fun `sin calce exacto gana el primero que aparecio`() = runTest {
        val v = verificacion(enTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco y sus amigos")
        }).verificar(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
    }

    @Test fun `lo ya visto por id se descarta`() = runTest {
        assertTrue(verificacion().verificar(listOf(coco), setOf("tmdb:1")).isEmpty())
    }

    @Test fun `lo ya visto por titulo se descarta`() = runTest {
        assertTrue(verificacion().verificar(listOf(coco), setOf(NormalizarTitulo.de("COCO!"))).isEmpty())
    }

    @Test fun `un titulo vacio en ya vistos no descarta nada`() = runTest {
        assertEquals(1, verificacion().verificar(listOf(coco), setOf("")).size)
    }

    @Test fun `sin fuente que lo tenga se descarta`() = runTest {
        assertTrue(verificacion(enFuentes = { emptyList() }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    @Test fun `el arbitro elige cual resultado es la obra`() = runTest {
        val v = verificacion(
            enFuentes = { listOf(resultado("Coco podcast", "ref-podcast"), resultado("Coco", "ref-bueno")) },
            arbitro = Arbitro { _, _, _, _ -> listOf(1) },
        ).verificar(listOf(coco), emptySet()).single()
        assertEquals("ref-bueno", v.ref)
    }

    @Test fun `un rechazo total del arbitro descarta al candidato`() = runTest {
        assertTrue(verificacion(arbitro = Arbitro { _, _, _, _ -> emptyList() }).verificar(listOf(coco), emptySet()).isEmpty())
    }

    /** Árbitro caído = primer resultado, como hacía el gateway. */
    @Test fun `si el arbitro no contesta se toma el primero`() = runTest {
        val v = verificacion(
            enFuentes = { listOf(resultado("A", "ref-a"), resultado("B", "ref-b")) },
            arbitro = Arbitro { _, _, _, _ -> null },
        ).verificar(listOf(coco), emptySet()).single()
        assertEquals("ref-a", v.ref)
    }

    @Test fun `se para en el tope`() = runTest {
        val muchos = (1..15).map { Candidato("Peli $it", "2020", "movie", "x") }
        assertEquals(10, verificacion().verificar(muchos, emptySet(), tope = 10).size)
    }

    @Test fun `normalizar quita tildes mayusculas y signos`() {
        assertEquals("el nino y la garza", NormalizarTitulo.de("¡El  Niño y la Garza!"))
    }

    @Test fun `normalizar no borra otros alfabetos`() {
        assertEquals("千と千尋の神隠し", NormalizarTitulo.de("千と千尋の神隠し"))
    }

    @Test fun `el arbitro lee los numeros y descarta los que no existen`() = runTest {
        val a = ArbitroDeIa { RespuestaDeIa.Texto("[0, 5, true, 1]", "m") }
        assertEquals(listOf(0, 1), a.cuales("Coco", "2017", "movie", listOf(resultado("x"), resultado("y"))))
    }

    @Test fun `el arbitro que no contesta es null`() = runTest {
        assertNull(ArbitroDeIa { RespuestaDeIa.NoPude }.cuales("Coco", "", "movie", listOf(resultado("x"))))
    }

    @Test fun `el arbitro ilegible es null`() = runTest {
        assertNull(ArbitroDeIa { RespuestaDeIa.Texto("no sé", "m") }.cuales("Coco", "", "movie", listOf(resultado("x"))))
    }

    @Test fun `el arbitro manda el prompt del gateway con la lista numerada`() = runTest {
        var instruccion = ""
        ArbitroDeIa { instruccion = it; RespuestaDeIa.Texto("[]", "m") }
            .cuales("Coco", "2017", "movie", listOf(GatewayResult(source = "magis", title = "Coco.2017.1080p", ref = "r", year = "2017")))
        assertTrue(instruccion.startsWith("Busco: Coco (2017) (película)."))
        assertTrue(instruccion.endsWith("\n\n0. [magis] Coco.2017.1080p (2017, movie)"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*VerificacionParaTiTest*'`
Expected: FAIL — no existe.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ia.JsonDelModelo
import com.arkiv.player.data.ia.JsonIlegible
import com.arkiv.player.data.ia.RespuestaDeIa
import java.text.Normalizer

internal data class Candidato(val titulo: String, val anio: String, val tipo: String, val porque: String)

internal data class Verificada(
    val candidato: Candidato,
    val tmdbId: Int,
    val tipo: String,
    val titulo: String,
    val posterUrl: String,
    val ref: String,
)

/** El PRIMER resultado de TMDB para ese tipo, o null. */
internal fun interface BuscadorEnTmdb { suspend fun buscar(tipo: String, titulo: String): TmdbItem? }

internal fun interface BuscadorEnFuentes {
    suspend fun buscar(titulo: String, tipo: String, anio: String, tmdbId: Int): List<GatewayResult>
}

/** Qué resultados son ESA obra. Null = no contestó (que es distinto de "ninguno"). */
internal fun interface Arbitro {
    suspend fun cuales(titulo: String, anio: String, tipo: String, resultados: List<GatewayResult>): List<Int>?
}

/**
 * Para comparar títulos sin importar tildes, mayúsculas ni signos. Port de `normalizar_titulo`
 * (`recomendaciones/verificacion.py`): así "El Señor de los Anillos!" y "el senor  de los anillos"
 * quedan iguales. Letras de cualquier alfabeto cuentan.
 */
internal object NormalizarTitulo {
    private val MARCAS = Regex("\\p{Mn}+")
    fun de(texto: String): String {
        val sinTildes = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFKD).replace(MARCAS, "")
        val limpio = sinTildes.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")
        return limpio.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * El árbitro de matching: decide si un resultado de fuente ES la obra buscada. Port de
 * `arkiv-api/src/arkiv_api/arbitro.py`, que existe por un bug medido: "The Mandalorian" terminó
 * apuntando a un podcast. Comparar el título exacto tampoco sirve (los releases se llaman
 * `Titulo.2022.1080p-dual-lat`); un modelo comparando es lo único que cubre los dos casos.
 */
internal class ArbitroDeIa(private val ia: suspend (String) -> RespuestaDeIa) : Arbitro {

    override suspend fun cuales(titulo: String, anio: String, tipo: String, resultados: List<GatewayResult>): List<Int>? {
        val lista = resultados.take(TOPE_RESULTADOS)
        val filas = lista.mapIndexed { i, r -> fila(i, r) }.joinToString("\n")
        val r = ia("${instruccion(titulo, anio, tipo)}\n\n$filas")
        if (r !is RespuestaDeIa.Texto) return null
        return try {
            val arr = JsonDelModelo.arreglo(r.texto)
            // Un modelo que contesta índices inventados no puede sacar a nadie de la lista: solo
            // sobreviven enteros de verdad (un `true` no es un índice) dentro del rango.
            (0 until arr.length()).mapNotNull { arr.opt(it) as? Int }.filter { it in lista.indices }
        } catch (e: JsonIlegible) {
            null
        }
    }

    /** `_fila` del gateway: `"<i>. [<source>] <title>"` y, si los hay, `" (<año>, <tipo>, <calidad>)"`. */
    private fun fila(i: Int, r: GatewayResult): String {
        val detalles = listOf(r.year, r.kind, r.quality).filter { it.isNotBlank() }
        val base = "$i. [${r.source}] ${r.title}"
        return if (detalles.isEmpty()) base else "$base (${detalles.joinToString(", ")})"
    }

    private fun instruccion(titulo: String, anio: String, tipo: String): String {
        val cual = if (tipo == "tv" || tipo == "anime") "serie" else "película"
        val conAnio = if (anio.isNotBlank()) " ($anio)" else ""
        return "Busco: $titulo$conAnio ($cual). Abajo hay una lista numerada de resultados de " +
            "varias fuentes: nombres de release de torrents, ítems de archivos y entradas de " +
            "catálogo. Dime cuáles corresponden a ESA obra exacta. Una temporada o un capítulo " +
            "de la serie buscada sí corresponde; un release con el título dentro del nombre " +
            "(p. ej. 'Titulo.2022.1080p-dual-lat') sí corresponde. Un podcast, reseña, " +
            "documental sobre la obra, otra obra del mismo universo o una de nombre parecido " +
            "NO corresponde. Si busco una película, una serie del mismo nombre NO corresponde, " +
            "y al revés tampoco. Responde SOLO un arreglo JSON con los números que sí, " +
            "p. ej. [0,2]. Si ninguno corresponde, responde []."
    }

    private companion object { const val TOPE_RESULTADOS = 25 }
}

/**
 * La cascada que decide qué llega a la fila. Port de
 * `arkiv-api/src/arkiv_api/recomendaciones/verificacion.py`.
 *
 * **El ORDEN es la optimización**: cada paso es más caro que el anterior, así que el que descarta
 * más barato va primero. Consultar las fuentes de un título que TMDB ni conoce sería pagar el paso
 * caro para nada.
 */
internal class VerificacionParaTi(
    private val tmdb: BuscadorEnTmdb,
    private val fuentes: BuscadorEnFuentes,
    private val arbitro: Arbitro,
) {
    suspend fun verificar(candidatos: List<Candidato>, yaVistos: Set<String>, tope: Int = 10): List<Verificada> {
        val salida = mutableListOf<Verificada>()
        for (c in candidatos) {
            if (salida.size >= tope) break

            // 1. ¿Existe? Un título que TMDB no conoce era una alucinación. La búsqueda ya va con
            //    include_adult=false (`TmdbApi.search`), así que lo adulto no pasa.
            val (enTmdb, tipo) = tipoReal(c) ?: continue

            // 2. ¿Ya lo tienes? Por id y por los DOS títulos. Un título que normaliza a vacío no
            //    dice nada y no puede descartar.
            val porTitulo = listOf(NormalizarTitulo.de(enTmdb.title), NormalizarTitulo.de(c.titulo))
                .filter { it.isNotEmpty() }
            if ("tmdb:${enTmdb.id}" in yaVistos || porTitulo.any { it in yaVistos }) continue

            // 3. ¿Se puede reproducir? Recién acá se paga el paso caro. El año y el id de TMDB
            //    viajan con la búsqueda.
            val anio = enTmdb.year
            val resultados = fuentes.buscar(c.titulo, tipo, anio, enTmdb.id)
            if (resultados.isEmpty()) continue

            // 4. ¿Es esa obra? Rechazo total = candidato descartado; árbitro caído = el primero.
            val indices = arbitro.cuales(c.titulo, anio, tipo, resultados)
            val elegido = when {
                indices == null -> resultados.first()
                indices.isEmpty() -> continue
                else -> resultados[indices.first()]
            }
            val titulo = enTmdb.title.ifBlank { c.titulo }
            salida += Verificada(c, enTmdb.id, tipo, titulo, enTmdb.posterUrl, elegido.ref)
        }
        return salida
    }

    /**
     * `_buscar_tipo_real` del gateway: primero el tipo que propuso el modelo (el caso común, más
     * barato), el otro solo si el primero no calza EXACTO. Un calce exacto en cualquiera de los dos
     * corta; si ninguno calza, gana el primero que apareció.
     */
    private suspend fun tipoReal(c: Candidato): Pair<TmdbItem, String>? {
        val objetivo = NormalizarTitulo.de(c.titulo)
        val otro = if (c.tipo == "tv") "movie" else "tv"
        var mejor: Pair<TmdbItem, String>? = null
        for (tipo in listOf(c.tipo, otro)) {
            val primero = tmdb.buscar(tipo, c.titulo) ?: continue
            if (mejor == null) mejor = primero to tipo
            if (objetivo.isNotEmpty() && NormalizarTitulo.de(primero.title) == objetivo) return primero to tipo
        }
        return mejor
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*VerificacionParaTiTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/recomendaciones/VerificacionParaTi.kt app/src/test/java/com/arkiv/player/data/recomendaciones/VerificacionParaTiTest.kt
git commit -m "feat(para-ti): la cascada de verificacion y el arbitro, portados del gateway"
```

---

### Task 9: cada recomendación se guarda con su fuente real

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/recomendaciones/GuardadoDeRecomendacion.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/recomendaciones/AgregadorDeRecomendaciones.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt`
- Modify: `app/src/test/java/com/arkiv/player/data/recomendaciones/GuardadoDeRecomendacionTest.kt`
- Modify: `app/src/test/java/com/arkiv/player/ui/tv/TvHomeScreenParaTiTest.kt`

**Interfaces:**
- Consumes: `MagisRef.decodificar`, `DituRef.decodificar` (con `.contentId` y `.esSerie`), `MagisEntities.itemIdDe`, `DituEntities.itemIdDe`, `ArkivRepository.addMagisSeason` / `addMagisSource` (sin cambios), `ArkivRepository.addDituSource(ref, title, …, posterUrl, …, tmdbId, tituloCanonico): String?` y `addDituSeason(seriesRef, title, capitulos: List<CapituloDeCaracol>, elegido: CapituloDeCaracol, posterUrl, backdropUrl, tmdbId, tituloCanonico): String?` (los dos devuelven un episodeId), `capituloDeCaracol(capitulo: GatewayEpisode, serie: GatewaySerie?): CapituloDeCaracol` (función `internal` de nivel superior en `ui/search/SearchPlayback.kt`).
- Produces:
  - `internal sealed interface DestinoDeRecomendacion { val contentId: String; data class Magis(…); data class Caracol(…) }`
  - En `GuardadoDeRecomendacion`: `internal fun destinoDeRef(ref: String): DestinoDeRecomendacion?`, `internal fun destino(rec: RecomendacionEntity): DestinoDeRecomendacion`, `internal fun itemIdDe(destino: DestinoDeRecomendacion): String`.
  - `AgregadorDeRecomendaciones.agregar(rec): String?` — el id del ítem guardado (para navegar), o null.

**El bug que esto cierra, anterior a este sub-proyecto**: `AgregadorDeRecomendaciones.agregar` guarda SIEMPRE con `addMagisSeason` / `addMagisSource`, que arman ids `magis:`, y con `contentId = rec.id`. En cuanto "Para ti" recomiende algo de Caracol, al abrirlo quedaría guardado como Magis y el reproductor lo mandaría a `loadMagis` con un ref de Caracol. Y `recommendationItemId` (en `TvHomeScreen.kt`) navega siempre a `MagisEntities.itemIdDe(rec.id)`. Ahora el `contentId` sale del `ref` —el mismo que usa la búsqueda: en Magis, `extra["content_id"]` y el `contentId` del `MagisRef` son el mismo valor (`MagisFuente.resultadoDe`)—, así que lo que se guarda desde "Para ti" y desde la búsqueda es el mismo ítem.

- [ ] **Step 1: Write the failing tests**

En `GuardadoDeRecomendacionTest.kt`, agrega un segundo helper (el `rec(tipo)` que ya existe no se toca) y los tests:

```kotlin
    private fun recCon(id: String, ref: String) = RecomendacionEntity(
        id = id, tmdbId = 0, tipo = "movie", titulo = "x", posterUrl = "", porque = "", ref = ref,
        orden = 0, generadoAt = 0,
    )

    @Test fun `un ref de Caracol va a Caracol con su contentId`() {
        assertEquals(
            DestinoDeRecomendacion.Caracol("99"),
            GuardadoDeRecomendacion.destino(recCon("ditu:99", "ditu1:BUNDLE:99")),
        )
    }

    @Test fun `un ref de Magis va a Magis con su contentId y no con el id de la fila`() {
        assertEquals(
            DestinoDeRecomendacion.Magis("C42"),
            GuardadoDeRecomendacion.destino(recCon("otro-id", "magis1:teleplay:0:C42")),
        )
    }

    /** Filas viejas del gateway: su id es el del registro de PocketBase y su ref puede no entenderse. */
    @Test fun `una fila con un ref que no se entiende cae a Magis con su id`() {
        assertEquals(
            DestinoDeRecomendacion.Magis("pbrecord123"),
            GuardadoDeRecomendacion.destino(recCon("pbrecord123", "ilegible")),
        )
    }

    @Test fun `el ref sin fuente conocida no tiene destino`() {
        assertNull(GuardadoDeRecomendacion.destinoDeRef("otra:cosa"))
    }

    @Test fun `el item guardado es el de la fuente`() {
        assertEquals(
            com.arkiv.player.data.MagisEntities.itemIdDe("C42"),
            GuardadoDeRecomendacion.itemIdDe(DestinoDeRecomendacion.Magis("C42")),
        )
        assertEquals(
            com.arkiv.player.data.DituEntities.itemIdDe("99"),
            GuardadoDeRecomendacion.itemIdDe(DestinoDeRecomendacion.Caracol("99")),
        )
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*GuardadoDeRecomendacionTest*'`
Expected: FAIL — `DestinoDeRecomendacion` no existe.

- [ ] **Step 3: Implement the decision**

En `GuardadoDeRecomendacion.kt`, antes de `object GuardadoDeRecomendacion`:

```kotlin
/** A qué fuente va a parar una recomendación al guardarla, y con qué contentId. */
internal sealed interface DestinoDeRecomendacion {
    val contentId: String
    data class Magis(override val contentId: String) : DestinoDeRecomendacion
    data class Caracol(override val contentId: String) : DestinoDeRecomendacion
}
```

y dentro de `object GuardadoDeRecomendacion`:

```kotlin
    /**
     * De qué fuente es este `ref`, o null si no es de ninguna conocida. Caracol se pregunta primero,
     * pero da igual el orden: `DituRef.decodificar` y `MagisRef.decodificar` solo aceptan lo suyo
     * (su prefijo, o un ref viejo del gateway con su propia fuente adentro).
     */
    internal fun destinoDeRef(ref: String): DestinoDeRecomendacion? {
        com.arkiv.player.data.ditu.DituRef.decodificar(ref)?.let { return DestinoDeRecomendacion.Caracol(it.contentId) }
        com.arkiv.player.data.magis.MagisRef.decodificar(ref)?.let { return DestinoDeRecomendacion.Magis(it.contentId) }
        return null
    }

    /**
     * De qué fuente es [rec]. Un ref de Caracol NUNCA puede guardarse como Magis (el reproductor lo
     * mandaría a `loadMagis`). Una fila vieja con un ref que no se entiende cae a Magis con su id,
     * que es como se guardaba antes.
     */
    internal fun destino(rec: RecomendacionEntity): DestinoDeRecomendacion =
        destinoDeRef(rec.ref) ?: DestinoDeRecomendacion.Magis(rec.id)

    /** El id del ítem que queda en la biblioteca: el mismo que arma la búsqueda para esa fuente. */
    internal fun itemIdDe(destino: DestinoDeRecomendacion): String = when (destino) {
        is DestinoDeRecomendacion.Magis -> com.arkiv.player.data.MagisEntities.itemIdDe(destino.contentId)
        is DestinoDeRecomendacion.Caracol -> com.arkiv.player.data.DituEntities.itemIdDe(destino.contentId)
    }
```

Corrige de paso el KDoc de `pideCapitulos`: dice que la fuente "vendría de destripar un token que el gateway firma y la app trata como opaco", y eso ya no es así (los refs los arma la app y se leen con `MagisRef`/`DituRef`). Déjalo en que se decide por el tipo que confirmó TMDB y que solo aplica a Magis (Caracol decide por `DituRef.esSerie`, ver el Step 4).

- [ ] **Step 4: Make the Agregador source-aware**

Reemplaza `agregar` en `AgregadorDeRecomendaciones.kt` por:

```kotlin
    /**
     * Guarda [rec] y devuelve el id del ítem que quedó en la biblioteca, para navegar a su detalle,
     * o null si no quedó nada (navegar ahí se vería como una recomendación rota).
     */
    suspend fun agregar(rec: RecomendacionEntity): String? = when (val destino = GuardadoDeRecomendacion.destino(rec)) {
        is DestinoDeRecomendacion.Magis -> agregarDeMagis(rec, destino)
        is DestinoDeRecomendacion.Caracol -> agregarDeCaracol(rec, destino)
    }

    private suspend fun agregarDeMagis(rec: RecomendacionEntity, destino: DestinoDeRecomendacion.Magis): String? {
        val temporada = if (GuardadoDeRecomendacion.pideCapitulos(rec)) temporadaDelGateway(rec) else null
        val guardo = if (temporada != null) {
            repo.addMagisSeason(
                contentId = destino.contentId,
                title = rec.titulo,
                capitulos = temporada.capitulos,
                // El ref de la recomendación ES el de la temporada: queda guardado en el ítem y
                // `BuscadorDeCapitulos` puede preguntar por capítulos nuevos más adelante.
                seriesRef = rec.ref,
                posterUrl = rec.posterUrl,
                tmdbId = temporada.tmdbId,
                seasonNumber = temporada.seasonNumber,
            ).isNotEmpty()
        } else {
            // Películas, y series cuyos capítulos no se pudieron listar: el guardado suelto, que es
            // mejor que no guardar nada.
            repo.addMagisSource(
                ref = rec.ref,
                contentId = destino.contentId,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
            ) != null
        }
        return if (guardo) GuardadoDeRecomendacion.itemIdDe(destino) else null
    }

    /**
     * Caracol decide por su ref y no por `rec.tipo`: un `BUNDLE`/`GROUP_OF_BUNDLES` se lista y se
     * guarda entero, como en la búsqueda (`SearchPlayback.playDituSeason`); un `VOD` se guarda solo.
     * El elegido es el primer capítulo: nadie tocó uno, y `addDituSeason` necesita alguno.
     */
    private suspend fun agregarDeCaracol(rec: RecomendacionEntity, destino: DestinoDeRecomendacion.Caracol): String? {
        val tmdbId = rec.tmdbId.takeIf { it > 0 }
        val esSerie = com.arkiv.player.data.ditu.DituRef.decodificar(rec.ref)?.esSerie == true
        val episodeId = if (esSerie) {
            val (capitulos, serie) = capitulosDe(rec) ?: return null
            val lista = capitulos.map { com.arkiv.player.ui.search.capituloDeCaracol(it, serie) }
            val elegido = lista.firstOrNull() ?: return null
            repo.addDituSeason(
                seriesRef = rec.ref,
                title = rec.titulo,
                capitulos = lista,
                elegido = elegido,
                posterUrl = rec.posterUrl.ifBlank { serie?.posterUrl.orEmpty() },
                backdropUrl = serie?.backdropUrl.orEmpty(),
                tmdbId = tmdbId,
                // `rec.titulo` es el de TMDB (lo confirmó la cascada), o sea el canónico.
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            )
        } else {
            repo.addDituSource(
                ref = rec.ref,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
                tmdbId = tmdbId,
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            )
        }
        return episodeId?.let { GuardadoDeRecomendacion.itemIdDe(destino) }
    }

    /** Los capítulos de una serie de Caracol, o null si no se pudieron listar. */
    private suspend fun capitulosDe(rec: RecomendacionEntity) = try {
        gateway.episodesConSerie(rec.ref)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "capitulos de Caracol de \"${rec.titulo}\": ${e.javaClass.simpleName}: ${e.message}")
        null
    }
```

Verifica antes con `command grep -n "val posterUrl\|val backdropUrl\|data class GatewaySerie" app/src/main/java/com/arkiv/player/data/gateway/GatewayModels.kt` que `GatewaySerie` tiene `posterUrl` y `backdropUrl` (`SearchPlayback.playDituSeason` los usa); si no, quítalos. Actualiza el KDoc de la clase: una serie de Magis entra con `addMagisSeason` y una de Caracol con `addDituSeason`.

`capituloDeCaracol` vive en `ui/search/` y se reusa a propósito: es la misma conversión que usa la búsqueda, y la lista y el elegido tienen que sacar la temporada del mismo lado (ver su KDoc).

- [ ] **Step 5: Navigate to what was saved**

En `TvHomeScreen.kt`:
1. Cambia `if (agregador.agregar(rec)) onOpenItem(recommendationItemId(rec))` por `agregador.agregar(rec)?.let(onOpenItem)`, y corrige el comentario de encima: la llave con la que se navega ya no se recalcula, la devuelve el agregador (ver `GuardadoDeRecomendacion.itemIdDe`).
2. Borra `internal fun recommendationItemId(...)` con su KDoc entero.

En `TvHomeScreenParaTiTest.kt`, borra la sección `// --- recommendationItemId: …` con sus tres tests (`la llave sale del id de la recomendacion con el prefijo magis`, `la llave cambia con el id de la recomendacion`, `la llave coincide con la que addMagisSource calcula para el mismo contentId`), el import que quede sin uso (`MagisEntities`) y la mención a `recommendationItemId` del KDoc de la clase de test. Después confirma con `command grep -rn "recommendationItemId" app/src` que no queda ninguna.

- [ ] **Step 6: Verify**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/recomendaciones/GuardadoDeRecomendacion.kt app/src/main/java/com/arkiv/player/data/recomendaciones/AgregadorDeRecomendaciones.kt app/src/main/java/com/arkiv/player/ui/tv/TvHomeScreen.kt app/src/test/java/com/arkiv/player/data/recomendaciones/GuardadoDeRecomendacionTest.kt app/src/test/java/com/arkiv/player/ui/tv/TvHomeScreenParaTiTest.kt
git commit -m "fix(para-ti): una recomendacion de Caracol se guarda como Caracol y se abre"
```

---

### Task 10: `GeneradorParaTi` — cuándo, qué pedir y dónde guardar

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/recomendaciones/GeneradorParaTi.kt`
- Modify: `app/src/main/java/com/arkiv/player/data/db/Daos.kt` (reemplazo en `RecomendacionDao`)
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt` (dos marcas)
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (el gancho)
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (armado y conexión)
- Test: `app/src/test/java/com/arkiv/player/data/recomendaciones/GeneradorParaTiTest.kt`

**Interfaces:**
- Consumes: `SenalesDeHistorial`, `Vista` (Task 7); `VerificacionParaTi`, `BuscadorEnTmdb`, `BuscadorEnFuentes`, `ArbitroDeIa`, `Candidato`, `Verificada`, `NormalizarTitulo` (Task 8); `GuardadoDeRecomendacion.destinoDeRef`, `itemIdDe` (Task 9); `RespuestaDeIa`, `JsonDelModelo`, `JsonIlegible` (Tasks 1 y 4); `AppGraph.clienteDeIa` (Task 4); `RecomendacionEntity`.
- Produces:
  - `internal object PuertaDeParaTi { const val VENTANA_MS; const val VENTANA_TRAS_FALLO_MS; fun toca(ultimoIntentoMs: Long, ultimoFueFalloDelModelo: Boolean, ahoraMs: Long): Boolean }`
  - `internal object PreguntaParaTi { const val CUANTOS_PIDE = 20; fun instruccion(renglones: String): String; fun candidatos(texto: String): List<Candidato> }` — `candidatos` lanza `JsonIlegible`.
  - `internal class GeneradorParaTi(...)` con `suspend fun generarSiToca()` — nunca lanza (salvo la cancelación).
  - En `RecomendacionDao`: `suspend fun reemplazar(nuevas: List<RecomendacionEntity>, ahora: Long)`.
  - En `SettingsStore`: `paraTiUltimoIntentoMs: Long`, `paraTiUltimoFueFalloDelModelo: Boolean`, `fun marcarIntentoDeParaTi(ahoraMs: Long, falloDelModelo: Boolean)`.
  - En `ArkivRepository`: `var alTerminarAlgo: (() -> Unit)? = null`.

**Un fallo nunca borra lo de ayer**: si el modelo no contesta o no queda ninguna verificada, las recomendaciones anteriores se quedan. Y una excepción inesperada tampoco puede escaparse: `generarSiToca` corre en `applicationScope`, que no tiene manejador de excepciones, así que una excepción suelta tumbaría la app.

La lectura de candidatos es la de `_parsear` del gateway (`recomendaciones/modelo.py`): se descartan filas que no son objeto o sin `titulo`; el tipo es `"tv"` solo si dice `"tv"`, si no `"movie"`; el año solo si es un entero o una cadena de dígitos ASCII.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneradorParaTiTest {

    private val HORA = 60 * 60 * 1000L

    @Test fun `la puerta abre la primera vez`() {
        assertTrue(PuertaDeParaTi.toca(ultimoIntentoMs = 0, ultimoFueFalloDelModelo = false, ahoraMs = 1))
    }

    @Test fun `la puerta espera 24 horas`() {
        assertFalse(PuertaDeParaTi.toca(1_000, false, 1_000 + 23 * HORA))
        assertTrue(PuertaDeParaTi.toca(1_000, false, 1_000 + 24 * HORA))
    }

    /** Un modelo caído no gasta la ventana entera: se reintenta a los 15 minutos. */
    @Test fun `tras un fallo del modelo espera 15 minutos`() {
        assertFalse(PuertaDeParaTi.toca(1_000, true, 1_000 + 14 * 60 * 1000L))
        assertTrue(PuertaDeParaTi.toca(1_000, true, 1_000 + 15 * 60 * 1000L))
    }

    @Test fun `la instruccion es la del gateway con el historial debajo`() {
        val i = PreguntaParaTi.instruccion("- Coco (movie): terminado")
        assertTrue(i.startsWith("Eres un recomendador de películas y series para una persona de Colombia."))
        assertTrue(i.contains("Propón 20 títulos que NO estén en la lista."))
        assertTrue(i.endsWith("\n\n- Coco (movie): terminado"))
    }

    @Test fun `los candidatos validos se leen y los rotos se tiran`() {
        val texto = """```json
            [{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"},
             {"titulo":"","tipo":"movie","porque":"x"},
             "no soy un objeto",
             {"titulo":"Naruto","anio":2002,"tipo":"serie","porque":"porque viste Bleach"},
             {"titulo":"Dark","anio":"dos mil","tipo":"tv","porque":"x"}]```"""
        val c = PreguntaParaTi.candidatos(texto)
        assertEquals(listOf("Coco", "Naruto", "Dark"), c.map { it.titulo })
        assertEquals("2002", c[1].anio)
        // Un tipo desconocido cae a película: TMDB confirma el real en la cascada.
        assertEquals("movie", c[1].tipo)
        assertEquals("tv", c[2].tipo)
        // Un año que no es un número no tumba al candidato: queda vacío.
        assertEquals("", c[2].anio)
    }

    // --- el generador ---------------------------------------------------------

    private class Guardado { var ultima: List<RecomendacionEntity>? = null }

    private fun generador(
        ia: (String) -> RespuestaDeIa,
        vistas: List<Vista> = listOf(Vista("Encanto", "movie", "terminado")),
        verificar: (List<Candidato>) -> List<Verificada> = { candidatos ->
            candidatos.mapIndexed { i, c -> Verificada(c, i + 1, c.tipo, c.titulo, "p$i", "magis1:${c.tipo}:0:C$i") }
        },
        guardado: Guardado = Guardado(),
        marcas: MutableMap<String, Any> = mutableMapOf(),
    ) = GeneradorParaTi(
        ia = { ia(it) },
        historial = { vistas },
        yaVistos = { emptySet() },
        verificar = { candidatos, _ -> verificar(candidatos) },
        guardar = { guardado.ultima = it },
        leerMarcas = { (marcas["t"] as? Long ?: 0L) to (marcas["f"] as? Boolean ?: false) },
        escribirMarcas = { t, f -> marcas["t"] = t; marcas["f"] = f },
        ahoraMs = { 10 * HORA },
    )

    private val respuestaBuena = RespuestaDeIa.Texto(
        """[{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"}]""", "m",
    )

    @Test fun `un exito guarda con orden, id de la fuente y porque`() = runTest {
        val g = Guardado()
        generador(ia = { respuestaBuena }, guardado = g).generarSiToca()
        val r = g.ultima!!.single()
        assertEquals(com.arkiv.player.data.MagisEntities.itemIdDe("C0"), r.id)
        assertEquals(0, r.orden)
        assertEquals("porque viste Encanto", r.porque)
        assertEquals("magis1:movie:0:C0", r.ref)
        assertEquals(10 * HORA, r.generadoAt)
    }

    @Test fun `la misma obra dos veces entra una sola`() = runTest {
        val g = Guardado()
        generador(
            ia = { respuestaBuena },
            verificar = { c -> List(2) { Verificada(c.first(), 1, "movie", "Coco", "p", "magis1:movie:0:C7") } },
            guardado = g,
        ).generarSiToca()
        assertEquals(1, g.ultima!!.size)
    }

    @Test fun `si el modelo no contesta no se borra nada y se marca el fallo`() = runTest {
        val g = Guardado()
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { RespuestaDeIa.NoPude }, guardado = g, marcas = marcas).generarSiToca()
        assertNull(g.ultima)
        assertEquals(true, marcas["f"])
    }

    @Test fun `si el modelo contesta algo ilegible es un fallo del modelo`() = runTest {
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { RespuestaDeIa.Texto("no sé", "m") }, marcas = marcas).generarSiToca()
        assertEquals(true, marcas["f"])
    }

    @Test fun `si no queda ninguna verificada no se borra nada`() = runTest {
        val g = Guardado()
        generador(ia = { respuestaBuena }, verificar = { emptyList() }, guardado = g).generarSiToca()
        assertNull(g.ultima)
    }

    /** `generarSiToca` corre en `applicationScope`: una excepción suelta tumbaría la app. */
    @Test fun `una excepcion no se escapa ni borra nada`() = runTest {
        val g = Guardado()
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { respuestaBuena }, verificar = { error("se cayó la red") }, guardado = g, marcas = marcas)
            .generarSiToca()
        assertNull(g.ultima)
        assertEquals(true, marcas["f"])
    }

    @Test fun `sin historial no se le pregunta al modelo`() = runTest {
        var preguntas = 0
        generador(ia = { preguntas++; respuestaBuena }, vistas = emptyList()).generarSiToca()
        assertEquals(0, preguntas)
    }

    @Test fun `con la puerta cerrada no hace nada`() = runTest {
        var preguntas = 0
        val marcas = mutableMapOf<String, Any>("t" to 10 * HORA - 1, "f" to false)
        generador(ia = { preguntas++; respuestaBuena }, marcas = marcas).generarSiToca()
        assertEquals(0, preguntas)
    }

    @Test fun `un exito marca el intento sin fallo`() = runTest {
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { respuestaBuena }, marcas = marcas).generarSiToca()
        assertEquals(10 * HORA, marcas["t"])
        assertEquals(false, marcas["f"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*GeneradorParaTiTest*'`
Expected: FAIL — no existe.

- [ ] **Step 3: Write the generator**

```kotlin
package com.arkiv.player.data.recomendaciones

import android.util.Log
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.ia.JsonDelModelo
import com.arkiv.player.data.ia.JsonIlegible
import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Cuándo toca volver a generar. Ver el KDoc de [GeneradorParaTi]. */
internal object PuertaDeParaTi {
    const val VENTANA_MS = 24 * 60 * 60 * 1000L
    /** Un modelo caído no gasta la ventana entera, pero tampoco se martilla: quince minutos. */
    const val VENTANA_TRAS_FALLO_MS = 15 * 60 * 1000L

    fun toca(ultimoIntentoMs: Long, ultimoFueFalloDelModelo: Boolean, ahoraMs: Long): Boolean {
        if (ultimoIntentoMs <= 0L) return true
        val ventana = if (ultimoFueFalloDelModelo) VENTANA_TRAS_FALLO_MS else VENTANA_MS
        return ahoraMs - ultimoIntentoMs >= ventana
    }
}

/** El prompt del gateway (`recomendaciones/modelo.py`), tal cual, y la lectura de su respuesta. */
internal object PreguntaParaTi {
    const val CUANTOS_PIDE = 20
    private val DIGITOS = Regex("[0-9]+")

    /**
     * Conserva la palabra *repetido* aunque la app no la mande: se porta tal cual para no tocar un
     * prompt que el gateway afinó midiendo.
     */
    fun instruccion(renglones: String): String =
        "Eres un recomendador de películas y series para una persona de Colombia. " +
            "Te doy lo que vio: 'terminado' le gustó, 'abandonado' lo dejó (NO propongas nada " +
            "parecido), 'repetido' le gustó mucho. Propón $CUANTOS_PIDE títulos que NO estén en la " +
            "lista. Responde SOLO un arreglo JSON, sin texto alrededor, con objetos " +
            "{\"titulo\",\"anio\",\"tipo\",\"porque\"}. \"tipo\" es \"movie\" o \"tv\". " +
            "\"porque\" es UNA frase corta en español de Colombia, sin voseo, que explique la " +
            "relación con lo que vio. Nada de contenido para adultos." +
            "\n\n$renglones"

    /** `_parsear` del gateway. Lanza [JsonIlegible] si no vino ningún arreglo. */
    fun candidatos(texto: String): List<Candidato> {
        val arr = JsonDelModelo.arreglo(texto)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val titulo = o.optString("titulo").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Perder el año de UN candidato no puede tumbar a los otros diecinueve.
            val anio = when (val a = o.opt("anio")) {
                is Int -> a.toString()
                is String -> a.trim().takeIf { DIGITOS.matches(it) }.orEmpty()
                else -> ""
            }
            Candidato(
                titulo = titulo,
                anio = anio,
                tipo = if (o.optString("tipo") == "tv") "tv" else "movie",
                porque = o.optString("porque").trim(),
            )
        }
    }
}

/**
 * Genera la fila "Para ti" en el aparato, con Kilo. Port del orquestador del gateway
 * (`recomendaciones/generador.py`).
 *
 * Tres reglas mandan:
 * 1. **Deduplicación por tiempo** ([PuertaDeParaTi]): el disparo es "terminaste algo", que en una
 *    maratón pasa veinte veces en una tarde. La ventana hace que eso sea un solo cálculo.
 * 2. **Un fallo nunca empeora lo que ya había**: sin respuesta del modelo, o sin ninguna verificada,
 *    las recomendaciones anteriores se quedan.
 * 3. **Nunca lanza**: corre en `applicationScope`, que no tiene manejador de excepciones. Una
 *    excepción inesperada se anota como fallo (reintento a los 15 min) y se traga.
 */
internal class GeneradorParaTi(
    private val ia: suspend (String) -> RespuestaDeIa,
    private val historial: suspend () -> List<Vista>,
    private val yaVistos: suspend () -> Set<String>,
    private val verificar: suspend (List<Candidato>, Set<String>) -> List<Verificada>,
    private val guardar: suspend (List<RecomendacionEntity>) -> Unit,
    private val leerMarcas: () -> Pair<Long, Boolean>,
    private val escribirMarcas: (Long, Boolean) -> Unit,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Una sola generación a la vez: dos disparos seguidos no pueden pasar la puerta los dos. */
    private val enCurso = Mutex()

    suspend fun generarSiToca() {
        if (!enCurso.tryLock()) return
        val ahora = ahoraMs()
        try {
            val (ultimo, falloDelModelo) = leerMarcas()
            if (!PuertaDeParaTi.toca(ultimo, falloDelModelo, ahora)) return
            val vistas = historial()
            if (vistas.isEmpty()) return
            escribirMarcas(ahora, false)

            val r = ia(PreguntaParaTi.instruccion(SenalesDeHistorial.renglones(vistas)))
            val candidatos = (r as? RespuestaDeIa.Texto)?.let {
                try { PreguntaParaTi.candidatos(it.texto) } catch (e: JsonIlegible) { null }
            }
            if (candidatos.isNullOrEmpty()) {
                Log.w(TAG, "el modelo no dio candidatos: se conservan las recomendaciones de antes")
                escribirMarcas(ahora, true)
                return
            }

            val verificadas = verificar(candidatos, yaVistos())
            val filas = verificadas.mapNotNull { v ->
                val destino = GuardadoDeRecomendacion.destinoDeRef(v.ref) ?: return@mapNotNull null
                v to GuardadoDeRecomendacion.itemIdDe(destino)
            }.distinctBy { it.second }.mapIndexed { i, (v, id) ->
                RecomendacionEntity(
                    id = id, tmdbId = v.tmdbId, tipo = v.tipo, titulo = v.titulo,
                    posterUrl = v.posterUrl, porque = v.candidato.porque, ref = v.ref,
                    orden = i, generadoAt = ahora, updatedAt = ahora,
                )
            }
            if (filas.isEmpty()) {
                Log.w(TAG, "ninguna verificada de ${candidatos.size}: se conservan las de antes")
                return
            }
            guardar(filas)
            Log.w(TAG, "${filas.size} recomendaciones nuevas de ${candidatos.size} candidatos")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "falló la generación: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { escribirMarcas(ahora, true) }
        } finally {
            enCurso.unlock()
        }
    }

    private companion object { const val TAG = "ArkivParaTi" }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --rerun --tests '*GeneradorParaTiTest*'`
Expected: PASS

- [ ] **Step 5: The replacement, the marks and the hook**

En `Daos.kt`, dentro de `interface RecomendacionDao` (mismo patrón que `ItemDao.replaceItem`, que ya usa `@Transaction` con cuerpo en una interfaz):

```kotlin
    @Query("UPDATE recomendaciones SET deleted = 1, updatedAt = :ahora WHERE deleted = 0")
    suspend fun retirarVigentes(ahora: Long)

    /**
     * Cambia la fila entera de una vez: nunca queda a medias entre la tanda vieja y la nueva. Se
     * retiran con tombstone y no se borran, igual que el resto de las tablas con `deleted`.
     */
    @Transaction
    suspend fun reemplazar(nuevas: List<RecomendacionEntity>, ahora: Long) {
        retirarVigentes(ahora)
        nuevas.forEach { upsert(it) }
    }
```

Verifica que `QUERY_RECOMENDACIONES_VIGENTES` filtra `deleted = 0` (`command grep -n "QUERY_RECOMENDACIONES_VIGENTES" -A 6 app/src/main/java/com/arkiv/player/data/db/Daos.kt`); si no, los retirados seguirían en la fila y hay que decirlo en el informe. Como `upsert` es `REPLACE`, una recomendación nueva con el mismo id que una retirada la revive con `deleted = false`.

En `SettingsStore.kt`, junto a las otras propiedades y siguiendo su patrón de `prefs`:

```kotlin
    /** Cuándo se intentó generar "Para ti" por última vez (0 = nunca). Ver `PuertaDeParaTi`. */
    val paraTiUltimoIntentoMs: Long get() = prefs.getLong(KEY_PARA_TI_ULTIMO_INTENTO, 0L)

    /** Si ese intento falló en el modelo: entonces se reintenta a los 15 min, no a las 24 h. */
    val paraTiUltimoFueFalloDelModelo: Boolean get() = prefs.getBoolean(KEY_PARA_TI_FALLO_MODELO, false)

    fun marcarIntentoDeParaTi(ahoraMs: Long, falloDelModelo: Boolean) {
        prefs.edit()
            .putLong(KEY_PARA_TI_ULTIMO_INTENTO, ahoraMs)
            .putBoolean(KEY_PARA_TI_FALLO_MODELO, falloDelModelo)
            .apply()
    }
```

y en su `companion object`:

```kotlin
        private const val KEY_PARA_TI_ULTIMO_INTENTO = "para_ti_ultimo_intento"
        private const val KEY_PARA_TI_FALLO_MODELO = "para_ti_fallo_modelo"
```

En `ArkivRepository.kt`, un gancho que no sabe nada de recomendaciones (junto a los otros campos de la clase):

```kotlin
    /**
     * Se llama cuando un capítulo ACABA de quedar visto. Lo conecta `AppGraph` con "Para ti"; el
     * repositorio no sabe nada de recomendaciones. Quien lo recibe tiene su propia puerta de 24 h.
     */
    var alTerminarAlgo: (() -> Unit)? = null
```

y dispáralo solo en la transición a visto, no en cada guardado (el reproductor llama `savePlayback` cada ~5 s, también durante los créditos):
- En `savePlayback`, antes del `playbackDao.upsert(...)`: `val yaEstabaVisto = watched && playbackDao.get(episodeId)?.watched == true`. Dentro del `if (watched) { … }` que ya existe, después de `borrarFrameDe(episodeId)`: `if (!yaEstabaVisto) alTerminarAlgo?.invoke()`. Un comentario de una línea: la lectura extra solo ocurre cuando el guardado ya dice "visto".
- En `setWatched`, dentro de su `if (watched) { … }`, después de `borrarFrameDe(episodeId)`: `if (existing?.watched != true) alTerminarAlgo?.invoke()` (`existing` ya existe ahí).

- [ ] **Step 6: Wire AppGraph (solo en el TV)**

En `AppGraph.kt`, agrega arriba los imports que falten:

```kotlin
import com.arkiv.player.data.recomendaciones.ArbitroDeIa
import com.arkiv.player.data.recomendaciones.BuscadorEnFuentes
import com.arkiv.player.data.recomendaciones.BuscadorEnTmdb
import com.arkiv.player.data.recomendaciones.GeneradorParaTi
import com.arkiv.player.data.recomendaciones.NormalizarTitulo
import com.arkiv.player.data.recomendaciones.SenalesDeHistorial
import com.arkiv.player.data.recomendaciones.VerificacionParaTi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
```

y, después de `datosCuriosos`:

```kotlin
    /**
     * "Para ti", generado en el aparato con Kilo (sub-proyecto 4). Verifica contra TMDB y contra la
     * fuente compuesta (Magis y Caracol). Cada paso con red atrapa sus fallos para que un candidato
     * roto no tumbe a los otros; la cancelación siempre se relanza.
     */
    internal val generadorParaTi: GeneradorParaTi by lazy {
        val verificacion = VerificacionParaTi(
            tmdb = BuscadorEnTmdb { tipo, titulo ->
                try {
                    tmdbApi.search(tipo, titulo).firstOrNull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            },
            fuentes = BuscadorEnFuentes { titulo, tipo, _, tmdbId ->
                try {
                    fuenteDeContenido
                        .search(com.arkiv.player.data.gateway.GatewaySearchQuery(q = titulo, type = tipo, tmdbId = tmdbId))
                        .filterIsInstance<com.arkiv.player.data.gateway.SearchEvent.ResultEvent>()
                        .map { it.item }
                        .take(25)
                        .toList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            },
            arbitro = ArbitroDeIa { clienteDeIa.preguntar(it) },
        )
        GeneradorParaTi(
            ia = { clienteDeIa.preguntar(it) },
            historial = { SenalesDeHistorial.de(database.playbackDao().historialReciente(100)) },
            yaVistos = {
                database.itemDao().getAllItems().filter { !it.deleted }.flatMap { item ->
                    listOfNotNull(
                        item.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" },
                        NormalizarTitulo.de(item.title).takeIf { it.isNotEmpty() },
                        item.tituloCanonico?.let { NormalizarTitulo.de(it) }?.takeIf { it.isNotEmpty() },
                    )
                }.toSet()
            },
            verificar = { candidatos, vistos -> verificacion.verificar(candidatos, vistos) },
            guardar = { database.recomendacionDao().reemplazar(it, System.currentTimeMillis()) },
            leerMarcas = { settings.paraTiUltimoIntentoMs to settings.paraTiUltimoFueFalloDelModelo },
            escribirMarcas = { t, f -> settings.marcarIntentoDeParaTi(t, f) },
        )
    }
```

Y conecta el gancho donde se construye `repository` (hoy `ArkivRepository(database, tmdbApi, almacenDeFrames = …, destructorDeFrames = …)`):

```kotlin
    val repository: ArkivRepository by lazy {
        ArkivRepository(
            database, tmdbApi,
            almacenDeFrames = almacenDeFrames,
            destructorDeFrames = destructorDeFrames,
        ).also { repo ->
            // "Para ti" solo existe en el home del TV: en el celular no hay fila que llenar, y cada
            // generación le pregunta a Kilo varias veces.
            if (DeviceType.isTelevision(appContext)) {
                repo.alTerminarAlgo = { applicationScope.launch { generadorParaTi.generarSiToca() } }
            }
        }
    }
```

(`DeviceType` es `com.arkiv.player.DeviceType`, el mismo paquete que `AppGraph`.)

- [ ] **Step 7: Verify**

Run: `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug`
Expected: los dos en verde.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/arkiv/player/data/recomendaciones/GeneradorParaTi.kt app/src/test/java/com/arkiv/player/data/recomendaciones/GeneradorParaTiTest.kt app/src/main/java/com/arkiv/player/data/db/Daos.kt app/src/main/java/com/arkiv/player/data/SettingsStore.kt app/src/main/java/com/arkiv/player/data/ArkivRepository.kt app/src/main/java/com/arkiv/player/AppGraph.kt
git commit -m "feat(para-ti): la fila se genera en el TV al terminar algo, una vez al dia"
```

---

### Task 11: verificación en el KALLEY R3 (la hace el controlador, no un subagente)

- [ ] **Step 1: Instalar**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(El KALLEY se encuentra por mDNS si cambió la IP; la app light es `com.arkiv.player.light` y es debuggable.)

- [ ] **Step 2: Mirar el log mientras Cristian prueba**

```bash
adb logcat | command grep -E "ArkivIA|ArkivTrivia|ArkivParaTi|trivia:"
```

1. Ver una película de Magis o Caracol: a los ~20 s aparece el cartel del dato curioso; ↑ muestra el siguiente; BACK cierra el panel. Los datos son de esa obra y sin spoilers.
2. Un capítulo: los datos son de ese capítulo.
3. Volver a abrir la misma película: los datos salen al instante (caché) y el log no muestra pedido a Kilo.
4. Un canal en vivo (Magis y Caracol) y algo descargado: no aparece el cartel.
5. Terminar algo (o marcarlo como visto): el log muestra `ArkivParaTi` y a los pocos minutos la fila "Para ti" del home del TV se renueva. Abrir una recomendación de Magis y una de Caracol: las dos se abren en su detalle y reproducen.
6. Wifi apagado: nada se rompe, no hay cartel, y la fila "Para ti" conserva lo de antes.

---

## Self-Review

**1. Cobertura del spec.**
- Cliente (spec §1): Tasks 1-4 — JSON envuelto, catálogo filtrado, 6 h con respaldo, orden persistido, 429/5xx/ilegible, 3 intentos, 45 s, nunca lanza, sin `Authorization`.
- Dato curioso (spec §2): Task 5 — prompt, 8 × 220, obra por TMDB o título canónico (y nada a ciegas), capítulo específico, caché 30 días en archivos, fallo no guardado. Task 6 — fuera de vivo, adultos y descargados; UI restaurada de `e161b231^`; sin datos no hay botón.
- "Para ti" (spec §3): Task 7 — historial local (terminado, abandonado < 10 %, a mitad se salta, sin *repetido*). Task 8 — la cascada en el orden del gateway, tipo real, ambos títulos, título vacío no cuenta, árbitro con su prompt, rechazo total descarta, árbitro caído toma el primero, tope 10. Task 10 — disparo al cruzar el umbral y en `setWatched`, 24 h / 15 min, prompt con 20, un fallo no borra, se guarda en `recomendaciones`, solo en el TV.
- Errores sin interfaz (spec "Errores"): todo best-effort, al log.
- Regla de la rama (spec): Task 4 suma Kilo como punto 8 del `CLAUDE.md`; ningún secreto nuevo.
- Fuera del spec pero necesario: la Task 9 arregla el agregador, que guardaba todo como Magis y habría roto cualquier recomendación de Caracol.

**2. Placeholders.** No hay "TBD". Donde el plan no puede ver el código exacto, nombra el comando que lo muestra: la verificación de `llm_json.py` (Task 1), el paquete de `SourceKind`/`MagisEfimero`/`DituVivo` (Task 6), los campos de `GatewaySerie` (Task 9) y el filtro de `QUERY_RECOMENDACIONES_VIGENTES` (Task 10).

**3. Consistencia de tipos.** `RespuestaDeIa` (Task 4) se consume igual en las Tasks 5, 6, 8 y 10. `ModeloDeKilo` y `Falla` en la Task 4. `TipoDeObra.de` (Task 5) en el repositorio y en la Task 7. `ObraDeDatos` y `DatosCuriosos.de(obra, nombre)` (Task 5) en la Task 6. `FilaDeHistorial` y `Vista` (Task 7) en la Task 10. `Candidato`, `Verificada`, `NormalizarTitulo` (Task 8) en la Task 10. `GuardadoDeRecomendacion.destinoDeRef` / `itemIdDe` (Task 9) en la Task 10, así que el id de una fila de "Para ti" es el mismo id de ítem con el que el agregador la guarda.
