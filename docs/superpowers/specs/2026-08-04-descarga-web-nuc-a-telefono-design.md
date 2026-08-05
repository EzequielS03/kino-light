# Descargar al teléfono un episodio web que ya está en la NUC

Fecha: 2026-08-04 · Estado: diseño

## Contexto / bug de origen

El botón "Descargar" (ícono `Download`, junto a cada fila de episodio en
`DetailScreen.kt`) no hace nada para episodios de series **web** (scrapeadas de un
sitio, no de archive.org). Confirmado en vivo con logs agregados a
`Downloader.enqueue()` e instalación por ADB:

```
onDownloadEpisode click: id=web:series:tt32766897::a46b0a31 original=false derivative=false
enqueue() quality=DERIVATIVE original=false derivative=false -> variantFor=null
enqueue() SALIDA TEMPRANA: variantFor()==null (sin original ni derivative)
```

`Downloader.enqueue()` solo sabe construir la URL a descargar a partir de
`episode.original`/`episode.derivative` (archivos dentro de un ítem de archive.org).
Los episodios web nunca traen esos campos — solo `sourceRef` (la pageUrl) — así que
`variantFor()` devuelve `null` siempre y la función retorna en silencio, sin excepción
ni feedback visible. El botón, sin embargo, se ofrece igual porque
`showDownload = !data.isTorrent` no distingue archive.org de web.

## Objetivo

Para un episodio web que **ya está descargado en la NUC** (arkiv-offline), el botón
"Descargar" debe bajar al teléfono el archivo que ya vive en la NUC — no intentar
archive.org. Para un episodio web que todavía no está en la NUC, el botón no debe
mostrarse (ese caso solo tiene sentido después de mandarlo a la NUC con el botón de
nube ya existente). Los episodios de archive.org quedan exactamente igual que hoy.

## Alcance

- Solo episodios **web** con copia ya confirmada en la NUC (`isInNuc == true`).
  Torrent sigue excluido (`!data.isTorrent`, sin cambios).
- Reusa el mismo endpoint `GET /stream/<item_id>` de arkiv-offline que ya usa VLC
  para reproducir (soporta Range, ya probado en producción) — no se toca el backend.
- No se agrega un ícono nuevo: es el mismo botón "Descargar" que ya existe en la
  fila, con visibilidad y comportamiento condicionados. El tilde ✓ verde ("ya está en
  la NUC") se mantiene sin cambios — conviven los dos íconos, igual que hoy conviven
  tilde/ícono-nube en el mismo slot.

## Arquitectura

### `ArkivOfflineApi.kt` — nuevo método `resolvedReachableBaseUrl()`

`baseUrlResolved()` ya existe y prueba LAN con un GET corto a
`/library?series_id=__probe__`; si no responde, devuelve `tunnelBaseUrl()` **sin
verificarlo** (asume que el túnel está arriba). Para esta feature hace falta saber
con certeza si hay algún camino vivo antes de encolar nada en `DownloadManager`
(decisión explícita: no encolar a ciegas, ver Manejo de errores). Se extrae el probe
a un método reusable y se agrega:

```kotlin
suspend fun resolvedReachableBaseUrl(): String? {
    val lan = lanBaseUrl()
    if (probe(lan)) return lan
    val tunnel = tunnelBaseUrl()
    return if (probe(tunnel)) tunnel else null
}
```

`baseUrlResolved()` no cambia (sigue usándose donde ya se usa, para jobs/library, que
toleran mejor un intento fallido con reintento).

### `Downloader.kt` — refactor + nuevo `enqueueFromNuc()`

Se extrae la parte común de `enqueue()` (chequeo de "ya existe" un registro no
`failed`, armar `DownloadManager.Request`, `dm.enqueue()`, guardar en `prefs` +
`downloadDao.upsert`) a un helper privado `downloadUrl(episode, url, ext, kind,
sizeBytes)`. `enqueue(episode)` (camino archive.org) pasa a resolver variant + URL y
delegar en el helper — sin cambio de comportamiento. Nuevo entry point:

```kotlin
suspend fun enqueueFromNuc(episode: Episode, url: String, sizeBytes: Long) =
    downloadUrl(episode, url, ext = "mp4", kind = "nuc", sizeBytes = sizeBytes)
```

- **Extensión fija `.mp4`**: arkiv-offline no expone la extensión real del archivo
  (`/stream` sirve los bytes con `Content-Type` adivinado por `mimetypes.guess_type`,
  sin exponerla en `/library`). Tocar ese backend para exponerla es scope extra
  descartado para esta iteración — mismo fallback que ya usa el camino archive.org
  cuando `MetadataParser.extensionOf` no encuentra nada.
- **`sizeBytes`**: se toma del tamaño real que ya devuelve `GET /library` y cachea
  `NucLibraryItemEntity.sizeBytes` — no hay que pedir nada nuevo al backend.
- Los logs de diagnóstico agregados en `enqueue()` (Fase 1 de esta investigación)
  quedan, y `enqueueFromNuc()` recibe logs equivalentes (mismo tag `ArkivDownload`).

### `DetailScreen.kt`

- `nucDownloaded` deja de ser `Set<Triple<season, episode, sourceRef>>` y pasa a
  `Map<Triple<Int, Int, String>, NucLibraryItemEntity>` — se necesita el `itemId`
  (para `streamUrl`) y el `sizeBytes` (para `enqueueFromNuc`) de cada entrada, que
  hoy se descartan al armar el set.
- `showDownload` pasa de ser un único booleano para toda la pantalla a calcularse
  por fila: `!data.isTorrent && (ep.original != null || ep.derivative != null ||
  nucEntry != null)`.
- El `onDownload` de cada fila decide en el momento del click: si hay `nucEntry`
  para ese episodio, dispara el flujo nuevo (`resolvedReachableBaseUrl` →
  `streamUrl` → `enqueueFromNuc`); si no, sigue llamando a `Downloader.enqueue()`
  como hoy.
- Sin Snackbar de éxito: igual que el camino archive.org, la confirmación es la
  notificación nativa que `DownloadManager` ya dispara
  (`setNotificationVisibility(VISIBILITY_VISIBLE)`).

## Manejo de errores

- **NUC inalcanzable (ni LAN ni túnel responden) al tocar "Descargar":**
  `resolvedReachableBaseUrl()` devuelve `null` → Snackbar de error ("La NUC no
  responde") y **no se encola nada** en `DownloadManager`. Decisión explícita del
  usuario: preferir avisar antes que encolar una descarga que va a quedar
  colgada/fallida sin explicación.
- Falla intermitente **después** de encolar (el archivo desaparece de la NUC, el
  túnel se cae a mitad de descarga, etc.): queda cubierto por el manejo de errores
  que `DownloadManager` y la pantalla de Descargas ya tienen para descargas
  fallidas — mismo tratamiento que hoy le dan a un fallo de archive.org.

## Testing

- Test unitario de `Downloader`: `enqueueFromNuc` arma el `DownloadManager.Request`
  con la URL dada, extensión `.mp4`, y respeta el mismo guard de "ya existe" que
  `enqueue()`.
- Test de `ArkivOfflineApi.resolvedReachableBaseUrl()`: LAN responde → devuelve LAN
  sin tocar el túnel; LAN no responde y túnel sí → devuelve túnel; ninguno responde
  → `null`.
- Verificación en vivo (como ya se hizo para diagnosticar el bug original): instalar
  por ADB, tocar "Descargar" en un episodio web ya bajado a la NUC, confirmar por
  logcat que se resuelve la URL y aparece la notificación nativa de descarga: y
  repetir con la NUC apagada para confirmar el Snackbar de error.

## Fuera de alcance

- Exponer la extensión real del archivo desde `arkiv-offline` (se usa `.mp4` fijo).
- Fallback automático "el archivo de la NUC no está → ofrecer archive.org en su
  lugar" — si algún día un mismo episodio tiene ambas fuentes, queda para una
  iteración futura.
- Torrent como fuente descargable al teléfono desde esta pantalla (sigue excluido,
  sin cambios respecto a hoy).
