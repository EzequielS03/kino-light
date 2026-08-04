package com.arkiv.player.data.offline

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NucJobEventsTest {

    private fun job(status: String) = NucJob(jobId = 1L, status = status, progress = null, items = emptyList())

    private fun api() = ArkivOfflineApi(lanBaseUrl = { "" }, tunnelBaseUrl = { "" }, apiKey = { "" })

    @Test
    fun `merges SSE and poll without duplicate terminal emission`() = runBlocking {
        // El SSE ve "done" casi de inmediato (10ms) -- mucho antes de que el poll (intervalo de
        // 200ms) tenga chance de notarlo.
        val sse: (Long) -> Flow<NucJob> = {
            flow {
                emit(job("downloading"))
                delay(10)
                emit(job("done"))
            }
        }
        // Si el poll llegara a correr sin cortarse, vería "done" recién en su 3ra llamada. Con un
        // intervalo de 200ms entre llamadas, no debería alcanzar ni la 2da antes de que el SSE
        // cierre el Flow.
        val pollCalls = AtomicInteger(0)
        val poll: suspend (Long) -> NucJob? = {
            when (pollCalls.getAndIncrement()) {
                0 -> job("downloading")
                1 -> job("downloading")
                else -> job("done")
            }
        }

        val events = NucJobEvents(
            api = api(),
            apiKey = { "" },
            pollIntervalMs = 200L,
            sseSource = sse,
            pollSource = poll,
        )

        val results = withTimeout(2000) { events.observeJob(1L).toList() }

        // Solo dos estados distintos vistos (dedup de la "downloading" duplicada entre SSE y
        // poll), y el Flow terminó apenas llegó el primer "done" -- sin repetirlo.
        assertEquals(listOf("downloading", "done"), results.map { it.status })
        assertEquals(1, results.count { it.status == "done" })
        // El poll nunca debería haber llegado a ver "done": el SSE cerró el Flow antes de su
        // segunda llamada.
        assertTrue("pollCalls=${pollCalls.get()}", pollCalls.get() < 2)
    }
}
