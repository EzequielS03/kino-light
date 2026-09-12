package com.arkiv.player.playback

/** Contenedor de video y el MIME con el que hay que anunciarlo. */
enum class Contenedor(val mime: String) {
    MP4("video/mp4"),
    MATROSKA("video/x-matroska"),
    WEBM("video/webm"),
    MPEGTS("video/mp2t"),
    AVI("video/x-msvideo"),
    MPEGPS("video/mpeg"),
    ASF("video/x-ms-asf"),
    OGG("video/ogg"),
}

/**
 * What container a file is, by looking at its BYTES before its name.
 *
 * It's the rule magis already applied -- the container comes from the file, not from a guess --
 * carried over to the cast side, which is where guessing costs something. There used to be three
 * different MIME tables deciding by extension and contradicting each other: the unknown case was
 * matroska in `TorrentStreamServer`, mp4 in `LocalFileServer`, and mp4 again in
 * `CastRequestBuilder`. That string is exactly what the Chromecast receiver and the DLNA renderer
 * use to decide whether to open the stream; libVLC used to ignore it and probe instead, they
 * don't. An `.avi` or `.ts` from a torrent was announced to the TV as Matroska, and an `.mkv`
 * downloaded from the NUC -- which `LocalFilePaths.fileNameFor` saves as `.mp4` because the source
 * URL is a web page with no extension -- was announced as mp4.
 *
 * Pure and Android-free so the edges can be pinned by test: here the failure mode is silent (the
 * TV rejects the item, or worse, opens it and stays mute) and leaves no trace in any log of ours.
 */
object ContenedorDeVideo {

    /**
     * Cuántos bytes hay que leer del principio del archivo para que la firma sea concluyente.
     *
     * El piso lo pone el MPEG-TS: su firma es el sincronismo REPETIDO cada 188 bytes, y en un m2ts
     * el primer paquete arranca en el byte 4 (los 4 bytes de timestamp que mete Blu-ray). O sea
     * 4 + 2×188 + 1 = 381 como mínimo absoluto. 512 es el siguiente bloque redondo y se lee de una.
     */
    const val BYTES_DE_FIRMA = 512

    /** Tamaño de un paquete de transporte MPEG-TS. */
    private const val PAQUETE_TS = 188

    /** Ídem en un m2ts, donde cada paquete lleva 4 bytes de timestamp por delante. */
    private const val PAQUETE_M2TS = 192

    /** Byte de sincronismo con el que empieza cada paquete de transporte. */
    private const val SYNC_TS = 0x47.toByte()

    /**
     * Cajas con las que puede empezar un MP4/MOV. `ftyp` es lo normal desde MP4; los `.mov` viejos
     * arrancan directo con una caja de contenido.
     */
    private val CAJAS_MP4 = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")

    private val POR_EXTENSION = mapOf(
        "mp4" to Contenedor.MP4, "m4v" to Contenedor.MP4, "mov" to Contenedor.MP4,
        "mkv" to Contenedor.MATROSKA,
        "webm" to Contenedor.WEBM,
        "ts" to Contenedor.MPEGTS, "m2ts" to Contenedor.MPEGTS, "mts" to Contenedor.MPEGTS,
        "avi" to Contenedor.AVI,
        "mpg" to Contenedor.MPEGPS, "mpeg" to Contenedor.MPEGPS,
        "wmv" to Contenedor.ASF, "asf" to Contenedor.ASF,
        "ogv" to Contenedor.OGG, "ogg" to Contenedor.OGG,
    )

    /** El contenedor que declaran los primeros bytes, o null si no se reconoce ninguno. */
    fun porFirma(cabecera: ByteArray): Contenedor? {
        if (cabecera.size < 4) return null
        if (texto(cabecera, 4, 4) in CAJAS_MP4) return Contenedor.MP4
        if (esEbml(cabecera)) return docTypeDeEbml(cabecera)
        if (texto(cabecera, 0, 4) == "RIFF" && texto(cabecera, 8, 4) == "AVI ") return Contenedor.AVI
        if (texto(cabecera, 0, 4) == "OggS") return Contenedor.OGG
        if (empiezaCon(cabecera, 0x00, 0x00, 0x01, 0xBA)) return Contenedor.MPEGPS
        if (empiezaCon(cabecera, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) return Contenedor.ASF
        // El TS va último: es el único que no se resuelve con una comparación de prefijo. Dos
        // variantes, y el PASO cambia con el offset: el TS pelado va de 188 en 188 desde el byte 0,
        // y el m2ts de 192 en 192 desde el byte 4 (188 de paquete + los 4 de timestamp que le mete
        // Blu-ray delante a cada uno). Buscar el m2ts con paso 188 no lo encuentra nunca.
        if (esMpegTs(cabecera, 0, PAQUETE_TS) || esMpegTs(cabecera, 4, PAQUETE_M2TS)) {
            return Contenedor.MPEGTS
        }
        return null
    }

    /**
     * Si [nombreOUrl] tiene pinta de archivo de video, por su extensión.
     *
     * Es LA lista, y existe para que vuelva a haber una sola: había cuatro repartidas por la app
     * (`TorrentEngine`, `SubtitleFilePicker`, `MetadataParser`, `LocalFilePaths`) y ninguna
     * coincidía con otra. Cada diferencia era un archivo invisible — un `.ts` en archive.org que no
     * salía como episodio, un `.m2ts` de torrent guardado con la extensión cambiada.
     */
    fun esVideo(nombreOUrl: String): Boolean = porExtension(nombreOUrl) != null

    /** El contenedor que sugiere la extensión de [nombreOUrl], o null si no dice nada útil. */
    fun porExtension(nombreOUrl: String): Contenedor? = POR_EXTENSION[extensionCruda(nombreOUrl)]

    /**
     * La extensión de video de [nombreOUrl] ya normalizada (minúsculas, sin querystring ni ruta),
     * o null si no es una que sepamos reproducir. Para quien tiene que ponerle nombre a un archivo.
     */
    fun extensionDeVideo(nombreOUrl: String): String? =
        extensionCruda(nombreOUrl).takeIf { it in POR_EXTENSION }

    /** El último tramo después del punto, sin querystring, fragmento ni directorios. "" si no hay. */
    private fun extensionCruda(nombreOUrl: String): String =
        nombreOUrl.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()

    /**
     * La decisión: manda la firma, el nombre es el respaldo, y sin ninguno de los dos se cae a
     * [Contenedor.MP4] — mentir ahí es estrictamente mejor que no mandar tipo, porque un receptor
     * sin `Content-Type` rechaza el ítem sin siquiera intentarlo.
     */
    fun de(cabecera: ByteArray, nombreOUrl: String): Contenedor =
        porFirma(cabecera) ?: porExtension(nombreOUrl) ?: Contenedor.MP4

    /** El MIME de [nombreOUrl] cuando no hay bytes a mano (una URL remota, por ejemplo). */
    fun mimePorNombre(nombreOUrl: String): String =
        (porExtension(nombreOUrl) ?: Contenedor.MP4).mime

    /**
     * [de] leyendo la cabecera de [archivo]. Cualquier problema de lectura —el archivo todavía no
     * existe, o un torrent que aún no bajó la cabeza— cae al nombre en silencio: acá no hay nada
     * que informar, es el estado normal de un archivo que se está bajando.
     */
    fun deArchivo(archivo: java.io.File): Contenedor {
        val cabecera = runCatching {
            java.io.FileInputStream(archivo).use { entrada ->
                val buf = ByteArray(BYTES_DE_FIRMA)
                val leidos = entrada.readNBytes(buf, 0, BYTES_DE_FIRMA)
                if (leidos == BYTES_DE_FIRMA) buf else buf.copyOf(leidos)
            }
        }.getOrDefault(ByteArray(0))
        return de(cabecera, archivo.name)
    }

    private fun esEbml(b: ByteArray) = empiezaCon(b, 0x1A, 0x45, 0xDF, 0xA3)

    /**
     * Matroska y WebM comparten la magia EBML; los separa el DocType, que va en la cabecera del
     * documento (los primeros bytes). Sin DocType legible se devuelve MATROSKA, que es el caso
     * común y además el superconjunto: un receptor que abre matroska abre el webm de adentro.
     */
    private fun docTypeDeEbml(b: ByteArray): Contenedor {
        val cabeza = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1)
        return if (cabeza.contains("webm")) Contenedor.WEBM else Contenedor.MATROSKA
    }

    /**
     * Sincronismo de TS en [offset] y en los dos paquetes siguientes, con [paso] entre uno y otro.
     *
     * Se exigen TRES y no uno: `0x47` es un byte cualquiera —aparece en casi cualquier archivo— y
     * lo que identifica a un TS es que se repita exactamente cada paquete.
     */
    private fun esMpegTs(b: ByteArray, offset: Int, paso: Int): Boolean {
        val ultimo = offset + 2 * paso
        if (b.size <= ultimo) return false
        return b[offset] == SYNC_TS && b[offset + paso] == SYNC_TS && b[ultimo] == SYNC_TS
    }

    private fun empiezaCon(b: ByteArray, vararg esperados: Int): Boolean {
        if (b.size < esperados.size) return false
        return esperados.withIndex().all { (i, e) -> b[i] == e.toByte() }
    }

    /** [largo] bytes desde [desde] como ASCII, o "" si no están todos. */
    private fun texto(b: ByteArray, desde: Int, largo: Int): String =
        if (b.size < desde + largo) "" else String(b, desde, largo, Charsets.US_ASCII)
}
