package com.arkiv.player.data.credentials

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CredentialsActivatorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun activator(
        resolve3des: (ByteArray) -> String = { "3desKey" },
        resolveHosts: (ByteArray) -> String = { "host1.com,host2.com" },
        resolveAppId: (ByteArray) -> String = { "appId" },
        resolveApkVersion: (ByteArray) -> String = { "1.2.3" },
        resolveTmdb: (ByteArray) -> String = { "tmdbKey" },
    ) = CredentialsActivator(
        client = OkHttpClient(),
        blobUrl = server.url("/credentials.enc").toString(),
        resolveIptv3desKey = resolve3des,
        resolveIptvHosts = resolveHosts,
        resolveIptvAppId = resolveAppId,
        resolveIptvApkVersion = resolveApkVersion,
        resolveTmdbApiKey = resolveTmdb,
    )

    @Test fun `downloads the blob and combines the five resolved fields`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val result = activator().activate()
        assertEquals(RemoteCredentials("3desKey", "host1.com,host2.com", "appId", "1.2.3", "tmdbKey"), result)
    }

    @Test fun `returns null when the download fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(activator().activate())
    }

    @Test fun `returns null when any resolver comes back empty`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertNull(activator(resolve3des = { "" }).activate())
    }

    @Test fun `passes the raw downloaded bytes to every resolver`() = runBlocking {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        var seen: ByteArray? = null
        activator(resolve3des = { seen = it; "k" }).activate()
        assertEquals(bytes.toList(), seen!!.toList())
    }
}
