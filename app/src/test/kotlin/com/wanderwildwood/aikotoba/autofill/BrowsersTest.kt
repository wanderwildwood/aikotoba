package com.wanderwildwood.aikotoba.autofill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowsersTest {
    private val cert = "a browser's certificate".toByteArray()
    private val other = "a look-alike's certificate".toByteArray()
    private val known = mapOf("org.example.browser" to setOf(Browsers.sha256(cert)))

    @Test
    fun `a listed browser is believed only with its own certificate`() {
        assertTrue(Browsers.pinned("org.example.browser", listOf(cert), known))
        assertTrue(Browsers.pinned("org.example.browser", listOf(other, cert), known))
        assertFalse(Browsers.pinned("org.example.browser", listOf(other), known))
        assertFalse(Browsers.pinned("org.example.browser", emptyList(), known))
    }

    @Test
    fun `an app that is not listed is never a browser, whatever it is signed with`() {
        assertFalse(Browsers.pinned("org.example.linkopener", listOf(cert), known))
        assertFalse(Browsers.pinned("org.example.linkopener", listOf(cert)))
    }

    @Test
    fun `digests are upper-case hex of SHA-256`() {
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", Browsers.sha256("abc".toByteArray()))
    }

    @Test
    fun `every pin is a whole SHA-256 digest`() {
        assertTrue(Browsers.KNOWN.isNotEmpty())
        for ((pkg, pins) in Browsers.KNOWN) {
            assertTrue(pins.isNotEmpty(), pkg)
            for (pin in pins) assertTrue(Regex("[0-9A-F]{64}").matches(pin), "$pkg: $pin")
        }
    }
}
