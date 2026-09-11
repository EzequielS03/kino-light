package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { UNKNOWN, MAGIS, LOCAL, LIVE, DITU }

data class PlayerSourceTag(
    val kind: SourceKind,
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val castUrl: String?,
    val referer: String? = null,      // headers para streams web (algunos hosts exigen Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,     // web: URL proxeada de respaldo si la directa falla (403/geo/anti-leech)
    /**
     * Headers extra que exige el origen, más allá de Referer y User-Agent.
     *
     * Existe porque magis sirve el VOD detrás de `Content-Auth` y `Content-License`, y libVLC solo
     * expone `:http-referrer` y `:http-user-agent`: no hay forma de mandarle un header cualquiera.
     * Estos viajan por el proxy local, que sí puede ponerlos en la petición al origen.
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Arrancar por SOFTWARE en vez de por hardware.
     *
     * Existe por los HEVC de magis: en device el decodificador por hardware falla a menudo al
     * crearse con este contenido y libVLC responde descartando las pistas enteras (`pistas=v0/a0`),
     * o sea ni imagen ni sonido, mientras el demuxer corre a vaciar el archivo. Por software esos
     * mismos títulos reproducen bien. Sabiéndolo de antemano se evita el intento fallido y los
     * ~10 s de pantalla negra que tardaba el rescate automático en actuar.
     */
    val preferirSoftware: Boolean = false,
) {
    /** Todos los headers del origen en un solo mapa, para quien pueda mandarlos completos. */
    val allHeaders: Map<String, String>
        get() = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            putAll(extraHeaders)
        }
}

object PlayerSource {
    /**
     * Prefijo de un canal en vivo (Tarea 14): `episodeId = "live:<code>"`, el mismo `code` que
     * [com.arkiv.player.ui.live.LiveController.abrir] recibe. Vive acá (y no repetido como string
     * literal en cada callsite) porque tanto quien arma la ruta de navegación
     * (ArkivRoot/ArkivTvRoot) como quien la interpreta (PlayerViewModel) tienen que coincidir.
     */
    const val LIVE_PREFIX = "live:"

    /**
     * ¿[episodeId] es un canal en vivo, de cualquier fuente? El de Magis (`live:`, ver [LIVE_PREFIX])
     * o el de Caracol ([DituVivo]).
     *
     * `PlayerScreen` cuelga de acá lo que es de cualquier directo: sin barra de avance ni seek, sin
     * posición que guardar, sin "siguiente capítulo" al terminar. Lo que es solo del vivo de Magis
     * (zapeo, cajón y ficha de canales, reapertura por cortes) sigue preguntando por [SourceKind.LIVE].
     */
    fun esCanalEnVivo(episodeId: String): Boolean =
        kindFor(episodeId) == SourceKind.LIVE || DituVivo.esVivo(episodeId)

    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        // Caracol (Ditu). Los ids con este prefijo los arman `DituEntities`, al guardar un título de
        // Caracol en la biblioteca, y `DituVivo`, para un canal en vivo: sus `PREFIX` tienen que
        // empezar con este.
        episodeId.startsWith("ditu:") -> SourceKind.DITU
        episodeId.startsWith(LIVE_PREFIX) -> SourceKind.LIVE
        // UNKNOWN covers ids from sources removed from this branch (torrent, archive.org, web): the
        // player answers them with a "no longer available" error, see PlayerViewModel.loadUnknownSource.
        else -> SourceKind.UNKNOWN
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)
