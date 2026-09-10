package com.arkiv.player.data.ditu

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Los errores de Caracol dichos para la persona, no para quien programa.
 *
 * Antes la pantalla mostraba el error tal cual: en la búsqueda, el mensaje de la excepción después
 * de "Caracol no respondió:"; en el reproductor, el nombre del código de ExoPlayer
 * (`errorCodeName`). Eso sirve para diagnosticar —y por eso sigue yendo al log donde se produce—,
 * pero a la persona no le dice qué pasó.
 *
 * Es el único lugar donde se traduce: la línea de la búsqueda ([enLaBusqueda]), lo que no se pudo
 * abrir ([alAbrir]), lo que se cortó reproduciendo ([alReproducir]) y lo que no cargó en la sección
 * de Caracol ([alCargarElCatalogo], [alCargarLosCanales]). Lo que no se reconoce cae en
 * un genérico, nunca en el texto crudo. La excepción son los motivos de [DituEntitlement]: ya vienen
 * escritos para la persona y pasan igual.
 *
 * Solo Caracol: los errores de Magis se muestran como siempre.
 */
internal object FalloDeCaracol {

    /** Qué pasó, en lo que le importa a la persona. */
    sealed interface Motivo {
        /** Caracol dijo por qué no deja ver algo: el texto de [DituEntitlement.bloqueo]. */
        data class Bloqueo(val motivo: String) : Motivo
        /** El nombre de Caracol no resuelve, que es lo que pasa sin internet. */
        object SinConexion : Motivo
        /** La conexión no se armó (rechazada, sin ruta): puede pasar con internet andando. */
        object SinRespuesta : Motivo
        object Demora : Motivo
        object ServidorFallando : Motivo
        object Desconocido : Motivo
    }

    /**
     * Qué pasó según [error] y toda su cadena de causas: `DituFuente` envuelve lo de `DituCliente`
     * en una `GatewayException`, y `DituCliente` envuelve lo de OkHttp en una [DituException], así
     * que lo que dice qué pasó suele venir un par de causas más abajo.
     *
     * Primero manda el tipo de las excepciones; si ninguno dice nada, su texto y [texto], que es lo
     * único que hay cuando la excepción no llegó.
     */
    fun clasificar(error: Throwable?, texto: String? = null): Motivo {
        val cadena = generateSequence(error) { it.cause }.take(MAX_CAUSAS).toList()
        cadena.firstNotNullOfOrNull { (it as? DituException)?.bloqueo }?.let { return Motivo.Bloqueo(it) }
        cadena.firstNotNullOfOrNull { porElTipo(it) }?.let { return it }
        return (cadena.mapNotNull { it.message } + listOfNotNull(texto))
            .firstNotNullOfOrNull { porElTexto(it) }
            ?: Motivo.Desconocido
    }

    /** La línea de la búsqueda cuando Caracol no trajo resultados por un error. */
    fun enLaBusqueda(error: Throwable?, texto: String?): String =
        frase(clasificar(error, texto), generico = "Caracol no respondió")

    /** Lo que dice el reproductor cuando no se pudo abrir lo de Caracol (falló resolverlo). */
    fun alAbrir(error: Throwable?): String =
        frase(clasificar(error), generico = GENERICO_AL_REPRODUCIR)

    /** Lo que dice la sección de Caracol cuando no cargó su catálogo: ahí no se reproduce nada. */
    fun alCargarElCatalogo(error: Throwable?): String =
        frase(clasificar(error), generico = "No se pudo cargar el catálogo de Caracol")

    /** Lo que dice la pestaña "En vivo" de la sección de Caracol cuando no cargaron los canales. */
    fun alCargarLosCanales(error: Throwable?): String =
        frase(clasificar(error), generico = "No se pudieron cargar los canales de Caracol")

    /**
     * Lo que dice el reproductor cuando `DituExoPlayer` se rindió, por la familia del [codigo] de
     * `PlaybackException`. Las familias van por miles: los `ERROR_CODE_IO_*` son los 2000, los de
     * decodificación (`ERROR_CODE_DECODER_*`, `ERROR_CODE_DECODING_*`) los 4000, los
     * `ERROR_CODE_AUDIO_TRACK_*` los 5000 y los `ERROR_CODE_DRM_*` los 6000.
     */
    fun alReproducir(codigo: Int, esTelevision: Boolean): String = when (codigo) {
        in 2000..2999 -> "Se cortó la conexión con Caracol"
        in 6000..6999 -> "Caracol no autorizó la reproducción"
        in 4000..5999 ->
            if (esTelevision) "El televisor no pudo reproducir este video" else "Este celular no pudo reproducir este video"
        else -> GENERICO_AL_REPRODUCIR
    }

    private fun frase(motivo: Motivo, generico: String): String = when (motivo) {
        // "Caracol: <motivo>" es como lo arma `DituResolve`: el mismo texto que se veía antes.
        is Motivo.Bloqueo -> "Caracol: ${motivo.motivo}"
        Motivo.SinConexion -> "Caracol no respondió: sin conexión a internet"
        Motivo.SinRespuesta -> "Caracol no respondió"
        Motivo.Demora -> "Caracol tardó demasiado en responder"
        Motivo.ServidorFallando -> "Caracol está fallando en este momento"
        Motivo.Desconocido -> generico
    }

    private fun porElTipo(e: Throwable): Motivo? = when {
        e is DituException && e.codigoHttp?.let { it in 500..599 } == true -> Motivo.ServidorFallando
        // Sin internet es que el nombre no resuelve. Una conexión que no se arma (rechazada, sin ruta)
        // puede pasar con internet andando: a la persona no se le puede decir que no tiene internet.
        e is UnknownHostException -> Motivo.SinConexion
        e is ConnectException || e is NoRouteToHostException -> Motivo.SinRespuesta
        e is SocketTimeoutException -> Motivo.Demora
        // Un `InterruptedIOException` a secas cuenta como demora solo si su mensaje lo dice.
        e is InterruptedIOException && e.message.orEmpty().contains("timeout", ignoreCase = true) -> Motivo.Demora
        else -> null
    }

    private fun porElTexto(t: String): Motivo? = when {
        t.contains("Unable to resolve host", ignoreCase = true) -> Motivo.SinConexion
        t.contains("Failed to connect", ignoreCase = true) -> Motivo.SinRespuesta
        t.contains("timeout", ignoreCase = true) || t.contains("timed out", ignoreCase = true) -> Motivo.Demora
        // "Caracol respondió 503 en <ruta>": el mensaje que arma `DituCliente` con un status de error.
        SERVIDOR_FALLANDO.containsMatchIn(t) -> Motivo.ServidorFallando
        else -> null
    }

    private val SERVIDOR_FALLANDO = Regex("""respondió 5\d\d\b""")

    private const val GENERICO_AL_REPRODUCIR = "No se pudo reproducir en Caracol"

    /** Un tope a la cadena de causas, por si alguna vuelve sobre sí misma. */
    private const val MAX_CAUSAS = 8
}
