package com.arkiv.player.data.magis

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisSessionTest {

    @Test
    fun `login hashes the password with MD5 plus the cloudstream salt`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/login", portalOk("userId" to "u1", "userToken" to "t1"))
        val session = MagisSession(fake, FakeCredentialStore())

        session.login("persona@ejemplo.com", "MiClaveMagis123")

        val (_, bean) = fake.calls.first { it.first == "v8/login" }
        assertEquals("d70f9413fdc3cf2c36de29d6ccccfb2c", bean["password"])
        assertEquals("persona@ejemplo.com", bean["userName"])
        assertEquals("2", bean["accountType"])
    }

    @Test
    fun `new_anonymous_device derives the sn with MD5 of snToken plus the salt`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", portalOk("snToken" to "TOK123", "isNew" to "1"))
        fake.queueResponse("v8/active", portalOk("userId" to "u2", "userToken" to "t2"))
        val store = FakeCredentialStore()
        val session = MagisSession(fake, store)

        session.ensureAnonymous()

        val (_, beanActive) = fake.calls.first { it.first == "v8/active" }
        assertEquals("TOK123", beanActive["snToken"])
        assertEquals("efd725bf38485c1d776b0ee7b8247c96", store.readSession()?.sn)
        assertEquals("t2", store.readSession()?.userToken)
    }

    @Test
    fun `with a saved session it doesn't touch the portal again`() = runTest {
        val fake = FakePortalClient()
        val session = testSession(fake)

        val r = session.ensureAnonymous()

        assertTrue(r is MagisResult.Ok<*>)
        assertEquals(0, fake.calls.size)
        assertEquals("t-test", session.userToken)
    }

    @Test
    fun `with a saved sn but no token, it reactivates that device, without minting another`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/active", portalOk("userId" to "u3", "userToken" to "t3"))
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u3", "", "", sn = "sn-viejo"))
        val session = MagisSession(fake, store)

        session.ensureAnonymous()

        assertEquals(0, fake.timesCalled("v3/snToken"))
        assertEquals("", fake.calls.first { it.first == "v8/active" }.second["snToken"])
        assertEquals("sn-viejo", store.readSession()?.sn)
        assertEquals("t3", store.readSession()?.userToken)
    }

    @Test
    fun `if the portal says the sn no longer works, it mints a new device`() = runTest {
        val fake = FakePortalClient()
        // aaa100082: that device ended up bound to an account with a password and only accepts login.
        fake.queueResponse("v8/active", MagisResult.PortalError("aaa100082", "三方账号已经设置密码"))
        fake.queueResponse("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.queueResponse("v8/active", portalOk("userId" to "u4", "userToken" to "t4"))
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u4", "", "", sn = "sn-quemado"))
        val session = MagisSession(fake, store)

        val r = session.ensureAnonymous()

        assertTrue("expected Ok, got $r", r is MagisResult.Ok<*>)
        assertEquals(1, fake.timesCalled("v3/snToken"))
        assertEquals("efd725bf38485c1d776b0ee7b8247c96", store.readSession()?.sn)
        assertEquals("t4", store.readSession()?.userToken)
    }

    @Test
    fun `if the portal is down while activating, it doesn't mint a new device`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/active", MagisResult.RedError(java.io.IOException("no network")))
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("u5", "", "", sn = "sn-bueno"))
        val session = MagisSession(fake, store)

        val r = session.ensureAnonymous()

        assertTrue("expected RedError, got $r", r is MagisResult.RedError)
        assertEquals(0, fake.timesCalled("v3/snToken"))
        assertEquals("sn-bueno", store.readSession()?.sn)
    }

    @Test
    fun `a rejected login leaves the working session intact`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/login", MagisResult.PortalError("aaa100015", "clave mala"))
        val session = testSession(fake)

        val r = session.login("persona@ejemplo.com", "clave-mala")

        assertTrue(r is MagisResult.PortalError)
        assertEquals("t-test", session.userToken)
        assertTrue(!session.hasAccountLinked)
    }

    @Test
    fun `a successful login saves the account so it can relog on its own later`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/login", portalOk("userId" to "u6", "userToken" to "t6", "jwtToken" to "j6"))
        val store = FakeCredentialStore()
        store.saveSession(StoredSession("viejo", "t-viejo", "", sn = "sn-mio"))
        val session = MagisSession(fake, store)

        session.login("persona@ejemplo.com", "MiClaveMagis123")

        assertEquals("persona@ejemplo.com" to "MiClaveMagis123", store.readAccount())
        assertEquals("t6", store.readSession()?.userToken)
        assertEquals("j6", store.readSession()?.jwtToken)
        // The device belongs to the hardware, not the account: login doesn't overwrite it.
        assertEquals("sn-mio", store.readSession()?.sn)
        assertTrue(session.hasAccountLinked)
    }

    @Test
    fun `withValidSession relogs with the saved account and retries once`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/login", portalOk("userId" to "u7", "userToken" to "t7"))
        val session = testSessionWithAccount(fake)
        var attempts = 0

        val r = session.withValidSession {
            attempts++
            if (attempts == 1) MagisResult.PortalError("aaa100028", "未登录！") else portalOk("ok" to "si")
        }

        assertEquals(2, attempts)
        assertEquals(1, fake.timesCalled("v8/login"))
        assertEquals("si", r.getOrNull()?.getString("ok"))
        assertEquals("t7", session.userToken)
    }

    @Test
    fun `withValidSession reauthenticates on any portal error, not just aaa100028`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/active", portalOk("userId" to "u8", "userToken" to "t8"))
        val session = testSession(fake)
        var attempts = 0

        val r = session.withValidSession {
            attempts++
            if (attempts == 1) {
                MagisResult.PortalError("aaa999999", "您的账号已经在其他设备登录")
            } else {
                portalOk("ok" to "si")
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, fake.timesCalled("v8/active"))
        assertEquals("si", r.getOrNull()?.getString("ok"))
    }

    @Test
    fun `withValidSession retries ONLY once`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/active", portalOk("userId" to "u9", "userToken" to "t9"))
        val session = testSession(fake)
        var attempts = 0

        val r = session.withValidSession {
            attempts++
            MagisResult.PortalError("aaa100028", "未登erró")
        }

        assertEquals(2, attempts)
        assertTrue(r is MagisResult.PortalError)
    }

    @Test
    fun `withValidSession doesn't reauthenticate if the portal is down`() = runTest {
        val fake = FakePortalClient()
        val session = testSession(fake)
        var attempts = 0

        val r = session.withValidSession {
            attempts++
            MagisResult.RedError(java.io.IOException("no network"))
        }

        assertEquals(1, attempts)
        assertEquals(0, fake.calls.size)
        assertTrue(r is MagisResult.RedError)
    }

    @Test
    fun `logout closes it on the portal, deletes the account and goes back to anonymous with the same device`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v5/loginOut", portalOk())
        fake.queueResponse("v8/active", portalOk("userId" to "anon", "userToken" to "t-anon"))
        val session = testSessionWithAccount(fake)

        session.logout()

        assertEquals(1, fake.timesCalled("v5/loginOut"))
        assertNull(session.linkedEmail())
        assertTrue(!session.hasAccountLinked)
        assertEquals("t-anon", session.userToken)
        assertEquals("sn-cuenta", session.sn)
    }

    @Test
    fun `the hardware fingerprint for minting isn't the same twice`() = runTest {
        val fake = FakePortalClient()
        fake.defaultResponse = MagisResult.PortalError("x", null)
        MagisSession(fake, FakeCredentialStore()).ensureAnonymous()
        MagisSession(fake, FakeCredentialStore()).ensureAnonymous()

        val fingerprints = fake.calls.filter { it.first == "v3/snToken" }.map { it.second }
        assertEquals(2, fingerprints.size)
        assertNotNull(fingerprints[0]["androidId"])
        assertTrue("androidId must not repeat across devices", fingerprints[0]["androidId"] != fingerprints[1]["androidId"])
        assertTrue(fingerprints[0]["wifiMac"] != fingerprints[1]["wifiMac"])
    }

    @Test
    fun `with no snToken in the response, it doesn't make up an sn`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", portalOk("isNew" to "1"))
        val store = FakeCredentialStore()

        val r = MagisSession(fake, store).ensureAnonymous()

        assertTrue("expected an error, got $r", r !is MagisResult.Ok<*>)
        assertNull(store.readSession())
        assertEquals(0, fake.timesCalled("v8/active"))
    }

    @Test
    fun `if the portal sends its own sn, that one wins over the derived MD5`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", portalOk("snToken" to "TOK123", "sn" to "SN-DEL-PORTAL"))
        fake.queueResponse("v8/active", portalOk("userId" to "u10", "userToken" to "t10"))
        val store = FakeCredentialStore()

        MagisSession(fake, store).ensureAnonymous()

        assertEquals("sn-del-portal", store.readSession()?.sn)
    }

    @Test
    fun `activating with no userToken in the response doesn't save an empty session`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v3/snToken", portalOk("snToken" to "TOK123"))
        fake.queueResponse("v8/active", portalOk("userId" to "u11"))
        val store = FakeCredentialStore()

        val r = MagisSession(fake, store).ensureAnonymous()

        assertTrue("expected an error, got $r", r !is MagisResult.Ok<*>)
        assertEquals("", store.readSession()?.userToken.orEmpty())
    }

    @Test
    fun `a successful login returns Ok without leaking the portal's JSON`() = runTest {
        val fake = FakePortalClient()
        fake.queueResponse("v8/login", portalOk("userId" to "u12", "userToken" to "t12"))

        val r = MagisSession(fake, FakeCredentialStore()).login("a@b.com", "x")

        assertEquals(Unit, r.getOrNull())
    }
}
