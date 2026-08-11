package com.arkiv.player.remote

import androidx.media3.common.Player
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.playback.PlaybackEngine
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.PocketBaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * (Solo TV) Publica fotos del estado de reproducción en `devices.nowPlaying` para que el celu pinte
 * el miniplayer.
 *
 * Lee `PlaybackEngine.vlc` directamente en vez de observar la UI: el estado de posición/duración vive
 * como estado local de Compose dentro de PlayerContent, y el player del servicio es la fuente real.
 *
 * Publica ante un CAMBIO DE EVENTO (episodio, estado, duración, vecinos) o cada [HEARTBEAT_MS] sin
 * importar el estado (reproduciendo o en pausa), para corregir la deriva de la extrapolación del
 * celu Y para que el registro siga "vivo": si deja de avanzar es porque el TV ya no está publicando
 * (se apagó, crasheó, perdió red), no porque esté pausado — así el celu puede distinguir "TV en
 * pausa" de "TV caído" por la antigüedad del snapshot. Nunca más de un PATCH por segundo.
 */
class NowPlayingPublisher(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
) {
    private data class Raw(val episodeId: String, val positionMs: Long, val durationMs: Long, val state: TvPlaybackState)

    fun start() {
        scope.launch {
            var lastEventKey: String? = null
            var lastPatchMs = 0L
            var lastWasNull = false
            while (true) {
                val now = System.currentTimeMillis()
                val foto = try {
                    build()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Si esto falla en cada tick, el publisher latchea en "sin reproducir" (lastWasNull)
                    // aunque el TV siga reproduciendo: dejar rastro en el log es lo único que permite
                    // diagnosticarlo (a diferencia de un runCatching mudo).
                    android.util.Log.w("ArkivRemote", "nowPlaying: build() falló (se publica vacío): ${e.message}")
                    null
                }

                if (foto == null) {
                    // Salió del player: limpiar una sola vez, no cada segundo. Latchear sin
                    // confirmar deja la foto vieja publicada y nadie reintenta: solo se marca
                    // lastWasNull si el PATCH realmente llegó a PocketBase.
                    if (!lastWasNull && publish(null)) {
                        lastWasNull = true
                        lastEventKey = null
                        lastPatchMs = now
                    }
                } else {
                    lastWasNull = false
                    val key = NowPlayingCodec.eventKey(foto)
                    val evento = key != lastEventKey
                    val latido = now - lastPatchMs >= HEARTBEAT_MS
                    if ((evento || latido) && now - lastPatchMs >= MIN_INTERVAL_MS) {
                        publish(NowPlayingCodec.encode(foto))
                        lastEventKey = key
                        lastPatchMs = now
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Lee el player en el hilo principal (SimpleBasePlayer se construyó con mainLooper). */
    private suspend fun readPlayer(): Raw? = withContext(Dispatchers.Main) {
        // Con el reproductor cerrado no hay nada que anunciar, aunque el player siga vivo y en
        // pausa: NowPlaying.episodeId no se limpia nunca.
        if (!NowPlaying.playerOpen) return@withContext null
        val p = PlaybackEngine.vlc ?: return@withContext null
        val epId = NowPlaying.episodeId ?: return@withContext null
        val state = when {
            p.playbackState == Player.STATE_BUFFERING -> TvPlaybackState.BUFFERING
            p.isPlaying -> TvPlaybackState.PLAYING
            else -> TvPlaybackState.PAUSED
        }
        // duration puede venir C.TIME_UNSET (negativo) hasta que se conoce: normalizar a 0.
        val dur = p.duration.let { if (it > 0) it else 0L }
        Raw(epId, p.currentPosition.coerceAtLeast(0), dur, state)
    }

    private suspend fun build(): TvNowPlaying? {
        val raw = readPlayer() ?: return null
        val meta = metaFor(raw.episodeId)
        return TvNowPlaying(
            episodeId = raw.episodeId,
            itemId = meta.itemId,
            kind = meta.kind,
            title = meta.title,
            subtitle = meta.subtitle,
            posterUrl = meta.posterUrl,
            positionMs = raw.positionMs,
            durationMs = raw.durationMs,
            state = raw.state,
            hasNext = meta.hasNext,
            hasPrev = meta.hasPrev,
            at = java.time.Instant.now().toString(),
            startedAtMs = NowPlaying.playerOpenedAtMs,
        )
    }

    /** Lo del payload que NO cambia mientras siga el mismo episodio. */
    private data class Meta(
        val itemId: String,
        val kind: String,
        val title: String,
        val subtitle: String,
        val posterUrl: String,
        val hasNext: Boolean,
        val hasPrev: Boolean,
    )

    private var metaEpisodeId: String? = null
    private var meta: Meta? = null

    /**
     * Metadatos del episodio, cacheados por `episodeId`.
     *
     * De todo el payload, lo único que cambia al ritmo del tick (1s) es posición, duración y estado.
     * Recalcular el resto en cada vuelta eran ~5 consultas a Room por segundo, tres de ellas
     * materializando la lista COMPLETA de episodios del ítem (`headerInfo`, `nextEpisode`,
     * `previousEpisode`) — para un pack de torrent, la temporada entera. Y sin final: el bucle no
     * para nunca, porque `NowPlaying.episodeId` no se limpia en ningún lado y `PlaybackEngine.vlc`
     * solo se anula en `PlaybackService.onDestroy`. En un Fire Stick de 2 núcleos eso compite con la
     * decodificación de video.
     *
     * El cache se puebla solo si las consultas terminan bien: si una tira, el tick siguiente
     * reintenta en vez de quedarse con metadatos a medias.
     *
     * Costo asumido: un dato que llegue tarde a la biblioteca (una carátula que se descarga después
     * de empezar el capítulo) no se republica hasta cambiar de episodio. El celu ya cubre ese caso
     * buscando la carátula en su propia biblioteca cuando `posterUrl` viene vacía (`rememberPoster`).
     */
    private suspend fun metaFor(episodeId: String): Meta {
        meta?.takeIf { metaEpisodeId == episodeId }?.let { return it }
        // Vivo (Tarea 15): "live:<code>" no es un episodio de la biblioteca -- headerInfo()/
        // getEpisode()/itemThumbnailForEpisode() no saben nada de un canal y devolverían todo
        // vacío (la barra del celu quedaría con el título en blanco al enviar un canal al TV, y
        // encima se pagarían varias consultas a Room por segundo para nada). El nombre real lo
        // deja NowPlaying.liveChannelName (lo actualiza PlayerScreen con cada zap); sin eso, cae
        // al código del canal para no dejar el título vacío del todo.
        val nuevo = if (episodeId.startsWith(com.arkiv.player.playback.PlayerSource.LIVE_PREFIX)) {
            Meta(
                itemId = episodeId,
                kind = "LIVE",
                title = com.arkiv.player.playback.NowPlaying.liveChannelName
                    ?: episodeId.removePrefix(com.arkiv.player.playback.PlayerSource.LIVE_PREFIX),
                subtitle = "En vivo",
                posterUrl = "",
                hasNext = false,
                hasPrev = false,
            )
        } else {
            val header = repository.headerInfo(episodeId)
            Meta(
                itemId = repository.getEpisode(episodeId)?.itemId.orEmpty(),
                // Informativo (la UI no lo usa para decidir nada): la fuente real la resuelve el
                // player del TV por el prefijo del id, igual que hace ArkivTvRoot / PlayerSource.kindFor.
                kind = when {
                    episodeId.startsWith("torrent:") -> "TORRENT"
                    episodeId.startsWith("web:") -> "WEB"
                    else -> "ARCHIVE"
                },
                title = header?.itemTitle.orEmpty(),
                subtitle = header?.episodeLabel.orEmpty(),
                // La carátula sale del ítem de la biblioteca, NO de PlayerData.artworkUrl: ésa solo
                // está poblada para archive y vale "" en torrent y web.
                posterUrl = repository.itemThumbnailForEpisode(episodeId).orEmpty(),
                hasNext = repository.nextEpisode(episodeId) != null,
                hasPrev = repository.previousEpisode(episodeId) != null,
            )
        }
        meta = nuevo
        metaEpisodeId = episodeId
        return nuevo
    }

    /** true solo si el PATCH llegó a PocketBase: quien limpia el estado (`lastWasNull`) lo usa para
     *  no latchear un borrado que en realidad no se publicó. */
    private suspend fun publish(json: String?): Boolean {
        val s = deviceAuth.session.value ?: return false
        return try {
            client.updateRecord(
                PocketBaseConfig.COLLECTION_DEVICES,
                s.recordId,
                mapOf("nowPlaying" to json),
                s.token,
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ArkivRemote", "nowPlaying no se pudo publicar: ${e.message}")
            false
        }
    }

    internal companion object {
        const val TICK_MS = 1_000L
        const val MIN_INTERVAL_MS = 1_000L

        /**
         * Refresco más lento de un TV sano. `ExtrapolatedClock.WARN_MS` depende de este valor: si el
         * latido se hace más lento, hay que subir también los umbrales de desconexión del celu.
         */
        const val HEARTBEAT_MS = 10_000L
    }
}
