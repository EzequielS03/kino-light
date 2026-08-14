package com.arkiv.player.ui.settings

/**
 * Cuándo se ve la sección 18+, y en qué aparato.
 *
 * El código NO se fija solo la primera vez que alguien escribe algo: si lo hiciera, el primero
 * que revuelva Ajustes quedaría adentro, que es exactamente el caso que este candado existe para
 * evitar. Se compara contra uno que ya existe (viene del `.env` al compilar, como la llave de
 * firma, así que no entra a git).
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

    /**
     * Si [escrito] abre el candado.
     *
     * Un [codigoReal] vacío NUNCA abre, ni contra un intento vacío. Ese es el borde peligroso:
     * compilar fuera del repo deja el `.env` sin leer y el código en blanco —ya pasó con otras
     * llaves de este proyecto—, y con una comparación ingenua "vacío == vacío" le abriría la
     * sección a cualquiera que apriete OK sin escribir nada.
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
    fun hayQueMostrarLaSeccion(desbloqueado: Boolean, hayCodigo: Boolean): Boolean =
        desbloqueado && hayCodigo

    /**
     * El campo para escribir el código aparece solo mientras haga falta: si el build no trae
     * código, pedirlo sería prometer una puerta que no lleva a ningún lado; y ya desbloqueado, lo
     * que corresponde es poder volver a cerrarlo.
     */
    fun hayQueMostrarElCampo(desbloqueado: Boolean, hayCodigo: Boolean): Boolean =
        !desbloqueado && hayCodigo
}
