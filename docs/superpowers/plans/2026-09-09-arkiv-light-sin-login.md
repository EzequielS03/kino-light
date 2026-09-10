# Arkiv Light — Sub-proyecto 2B (la app abre sin login) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que Kino L abra y reproduzca sin ninguna cuenta ni sesión, borrando el subsistema de cuentas, la identidad de aparato y los clientes del gateway que quedaban.

**Architecture:** Por capas, de afuera hacia adentro: primero se sacan los consumidores de las funciones que se resignan, lo que deja a los clientes del gateway sin llamadores; después se abre la puerta del arranque; recién al final se borra el subsistema de cuentas. Así, en cualquier commit intermedio, la app compila, la suite queda verde y el APK se puede instalar.

**Tech Stack:** Kotlin, Compose (celu y TV/Leanback), Room, `SharedPreferences`, JUnit.

**Spec:** `docs/superpowers/specs/2026-09-09-arkiv-light-sin-login-design.md`

## Global Constraints

- Cada tarea deja `./gradlew :app:testDebugUnitTest` en verde y `./gradlew :app:assembleDebug` ensamblando antes del commit.
- No se comenta código ni se deja detrás de un flag: lo que no se usa se borra del árbol (regla del `CLAUDE.md` de esta rama).
- Identidad de git: `user.name=lordmacu`, sin línea de coautoría.
- Los tests de este repo mienten si no se los mide: cuando una tarea agrega lógica (no solo borra), **mutar la implementación y comprobar que el test falla** antes de darla por buena.
- La app tiene DOS interfaces (celular y TV/Leanback) y casi todo existe por duplicado: al sacar algo de una pantalla, buscar su gemela en `ui/tv/`.
- No se toca `data/magis/` salvo donde el plan lo diga: es el cliente del portal, verificado en dispositivo en 2A.

---

### Task 1: Sacar la trivia del reproductor

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt`
- Delete: `app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`_trivia`, `trivia`, `triviaJob`, `cargarTrivia()` y su llamada)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (líneas ~354, ~472-474, ~1740, ~2059-2065, ~2602, ~2615)
- Modify: `app/src/main/java/com/arkiv/player/data/ArkivRepository.kt` (`obraDeTriviaPara`, si queda sin llamadores)

**Interfaces:**
- Consumes: nada de tareas anteriores.
- Produces: `PlayerViewModel` deja de exponer `val trivia: StateFlow<List<String>>`. `ArkivApiClient.trivia(...)` queda sin llamadores (lo borra la Task 5).

- [ ] **Step 1: Encontrar TODOS los puntos de la trivia antes de tocar nada**

```bash
grep -rn "trivia\|Trivia" app/src/main/java app/src/test/java | grep -v "data/gateway/ArkivApiClient.kt"
```

Esperado: `PlayerViewModel.kt`, `PlayerScreen.kt`, `TriviaDelPlayer.kt`, `TriviaDelPlayerTest.kt`, `ArkivRepository.kt` (`obraDeTriviaPara`) y el `focos.trivia` del D-pad en `PlayerScreen.kt`. Anotar la lista: es la lista de lo que hay que dejar en cero al final del paso 4.

- [ ] **Step 2: Borrar la UI de la trivia**

```bash
git rm app/src/main/java/com/arkiv/player/ui/player/TriviaDelPlayer.kt \
       app/src/test/java/com/arkiv/player/ui/player/TriviaDelPlayerTest.kt
```

- [ ] **Step 3: Sacarla de `PlayerScreen.kt`**

Borrar, en este orden (de abajo hacia arriba del archivo, para que los números de línea no se corran):
- el botón de trivia del panel de TV (~2615) y su rama en el cálculo de foco (~2602, `TriviaDelPlayer.hayBoton(trivia) -> focos.trivia`);
- el cartel y el panel (~2059-2065: `onTocar = …`, `PanelDeTrivia(estadoTrivia, trivia)`);
- el atajo de D-pad arriba (~1740);
- `rememberEstadoDeTrivia()` y `EfectosDeTrivia(...)` (~472-474);
- `val trivia by vm.trivia.collectAsStateWithLifecycle()` (~354).

Si `focos` tiene un campo `trivia` que queda sin uso, borrarlo también.

- [ ] **Step 4: Sacarla del `PlayerViewModel`**

Borrar `_trivia`, `trivia`, `triviaJob`, la función `cargarTrivia(...)` entera y la línea que la llama dentro de `load()`. Si `gatewayClient` queda usado solo por `buscadorDeMarcadores`, dejarlo — se lo lleva la Task 2.

- [ ] **Step 5: Borrar `obraDeTriviaPara` si quedó huérfana**

```bash
grep -rn "obraDeTriviaPara" app/src/main/java app/src/test/java
```

Si solo aparece su definición en `ArkivRepository.kt` (y sus tests), borrar la función y esos tests. Si algún otro camino la usa, dejarla.

- [ ] **Step 6: Compilar y correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, cero fallas.

- [ ] **Step 7: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): sacar la trivia del reproductor

El gateway exige sesion para /v1/trivia y esta rama saca el login, asi que la trivia se
resigna (sub-proyecto 2B). Era la ultima excepcion 'permanente' al cero-servidor: la unica
forma de conservarla sin servidor seria pegarle a un LLM con llave embebida, que es otro
sub-proyecto."
```

---

### Task 2: Sacar el saltar-intro automático (conservando el manual)

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/marcadores/BuscadorDeMarcadores.kt`
- Delete: `app/src/test/java/com/arkiv/player/data/marcadores/MarcadoresDelGatewayTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`marcadoresJob`, `buscadorDeMarcadores`, la llamada a `asegurar(...)`)
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` (`marcadores(...)` y `parseMarcadores`, que queda sin llamadores)

**Interfaces:**
- Consumes: nada.
- Produces: los marcadores siguen leyéndose de Room (`repo.skipMarkerDao()`) y escribiéndose a mano con `EditorDeMarcadores`. Lo que desaparece es que se busquen solos.

- [ ] **Step 1: Confirmar qué es local y qué es del servidor**

```bash
grep -rn "skipMarker\|SkipMarker\|EditorDeMarcadores\|BuscadorDeMarcadores" app/src/main/java | grep -v "BuscadorDeMarcadores.kt"
```

Esperado: `EditorDeMarcadores` y el DAO de Room son locales (se quedan); `BuscadorDeMarcadores` es el único que le pega al gateway (se va). Si aparece algún otro consumidor del buscador, anotarlo antes de borrar.

- [ ] **Step 2: Borrar el buscador y su test**

```bash
git rm app/src/main/java/com/arkiv/player/data/marcadores/BuscadorDeMarcadores.kt \
       app/src/test/java/com/arkiv/player/data/marcadores/MarcadoresDelGatewayTest.kt
```

- [ ] **Step 3: Sacarlo del `PlayerViewModel`**

Borrar el `by lazy` de `buscadorDeMarcadores`, el campo `marcadoresJob` y la corrutina que llama a `asegurar(...)` en `load()`. **No** tocar la lectura de marcadores para reproducir: eso sale de Room y es lo que hace funcionar el saltar-intro a mano.

- [ ] **Step 4: Sacar `marcadores` de `ArkivApiClient`**

Borrar el método `marcadores(...)`, el `parseMarcadores` que solo él usa y `GatewayMarcadores` si queda sin referencias (`grep -rn "GatewayMarcadores" app/src`).

- [ ] **Step 5: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Si falla algún test de `PlayerViewModel` que esperaba la búsqueda automática, borrar ese test: fijaba una función que ya no existe.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): sacar la busqueda automatica de marcadores de intro

/v1/marcadores exige sesion. Se va solo la BUSQUEDA: los marcadores siguen en Room y se
siguen pudiendo corregir a mano con EditorDeMarcadores, que nunca toco la red."
```

---

### Task 3: Sacar los subtítulos de OpenSubtitles

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/subtitles/SubtitleApi.kt`
- Delete: los tests de `SubtitleApi` (buscarlos con `ls app/src/test/java/com/arkiv/player/data/subtitles/`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerPistas.kt:425` y lo que cuelgue de esa búsqueda (la lista de resultados y su UI)
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (`subtitleApi`)

**Interfaces:**
- Consumes: nada.
- Produces: la pantalla de pistas queda solo con las pistas que trae el stream. `SubtitlePrefs` (el orden de idiomas preferidos) **se queda**: sigue eligiendo pista entre las del propio portal.

- [ ] **Step 1: Ver qué parte de la pantalla de pistas es de OpenSubtitles**

```bash
grep -rn "subtitleApi\|SubtitleApi\|opensubtitles" app/src/main/java
```

Anotar: en `PlayerPistas.kt` hay que sacar la sección de "buscar subtítulos" entera (el botón, el estado de carga y la lista de resultados), no solo la llamada.

- [ ] **Step 2: Sacar la sección de la UI y la dependencia**

Borrar en `PlayerPistas.kt` la búsqueda y su UI; borrar `subtitleApi` de `AppGraph.kt`; `git rm` el cliente y sus tests.

- [ ] **Step 3: Confirmar que `SubtitlePrefs` sigue en pie**

```bash
grep -rn "SubtitlePrefs\|subtitlePrefs" app/src/main/java | head
```

Esperado: sigue usándose para elegir pista por idioma. Si su KDoc menciona OpenSubtitles, corregirlo.

- [ ] **Step 4: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): sacar los subtitulos de OpenSubtitles

/v1/catalog/opensubtitles exige sesion. Quedan los subtitulos que el propio portal entrega
junto al stream (MagisPlayable.subtitulos, cableados en 2A) y la preferencia de idioma, que
ahora elige entre esos."
```

---

### Task 4: Sacar los tres huérfanos (Simkl, metadata de anime, aviso de recomendaciones)

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt` y sus tests (`SimklApiTest.kt`, `SimklParseTest.kt`)
- Delete: `app/src/main/java/com/arkiv/player/data/gateway/AvisadorDeRecomendaciones.kt` y su test si existe
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (`simklApi`, `agregadorDeRecomendaciones` si depende del avisador)
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` (`animeMeta`, `refrescarRecomendaciones`, `GatewayAnimeMeta`)

**Interfaces:**
- Consumes: nada.
- Produces: `ArkivApiClient` queda sin un solo método (lo borra la Task 5).

- [ ] **Step 1: Confirmar que de verdad no los llama nadie**

```bash
grep -rn "simklApi\|animeMeta\|refrescarRecomendaciones\|AvisadorDeRecomendaciones" app/src/main/java \
  | grep -v "SimklApi.kt\|ArkivApiClient.kt\|AvisadorDeRecomendaciones.kt\|AppGraph.kt"
```

Esperado: **vacío**. Si sale algo, ese consumidor hay que resolverlo antes (no estaba previsto en el spec: pararse y avisar).

- [ ] **Step 2: Borrarlos**

```bash
git rm app/src/main/java/com/arkiv/player/data/catalog/SimklApi.kt \
       app/src/test/java/com/arkiv/player/data/catalog/SimklApiTest.kt \
       app/src/test/java/com/arkiv/player/data/catalog/SimklParseTest.kt \
       app/src/main/java/com/arkiv/player/data/gateway/AvisadorDeRecomendaciones.kt
```

Y sacar de `ArkivApiClient.kt` los métodos `animeMeta` y `refrescarRecomendaciones`, más `GatewayAnimeMeta` en `GatewayModels.kt` si queda sin referencias.

- [ ] **Step 3: Sacarlos de `AppGraph`**

Borrar `val simklApi` y, si `agregadorDeRecomendaciones` solo existía para el avisador, borrarlo también. Ojo: `AgregadorDeRecomendaciones` usa `FuenteDeContenido` para listar capítulos; si algo de la biblioteca todavía lo llama, se queda.

- [ ] **Step 4: Correr la suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): sacar Simkl, la metadata de anime y el aviso de recomendaciones

Los tres estaban huerfanos desde la poda del sub-proyecto 1: AppGraph los construia y nadie
les pedia nada. El mapeo de anime que si se usa sale de Fribb, que la app ya baja sola
(AnimeMappingRepository), sin servidor de por medio."
```

---

### Task 5: Borrar los clientes del gateway de contenido

**Files:**
- Delete: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` (queda vacío de métodos tras las tareas 1-4)
- Delete: `app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (`arkivApiClient`, `httpGatewayCorto` si queda sin uso)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` (`gatewayClient`, `httpGateway`, `personToken`)
- Modify: `app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt` (los argumentos que se caen del constructor)
- Modify: `app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt` → mover `GatewayException` y `GatewaySearchQuery` a `GatewayModels.kt` antes de borrar el archivo

**Interfaces:**
- Consumes: Tasks 1-4 (dejaron el cliente sin llamadores).
- Produces: `GatewayException` y `GatewaySearchQuery` viven en `data/gateway/GatewayModels.kt`. `MagisFuente` los sigue usando.

- [ ] **Step 1: Mudar los dos tipos que sobreviven**

`GatewayException` (la usa `MagisFuente` y `MagisLive`) y `GatewaySearchQuery` (la arma `SearchViewModel`) están declaradas arriba de `ArkivApiClient.kt`. Moverlas tal cual a `GatewayModels.kt`, sin cambiarles nada.

- [ ] **Step 2: Compilar para confirmar que solo faltaba eso**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Borrar el cliente y su test**

```bash
git rm app/src/main/java/com/arkiv/player/data/gateway/ArkivApiClient.kt \
       app/src/test/java/com/arkiv/player/data/gateway/ArkivApiClientTest.kt
```

- [ ] **Step 4: Sacarlo de `AppGraph` y del `PlayerViewModel`**

En `AppGraph.kt`: borrar `arkivApiClient` y `httpGatewayCorto` si no lo usa nadie más (`grep -rn "httpGatewayCorto" app/src/main/java`). En `PlayerViewModel.kt`: borrar `gatewayClient`, y los parámetros `httpGateway` y `personToken` si quedan sin uso — con su argumento correspondiente en `PlayerScreen.kt`.

- [ ] **Step 5: Correr la suite y ensamblar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): borrar el cliente del gateway de contenido

ArkivApiClient se quedo sin un solo metodo tras sacar trivia, marcadores, anime y
recomendaciones. GatewayException y GatewaySearchQuery se mudan a GatewayModels.kt: las
sigue usando la fuente de Magis."
```

---

### Task 6: Abrir la puerta — la app entra sin sesión

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/MainActivity.kt:110-146`

**Interfaces:**
- Consumes: nada.
- Produces: `MainActivity` ya no compone `PantallaDeEntrada` ni `TvPantallaDeEntrada`; entra siempre a `ArkivRoot`/`ArkivTvRoot`. `EntradaViewModel`, `estadoDeEntrada` y las dos pantallas quedan sin llamadores (los borra la Task 9).

- [ ] **Step 1: Reemplazar el gate por la entrada directa**

En `MainActivity.kt`, borrar el bloque del `entradaVm`, `sesionEstado`, `aviso` y el `when (estadoDeEntrada(...))`, dejando solo el contenido de la rama `Adentro`:

```kotlin
if (isTv) {
    ArkivTvRoot(
        deepLinkEpisodeId = pendingEpisode,
        onDeepLinkConsumed = { pendingEpisode = null },
    )
} else {
    ArkivRoot(
        deepLinkEpisodeId = pendingEpisode,
        onDeepLinkConsumed = { pendingEpisode = null },
    )
}
```

El gate de integridad (`motivosParaNoArrancar`) que corre ANTES no se toca: ese sigue siendo la única razón para no arrancar.

- [ ] **Step 2: Sacar los imports que quedaron sin uso**

`EntradaViewModel`, `PantallaDeEntrada`, `estadoDeEntrada`, `EstadoDeEntrada` y lo que arrastren.

- [ ] **Step 3: Correr la suite y ensamblar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verificar EN EL APARATO que abre sin login**

Este es el commit donde el objetivo del sub-proyecto ya se puede comprobar, y hay que comprobarlo antes de seguir borrando:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.arkiv.player.light   # simula un aparato sin sesion guardada
adb shell monkey -p com.arkiv.player.light -c android.intent.category.LAUNCHER 1
```

Esperado: la app entra al home sin pedir nada. **Ojo**: `pm clear` borra también la biblioteca y el vínculo de Magis de ESE aparato; hacerlo en el TV de prueba, no en un aparato con biblioteca que importe. Si no hay un aparato descartable a mano, saltear el `pm clear` y confirmar al menos que abre.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(light): la app abre sin login

MainActivity pierde el gate de sesion: queda solo el de integridad. Con esto Kino L ya no
depende de PocketBase ni del gateway para ABRIR, que es el objetivo del sub-proyecto 2B.
El subsistema de cuentas todavia esta compilado: lo borran las tareas siguientes."
```

---

### Task 7: Rescatar del store cifrado lo que no es de la cuenta

**Files:**
- Modify: `app/src/main/java/com/arkiv/player/data/SettingsStore.kt`
- Test: `app/src/test/java/com/arkiv/player/data/MigracionDePrefsTest.kt` (crear)
- Modify: `app/src/main/java/com/arkiv/player/ui/tv/ArkivTvRoot.kt:166`, `app/src/main/java/com/arkiv/player/ui/tv/TvLiveGuideScreen.kt:135`, `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsApp.kt:96-120`
- Modify: `app/src/main/java/com/arkiv/player/ArkivApp.kt:53-55` (`recientesPurgados`)

**Interfaces:**
- Consumes: nada.
- Produces: `SettingsStore.adultosDesbloqueado: StateFlow<Boolean>` + `setAdultosDesbloqueado(v: Boolean)`, y `SettingsStore.recientesPurgados: Boolean` + `setRecientesPurgados(v: Boolean)`. La Task 9 borra el store cifrado del aparato confiando en que esto ya corrió.

**Contexto:** `magisOfertaDescartada` NO entra acá — ya vive en `SettingsStore`. Lo único que
desaparece es su reset en el logout (`onLocalWipe`), y eso es correcto: sin cuentas no hay logout, y
el "Ahora no" es del aparato. El candado 18+ y el marcador de la purga única de recientes sí viven en `SecureDeviceStore` (prefs cifradas del aparato), que se borra con las cuentas. No son datos de cuenta: si se pierden, la sección 18+ se esconde sola —y eso parece un bug de otra cosa— y la purga vuelve a correr.

- [ ] **Step 1: Escribir el test que falla**

`SettingsStore` toma un `Context`, así que el test ejercita la lógica pura de la migración —una
función de nivel superior— y no la clase entera:

```kotlin
package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `valorMigrado(deSettings, deStoreViejo, default)`: `null` en cualquiera de los dos primeros
 * significa "esa clave no existe ahí".
 */
class MigracionDePrefsTest {

    @Test
    fun `el candado 18+ desbloqueado sobrevive a la migracion`() {
        assertEquals(true, valorMigrado(deSettings = null, deStoreViejo = true, default = false))
    }

    @Test
    fun `lo que ya esta en Settings manda: el valor viejo no resucita`() {
        // La persona volvió a trabar el candado DESPUÉS de migrar: el true del store viejo sigue
        // ahí, pero no puede volver en el próximo arranque.
        assertEquals(false, valorMigrado(deSettings = false, deStoreViejo = true, default = false))
    }

    @Test
    fun `sin store viejo se queda el default`() {
        assertEquals(false, valorMigrado(deSettings = null, deStoreViejo = null, default = false))
    }

    @Test
    fun `la purga de recientes ya aplicada no se vuelve a correr`() {
        assertEquals(true, valorMigrado(deSettings = null, deStoreViejo = true, default = false))
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.MigracionDePrefsTest"`
Expected: FAIL (`valorMigrado` no existe).

- [ ] **Step 3: Implementar la migración**

En `SettingsStore.kt`, agregar la función pura y las dos preferencias nuevas:

```kotlin
/**
 * Qué valor queda tras mudar una preferencia del store cifrado del aparato a estos ajustes.
 * Lo que ya esté acá MANDA: si la persona cambió el valor después de migrar, el viejo no puede
 * resucitar en el próximo arranque.
 */
internal fun valorMigrado(deSettings: Boolean?, deStoreViejo: Boolean?, default: Boolean): Boolean =
    deSettings ?: deStoreViejo ?: default
```

Y en la clase, dos preferencias con el mismo patrón que `magisOfertaDescartada` (una `MutableStateFlow` respaldada por `prefs`, y su setter). La lectura inicial usa `valorMigrado(...)` con el valor del store cifrado, que se lee UNA vez desde `SecureDeviceStore` en `ArkivApp.onCreate` y se escribe acá.

- [ ] **Step 4: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.MigracionDePrefsTest"`
Expected: PASS.

- [ ] **Step 5: Mutar para comprobar que el test muerde**

Cambiar `deSettings ?: deStoreViejo ?: default` por `deStoreViejo ?: deSettings ?: default` y correr: tiene que fallar el test de "lo que ya está en Settings manda". Revertir.

- [ ] **Step 6: Mover los tres lectores del candado**

`ArkivTvRoot.kt:166`, `TvLiveGuideScreen.kt:135` y `TvSettingsApp.kt:96-120` pasan de `graph.deviceStore.adultosDesbloqueado()` a `graph.settings.adultosDesbloqueado` (y de `setAdultosDesbloqueado` al de `settings`). En `ArkivApp.kt`, `recientesPurgados` pasa a leerse/escribirse en `settings`.

- [ ] **Step 7: Correr la suite y ensamblar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A app/src
git commit -m "refactor(light): mudar el candado 18+ y la purga de recientes a SettingsStore

Las dos vivian en el store cifrado del aparato, que se borra con las cuentas, y ninguna es
un dato de cuenta. Si el candado se pierde, la seccion 18+ se esconde sola y parece un bug
de otra cosa: por eso la migracion va con test (y lo que ya este en Settings manda, para que
un valor viejo no resucite si la persona lo cambio despues)."
```

---

### Task 8: `CuentaDeMagis` — el vínculo parado sobre sus propios pies

**Files:**
- Create: `app/src/main/java/com/arkiv/player/data/magis/CuentaDeMagis.kt`
- Test: `app/src/test/java/com/arkiv/player/data/magis/CuentaDeMagisTest.kt`
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt` (exponerla; `accountManager` deja de usarse en las pantallas)
- Modify: `app/src/main/java/com/arkiv/player/ui/settings/AccountSection.kt`, `app/src/main/java/com/arkiv/player/ui/tv/TvSettingsCuenta.kt`, `app/src/main/java/com/arkiv/player/ui/tv/TvOfertaVincularMagis.kt`

**Interfaces:**
- Consumes: `MagisSession` (2A: `login`, `logout`, `hasAccountLinked`, `emailVinculado`).
- Produces: `CuentaDeMagis.estado: StateFlow<EstadoDeMagis>` con `EstadoDeMagis.Sin` / `EstadoDeMagis.Vinculada(email: String)`, `suspend fun vincular(email: String, clave: String)` (lanza `MagisException`), `suspend fun desvincular()`.

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuentaDeMagisTest {

    private fun cuenta(portal: FakePortalClient, store: FakeCredentialStore = FakeCredentialStore()) =
        CuentaDeMagis(MagisSession(portal, store))

    @Test
    fun `arranca en Sin y refrescar lee lo que hay guardado`() = runTest {
        val store = FakeCredentialStore()
        val c = cuenta(FakePortalClient(), store)
        assertEquals(EstadoDeMagis.Sin, c.estado.value)

        store.guardarCuenta("persona@ejemplo.com", "clave123")
        c.refrescar()

        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)
    }

    @Test
    fun `vincular con credenciales que el portal acepta deja Vinculada con el email`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", portalOk("userId" to "u1", "userToken" to "t1"))
        val c = cuenta(portal)

        c.vincular("persona@ejemplo.com", "clave123")

        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)
    }

    @Test
    fun `credenciales rechazadas no cambian el estado y el mensaje lo dice`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", MagisResult.PortalError("aaa100015", "clave mala"))
        val c = cuenta(portal)

        val e = runCatching { c.vincular("persona@ejemplo.com", "mala") }.exceptionOrNull()

        assertTrue(e is MagisException)
        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("inválidas"))
        assertEquals(EstadoDeMagis.Sin, c.estado.value)
    }

    @Test
    fun `el portal caido se distingue de una clave mala`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", MagisResult.RedError(java.io.IOException("sin red")))

        val e = runCatching { cuenta(portal).vincular("a@b.com", "x") }.exceptionOrNull()

        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("no disponible"))
    }

    @Test
    fun `desvincular vuelve a Sin`() = runTest {
        val portal = FakePortalClient()
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u", "t", "", "sn"))
        store.guardarCuenta("persona@ejemplo.com", "clave123")
        val c = cuenta(portal, store)
        c.refrescar()
        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)

        c.desvincular()

        assertEquals(EstadoDeMagis.Sin, c.estado.value)
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.CuentaDeMagisTest"`
Expected: FAIL (`CuentaDeMagis` no existe).

- [ ] **Step 3: Implementar `CuentaDeMagis`**

```kotlin
package com.arkiv.player.data.magis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mensaje listo para mostrar: distingue "la clave está mal" de "Magis no contesta". */
class MagisException(mensaje: String) : RuntimeException(mensaje)

sealed interface EstadoDeMagis {
    data object Sin : EstadoDeMagis
    data class Vinculada(val email: String) : EstadoDeMagis
}

/**
 * El vínculo con Magis visto desde la UI. Reemplaza lo único que quedaba vivo de
 * `AccountManager`: sostener si hay cuenta y avisar cuando cambia.
 */
internal class CuentaDeMagis(private val session: MagisSession) {
    private val _estado = MutableStateFlow<EstadoDeMagis>(EstadoDeMagis.Sin)
    val estado: StateFlow<EstadoDeMagis> = _estado.asStateFlow()

    /**
     * Se llama una vez al entrar a la pantalla, NO en el constructor: leer el email sale de
     * `EncryptedSharedPreferences` (disco + descifrado) y este objeto se construye desde `AppGraph`,
     * que se toca desde el hilo principal. En el KALLEY eso son milisegundos que se notan.
     */
    suspend fun refrescar() = withContext(Dispatchers.IO) {
        _estado.value = session.emailVinculado()?.let { EstadoDeMagis.Vinculada(it) } ?: EstadoDeMagis.Sin
    }

    suspend fun vincular(email: String, clave: String) {
        when (session.login(email, clave)) {
            is MagisResult.Ok -> _estado.value = EstadoDeMagis.Vinculada(email)
            is MagisResult.RedError -> throw MagisException("Magis no disponible")
            // El portal dice POR QUÉ en chino: se muestra el nuestro y el suyo queda en el log.
            is MagisResult.PortalError -> throw MagisException("Credenciales de Magis inválidas")
        }
    }

    suspend fun desvincular() {
        session.logout()
        _estado.value = EstadoDeMagis.Sin
    }
}
```

- [ ] **Step 4: Correr el test hasta que pase**

Run: `./gradlew :app:testDebugUnitTest --tests "com.arkiv.player.data.magis.CuentaDeMagisTest"`
Expected: PASS.

- [ ] **Step 5: Mutar para comprobar que los tests muerden**

Hacer que `vincular` ponga `Vinculada` también en la rama `PortalError`: tiene que fallar el test de credenciales rechazadas. Revertir.

- [ ] **Step 6: Exponerla en `AppGraph` y reescribir las tres pantallas**

`AppGraph`: `internal val cuentaDeMagis: CuentaDeMagis by lazy { CuentaDeMagis(magisSession) }`.

Las tres pantallas llaman a `refrescar()` en un `LaunchedEffect(Unit)` al componerse.

En `AccountSection.kt` (celu), `TvSettingsCuenta.kt` y `TvOfertaVincularMagis.kt`: reemplazar `AccountManager`/`AccountState` por `CuentaDeMagis`/`EstadoDeMagis`, y `AccountException` por `MagisException`. En la oferta del TV, la condición pasa a ser `estado is EstadoDeMagis.Sin && !descartada`, y el email **deja de venir precargado** (lo llenaba la cuenta de Kino). Actualizar `debeOfrecerVincularMagis` y su test (`TvOfertaVincularMagisTest.kt`) a la firma nueva.

- [ ] **Step 7: Correr la suite y ensamblar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A app/src
git commit -m "feat(light): CuentaDeMagis, el vinculo sin cuenta de Kino por debajo

Lo unico vivo que quedaba de AccountManager era sostener si hay cuenta de Magis y avisar
cuando cambia. Eso pasa a una clase propia sobre MagisSession, y las tres pantallas
(Ajustes del celu, Ajustes del TV y la oferta del TV) hablan con ella. Se conserva la
distincion que importa: 'credenciales invalidas' y 'Magis no disponible' son cosas
distintas porque lo que la persona hace despues es distinto."
```

---

### Task 9: Borrar el subsistema de cuentas

**Files:**
- Move: `app/src/main/java/com/arkiv/player/pocketbase/PrefsCifradas.kt` → `app/src/main/java/com/arkiv/player/data/magis/PrefsCifradas.kt` (y su test, si tiene)
- Delete: el resto de `app/src/main/java/com/arkiv/player/pocketbase/`
- Delete: `app/src/main/java/com/arkiv/player/data/gateway/CuentaApi.kt`, `app/src/main/java/com/arkiv/player/data/gateway/InterceptorDeSesion.kt`
- Delete: `app/src/main/java/com/arkiv/player/ui/entrada/`, `app/src/main/java/com/arkiv/player/ui/tv/TvPantallaDeEntrada.kt`, `app/src/main/java/com/arkiv/player/ui/settings/MisAparatos.kt`
- Delete: `app/src/main/java/com/arkiv/player/crash/CrashUploader.kt` y su test
- Delete: `app/src/main/java/com/arkiv/player/data/LibraryWiper.kt` y su test
- Modify: `app/src/main/java/com/arkiv/player/AppGraph.kt`, `app/src/main/java/com/arkiv/player/ArkivApp.kt`, `app/src/main/java/com/arkiv/player/crash/Crash.kt`

**Interfaces:**
- Consumes: Tasks 6, 7 y 8 (nadie mira ya la sesión, las preferencias ya se migraron y el vínculo de Magis ya no pasa por `AccountManager`).
- Produces: nada. Es la tarea que cierra.

- [ ] **Step 1: Mudar `PrefsCifradas` ANTES de borrar el paquete**

```bash
git mv app/src/main/java/com/arkiv/player/pocketbase/PrefsCifradas.kt \
       app/src/main/java/com/arkiv/player/data/magis/PrefsCifradas.kt
```

Cambiarle el `package` a `com.arkiv.player.data.magis` y arreglar el import en `MagisCredentialStore.kt`. Si tiene test, mudarlo igual.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (con el resto del paquete todavía en pie).

- [ ] **Step 2: Sacar la subida de crashes, conservando el reporte local**

En `Crash.kt`, borrar la llamada al `CrashUploader` y dejar el guardado/log local tal cual. Después `git rm` el uploader y su test.

```bash
grep -rn "CrashUploader\|crashUploader" app/src/main/java
```

Esperado tras el cambio: vacío.

- [ ] **Step 3: Borrar el resto**

```bash
git rm -r app/src/main/java/com/arkiv/player/pocketbase app/src/main/java/com/arkiv/player/ui/entrada
git rm app/src/main/java/com/arkiv/player/ui/tv/TvPantallaDeEntrada.kt \
       app/src/main/java/com/arkiv/player/ui/settings/MisAparatos.kt \
       app/src/main/java/com/arkiv/player/data/gateway/CuentaApi.kt \
       app/src/main/java/com/arkiv/player/data/gateway/InterceptorDeSesion.kt \
       app/src/main/java/com/arkiv/player/data/LibraryWiper.kt
```

Y sus tests (`app/src/test/java/com/arkiv/player/pocketbase/`, `ui/entrada/`, los de `CuentaApi`, del interceptor y del wiper).

- [ ] **Step 4: Limpiar `AppGraph` y `ArkivApp`**

`AppGraph`: borrar `pbClient`, `deviceStore`, `deviceAuth`, `sesionDePersona`, `cuentaApi`, `accountManager`, `libraryWiper` y el `InterceptorDeSesion` del `httpGateway`. Ese `OkHttpClient` pasa a tener un solo usuario —el cliente del portal— así que se renombra a `httpDelPortal` y su KDoc queda diciendo lo que hace: el `callTimeout` de 45 s y el `readTimeout` paciente de 25 s que necesita el portal para resolver algunos canales. `ArkivApp.onCreate`: borrar el bloque de `DuenoDeLaBase` y la lectura del store del aparato.

- [ ] **Step 5: Compilar, correr la suite y ensamblar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Los tests que se caigan van a ser de lo borrado: revisarlos uno por uno y borrar solo los que fijaban comportamiento que ya no existe.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(light): borrar cuentas, identidad de aparato y subida de crashes

Se va todo pocketbase/ (salvo PrefsCifradas, que se muda a data/magis porque lo usa el
store de credenciales de Magis), CuentaApi, el interceptor de sesion, las pantallas de
entrada, Mis Aparatos, LibraryWiper (su unico llamador era el logout) y el CrashUploader.
Los crashes se siguen guardando y logueando en el aparato: se lee por adb, que es como se
diagnostico el vivo."
```

---

### Task 10: Limpieza final y verificación en dispositivo

**Files:**
- Modify: `CLAUDE.md` (reglas de la rama)
- Modify: los KDoc que quedaron mintiendo (buscarlos con el grep del Step 2)

**Interfaces:**
- Consumes: Tasks 1-9.
- Produces: la rama documentada como está.

- [ ] **Step 1: Barrido de infraestructura propia**

```bash
grep -rn "comparadorinternet\|pocketbase\|gatewayUrl\|/v1/" app/src/main/java | grep -v "^.*://.*magis"
```

Esperado: **solo** el OTA (`apk.comparadorinternet.co` en `UpdateChecker`). Cualquier otra cosa que aparezca es algo que se olvidó de sacar. Si `settings.gatewayUrl`/`DEFAULT_GATEWAY_URL` quedaron sin lectores, borrarlos también.

- [ ] **Step 2: Corregir los comentarios que quedaron viejos**

```bash
grep -rn "gateway\|sesión de la persona\|X-Arkiv" app/src/main/java --include=*.kt | grep -iE "kdoc|\*|//" | head -40
```

Corregir los que digan cosas que ya no son ciertas (`DestructorDeFrames` menciona `LibraryWiper`; `SettingsStore` y `AppGraph` hablan del gateway unificado).

- [ ] **Step 3: Actualizar el `CLAUDE.md` de la rama**

La lista de llamadas permitidas queda: portal de Magis, su CDN, TMDB y el OTA. La trivia deja de figurar como excepción permanente — se resignó en este sub-proyecto.

- [ ] **Step 4: Verificar en el KALLEY R3**

```bash
./gradlew :app:assembleDebug
adb connect <ip>:5555   # ver `adb mdns services` si cambió la IP
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.arkiv.player.light -c android.intent.category.LAUNCHER 1
```

Comprobar, en este orden:
1. la app **abre sin pedir login**;
2. la biblioteca sigue entera (los capítulos de Naruto);
3. Magis sigue vinculado (su store cifrado es otro archivo y no se tocó);
4. un canal en vivo reproduce;
5. el candado 18+ sigue como estaba (si estaba desbloqueado, la sección 18+ sigue apareciendo).

- [ ] **Step 5: Commit final**

```bash
git add -A
git commit -m "docs(light): la rama sin login, documentada

CLAUDE.md queda con la lista real de llamadas permitidas (portal de Magis, su CDN, TMDB y el
OTA) y sin la excepcion permanente de la trivia, que se resigno en el sub-proyecto 2B."
```
