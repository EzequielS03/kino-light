# Historial de búsqueda en la pantalla Buscar (celular)

Fecha: 2026-08-09
Estado: aprobado, sin implementar

## Problema

La pantalla Buscar arranca vacía y ruidosa: antes de escribir nada ya muestra
"Películas y series → Sin resultados" y "Resultados directos → Sin resultados".
Son dos avisos que no informan nada — todavía no hubo búsqueda — y ocupan el
lugar donde debería estar lo útil: lo que ya buscaste antes.

Además no hay forma de repetir una búsqueda. Si ayer buscaste una serie y hoy
querés volver, hay que escribirla de nuevo y volver a elegir el título entre los
resultados.

## Qué se construye

En la fase QUERY de `SearchScreen`, mientras no se haya buscado nada en esta
entrada a la pantalla, se muestran dos bloques de historial en vez de las
secciones vacías:

1. **Búsquedas recientes** — chips con los textos buscados. Tocar uno relanza
   esa búsqueda. Cada chip tiene una × para borrarlo; al final, "Borrar todo".
2. **Seguí buscando** — grilla de pósters de los títulos que abriste. Tocar uno
   llama a `pickTitle` directo: se salta la búsqueda y cae en las fuentes.

Al buscar, el historial desaparece y la pantalla muestra exactamente lo que
muestra hoy — incluido "Sin resultados", que recién ahí significa algo.

Alcance: **solo el celular** (`ui/search/SearchScreen.kt`). `TvSearchScreen.kt`
queda igual.

## Arquitectura

### `data/SearchHistoryStore.kt` (nuevo)

Guarda el historial. Va aparte de `SettingsStore` a propósito: ese archivo es
configuración (URLs, llaves, calidad) y el historial es dato de uso, que se
ensucia y se borra. Mezclarlos engorda un archivo que ya hace bastante.

- SharedPreferences propio: `arkiv_search_history`.
- Serializa con `org.json` — el patrón del repo (`TmdbApi`, `AnimeMapping`,
  `UpdateChecker`), sin dependencias nuevas.
- Expone dos `StateFlow`:
  - `queries: StateFlow<List<String>>` — máx **10**
  - `titles: StateFlow<List<RecentTitle>>` — máx **12**
- API: `addQuery(q)`, `addTitle(card)`, `removeQuery(q)`, `removeTitle(t)`,
  `clearQueries()`, `clearTitles()`.
- Lectura tolerante: JSON corrupto o de una versión vieja del formato →
  `runCatching` → lista vacía. El historial nunca tumba la pantalla.

### `data/RecentTitle`

Modelo chico de la capa de datos: `kind`, `tmdbId`, `anilistId`, `title`,
`posterUrl`, `year`. No se persiste `TitleCard` porque es un modelo de UI
(`ui/search/CardContext.kt`) y arrastra campos que no hacen falta.

Los mappers en las dos direcciones viven en `CardContext.kt`, al lado de los
otros (`TmdbItem.toTitleCard()`, `AnimeShow.toTitleCard()`). Al reconstruir el
`TitleCard`, `overview` y `backdropUrl` van vacíos: el hero de la fase RESULTS
los pide aparte a TMDB/AniList.

### `data/SearchHistoryPolicy` (objeto puro)

Toda la lógica de lista, sin Android, testeable:

- `pushQuery(lista, texto, max)`: recorta el texto; si queda vacío devuelve la
  lista igual; dedupe **case-insensitive** (buscar "One Piece" con "one piece"
  guardado sube el existente al tope en vez de duplicar); lo más reciente
  primero; recorta a `max`.
- `pushTitle(lista, título, max)`: igual, pero el dedupe es por identidad
  (`kind` + `tmdbId` + `anilistId`), no por texto — dos series pueden llamarse
  parecido.

Mismo patrón que `UnknownLengthPolicy` y `TorrentSizeGate`: la decisión en un
objeto puro con test unitario, y la I/O en una capa flaca alrededor. Es la única
forma de probarlo acá: con `unitTests.isReturnDefaultValues = true`,
`JSONObject` devuelve defaults en tests unitarios.

### `AppGraph`

`val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }`,
junto a `settings`.

### `SearchViewModel`

Recibe el store por constructor (como `settings`) y es **el único que graba**:

- `search(q)` → `addQuery(q)`. El `q.isBlank()` ya sale temprano, así que
  limpiar el campo no ensucia el historial.
- `pickTitle(card)` → `addTitle(card.toRecent())`.

Graba en la pantalla no, en el ViewModel: así da igual quién dispare la
búsqueda y queda un solo lugar donde mirar.

Expone `queries` y `titles` del store hacia la UI.

### `QueryContent` (UI)

Estado local nuevo: `var haBuscado by remember { mutableStateOf(false) }`.

- `haBuscado == false` → se muestran los dos bloques de historial y **no** las
  secciones "Películas y series" / "Resultados directos".
- `haBuscado == true` → exactamente la UI de hoy.

Pasa a `true` con cualquier búsqueda: la del teclado y la de tocar un chip. Es
estado local (`remember`), así que salir de la pantalla y volver muestra el
historial de nuevo.

El campo de texto gana una **×** (`trailingIcon`, visible solo con texto): la
limpia, llama `onSearch("")` — que vacía resultados en el ViewModel — y pone
`haBuscado = false`. Sin esto, una vez que buscás una vez el historial no vuelve
hasta salir y volver a entrar a la pantalla.

Los pósters reusan el `TitleCardItem` que ya existe: cero componentes nuevos de
tarjeta. Los chips son `InputChip` de Material 3 en un `FlowRow`.

Si las dos listas están vacías (primera vez que se abre la app), no se muestra
ningún encabezado: la pantalla queda limpia con solo el buscador.

## Flujo

```
escribís y buscás
  → vm.search(q) → store.addQuery(q) → haBuscado = true → resultados de hoy
tocás un chip
  → vm.search(texto) → sube al tope del historial → resultados
tocás un póster
  → vm.pickTitle(card) → fase REFINE/RESULTS (se salta la búsqueda)
tocás la × del campo
  → vm.search("") + haBuscado = false → vuelve el historial
```

## Errores

| Caso | Qué pasa |
|---|---|
| JSON corrupto en prefs | `runCatching` → lista vacía, se reescribe al siguiente guardado |
| Póster vacío o roto | `TitleCardItem` ya cae a fondo liso |
| Título guardado que ya no existe en TMDB | `pickTitle` corre igual; la búsqueda de fuentes no encuentra nada, que es el camino de error que ya existe |

## Tests

`app/src/test/java/com/arkiv/player/data/SearchHistoryPolicyTest.kt`:

- lo nuevo queda primero
- repetir un texto lo sube al tope sin duplicarlo
- el dedupe de texto ignora mayúsculas y espacios sobrantes
- el dedupe de títulos usa identidad, no el nombre
- se respeta el tope (el más viejo cae)
- texto vacío o solo espacios no entra

## Decisiones tomadas

- **Las dos cosas** (chips de texto + pósters), no una sola: los chips cubren
  las búsquedas que no terminaron en un título elegido, y los pósters son el
  atajo real para volver a algo que ya abriste.
- **Solo celular.** El TV tiene navegación por foco y merece su propia pasada.
- **Tope 10/12.** Suficiente para "lo de esta semana" sin volverse una lista
  interminable de scrollear.
- **El póster va a las fuentes, no al detalle.** Es el mismo destino que tocar
  el título en una búsqueda normal: menos sorpresa.
