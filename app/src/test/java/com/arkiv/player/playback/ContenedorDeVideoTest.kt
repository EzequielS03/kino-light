package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El contenedor sale de los BYTES, no del nombre del archivo.
 *
 * Es la misma regla que ya regía para magis, aplicada al lado del cast:
 * hoy hay tres tablas de MIME distintas que adivinan por extensión y no coinciden entre sí
 * —`TorrentStreamServer` manda todo lo desconocido a matroska, `LocalFileServer` a mp4 y
 * `CastRequestBuilder` también a mp4—, y ese string es exactamente lo que el receptor de Chromecast
 * y el renderer DLNA usan para decidir si abren el stream. libVLC sondea y sobrevive; ellos no.
 */
class ContenedorDeVideoTest {

    /** Cabecera de [n] bytes con [bytes] escritos desde [offset]. El resto en ceros. */
    private fun cabecera(n: Int, offset: Int, bytes: ByteArray): ByteArray =
        ByteArray(n).also { bytes.copyInto(it, offset) }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    // ---- Firma ----

    @Test fun `mp4 se reconoce por el ftyp`() {
        val h = cabecera(64, 4, ascii("ftypisom"))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porFirma(h))
    }

    @Test fun `mov viejo se reconoce por el moov`() {
        val h = cabecera(64, 4, ascii("moov"))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porFirma(h))
    }

    /** Matroska y WebM comparten la magia EBML: los distingue el DocType, no los cuatro bytes. */
    @Test fun `matroska y webm comparten magia y los separa el DocType`() {
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val mkv = cabecera(64, 0, ebml).also { ascii("matroska").copyInto(it, 24) }
        val webm = cabecera(64, 0, ebml).also { ascii("webm").copyInto(it, 24) }
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.porFirma(mkv))
        assertEquals(Contenedor.WEBM, ContenedorDeVideo.porFirma(webm))
    }

    /** Sin DocType a la vista se cae a matroska, que es el caso común y el superconjunto. */
    @Test fun `ebml sin DocType legible cae a matroska`() {
        val h = cabecera(64, 0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.porFirma(h))
    }

    /**
     * Un solo 0x47 no alcanza: es un byte tan común que aparece en cualquier archivo. Lo que
     * identifica a un TS es el sincronismo REPETIDO cada 188 bytes.
     */
    @Test fun `mpegts exige el sincronismo repetido cada 188 bytes`() {
        val ts = ByteArray(600).also {
            it[0] = 0x47; it[188] = 0x47; it[376] = 0x47
        }
        assertEquals(Contenedor.MPEGTS, ContenedorDeVideo.porFirma(ts))
    }

    @Test fun `un 0x47 suelto no es mpegts`() {
        val h = ByteArray(600).also { it[0] = 0x47 }
        assertNull(ContenedorDeVideo.porFirma(h))
    }

    /** El m2ts mete 4 bytes de timestamp delante de cada paquete: el sincronismo arranca en 4. */
    @Test fun `m2ts se reconoce con el sincronismo corrido cuatro bytes`() {
        val m2ts = ByteArray(700).also {
            it[4] = 0x47; it[196] = 0x47; it[388] = 0x47
        }
        assertEquals(Contenedor.MPEGTS, ContenedorDeVideo.porFirma(m2ts))
    }

    @Test fun `avi se reconoce por RIFF mas AVI`() {
        val h = cabecera(64, 0, ascii("RIFF")).also { ascii("AVI ").copyInto(it, 8) }
        assertEquals(Contenedor.AVI, ContenedorDeVideo.porFirma(h))
    }

    /** Un WAV también empieza con RIFF: sin el "AVI " no es un AVI. */
    @Test fun `RIFF sin AVI no es avi`() {
        val h = cabecera(64, 0, ascii("RIFF")).also { ascii("WAVE").copyInto(it, 8) }
        assertNull(ContenedorDeVideo.porFirma(h))
    }

    @Test fun `mpeg program stream se reconoce por el pack header`() {
        val h = cabecera(64, 0, byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte()))
        assertEquals(Contenedor.MPEGPS, ContenedorDeVideo.porFirma(h))
    }

    @Test fun `asf wmv se reconoce por su GUID`() {
        val guid = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        )
        assertEquals(Contenedor.ASF, ContenedorDeVideo.porFirma(cabecera(64, 0, guid)))
    }

    @Test fun `ogg se reconoce por OggS`() {
        assertEquals(Contenedor.OGG, ContenedorDeVideo.porFirma(cabecera(64, 0, ascii("OggS"))))
    }

    @Test fun `una cabecera vacia o corta no inventa contenedor`() {
        assertNull(ContenedorDeVideo.porFirma(ByteArray(0)))
        assertNull(ContenedorDeVideo.porFirma(ByteArray(3)))
    }

    @Test fun `bytes que no son de ningun contenedor conocido`() {
        assertNull(ContenedorDeVideo.porFirma(ByteArray(600) { 0x5A }))
    }

    // ---- Extensión (respaldo) ----

    @Test fun `la extension cubre los contenedores conocidos`() {
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porExtension("peli.mp4"))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porExtension("peli.m4v"))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porExtension("peli.MOV"))
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.porExtension("peli.mkv"))
        assertEquals(Contenedor.WEBM, ContenedorDeVideo.porExtension("peli.webm"))
        assertEquals(Contenedor.MPEGTS, ContenedorDeVideo.porExtension("peli.ts"))
        assertEquals(Contenedor.MPEGTS, ContenedorDeVideo.porExtension("peli.m2ts"))
        assertEquals(Contenedor.AVI, ContenedorDeVideo.porExtension("peli.avi"))
        assertEquals(Contenedor.MPEGPS, ContenedorDeVideo.porExtension("peli.mpg"))
        assertEquals(Contenedor.ASF, ContenedorDeVideo.porExtension("peli.wmv"))
        assertEquals(Contenedor.OGG, ContenedorDeVideo.porExtension("peli.ogv"))
    }

    @Test fun `la extension ignora el querystring y el fragmento`() {
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.porExtension("http://x/y/z.mkv?t=1&u=2"))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.porExtension("http://x/y/z.mp4#frag"))
    }

    @Test fun `sin extension conocida no adivina`() {
        assertNull(ContenedorDeVideo.porExtension("http://cdn/stream"))
        assertNull(ContenedorDeVideo.porExtension("archivo.txt"))
        assertNull(ContenedorDeVideo.porExtension(""))
    }

    // ---- La decisión combinada ----

    /**
     * El caso que motiva todo esto: `LocalFilePaths.fileNameFor` guarda como `.mp4` todo lo que baja
     * de la NUC (yt-dlp produce mkv seguido y la URL de origen es una página web, sin extensión de
     * video). Los bytes dicen la verdad y el nombre miente: manda la firma.
     */
    @Test fun `la firma le gana al nombre cuando se contradicen`() {
        val mkv = cabecera(64, 0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.de(mkv, "capitulo-3.mp4"))
    }

    @Test fun `sin firma reconocible se usa el nombre`() {
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.de(ByteArray(600), "capitulo-3.mkv"))
    }

    /**
     * Sin firma Y sin extensión no se inventa nada: mp4 es el MIME que más receptores aceptan, y
     * mentir ahí es estrictamente mejor que no mandar nada (el receptor rechaza el ítem sin tipo).
     */
    @Test fun `sin firma ni extension cae a mp4`() {
        assertEquals(Contenedor.MP4, ContenedorDeVideo.de(ByteArray(600), "cosa"))
    }

    @Test fun `los MIME son los que esperan Chromecast y DLNA`() {
        assertEquals("video/mp4", Contenedor.MP4.mime)
        assertEquals("video/x-matroska", Contenedor.MATROSKA.mime)
        assertEquals("video/webm", Contenedor.WEBM.mime)
        assertEquals("video/mp2t", Contenedor.MPEGTS.mime)
        assertEquals("video/x-msvideo", Contenedor.AVI.mime)
        assertEquals("video/mpeg", Contenedor.MPEGPS.mime)
        assertEquals("video/x-ms-asf", Contenedor.ASF.mime)
        assertEquals("video/ogg", Contenedor.OGG.mime)
    }

    /**
     * Cuántos bytes hay que leer del archivo para que la firma sea concluyente. El piso lo pone el
     * m2ts: 4 (offset) + 2×192 (paso) + 1 = 389. Leer de menos deja al m2ts sin reconocer.
     */
    @Test fun `el tamano de cabecera alcanza para el sincronismo del m2ts`() {
        assert(ContenedorDeVideo.BYTES_DE_FIRMA >= 389)
    }

    // ---- "¿Esto es un video?" (la lista única) ----

    /**
     * Había CUATRO listas de extensiones de video en la app y ninguna coincidía con otra:
     * `TorrentEngine` sin mpg/wmv/ogv, `MetadataParser` sin ts/m2ts, `LocalFilePaths` sin m2ts y
     * `SubtitleFilePicker` con su propia copia. Cada hueco es un archivo que la app no ve: un `.ts`
     * en un ítem de archive.org no aparecía como episodio, y un `.m2ts` bajado de un torrent se
     * guardaba con el nombre cambiado a `.mp4`.
     */
    @Test fun `esVideo reconoce todos los contenedores que sabemos reproducir`() {
        listOf(
            "a.mkv", "a.mp4", "a.m4v", "a.mov", "a.webm", "a.ts", "a.m2ts", "a.mts",
            "a.avi", "a.mpg", "a.mpeg", "a.wmv", "a.asf", "a.ogv", "a.ogg",
        ).forEach { assert(ContenedorDeVideo.esVideo(it)) { "debería ser video: $it" } }
    }

    @Test fun `esVideo dice que no a lo que no es video`() {
        listOf("info.txt", "sub.srt", "cover.jpg", "peli", "peli.nfo", "")
            .forEach { assert(!ContenedorDeVideo.esVideo(it)) { "NO debería ser video: $it" } }
    }

    /** Un nombre con puntos en el título no confunde: cuenta el último tramo. */
    @Test fun `esVideo mira solo la ultima extension`() {
        assert(ContenedorDeVideo.esVideo("Serie.S01E02.1080p.WEB-DL.mkv"))
        assert(!ContenedorDeVideo.esVideo("Serie.S01E02.1080p.mkv.srt"))
    }

    /**
     * La extensión NORMALIZADA, para quien tiene que ponerle nombre a un archivo.
     *
     * `LocalFilePaths.fileNameFor` la sacaba con un `substringAfterLast('.')` a secas sobre lo que
     * le pasaran, y a la descarga de la NUC le pasan una URL de página: de `https://sitio.com/peli`
     * ese corte devuelve `"com/peli"`. No rompía —no está en ninguna lista, así que caía a mp4—
     * pero tampoco podía acertar nunca.
     */
    @Test fun `la extension normalizada sirve para nombrar el archivo`() {
        assertEquals("mkv", ContenedorDeVideo.extensionDeVideo("https://sitio.com/x/y.mkv?t=1"))
        assertEquals("m2ts", ContenedorDeVideo.extensionDeVideo("BluRay/00001.m2ts"))
        assertEquals("mp4", ContenedorDeVideo.extensionDeVideo("PELI.MP4"))
        assertNull(ContenedorDeVideo.extensionDeVideo("https://sitio.com/pelicula"))
        assertNull(ContenedorDeVideo.extensionDeVideo("https://sitio.com/peli.html"))
    }

    // ---- Desde el disco ----

    private fun archivoTemporal(nombre: String, contenido: ByteArray): java.io.File =
        java.io.File.createTempFile("cont", "-$nombre").apply {
            deleteOnExit()
            writeBytes(contenido)
        }

    /**
     * El caso real de la NUC: yt-dlp produce un mkv y `LocalFilePaths.fileNameFor` lo guarda como
     * `.mp4` porque la URL de origen es una página web, sin extensión de video. Los bytes mandan.
     */
    @Test fun `un mkv guardado con nombre mp4 se reconoce por sus bytes`() {
        val mkv = ByteArray(600).also {
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(it)
            ascii("matroska").copyInto(it, 24)
        }
        val f = archivoTemporal("capitulo.mp4", mkv)
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.deArchivo(f))
    }

    /** Un torrent recién arrancado puede no tener todavía la cabeza en disco: manda el nombre. */
    @Test fun `un archivo vacio se resuelve por el nombre`() {
        val f = archivoTemporal("capitulo.mkv", ByteArray(0))
        assertEquals(Contenedor.MATROSKA, ContenedorDeVideo.deArchivo(f))
    }

    @Test fun `un archivo que no existe se resuelve por el nombre`() {
        val f = java.io.File("/no/existe/peli.avi")
        assertEquals(Contenedor.AVI, ContenedorDeVideo.deArchivo(f))
    }

    @Test fun `sin bytes ni extension util cae a mp4`() {
        val f = archivoTemporal("cosa", ByteArray(0))
        assertEquals(Contenedor.MP4, ContenedorDeVideo.deArchivo(f))
    }

    /** Un archivo más corto que la firma no debe reventar ni inventar: se cae al nombre. */
    @Test fun `un archivo mas corto que la firma no revienta`() {
        val f = archivoTemporal("trozo.ts", ByteArray(10) { 0x47 })
        assertEquals(Contenedor.MPEGTS, ContenedorDeVideo.deArchivo(f))
    }
}
