package com.arkiv.player.pocketbase

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketBaseClientAuthRecordTest {
    @Test
    fun authWithPasswordRecord_devuelveTokenYRecord() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"token":"tok-1","record":{"id":"rec-1","accountId":"acc-9","email":"a@b.co"}}"""))
        server.start()
        val client = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

        val r = client.authWithPasswordRecord("users", "a@b.co", "secret12")

        assertEquals("tok-1", r.token)
        assertEquals("rec-1", r.recordId)
        assertEquals("acc-9", r.record.getString("accountId"))
        val req = server.takeRequest()
        assertEquals("/api/collections/users/auth-with-password", req.path)
        server.shutdown()
    }
}
