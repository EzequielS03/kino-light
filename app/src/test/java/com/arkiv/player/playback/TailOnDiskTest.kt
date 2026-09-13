package com.arkiv.player.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file's tail (its last KB), saved to disk so it survives the app restarting.
 *
 * MEASURED ON THE FIRE TV on 2026-08-14, opening an MPEG-TS movie for the first time:
 *
 * ```
 * 09:35:11.012  loadMedia formato=mpegts
 * 09:35:12.667  ← pide rango=bytes=660308868-           ← libVLC wants the END of the file
 * 09:35:13.921  origen rechazó ... con -1 (intento 1/3)
 * 09:35:15.121  origen rechazó ... con -1 (intento 1/3)
 * 09:35:16.320  precalentada la cola: 256KB en 6205ms
 * 09:35:16.386  ⏱ abrió en 5376ms
 * ```
 *
 * With a TS, libVLC probes the end of the file to deduce the duration, and until that range
 * arrives there's no picture. The same day's comparison leaves no doubt: the new mp4 asked ONLY
 * for `bytes=0-` and opened in 1525 ms; the new mpegts also asked for the end and took 5376 ms.
 *
 * [HotTail] already resolves the probe -it served it from memory, no network- and
 * `openWithDuplicate` already tackles the CDN's rejections. What was missing is that those 256 KB
 * don't get thrown away: until now they lived in an in-memory map, so EVERY app startup paid the
 * first-time cost again. On a Fire TV, which kills the app as soon as it goes to the background,
 * that's almost always.
 */
class TailOnDiskTest {

    @get:Rule val folder = TemporaryFolder()

    private fun tail() = TailOnDisk(folder.root)

    private val bytes = ByteArray(4096) { (it % 251).toByte() }

    @Test fun `what was saved can be read back the same`() {
        val c = tail()
        c.save("clave1", start = 660_296_724L, total = 660_558_868L, bytes = bytes)

        val read = c.read("clave1")!!
        assertEquals(660_296_724L, read.start)
        assertEquals(660_558_868L, read.total)
        assertArrayEquals(bytes, read.bytes)
    }

    @Test fun `a key that was never saved returns nothing`() {
        assertNull(tail().read("nunca-vista"))
    }

    @Test fun `it survives another instance, which is the point of saving it to disk`() {
        tail().save("clave1", 100L, 200L, bytes)
        // Another instance over the same folder = the app restarted.
        assertArrayEquals(bytes, TailOnDisk(folder.root).read("clave1")!!.bytes)
    }

    /** A half-written file (the app died while saving) can't blow up or serve garbage. */
    @Test fun `a truncated file is discarded instead of blowing up`() {
        val c = tail()
        c.save("clave1", 100L, 200L, bytes)
        val f = folder.root.listFiles()!!.first { it.name.startsWith("clave1") }
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(c.read("clave1"))
    }

    @Test fun `a file with only the header and no body is discarded`() {
        val c = tail()
        c.save("clave1", 100L, 200L, bytes)
        val f = folder.root.listFiles()!!.first { it.name.startsWith("clave1") }
        f.writeBytes(ByteArray(TailOnDisk.HEADER_BYTES))
        assertNull(c.read("clave1"))
    }

    @Test fun `saving the same key twice leaves the last one`() {
        val c = tail()
        c.save("clave1", 100L, 200L, ByteArray(10) { 1 })
        c.save("clave1", 300L, 400L, ByteArray(10) { 2 })

        val read = c.read("clave1")!!
        assertEquals(300L, read.start)
        assertEquals(2.toByte(), read.bytes[0])
    }

    /**
     * It can't grow forever: it's ~256 KB per title and the video cache already has its own
     * budget. The oldest one gets dropped, since it's the least likely to be reopened.
     */
    @Test fun `it does not save more tails than the cap`() {
        val c = TailOnDisk(folder.root, maxTails = 3)
        repeat(5) { i ->
            c.save("clave$i", i.toLong(), 999L, ByteArray(64) { i.toByte() })
            // Dropping goes by modification date, which runs too fast within a test.
            folder.root.listFiles()!!.forEach { it.setLastModified(1_000_000L + i * 1000L) }
        }
        assertEquals(3, folder.root.listFiles()!!.size)
        // The first two got dropped; the last ones remain.
        assertNull(c.read("clave0"))
        assertNull(c.read("clave1"))
    }

    @Test fun `saving an empty tail leaves nothing`() {
        val c = tail()
        c.save("clave1", 100L, 200L, ByteArray(0))
        assertNull(c.read("clave1"))
    }
}
