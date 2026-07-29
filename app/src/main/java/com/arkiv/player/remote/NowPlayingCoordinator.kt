package com.arkiv.player.remote

import com.arkiv.player.cast.CastProgress
import com.arkiv.player.cast.CastSessionManager
import com.arkiv.player.data.ArkivRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Única fuente de verdad de la barra: observa el Fire TV y el Chromecast y expone lo que hay que
 * dibujar. La barra queda tonta — dibuja lo que le den y no sabe de dónde viene.
 */
class NowPlayingCoordinator(
    private val tv: TvNowPlayingRepository,
    private val cast: CastSessionManager?,
    private val repository: ArkivRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<BarState?>(null)
    val state: StateFlow<BarState?> = _state.asStateFlow()

    // Caché de siguiente/anterior por episodio (ver `vecinos`). El tick corre cada 500ms y sin esto
    // cada uno pagaría dos consultas a Room (nextEpisode + previousEpisode) que materializan la
    // lista completa de episodios de la serie DOS VECES, para un resultado que solo cambia cuando
    // cambia el episodio, no cada medio segundo.
    private var vecinosEpisodeId: String? = null
    private var vecinosCache: Pair<Boolean, Boolean> = false to false

    // Marcas de arranque por fuente, en el reloj LOCAL del celu. La barra muestra la que arrancó
    // ÚLTIMO, así que hay que saber cuándo arrancó cada una de verdad. [sellarMarca] las congela
    // cuando la fuente se calla en vez de borrarlas (un corte momentáneo no es un arranque nuevo, ver
    // su doc) y las re-sella cuando la IDENTIDAD cambia —no alcanza con el episodio solo: el mismo
    // capítulo puede volver a arrancar en cualquiera de las dos fuentes (ver `startedAtMs` del TV y
    // `mediaGeneracion` del cast)—. Este es el único lugar de la app que ve las dos fuentes cambiar,
    // por eso el sellado vive acá y no dentro de BarSource, que se mantiene puro.
    private var castMarca = MarcaFuente()
    private var tvMarca = MarcaFuente()

    fun start() {
        scope.launch {
            while (true) {
                val castLectura = leerCast()
                val castFoto = castLectura?.foto
                val tvFoto = tv.state.value
                val ahora = System.currentTimeMillis()

                castMarca = sellarMarca(castMarca, castLectura?.identidad, ahora)
                val tvIdentidad = tvFoto?.let { "${it.nowPlaying.episodeId}#${it.nowPlaying.startedAtMs}" }
                tvMarca = sellarMarca(tvMarca, tvIdentidad, ahora)

                // Un Fire TV que se murió sin avisar conserva su foto hasta la cota de 2 minutos, y
                // con la regla de "gana el último" esa foto podía ganarle a un Chromecast VIVO y
                // dejar la barra escondida ~75s (a los 45s `render.hidden` la oculta, y no vuelve
                // hasta que la foto muere del todo). Si hay cast al que caer, una foto rancia no
                // compite. Si NO lo hay se pasa igual que siempre: ahí ocultarla es justamente lo
                // correcto, y es de lo que depende que "Reproduciendo ahora" se cierre sola.
                val tvCompite = tvFoto != null && (
                    castFoto == null ||
                        !ExtrapolatedClock.isStale(tvFoto, ahora, ExtrapolatedClock.HIDE_MS)
                    )

                _state.value = BarSource.pick(
                    castActivo = cast?.casting?.value == true,
                    cast = castFoto,
                    castDesdeMs = castMarca.desdeMs,
                    tv = if (tvCompite) tvFoto else null,
                    tvDesdeMs = tvMarca.desdeMs,
                    ahoraMs = ahora,
                )
                delay(TICK_MS)
            }
        }
    }

    /** Foto del Chromecast más la identidad de arranque (episodio+generación) para [sellarMarca]. */
    private data class CastLectura(val foto: TvNowPlaying, val identidad: String)

    /**
     * Foto del Chromecast. La posición y la duración pasan por [CastProgress] a propósito: con el
     * audio transcodificado el receptor arranca en cero y no conoce la duración, así que en crudo
     * mentiría.
     */
    private suspend fun leerCast(): CastLectura? {
        val c = cast ?: return null
        // `pending` sobrevive al fin de la sesión (el manager lo recarga al reconectar), así que sin
        // este corte cada tick saltaría al hilo principal a leer un CastPlayer muerto para siempre.
        if (!c.casting.value) return null
        val req = c.currentRequest ?: return null
        // La identidad se arma ACÁ, junto con `req`, y no más tarde a partir de `cast?.mediaGeneracion`:
        // `setMedia()` corre en el hilo del reproductor y puede pisar `pending`/la generación entre
        // que esta función retorna y que alguien más los mirara por separado, desacoplando el
        // episodio leído de la generación que se le atribuye.
        val identidad = "${req.episodeId}#${c.mediaGeneracion}"
        val (pos, dur, playing, buffering, idle, terminado) = withContext(Dispatchers.Main) {
            Lectura(
                CastProgress.contentPosition(c.player.currentPosition, c.baseOffsetMs),
                CastProgress.contentDuration(c.player.duration, c.knownDurationMs),
                c.player.isPlaying,
                c.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
                c.player.playbackState == androidx.media3.common.Player.STATE_IDLE,
                c.player.playbackState == androidx.media3.common.Player.STATE_ENDED,
            )
        }
        // Terminado no es pausado: sin este corte la barra queda clavada al final mostrando ▶ para
        // siempre, porque en el camino del cast no hay cota de rancio (stale/hidden dependen de
        // bar.extrapolar, que acá siempre da false) — nada la limpiaría salvo que el receptor
        // colgara la sesión o el usuario pulsara parar. El episodio se acabó: no hay nada que dibujar.
        if (terminado) return null
        val (hasNext, hasPrev) = vecinos(req.episodeId)
        return CastLectura(
            TvNowPlaying(
                episodeId = req.episodeId,
                itemId = "",
                kind = "CAST",
                title = req.title,
                subtitle = req.subtitle,
                posterUrl = req.artworkUrl,
                // Sin cargar todavía el receptor reporta 0: en el camino transcodificado baseOffsetMs ya
                // trae el punto de reanudación (pos ya lo incluye), pero en el directo vale 0 y el punto
                // real vive en startPositionMs. maxOf es monótono y sirve para los dos caminos.
                positionMs = if (idle) maxOf(pos, req.startPositionMs) else pos,
                durationMs = dur,
                state = when {
                    // Antes de que el receptor termine de cargar —y en el camino transcodificado eso
                    // espera detrás de awaitSourceReady, varios segundos— no está pausado ni
                    // reproduciendo: BUFFERING existe justo para que la barra no muestre un ▶ sobre algo
                    // que todavía no arrancó (ver el comentario en MiniPlayerBar.kt:153).
                    idle || buffering -> TvPlaybackState.BUFFERING
                    playing -> TvPlaybackState.PLAYING
                    else -> TvPlaybackState.PAUSED
                },
                hasNext = hasNext,
                hasPrev = hasPrev,
                // Reloj de TV únicamente: lo lee NowPlayingCodec al publicar y TvNowPlayingRepository al
                // recibir. Nadie en el camino del cast lo consulta — la barra se guía por
                // BarState.receivedAtMs, sellado con el reloj local del celu.
                at = "",
                // startedAtMs queda en su default (0): es un concepto del TV (ver NowPlayingModels),
                // acá la identidad de arranque es `identidad` (episodio+generación), no este campo.
            ),
            identidad,
        )
    }

    /**
     * Siguiente/anterior los sabe el repositorio, no el receptor: el Chromecast tiene un solo ítem
     * cargado y no sabe nada de la serie. Es la misma fuente que usa el publisher del TV, así que
     * celu y TV nunca ofrecen un salto distinto.
     *
     * Memoizado por episodeId: sin esto, cada tick de 500ms —dos veces por segundo— dispararía las
     * dos consultas de nuevo para el mismo episodio. Solo se vuelve a preguntar cuando el episodio
     * cambia de verdad.
     */
    private suspend fun vecinos(episodeId: String): Pair<Boolean, Boolean> {
        vecinosEpisodeId?.let { if (it == episodeId) return vecinosCache }
        val resultado = (repository.nextEpisode(episodeId) != null) to
            (repository.previousEpisode(episodeId) != null)
        vecinosEpisodeId = episodeId
        vecinosCache = resultado
        return resultado
    }

    private data class Lectura(
        val posicionMs: Long,
        val duracionMs: Long,
        val reproduciendo: Boolean,
        val buffereando: Boolean,
        val cargando: Boolean,
        val terminado: Boolean,
    )

    private companion object {
        const val TICK_MS = 500L
    }
}
