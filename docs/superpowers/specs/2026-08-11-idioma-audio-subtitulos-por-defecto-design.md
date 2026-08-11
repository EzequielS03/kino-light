# Idioma de audio y subtítulos por defecto, configurable

Fecha: 2026-08-11

## Qué queremos

Poder decir en Ajustes **en qué idioma quiero el audio y en cuál los subtítulos**, y que el
reproductor obedezca en vez de quedarse con la primera pista que aparezca en el archivo. Si el idioma
que pedí no está en ese video, que caiga a otro **según un orden que yo definí**, no al azar.

Hoy pasan tres cosas mal:

1. **El audio no se puede configurar.** El selector por idioma ya existe y funciona
   (`AudioTrackSelector`, `AudioLanguage.kt:45`), pero su orden está quemado en el código:
   `VlcPlayer.audioLangPreference` (`VlcPlayer.kt:141`) es un `var` que **nadie asigna nunca**. Siempre
   corre con Latino > Castellano > Dual.
2. **Sin match, gana la primera pista.** Si ninguna pista cae en esos tres buckets — una peli
   inglés + japonés, por ejemplo — `select()` devuelve `null` y VLC se queda con la primera. Ese es
   literalmente el "toma el primer idioma que encuentra".
3. **Los subtítulos no se eligen por idioma en absoluto.** Arrancan forzados en OFF
   (`applyDefaultSpuOff`, `VlcPlayer.kt:667`) y no hay ninguna selección por idioma. El campo
   `SubtitleStyle.language` ("es"/"off") **solo** se usa para buscar en OpenSubtitles
   (`PlayerScreen.kt:1339`); no toca las pistas del contenedor.

Además, la TV no tiene **ninguna** sección de subtítulos en `TvSettingsScreen`.

## Decisiones tomadas

| Pregunta | Decisión |
|---|---|
| ¿Cómo se resuelve el fallback? | **Lista ordenada de preferencia.** Se recorre y gana la primera pista que exista. |
| ¿Cuándo se prenden los subs solos? | **Solo si el audio no quedó en un idioma que entiendo.** |
| ¿Cambiar el idioma en el player cambia el defecto? | **Sí**, pero solo si hubo elección real (ver §5). |
| ¿Descarga automática de OpenSubtitles como fallback? | **Fuera de alcance** por ahora. |

## 1. Modelo de preferencia

Una sola forma para audio y subtítulos: una **lista ordenada de buckets de idioma**, reusando el
clasificador que ya existe (`LangTokens.classify`, portado de Alfa), que ya entiende
`"Track 1 - [Spanish]"`, `"es-419"`, `"Español (Latinoamérica)"`, `"Castellano"`, `"Dual"`, etc.

- **Audio**: Latino, Castellano, Español genérico, Dual, Inglés, Japonés
- **Subtítulos**: los mismos **menos Dual** (no aplica a una pista de texto)

`AudioLang` pasa a llamarse **`TrackLang`** porque ahora sirve para las dos cosas. Rename mecánico:
`AudioLanguage.kt`, `VlcPlayer.kt`, `AudioLanguageTest.kt` y los sitios de uso.

### Persistencia y sync

Los campos nuevos van **dentro del JSON que ya persiste y sincroniza `SubtitlePrefs`**, así el sync
celu↔TV sale gratis (`RemoteController.sendSubtitlePrefs`) y no hay que tocar el protocolo remoto.

`SubtitleStyle` pasa a llamarse **`PlaybackPrefs`** (ya no es solo estilo) con campos planos:

```kotlin
data class PlaybackPrefs(
    val audioLangs: List<TrackLang> = listOf(LATINO, CASTELLANO, SPANISH, DUAL),
    val subtitleLangs: List<TrackLang> = listOf(LATINO, CASTELLANO, SPANISH),
    val subtitleMode: SubtitleMode = AUTO,   // AUTO | OFF
    val sizePercent: Int = 100,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x80000000,
    val edge: Int = EDGE_OUTLINE,
)
```

Compatibilidad en ambos sentidos: `fromJson` ya usa `opt*` con default, así que una build vieja
ignora los campos nuevos y una build nueva tolera un JSON viejo. La migración del campo legacy
`language` es: `"off"` → `subtitleMode = OFF`; cualquier otra cosa → `AUTO`.

`SubtitleMode` tiene **dos** valores, no tres. No se agrega un "siempre prendidos" porque no se pidió.

## 2. El selector

`AudioTrackSelector.select` se generaliza a **`TrackSelector.select(tracks, order): Int?`**:

- clasifica el nombre de cada pista con `LangTokens.classify`
- recorre `order` y devuelve la primera pista que matchee
- **el español genérico sigue de comodín** para LATINO y CASTELLANO (ya está así hoy)
- devuelve `null` si nada matchea → **se deja la pista que puso VLC** (no se rompe nada)
- devuelve `null` si hay ≤1 pista real (no hay nada que elegir)

El mismo selector sirve para audio y para subtítulos; solo cambia la lista que se le pasa.

## 3. Cuándo se prenden los subtítulos

La regla, en una línea:

> Los subs se prenden si el idioma que quedó sonando **no está en tu lista de audio**.

Con lista de audio `Latino > Castellano`: un anime en japonés → subs en español. Una peli que cayó a
castellano → subs apagados, está en tu lista y la entendés. Si mañana agregás Inglés a la lista de
audio, el audio inglés deja de prender subs solo — correcto: lo agregaste porque lo entendés.

Casos borde:

- **El español genérico es comodín también acá.** El selector ya trata `SPANISH` como comodín de
  `LATINO`/`CASTELLANO`; el chequeo de "¿está en mi lista?" tiene que usar **la misma** regla. Si no,
  una pista etiquetada solo `"Spanish"` con una lista `Latino > Castellano` daría "no está en mi
  lista" y prendería subs sobre un audio que entendés perfectamente. Se comparte una única función.
- **`subtitleMode == OFF`** → siempre `spu = -1`, sin importar el audio (es el chip "Desactivado" de hoy).
- **Audio clasificado `UNKNOWN`** (pistas sin etiqueta, `"Track 1"`) → **no** se prenden. Asumir que es
  tu idioma es lo conservador; lo contrario haría aparecer subs en pelis normales sin razón.
- **No hay pista de subtítulo en ninguno de tus idiomas** → quedan apagados y listo. El menú CC
  igual va a preseleccionar el correcto si más tarde aparece uno.

### Orden de aplicación en `VlcPlayer`

En el evento `Playing` ya hay dos post-diferidos al looper (tocar el player desde el thread de
eventos crashea). Se mantiene ese patrón:

1. `applyPreferredAudio` — ya existe, ahora con la lista del usuario.
2. `applyPreferredSpu` — **reemplaza a `applyDefaultSpuOff`**. Corre después del audio, porque su
   decisión depende de qué pista de audio quedó activa.

## 4. Las tres fuentes de subtítulos

Arkiv tiene tres orígenes y **un solo selector los cubre a todos**, porque las pistas externas que se
cargan con `addSlave` también aparecen en `mediaPlayer.spuTracks` junto a las del contenedor.

### a) Inline (embebidas en el contenedor)

Caso directo. Nombres tipo `"Track 2 - [spa]"` o `"Spanish"`, que `LangTokens.classify` ya resuelve.

### b) Inyectadas (`.srt`/`.ass` sueltos del torrent)

Se cargan solas (`PlayerScreen.kt:1376`) y necesitan dos arreglos:

1. **No deben prenderse solas.** Hoy se llaman con `addSlave(tipo, uri, select = true)`, que las activa
   al instante y pisa la regla de §3. En la carga automática (`byUser = false`) va **`select = false`**
   y decide el selector.
2. **Clasificar por el sufijo de idioma del nombre, no por texto libre.** `SubtitleFilePicker` ya
   contempla nombres tipo `movie.es.srt` / `movie.lat.srt` y sabe aislar ese sufijo (`subBase`).
   Pero el diccionario de `LangTokens` está hecho para **nombres de release**: `es` y `lat` matchean,
   **`en` no** (el regex de inglés pide `eng`, `en-us` o `[en]`). Y agregar `\ben\b` al regex general
   sería peor: le pegaría a cualquier pista que diga *"Audio **en** español"*.

   Va entonces un **`classifyFileName(path)`** aparte: extrae el sufijo de idioma igual que `subBase` y
   lo compara **exacto** contra códigos (`es`, `spa`, `lat`, `es-419`, `cast`, `en`, `eng`, `ja`,
   `jpn`). Al comparar un token aislado en vez de buscar dentro de texto libre, no hay falso positivo.
   Si no hay sufijo, cae a `classify()` sobre el nombre completo.

### c) OpenSubtitles

Sigue igual: es una acción **manual**, marca `userTouchedSpu` y por eso **gana siempre** sobre la
selección automática, incluso si el reintento de §6 todavía está corriendo. Detalle: el archivo se
guarda hoy como `sub-<fileId>.srt`, sin rastro del idioma, así que esa pista sería la única opaca del
sistema. Se pasa a **`sub-<fileId>.<lang>.srt`** — el idioma ya viene en `SubtitleTrack.language` — y
queda clasificable como las demás.

## 5. Promoción a defecto tras un cambio manual

Elegir una pista en el diálogo "Audio y subtítulos" del player mueve ese bucket **al tope** de la
lista correspondiente, persiste y sincroniza a la TV.

**Mitigación obligatoria**: solo promociona si **hubo elección real**, es decir:

- el archivo tenía **2+ buckets de idioma distintos** entre sus pistas, **y**
- el bucket elegido **no es `UNKNOWN`**

Si el archivo traía un solo idioma, elegirlo no dice nada sobre tu preferencia (no había alternativa)
y el cambio aplica **solo a esa reproducción**. Sin esta regla, una peli que solo venía en inglés te
subiría el inglés al tope para siempre y la próxima peli dual arrancaría en inglés.

## 6. El bug del subtítulo colado

Aparte de no elegir idioma, el apagado por defecto **falla a veces**: reintenta 4 veces cada 350 ms
(~1,5 s) y, cuando VLC puebla las pistas más tarde, ya se rindió y se cuela la primera pista.

`applyPreferredSpu` lo corrige: reintenta hasta ~3 s **y corta apenas la lista de pistas aparece**,
decidiendo una sola vez con datos reales en vez de insistir a ciegas contra una lista vacía.

### `addSubtitleSlave` y los `.srt` del torrent

`addSubtitleSlave` hoy marca `userTouchedSpu = true` ("el usuario los quiere"), pero los subtítulos
sueltos del torrent se cargan **solos** (`PlayerScreen.kt:1376`), sin que el usuario toque nada. Eso
apaga la lógica automática por accidente.

Se le agrega un parámetro **`byUser: Boolean = true`**: la carga automática de los `.srt` del torrent
pasa `false`, con lo cual esas pistas externas entran a la lista de candidatas y **el selector puede
elegirlas por idioma** — que es justo lo que uno quiere de un `Movie.spanish.srt`.

## 7. UI

Cada idioma es una fila con **`[✓ incluir] [▲] [▼]`**. Funciona igual con dedo y con D-pad de la TV, y
deja el orden a la vista. Dos bloques: **"Idioma del audio"** e **"Idioma de subtítulos"**, este
último con los chips **Automático / Desactivado**.

- **Celular** (`SettingsScreen.kt:239`): reemplaza los dos chips actuales ("Español (auto)" /
  "Desactivado") por los dos bloques.
- **TV** (`TvSettingsScreen.kt`): **no existe hoy ninguna sección de subtítulos** — se agrega, con el
  mismo widget navegable por control remoto.
- **Player**: el diálogo no cambia de estructura; suma la promoción de §5 y marca cuál quedó
  auto-seleccionada.

## 8. Compatibilidad con OpenSubtitles

El campo `language` que se elimina es **el mismo que hoy decide en qué idioma se busca online**
(`PlayerScreen.kt:1341` → `SubtitleApi.search(languages = …)`). Hay que mantener ese camino andando.

**Mapeo `TrackLang` → códigos de OpenSubtitles.** `SubtitleApi` sigue recibiendo un string separado
por comas; se agrega un mapper que lo arma desde `subtitleLangs`, deduplicando y **respetando el
orden** del usuario:

| `TrackLang` | Código |
|---|---|
| `LATINO`, `CASTELLANO`, `SPANISH` | `es` |
| `ENGLISH` | `en` |
| `JAPANESE` | `ja` |
| `DUAL` | *(se ignora: no aplica a texto)* |

Los tres buckets del español colapsan a `es` a propósito: es el único código español que hoy se
manda y que está verificado contra el gateway. `langLabel` reconoce además `es-419` y `es-mx`, pero
esos aparecen en las **respuestas**; mandarlos en el request sin confirmar que el gateway los acepta
arriesga un 400. Si más adelante se quiere pedir latino específicamente, hay que probarlo primero.

Efecto práctico: hoy siempre se busca `"es"`; con la lista, alguien que tenga Inglés configurado
recibe `"es,en"`. Es estrictamente mejor y **no cambia la firma de `SubtitleApi`**.

**`subtitleMode = OFF` no apaga la búsqueda.** Hoy `if (prefLang.isBlank() || prefLang == "off") "es"`
sigue buscando en español aunque los subs estén desactivados, para que el menú CC tenga opciones.
Ese comportamiento se conserva: OFF significa "no encender solos", no "no buscar". Con la lista
vacía o en OFF se busca en `es`.

**Elegir un subtítulo online sigue ganando.** `applySubtitle` es una acción manual del usuario y
llama a `addSubtitleSlave` con el default `byUser = true`, así que corta la selección automática tal
como hoy. El `byUser = false` de §4b es **solo** para la carga automática de los `.srt` del torrent.

**Orden de resultados.** Hoy se ordena por `hashMatch` (release exacto primero). Se agrega como
segundo criterio la posición del idioma en `subtitleLangs`, para que el idioma preferido flote sobre
los demás dentro del mismo nivel de match.

## 9. Tests

- **`TrackSelectorTest`** (extiende `AudioLanguageTest`): fallback recorriendo la lista, comodín del
  español genérico, sin match → `null`, ≤1 pista → `null`, orden respetado.
- **Decisión de auto-subs** (tabla): audio ∈ lista → off; audio ∉ lista → prende y elige por idioma;
  audio `UNKNOWN` → off; `subtitleMode = OFF` → off siempre.
- **Regla de promoción**: archivo con un solo idioma no promociona; con 2+ sí; `UNKNOWN` nunca.
- **`PlaybackPrefs` JSON**: round-trip, back-compat con JSON viejo (sin campos nuevos → defaults) y
  migración de `language: "off"` → `subtitleMode = OFF`.
- **Mapper de OpenSubtitles**: los tres buckets del español colapsan a un solo `es` sin repetirlo, el
  orden del usuario se respeta (`["ENGLISH","LATINO"]` → `"en,es"`), `DUAL` se ignora, y una lista
  vacía o en OFF cae a `"es"`.

## Fuera de alcance

- **Selección de pista en Chromecast**: ese camino transcodifica el audio aparte
  (`CastTranscoder`/`CastSoutChain`) y merece su propio trabajo.
- **Descarga automática de OpenSubtitles** cuando el archivo no trae el sub en tu idioma. La búsqueda
  online sigue funcionando como hoy (manual, desde el menú CC). Es la extensión natural si el caso
  anime-sin-subs-embebidos molesta en la práctica.
