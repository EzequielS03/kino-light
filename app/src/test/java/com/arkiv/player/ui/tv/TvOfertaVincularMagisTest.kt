package com.arkiv.player.ui.tv

import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cubre `debeOfrecerVincularMagis` (Task 10), la condición pura detrás de "ofrecer vincular Magis
 * apenas se entra a la TV". Separada de la Composable a propósito -este proyecto no tiene
 * infraestructura de tests de UI de Compose, ver el KDoc de `entrarDesdeTv`-, así que esta función es
 * la única parte de la feature que se puede probar en un test JVM plano.
 */
class TvOfertaVincularMagisTest {

    @Test fun `anonimo nunca se ofrece, tenga o no descartada`() {
        assertEquals(false, debeOfrecerVincularMagis(AccountState.Anonimo, descartada = false))
        assertEquals(false, debeOfrecerVincularMagis(AccountState.Anonimo, descartada = true))
    }

    @Test fun `conectado sin Magis y sin descartar SI se ofrece`() {
        assertEquals(
            true,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = false), descartada = false),
        )
    }

    @Test fun `conectado y ya vinculado no se ofrece, aunque descartada sea false`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = true), descartada = false),
        )
    }

    @Test fun `conectado sin Magis pero ya descartada (Ahora no) no se ofrece`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = false), descartada = true),
        )
    }

    @Test fun `vinculado Y descartada tampoco se ofrece (caso limite, las dos razones a la vez)`() {
        assertEquals(
            false,
            debeOfrecerVincularMagis(AccountState.Conectado("a@b.co", magisLinked = true), descartada = true),
        )
    }

    // --- RegistroMagisFlow (Task 11): las transiciones del alta de Magis en dos pasos, sin
    // depender de un AccountManager real -las lambdas son fakes livianos que tiran o no, ver el
    // KDoc de la clase sobre por qué esto alcanza en vez de levantar un MockWebServer-.

    @Test fun `arranca en paso EMAIL`() {
        val flow = RegistroMagisFlow(enviarCodigoAMagis = {}, confirmarEnMagis = { _, _, _ -> })
        assertEquals(PasoRegistroMagis.EMAIL, flow.paso)
    }

    @Test fun `enviarCodigo avanza a paso CODIGO cuando Magis lo acepta`() = runBlocking {
        val flow = RegistroMagisFlow(enviarCodigoAMagis = { /* ok */ }, confirmarEnMagis = { _, _, _ -> })

        flow.enviarCodigo("a@b.co")

        assertEquals(PasoRegistroMagis.CODIGO, flow.paso)
    }

    @Test fun `enviarCodigo NO avanza de paso si Magis lo rechaza, la excepcion sube tal cual`() = runBlocking {
        val flow = RegistroMagisFlow(
            enviarCodigoAMagis = { throw AccountException("ese email ya está registrado en Magis") },
            confirmarEnMagis = { _, _, _ -> },
        )

        var msg: String? = null
        try {
            flow.enviarCodigo("a@b.co")
            fail("esperaba AccountException")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertEquals("ese email ya está registrado en Magis", msg)
        assertEquals("el paso NO avanza si Magis lo rechazó", PasoRegistroMagis.EMAIL, flow.paso)
    }

    @Test fun `se puede volver del paso del codigo al del email`() = runBlocking {
        val flow = RegistroMagisFlow(enviarCodigoAMagis = {}, confirmarEnMagis = { _, _, _ -> })
        flow.enviarCodigo("a@b.co")
        assertEquals(PasoRegistroMagis.CODIGO, flow.paso)

        flow.volverAEmail()

        assertEquals(
            "para el caso del email mal tipeado: no hay que quedar encerrado esperando un código",
            PasoRegistroMagis.EMAIL,
            flow.paso,
        )
    }

    @Test fun `confirmar delega los tres valores tal cual a la funcion de confirmar`() = runBlocking {
        var recibido: Triple<String, String, String>? = null
        val flow = RegistroMagisFlow(
            enviarCodigoAMagis = {},
            confirmarEnMagis = { email, password, code -> recibido = Triple(email, password, code) },
        )

        flow.confirmar("a@b.co", "unaclave12", "123456")

        assertEquals(Triple("a@b.co", "unaclave12", "123456"), recibido)
    }

    @Test fun `confirmar que lanza no toca el paso, se queda en CODIGO`() = runBlocking {
        val flow = RegistroMagisFlow(
            enviarCodigoAMagis = {},
            confirmarEnMagis = { _, _, _ -> throw AccountException("código incorrecto") },
        )
        flow.enviarCodigo("a@b.co")

        var msg: String? = null
        try {
            flow.confirmar("a@b.co", "unaclave12", "000000")
            fail("esperaba AccountException")
        } catch (e: AccountException) {
            msg = e.message
        }

        assertEquals("código incorrecto", msg)
        assertEquals(PasoRegistroMagis.CODIGO, flow.paso)
    }
}
