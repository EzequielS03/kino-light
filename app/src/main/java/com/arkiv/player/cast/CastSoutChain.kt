package com.arkiv.player.cast

/**
 * La cadena de salida (`sout`) que se le pasa a libVLC para mandarle al Chromecast algo que sí pueda
 * decodificar.
 *
 * La idea de fondo, y el motivo de que esto sea barato: **el video no se toca**. El H.264 de los
 * releases ya lo reproduce el receptor sin problema —por eso se veía la imagen—, así que solo se
 * re-encodea el audio (AC-3, E-AC-3, DTS…) a AAC estéreo y se remuxa todo a MPEG-TS, que Cast sí
 * soporta. Re-encodear audio son unos pocos puntos de CPU; re-encodear video funde la batería.
 *
 * Es exactamente lo que hacen Jellyfin y el módulo de Chromecast de VLC: pasar el video como está y
 * convertir únicamente lo que el receptor no entiende.
 *
 * Va en un objeto puro y con tests porque libVLC parsea estos strings en runtime: un typo acá no
 * rompe la compilación, solo hace que no salga nada por la TV y sin un error que lo explique.
 */
object CastSoutChain {

    /** Lo que se le declara al receptor. Es el mismo que manda el módulo de Chromecast de VLC. */
    const val MIME = "video/x-matroska"

    private const val PATH = "cast.mkv"

    /**
     * El muxer, copiado literal del módulo de Chromecast de libVLC (su `DEFAULT_MUXER`), que es una
     * implementación probada contra Chromecasts reales.
     *
     * `reset-ts` es lo que arregla el arranque con [mediaOptions] `startAtMs` > 0: con el muxer TS,
     * empezar en el minuto 10 hacía que el muxer viera TODOS los buffers como `late buffer` (~8 s de
     * desfase, medido en el receptor real) y el stream salía inservible. Esto rebasa los timestamps
     * a cero. `live=1` le dice a avformat que no espere a poder escribir un índice.
     */
    private const val MUXER = "avformat{mux=matroska,options={live=1},reset-ts}"

    /** Estéreo a propósito: el receptor se atraganta con AAC multicanal (ver [CastAudioSupport]). */
    private const val AUDIO = "acodec=mp4a,ab=192,channels=2"

    /**
     * Opciones para el Media de libVLC que hace de transcodificador.
     *
     * @param port puerto donde queda escuchando el HTTP que consumirá la TV.
     * @param startAtMs desde dónde arrancar. Como el stream sale "en vivo" y no se puede buscar,
     *   moverse por el video significa volver a arrancar todo esto en otro punto.
     * @param audioTrackIndex qué pista de audio mandar. Sin esto, un release con latino + inglés
     *   sonaría en la TV en un idioma distinto al que elegiste en el celu.
     */
    fun mediaOptions(port: Int, startAtMs: Long, audioTrackIndex: Int?): List<String> = buildList {
        // El `mime=` NO es decorativo: el access_output de VLC manda "application/octet-stream" por
        // defecto, y con eso el Chromecast bufferea unos segundos y se va a idle SIN disparar ningún
        // error. El receptor decide por la cabecera del servidor, no por el MIME que le declaramos a
        // media3. Verificado contra el receptor real.
        add(":sout=#transcode{$AUDIO}:standard{mux=$MUXER,access=http{mime=$MIME},dst=:$port/$PATH}")
        // Solo las pistas elegidas: con esto en "todas", un MKV con tres audios transcodificaría los
        // tres y pagaríamos el triple de trabajo para nada.
        add(":no-sout-all")
        // libVLC lo quiere en SEGUNDOS. Pasarle milisegundos manda el arranque a las 11 horas.
        if (startAtMs > 0) add(":start-time=${startAtMs / 1000}")
        if (audioTrackIndex != null) add(":audio-track=$audioTrackIndex")
    }

    /** La URL que se le pasa al receptor. Apunta al PROPIO celu: la TV tiene que poder alcanzarlo. */
    fun streamUrl(lanIp: String, port: Int): String = "http://$lanIp:$port/$PATH"
}
