package com.arkiv.player.miniaturas

/**
 * Cuándo un frame NO sirve para guardar.
 *
 * Existen porque la captura sobrescribe: un frame malo no se suma al bueno, lo reemplaza. Sin
 * estas dos guardas, pausar en un fundido te deja la tarjeta en negro y encima perdés el frame
 * bueno que ya tenías.
 *
 * Trabajan sobre un IntArray de píxeles ARGB (lo que devuelve `Bitmap.getPixels`) y no sobre un
 * Bitmap para poder testearlas sin Robolectric.
 */
object GuardasDeFrame {

    /** Los primeros 60 s son logos de distribuidora y pantallas negras. Punto de partida ajustable. */
    const val PISO_DE_POSICION_MS = 60_000L

    /** Por debajo de esto (sobre 255) el frame se considera negro y no se guarda. */
    const val LUMINANCIA_MINIMA = 10

    fun posicionSirve(positionMs: Long): Boolean = positionMs >= PISO_DE_POSICION_MS

    /** Luminancia media 0..255, con los pesos enteros de siempre (77/150/29 sobre 256). */
    fun luminanciaMedia(pixeles: IntArray): Int {
        if (pixeles.isEmpty()) return 0
        var suma = 0L
        for (p in pixeles) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            suma += ((r * 77 + g * 150 + b * 29) shr 8).toLong()
        }
        return (suma / pixeles.size).toInt()
    }

    fun noEsCasiNegro(pixeles: IntArray): Boolean =
        pixeles.isNotEmpty() && luminanciaMedia(pixeles) >= LUMINANCIA_MINIMA
}
