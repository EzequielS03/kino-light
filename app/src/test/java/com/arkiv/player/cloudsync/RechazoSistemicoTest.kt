package com.arkiv.player.cloudsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cuarentena existe para que UNA fila inválida no atasque la colección para siempre. Pero
 * cuando el rechazo es del servidor y afecta a TODAS las filas por igual, ese mecanismo se vuelve
 * en contra: cada fila quema sus intentos, todas terminan en cuarentena y el cursor las pasa de
 * largo. Así se perdieron 1.095 `episodes` + 307 `progress` en 25 h, cuando el `accountId` del
 * aparato quedó desfasado del record y la regla `accountId = @request.auth.accountId` rechazó todo.
 *
 * Un lote que rebota ENTERO no son N filas malas: es un problema del servidor, y hay que dejar que
 * el cursor espere en vez de quemar los intentos.
 */
class RechazoSistemicoTest {

    @Test
    fun loteQueRebotaEnteroEsSistemico() {
        assertTrue(PushLote.esRechazoSistemico(intentadas = 120, fallidas = 120))
    }

    @Test
    fun loteConAlgunasQueSubenNoEsSistemico() {
        // Si algo pasó, el servidor acepta: las que fallan son problema de esas filas.
        assertFalse(PushLote.esRechazoSistemico(intentadas = 5, fallidas = 3))
    }

    @Test
    fun unaSolaFilaQueFallaNoEsSistemico() {
        // Indistinguible de una fila envenenada. Se conserva la cuarentena, que es la garantía
        // de que la colección no se atasca para siempre.
        assertFalse(PushLote.esRechazoSistemico(intentadas = 1, fallidas = 1))
    }

    @Test
    fun loteSinFallosNoEsSistemico() {
        assertFalse(PushLote.esRechazoSistemico(intentadas = 8, fallidas = 0))
    }
}
