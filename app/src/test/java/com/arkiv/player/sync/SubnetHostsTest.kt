package com.arkiv.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetHostsTest {
    @Test fun `enumera la subred 24 excluyendo la propia ip`() {
        val hosts = subnetHosts("192.168.3.20")
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.3.28"))
        assertTrue(!hosts.contains("192.168.3.20"))
        assertTrue(hosts.contains("192.168.3.1") && hosts.contains("192.168.3.254"))
    }

    @Test fun `ip invalida da vacio`() {
        assertTrue(subnetHosts(null).isEmpty())
        assertTrue(subnetHosts("abc").isEmpty())
    }
}
