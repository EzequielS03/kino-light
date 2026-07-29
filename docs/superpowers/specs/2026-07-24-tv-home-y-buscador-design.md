# Home y Buscador en el TV — Diseño

**Fecha:** 2026-07-24
**Estado:** Aprobado en brainstorming (pendiente revisión del spec escrito)

## Problema

El celular ya tiene un Home de descubrimiento (filas horizontales por categoría) y un buscador por fases
que llega hasta los torrents. El **TV (Fire Stick)** se quedó atrás: su Home solo muestra la biblioteca
y no hay forma de buscar contenido nuevo desde el televisor. Queremos paridad, pero **con ergonomía de
control remoto**, no copiando la UI del teléfono.

## Contexto ya verificado

- El TV usa **Compose for TV** (`androidx.tv:tv-material:1.0.0`): sus `Card` ya traen foco, escala al
  enfocar (`CardDefaults.scale`) y `onClick`/`onLongClick` — la navegación por D-pad está resuelta.
- **`TvHomeScreen` ya construye `HomeViewModel(graph.repository, graph.tmdbApi, graph.aniListApi)`**,
  así que `rows`, `rowItems`, `rowsLoaded` y `loadRow()` (con su carga perezosa y caché) ya están
  disponibles en el TV sin trabajo extra.
- Piezas de TV existentes: `TvWideCard` (con progreso), `TvLandscapeCard` (con badge), `TvComponents`,
  `ArkivTvRoot` con rutas `home`, `detail`, `settings`, `pairing`, `torrent`, `player`.

## Decisiones tomadas (brainstorming)

1. **Columna derecha del buscador = títulos** (flujo Amazon): cards con póster que se filtran al
   escribir; las fuentes aparecen después de elegir un título.
2. **Series/anime en TV = selector visual** (fila de temporadas + lista de capítulos con el D-pad),
   nada de escribir números. Más un acceso directo a "Toda la serie" (packs).
3. **Packs en TV = lista de capítulos** navegable; eliges uno y se reproduce. Botón aparte para
   "Guardar toda la serie". Sin checkboxes (eso se queda en el celu).
4. **Teclado en pantalla alfabético en grilla**, estilo Amazon/Netflix.
5. **El Home del TV no se rediseña**: se mantiene tal cual y solo se le agregan las filas del celu.

## 1. Home del TV

Se conserva lo actual (Continuar viendo con `TvWideCard`, biblioteca con `TvLandscapeCard`, foco y
diálogo de categoría). **Debajo** se agregan las mismas filas de descubrimiento del celular:

En cartelera · Películas populares · Tendencias de la semana · Series populares · Series mejor
valoradas · Anime del momento · Anime populares · Anime mejor valorados · una fila por cada género
(películas, series y anime).

- Se reutiliza el `HomeViewModel` que la pantalla ya usa: mismas specs (`buildRowSpecs`), misma
  **carga perezosa** (`loadRow` al entrar la fila en pantalla) y mismo caché en memoria.
- Fila que falla o llega vacía → **se oculta** (igual que en el celu).
- **Nuevo componente `TvPosterCard`**: los pósters son 2:3 y los cards actuales son apaisados. Sigue
  el mismo patrón que `TvLandscapeCard` (mismo `Card` de tv-material, misma escala al enfocar,
  `AsyncImage`), solo cambia la proporción.
- **Click en una card de descubrimiento** → abre el buscador del TV **ya posicionado en ese título**,
  reutilizando el atajo `kind + id` que ya existe (`searchShortcutRoute`).
- **Entrada al buscador:** botón **Buscar** en la barra superior del Home del TV, junto a *Torrent* y
  *Ajustes*.

## 2. Buscador del TV — dos columnas

```
┌──────────────┬───────────────────────────────┐
│  A B C D E F │  [póster] [póster] [póster]   │
│  G H I J K L │   Título     Título    Título │
│  M N O P Q R │                               │
│  S T U V W X │  resultados que se filtran    │
│  Y Z 0 1 2 3 │  mientras escribes            │
│  ␣  ⌫        │                               │
└──────────────┴───────────────────────────────┘
```

**Columna izquierda — teclado alfabético en grilla:**
- Letras A-Z y dígitos 0-9 en grilla, más **espacio** y **borrar**.
- Cada pulsación agrega/quita del texto y **dispara la búsqueda con un pequeño retardo** (debounce
  ~300 ms) — sin botón "buscar", como Amazon.
- El texto escrito se muestra arriba de la grilla.

**Columna derecha — títulos:**
- Cards con póster (`TvPosterCard`) de TMDB + AniList, alimentadas por **`SearchViewModel.search()`
  tal cual** (misma búsqueda unificada del celu).
- Mientras carga, indicador discreto; sin resultados → mensaje simple.

**Navegación (D-pad):**
- Foco inicial en el teclado.
- **Derecha** desde el borde del teclado → salta a los resultados. **Izquierda** en el primer
  resultado → vuelve al teclado.
- **Atrás** retrocede un paso del flujo (fuentes → capítulos → títulos → sale del buscador).

## 3. Al elegir un título

- **Película** → directo a la lista de **fuentes**.
- **Serie / anime** → **selector visual**:
  - Fila horizontal de temporadas (`T1`, `T2`, …) — para anime, la lista de episodios.
  - Debajo, lista de capítulos de la temporada enfocada (título del episodio incluido).
  - Botón **"Toda la serie"** → busca por nombre (que es donde salen los packs).
  - Los datos salen de `tmdbApi.seasonEpisodes(...)` / la capa de anime que ya existe.

## 4. Fuentes y reproducción

- Lista navegable de fuentes con **packs de primeros** (misma ordenación del celu, `packsFirst`).
- Cada fila muestra lo mismo que en el celu (nombre, idioma, calidad, seeds, tamaño, badge PACK).
- **Al elegir una fuente se reproduce de inmediato en el TV** — no hay diálogo de "dónde ver" (no
  aplica: ya estás en el televisor). El ítem se guarda en la biblioteca como hoy antes de reproducir.

**Si la fuente es un pack:**
- Se abre la **lista de capítulos del pack** (resuelta con `PackResolver`), navegable con el control.
- Elegir un capítulo → **reproduce ese** al instante.
- Botón **"Guardar toda la serie"** → `savePackAsSeries(...)` con título/póster/sinopsis de la card.

## 5. Reutilización (requisito, no sugerencia)

Sin reescribir nada de la lógica:
- `SearchViewModel` completo: `search()`, `pickTitle()`, `runSourceSearch()`, `sources`, flags de carga,
  matriz de búsqueda (movie / serie+S/E / serie por nombre / anime+ep / anime por nombre).
- `HomeViewModel` + `buildRowSpecs` / `LoadGuard` / `searchShortcutRoute`.
- `PackDetector`, `PackResolver`, `PackRowBuilder`, `repository.savePackAsSeries`.
- `TorrentEngine`, `EpisodeFilePicker`, `ArkivRepository` (add*, `firstEpisodeId`).
- Ruta `player/{episodeId}` de `ArkivTvRoot`.

**Lo nuevo es solo UI de TV**: `TvSearchScreen` (dos columnas + teclado), el selector de
temporada/capítulo, la lista de fuentes, la lista de capítulos de pack, y `TvPosterCard`.

## 6. Errores y estados

- Fuente que no abre (sin seeds, sin video) → mensaje en pantalla, el foco se queda en la lista.
- Fila del Home que falla → se oculta.
- Búsqueda sin resultados → texto simple, sin bloquear el teclado.
- Todo envuelto en `runCatching`: una fuente caída no tumba la pantalla.

## 7. Testing

- **Unit (lógica pura):** distribución de teclas de la grilla (filas/columnas, orden alfabético +
  dígitos + espacio/borrar), y la reducción de texto del teclado (agregar letra, borrar, borrar en
  vacío). El resto de la lógica ya está testeada en el celu.
- **En device:** verificación con el Fire Stick por ADB (capturas + navegación con `input keyevent`
  DPAD_*), que es como se prueba la UI de TV.

## Fuera de alcance

- Búsqueda por voz.
- Rediseñar el Home actual del TV (solo se le agregan filas).
- Selección múltiple de capítulos de un pack en TV (queda en el celu).
- Teclado QWERTY (se eligió grilla alfabética).
