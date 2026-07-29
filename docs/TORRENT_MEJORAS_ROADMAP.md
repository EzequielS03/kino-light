# Roadmap de mejoras del reproductor de torrent (Arkiv)

Catálogo exhaustivo de técnicas robadas de **Alfa**, **Balandro** y los motores **Elementum** (Go, al día) y **Torrest** (Go). Cada ítem indica: qué hace el motor origen (con `archivo:línea`), qué tiene Arkiv hoy, qué falta implementar, y el archivo Arkiv donde va. Priorizado por impacto en **arranque / buffer / descarga / priorización**.

Stack Arkiv: **libtorrent4j 2.1.0-31** (libtorrent 2.0) + servidor HTTP propio (`TorrentStreamServer`) + **libVLC 3.6.0** (decodificación real) tras media3.

Leyenda esfuerzo: 🟢 pequeño · 🟡 medio · 🔴 grande. Riesgo de regresión: ⚠️ bajo · ⚠️⚠️ medio · ⚠️⚠️⚠️ alto (toca ruta caliente de reproducción).

---

## ESTADO DE EJECUCIÓN

### ✅ VALIDADO EN DEVICE (2026-07-24, S24+ por USB + Fire TV Stick por ADB WiFi)
Medido con logcat en vivo (tags `ArkivGate`/`ArkivDiag`/`ArkivStream`/`ArkivVlc`) sobre torrents reales:
- **Throughput ×2-5:** ventana de streaming 8→40MB + cabeza 16MB engancharon el swarm. En un mismo torrent (5.8GB, celu): **500-800 → 1000-1358 KB/s**. En la TV (One Piece 1080p): **hasta 2400 KB/s, 77 peers**.
- **Arranque a menos de la mitad:** cola en PARALELO con la cabeza (deadlines iguales) → gate = max(cabeza,cola) en vez de suma. Mismo torrent: **48s → 22s** (celu); TV: **11.25s**.
- **Progreso suave** (bytes parciales `totalWantedDone/totalWanted`) en vez de saltar 0→50%.
- **Reproducción sin estancones** tras el arranque en la TV (~90s sin un solo re-buffer); bache post-apertura mitigado con VLC `network-caching` 2500→6000ms.
- **CloseNotifier confirmado** (`PIECE_ABORT client-gone` al cerrar/seek).
- **Overlay "No se encontró peer"** funciona (magnet muerto en resume).
- Parámetros finales tras iterar en device: `HEADER_BYTES=16MB`, `FOOTER_DEADLINE_BASE=-10000` (paralelo), `STREAM_WINDOW_BYTES=40MB` (clamp [4,64]), VLC torrent `network-caching=6000`. Log de spam `PIECE_HAVE immediate` eliminado; añadido log de infohash al arrancar stream.



**✅ Implementado y verificado (compila + 36 tests verdes) — commit pendiente:**
- **1.1** Gate de arranque = cabeza **+ cola** completas (no 1 byte). `bufferReady()`/`bufferProgress()` en `TorrentEngine.kt`; gate en `PlayerViewModel.preBufferHead()`. `PREBUFFER_BYTES` 1B→2MB.
- **1.2** Footer 1MB→5MB (MKV/AVI) y 12MB (MP4/MOV); cabeza 5MB para MP4 faststart. Resuelto el código muerto `MP4_LIKE` (rama por contenedor).
- **1.3** Ventana de prioridad **escalonada** 7/6/5/4/3 por distancia al cabezal (`promoteWindow`/`windowPriority` en `TorrentStreamServer.kt`), antes TOP binario.
- **1.4** Deadlines en la ventana del cabezal (ya existían; ahora combinados con la prioridad escalonada).
- **1.5** Flags de streaming en `SettingsPack`: `strict_end_game_mode`, `prioritize_partial_pieces=false`, `mixed_mode_algorithm=prefer_tcp`, `whole_pieces_threshold=10`, `use_parole_mode`, `no_atime_storage`, `request_timeout=5`, `peer_connect_timeout` 10→5, `connection_speed` 200→300.
- **1.6** Límites de sesión + timeouts idle: `active_downloads/active_limit=-1`, `active_dht_limit=88`, `peer_timeout=600`, `inactivity_timeout=1800`.
- **2.1** **CloseNotifier**: `waitForPiece` aborta si VLC cerró la conexión (`clientGone` en `TorrentStreamServer.kt`) → no seguir 120s bajando un stream abandonado tras un seek.

**⛔ Descartado por incompatibilidad de versión:**
- **1.7** Read cache / `TunedStorage` (`cache_size`, `use_read_cache`, `coalesce_reads/writes`): **eliminados en libtorrent 2.0** (disco por mmap). No aplican a libtorrent4j 2.1. `no_atime_storage` (sí existe) se aplicó como sustituto parcial para reducir IO en flash.

**✅ Fase 3 (parcial) — audio por idioma (compila + AudioLanguageTest 9/9):**
- **3.1 + 3.3** Auto-selección de pista de **audio por idioma** (Latino>Castellano>Dual): `AudioLanguage.kt` (`LangTokens.classify` = diccionario `set_lang` de Alfa + `AudioTrackSelector.select` puro, testeado). Enganchado en `VlcPlayer` (al primer `Playing`, con reintentos; se aplica 1 vez por ítem, no pisa selección manual). Preferencia configurable vía `VlcPlayer.audioLangPreference`. **Ventaja exclusiva de Arkiv** (Alfa/Balandro sólo eligen idioma a nivel de fuente).

**✅ Fase 3 (subtítulos) — compila + tests verdes (MovieHashTest 6/6, SubtitleFilePickerTest 6/6):**
- **3.6 Hash OpenSubtitles (OSDb):** `MovieHash.kt` (head+tail 64KB, robado de Torrest `util/hash.go`) + `TorrentEngine.servedMovieHash()` (usa la cabeza/cola ya bufferizadas). Enganchado en `SubtitleApi.search(moviehash=…)` y en la búsqueda del player (permite buscar aunque no haya imdb/título; matches exactos marcados con ✓ y ordenados primero).
- **3.5 Extraer subs EMBEBIDOS del torrent:** `SubtitleFilePicker.kt` (empareja .srt/.ass por nombre → carpeta Subs/ → único video; robado de Alfa `servers/torrent.py:444`). `TorrentEngine` los prioriza (TOP + deadline, son KB) y `embeddedSubtitleFiles()` los expone; el player los carga como slave apenas bajan (aparecen en el menú CC).

**Validación en device (Fire Stick, release DUAL "Supergirl 2026 Dual YG"):**
- ✅ **auto-audio (3.1):** el menú mostró `LINE ingles - [English]` + `latino 5.1 - [Spanish]` con el **✓ en la pista latina** → audio latino activo (preferencia Latino>Cast). Sin regresión.
- ✅ **SubtitleFilePicker (3.5)** corrió y detectó bien "sin subtítulos embebidos" (mkv único).
- ✅ **Búsqueda OpenSubtitles** funciona (encontró subs en español).
- ⚠️ **moviehash:** el gate hizo timeout (13%) → la cola no estaba en disco → `servedMovieHash` leía tail incompleto = hash basura. **FIX aplicado:** `servedMovieHash()` gateado en `bufferReady()` (no calcula sin cola confirmada) + el player re-busca con el hash cuando la cola llega (sondeo 20×1.5s) → sube los subs del release exacto al tope.

**✅ Fase 4 (parcial):**
- **4.5 skip samples (SampleFilterTest 4/4):** `SampleFilter.kt` (regex de basura como palabra: sample/trailer/extras/featurette/rarbg…, con bordes que incluyen `/ \`; robado de Elementum `skipFileRegex`) en `TorrentEngine.pickVideoIndex`/`pickVideo` → nunca auto-elige un sample; si todo parece basura no filtra.
- **4.6 (parcial) badge de calidad (QualityLabelTest 4/4):** `QualityLabel.kt` extrae "1080p BluRay"/"4K HDR"/"CAM"… del nombre (tokens de Balandro/Alfa) y se muestra en la fila de fuentes (`CineDetailScreen`/`AnimeShowDetailScreen`), junto a idioma·seeds·tamaño. La lista de fuentes ya existía con color por idioma; faltaba la calidad.

**⏳ Pendiente:** Fase 2.2-2.5, Fase 3: **3.2** (auto-activar subtítulo sólo si no hay audio en el idioma — DIFERIDO: cambia el default deliberado "subs off", requiere decisión de UX) y **3.4 subdivx** (DIFERIDO: scraper de HTML vivo, la fuente cambió y necesita validación en vivo; OpenSubtitles con hash + es/es-419 ya cubre bien). Fase 4 (fuentes/UX). Falta validar en device: auto-audio con MKV DUAL, subs embebidos con un release que los traiga, y match por hash.

---

## FASE 1 — Motor, buffer y priorización (máximo impacto en arranque y estabilidad)

### 1.1 Gate de arranque real: esperar el buffer completo, no 1 byte 🟡 ⚠️⚠️⚠️
- **Hoy en Arkiv**: `PREBUFFER_BYTES = 1L` (`TorrentEngine.kt:538`) → arranca VLC en cuanto llega **la primera pieza de la cabeza**. Causa rebuffering temprano.
- **Elementum**: sólo devuelve la URL al reproductor cuando `GetBufferProgress() >= 100` — es decir, **el 100% de las piezas de cabeza + cola** (`torrent.go:300-361, 852-864`).
- **Torrest**: idéntico; `verifyBufferingState()` marca listo sólo cuando `bufferBytesMissing()==0` (`file.go:150-166`).
- **Implementar**: gate = todas las piezas del bloque cabeza **y** del bloque cola completas. Exponer `buffering_progress` (0–100) para el overlay. Arrancar VLC al 100%.
- **Archivos**: `TorrentEngine.kt` (`headBuffered`/nuevo `bufferReady`), `PlayerViewModel.kt:180-187` (`preBufferHead`).

### 1.2 Agrandar y hacer configurable la cola (footer) 🟢 ⚠️
- **Hoy**: `FOOTER_BYTES = 1 MB` (`TorrentEngine.kt:551`). Los `Cues` de un MKV largo (2–3 h) pueden superar 1 MB → VLC no obtiene índice y bufferea al buscar.
- **Motores**: Torrest `endBufferSize = 10 MB` (`api/torrents.go:10-13`); Elementum `EndBufferSize` configurable, mínimo 1 MB (`config.go:39`).
- **Implementar**: subir cola a ~5–10 MB (o configurable). Cabeza inicial subir de 2 MB a ~10–20 MB (Torrest usa `max(0.5% del archivo, 20 MB)`).
- **Archivos**: `TorrentEngine.kt:538-551` (constantes).

### 1.3 Ventana de prioridad escalonada por distancia al cabezal 🟡 ⚠️⚠️
- **Hoy**: `promoteWindow` pone **TOP binario** a una ventana de 8 MB (`TorrentStreamServer.kt:182-196`). Todo dentro es 7, fuera IGNORE.
- **Elementum**: prioridad **decreciente** según distancia a la posición de lectura (`torrent.go:658-679`): actual=6, +1..2=5, +3..5=4, +6..9=3, resto ventana=2, fuera=0. Recalcula ~1 s y **sólo si cambió** (hash min/max/count, `torrent.go:783`) para no saturar JNI.
- **Implementar**: reemplazar la ventana binaria por vector escalonado; comparar hash de prioridades antes de llamar `prioritizePieces`.
- **Archivos**: `TorrentStreamServer.kt:182-196`, `StreamBuffering.kt` (`readAheadWindow` ya existe, cablearla).

### 1.4 Deadlines en la pieza bajo el cabezal + 2-3 siguientes 🟢 ⚠️⚠️
- **Hoy**: deadlines negativos sólo al inicio para cabeza/cola (`TorrentEngine.kt:414-419`); la ventana de streaming usa deadline en `promoteWindow` pero no escalonado fino por lectura.
- **Elementum**: `PrioritizePiece()` marca `piece..piece+2` con `setPieceDeadline(i, (i-piece)*100)` (`torrent.go:594-601`). **Torrest**: rango actual deadline 0, read-ahead deadline `(i-end)*10 ms` (`reader.go:147-158`).
- **Implementar**: al pedir VLC un rango nuevo, `setPieceDeadline(piece, 0)` + deadlines crecientes (0, N, 2N ms) a las 2-3 siguientes.
- **Archivos**: `TorrentStreamServer.kt` (`waitForPiece`/`promoteWindow`).

### 1.5 Flags de streaming en el SettingsPack 🟢 ⚠️
- **Hoy** (`TorrentEngine.kt:130-145`): `connectionsLimit=300`, `connection_speed=200`, `unchoke_slots_limit=-1`, `peer_connect_timeout=10`, `announce_to_all_trackers/tiers=true`, `allow_multiple_connections_per_ip=true`, `prefer_udp_trackers=true`, DHT bootstrap. **Faltan** los flags finos de streaming.
- **Elementum/Torrest** (`service.go:230-367`): añadir/ajustar —
  - `strict_end_game_mode = true`
  - `prioritize_partial_pieces = false`
  - `mixed_mode_algorithm = prefer_tcp`
  - `whole_pieces_threshold = 10` (pide piezas enteras si están a ≤10 → contigüidad)
  - `peer_connect_timeout` 10→**2-3**, `request_timeout = 2`
  - `connection_speed` 200→**250-500**
  - `no_atime_storage = true`, `use_parole_mode = true`, `lazy_bitfields = true`
- **Archivos**: `TorrentEngine.kt:130-145` (`tunedSettings`).

### 1.6 Límites de sesión y timeouts idle largos (modo streaming) 🟢 ⚠️
- **Hoy**: **no configura** `active_downloads`/`active_limit` ni timeouts idle → usa defaults de libtorrent.
- **Elementum modo memoria** (`service.go:427-472`): `active_*=-1` (sin límite), y **anti-desconexión**: `inactivity_timeout=1800`, `peer_timeout=600`, `min_reconnect_time=20`. **Torrest**: `active_downloads=3`, `active_limit=500`, `active_dht_limit=88`.
- **Implementar**: como Arkiv sirve 1 stream, subir timeouts idle (mantener peers vivos durante pausas del usuario) y fijar `active_downloads`/`active_limit` holgados.
- **Archivos**: `TorrentEngine.kt:130-145`.

### 1.7 Read cache / TunedStorage para almacenamiento externo (Fire Stick / SD) 🟡 ⚠️⚠️
- **Hoy**: no configura cache de disco (`cache_size` etc.).
- **Torrest `TunedStorage`** (`service.go:370-376`), pensado **explícitamente para Android/NAS**: `use_read_cache=true`, `coalesce_reads/writes=true`, `max_queued_disk_bytes=10MB`, `cache_size=-1` (auto). **Elementum**: `aio_threads = NumCPU*4`.
- **Implementar**: activar read cache + coalesce cuando el destino sea almacenamiento externo/lento (caso Fire TV). Medir en device.
- **Archivos**: `TorrentEngine.kt`.

### 1.8 `whole_pieces_threshold` + sequential inteligente ya presente 🟢 ⚠️
- **Hoy**: sequential se ancla dinámicamente con `setSequentialRange` salvo en el último 10% (`TorrentStreamServer.kt:104-109`) — buena base.
- **Refinar** con `whole_pieces_threshold=10` (§1.5) para mejorar contigüidad de descarga.

---

## FASE 2 — Reader / red (evitar descarga desperdiciada y reducir coste JNI)

### 2.1 Cancelar la espera de piezas al cerrar la conexión (CloseNotifier) 🟡 ⚠️⚠️
- **Hoy**: `waitForPiece` hace polling `havePiece` cada 100 ms hasta **120 s** (`TorrentStreamServer.kt:199-217`) — **no detecta que VLC cerró el socket** → sigue priorizando/bajando un stream abandonado. Crítico en seeks rápidos (VLC abre/cierra muchos `/video`).
- **Torrest**: `RegisterCloseNotifier(CloseNotify())` rompe `waitForPiece` al desconectar el cliente (`reader.go:75-83`, `files.go:141`). **Elementum**: señal `seeked`/`removed` aborta el wait (`torrentfs.go:313-317`).
- **Implementar**: detectar cierre del socket (o `SocketException` en el write) y abortar el bucle de espera; al recibir un nuevo `Range` (seek) cancelar el reader anterior.
- **Archivos**: `TorrentStreamServer.kt:34-41` (por conexión), `199-217`.

### 2.2 `havePiece` vía bitfield cacheado (menos cruces JNI) 🟢 ⚠️
- **Hoy**: consulta `havePiece` pieza a pieza en el bucle de lectura.
- **Elementum**: cachea el bitfield y lo refresca con alerts `piece_finished` (`torrent.go:1532-1539`).
- **Implementar**: cachear `status().pieces()` y refrescar con el alert `PieceFinishedAlert`; consultar la caché en `read()`/`waitForPiece`.
- **Archivos**: `TorrentEngine.kt` (listener de alerts), `TorrentStreamServer.kt`.

### 2.3 Rate-limit tras buffering 🟢 ⚠️
- **Torrest** (`service.go:494-507`, `LimitAfterBuffering`): ilimitado durante el pre-buffer, aplica límite del usuario después → arranque rápido sin saturar la red en régimen.
- **Implementar** (opcional, si se añade ajuste de límite): quitar límite mientras `isBuffering`, aplicarlo al terminar.
- **Archivos**: `TorrentEngine.kt`.

### 2.4 Check de espacio libre antes de descargar 🟢 ⚠️
- **Torrest**: comprueba espacio y pausa si no cabe (`torrent.go:344-380`). Relevante en Fire Stick (almacenamiento chico).
- **Implementar**: antes de `beginServing`, verificar espacio en `cacheDir`; avisar si insuficiente.
- **Archivos**: `TorrentEngine.kt`.

### 2.5 Fast-resume entre sesiones (opcional) 🟡 ⚠️
- **Torrest**: guarda `.fastresume` cada 30 s y recarga al arrancar (`service.go:230-250, 670-709`).
- **Nota**: Arkiv es "stream y descarta". Sólo útil si se quiere re-ver sin re-descargar cabecera. Baja prioridad.

---

## FASE 3 — Pistas de audio / subtítulos (tu mayor ventaja: libVLC expone tracks internas)

### 3.1 Auto-seleccionar pista de audio por idioma 🟡 ⚠️
- **Hoy**: **no** auto-selecciona; siempre pista por defecto de VLC; el usuario elige a mano (`PlayerScreen.kt:1248`). Para packs DUAL (latino+castellano) arranca con la que toque.
- **Alfa/Balandro**: no lo hacen (eligen idioma a nivel de fuente) → **oportunidad exclusiva de Arkiv**.
- **Implementar**: al primer `Playing`, enumerar `vlcAudioTracks()`, normalizar el idioma de cada pista con un diccionario tipo `set_lang()`, y `setVlcAudioTrack()` a la preferida (Latino > Castellano, configurable).
- **Archivos**: `VlcPlayer.kt:310-320`, `PlayerScreen.kt:606-612`, nueva preferencia de idioma de audio.

### 3.2 Auto-activar subtítulo según preferencia 🟢 ⚠️
- **Hoy**: busca subs online pero arranca con `spu=-1` (apagado), requiere activación manual (`VlcPlayer.kt:64,106-109`; `PlayerScreen.kt:647`).
- **Alfa** (`platformtools.py:3001-3002`): si hay lista y el usuario no eligió, toma el primero y lo activa.
- **Implementar**: si el usuario tiene preferencia de subs (idioma) y no hay audio en ese idioma, auto-seleccionar la pista SPU / subtítulo externo correspondiente.
- **Archivos**: `VlcPlayer.kt`, `PlayerScreen.kt:615-649`.

### 3.3 Diccionario de idioma completo (`set_lang`) 🟢 ⚠️
- **Hoy**: `TorrentLang.classify()` por regex (`TorrentSearchApi.kt:690-702`) — bueno pero acotado.
- **Alfa** (`unify.py:504-562`): diccionario canónico que colapsa **decenas** de variantes a `cast/lat/vose/vos/vo/dual`. Portarlo enriquece clasificación de fuentes **y** el matching de pistas de audio (§3.1).
- **Archivos**: nuevo `LangTokens.kt` reutilizable por `TorrentSearchApi` y `VlcPlayer`.

### 3.4 Subtítulos de Subdivx (español/latino) 🟡 ⚠️⚠️
- **Hoy**: sólo OpenSubtitles.com (`SubtitleApi.kt`).
- **Alfa** (`subtitletools.py`): scrapers de **subdivx** (`get_from_subdivx:289`), subscene, subdl, con matching por nombre de release. Subdivx es la mejor fuente para español/latino.
- **Implementar**: proveedor Subdivx como fallback/preferente para es-LatAm.
- **Archivos**: nuevo `SubdivxApi.kt` junto a `SubtitleApi.kt`.

### 3.5 Extraer `.srt`/`.ass` embebidos del torrent 🟡 ⚠️
- **Alfa** (`servers/torrent.py:444-448`): copia cualquier `.srt` que acompañe al vídeo dentro del torrent y lo ofrece primero.
- **Implementar**: tras metadata, si el torrent trae subs junto al vídeo, priorizar su descarga y ofrecerlos como slave (`addSubtitleSlave`).
- **Archivos**: `TorrentEngine.kt` (selección de archivos), `PlayerViewModel.kt`.

### 3.6 Hash OpenSubtitles por archivo (matching preciso) 🟢 ⚠️
- **Hoy**: busca subs por imdb/season/episode (contexto), no por el archivo real.
- **Torrest** (`util/hash.go`, `files.go:111`): hash de 64 bits = head+tail 64 KB. Es el método nativo de OpenSubtitles → match exacto del release.
- **Implementar**: calcular el moviehash sobre los primeros/últimos 64 KB (ya disponibles al bufferizar cabeza+cola) y buscar por hash.
- **Archivos**: nuevo util de hash, `SubtitleApi.kt`.

---

## FASE 4 — Selección de fuentes, metadata y UX

### 4.1 Ranking: añadir señales además de seeds 🟢 ⚠️
- **Hoy**: rankea por idioma → seedBucket → calidad → seeds (`TorrentSearchApi.kt:452-457`). Ya **supera** a Alfa/Balandro (que no usan seeds).
- **Mejorar**: incorporar `leechers`/`completed` y edad del release como desempate secundario.
- **Archivos**: `TorrentSearchApi.kt:452-457`.

### 4.2 Diccionarios de tokens de calidad de los canales 🟢 ⚠️
- **Balandro** (`mejortorrents.py:319-345`, `grantorrent.py:178-180`, `torrentdivx.py`): listas ordenadas de calidad (screener→dvdrip→720p→microhd→1080p→bdremux→4k). **Alfa** (`autoplay.py:183-188`): equivalencias 1080p≈BluRay1080p≈HD1080.
- **Mejorar** `qualityRank` (`TorrentSearchApi.kt:544-558`) con estas equivalencias/orden.
- **Archivos**: `TorrentSearchApi.kt:544-558`.

### 4.3 Preview de metadata sin arrancar el swarm 🟡 ⚠️
- **Alfa** (`magnet2torrent:725-792`): resuelve el `.torrent` desde caché online `itorrents.net/torrent/<BTIH>.torrent`, valida bencode y verifica info-hash → muestra nombre/tamaño/ficheros **antes** de conectar peers. Caché por info-hash (`torrent_cached_list:247-262`) + precheck con timeout (`verify_url_torrent:795-808`).
- **Implementar**: al elegir un resultado, intentar metadata rápida (caché online o `metadata_received` con timeout corto) para listar ficheros/tamaño antes de comprometer el swarm.
- **Archivos**: `TorrentEngine.kt`, `TorrentSearchApi.kt`.

### 4.4 Trackers extra (incluir españoles) 🟢 ⚠️
- **Hoy**: `TrackerListProvider` (ngosang `trackers_best.txt` + defaults + trackers de anime) — buena base.
- **Alfa** (`servers/torrent.py:81-104`): añade `spanishtracker`/`todotorrents`. **Elementum** (`types.go:107-123`): lista embebida amplia + descarga `ngosang` all/best.
- **Mejorar**: sumar trackers hispanos y refrescar los muertos.
- **Archivos**: `TrackerListProvider.kt`.

### 4.5 Selección de archivo: BluRay + skip de samples/RAR 🟢 ⚠️
- **Hoy**: `EpisodeFilePicker` (SxxEyy/NxNN/Cap/absoluto) + fallback al mayor (`pickVideoIndex`, `TorrentEngine.kt:214-225`). Sólida.
- **Elementum** (`GetCandidateFiles:1744-1859`): añade caso **BluRay `BDMV/STREAM/*.m2ts`** (mayor por carpeta) y **skip por regex** de samples/extras + detección de RAR.
- **Mejorar**: filtrar samples/extras y manejar estructura BluRay.
- **Archivos**: `EpisodeFilePicker.kt`, `TorrentEngine.kt:214-225`.

### 4.6 UX de selección/autoplay (de Balandro/Alfa) 🟡 ⚠️
- **Autoplay con fallback secuencial**: intenta la mejor fuente; si no bufferiza en X s, pasa a la siguiente ya ordenada (Balandro `platformtools.py:644-707`; Alfa `autoplay.py:298-395`, `max_intentos=5`).
- **Badges `[calidad][idioma][tamaño]`** con color (Balandro `platformtools.py:457-466`).
- **Marcar en gris las fuentes que fallaron** en vez de ocultarlas (Balandro `platformtools.py:713-717`).
- **Archivos**: `TorrentScreen.kt`, `TorrentViewModel.kt`.

### 4.7 Estados de torrent ricos para la UI 🟢 ⚠️
- **Torrest** (`torrent.go:17-29`): enum con estados custom `BUFFERING`/`PAUSED` + métricas (`total_done`, `download_rate`, `seeders/peers`, `buffering_progress`). Arkiv ya expone algo en `streamStatus()`; alinear a este modelo para overlay completo.
- **Archivos**: `TorrentEngine.kt` (`streamStatus`), overlay del player.

---

## Cosas que Arkiv YA hace mejor (no tocar / mantener ventaja)
- Ranking por **seeds** (Alfa y Balandro no lo hacen).
- Selección de **pistas internas** de audio/subs vía libVLC (ellos manejan 1 idioma por fuente).
- **Warm-up de DHT** + bootstrap nodes + reannounce agresivo (`TorrentEngine.kt:98,491-520`).
- Priorización **atómica** cabeza+cola en una sola llamada (evita que `prioritizeFiles` async pise los IGNORE) — mantenerla al refactorizar §1.3.
- Auto-retry **HW→SW** de VLC (`VlcPlayer.kt:121-130`).

## Descartar (infra Kodi, no portable)
`setResolvedUrl`, `play_fake`, `System.HasAddon`, plantillas `plugin://`, delegación a motores externos, VFS de Kodi, `xbmcaddon`.

---

## Orden de ejecución sugerido
1. **Fase 1** (motor/buffer/priorización) — mayor impacto en el problema real (arranque + rebuffering). Empezar por 1.5 (flags, bajo riesgo) → 1.2 (tamaños buffer) → 1.1 (gate) → 1.3/1.4 (priorización) → 1.6/1.7 (device).
2. **Fase 2** (reader/red) — 2.1 (CloseNotifier) es alto valor.
3. **Fase 3** (audio/subs) — mayor ganancia de UX percibida.
4. **Fase 4** (fuentes/UX) — pulido.

Validar cada fase con los tests (`StreamBufferingTest`, `VlcPlaybackStateTest`) + build + prueba en device (Fire Stick por ADB 5555 / S24+ por WiFi).
