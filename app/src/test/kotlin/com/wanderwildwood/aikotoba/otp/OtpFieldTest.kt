package com.wanderwildwood.aikotoba.otp

import com.ok1cdj.kauth.core.Base32
import com.ok1cdj.kauth.core.OtpAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OtpFieldTest {

    @Test
    fun `reads KeePassXC's otp field`() {
        val spec = OtpField.read(mapOf("otp" to "otpauth://totp/River%20Bank:tomas.reyes?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=River%20Bank&period=60&digits=8&algorithm=SHA256"))
        assertNotNull(spec)
        assertEquals(60, spec!!.period)
        assertEquals(8, spec.account.digits)
        assertEquals(OtpAlgorithm.SHA256, spec.account.algorithm)
        assertEquals("River Bank", spec.account.issuer)
        assertFalse(spec.steam)
    }

    @Test
    fun `reads the fields KeePassXC wrote before 2_6`() {
        val spec = OtpField.read(mapOf("TOTP Seed" to "KRUGS4ZANFZSAYJAONSWG4TFOQ", "TOTP Settings" to "45;8"))!!
        assertEquals(45, spec.period)
        assertEquals(8, spec.account.digits)
        val steam = OtpField.read(mapOf("TOTP Seed" to "KRUGS4ZANFZSAYJAONSWG4TFOQ", "TOTP Settings" to "30;S"))!!
        assertTrue(steam.steam)
    }

    @Test
    fun `no field or a broken one reads as none`() {
        assertNull(OtpField.read(mapOf("Title" to "x")))
        assertNull(OtpField.read(mapOf("otp" to "otpauth://totp/x?secret=not!base32")))
    }

    @Test
    fun `RFC 6238 vectors, SHA1 SHA256 SHA512, through the field`() {
        // RFC 6238 Appendix B, 8 digits. Seeds are the ASCII strings the RFC gives, in Base32.
        val sha1 = Base32.encode("12345678901234567890".toByteArray())
        val sha256 = Base32.encode("12345678901234567890123456789012".toByteArray())
        val sha512 = Base32.encode("1234567890123456789012345678901234567890123456789012345678901234".toByteArray())
        fun code(secret: String, alg: String, t: Long) =
            OtpField.code(OtpField.parse("otpauth://totp/x?secret=$secret&digits=8&algorithm=$alg")!!, t * 1000)
        assertEquals("94287082", code(sha1, "SHA1", 59))
        assertEquals("46119246", code(sha256, "SHA256", 59))
        assertEquals("90693936", code(sha512, "SHA512", 59))
        assertEquals("07081804", code(sha1, "SHA1", 1111111109))
        assertEquals("68084774", code(sha256, "SHA256", 1111111109))
        assertEquals("25091201", code(sha512, "SHA512", 1111111109))
        assertEquals("65353130", code(sha1, "SHA1", 20000000000))
    }

    @Test
    fun `Steam codes match KeePassXC's test values`() {
        val spec = OtpField.parse("otpauth://totp/Steam:x?secret=63BEDWCQZKTQWPESARIERL5DTTQFCJTK&issuer=Steam&encoder=steam")!!
        assertTrue(spec.steam)
        assertEquals(5, spec.account.digits)
        assertEquals("FR8RV", OtpField.code(spec, 1511200518L * 1000))
        assertEquals("9P3VP", OtpField.code(spec, 1511200714L * 1000))
    }

    @Test
    fun `link round-trips, Steam marked as KeePassXC marks it`() {
        val spec = OtpField.parse("otpauth://totp/Steam:x?secret=63BEDWCQZKTQWPESARIERL5DTTQFCJTK&issuer=Steam&encoder=steam")!!
        val again = OtpField.parse(OtpField.link(spec))!!
        assertTrue(again.steam)
        assertTrue(OtpField.same(spec, again))
        assertTrue(OtpField.link(spec).contains("encoder=steam"))
    }

    @Test
    fun `counter moves on by one`() {
        val spec = OtpField.parse("otpauth://hotp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&counter=5")!!
        assertTrue(spec.isCounter)
        assertEquals(6, OtpField.next(spec).account.counter)
        // RFC 4226 Appendix D: counter 5 -> 254676, counter 6 -> 287922.
        assertEquals("254676", OtpField.code(spec))
        assertEquals("287922", OtpField.code(OtpField.next(spec)))
    }

    @Test
    fun `spacing`() {
        assertEquals("123 456", OtpField.spaced("123456"))
        assertEquals("1234 5678", OtpField.spaced("12345678"))
        assertEquals("FR8RV", OtpField.spaced("FR8RV"))
    }
}
