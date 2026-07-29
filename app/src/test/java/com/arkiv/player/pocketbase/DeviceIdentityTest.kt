package com.arkiv.player.pocketbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun newPhoneAccount_generatesDistinctFields() {
        val a = DeviceIdentityFactory.newPhoneAccount()
        val b = DeviceIdentityFactory.newPhoneAccount()
        assertNotEquals("accountId debe ser único", a.accountId, b.accountId)
        assertNotEquals("deviceId debe ser único", a.deviceId, b.deviceId)
        assertEquals("phone", a.kind)
        assertTrue("email deriva del deviceId", a.email.startsWith(a.deviceId))
        assertTrue("email es del dominio interno", a.email.endsWith("@arkiv.local"))
        assertTrue("password suficientemente largo", a.password.length >= 24)
    }

    @Test
    fun newTvDevice_reusesAccountId() {
        val tv = DeviceIdentityFactory.newTvDevice(accountId = "acc-123")
        assertEquals("acc-123", tv.accountId)
        assertEquals("tv", tv.kind)
        assertTrue(tv.email.endsWith("@arkiv.local"))
        assertTrue(tv.password.length >= 24)
    }
}
