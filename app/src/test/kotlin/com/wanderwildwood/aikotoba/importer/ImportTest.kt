package com.wanderwildwood.aikotoba.importer

import com.ok1cdj.kauth.core.OtpAccount
import com.ok1cdj.kauth.core.OtpType
import com.ok1cdj.kauth.core.TestArgon2
import com.ok1cdj.kauth.core.VaultCrypto
import com.ok1cdj.kauth.core.VaultModel
import com.ok1cdj.kauth.core.WrongPasswordException
import com.wanderwildwood.aikotoba.otp.OtpField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportTest {
    init { TestArgon2.install() }

    private companion object {
        // Published test secrets: RFC 4226's, a made-up one, and KeePassXC's Steam test key.
        const val RFC = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // gitleaks:allow (test vector)
        const val OLD = "KRUGS4ZANFZSAYJAONSWG4TFOQ" // gitleaks:allow (test vector)
        const val STEAM = "63BEDWCQZKTQWPESARIERL5DTTQFCJTK" // gitleaks:allow (test vector)
    }

    private val accounts = listOf(
        OtpAccount("Example Mail", "ada.whitlock@example.org", "JBSWY3DPEHPK3PXP"),
        OtpAccount("River Bank", "tomas.reyes", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8, period = 60),  // gitleaks:allow (test vector)
        OtpAccount("Old Token", "ada", "KRUGS4ZANFZSAYJAONSWG4TFOQ", type = OtpType.HOTP, counter = 7),  // gitleaks:allow (test vector)
    )

    @Test
    fun `kAuth backup, made the way kAuth makes it, opens with its password`() {
        val fast = VaultCrypto.KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1)
        val blob = VaultCrypto.encrypt(VaultModel.serialize(accounts).toByteArray(), "kauth pass".toCharArray(), fast)
        val r = Import.recognise(blob)
        assertTrue(r is Recognised.KauthBackup)
        val found = Import.openKauth((r as Recognised.KauthBackup).blob, "kauth pass".toCharArray())
        assertEquals(accounts, found.codes.map { it.account })
        assertThrows(WrongPasswordException::class.java) { Import.openKauth(blob, "wrong".toCharArray()) }
    }

    @Test
    fun `kAuth's exported list of links`() {
        val text = com.ok1cdj.kauth.core.OtpUri.buildAll(accounts)
        val r = Import.recognise(text) as Recognised.Codes
        assertEquals(accounts, r.found.codes.map { it.account })
        assertFalse(r.found.fromGoogle)
    }

    @Test
    fun `links inside other text, and a broken one named without its secret`() {
        val r = Import.links("Set up: otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&issuer=A. And otpauth://totp/C:d?secret=!!!SECRETISH")
        assertEquals(1, r.codes.size)
        assertEquals(1, r.failures.size)
        assertFalse(r.failures.first().contains("SECRETISH"))
    }

    @Test
    fun `Aegis plain export, with what it cannot take named`() {
        val json = """
        {"version":1,"header":{"slots":null,"params":null},"db":{"version":2,"entries":[
          {"type":"totp","uuid":"1","name":"ada.whitlock@example.org","issuer":"Example Mail","note":"","icon":null,
           "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}},
          {"type":"totp","uuid":"2","name":"tomas.reyes","issuer":"River Bank","icon":null,
           "info":{"secret":"$RFC","algo":"SHA256","digits":8,"period":60}},
          {"type":"hotp","uuid":"3","name":"ada","issuer":"Old Token","info":{"secret":"$OLD","algo":"SHA1","digits":6,"counter":7}},
          {"type":"steam","uuid":"4","name":"ada_w","issuer":"Steam","info":{"secret":"$STEAM","algo":"SHA1","digits":5,"period":30}},
          {"type":"yandex","uuid":"5","name":"x","issuer":"Yandex","info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA256","digits":8,"period":30,"pin":"1234"}}
        ]}}
        """.trimIndent()
        val r = Import.recognise(json) as Recognised.Codes
        assertEquals(4, r.found.codes.size)
        assertEquals(60, r.found.codes[1].period)
        assertEquals(8, r.found.codes[1].account.digits)
        assertEquals(7, r.found.codes[2].account.counter)
        assertTrue(r.found.codes[3].steam)
        assertEquals("FR8RV", OtpField.code(r.found.codes[3], 1511200518L * 1000))
        assertEquals(1, r.found.failures.size)
    }

    @Test
    fun `an Aegis export with a password is recognised and refused plainly`() {
        val json = """{"version":1,"header":{"slots":[{"type":1}],"params":{"nonce":"x","tag":"y"}},"db":"AAAA"}"""
        assertEquals(Recognised.AegisEncrypted, Import.recognise(json))
    }

    @Test
    fun `a Google Authenticator transfer is marked as one`() {
        // One account, issuer "Example", name "ada", secret bytes "Hello!", made by hand from the
        // transfer format's field numbers.
        val secret = "Hello!".toByteArray()
        val params = byteArrayOf(0x0A, secret.size.toByte()) + secret +
            byteArrayOf(0x12, 3) + "ada".toByteArray() + byteArrayOf(0x1A, 7) + "Example".toByteArray() +
            byteArrayOf(0x20, 1, 0x28, 1, 0x30, 2)
        val payload = byteArrayOf(0x0A, params.size.toByte()) + params
        val link = "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(payload), "UTF-8")
        val r = Import.recognise(link) as Recognised.Codes
        assertTrue(r.found.fromGoogle)
        assertEquals("Example", r.found.codes.single().account.issuer)
        assertEquals(30, r.found.codes.single().period)
    }

    @Test
    fun `duplicates are the same secret counted the same way`() {
        val a = OtpField.parse("otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP")!!
        val b = OtpField.parse("otpauth://totp/Other:c?secret=jbswy3dpehpk3pxp&period=60")!!
        assertTrue(Import.isDuplicate(b, listOf(a)))
    }
}
