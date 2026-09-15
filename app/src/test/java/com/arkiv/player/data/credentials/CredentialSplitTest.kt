package com.arkiv.player.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialSplitTest {
    @Test fun `splits an even-length value into equal halves`() {
        val (file, native) = CredentialSplit.split("ABCDEF")
        assertEquals("ACE", file)
        assertEquals("BDF", native)
    }

    @Test fun `splits an odd-length value with the file half one character longer`() {
        val (file, native) = CredentialSplit.split("ABCDE")
        assertEquals("ACE", file)
        assertEquals("BD", native)
    }

    @Test fun `splitting an empty string gives two empty halves`() {
        val (file, native) = CredentialSplit.split("")
        assertEquals("", file)
        assertEquals("", native)
    }

    @Test fun `a single character goes entirely to the file half`() {
        val (file, native) = CredentialSplit.split("A")
        assertEquals("A", file)
        assertEquals("", native)
    }

    @Test fun `combine reverses split for an even-length value`() {
        val (file, native) = CredentialSplit.split("ABCDEF")
        assertEquals("ABCDEF", CredentialSplit.combine(file, native))
    }

    @Test fun `combine reverses split for an odd-length value`() {
        val (file, native) = CredentialSplit.split("ABCDE")
        assertEquals("ABCDE", CredentialSplit.combine(file, native))
    }

    @Test fun `combine reverses split for a realistic hex key`() {
        val key = "5f3a9c1e7b2d4468091acaffe12300de45ab6c7"
        val (file, native) = CredentialSplit.split(key)
        assertEquals(key, CredentialSplit.combine(file, native))
    }

    @Test fun `combine rejects halves whose lengths cannot come from a valid split`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialSplit.combine("A", "XY")
        }
    }
}
