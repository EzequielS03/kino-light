# Arkiv — Reproductor Android para archive.org (Diseño)

**Fecha**: 2026-07-20 · **Estado**: Aprobado por Cristian

## Resumen

App Android nativa (Kotlin) de uso personal para reproducir video de archive.org
con estética estilo Netflix/Prime Video. Se agregan ítems pegando la URL o el
identificador; la app arma una biblioteca local con progreso de reproducción,
descargas offline, soporte Android TV y Chromecast. Sin backend, sin cuentas:
la única red es archive.org.

## Alcance

- **Incluido**: pegar URL → biblioteca, streaming, descargas, continuar viendo,
  marcado de vistos, selector de audio/subtítulos, Chromecast, UI de celular y
  UI de Android TV en el mismo APK, tema oscuro estilo Netflix.
- **Excluido**: búsqueda dentro de archive.org, cuentas/login, publicación en
  Play Store, iOS, sincronización entre dispositivos.

## Stack

- Kotlin 2.x, Jetpack Compose (Material 3) para celular, Compose for TV
  (`androidx.tv:tv-material`) para Android TV. Un solo módulo `app`.
- Media3/ExoPlayer para reproducción (mkv con multi-audio y subtítulos
  embebidos, seek por HTTP range). Media3 DownloadManager para descargas.
- Cast SDK (`play-services-cast-framework` + `media3-cast`) para Chromecast.
- Room (SQLite) para persistencia. DI manual (AppGraph singleton; sin Hilt,
  para builds más simples y rápidos). MVVM.
- minSdk 26, targetSdk actual. APK firmado con keystore local (sideload).

## Fuente de datos: archive.org

- **Metadata**: `GET https://archive.org/metadata/{identifier}` → JSON con
  `metadata` (título, descripción) y `files[]` (name, format, size, original).
- **Streaming/descarga**: `https://archive.org/download/{identifier}/{path}`
  con cada segmento del path URL-encodeado (los nombres traen `@`, espacios,
  etc. — validado con el ítem real de Evangelion).
- **Carátula**: `https://archive.org/services/img/{identifier}`.
- **Parser**: filtra archivos de video (mkv/mp4/avi/webm/m4v/ogv), empareja el
  derivado mp4 con su original mkv vía el campo `original` (fallback: mismo
  basename), agrupa por carpeta (carpeta = sección/temporada) y ordena por
  nombre natural (respetando números: E01, E02… E10).

## Modelo de datos (Room)

- `items`: identifier (PK), title, description, thumbnailUrl, addedAt.
- `episodes`: id (PK), itemId (FK), section (carpeta), displayName, orderIndex,
  originalPath/originalSize/originalFormat, derivativePath/derivativeSize
  (nullable — puede existir solo una variante).
- `playback`: episodeId (PK), positionMs, durationMs, watched, lastPlayedAt.
- `downloads`: episodeId (PK), variant (original|derivative), state, progress,
  localUri, bytes.

## Pantallas — celular

1. **Inicio**: hero banner con lo último visto (▶ Continuar), fila "Continuar
   viendo" con barras de progreso, grilla/carruseles de ítems guardados.
   Botón + para agregar.
2. **Agregar**: campo para URL/identificador → preview (título, carátula,
   cantidad de videos) → confirmar. Errores claros (sin red, ítem inexistente).
3. **Detalle**: backdrop, descripción, botón Reproducir (retoma), secciones con
   episodios: miniatura, progreso, check visto, botón descargar.
4. **Player**: fullscreen, tap muestra/oculta controles, doble-tap ±10 s,
   selector de pistas de audio y subtítulos, velocidad, botón Cast, botón y
   autoplay de "Siguiente episodio".
5. **Descargas**: lista con progreso, pausar/reanudar/cancelar, espacio usado.
6. **Ajustes**: calidad preferida (original mkv / mp4 liviano) para streaming y
   para descargas; borrar datos.

## Android TV

Mismo APK; en TV (detección por `uiModeManager`/feature leanback) se muestra la
UI de Compose for TV: filas con foco y zoom sutil, detalle, player con D-pad
(izq/der seek, OK pausa). Sin Cast ni descargas en TV. Intent-filter
`LEANBACK_LAUNCHER` + banner.

## Reglas de reproducción

- Streaming local: variante según ajuste (default: original mkv).
- Cast: **siempre** el derivado mp4 h.264 (compatibilidad garantizada); si no
  existe, intenta el original.
- Progreso: se persiste cada 5 s y al pausar/salir; visto al superar 90 %.
- Autoplay del siguiente episodio de la misma sección.
- Si el episodio está descargado, se reproduce el archivo local.

## Errores y casos borde

- Offline: biblioteca navegable, descargas reproducibles, streaming da mensaje.
- Metadata refresh: si un archivo ya no existe en el ítem, se marca no
  disponible sin romper el progreso guardado.
- Redirecciones de archive.org a nodos (`dnXXXXXX.ca.archive.org`): ExoPlayer
  las sigue; reintentos con backoff en la capa de red.

## Testing

- Unit tests: parser de metadata (pareo mkv/mp4, agrupado por carpeta, orden
  natural, encoding de URLs) y DAOs de Room (in-memory).
- Player, Cast y TV: verificación manual en dispositivos reales (instalación
  por ADB WiFi al celular de Cristian, misma red).
