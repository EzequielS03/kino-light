# Spec — Saltar intro y outro sin teclear nada

- **Fecha:** 2026-08-19
- **Estado:** Diseño propuesto (pendiente de revisión y de plan de implementación)
- **Repos afectados:** `archive` (app Android) y `arkiv-api` (gateway)
- **De qué depende:** del `tmdbId` de la biblioteca, que quedó puesto con la canonización de
  títulos del 2026-08-19 (`arkiv-api` f5c9264 y e20a3e2, app 9dd3c6b0). Sin esa identidad no hay con
  qué preguntar: al medirlo, 155 de 213 ítems no tenían `tmdbId`, y hoy la cuenta viva está en 50 de
  56 con obra identificada.

## Problema

Los botones **"Saltar intro"** y **"Saltar outro"** ya están escritos
([PlayerScreen.kt:2623](../../../app/src/main/java/com/arkiv/player/ui/player/PlayerScreen.kt)),
con su diálogo de edición, su tabla y su sync. Y **nunca se usaron**: la tabla `markers` de
PocketBase tiene **0 filas** en producción.

El motivo es que llenarla exige abrir un diálogo y teclear `1:30` a mano, por serie. Nadie lo hizo
nunca. Es una función completa que no se encendió jamás por el coste de darle de comer.

Y el sitio donde más haría falta es justo donde el botón **no se dibuja**: el gate actual lo apaga
en el TV (`!isTv`) y en torrents, y el anime —90 s de opening en cada capítulo, que es lo que se ve
en el Fire TV— cae de lleno ahí.

## Lo que se midió (2026-08-19, contra la biblioteca real)

**Los subtítulos NO sirven, y esto se probó antes de diseñar nada.** Se bajaron subtítulos reales de
OpenSubtitles (la llave sola alcanza: ~100 descargas/día, sin login) para tres capítulos de
Evangelion. El opening no se distingue: la primera línea de diálogo cae a 1:55 en un capítulo y a
1:32 en otro, y ese silencio inicial es *cold open + opening* mezclados. Ningún release traía líneas
de letra de canción. **No hay corte que detectar.** La hipótesis inicial era falsa.

**AniSkip sí sirve.** Base comunitaria de tiempos de opening/ending, sin llave y sin login:

```
GET https://api.aniskip.com/v2/skip-times/{mal_id}/{episodio}?types=op&types=ed&episodeLength=0

Evangelion (MAL 30) ep1 →  op: 0s → 90s     ed: 1319s → 1394s
```

Devuelve **los dos tramos en la misma llamada**, que es exactamente lo que pide `SkipMarkerEntity`
(`openingStartMs`, `openingEndMs`, `endingStartMs`).

Cobertura medida sobre 12 series de la biblioteca real, 4 capítulos cada una: **37 de 48 (77 %)**.

| serie | cobertura | ejemplo |
|---|---|---|
| Death Note, Attack on Titan, Demon Slayer, Hunter x Hunter, Dragon Ball, Steins;Gate | 4/4 | `ep1 1-91s` |
| Evangelion, Cowboy Bebop, Fullmetal Alchemist: B. | 3/4 | `ep1 0-90s` |
| My Hero Academia, Samurai Champloo | 2/4 | `ep2 162-252s` |
| Dragon Ball Daima | 0/4 | demasiado nueva |

**Coste: cero tokens.** No entra ningún modelo. Es un GET por capítulo, cacheable.

## El hallazgo que decide la forma

Un marcador **por serie** está mal, y hay prueba:

```
Demon Slayer   ep1: 1270-1360s    ep2: 57-147s
Steins;Gate    ep1:  638-728s     ep2: 80-170s
```

En esos capítulos el opening va a los 21 y a los 10 minutos —cold opens largos, recapitulaciones,
episodios especiales—. Con el modelo actual (una fila por `itemId`, "aplican a todos los episodios
de esta serie") el botón te tiraría a la mitad del capítulo. **Los marcadores tienen que ser por
episodio.**

## Diseño

### 1. `skip_markers` gana el episodio (app, migración de Room)

Hoy la clave primaria es `itemId`. Pasa a ser una llave DERIVADA de los dos:

```kotlin
@Entity(tableName = "skip_markers")
data class SkipMarkerEntity(
    /** `"<itemId>|<episodeId>"`. Ver [markerIdDe]. */
    @PrimaryKey val id: String,
    val itemId: String,
    /** "" = vale para toda la serie (el marcador que se pone a mano). */
    val episodeId: String = "",
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)
```

**Derivada y no compuesta (`primaryKeys = [...]`), por el sync.** `CloudSyncManager.pushRows` busca
la fila remota por UN campo natural por colección (`items=identifier`, `progress=episodeId`,
`markers=itemId`). Una clave compuesta obligaría a cambiar ese mecanismo para todas las colecciones;
una llave derivada encaja en el que ya existe — igual que `episodes`, que sincroniza por `epId`.

**`episodeId = ""` conserva el marcador manual tal como funciona hoy**, y la búsqueda del player es:
el del episodio exacto si existe, y si no el de la serie. Así lo automático y lo manual conviven sin
pisarse, y quien marcó algo a mano no lo pierde.

La migración es barata **porque la tabla está vacía en producción**: se recrea con la clave nueva.
No hay dato que preservar, y eso hay que aprovecharlo ahora — dentro de un mes ya no será verdad.

En PocketBase, la colección `markers` gana `markerId` (la llave derivada, que pasa a ser su
campo natural en el sync) y `episodeId`; el índice único pasa a `(accountId, markerId)`.

### 2. El gateway resuelve los tiempos (`GET /v1/marcadores`)

```
GET /v1/marcadores?tmdbId=890&temporada=1&episodio=7
  → {"openingStartMs": 0, "openingEndMs": 90000, "endingStartMs": 1319000}
  → {} si no se sabe
```

Por qué el gateway y no la app directo contra AniSkip:

- **El puente `tmdbId → MAL` vive acá.** AniSkip usa ids de MyAnimeList; la app solo tiene `tmdbId`.
  El dataset de Fribb que el gateway ya descarga **trae el `mal_id` y hoy lo tira**: el indexador se
  queda solo con `tvdb_season` y `tmdb_id` (`arkiv-api`, `src/arkiv_api/anime/meta.py:79`).
  Conservarlo y armar el índice inverso `tmdb → mal` es el único cambio de datos que hace falta, y
  no cuesta una descarga nueva.
- **La caché es compartida.** Un `tmdbId+temporada+episodio` que ya se resolvió sirve para todos los
  aparatos y para todas las cuentas: los tiempos de un capítulo no son datos de nadie. Redis, 30
  días — es un dato que no cambia.
- **La fuente se puede cambiar sin tocar la app.** Hoy AniSkip; mañana Crunchyroll u otra (ver
  "Fuera de alcance"). La app pide "los marcadores de este capítulo", no "esto a AniSkip".

Nunca falla hacia el cliente: sin `mal_id`, sin datos o con AniSkip caído responde `{}` y la app se
comporta igual que hoy. Mismo criterio que `/v1/trivia`.

### 3. La app pide y guarda (perezoso, no un barrido)

Al empezar a reproducir un capítulo, si no hay marcador guardado para él, se le pide al gateway y
**se guarda en `skip_markers`**. Una sola petición, ~200 ms, en paralelo con la resolución de la
fuente.

Perezoso y no un trabajo que baje la serie entera: Dragon Ball son 153 capítulos y solo se ven en
orden. Y como `skip_markers` **se sincroniza**, lo que se resolvió en el celu ya está en el TV sin
volver a preguntar, y funciona sin red la segunda vez.

Consecuencia a tener presente: `episodeId` es por ADQUISICIÓN (`magis:XXX::e1`), no por obra, así
que la misma serie guardada desde dos fuentes pide dos veces. No se arregla acá y no hace falta: la
segunda la sirve la caché del gateway, que sí está por obra.

### 4. Destapar el botón

El gate de hoy:

```kotlin
if (d != null && !isTorrent && !marcadores.marcando && !isTv && estadoDlna.activo == null) {
```

- **Se va `!isTv`**: es el caso principal. En TV el botón necesita foco propio (D-pad) y no
  desaparecer al aparecer, lo que se resuelve como cualquier otro control enfocable de la pantalla.
- **Se va `!isTorrent`**: los marcadores ahora salen de la identidad de la obra, no de la fuente del
  archivo. Un capítulo de anime por torrent tiene el mismo opening.
- **Se queda `estadoDlna.activo == null`** (no controlamos la posición en un renderer ajeno) y
  `!marcadores.marcando` (el editor manual tapa los controles a propósito).

## Riesgos

**El desfase de release, que es el riesgo real.** Los tiempos de AniSkip son del release canónico.
Si tu copia arranca con un logo de distribuidora, el marcador queda corrido unos segundos. Con
Magis —un encode consistente— probablemente calce, pero **eso solo se sabe mirando un capítulo de
verdad**: es la primera comprobación del plan, antes de escribir la UI. Si el desfase existe y es
constante por fuente, se corrige con un offset; si es errático, esta fuente no sirve para esa fuente
de video y hay que decirlo.

**Cobertura del 77 %, no del 100 %.** Lo que no está no muestra botón: se comporta igual que hoy,
sin error ni hueco. Y el marcador manual sigue existiendo para quien quiera ponerlo.

**Un tiempo equivocado se ve y molesta.** Por eso el botón **nunca salta solo**: sigue siendo un
botón que se toca, como hoy. Saltar automático es otra decisión y no entra acá.

## Fuera de alcance

- **Crunchyroll como segunda fuente.** Cubriría el 23 % que falta y lo que no es anime, pero su
  contenedor (`crunch-app-1`) tiene el login roto desde el 7 de agosto (`SSO login falló: no se
  capturó el authorization code`) y no se pudo verificar que sus *skip events* sean accesibles. El
  diseño deja el hueco listo —el gateway decide la fuente— pero revivir ese login es otro trabajo.
- **Saltar automático sin tocar nada.**
- **Detección por audio** (huella entre capítulos, como el Intro Skipper de Jellyfin): es la única
  vía que cubriría el 100 %, y no cabe en blog (Celeron N3050) ni tiene acceso al archivo.

## Criterios de aceptación

1. Al reproducir un capítulo de una serie cubierta, el botón "Saltar intro" aparece en el momento
   correcto **en el Fire TV**, y saltar deja el video donde empieza el episodio de verdad.
2. Dos capítulos de la misma serie con openings en minutos distintos (Demon Slayer ep1 vs ep2)
   reciben marcadores distintos.
3. Un capítulo sin datos no muestra botón y no registra error.
4. El marcador puesto a mano para una serie sigue valiendo para los capítulos sin marcador propio.
5. Lo resuelto en un aparato aparece en el otro sin volver a preguntarle al gateway.
