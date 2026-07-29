# Sinopsis en el subtítulo del hero (TV Home)

**Fecha:** 2026-07-27
**Alcance:** solo el hero del home de TV (`TvHomeScreen`). No toca detalle, buscador ni celular.

## Problema

El hero del home de TV (`TvHomeScreen.kt:266-286`) pinta `Featured.title` y debajo
`Featured.subtitle`. El subtítulo sale de tres fuentes distintas y en dos de ellas repite el
título que ya está arriba:

| Fila | Subtítulo actual | ¿Duplica? |
|---|---|---|
| Continuar viendo (serie) | `displayName` = `"Show · S01E03 · Nombre"` (`SeriesEpisodeLabel.kt:7`) | **Sí** — el título va adentro |
| Continuar viendo (película torrent) | `displayName` = `cleanName(título)` (`ArkivRepository.kt:512`) | **Sí, idéntico** |
| Biblioteca | `libraryMeta()` → `"12 episodios"` | No |
| Descubrimiento (TMDB/AniList) | `discoveryMeta()` → `"Serie · 2024"` | No |

## Solución

El subtítulo pasa a mostrar la **sinopsis del título en 2 líneas**. Cuando el ítem no tiene
sinopsis, cae al meta que se muestra hoy (`libraryMeta` / `discoveryMeta`) en vez de quedar vacío.

```
Breaking Bad                                    ← displaySmall, blanco, bold (igual que hoy)

Un profesor de química con cáncer terminal se   ← titleMedium, ArkivTextSecondary, 2 líneas
asocia con un ex alumno para fabricar meta…       (hoy: 1 línea que repetía el título)
```

Decisión tomada en brainstorming: se prioriza la sinopsis sobre el meta. En "Continuar viendo"
esto implica **perder de vista qué capítulo se venía viendo** cuando la serie tiene sinopsis;
es un trade-off aceptado a conciencia.

## Origen de los datos

Ninguna fuente requiere llamadas de red adicionales.

| Fila del hero | Fuente de la sinopsis | Cambio necesario |
|---|---|---|
| Descubrimiento TMDB | `overview`, ya viene en la respuesta de la lista | Agregar `overview` a `TmdbItem`; parsear `o.optString("overview")` en `parseItem` (`TmdbApi.kt:266`); dejar de hardcodear `overview = null` en `TmdbItem.toTitleCard()` (`CardContext.kt:29`) |
| Descubrimiento AniList | `TitleCard.overview` (ya poblado desde `AnimeShow.description`) | Ninguno en la capa de datos; solo limpieza |
| Biblioteca | `items.description` (ya existe en la DB, `Entities.kt:11`) | Sumar `i.description` al SELECT de `observeLibrary()`; campo `description: String?` en `LibraryRow` |
| Continuar viendo | `items.description` de la serie/película | Sumar `i.description AS itemDescription` al SELECT de `observeContinueWatching()`; campo en `ContinueRow` |

### Cobertura conocida

`items.description` se puebla cuando el ítem se agrega desde el catálogo TMDB
(`CineDetailScreen` pasa `description = d.overview`). Queda `null` en los flujos de web y magnet
suelto (`ArkivRepository.kt:507`, `:530`). Esos ítems caen al fallback de meta.

**No se buscará la sinopsis en TMDB al vuelo** para rellenar los faltantes: sería una petición de
red en cada cambio de foco y un matching por título (frágil — el matching correcto es por
`tmdb_id`, ver `arkiv-busqueda-torrents-matching`).

## Helpers nuevos

Ambos son funciones puras en `ui/Format.kt`, con tests unitarios (el proyecto ya tiene
`SeriesEpisodeLabelTest.kt` como precedente).

### `plainSynopsis(raw: String?): String`

Normaliza la sinopsis antes de pintarla. Necesario porque AniList devuelve HTML (`<br>`, `<i>`,
`<b>`) y las descripciones de archive.org llegan con markup y saltos de línea crudos.

El orden de los pasos importa y es parte del contrato:

1. Quita tags HTML.
2. Decodifica entidades básicas: `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`, `&nbsp;`.
3. Colapsa saltos de línea y espacios múltiples en un solo espacio.
4. `trim()`.
5. Devuelve `""` si no queda nada (así el call site puede caer al fallback con un `ifBlank`).

Los pasos 1 y 2 van en ese orden y no al revés: si se decodificaran las entidades primero, un
`&lt;b&gt;` literal en el texto se convertiría en `<b>` y el paso 1 lo borraría, comiéndose
contenido que el autor escribió a propósito.

### `heroSubtitle(title: String, description: String?, fallback: String): String`

**Agregada durante la implementación**, al verificar contra la DB real del Fire TV. Los ítems de
archive.org suelen traer el nombre del archivo como descripción — `"Night Of The Living Dead 1990"`
tenía `description = "Night of the living dead 1990"` — así que pintar la sinopsis sin más
reproducía la duplicación que este cambio existe para eliminar, entrando por otra puerta.

Resuelve el subtítulo completo: limpia con `plainSynopsis` y devuelve `fallback` si el resultado
queda vacío **o es igual al título** (insensible a mayúsculas y espacios de borde).

Se descarta solo la igualdad, nunca el prefijo: una sinopsis que arranca con el nombre de la obra
(`"Avatar Aang, el último Maestro Aire del mundo, se entera de…"`) es legítima y se conserva.

### `heroFallback(itemTitle: String, displayName: String): String`

El respaldo de "Continuar viendo" sin duplicar el título.

- Si `displayName` empieza con exactamente `"$itemTitle · "`, quita ese prefijo:
  `"Show · S01E03 · Nombre"` → `"S01E03 · Nombre"`. Si no empieza así, deja `displayName` intacto
  (el `showTitle` con el que se formateó la etiqueta pudo haber cambiado después).
- Si lo que queda es igual al título o vacío (caso película, donde
  `displayName == cleanName(título)`), devuelve `""`.
- La comparación con el título es insensible a mayúsculas y a espacios de borde: los títulos
  guardados y los `cleanName()` de archivos difieren en capitalización más seguido de lo que
  parece, y una duplicación que se escapa por una mayúscula es justamente el bug que se arregla.

## Truncado

**No se trunca por cantidad de caracteres.** El `Text` del subtítulo usa `maxLines = 2` +
`TextOverflow.Ellipsis` y Compose corta en el punto exacto según el ancho renderizado. Se evitan
números mágicos que se ven mal en anchos distintos.

El ancho se mantiene en `fillMaxWidth(0.55f)`, igual que el título, para que las dos columnas de
texto queden alineadas.

## Cambios en la UI

En `TvHomeScreen.kt`:

- El `Text` del subtítulo pasa de `maxLines = 1` a `maxLines = 2`.
- Los **5** call sites que construyen `Featured(...)` resuelven el subtítulo como
  `heroSubtitle(título, descripción, fallbackDeMeta)`:
  - Destacado inicial (`:127-134`) → según venga de continuar viendo o de biblioteca
  - Continuar viendo (`:318`) → fallback `heroFallback(itemTitle, displayName)`
  - Series de biblioteca (`:343`) → fallback `libraryMeta(...)`
  - Películas de biblioteca (`:360`) → fallback `libraryMeta(...)`
  - Descubrimiento (`:400`) → fallback `discoveryMeta(card)`

El destacado inicial es fácil de pasar por alto y es justamente el primero que se ve al abrir el
home: hoy arma `Featured(it.itemTitle, it.displayName, …)`, con la misma duplicación que el resto.

La estructura de `Featured` no cambia: sigue siendo `(title, subtitle, imageUrl)` y cada call site
pasa el subtítulo ya resuelto. Es el punto donde ya se conocen tanto la descripción como el meta
correspondiente, así que no hace falta arrastrar ambos hasta el composable. Para no repetir la
lógica cinco veces, los casos de continuar viendo y de biblioteca se consolidan en dos helpers
locales del composable (`continueFeatured`, `libraryFeatured`).

## Tests

- `plainSynopsis`: HTML de AniList, entidades, saltos de línea, `null`, string vacío, texto ya
  limpio (idempotencia).
- `heroFallback`: serie con capítulo nombrado, serie sin nombre de capítulo, película (título
  repetido → `""`), título con espacios.

El resto es cableado de UI y de queries de Room, que se verifica ejecutando la app en el TV.

## Riesgos

- **Migración de Room:** no hace falta. `items.description` ya existe como columna; solo se agrega
  al SELECT de dos queries y a sus data classes de proyección. No cambia el esquema.
- **Descripciones de archive.org largas o con markup:** las absorbe `plainSynopsis` + el
  `maxLines = 2`.
