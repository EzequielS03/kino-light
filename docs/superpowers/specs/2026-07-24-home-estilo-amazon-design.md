# Home estilo Amazon — Diseño

**Fecha:** 2026-07-24
**Estado:** Aprobado en brainstorming (pendiente revisión del spec escrito)

## Problema

El Home hoy es solo la biblioteca: "Continuar viendo" + una grilla de lo guardado. No hay
descubrimiento: para encontrar algo nuevo hay que ir al Catálogo, escribir y navegar. Queremos un
Home tipo Prime Video — **filas horizontales por categoría** — donde tocar cualquier póster sea un
**atajo directo a las fuentes** (torrents/web/archive) de ese título, aprovechando el buscador por
fases que ya construimos.

## Decisiones tomadas (brainstorming)

1. **Descubrimiento primero**, con la biblioteca a un scroll de distancia (no enterrada al final).
2. **Hero = lo que estabas viendo**: el bloque grande de arriba es tu "Continuar viendo" más reciente
   con botón **Reanudar**. Si no hay nada empezado, muestra un destacado de Tendencias.
3. **Filas elegidas:** Estrenos (en cartelera) + Próximamente, Tendencias de la semana, Series
   populares + Series mejor valoradas, Anime del momento.
4. **Todos los géneros**, pero con **carga perezosa**: cada fila pide sus datos cuando entra en
   pantalla, no todas al abrir.
5. **Mi biblioteca**: fila horizontal con **Ver todo**, encima del descubrimiento.
6. **Click en card = atajo al buscador** ya posicionado en ese título.
7. **Ocultar la pestaña Catálogo** (sin borrar código).

## Estructura del Home (orden final)

| # | Sección | Fuente |
|---|---|---|
| 1 | **Hero** — backdrop + título + `T1 · E3` + **Reanudar** | Continuar viendo más reciente (fallback: Tendencias) |
| 2 | **Continuar viendo** | local (biblioteca + progreso) |
| 3 | **Mi biblioteca** + *Ver todo* | local |
| 4 | **En cartelera** | TMDB `NOW_PLAYING` (movie) |
| 5 | **Próximamente** | TMDB `UPCOMING` (movie) |
| 6 | **Tendencias de la semana** | TMDB `TRENDING` |
| 7 | **Series populares** | TMDB `POPULAR` (tv) |
| 8 | **Series mejor valoradas** | TMDB `TOP_RATED` (tv) |
| 9 | **Anime del momento** | AniList `TRENDING_DESC` |
| 10+ | **Una fila por género** — películas y luego series | TMDB `genres()` + `discover(type, genreId)` |

## Comportamiento del click (el corazón de la feature)

Tocar una card de descubrimiento **salta el paso de escribir** del buscador:

- **Película** → directo a **RESULTS** (torrents + web + archive, packs primero).
- **Serie / anime** → paso **REFINE** (temporada/capítulo opcional, episodio para anime) → RESULTS.
- **Atrás** desde el buscador vuelve al **Home**, no a un buscador vacío.

**Transporte del dato:** la ruta lleva solo `kind` + id, p.ej. `search?kind=series&tmdbId=1399` o
`search?kind=anime&anilistId=20`. El buscador resuelve título/póster/sinopsis por su cuenta (ya llama
`tmdbApi.detail` / `aniListApi.details` en `runSourceSearch`). Evita URLs gigantes con pósters.

El Hero y "Continuar viendo"/"Mi biblioteca" **no** usan este atajo: siguen abriendo el reproductor o
el detalle del ítem guardado, como hoy.

## Carga y rendimiento

- `LazyColumn`: solo compone las filas visibles. Cada fila dispara `loadRow(id)` **la primera vez que
  se compone**; el resultado queda cacheado en memoria del ViewModel, así que volver a subir no
  vuelve a pedir red.
- La lista de géneros se pide **una vez** (`tmdbApi.genres("movie")` y `("tv")`) para construir las
  specs de fila.
- Sin internet: hero, Continuar viendo y Mi biblioteca funcionan (son locales); las filas remotas se
  ocultan.

## Arquitectura

**`HomeViewModel`**
- `rows: StateFlow<List<HomeRowSpec>>` — las specs se construyen al arrancar (las fijas + una por
  género tras resolver `genres()`).
- `HomeRowSpec(id: String, title: String, source: RowSource)` con
  `RowSource = Curated(type, category) | Discover(type, genreId) | Anime | Library | ContinueWatching`.
- `rowItems: StateFlow<Map<String, List<TitleCard>>>` + `loadRow(id)` **idempotente** (si ya cargó o
  está cargando, no repite).
- Reutiliza el `TitleCard` del buscador (`ui/search/CardContext.kt`) para que el click mapee directo.

**`HomeScreen`**
- `LazyColumn` con: hero → filas. Cada fila es un composable con su `LaunchedEffect(rowId)` que llama
  `vm.loadRow(rowId)`.
- Card = póster 2:3 con título debajo (estilo del catálogo actual, reutilizando `AsyncImage`).
- Fila que falla o queda vacía → **se oculta** (sin huecos ni errores por todos lados).

**Navegación**
- Ruta `search` gana argumentos opcionales: `?kind=&tmdbId=&anilistId=`.
- `SearchScreen`/`SearchViewModel`: si llegan esos args, construyen el `TitleCard` y llaman
  `pickTitle(card)` al arrancar — saltándose la fase QUERY.

**Pestaña Catálogo**
- Se **oculta del menú inferior** quitando su entrada de la lista `TABS` (`ArkivRoot.kt:97`).
- **No se borra nada**: la ruta `composable("catalog")`, `CineCatalogScreen`, su ViewModel y
  `catalogResetSignal` quedan intactos. Volver a mostrarla = devolver esa línea.

## Errores y estados

- Fila remota que falla → oculta (log silencioso, sin Toast).
- Hero sin "continuar viendo" → destacado de Tendencias; si tampoco hay, se oculta y el Home arranca
  en la primera fila.
- Cada llamada de red envuelta en `runCatching`, una fila nunca tumba a las demás.

## Testing

- **Unit (lógica pura):**
  - Construcción de specs: las fijas en el orden definido + una por género, ids únicos.
  - Mapeo card → ruta del buscador (`movie` → `search?kind=movie&tmdbId=…`, etc.).
  - `loadRow` idempotente: dos llamadas seguidas para el mismo id no disparan dos cargas.
- **Visual:** capturas en el device (hero, filas, click a una película y a una serie).

## Fuera de alcance

- Rediseñar el Catálogo (solo se oculta la pestaña).
- Personalización/recomendaciones propias ("porque viste X").
- Guardar el estado de scroll horizontal entre sesiones.
