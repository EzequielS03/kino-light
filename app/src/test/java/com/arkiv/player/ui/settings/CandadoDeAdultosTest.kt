package com.arkiv.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se ve la sección 18+, con qué código y en qué aparato.
 *
 * El código lo elige la persona en Ajustes y arranca en un default conocido (0000). Desbloquea
 * solo el aparato donde se escribió: no viaja en el sync, así que el televisor del living no
 * hereda lo que se desbloqueó en el celular, y reinstalar la app lo apaga.
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
     * EL BORDE PELIGROSO. Con un código real vacío, un campo vacío daría "coincide" y le abriría
     * la sección a cualquiera que apriete OK sin escribir nada. Hoy [codigoEfectivo] impide que el
     * código real llegue vacío, pero la guarda se queda: es la última línea si las prefs quedaran
     * en blanco por cualquier motivo.
     */
    @Test fun `sin codigo configurado no abre NADA, ni siquiera el vacio`() {
        assertFalse(CandadoDeAdultos.abre("", ""))
        assertFalse(CandadoDeAdultos.abre("   ", ""))
        assertFalse(CandadoDeAdultos.abre("482913", ""))
    }

    @Test fun `un intento vacio contra un codigo real tampoco`() {
        assertFalse(CandadoDeAdultos.abre("", codigo))
    }

    // --- El default y su aviso -------------------------------------------------------------

    /** Nunca se guardó nada: rige el default, y por eso hay que anunciarlo. */
    @Test fun `sin nada guardado el codigo es el default`() {
        assertEquals("0000", CandadoDeAdultos.codigoEfectivo(null))
        assertTrue(CandadoDeAdultos.esElDefault(null))
    }

    /** Un archivo de prefs con la clave en blanco vale lo mismo que no tenerla: nunca vacío. */
    @Test fun `un guardado en blanco cae al default en vez de dejar el codigo vacio`() {
        assertEquals("0000", CandadoDeAdultos.codigoEfectivo(""))
        assertEquals("0000", CandadoDeAdultos.codigoEfectivo("   "))
    }

    @Test fun `con un codigo propio ya no rige el default`() {
        assertEquals(codigo, CandadoDeAdultos.codigoEfectivo(codigo))
        assertFalse(CandadoDeAdultos.esElDefault(codigo))
    }

    /**
     * Elegir 0000 a mano es tan default como no haber elegido nada: el aviso tiene que seguir
     * ahí, porque lo que anuncia es que ese código lo sabe cualquiera.
     */
    @Test fun `poner 0000 a mano sigue siendo el default`() {
        assertTrue(CandadoDeAdultos.esElDefault("0000"))
    }

    // --- El reseteo oculto -----------------------------------------------------------------

    /**
     * La salida para quien olvidó el código que puso. No se anuncia en ninguna parte: la señal de
     * que funcionó es que el aviso "por defecto 0000" vuelve a aparecer solo.
     */
    @Test fun `9999 pide resetear`() {
        assertTrue(CandadoDeAdultos.pideReseteo("9999"))
        assertTrue(CandadoDeAdultos.pideReseteo(" 9999 "))
    }

    @Test fun `cualquier otra cosa no resetea`() {
        assertFalse(CandadoDeAdultos.pideReseteo("0000"))
        assertFalse(CandadoDeAdultos.pideReseteo("999"))
        assertFalse(CandadoDeAdultos.pideReseteo("99999"))
        assertFalse(CandadoDeAdultos.pideReseteo(""))
    }

    /**
     * 9999 no se puede elegir como código propio. Si se pudiera, el reseteo lo taparía: quien lo
     * escribiera para entrar terminaría borrando su propio código sin abrir nada.
     */
    @Test fun `9999 esta reservado y no se puede poner como codigo`() {
        assertTrue(CandadoDeAdultos.estaReservado("9999"))
        assertTrue(CandadoDeAdultos.estaReservado(" 9999 "))
        assertFalse(CandadoDeAdultos.estaReservado("0000"))
        assertFalse(CandadoDeAdultos.estaReservado("1234"))
    }

    // --- Formato del código nuevo -----------------------------------------------------------

    @Test fun `el codigo nuevo son cuatro digitos`() {
        assertTrue(CandadoDeAdultos.formatoValido("1234"))
        assertTrue(CandadoDeAdultos.formatoValido(" 1234 "))
        assertTrue(CandadoDeAdultos.formatoValido("0000"))
    }

    @Test fun `ni mas corto ni mas largo ni con letras ni vacio`() {
        assertFalse(CandadoDeAdultos.formatoValido("123"))
        assertFalse(CandadoDeAdultos.formatoValido("12345"))
        assertFalse(CandadoDeAdultos.formatoValido("12a4"))
        assertFalse(CandadoDeAdultos.formatoValido(""))
        assertFalse(CandadoDeAdultos.formatoValido("    "))
    }

    // --- Qué se muestra ----------------------------------------------------------------------

    /** Sin desbloquear no hay ni botón ni candado ni renglón gris: un botón deshabilitado
     *  anuncia que existe algo, y anunciarlo es la mitad del problema. */
    @Test fun `sin desbloquear no se muestra la seccion`() {
        assertFalse(CandadoDeAdultos.hayQueMostrarLaSeccion(desbloqueado = false))
    }

    @Test fun `desbloqueado se muestra`() {
        assertTrue(CandadoDeAdultos.hayQueMostrarLaSeccion(desbloqueado = true))
    }

    /** El campo para escribir el código está siempre que no se haya entrado: ahora SIEMPRE hay
     *  un código, aunque sea el default, así que ya no existe el caso de pedir uno que no abre. */
    @Test fun `bloqueado se muestra el campo y desbloqueado no`() {
        assertTrue(CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado = false))
        assertFalse(CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado = true))
    }
}
