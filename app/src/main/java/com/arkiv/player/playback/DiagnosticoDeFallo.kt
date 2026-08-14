package com.arkiv.player.playback

/**
 * La línea que dice QUÉ contenido falló, para acompañar a cada rescate del reproductor.
 *
 * Un rescate dejaba escrito `hardware sin imagen 10234ms → paso a software` y nada más: ni el
 * contenedor, ni el códec, ni el equipo — que es justo lo que hace falta para distinguir un HEVC de
 * magis de un AVI viejo o del decodificador del Fire Stick. La app original de magis sí lo registra
 * (su telemetría `SwitchPlayer` lleva `format`, `vcodec` y `model`, y ante un error de códec vuelca
 * el `getCandidateCodecList()` del equipo), solo que se lo manda a su backend; acá va al log, que es
 * donde lo podemos leer.
 *
 * Pura por una razón concreta: el modo de fallar de un log es silencioso, y una excepción leyendo el
 * códec desde adentro del rescate se comería el rescate entero.
 */
object DiagnosticoDeFallo {

    /** Lo que no se pudo leer se dice, no se omite: un hueco callado se lee como un dato. */
    private const val NO_SE_SABE = "?"

    private fun texto(v: String) = v.ifBlank { NO_SE_SABE }

    /** Un negativo es "no se pudo preguntar" y NO es lo mismo que cero (ver [RachaSinVideo]). */
    private fun cuenta(v: Int) = if (v < 0) NO_SE_SABE else v.toString()

    private fun medida(v: Int) = if (v <= 0) NO_SE_SABE else v.toString()

    @Suppress("LongParameterList")
    fun linea(
        motivo: String,
        contenedor: String?,
        codecVideo: String,
        ancho: Int,
        alto: Int,
        pistasVideo: Int,
        pistasAudio: Int,
        porHardware: Boolean,
        equipo: String,
    ): String = "FALLO motivo=$motivo contenedor=${texto(contenedor.orEmpty())} " +
        "vcodec=${texto(codecVideo)} ${medida(ancho)}x${medida(alto)} " +
        "pistas=v${cuenta(pistasVideo)}/a${cuenta(pistasAudio)} " +
        "decodificador=${if (porHardware) "hardware" else "software"} equipo=${texto(equipo)}"
}
