package com.arkiv.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
 * Instancia viva del [VlcPlayer] del service. La pantalla usa el MediaController para el transporte,
 * pero necesita el VlcPlayer real para el render (VLCVideoLayout) y la selección de pistas (audio/
 * subtítulos VLC), que no están en la API del controller.
 */
object PlaybackEngine {
    @Volatile
    var vlc: VlcPlayer? = null
}

const val ACTION_OPEN_PLAYER = "com.arkiv.player.OPEN_PLAYER"

/**
 * Servicio que aloja el VlcPlayer (libVLC sobre SimpleBasePlayer) y expone una
 * MediaSession. Media3 genera automáticamente la notificación de reproducción
 * con carátula y controles (pantalla de bloqueo / barra de notificaciones),
 * como las apps de música.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = VlcPlayer(this, mainLooper)
        PlaybackEngine.vlc = player

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
                // Reconstruir el PlayerSourceTag (kind/referer/etc.) que se perdió en el IPC.
                if (ex != null && ex.containsKey("kind")) {
                    b.setTag(
                        PlayerSourceTag(
                            kind = runCatching { SourceKind.valueOf(ex.getString("kind")!!) }.getOrDefault(SourceKind.ARCHIVE),
                            openingStartMs = if (ex.containsKey("openingStartMs")) ex.getLong("openingStartMs") else null,
                            openingEndMs = if (ex.containsKey("openingEndMs")) ex.getLong("openingEndMs") else null,
                            endingStartMs = if (ex.containsKey("endingStartMs")) ex.getLong("endingStartMs") else null,
                            castUrl = ex.getString("castUrl"),
                            referer = ex.getString("referer"),
                            userAgent = ex.getString("userAgent"),
                            proxyUrl = ex.getString("proxyUrl"),
                            knownDurationMs = ex.getLong("knownDurationMs", 0L),
                            // Si se agrega un campo al tag hay que agregarlo ACÁ y en los extras que
                            // arma PlayerScreen: el tag no cruza el IPC y lo que falte llega en su
                            // valor por defecto, en silencio. Pasó con esto: el arranque por software
                            // se quedaba en false y el HEVC seguía abriendo por hardware.
                            preferirSoftware = ex.getBoolean("preferirSoftware", false),
                        ),
                    )
                }
                b.build()
            }
            return Futures.immediateFuture(resolved)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // OJO: casteando, el reproductor local queda con playWhenReady=false A PROPÓSITO (para no
        // competirle el stream al receptor), que es justo la condición que acá se lee como "no hay
        // nada reproduciendo". Que el service se frene está bien —el motor de torrent y su server
        // LAN viven en el grafo, no acá, y el proceso lo sostiene TorrentServingService—; lo que NO
        // puede pasar es que suelte la red, y de eso se ocupa la guarda de releaseNetworkResources().
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // El usuario sacó la app de recientes sin reproducción activa: cortar recursos de red
            // (stream de torrent + proxy de archive) antes de frenar el service.
            releaseNetworkResources()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Cortar el stream de torrent y el proxy de archive ANTES de liberar el player: ambos viven
        // en el grafo (segundo plano vía service/MediaSession), así que al destruirse el service es
        // acá donde hay que soltarlos para no fugar red/batería/disco.
        releaseNetworkResources()
        PlaybackEngine.vlc = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Detiene el stream de torrent y cierra el proxy de archive (idempotente y a prueba de nulls). */
    private fun releaseNetworkResources() {
        // Con una sesión de Chromecast viva NO se sueltan: el receptor está jalando bytes del server
        // LAN de este proceso, así que stopStream() le corta el video a la TV. Pasa de verdad en el
        // camino "abrir la app casteando y mandar el capítulo directo a la TV": ahí nunca hubo
        // reproducción local, el service quedó solo BINDEADO, y al soltar el MediaController (salir
        // de la pantalla) el service se destruye y llega acá. El proceso sigue vivo por el
        // TorrentServingService, y el motor de torrent vive en el grafo, no en el service.
        if (isCasting()) {
            android.util.Log.i("ArkivCast", "no suelto los recursos de red: hay sesión de Chromecast viva")
            return
        }
        runCatching {
            val graph = (application as com.arkiv.player.ArkivApp).graph
            runCatching { graph.torrentEngine.stopStream() }
            runCatching { graph.archiveCacheProxy.stop() }
            // Tarea 14 (canal en vivo) creaba liveHlsProxy/liveController en el grafo pero nunca los
            // cerraba: el ServerSocket en 127.0.0.1 y su hilo accept() quedaban vivos el resto del
            // proceso después de salir de un canal. Mismo hermano que archiveCacheProxy: se cierra
            // acá, con la MISMA guarda de casteo de arriba (el proxy local no lo usa el Chromecast
            // todavía -castUrl siempre null para vivo, ver PlayerViewModel.abrirCanalActual-, pero
            // conviene una sola guarda para todos los recursos de red en vez de reinventar el gate).
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
