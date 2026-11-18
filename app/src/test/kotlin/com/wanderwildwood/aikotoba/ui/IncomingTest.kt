package com.wanderwildwood.aikotoba.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncomingTest {
    @Test
    fun `only a content address is taken as a vault file`() {
        assertTrue(Incoming.isContent("content"))
        assertTrue(Incoming.isContent("CONTENT"))
        assertFalse(Incoming.isContent("file"))
        assertFalse(Incoming.isContent(null))
    }

    @Test
    fun `a different vault here means asking first`() {
        assertFalse(Incoming.asksFirst(null, "content://a/vault.kdbx"))
        assertFalse(Incoming.asksFirst("content://a/vault.kdbx", "content://a/vault.kdbx"))
        assertTrue(Incoming.asksFirst("content://a/vault.kdbx", "content://b/other.kdbx"))
    }
}
