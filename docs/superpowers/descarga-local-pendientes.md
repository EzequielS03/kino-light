# Descarga local al dispositivo — pendientes y huecos conocidos

Estado al 2026-08-07. La funcionalidad está implementada y en `main` (commits `e520d10d..05a11ff9`),
con 597 tests verdes. **Nada se verificó en un dispositivo físico.**

Spec: [2026-08-07-descarga-local-dispositivo-design.md](specs/2026-08-07-descarga-local-dispositivo-design.md)
Plan: [2026-08-07-descarga-local-dispositivo.md](plans/2026-08-07-descarga-local-dispositivo.md)

## Verificación en device, por orden de riesgo

1. **Instalar encima de un APK viejo con descargas de archive.org completas.** Es el único punto
   donde el usuario puede perder algo que ya tenía. Confirmar que la migración Room 16→17 corre
   limpia sobre una base real con filas viejas y que esas descargas siguen reproduciéndose del disco.
   Después, con el WiFi apagado, confirmar que una descarga **nueva** de archive también reproduce
   local — es la prueba honesta del arreglo que cerró el review final.
2. **Torrent de punta a punta con interrupciones:** encolar, matar la app a mitad, reabrir. Ver si
   retoma o si cae en "el torrent podría estar en uso ahora mismo" de forma permanente. Chequear
   `adb shell dumpsys power | grep -i wake` antes y después, por el conteo de wake locks.
3. **Web (serie) con blog vivo:** staging → transferencia → `DELETE /library`. Mirar que la barra
   muestre "Preparando en el servidor" durante el staging, y que el archivo resultante sea realmente
   lo que dice su extensión. Confirmar que una película web suelta falla con el mensaje esperado.
4. **La compuerta de 5 GB:** un torrent de un solo archivo de más de 5 GB (debe avisar inline y de
   nuevo en el worker) y un pack de temporada de más de 30 GB con capítulos de ~1,2 GB (**no** debe
   avisar). Confirmar y ver que no vuelve a preguntar.
5. **Cast de lo guardado** (Chromecast y DLNA) para las tres fuentes: `LocalFileServer` no tiene
   ningún test y es donde un error falla en silencio.
6. **Cola de varios ítems con la app en background y la pantalla apagada.** Ver si el segundo y el
   tercero arrancan con notificación de foreground.
7. **Quitar una descarga en curso** (torrent y archive) y revisar
   `Android/data/com.arkiv.player/files/Movies/` con `adb shell ls -la`.
8. **Fire TV Stick:** foco con D-pad sobre la fila de botones de Descargas, y el tilde de "guardado"
   sobre carátulas reales.

## Huecos funcionales conocidos

- **Las películas web sueltas no se pueden guardar.** Fallan con un mensaje claro y la fila queda
  reintentable. El modelo de job de `arkiv-offline` es `(seriesId, season, episode, pageUrl)` y no
  tiene convención para películas; cerrarlo requiere tocar el backend. Las películas por torrent y
  por archive.org sí se guardan.
- **Ítem huérfano en la NUC si se usa "Quitar" con una transferencia web a medias.** `remove()` borra
  la fila sin avisarle a la NUC, y el barrido de arranque solo escanea filas existentes.
- **`enqueue()` sobre una fila fallida borra `stagingItemId` y `sizeConfirmed`.** Volver a tocar
  "Guardar" en vez de "Reintentar" pierde el puntero al ítem de la NUC y obliga a reconfirmar el
  aviso de 5 GB.

## Deuda menor

- `needs_confirmation` se ve como spinner permanente en `DetailScreen`, que no ofrece cómo
  confirmarlo (sí lo ofrece la pantalla de Descargas).
- `restart()` puede solapar dos pasadas del worker un instante, rompiendo el invariante "una a la vez".
- `runAttemptCount` es por work request, no por fila: una fila puede heredar el contador de otra.
- El tilde de "guardado" confía en el estado sin verificar que el archivo exista.
- `AnimeShowDetailScreen.saveTorrentLocally` no muestra señal mientras resuelve un magnet, que puede
  tardar decenas de segundos, y deja las filas habilitadas.
- `LocalFileServer` sirve mal los rangos-sufijo (`bytes=-N`) y nunca se apaga.
- Las notificaciones no llevan a ninguna pantalla (sin `PendingIntent`).
- Quedan `SharedPreferences` huérfanas (`arkiv_downloads`) de instalaciones previas. Nadie las lee.

## Tests que faltan y son fáciles

El repo tiene la convención de extraer la lógica pura y testearla (`DownloadQueuePolicy`,
`FreeSpacePolicy`, `StagingProgress`, `TorrentSizeGate`). Quedaron sin cubrir tres piezas que sí
califican:

- `LocalFilePaths.originOf`, de lo que depende el barrido por prefijo de `remove()`.
- El conteo de wake locks de `TorrentEngine` — aritmética pura que saldría a un `LockCounter` chico.
- El predicado "¿esta fila está en vuelo?" de `cancel`/`remove`, que debería vivir junto a
  `DownloadQueuePolicy.isTerminal`.

`LocalFileServer` es `java.io`/`java.net` puro y tampoco tiene test, siendo que MockWebServer y
`TemporaryFolder` ya están en las dependencias.
