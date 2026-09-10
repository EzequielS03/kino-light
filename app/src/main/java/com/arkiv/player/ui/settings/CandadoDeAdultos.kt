package com.arkiv.player.ui.settings

/**
 * Cuándo se ve la sección 18+, con qué código y en qué aparato.
 *
 * El código lo elige la persona en Ajustes y se guarda en el aparato. Mientras no elija ninguno
 * rige [CODIGO_POR_DEFECTO], que la pantalla ANUNCIA — a propósito: el APK se distribuye, y un
 * código que solo conoce quien compiló deja la sección cerrada para todos los demás. El anuncio
 * desaparece en cuanto hay un código propio.
 *
 * Desbloquea SOLO el aparato donde se escribió: se guarda local, no viaja en el sync, y
 * reinstalar la app lo apaga.
 *
 * El alcance, dicho derecho: esto frena a alguien con el control remoto, no a alguien que
 * decompile el APK ni a quien arme el pedido HTTP a mano. El candado de la app original tiene
 * exactamente la misma fuerza — su clave parental se valida del lado del cliente y el portal ni
 * la mira (`RestrictedStatusBean` no lleva contraseña, verificado en el decompilado).
 */
object CandadoDeAdultos {

    /** El código con el que arranca cualquier instalación, y el único que la pantalla anuncia. */
    const val CODIGO_POR_DEFECTO = "0000"

    /**
     * La salida para quien olvidó el código que puso: escribirlo en el campo devuelve el candado
     * a [CODIGO_POR_DEFECTO]. No se anuncia en ninguna parte — la señal de que funcionó es que el
     * aviso del default vuelve a aparecer solo.
     */
    const val CODIGO_DE_RESETEO = "9999"

    /**
     * El código que rige de verdad. Nada guardado —o guardado en blanco— cae al default: que el
     * código real llegue vacío es justo el borde que [abre] existe para tapar.
     */
    fun codigoEfectivo(guardado: String?): String =
        guardado?.trim()?.takeIf { it.isNotEmpty() } ?: CODIGO_POR_DEFECTO

    /**
     * Si todavía rige el código que sabe cualquiera, y por lo tanto hay que anunciarlo. Elegir
     * `0000` a mano cuenta como default: lo que el aviso dice no es "no elegiste", es "este
     * código no protege nada".
     */
    fun esElDefault(guardado: String?): Boolean = codigoEfectivo(guardado) == CODIGO_POR_DEFECTO

    /** Si lo escrito en el campo es el pedido de reseteo en vez de un intento de entrar. */
    fun pideReseteo(escrito: String): Boolean = escrito.trim() == CODIGO_DE_RESETEO

    /**
     * [CODIGO_DE_RESETEO] no se puede elegir como código propio. Si se pudiera, el reseteo lo
     * taparía: quien lo escribiera para entrar terminaría borrando su propio código sin abrir
     * nada.
     */
    fun estaReservado(nuevo: String): Boolean = nuevo.trim() == CODIGO_DE_RESETEO

    /** Cuatro dígitos, como el default: el campo usa el teclado numérico del televisor. */
    fun formatoValido(nuevo: String): Boolean =
        nuevo.trim().length == 4 && nuevo.trim().all { it.isDigit() }

    /**
     * Si [escrito] abre el candado.
     *
     * Un [codigoReal] vacío NUNCA abre, ni contra un intento vacío. [codigoEfectivo] ya impide
     * que llegue vacío, pero la guarda se queda como última línea: con una comparación ingenua
     * "vacío == vacío" le abriría la sección a cualquiera que apriete OK sin escribir nada.
     */
    fun abre(escrito: String, codigoReal: String): Boolean {
        val real = codigoReal.trim()
        if (real.isEmpty()) return false
        return escrito.trim() == real
    }

    /**
     * Sin desbloquear no se muestra NADA de la sección: ni el botón, ni un candado, ni un renglón
     * en gris. Un botón deshabilitado anuncia que existe algo, y anunciarlo es la mitad del
     * problema.
     */
    fun hayQueMostrarLaSeccion(desbloqueado: Boolean): Boolean = desbloqueado

    /**
     * El campo para escribir el código está siempre que no se haya entrado. Ya no existe el caso
     * de "el build no trae código": siempre hay uno, aunque sea el default.
     */
    fun hayQueMostrarElCampo(desbloqueado: Boolean): Boolean = !desbloqueado
}
