package com.arkiv.player.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se ve la sección 18+, y en qué aparato.
 *
 * El código no se fija solo la primera vez: si tipear cualquier cosa lo definiera, el primero que
 * revuelva Ajustes queda adentro — el caso que este candado existe para evitar. Y desbloquea solo
 * el aparato donde se escribió: no viaja en el sync, así que el televisor del living no hereda lo
 * que se desbloqueó en el celular, y reinstalar la app lo apaga.
 */
class CandadoDeAdultosTest {

    private val codigo = "482913"

    @Test fun `con el codigo correcto se abre`() {
        assertTrue(CandadoDeAdultos.abre(escrito = "482913", codigoReal = codigo))
    }

    @Test fun `con cualquier otro codigo no`() {
        assertFalse(CandadoDeAdultos.abre("482914", codigo))
        assertFalse(CandadoDeAdultos.abre("1234", codigo))
    }

    /** Los espacios de un teclado en pantalla no pueden ser la diferencia entre entrar o no. */
    @Test fun `los espacios de sobra no cuentan`() {
        assertTrue(CandadoDeAdultos.abre("  482913 ", codigo))
    }

    /**
     * EL BORDE PELIGROSO. Sin código configurado (el `.env` sin leer al compilar fuera del repo —
     * ya pasó con otras llaves), un campo vacío contra un código vacío daría "coincide" y le
     * abriría la sección a cualquiera que apriete OK sin escribir nada.
     */
    @Test fun `sin codigo configurado no abre NADA, ni siquiera el vacio`() {
        assertFalse(CandadoDeAdultos.abre("", ""))
        assertFalse(CandadoDeAdultos.abre("   ", ""))
        assertFalse(CandadoDeAdultos.abre("482913", ""))
    }

    @Test fun `un intento vacio contra un codigo real tampoco`() {
        assertFalse(CandadoDeAdultos.abre("", codigo))
    }

    /** Sin desbloquear no hay ni botón ni candado ni renglón gris: un botón deshabilitado
     *  anuncia que existe algo, y anunciarlo es la mitad del problema. */
    @Test fun `sin desbloquear no se muestra la seccion`() {
        assertFalse(CandadoDeAdultos.hayQueMostrarLaSeccion(desbloqueado = false, hayCodigo = true))
    }

    @Test fun `desbloqueado se muestra`() {
        assertTrue(CandadoDeAdultos.hayQueMostrarLaSeccion(desbloqueado = true, hayCodigo = true))
    }

    /** Si el build no trae código, el campo tampoco: pedir un código que no existe es prometer
     *  una puerta que no lleva a ningún lado. */
    @Test fun `sin codigo configurado no se muestra ni el campo`() {
        assertFalse(CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado = false, hayCodigo = false))
        assertTrue(CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado = false, hayCodigo = true))
    }

    @Test fun `desbloqueado no se muestra el campo`() {
        assertFalse(CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado = true, hayCodigo = true))
    }
}
