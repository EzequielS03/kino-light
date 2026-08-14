package com.arkiv.player.ui.entrada

/**
 * Da forma al código de licencia mientras se escribe: `TUXY-Q7EV-HHKF`.
 *
 * Existe porque escribirlo a mano es donde más se falla, y en la TV es directamente un suplicio:
 * catorce caracteres con el D-pad, contando los dos guiones, que además hay que acordarse de poner.
 *
 * El alfabeto NO es "letras y números". El gateway acepta exactamente estos 31 caracteres
 * (`_ALFABETO` en `identidad/sesion.py`):
 *
 * ```
 * ABCDEFGHJKMNPQRSTUVWXYZ23456789
 * ```
 *
 * Faltan `I`, `L`, `O`, `0` y `1` a propósito: son los que se confunden al leer un código escrito.
 * Por eso acá no alcanza con "pasar a mayúsculas y meter guiones" — hay que **no dejar entrar** lo
 * que el gateway va a rechazar después, cuando la persona ya se cansó de tipear.
 *
 * Y por eso mismo estas confusiones se CORRIGEN en vez de rechazarse: si alguien lee una `O` en un
 * papel y la escribe, lo que había impreso solo pudo haber sido un `0`… que tampoco existe. La
 * salida honesta es mapear cada ambiguo a su pareja que sí está en el alfabeto, que es justo el
 * motivo por el que el alfabeto los excluyó. Ver [CONFUSIONES].
 */
object MascaraDeLicencia {

    /** Los caracteres que el gateway acepta. Cualquier otro no puede ser parte de una licencia. */
    const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Cuántos caracteres útiles tiene un código, sin contar los guiones. */
    const val UTILES = 12

    /** El código completo, ya con guiones. */
    const val LARGO_VISIBLE = 14

    /**
     * Lo que la gente escribe cuando lee un código impreso, y en qué se traduce.
     *
     * No es adivinar: el alfabeto sacó estos caracteres PORQUE se confunden con los que dejó. Si
     * llega una `O` es que quisieron poner algo que en el alfabeto no existe, así que la única
     * lectura posible es su pareja. Rechazar la tecla dejaría a la persona apretando algo que no
     * responde, sin entender por qué.
     */
    val CONFUSIONES = mapOf(
        'O' to 'Q', '0' to 'Q',
        'I' to 'J', '1' to 'J', 'L' to 'J',
    )

    /**
     * Deja [crudo] como debe verse en el campo: mayúsculas, sin nada fuera del alfabeto, con los
     * guiones puestos y cortado en el largo de un código.
     *
     * Es idempotente a propósito — se le puede pasar lo que ya está en el campo, con guiones y
     * todo, y devuelve lo mismo. Así el campo se puede formatear en cada tecla sin ir acumulando
     * guiones ni perder lo escrito.
     */
    fun formatear(crudo: String): String {
        val utiles = buildString {
            for (c in crudo.uppercase()) {
                if (length >= UTILES) break
                val corregido = CONFUSIONES[c] ?: c
                if (corregido in ALFABETO) append(corregido)
            }
        }
        return utiles.chunked(4).joinToString("-")
    }

    /** Si [texto] ya es un código completo y bien formado, listo para mandar al gateway. */
    fun estaCompleto(texto: String): Boolean =
        formatear(texto).length == LARGO_VISIBLE

    /**
     * Dónde tiene que quedar el cursor después de formatear, sabiendo dónde estaba antes.
     *
     * Sin esto, escribir el cuarto carácter mete un guión y el cursor se queda ANTES de él: la
     * tecla siguiente se escribe en el lugar equivocado. Se cuentan los caracteres útiles que
     * quedaron a la izquierda y se traduce de vuelta a una posición del texto con guiones.
     */
    fun cursorTrasFormatear(crudo: String, cursorCrudo: Int): Int {
        val hastaElCursor = crudo.take(cursorCrudo.coerceIn(0, crudo.length))
        val utiles = formatear(hastaElCursor).count { it != '-' }
        // Cada bloque de 4 completo suma un guión a la izquierda del cursor. El `- 1` es para que
        // un bloque JUSTO completo (4, 8) no cuente el guión que todavía no tiene nada detrás.
        val guiones = if (utiles == 0) 0 else (utiles - 1) / 4
        return (utiles + guiones).coerceAtMost(LARGO_VISIBLE)
    }
}
