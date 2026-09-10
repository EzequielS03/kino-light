package com.arkiv.player.ui.player

/**
 * Cuánto espera [DituExoPlayer] la primera imagen antes de arrancar igual.
 *
 * Es un número ELEGIDO, no medido. Tiene que quedar por encima de lo que tarda de verdad la primera
 * imagen: si fuera más corto, el audio arrancaría sin imagen justo en el caso que esta espera vino a
 * arreglar. El KDoc de `exoYaPintoAlgo` en `PlayerScreen` anota 6,5 s medidos en ditu, y el
 * comentario de `setSessionKeepaliveMs` en [DituExoPlayer] cuenta 14 s medidos en `main` con la
 * sesión DRM del canal anterior retenida, que es justo lo que ese ajuste suelta. Si fuera muy largo
 * y algún aparato no pintara nada en pausa, la persona se quedaría ese rato con el spinner y en
 * silencio antes de que arranque igual.
 *
 * 10 s queda por encima de los 6,5 s y le pone techo a esa espera. El caso de 14 s no lo cubre: si
 * volviera a pasar, el audio arrancaría a los 10 s con la pantalla todavía negra, que no es peor que
 * antes, cuando arrancaba de una.
 */
internal const val ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS = 10_000L

/**
 * Cuándo arranca Caracol: con la primera imagen, no antes.
 *
 * [DituExoPlayer] prepara en pausa y le pregunta a esto cuándo darle play. Arranca cuando se pinta
 * la primera imagen ([llegoLaImagen]) o, si no llega, cuando se vence la espera ([vencio]): nunca
 * las dos, y una sola vez. Si en el medio la persona tocó play o pausa ([laPersonaDecidio]), manda
 * ella y esto ya no toca el reproductor: una pausa suya no se confunde con esta espera. Si la app se
 * fue al fondo mientras esperaba ([cancelar]), tampoco: al volver no arranca sola.
 *
 * Va aparte y sin Android para poder probarlo en la JVM, igual que [EstadoDeDitu]. Es uno por
 * reproductor: una recarga arma otro reproductor y, con él, otra espera.
 */
internal class ArranqueConLaPrimeraImagen(
    private val esperaMaximaMs: Long = ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS,
) {
    /** Cuándo quedó preparado en pausa, o `null` si todavía no. */
    private var desdeMs: Long? = null

    /** Ya arrancó, o ya decidió la persona: no queda nada que esperar. */
    private var resuelto = false

    /** Si está preparado en pausa, esperando la primera imagen. */
    val esperando: Boolean get() = desdeMs != null && !resuelto

    /** El reproductor quedó preparado en pausa en [ahoraMs]: empieza la espera. */
    fun empezo(ahoraMs: Long) {
        if (desdeMs == null) desdeMs = ahoraMs
    }

    /** Se pintó la primera imagen. `true` = darle play ahora. */
    fun llegoLaImagen(): Boolean = soltar()

    /** Una lectura del reloj. `true` = se venció la espera sin imagen: darle play ahora, igual. */
    fun vencio(ahoraMs: Long): Boolean {
        val desde = desdeMs ?: return false
        if (ahoraMs - desde < esperaMaximaMs) return false
        return soltar()
    }

    /** La persona tocó play o pausa mientras se esperaba: desde ahí decide ella. */
    fun laPersonaDecidio() {
        resuelto = true
    }

    /** La app se fue al fondo mientras se esperaba: no arranca más sola, ni en el fondo ni al volver. */
    fun cancelar() {
        resuelto = true
    }

    private fun soltar(): Boolean {
        if (!esperando) return false
        resuelto = true
        return true
    }
}
