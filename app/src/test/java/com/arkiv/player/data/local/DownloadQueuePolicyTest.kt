package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {

    @Test
    fun `toma la fila encolada mas vieja`() {
        val rows = listOf(
            QueueRow("b", LocalDownloadState.QUEUED, createdAt = 200),
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("c", LocalDownloadState.QUEUED, createdAt = 300),
        )
        assertEquals("a", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `una fila a medio bajar tiene prioridad sobre las encoladas`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.DOWNLOADING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `staging tambien se retoma antes que lo encolado`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.QUEUED, createdAt = 100),
            QueueRow("b", LocalDownloadState.STAGING, createdAt = 900),
        )
        assertEquals("b", DownloadQueuePolicy.nextToProcess(rows)?.episodeId)
    }

    @Test
    fun `no toma completadas fallidas ni pendientes de confirmacion`() {
        val rows = listOf(
            QueueRow("a", LocalDownloadState.COMPLETED, createdAt = 100),
            QueueRow("b", LocalDownloadState.FAILED, createdAt = 200),
            QueueRow("c", LocalDownloadState.NEEDS_CONFIRMATION, createdAt = 300),
        )
        assertNull(DownloadQueuePolicy.nextToProcess(rows))
    }

    @Test
    fun `cola vacia no da nada`() {
        assertNull(DownloadQueuePolicy.nextToProcess(emptyList()))
    }

    @Test
    fun `estados terminales`() {
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.COMPLETED))
        assertTrue(DownloadQueuePolicy.isTerminal(LocalDownloadState.FAILED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.QUEUED))
        assertFalse(DownloadQueuePolicy.isTerminal(LocalDownloadState.DOWNLOADING))
    }

    @Test
    fun `solo falla y pendiente de confirmacion se pueden reintentar`() {
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.FAILED))
        assertTrue(DownloadQueuePolicy.isRetryable(LocalDownloadState.NEEDS_CONFIRMATION))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.COMPLETED))
        assertFalse(DownloadQueuePolicy.isRetryable(LocalDownloadState.QUEUED))
    }
}
