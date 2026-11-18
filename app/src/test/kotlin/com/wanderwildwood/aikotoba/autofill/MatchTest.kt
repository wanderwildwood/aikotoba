package com.wanderwildwood.aikotoba.autofill

import com.wanderwildwood.aikotoba.vault.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchTest {
    private fun item(url: String, extra: Map<String, String> = emptyMap()) =
        Item(UUID.randomUUID(), "t", "u", url, "", "", null, true, extra)

    @Test
    fun `a site matches itself and its parts, nothing else`() {
        assertTrue(Match.matches(item("https://mail.example.org/login"), "org.browser", "mail.example.org"))
        assertTrue(Match.matches(item("example.org"), "org.browser", "accounts.example.org"))
        assertTrue(Match.matches(item("https://www.example.org"), "org.browser", "example.org"))
        assertFalse(Match.matches(item("https://example.org"), "org.browser", "badexample.org"))
        assertFalse(Match.matches(item("https://example.org"), "org.browser", "example.org.evil.net"))
        assertFalse(Match.matches(item(""), "org.browser", "example.org"))
    }

    @Test
    fun `an app matches only by an exact claim`() {
        assertTrue(Match.matches(item("androidapp://com.example.mail"), "com.example.mail", null))
        assertTrue(Match.matches(item("", mapOf("KP2A_URL_1" to "androidapp://com.example.mail")), "com.example.mail", null))
        assertTrue(Match.matches(item("", mapOf("AndroidApp" to "com.example.mail")), "com.example.mail", null))
        assertFalse(Match.matches(item("https://mail.example.com"), "com.example.mail", null))
        assertFalse(Match.matches(item("androidapp://com.example.mail"), "com.example.mail.fake", null))
    }

    @Test
    fun `hosts`() {
        assertEquals("example.org", Match.host("https://WWW.Example.org/a"))
        assertEquals("bank.example.com", Match.host("bank.example.com"))
        assertEquals(null, Match.host("androidapp://x.y"))
    }

    @Test
    fun `a pick is remembered only on an app's own screen`() {
        assertTrue(Match.mayRemember(site = null, webDomain = null))
        assertTrue(Match.mayRemember(site = null, webDomain = ""))
        // A browser's page: matched by its address instead.
        assertFalse(Match.mayRemember(site = "example.org", webDomain = "example.org"))
        // A web page inside some other app.
        assertFalse(Match.mayRemember(site = null, webDomain = "bank.example.org"))
        // A browser not on the known list that names its page only away from the fields.
        assertFalse(Match.mayRemember(site = null, webDomain = null, pageDomain = "bank.example.org"))
        assertFalse(Match.mayRemember(site = null, webDomain = "", pageDomain = "bank.example.org"))
        assertTrue(Match.mayRemember(site = null, webDomain = null, pageDomain = ""))
    }
}
