# Spec — Recomendaciones a partir del historial

- **Fecha:** 2026-08-16
- **Estado:** Diseño aprobado (pendiente de plan de implementación)
- **Repos afectados:** `archive` (app Android), `arkiv-api` (gateway) y PocketBase en `blog`
- **Relación con specs previos:** usa la identidad por cuenta del
  [Spec — Login obligatorio y licencias](2026-08-12-licencias-y-login-obligatorio-design.md)
  y viaja por el sync existente (cursor + SSE) de `CloudSyncManager`.

## Problema

Cuando terminas una serie no hay nada que te diga qué sigue. La biblioteca muestra lo que ya
agregaste y el catálogo muestra lo que es popular, pero nada mira **lo que tú viste** para
proponerte algo.

El objetivo es una fila **"Para ti"** en la pantalla de inicio, que se recalcula **cuando terminas
algo**, con títulos que **se pueden reproducir de verdad**.

## Lo que ya existe (verificado en código y contra PocketBase el 2026-08-16)

No hace falta construir infraestructura para esto:

- **La materia prima ya está en la nube y por cuenta.** `progress` (`episodeId`, `positionMs`,
  `durationMs`, `watched`, `lastPlayedAt`, `accountId`) cruzable con `episodes` y `library_items`.
- **El transporte ya existe.** `CloudSyncManager` pagina por cursor y se suscribe por SSE a una
  lista de colecciones ([CloudSyncManager.kt:327 y :349](../../../app/src/main/java/com/arkiv/player/cloudsync/CloudSyncManager.kt)),
  con merge LWW + tombstones. Sumar una colección es agregarla a esas dos listas.
  **Consecuencia importante: la fila se enciende sola, sin polling nuevo.**
- **El modelo ya está pago y andando.** MiniMax por API compatible con Anthropic
  (`https://api.minimax.io/anthropic`, modelo `MiniMax-M3`), hoy usada por `utiles-escolares`,
  `eventos-scraper` y `content-gen-utiles` en Coolify.
- **El inicio ya sabe dibujar filas** ("Continuar viendo", "Canales en vivo").

### La restricción que define el diseño

Medido el 2026-08-16 contra la API real: **10,1 s en frío, 4,6 s y 1,35 s en caliente**. Eso
descarta cualquier uso interactivo y obliga a que todo corra fuera del camino donde alguien espera.
Es exactamente por eso que el disparo es "al terminar algo" y no "al abrir la pantalla".

## Decisiones tomadas

| Decisión | Elegido | Por qué |
|---|---|---|
| Superficie | Fila en el inicio | Encaja con la forma que la app ya tiene; si no hay nada, no aparece |
| Candidatos | Verificados antes de mostrar | Una recomendación que no se reproduce es peor que ninguna |
| Alcance | Todas las cuentas, con interruptor | Permite prenderlo solo para Cristian primero |
| Frecuencia | Al terminar algo | Es el momento en que la pregunta "¿qué sigo?" existe de verdad |
| Disparo | La app avisa al gateway | Inmediato, y deja la lógica en Python, donde hay tests |

**Consecuencia aceptada del disparo por evento:** si pasan semanas sin terminar nada, la fila queda
congelada. Se asume; agregar un refresco de base después es barato.

## Arquitectura

### Qué cuenta como "terminaste algo"

**Cualquier `progress` que pase a `watched = true`** — una película, o un capítulo cualquiera de una
serie. NO se intenta detectar "el último capítulo de la temporada": saber cuántos capítulos tiene
una serie no siempre es posible (depende de la fuente), y una detección que a veces falla produce
una función que a veces no anda, sin que se note por qué.

Lo que evita que eso dispare de más es la **deduplicación por tiempo**, no la precisión del evento:
si ya se calculó para esa cuenta hace menos de **6 horas**, el disparo se descarta. Maratonear una
temporada entera produce un solo cálculo.

```
[App] marca algo como visto
   │  POST /v1/recomendaciones/refrescar   (dispara y se olvida; responde al instante)
   ▼
[Gateway arkiv-api]
   1. ¿interruptor prendido?  no → corta sin gastar nada
   2. deduplica por cuenta    ¿ya calculé para este evento? → corta
   3. lee el historial        progress + episodes + library_items (como admin)
   4. arma el prompt          terminado / abandonado / repetido
   5. MiniMax                 ~20 candidatos + el porqué de cada uno
   6. verifica en cascada     existe → no visto → tiene fuente
   7. escribe                 colección `recomendaciones`
   ▼
[PocketBase] ──SSE──▶ [App] sync existente → Room → fila "Para ti"
```

### Por qué el gateway hace todo

Es el único que puede tener la llave del modelo. La app **no** puede: esa lección ya se pagó cuando
`ARKIV_API_KEY` era una constante compilada que cualquiera extraía del APK. La app pide un refresco
y después lee una fila, igual que lee "Continuar viendo".

## Señal que entra al prompt

Las últimas ~30 entradas, no el historial entero: más filas no mejoran la recomendación y
multiplican el costo. Tres señales, que significan cosas distintas:

- **Terminado** — llegaste al final.
- **Abandonado** — empezaste y no volviste. Vale tanto como lo anterior: dice qué **no** proponer.
- **Repetido** — la señal más fuerte.

### Regla no negociable: nada de contenido adulto

No entra como señal y no puede salir como sugerencia. Es coherente con la regla que ya existe en el
reproductor (un canal de adultos no se anota en el historial), y aquí importa más todavía: la fila
"Para ti" está en la pantalla de inicio, a la vista de cualquiera que prenda el TV.

### Salida del modelo

JSON estricto (ya hay `LLM_JSON_MODE=auto`): título, año, tipo (película/serie) y **el porqué en una
frase**. El porqué no es decorativo — convierte "una película más" en "esto, porque terminaste
Dragon Ball".

## Verificación en cascada

En este orden, del descarte más barato al más caro:

1. **¿Existe?** Búsqueda en TMDB. Si TMDB no lo conoce, era una alucinación → afuera. De paso se
   obtiene el `tmdbId` real, el título en es-MX y el póster.
2. **¿Ya lo viste?** Contra biblioteca e historial → afuera.
3. **¿Se puede reproducir?** Recién aquí se consultan las fuentes (magis/torrent/web) con presupuesto
   de tiempo. Sin fuente → afuera.

Se piden ~20 para quedarse con ~10: la cascada descarta bastante y pedir de más es más barato que
quedarse corto.

## Datos

### Colección `recomendaciones` (PocketBase)

Campos: `accountId`, `tmdbId`, `tipo`, `titulo`, `posterUrl`, `porque`, `ref` (la fuente ya
resuelta), `orden`, `generadoAt`, `updatedAt`, `deleted`.

Se guarda **como dato, no como pantalla**: con el `ref` ya resuelto, mostrar la fila también en el
celular después no cuesta nada.

### Interruptor

Campo booleano en `users`, **apagado por defecto**. El gateway lo consulta antes de cualquier
trabajo. En la app, un switch en Ajustes → Cuenta (`TvSettingsCuenta.kt`), que es donde ya vive lo
que es por-persona.

## Manejo de errores

- **El disparo no bloquea nunca.** El endpoint responde al instante y el trabajo sigue por detrás.
  Responder al final dejaría a la app esperando 30 s o más justo al terminar un capítulo.
- **Un fallo no empeora lo que ya había.** Si MiniMax se cae, devuelve basura o las fuentes no
  responden, la fila anterior **sobrevive**. Una fila vacía por un error transitorio se ve idéntica
  a "no tengo nada para ti".
- **Tope por cuenta y por día.** Hoy la llave la comparten tres apps: sin tope, un bucle en Kino
  apaga el bot. **Recomendado: llave propia de MiniMax para Kino.**
- **Deduplicación**: candado por cuenta + ventana mínima de **6 h** entre cálculos (ver "Qué cuenta
  como terminaste algo"). Maratonear una temporada, o el celular y el TV avisando lo mismo, producen
  un solo cálculo.

## Pruebas

El modelo va **detrás de una interfaz** para que los tests inyecten respuestas preparadas y no
llamen a MiniMax. En el gateway (pytest, donde ya hay 695 tests):

- un título inventado se cae en TMDB
- algo ya visto no se recomienda
- algo sin fuente no llega a la pantalla
- el contenido adulto no entra como señal ni sale como sugerencia
- si el modelo falla, la fila anterior sobrevive
- tres capítulos seguidos = un solo cálculo
- interruptor apagado = cero llamadas al modelo

En Android: que la fila aparezca con datos, que no aparezca cuando no hay, y que el disparo salga
una sola vez.

### Lo que los tests NO pueden decir

Si las recomendaciones son **buenas**. Ningún verde prueba eso. Por eso el plan incluye un comando
para correr la generación a mano contra una cuenta real y **mirar la salida cruda** antes de darlo
por bueno — el mismo criterio de ejecutar y ver en vez de leer y suponer.

## Fuera de alcance

- Fila en el celular (el dato queda listo; la UI se hace después si se quiere).
- Refresco periódico de base.
- Aprender de si aceptaste o ignoraste una recomendación.
