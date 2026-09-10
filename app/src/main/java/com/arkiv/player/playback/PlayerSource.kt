package com.arkiv.player.playback

import androidx.media3.common.MediaItem

enum class SourceKind { ARCHIVE, MAGIS, NUC, LOCAL, LIVE, DITU }

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
     * Duración real en ms cuando el reproductor NO puede deducirla solo (0 = no se sabe).
     *
     * Existe por el MPEG-TS de magis: libVLC solo saca la duración de un TS sondeando el final del
     * archivo, y eso lo hace únicamente con acceso de lectura rápida (archivo local). Servido por
     * HTTP, `length` se queda en 0 y la barra queda llena/00:00 y sin poder buscar. La sonda de
     * [TsDurationProbe] la calcula aparte y viaja hasta el player por acá.
     */
    val knownDurationMs: Long = 0L,
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
    /**
     * Contenedor tal como lo nombra la FUENTE ("ts", "mp4"…); "" = no se sabe, hay que sondear.
     *
     * Viaja hasta acá porque es lo que se le declara al demuxer al abrir (`:avformat-format`), y la
     * única alternativa —deducirlo de la extensión de la URL— no sirve en magis: esa extensión la
     * arma el gateway colapsando a `.mp4` todo lo que el portal no llame `ts`, porque es la clave
     * del objeto en el CDN y solo existe en dos sabores. Es el mismo camino que usa la app original
     * de magis, que pasa el `format` de su backend tal cual al `iformat` de su ijkplayer. Ver
     * [formatoAvformatDe].
     */
    val contenedorDeLaFuente: String = "",
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

    fun kindFor(episodeId: String): SourceKind = when {
        episodeId.startsWith("magis:") -> SourceKind.MAGIS
        // Caracol (Ditu). Los ids con este prefijo los arman `DituEntities`, al guardar un título de
        // Caracol en la biblioteca, y `DituVivo`, para un canal en vivo: sus `PREFIX` tienen que
        // empezar con este.
        episodeId.startsWith("ditu:") -> SourceKind.DITU
        episodeId.startsWith(LIVE_PREFIX) -> SourceKind.LIVE
        else -> SourceKind.ARCHIVE
    }
}

fun MediaItem.Builder.setPlayerSourceTag(tag: PlayerSourceTag): MediaItem.Builder = setTag(tag)
