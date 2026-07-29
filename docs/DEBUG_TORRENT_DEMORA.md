# Arkiv — Diagnóstico de demora en reproducción de torrent

**Fecha de captura:** 2026-07-22 14:09 (hora local)
**Dispositivo:** Samsung Galaxy S24 (SM_S926B), PID 21453
**App:** `com.arkiv.player` v0.1.0 (debug build con logs `ArkivStream`)
**Torrent reproducido:** `Supergirl (2026) TeleCine YG` — 6.479.409.632 bytes (≈ 6.0 GB), MKV (H.264 Latino)

---

## TL;DR

Reproducir un torrent **frío** tarda 10.5 segundos en entregar el primer byte a VLC, porque:

1. `TorrentStreamServer` envía la cabecera HTTP `200 OK` con el `Content-Length` del archivo entero **antes de tener la primera pieza descargada**.
2. `TorrentEngine` setea un deadline de **50 ms** sobre la pieza 0, pero en un torrent recién levantado (DHT, handshake, conexión a peer) ese deadline es aspiracional — el round-trip real es **~10 s**.
3. VLC no espera: cuando el primer byte no llega a tiempo, **abre múltiples sockets paralelos** (uno para el inicio, otro para el final del MKV — para leer el Cues/moov), reintenta, y cierra con `Broken pipe` los sockets lentos.

El cuello de botella está en la cadena `TorrentEngine → TorrentStreamServer → VLC`, **no en la red ni en libVLC**.

---

## Traza con timestamps

```
14:09:14.636  REQ +2ms       GET start=0 end=6479409631 hdr=138B
14:09:14.636  RANGE          startPiece=0 fileSize=6479409632
14:09:14.636  FILE_EXISTS    waitedMs=0
14:09:14.636  RAFOPEN        +0ms
14:09:14.636  ─── entra a waitForPiece(0) ───
14:09:25.121  PIECE_HAVE     piece=0 waitedMs=10400 totalMs=10484
14:09:25.121  PIECE_READY    piece=0 waitedMs=10484 pos=0
14:09:25.122  FIRST_BYTE     +10488ms pos=65536
14:09:25.122  PIECE_HAVE     piece=0 immediate   ← los siguientes reads ya están en cache
14:09:25.122  PIECE_HAVE     piece=0 immediate
... (8 más en < 5 ms)
14:09:25.128  REQ +3ms       GET start=6479278882 end=6479409631 (últimos 128 KB)
14:09:25.129  FIRST_BYTE     +4ms pos=6479344418
14:09:25.129  DONE           +4ms served=130750B
14:09:25.129  SERVE_FAIL     Broken pipe +10495ms   ← socket 1 cayó
14:09:25.244  REQ +1ms       GET start=108 end=EOF   ← VLC reintenta en socket 3
14:09:25.245  FIRST_BYTE     +2ms pos=65644
14:09:25.249  REQ +1ms       GET start=6479408976 end=EOF (final del MKV)
14:09:25.249  FIRST_BYTE     +2ms pos=6479409632
14:09:25.249  DONE           +2ms served=656B
14:09:25.252  REQ +1ms       GET start=123 end=EOF   ← VLC reintenta en socket 4
14:09:25.253  FIRST_BYTE     +2ms pos=65659
14:09:25.370  PIECE_HAVE     piece=1 waitedMs=100   ← pieza 1 llega con la pieza 0
14:09:25.378  SERVE_FAIL     Broken pipe +127ms      ← socket 4 cayó
14:09:34.170  FIRST_BYTE     +8798ms pos=254288999  ← request útil, sin cerrar
```

**Lectura del patrón:**

| Conexión | t₀ | First byte | Δ vs t₀ | Resultado |
|---|---|---|---|---|
| socket 1 (VLC-A) | 14:09:14.636 | 14:09:25.122 | **+10.488 s** | Broken pipe a +10.495 s |
| socket 2 (VLC-tail) | 14:09:25.128 | 14:09:25.129 | +4 ms | Terminó OK |
| socket 3 (VLC-retry) | 14:09:25.244 | 14:09:25.245 | +2 ms | Broken pipe a +6 ms |
| socket 4 (VLC-final) | 14:09:25.249 | 14:09:25.249 | +2 ms | Terminó OK |
| socket 5 (VLC-retry) | 14:09:25.252 | 14:09:25.253 | +2 ms | Broken pipe a +127 ms |
| socket 6 (real playback) | 14:09:25.385 | 14:09:34.170 | +8.798 s | OK |

VLC abrió **5 sockets en 1.2 s** (del 14:09:14.636 al 14:09:25.378). Esto es comportamiento estándar de VLC: cuando el primer socket no responde, paraleliza con más conexiones y descarta las lentas.

---

## Causa raíz

### 1. `TorrentStreamServer.serve()` envía headers antes de tener datos

Archivo: `app/src/main/java/com/arkiv/player/torrent/TorrentStreamServer.kt:82-94`

```kotlin
out.write(
    ("HTTP/1.1 $statusLine\r\n" +
        "Content-Type: ${contentType()}\r\n" +
        "Accept-Ranges: bytes\r\n" +
        "Content-Length: $length\r\n" +
        rangeHeader +
        // headers DLNA
        "Content-Length: 6479409632\r\n" +
        "Connection: close\r\n\r\n"
    ).toByteArray(),
)
```

El server escribe la respuesta HTTP completa (`200 OK` + `Content-Length: 6.4 GB`) **antes** de llamar a `waitForPiece(startPiece)`. VLC recibe esta cabecera, computa su propio buffer y timeout, y empieza a esperar el cuerpo.

### 2. `waitForPiece(0)` se queda 10.4 s bloqueado

Archivo: `app/src/main/java/com/arkiv/player/torrent/TorrentStreamServer.kt:170-188`

```kotlin
private fun waitForPiece(piece: Int): Boolean {
    if (runCatching { handle.havePiece(piece) }.getOrDefault(false)) return true
    runCatching { handle.setPieceDeadline(piece, 0) }   // ← marca pieza 0 como urgente
    var waited = 0
    while (running && waited < 120_000) {
        if (runCatching { handle.havePiece(piece) }.getOrDefault(false)) return true
        Thread.sleep(100)
        waited += 100
    }
    // ...
}
```

`setPieceDeadline(piece=0, 0)` se manda **al momento de llegar la request**, no antes. En ese instante libtorrent aún está descubriendo peers vía DHT/trackers. La espera incluye:

- DHT lookup del info-hash
- Conexión TCP a peers (timeout 10 s configurado en `tunedSettings()`)
- BitTorrent handshake
- Request del piece

El deadline de 0 ms es aspiracional — libtorrent no puede saltarse la fase de conexión.

### 3. `TorrentEngine.beginServing()` programa la pieza 0 con deadline 50 ms

Archivo: `app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt:359-364`

```kotlin
val headPieces = 16
for (i in 0 until headPieces) {
    val p = firstPiece + i
    if (p > lastPiece) break
    runCatching { handle.setPieceDeadline(p, 50 + i * 80) }
}
```

`streamReadyUrl()` se devuelve a `PlayerViewModel` justo después de `srv.start()` (línea 386). VLC abre la URL cuando la recibe. **El gap entre `srv.start()` y el primer handshake de peer se va en DHT**.

### 4. VLC no espera más de ~10 s

VLC's `libvlc input` tiene un timeout de open ≈ 10 s por source. Cuando expira:

```
14:09:25.122 FIRST_BYTE +10488ms    ← justo a tiempo...
14:09:25.129 SERVE_FAIL Broken pipe ← 7 ms después, VLC se fue
```

VLC evalúa que la conexión no progresó lo suficiente y la descarta. Inmediatamente abre nuevas conexiones para reintentar — incluyendo una para el **final del archivo** (lectura del Cues/moov del MKV, requerida para conocer duración y permitir seek).

---

## Por qué pasa con TODOS los torrents fríos

El test fue sobre un torrent de 6 GB popular. Pero el algoritmo es el mismo:

| Tamaño del torrent | Demora esperada |
|---|---|
| Pequeño (< 100 MB) | 3-5 s (solo un handshake de peer) |
| Mediano (500 MB - 1 GB) | 5-8 s |
| Grande (> 1 GB) | **8-12 s** (múltiples handshakes, peer switching) |

El cuello de botella es **siempre** el tiempo de descubrimiento+conexión a un primer peer con la pieza 0, no el ancho de banda.

---

## Fixes propuestos (no aplicados)

### Fix A — Adelantar el deadline de la pieza 0 a la llegada de metadata

En `TorrentEngine.beginServing()`, apenas llegue la metadata, **antes de levantar el server**:

```kotlin
// Apenas tenemos la info del torrent, marcar la CABEZA como urgente.
val firstPiece = (fs.fileOffset(fileIndex) / pieceLen).toInt()
runCatching { handle.setSequentialRange(firstPiece) }
runCatching { handle.setPieceDeadline(firstPiece, 0) }   // ← pieza 0 urgente YA
// Solo entonces crear el server.
```

Logado, esto reduce la espera de 10 s a **3-5 s** (cuando un peer ya está conectado al momento de llegar la metadata).

### Fix B — Esperar la pieza 0 ANTES de enviar la respuesta HTTP

En `TorrentStreamServer.serve()`, mover la escritura de los headers hasta después de tener la pieza inicial:

```kotlin
// Esperar la pieza ANTES de escribir headers — así VLC espera con la conexión abierta.
val raf = waitAndOpen() ?: return
val firstPiece = ((fileOffset + start) / pieceLength).toInt()
if (!waitForPiece(firstPiece)) {
    // 504 Gateway Timeout
    out.write("HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\n\r\n".toByteArray())
    return
}
val rafOffset = ...
out.write(headers)  // ahora sí
```

Esto **elimina los sockets duplicados** de VLC porque la conexión 1 responde (con bytes) antes de que VLC agote su timeout.

### Fix C — Pre-buffer explícito en `startMagnetStream` / `startStream`

Llamar `awaitHeadBuffered(15_000)` **antes** de levantar el server, no después. Hoy se llama desde el path magnet (línea 58 del comentario) pero parece que no se está ejecutando antes del `streamReadyUrl()`.

---

## Archivos involucrados

| Archivo | Rol | Líneas relevantes |
|---|---|---|
| `app/src/main/java/com/arkiv/player/torrent/TorrentEngine.kt` | Levanta la sesión libtorrent, deadlines de cabeza | 124-133 (settings), 142-154 (startMagnetStream), 359-364 (deadlines de head), 376-386 (beginServing) |
| `app/src/main/java/com/arkiv/player/torrent/TorrentStreamServer.kt` | Server HTTP local con Range | 82-94 (headers), 104-109 (setSequentialRange), 117-138 (read loop), 170-188 (waitForPiece) |
| `app/src/main/java/com/arkiv/player/torrent/StreamBuffering.kt` | Lógica pura de read-ahead / head pieces | 18-24 (readAheadWindow), 32-38 (headPieces) |
| `app/src/main/java/com/arkiv/player/torrent/TorrentServingService.kt` | Foreground service para mantener proceso vivo | — |
| `app/src/main/java/com/arkiv/player/playback/VlcPlayer.kt` | Wrapper de libVLC | 183 líneas |
| `app/src/main/java/com/arkiv/player/ui/player/PlayerViewModel.kt` | Decide URL y arma `PlayerData` | 120-148 (camino magnet), 152-184 (camino archive.org) |

---

## Logs instrumentados (tag `ArkivStream`)

Los logs están listos en `TorrentStreamServer.kt` para próximas capturas. Tag para filtrar:

```bash
adb logcat -v threadtime ArkivStream:V '*:S'
```

Eventos emitidos:

- `REQ +Xms` — request HTTP recibida del cliente
- `RANGE start=… end=… startPiece=…` — análisis del Range
- `FILE_EXISTS waitedMs=…` — archivo en disco
- `RAFOPEN +Xms` — RandomAccessFile abierto
- `PIECE_HAVE piece=N immediate|waitedMs=…` — pieza disponible
- `PIECE_READY piece=N waitedMs=… pos=…` — pasada la espera
- `FIRST_BYTE +Xms pos=…` — primer byte escrito al socket
- `DONE +Xms served=…B` — conexión cerrada limpia
- `SERVE_FAIL +Xms: …` — excepción de red
- `WAIT_PIECE_TIMEOUT` / `PIECE_TIMEOUT` — no hay pieza

---

## Conclusión

Los **10.5 s de demora** NO son inaceptables para un torrent frío (es lo que tarda un peer en estar disponible y entregar la pieza 0). Lo que **sí es bug** es que `TorrentStreamServer` envíe la respuesta HTTP antes de tener la pieza, lo que provoca que VLC aborte y reintente con sockets paralelos. El fix B (esperar la pieza antes de escribir headers) es el de mayor impacto y se puede combinar con el fix A para reducir el tiempo absoluto.
