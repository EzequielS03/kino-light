package com.arkiv.player.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Convierte al vuelo el audio que el Chromecast no sabe decodificar y lo sirve por HTTP para que la
 * TV lo consuma.
 *
 * Es un libVLC headless (sin pantalla) leyendo la misma URL que reproduciría el celu y escupiendo un
 * MPEG-TS por la LAN. El video pasa intacto: lo único que se re-encodea es el audio. Ver
 * [CastSoutChain] para la cadena y el por qué.
 *
 * Corre en su propia instancia de LibVLC, aparte de la del reproductor local, porque son dos
 * pipelines con vidas distintas: el local queda pausado mientras la TV reproduce.
 *
 * **No es "buscable"**: lo que sale es un stream en vivo, sin duración ni Range. Moverse por el
 * video significa volver a arrancar todo esto en otro punto ([start] con otro `startAtMs`), que es
 * lo mismo que hace Jellyfin cuando no usa HLS.
 */
class CastTranscoder(context: Context) {

    private val appContext = context.applicationContext

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    /** La URL que está sirviendo ahora, o null si no hay transcode andando. */
    @Volatile
    var activeUrl: String? = null
        private set

    /** Desde qué punto del video arrancó el stream actual. El receptor cuenta desde cero a partir
     *  de acá, así que sin este offset la posición que se guarda queda corrida. */
    @Volatile
    var baseOffsetMs: Long = 0L
        private set

    /**
     * Arranca (o reinicia) el transcode de [sourceUrl] desde [startAtMs] y devuelve la URL que hay
     * que mandarle al receptor, o null si no se pudo.
     *
     * @param lanIp la IP del celu en la LAN: la TV tiene que poder alcanzarla.
     */
    fun start(sourceUrl: String, lanIp: String, startAtMs: Long, audioTrackIndex: Int?): String? {
        stop()
        return runCatching {
            // Verboso a propósito: con `--quiet` no hay forma de saber si el receptor llegó a
            // conectarse al sout ni por qué se cayó, que es justo lo que hace falta diagnosticar.
            // Sale en logcat con tag "VLC".
            val vlc = LibVLC(appContext, arrayListOf("--no-video", "-vv"))
            val mp = MediaPlayer(vlc)
            mp.setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.EncounteredError -> Log.e(TAG, "libVLC falló transcodificando")
                    MediaPlayer.Event.EndReached -> Log.i(TAG, "el transcode llegó al final del origen")
                    MediaPlayer.Event.Opening -> Log.i(TAG, "transcode: abriendo el origen")
                    MediaPlayer.Event.Playing -> Log.i(TAG, "transcode: el origen está fluyendo")
                    else -> Unit
                }
            }
            val options = CastSoutChain.mediaOptions(PORT, startAtMs, audioTrackIndex)
            val media = Media(vlc, Uri.parse(sourceUrl)).apply { options.forEach(::addOption) }
            mp.media = media
            // El Media queda retenido por el MediaPlayer; soltamos NUESTRA referencia o se fuga.
            media.release()
            mp.play()

            libVlc = vlc
            player = mp
            baseOffsetMs = startAtMs
            val url = CastSoutChain.streamUrl(lanIp, PORT)
            activeUrl = url
            Log.i(TAG, "transcodificando audio → $url · desde=${startAtMs}ms · pista=$audioTrackIndex · origen=$sourceUrl")
            Log.i(TAG, "opciones vlc: $options")
            url
        }.onFailure {
            Log.e(TAG, "no se pudo arrancar el transcode: ${it.message}", it)
            stop()
        }.getOrNull()
    }

    /**
     * Espera a que el stream esté REALMENTE entregando bytes. Hay que llamarla antes de decirle al
     * receptor que cargue la URL: si se le pide antes, recibe conexión rechazada, se va a idle y no
     * reintenta nunca. libVLC tarda en abrir el origen, demuxear y recién ahí bindear el puerto.
     *
     * Bloquea, así que va fuera del hilo principal.
     *
     * @return true si quedó listo; false si se agotó [timeoutMs].
     */
    fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (player == null) {
                Log.w(TAG, "el transcodificador se detuvo mientras esperábamos que estuviera listo")
                return false
            }
            if (StreamReadiness.servesData("127.0.0.1", PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)) {
                Log.i(TAG, "stream listo en ${System.currentTimeMillis() - t0}ms")
                return true
            }
            runCatching { Thread.sleep(POLL_MS) }.onFailure { return false }
        }
        Log.e(TAG, "el stream no entregó datos en ${timeoutMs}ms: el receptor se quedaría en idle")
        return false
    }

    /** Corta el transcode y libera el puerto. Idempotente. */
    fun stop() {
        val mp = player
        val vlc = libVlc
        player = null
        libVlc = null
        activeUrl = null
        baseOffsetMs = 0L
        if (mp == null && vlc == null) return
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        runCatching { vlc?.release() }
        Log.i(TAG, "transcode detenido")
    }

    private companion object {
        const val TAG = "ArkivCast"

        /** Fijo para que la URL sea predecible. Es el celu sirviendo a la TV en la LAN. */
        const val PORT = 8099

        /** Generous on purpose: opening the source can take a while -- with torrent (removed in
         *  this branch's pruning) it was waiting on the first pieces; with Magis today it's the
         *  CDN's own latency, measured anywhere from ~0.2s to ~20s per range. It's wait time, not
         *  CPU time. */
        const val READY_TIMEOUT_MS = 45_000L
        const val POLL_MS = 250L
        const val CONNECT_TIMEOUT_MS = 500
        const val READ_TIMEOUT_MS = 1_500
    }
}
