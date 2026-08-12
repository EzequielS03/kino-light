package com.arkiv.player.data.catalog

import com.arkiv.player.data.MetadataParser
import com.arkiv.player.torrent.PackFileParser
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentFile

/** Una fila mostrable del diálogo de pack: un archivo de video con su etiqueta/sección/orden. */
data class PackFileRow(
    val index: Int,        // torrentFileIndex dentro del torrent
    val label: String,     // "T1 · E2" | "Ep 1085" | nombre limpio
    val sizeBytes: Long,
    val quality: String,   // "1080p" | ""
    val section: String,   // "Temporada 1" | ""
    val orderIndex: Int,
    /**
     * La numeración que declaraba el nombre del archivo, tal cual, para guardarla en su propia
     * columna en vez de dejarla solo codificada dentro del [orderIndex] (ver [PackRowBuilder]).
     * En un pack de numeración absoluta (One Piece 1085) va [episode] sin [season].
     */
    val season: Int? = null,
    val episode: Int? = null,
)

object PackRowBuilder {
    /**
     * El `orderIndex` sirve para ORDENAR y por eso codifica temporada*1000 + episodio; la
     * numeración de verdad viaja aparte, en `season`/`episode`. Antes solo existía la codificada, y
     * leerla de vuelta obligaba a adivinar: una temporada 0 daba un número por debajo de 1000
     * (indistinguible de un correlativo de archive.org) y un pack absoluto daba uno por encima
     * (que se leía como si fuera "T1 · E85").
     */
    fun build(files: List<TorrentFile>): List<PackFileRow> = files.mapIndexed { pos, f ->
        val info = PackFileParser.parse(f.name)
        when {
            info.season != null && info.episode != null -> PackFileRow(
                f.index, "T${info.season} · E${info.episode}", f.sizeBytes, QualityLabel.extract(f.name),
                "Temporada ${info.season}", info.season * 1000 + info.episode, info.season, info.episode,
            )
            info.absolute != null -> PackFileRow(
                f.index, "Ep ${info.absolute}", f.sizeBytes, QualityLabel.extract(f.name),
                "", info.absolute, null, info.absolute,
            )
            else -> PackFileRow(
                f.index, MetadataParser.cleanName(f.name), f.sizeBytes, QualityLabel.extract(f.name),
                "", pos, null, null,
            )
        }
    }.sortedBy { it.orderIndex }
}

/** Resuelve la metadata de una fuente pack y arma sus filas mostrables. Null si no se pudo (sin
 *  seeds/timeout, o sin video). El resolver de magnet puede tardar (hasta ~45s buscando peers). */
class PackResolver(
    private val torrentSearchApi: TorrentSearchApi,
    private val torrentEngine: TorrentEngine,
) {
    data class PackContents(val infoHashHex: String, val infoBytes: ByteArray, val rows: List<PackFileRow>)

    suspend fun resolve(result: TorrentResult): PackContents? {
        val source = torrentSearchApi.resolveSource(result) ?: return null
        val meta = when (source) {
            is TorrentSource.Magnet -> torrentEngine.resolveMagnet(source.uri)
            is TorrentSource.TorrentFile -> torrentEngine.resolveTorrent(source.bytes)
        } ?: return null
        val files = torrentEngine.videoFiles(meta)
        if (files.isEmpty()) return null
        return PackContents(meta.infoHashHex, meta.infoBytes, PackRowBuilder.build(files))
    }
}
