package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuentaDeMagisTest {

    private fun cuenta(portal: FakePortalClient, store: FakeCredentialStore = FakeCredentialStore()) =
        CuentaDeMagis(MagisSession(portal, store))

    @Test
    fun `arranca en Sin y refrescar lee lo que hay guardado`() = runTest {
        val store = FakeCredentialStore()
        val c = cuenta(FakePortalClient(), store)
        assertEquals(EstadoDeMagis.Sin, c.estado.value)

        store.guardarCuenta("persona@ejemplo.com", "clave123")
        c.refrescar()

        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)
    }

    @Test
    fun `vincular con credenciales que el portal acepta deja Vinculada con el email`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", portalOk("userId" to "u1", "userToken" to "t1"))
        val c = cuenta(portal)

        c.vincular("persona@ejemplo.com", "clave123")

        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)
    }

    @Test
    fun `credenciales rechazadas no cambian el estado y el mensaje lo dice`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", MagisResult.PortalError("aaa100015", "clave mala"))
        val c = cuenta(portal)

        val e = runCatching { c.vincular("persona@ejemplo.com", "mala") }.exceptionOrNull()

        assertTrue(e is MagisException)
        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("inválidas"))
        assertEquals(EstadoDeMagis.Sin, c.estado.value)
    }

    @Test
    fun `el portal caido se distingue de una clave mala`() = runTest {
        val portal = FakePortalClient()
        portal.encolarRespuesta("v8/login", MagisResult.RedError(java.io.IOException("sin red")))

        val e = runCatching { cuenta(portal).vincular("a@b.com", "x") }.exceptionOrNull()

        assertTrue("mensaje: ${e?.message}", e!!.message!!.contains("no disponible"))
    }

    @Test
    fun `desvincular vuelve a Sin`() = runTest {
        val portal = FakePortalClient()
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u", "t", "", "sn"))
        store.guardarCuenta("persona@ejemplo.com", "clave123")
        val c = cuenta(portal, store)
        c.refrescar()
        assertEquals(EstadoDeMagis.Vinculada("persona@ejemplo.com"), c.estado.value)

        c.desvincular()

        assertEquals(EstadoDeMagis.Sin, c.estado.value)
    }
}
