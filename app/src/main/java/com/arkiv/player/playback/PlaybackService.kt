package com.arkiv.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.arkiv.player.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Referencia al capítulo que se está reproduciendo (para el deep-link de la notificación). */
object NowPlaying {
    @Volatile
    var episodeId: String? = null

    /**
     * ¿Está abierta la pantalla del reproductor en ESTE dispositivo?
     *
     * Existe aparte de [episodeId] a propósito: ese valor no se limpia nunca —lo leen el deep-link
     * de la notificación y la resolución de siguiente/anterior, que lo necesitan DESPUÉS de cerrar
     * el reproductor—. Sin esta señal el publisher del TV seguía anunciando el último capítulo en
     * pausa para siempre, y la barra del celu mostraba algo que hacía rato no sonaba.
     */
    @Volatile
    var playerOpen: Boolean = false

    /** Instante en que se abrió el reproductor; el celu lo usa para distinguir "volvió a arrancar
     *  lo mismo" de "sigue lo mismo" (ver `NowPlayingCoordinator`). Solo tiene sentido mientras
     *  [playerOpen] es true — no se limpia al cerrar porque no hace falta. */
    @Volatile
    var playerOpenedAtMs: Long = 0L

    /**
     * Nombre del canal en vivo actual (Tarea 15), o null fuera de modo vivo.
     *
     * Existe porque un canal en vivo NO es un episodio de la biblioteca: `episodeId` vale
     * `"live:<code>"`, y `NowPlayingPublisher.metaFor()` no tiene de dónde sacar un título si busca
     * eso en `ArkivRepository` (headerInfo/getEpisode devuelven vacío, la barra del celu quedaría en
     * blanco al enviar un canal al TV). `PlayerScreen` lo actualiza con cada zap, igual que
     * [episodeId]; no se limpia al salir por el mismo motivo que ese campo no se limpia.
     */
    @Volatile
    var liveChannelName: String? = null
}

/**
 * The service's [ExoPlayer], the one that plays downloaded files (successor of the old libVLC
 * handle). The screen drives it through its `MediaController` for everything —transport, tracks,
 * speed, volume, first frame, errors— and uses this handle ONLY to bind its video `TextureView`
 * (and to ask whether the loaded media already painted, for `MediaReusePolicy`).
 *
 * Why the surface doesn't go through the controller: every `PlayerScreen` builds its own
 * `MediaController`, two screens coexist during a navigation, and a controller's surface state is
 * its own. The outgoing controller sends `setVideoSurface(null)` when its TextureView is torn down
 * (media3 1.5.1 `MediaControllerImplBase.onSurfaceTextureDestroyed`) and the session applies it to
 * the shared player, blanking the incoming screen. The player's own `setVideoTextureView` /
 * `clearVideoTextureView(view)` arbitrate that correctly: the latter is a no-op unless `view` is the
 * current one.
 */
object PlaybackEngine {
    @Volatile
    var player: ExoPlayer? = null
}

const val ACTION_OPEN_PLAYER = "com.arkiv.player.OPEN_PLAYER"

/**
 * Qué capítulo abrir con [ACTION_OPEN_PLAYER]. Opcional: sin él se abre el que esté sonando
 * ([NowPlaying]), que es lo que quiere la notificación del reproductor. Lo usa el aviso de "descarga
 * completa", que apunta a un capítulo concreto y no al que sonaba.
 */
const val EXTRA_EPISODE_ID = "episodeId"

/**
 * Service that hosts the ExoPlayer for downloaded files ([LocalExoPlayer]) behind a MediaSession.
 * Media3 builds the playback notification from it (artwork and controls on the lock screen and the
 * notification shade), and the session keeps the file playing when the app goes to the background.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = LocalExoPlayer.build(this)
        PlaybackEngine.player = player

        // Al tocar la notificación se abre la app en el capítulo actual.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(MediaItemResolverCallback)
            .build()
    }

    /**
     * Al enviar MediaItems desde un MediaController a la MediaSession, media3 **descarta el
     * `localConfiguration` (la URI)** al cruzar el límite controller→session, y sin este callback
     * el comando `setMediaItems` se ignora en silencio (el player nunca recibe los ítems → pantalla
     * negra, no reproduce). Acá reconstruimos cada MediaItem con su URI, que la UI preserva en
     * `requestMetadata.mediaUri` (ese campo SÍ sobrevive el IPC). Es el patrón recomendado de media3.
     */
    private object MediaItemResolverCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(ArrayList(mediaItems.size)) { item ->
                val uri = item.requestMetadata.mediaUri
                val ex = item.requestMetadata.extras
                val b = item.buildUpon()
                if (uri != null) b.setUri(uri)
                // Reconstruir el PlayerSourceTag (kind/referer/etc.) que se perdió en el IPC. Ver
                // PlayerSourceTagIpc: el codec queda ahí (y no acá) para que el round trip sea
                // testeable sin Robolectric.
                PlayerSourceTagIpc.decodeFromBundle(ex)?.let { b.setTag(it) }
                b.build()
            }
            return Futures.immediateFuture(resolved)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // OJO: casteando, el reproductor local queda con playWhenReady=false A PROPÓSITO (para no
        // competirle el stream al receptor), que es justo la condición que acá se lee como "no hay
        // nada reproduciendo". It's fine for the service to stop —the magis proxy and the live one
        // live in the graph, not here—; what CANNOT happen is for it to let go of the network, and
        // that's what the releaseNetworkResources() guard takes care of.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // El usuario sacó la app de recientes sin reproducción activa: cortar recursos de red
            // (proxy de magis + proxy de vivo) antes de frenar el service.
            releaseNetworkResources()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Stop the magis proxy and the live one BEFORE releasing the player: both live in the
        // graph (background via service/MediaSession), so when the service is destroyed this is
        // where they have to be released to avoid leaking network/battery/disk.
        releaseNetworkResources()
        PlaybackEngine.player = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Cierra el proxy de archive y el de vivo (idempotente y a prueba de nulls). */
    private fun releaseNetworkResources() {
        // Con una sesión de Chromecast viva NO se sueltan: el receptor está jalando bytes del server
        // LAN de este proceso. Pasa de verdad en el camino "abrir la app casteando y mandar el
        // capítulo directo a la TV": ahí nunca hubo reproducción local, el service quedó solo
        // BINDEADO, y al soltar el MediaController (salir de la pantalla) el service se destruye y
        // llega acá.
        if (isCasting()) {
            android.util.Log.i("ArkivCast", "not releasing network resources: a Chromecast session is alive")
            return
        }
        runCatching {
            val graph = (application as com.arkiv.player.ArkivApp).graph
            runCatching { graph.archiveCacheProxy.stop() }
            // Tarea 14 (canal en vivo) creaba liveHlsProxy/liveController en el grafo pero nunca los
            // cerraba: el ServerSocket en 127.0.0.1 y su hilo accept() quedaban vivos el resto del
            // proceso después de salir de un canal. Mismo hermano que archiveCacheProxy: se cierra
            // acá, con la MISMA guarda de casteo de arriba -desde la Tarea 18 esa guarda protege DE
            // VERDAD una sesión de Chromecast en curso: el receptor jala los segmentos de ESTE
            // proxy (ver PlayerScreen.castRequestFor/LiveHlsProxy.lanUrl), así que cerrarlo con la
            // TV todavía reproduciendo le cortaría el canal en seco.
            runCatching { graph.liveHlsProxy.stop() }
            // cerrar() solo invalida la caché de sesiones resueltas (no hay socket que soltar acá,
            // eso ya lo hizo stop() arriba) para que el próximo canal que se abra no reutilice una
            // sesión vieja del gateway después de un corte largo de red/proceso en pausa.
            runCatching { graph.liveController.cerrar() }
        }
    }

    /** ¿Hay sesión de Chromecast viva? Sin Google Play Services el manager no existe → false. */
    private fun isCasting(): Boolean = runCatching {
        (application as com.arkiv.player.ArkivApp).graph.castSession?.casting?.value == true
    }.getOrDefault(false)
}
