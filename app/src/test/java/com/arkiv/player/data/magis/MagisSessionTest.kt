package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisSessionTest {

    @Test
    fun `login hashea la contrasena con MD5 mas el salt cloudstream`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", portalOk("userId" to "u1", "userToken" to "t1"))
        val session = MagisSession(fake, FakeCredentialStore())

        session.login("persona@ejemplo.com", "MiClaveMagis123")

        val (_, bean) = fake.llamadas.first { it.first == "v8/login" }
        assertEquals("d70f9413fdc3cf2c36de29d6ccccfb2c", bean["password"])
        assertEquals("persona@ejemplo.com", bean["userName"])
        assertEquals("2", bean["accountType"])
    }

    @Test
    fun `new_anonymous_device deriva el sn con MD5 de snToken mas el salt`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", portalOk("snToken" to "TOK123", "isNew" to "1"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u2", "userToken" to "t2"))
        val store = FakeCredentialStore()
        val session = MagisSession(fake, store)

        session.ensureAnonymous()

        val (_, beanActive) = fake.llamadas.first { it.first == "v8/active" }
        assertEquals("TOK123", beanActive["snToken"])
        assertEquals("efd725bf38485c1d776b0ee7b8247c96", store.leerSesion()?.sn)
        assertEquals("t2", store.leerSesion()?.userToken)
    }

    @Test
    fun `con sesion guardada no vuelve a tocar el portal`() = runTest {
        val fake = FakePortalClient()
        val session = sesionDeTest(fake)

        val r = session.ensureAnonymous()

        assertTrue(r is MagisResult.Ok<*>)
        assertEquals(0, fake.llamadas.size)
        assertEquals("t-test", session.userToken)
    }

    @Test
    fun `con sn guardado pero sin token reactiva ese device, sin acunar otro`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u3", "userToken" to "t3"))
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u3", "", "", sn = "sn-viejo"))
        val session = MagisSession(fake, store)

        session.ensureAnonymous()

        assertEquals(0, fake.vecesLlamado("v3/snToken"))
        assertEquals("", fake.llamadas.first { it.first == "v8/active" }.second["snToken"])
        assertEquals("sn-viejo", store.leerSesion()?.sn)
        assertEquals("t3", store.leerSesion()?.userToken)
    }

    @Test
    fun `si el portal dice que el sn ya no sirve, acuna un device nuevo`() = runTest {
        val fake = FakePortalClient()
        // aaa100082: ese device quedó bindeado a una cuenta con contraseña y solo admite login.
        fake.encolarRespuesta("v8/active", MagisResult.PortalError("aaa100082", "三方账号已经设置密码"))
        fake.encolarRespuesta("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u4", "userToken" to "t4"))
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u4", "", "", sn = "sn-quemado"))
        val session = MagisSession(fake, store)

        val r = session.ensureAnonymous()

        assertTrue("esperaba Ok y fue $r", r is MagisResult.Ok<*>)
        assertEquals(1, fake.vecesLlamado("v3/snToken"))
        assertEquals("efd725bf38485c1d776b0ee7b8247c96", store.leerSesion()?.sn)
        assertEquals("t4", store.leerSesion()?.userToken)
    }

    @Test
    fun `si el portal esta caido al activar, no acuna un device nuevo`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/active", MagisResult.RedError(java.io.IOException("sin red")))
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("u5", "", "", sn = "sn-bueno"))
        val session = MagisSession(fake, store)

        val r = session.ensureAnonymous()

        assertTrue("esperaba RedError y fue $r", r is MagisResult.RedError)
        assertEquals(0, fake.vecesLlamado("v3/snToken"))
        assertEquals("sn-bueno", store.leerSesion()?.sn)
    }

    @Test
    fun `login rechazado deja intacta la sesion que estaba funcionando`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", MagisResult.PortalError("aaa100015", "clave mala"))
        val session = sesionDeTest(fake)

        val r = session.login("persona@ejemplo.com", "clave-mala")

        assertTrue(r is MagisResult.PortalError)
        assertEquals("t-test", session.userToken)
        assertTrue(!session.hasAccountLinked)
    }

    @Test
    fun `login exitoso guarda la cuenta para poder relogar sola despues`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", portalOk("userId" to "u6", "userToken" to "t6", "jwtToken" to "j6"))
        val store = FakeCredentialStore()
        store.guardarSesion(SesionGuardada("viejo", "t-viejo", "", sn = "sn-mio"))
        val session = MagisSession(fake, store)

        session.login("persona@ejemplo.com", "MiClaveMagis123")

        assertEquals("persona@ejemplo.com" to "MiClaveMagis123", store.leerCuenta())
        assertEquals("t6", store.leerSesion()?.userToken)
        assertEquals("j6", store.leerSesion()?.jwtToken)
        // El device es del aparato, no de la cuenta: el login no lo pisa.
        assertEquals("sn-mio", store.leerSesion()?.sn)
        assertTrue(session.hasAccountLinked)
    }

    @Test
    fun `conSesionValida relogea con la cuenta guardada y reintenta una vez`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", portalOk("userId" to "u7", "userToken" to "t7"))
        val session = sesionDeTestConCuenta(fake)
        var intentos = 0

        val r = session.conSesionValida {
            intentos++
            if (intentos == 1) MagisResult.PortalError("aaa100028", "未登录！") else portalOk("ok" to "si")
        }

        assertEquals(2, intentos)
        assertEquals(1, fake.vecesLlamado("v8/login"))
        assertEquals("si", r.dato()?.getString("ok"))
        assertEquals("t7", session.userToken)
    }

    @Test
    fun `conSesionValida reautentica ante cualquier error del portal, no solo aaa100028`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u8", "userToken" to "t8"))
        val session = sesionDeTest(fake)
        var intentos = 0

        val r = session.conSesionValida {
            intentos++
            if (intentos == 1) {
                MagisResult.PortalError("aaa999999", "您的账号已经在其他设备登录")
            } else {
                portalOk("ok" to "si")
            }
        }

        assertEquals(2, intentos)
        assertEquals(1, fake.vecesLlamado("v8/active"))
        assertEquals("si", r.dato()?.getString("ok"))
    }

    @Test
    fun `conSesionValida reintenta UNA sola vez`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u9", "userToken" to "t9"))
        val session = sesionDeTest(fake)
        var intentos = 0

        val r = session.conSesionValida {
            intentos++
            MagisResult.PortalError("aaa100028", "未登erró")
        }

        assertEquals(2, intentos)
        assertTrue(r is MagisResult.PortalError)
    }

    @Test
    fun `conSesionValida no reautentica si el portal esta caido`() = runTest {
        val fake = FakePortalClient()
        val session = sesionDeTest(fake)
        var intentos = 0

        val r = session.conSesionValida {
            intentos++
            MagisResult.RedError(java.io.IOException("sin red"))
        }

        assertEquals(1, intentos)
        assertEquals(0, fake.llamadas.size)
        assertTrue(r is MagisResult.RedError)
    }

    @Test
    fun `logout cierra en el portal, borra la cuenta y vuelve a anonimo con el mismo device`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v5/loginOut", portalOk())
        fake.encolarRespuesta("v8/active", portalOk("userId" to "anon", "userToken" to "t-anon"))
        val session = sesionDeTestConCuenta(fake)

        session.logout()

        assertEquals(1, fake.vecesLlamado("v5/loginOut"))
        assertNull(session.emailVinculado())
        assertTrue(!session.hasAccountLinked)
        assertEquals("t-anon", session.userToken)
        assertEquals("sn-cuenta", session.sn)
    }

    @Test
    fun `la huella de hardware para acunar no es la misma dos veces`() = runTest {
        val fake = FakePortalClient()
        fake.respuestaPorDefecto = MagisResult.PortalError("x", null)
        MagisSession(fake, FakeCredentialStore()).ensureAnonymous()
        MagisSession(fake, FakeCredentialStore()).ensureAnonymous()

        val huellas = fake.llamadas.filter { it.first == "v3/snToken" }.map { it.second }
        assertEquals(2, huellas.size)
        assertNotNull(huellas[0]["androidId"])
        assertTrue("el androidId no puede repetirse entre aparatos", huellas[0]["androidId"] != huellas[1]["androidId"])
        assertTrue(huellas[0]["wifiMac"] != huellas[1]["wifiMac"])
    }

    @Test
    fun `sin snToken en la respuesta no inventa un sn`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", portalOk("isNew" to "1"))
        val store = FakeCredentialStore()

        val r = MagisSession(fake, store).ensureAnonymous()

        assertTrue("esperaba error y fue $r", r !is MagisResult.Ok<*>)
        assertNull(store.leerSesion())
        assertEquals(0, fake.vecesLlamado("v8/active"))
    }

    @Test
    fun `si el portal manda su propio sn, ese gana sobre el MD5 derivado`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", portalOk("snToken" to "TOK123", "sn" to "SN-DEL-PORTAL"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u10", "userToken" to "t10"))
        val store = FakeCredentialStore()

        MagisSession(fake, store).ensureAnonymous()

        assertEquals("sn-del-portal", store.leerSesion()?.sn)
    }

    @Test
    fun `activar sin userToken en la respuesta no guarda una sesion vacia`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.encolarRespuesta("v8/active", portalOk("userId" to "u11"))
        val store = FakeCredentialStore()

        val r = MagisSession(fake, store).ensureAnonymous()

        assertTrue("esperaba error y fue $r", r !is MagisResult.Ok<*>)
        assertEquals("", store.leerSesion()?.userToken.orEmpty())
    }

    @Test
    fun `login exitoso devuelve Ok sin filtrar el JSON del portal`() = runTest {
        val fake = FakePortalClient()
        fake.encolarRespuesta("v8/login", portalOk("userId" to "u12", "userToken" to "t12"))

        val r = MagisSession(fake, FakeCredentialStore()).login("a@b.com", "x")

        assertEquals(Unit, r.dato())
    }
}
