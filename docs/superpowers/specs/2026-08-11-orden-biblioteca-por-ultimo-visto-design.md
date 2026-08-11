# Orden de la biblioteca por lo último que vi

Fecha: 2026-08-11

## El problema

Las tarjetas de "Mi biblioteca" se ordenan hoy por **cuándo agregaste el ítem**, no por cuándo lo
viste. Si venís viendo una serie que guardaste hace meses, cada vez que querés seguirla tenés que
bajar a buscarla entre todo lo que agregaste después. Lo que estás viendo debería estar primero.

Las dos pantallas tienen el mismo defecto por caminos distintos:

- **Celu** (`ui/library/LibraryScreen.kt`): dibuja las filas crudas de `ArkivRepository.observeLibrary()`,
  que salen ordenadas por `items.addedAt DESC` desde el SQL de `ItemDao.observeLibrary` (`data/db/Daos.kt`).
- **TV** (`ui/tv/library/TvLibraryScreen.kt`): dibuja los grupos de `observeLibraryGroups()`, ordenados
  por `max(addedAt)` de sus miembros en `LibraryGrouping.group` (`data/LibraryGrouping.kt`).

## La regla

```
recencia(ítem) = max(última reproducción del ítem, addedAt del ítem)
```

Ordenado de mayor a menor. Una sola lista: lo nunca visto **no** va a un bloque aparte, se mezcla por
recencia. Lo recién agregado sube igual porque su `addedAt` es "ahora".

**Última reproducción** es el `MAX(playback.lastPlayedAt)` de **cualquier** episodio del ítem, esté a
medias o terminado. Que un capítulo terminado cuente es el punto central: terminar el E4 anoche tiene
que dejar la serie primera hoy, con el E5 a un toque. Un criterio tipo "solo lo que dejé a medias"
(el del carrusel "Continuar viendo") la tiraría del tope justo al terminar el capítulo.

No hay piso de segundos: darle play tres segundos por curiosidad también sube la tarjeta. Es el mismo
criterio que ya usa `inProgressEpisode` en `ArkivRepository.kt`, y por el mismo motivo — no hay forma
de distinguir un toque por error de uno real sin romper el caso que la regla existe para resolver.

Para un **grupo** del TV (una serie guardada desde varias fuentes) la recencia es la del miembro más
reciente: verla por la copia de torrent sube la tarjeta del grupo entero, no aparece una segunda.

Casos que salen de la regla:

| Situación | Dónde queda |
|---|---|
| Terminaste el E4 anoche | Primera. **El caso que motiva esto.** |
| Agregaste una peli recién, sin verla | Primera (su `addedAt` es "ahora") |
| Serie que veías hace un año | Se hunde sola, sin tener que quitarla |
| Vista hace un año pero re-agregada hoy | Sube: manda el `max(...)`, no la reproducción sola |
| Dos ítems con la misma recencia | Se mantiene el orden entrante (`addedAt DESC`) |

## Arquitectura

### Consulta nueva

`PlaybackDao` gana la gemela de `observeVistos` sin el filtro `watched = 1`:

```sql
SELECT e.itemId AS itemId, MAX(p.lastPlayedAt) AS ultimaMs
FROM playback p
JOIN episodes e ON e.id = p.episodeId
WHERE p.deleted = 0 AND e.deleted = 0
GROUP BY e.itemId
```

Sin migración de Room: es una consulta sobre tablas que ya existen, no cambia ningún `@Entity`. El
sync no se entera.

### Objeto puro

`data/biblioteca/OrdenDeBiblioteca.kt`, al lado de `VistosDeLaBiblioteca` y `SeccionDeBiblioteca`
porque es la misma clase de cosa: lógica pura, testeable sin Room. Expone la recencia de una fila y
dos ordenamientos —filas (celu) y grupos (TV)— con **una sola** definición de la regla adentro.

### Cableado en el repositorio

`observeLibrary()` cruda queda **intacta**. La usan `ensureArtwork` (vía el `onEach` del `init` de
`HomeViewModel`), la pantalla de descargas y el héroe del home del TV: a ninguno le aporta el
reorden, y sí le costaría re-trabajo. Esto es lo que decide toda la forma del diseño — ver
"Alternativa descartada".

Encima de ella, dos flows:

- `observeLibraryOrdenada()`: `observeLibrary()` + recencias → filas ordenadas. Nuevo, lo consume el celu.
- `observeLibraryGroups()`: lo que ya arma hoy, con las recencias aplicadas por encima. Lo consume el TV.

`LibraryGrouping.kt` **no se toca**. Su `sortedByDescending { max(addedAt) }` interno pasa a ser el
desempate: como `sortedByDescending` de Kotlin es estable, dos grupos con la misma recencia conservan
el orden por fecha de agregado que ya traían.

### Alternativa descartada

Meter un `MAX(playback.lastPlayedAt)` como subconsulta dentro de `ItemDao.observeLibrary` y cambiar
su `ORDER BY` es una línea y media (el patrón ya está en `seriesConProgreso`), pero ata esa consulta
a la tabla `playback`: Room re-emitiría **toda la biblioteca cada vez que se guarda progreso**, o sea
cada pocos segundos durante la reproducción. De ese flow cuelga `ensureArtwork`, que hace una
consulta por fila en cada emisión. El costo cae sobre consumidores que no pidieron nada.

También se descartó ordenar dentro de cada pantalla: la misma regla escrita dos veces (celu y TV) se
desincroniza al primer cambio.

## Cambios por pantalla

**Celu** (`LibraryScreen.kt`). `HomeViewModel` gana un `bibliotecaOrdenada` y la pantalla lee de ahí:
la grilla, los chips de filtro y el `hasMovies`/`hasSeries`. El `library` crudo se queda en el
ViewModel para su `onEach { ensureArtwork(rows) }`. Son dos suscripciones a la misma consulta barata,
y es a propósito: es lo que evita que guardar progreso dispare una pasada de arte por fila. Los chips
Todas/Películas/Series filtran sobre la lista ya ordenada, así que el orden aguanta dentro de cada
filtro.

**TV** (`TvLibraryScreen.kt`). Cero cambios de UI: `TvLibraryViewModel.grupos` ya sale de
`observeLibraryGroups()`, así que Todo / Series / Películas heredan el orden solos. "Ya visto"
conserva el suyo, que ya era por último visto y ahora queda consistente con el resto. Descargas no se
toca.

**Sin cambios a propósito**: el héroe del home del TV (`TvHomeScreen.kt`) sigue cayendo en lo último
agregado cuando no hay nada a medias; descargas y sync ni se enteran.

## Repetición aceptada

En el celu, la primera tarjeta de la grilla va a ser casi siempre la misma que la primera del
carrusel "Continuar viendo". Se deja así: el carrusel lleva al capítulo exacto y la grilla al detalle,
no son la misma acción. Si molesta, sacarla es un cambio chico y posterior.

## Verificación

`OrdenDeBibliotecaTest`, puro, sin Room ni Robolectric (que este proyecto no tiene):

- lo visto anoche va antes de lo visto la semana pasada
- una peli agregada recién le gana a una serie vista hace una semana
- un capítulo terminado cuenta igual que uno a medias
- un ítem sin ninguna reproducción se ordena por su `addedAt`
- progreso viejo + agregado reciente → manda el agregado (el `max`)
- empate en recencia → se mantiene el orden entrante
- grupo de dos fuentes: ver por la fuente B sube el grupo entero, y sigue siendo una sola tarjeta

Fuera del alcance de los tests quedan la consulta nueva de Room y el `combine` del repositorio; eso
se verifica en el aparato: reproducir algo que esté al fondo de la biblioteca, salir, y confirmar que
quedó primero — en el celu y en el Fire Stick.
