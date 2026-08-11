# Capítulos de Magis con imagen, nombre y sinopsis reales

Fecha: 2026-08-11

## Problema

Un capítulo de una serie de Magis se ve así en toda la app: una tarjeta **negra** con el texto
`Dragon Ball Daima T1_5`. Sin imagen, sin nombre y sin sinopsis. Pasa en el detalle del TV, en el del
celular, en el overlay de pausa del reproductor, en "Continuar viendo" y en la lista de capítulos del
buscador.

**El portal no tiene esos datos.** Verificado a fondo (2026-08-10/11):

- Los ~90 endpoints de la app oficial (los declara `sb/InterfaceC5504b.java` en el decompilado) se
  enumeraron uno por uno: solo **dos** tocan un capítulo, `v4/getItemData` (el detalle) y
  `v10/startPlayVOD`. No existe endpoint de metadata ni de imágenes por episodio.
- `simpleProgramList[].posterList` (el campo donde irían las imágenes del capítulo) llega **vacío**:
  0 de ~600 capítulos en 10 series —anime y live-action—, pedidas en vivo, probando además
  `lang=es`, `sortType=1` y `type=2`.
- `startPlayVOD` en crudo no trae ningún campo de imagen: solo stream, licencia, subtítulos y nombre.
- En el código de la app oficial, de 14 archivos que manejan capítulos **uno solo** lee su
  `posterList` (`MFInfoBar`, el hero), y lo primero que hace si viene vacía es caer a las imágenes de
  la SERIE. Las cinco vistas que listan capítulos (`SelectionView`, `VodSelectionView`,
  `VodDetailsActivity`, `VodDetailBottomScrollView`, `VodDetailMenuPanel`) no cargan ninguna imagen:
  pintan `getSeriesNumber()`.

O sea: **la app oficial tampoco muestra imagen por capítulo**, y por eso escribieron ese fallback.

## La solución: el `keyWords` es un ID de IMDb

El detalle de la serie trae la llave que faltaba:

```
keyWords             = "tt29485149"                 ← ID de IMDb
contentTagUrl        = "https://www.imdb.com/title/tt29485149/parentalguide"
sameSeasonSeriesList = [{"contentId": "...", "seasonNumber": 1}]
alias                = "Dragon Ball Daima S1"
```

No es un título para adivinar: es un identificador exacto. Verificado contra TMDB:

| IMDb | TMDB `/find` | Resultado |
|---|---|---|
| `tt29485149` | 1 resultado | Dragon Ball Daima → `tmdb_id 236994` |
| `tt0903747` | 1 resultado | Breaking Bad → `tmdb_id 1396` |

Y `/tv/236994/season/1` devuelve **20 de 20 capítulos con still**, con nombres reales en español:
`E1 La conspiración`, `E2 Glorio`, `E3 Daima`, `E5 Panzy`…

Esto importa porque el `tmdbId` que hoy resuelve la app buscando **por título** es una adivinanza que
ya se sabe que falla (ver `LibraryGrouping.groupKeyOf`: en películas junta "Lego Batman" con "Batman
(1966)"). Por IMDb no hay adivinanza.

## Alcance

**Solo Magis**, y todas las superficies donde se pinta un capítulo suyo. Las otras fuentes ya
resuelven su propio `tmdbId` y no se tocan.

## Diseño

### 1. El gateway enriquece la lista de capítulos

`/v1/episodes` ya llama a `detail`, así que **ya tiene** el `keyWords` y el `sameSeasonSeriesList` en
la mano: no hace falta ninguna llamada extra al portal.

Que el enriquecimiento viva en el gateway y no en la app es lo que lo hace robusto:

- **Un solo lugar** y **caché compartido** entre todos los dispositivos (ya cachea la lista 6 h), en
  vez de que cada teléfono y cada TV repitan las mismas llamadas a TMDB.
- La llave de TMDB **no viaja en el APK** (que es la política que ya tiene el proyecto: las llaves se
  movieron al gateway y se alcanzan por `/v1/catalog/*`).
- **Cubre el buscador y la biblioteca con el mismo dato**: la respuesta que pinta la lista de
  capítulos es exactamente la que `addMagisSeason` guarda.

Pasos dentro del adapter de Magis:

1. `_capitulos_crudos` pasa a conservar también, además de `simpleProgramList`, el `keyWords` y el
   `sameSeasonSeriesList` del `detail`. Cambia la forma de lo cacheado → **subir
   `_VERSION_CACHE`** (si no, las entradas viejas se leen con la forma nueva y rompen).
2. **IMDb → TMDB**: se valida que `keyWords` matchee `^tt\d+$` (puede venir vacío o con otra cosa) y
   se resuelve con `/find/{imdb}?external_source=imdb_id`, tomando `tv_results[0]`.
3. **Número de temporada**: el elemento de `sameSeasonSeriesList` cuyo `contentId` es el nuestro. Si
   no está, **no se adivina**: no se enriquece.
4. **Capítulos**: `/tv/{tmdb_id}/season/{n}?language=es-MX`, indexados por `episode_number`.
5. Cada capítulo sale con `still`, `tmdb_title` y `overview` cuando hubo match.

Reusa el `TmdbClient` que ya existe (`catalog/tmdb.py`), que ya trae caché y cliente HTTP; se le
suman `find_by_imdb` y `season`.

**Todo best-effort.** Si TMDB falla, tarda, no conoce la serie o no tiene el capítulo, la respuesta
sale como hoy (número + nombre del portal + ref). La lista de capítulos **nunca** puede fallar porque
TMDB esté caído: es el camino por el que se reproduce.

**Forma de la respuesta** (compatible hacia atrás — los APKs viejos leen `episodes` y no se enteran
del resto):

```json
{
  "episodes": [
    {"number": 5, "title": "Dragon Ball Daima T1_5", "ref": "…",
     "still": "https://image.tmdb.org/t/p/w300/oxkBT8….jpg",
     "tmdb_title": "Panzy",
     "overview": "…"}
  ],
  "series": {"imdb_id": "tt29485149", "tmdb_id": 236994, "season_number": 1}
}
```

`title` sigue siendo **el del portal** y `tmdb_title` va aparte, a propósito: el `displayName` que se
guarda en `episodes` no cambia, y el nombre de TMDB se superpone al mostrar (que es el patrón que la
app ya usa con `observeEpisodeTitles`). Así, si mañana TMDB deja de resolver, la app no se queda sin
nombre.

El router de `/episodes` acepta que un adapter devuelva la lista pelada (como hoy) o un dict con
`episodes` + `series`, y solo agrega `series` a la salida cuando viene. Magis es hoy el único adapter
que expone capítulos, pero el router es compartido y no debe asumirlo.

#### Caché: cada dato envejece distinto

Los 6 h de la lista de capítulos son del **portal** (aparecen capítulos nuevos y hay que enterarse).
Lo de TMDB no tiene por qué compartir ese reloj — es mucho más estable, y cada llamada de más es
tráfico contra TMDB desde un servidor que ya está justo de recursos:

| Dato | Fresco | Por qué |
|---|---|---|
| `find` (imdb → tmdb id) | **30 días** | Es un identificador: no cambia nunca. Si acaso, se corrige un match malo del lado de TMDB, y para eso 30 días sobra. |
| Temporada **completa** (todos sus capítulos con still) | **14 días** | Ya no hay nada que esperar: los stills y nombres de una temporada terminada no se mueven. |
| Temporada **incompleta** (algún capítulo sin still) | **24 h** | La serie está en emisión: el still de un capítulo recién estrenado aparece en TMDB días después. Con caché largo, ese capítulo se quedaría sin imagen semanas. |

La distinción "completa vs incompleta" es una condición barata —¿hay algún capítulo sin
`still_path`?— y es lo que evita tener que elegir entre "refrescar de más para siempre" o "dejar sin
imagen lo que recién sale".

Además se usa el mecanismo **stale** que el caché ya tiene (`get_json` devuelve `(valor, stale)`):
vencido el tramo fresco, se sirve lo viejo igual mientras se revalida. Así, una caída de TMDB nunca
le saca las imágenes a una serie que ya las tenía.

### 2. La app guarda lo que llega

- `GatewayEpisode` suma `still`, `tmdbTitle` y `overview`; el cliente los parsea con el mismo criterio
  tolerante que ya usa (campo ausente = null, nunca excepción).
- `episode_still` ya guarda `stillUrl` y `title` por capítulo; se le suma **`overview`**. Es una tabla
  **local**: no está entre las cuatro que sincroniza `SyncTriggers`, así que esta columna no toca el
  sync ni sus triggers. Migración de Room con la columna nueva y nullable.
- `addMagisSeason` escribe, además de ítem y episodios, una fila de `episode_still` por capítulo que
  haya venido enriquecido (`stillUrl`, `title` = `tmdb_title`, `overview`). Un capítulo sin match
  **no** escribe fila, para que la UI caiga al `displayName` del portal.
- El `tmdb_id` de `series` se guarda en `ItemEntity.tmdbId`. No es solo por prolijidad: es lo que deja
  que el mecanismo que ya existe (`ensureEpisodeStills`) rellene por su cuenta lo que el gateway no
  haya podido, y lo que evita que `ensureArtwork` siga adivinando por título.

### 3. Dónde se ve

| Superficie | Qué cambia |
|---|---|
| Detalle del TV | Ya lee `observeEpisodeStills`/`observeEpisodeTitles`: las tarjetas dejan de ser negras y el hero muestra el nombre real. **Nuevo**: la sinopsis del capítulo enfocado. |
| Detalle del celu | Ya lee las mismas: still y nombre real en cada fila. **Nuevo**: la sinopsis. |
| Overlay de pausa del player | Ya lee las mismas: se prende solo. |
| "Continuar viendo" (Home TV y celu) | **Nuevo**: `observeContinueWatching` pasa a cruzar con `episode_still` para usar su still y su título en vez del `displayName` y el `thumbPath` crudos. |
| Lista de capítulos del buscador (TV y celu) | **Nuevo**: pinta `tmdb_title` y `still`, que vienen en la misma respuesta que ya se pide para listar. Sin llamada extra. |

La sinopsis en el TV va en el hueco que el propio código documenta: hoy, con un capítulo enfocado,
`TvDetailScreen` **oculta** la sinopsis de la serie con el comentario *"la de la serie no describe ESE
capítulo, y TMDB no nos da la del episodio acá"*. Ahora sí la tenemos.

### 4. La numeración: dónde esto se rompe

Es el riesgo real y hay que ser honestos con él. Se cruza por `episode_number`, que es lo único que
los dos lados comparten. Cuando el portal numera distinto que TMDB, el cruce miente — y **un still
equivocado es peor que ninguno**, porque el usuario no tiene forma de saber que está mal.

Casos vistos: `One Piece Temp.1` en el portal trae 8 capítulos y la temporada 1 de TMDB tiene 61 (el
portal partió la serie de otra manera). Ahí el cruce por número da imágenes de los primeros 8, que
pueden no corresponder.

Reglas:

1. Se cruza por `episode_number`, sin corrimientos ni heurísticas.
2. **Guard, por el total que DECLARA el portal.** Cada temporada trae su total en `volumnCount`
   (Daima declara 20, Breaking Bad T5 declara 16; los dos calzan con TMDB). Se enriquece solo si ese
   total coincide con la cantidad de capítulos de la temporada en TMDB.

   Comparar el total declarado y no la cantidad de capítulos publicados es lo que hace que la regla
   sirva en los dos casos a la vez:

   - **One Piece "Temp.1"**: declara 8 donde TMDB tiene 61 → no coinciden → no se enriquece. El
     portal partió la serie de otra manera y cruzar por número pondría imágenes que no corresponden.
   - **Una temporada en emisión**: declara 20 y tiene 8 publicados → coincide con TMDB → se
     enriquecen esos 8. Comparar cantidades reales acá dejaría sin imágenes justo a lo que se está
     estrenando, que es lo que más se mira.

   Si el portal no declara total (`volumnCount` ausente o 0), se cae a comparar la cantidad real de
   capítulos contra la de TMDB.
3. Un capítulo sin match en TMDB queda sin enriquecer; los demás sí. No se rellena con el vecino.
4. **Idioma**: `es-MX`. Cuando TMDB devuelve el `overview` vacío en español (pasa seguido), se cae al
   inglés para ese campo. El nombre se toma como venga.
5. El **número manda** en la UI: el chip sigue diciendo `E5` y el nombre de TMDB va al lado, nunca en
   su lugar. Si el nombre estuviera corrido, el número sigue siendo cierto.

## Qué NO cambia

- Las otras fuentes (torrent, web, archive): ya resuelven su `tmdbId` y no se tocan.
- El sync: `episode_still` es local y no viaja.
- El camino de reproducción: el `ref` de cada capítulo sale igual que hoy.
- Las películas de Magis: su `keyWords` también trae IMDb y se podría enriquecer, pero esto es sobre
  capítulos. Fuera de alcance.

## Tests

**Gateway** (pytest, con el cliente TMDB mockeado — sin red):

- `keyWords` inválido o vacío (`""`, `"12345"`, `None`) → capítulos sin enriquecer, sin excepción.
- TMDB `/find` sin `tv_results` → sin enriquecer.
- `sameSeasonSeriesList` sin nuestro `contentId` → sin enriquecer (no se asume temporada 1).
- Cruce correcto por `episode_number`, incluyendo una temporada con un capítulo que TMDB no tiene.
- **Guard de numeración**: total declarado por el portal distinto al de TMDB → no se enriquece
  ninguno; total declarado igual pero con menos capítulos publicados (temporada en emisión) → sí se
  enriquecen los publicados; sin `volumnCount` → se comparan las cantidades reales.
- `overview` vacío en español → cae al inglés.
- TMDB lanza excepción o timeout → la lista sale completa igual (best-effort).
- **TTL por completitud**: una temporada con todos sus stills se cachea con el TTL largo; una a la
  que le falta al menos uno, con el corto.

**App** (unitarios, sin Room ni red, como el resto del proyecto):

- El parseo de `GatewayEpisode` con y sin los campos nuevos (compatibilidad hacia atrás: una
  respuesta vieja sin `still`/`tmdb_title`/`overview` sigue funcionando).
- `MagisEntities`: un capítulo enriquecido produce su fila de `episode_still`; uno sin match no
  produce ninguna.
- La regla de qué texto se muestra: `tmdb_title` si existe, si no el `displayName` del portal.

## Verificación en device

Ningún test cubre esto: depende del portal y de TMDB reales.

1. Buscar Dragon Ball Daima, abrir la temporada: la lista debe mostrar `E1 La conspiración`,
   `E5 Panzy`… con su miniatura, en vez de `Dragon Ball Daima T1_5`.
2. Tocar el E5: se guarda la temporada y reproduce el 5 (no debe romperse nada de lo que ya andaba).
3. Detalle en el TV: tarjetas con imagen, nombre real, y al enfocar una, su sinopsis.
4. Detalle en el celu: lo mismo en la lista.
5. Pausar el reproductor: el overlay con imagen y nombre real.
6. Home: la tarjeta de "Continuar viendo" con el still y el nombre del capítulo.
7. Una serie que TMDB no conozca (o cortarle la red al gateway hacia TMDB): todo tiene que seguir
   funcionando con los nombres del portal, sin pantallas rotas ni cuelgues.
