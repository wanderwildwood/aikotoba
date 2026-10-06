package com.wanderwildwood.aikotoba.vault

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.errors.CryptoError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Against a vault made by pykeepass (tools/make-test-vault.py --light), a KDBX implementation
 * independent of kotpass, so reading it here is a check of compatibility and not of agreement
 * with itself. The JVM has no native Argon2, so kotpass's own runs these.
 */
class VaultTest {
    private val credentials = Credentials.from(EncryptedValue.fromString("test-only"))
    private fun bytes() = javaClass.getResourceAsStream("/light.kdbx")!!.readBytes()
    private fun open() = KeePassDatabase.decode(ByteArrayInputStream(bytes()), credentials)
    private fun roundTrip(db: KeePassDatabase): KeePassDatabase {
        val out = ByteArrayOutputStream()
        db.encode(out)
        return KeePassDatabase.decode(ByteArrayInputStream(out.toByteArray()), credentials)
    }

    @Test
    fun `reads every entry, its group and its code`() {
        val items = Vault.items(open())
        assertEquals(listOf("Example Mail", "Hill Forum", "Library", "River Bank", "Steam", "Team Wiki"), items.map { it.title })
        val wiki = items.first { it.title == "Team Wiki" }
        assertEquals("Work", wiki.group)
        assertNotNull(wiki.otp)
        assertNotNull(items.first { it.title == "Hill Forum" }.otp)
        assertTrue(items.first { it.title == "Steam" }.otp!!.steam)
        assertEquals(null, items.first { it.title == "Library" }.otp)
        assertEquals("correct-horse-battery", Vault.password(open(), items.first { it.title == "Example Mail" }.uuid))
    }

    @Test
    fun `a wrong password is told apart`() {
        assertThrows(CryptoError.InvalidKey::class.java) {
            KeePassDatabase.decode(ByteArrayInputStream(bytes()), Credentials.from(EncryptedValue.fromString("nope")))
        }
    }

    @Test
    fun `add, edit, code, delete survive a save and a reopen`() {
        var db = open()
        val (added, id) = Vault.add(db, Draft("Ada's Garden", "ada", "s3cret", "garden.example.org", "notes", ""))
        db = added
        val forum = Vault.items(db).first { it.title == "Hill Forum" }
        db = Vault.update(db, forum.uuid, Draft("Hill Forum", "ada2", "new-pass", forum.url, "n", forum.otp?.let { com.wanderwildwood.aikotoba.otp.OtpField.link(it) }.orEmpty()))
        val mail = Vault.items(db).first { it.title == "Example Mail" }
        db = Vault.delete(db, mail.uuid)

        val back = roundTrip(db)
        val items = Vault.items(back)
        assertTrue(items.any { it.uuid == id && it.username == "ada" })
        assertEquals("s3cret", Vault.password(back, id))
        val f = items.first { it.title == "Hill Forum" }
        assertEquals("ada2", f.username)
        assertEquals("new-pass", Vault.password(back, f.uuid))
        // The old legacy fields become one otp field, the codes the same.
        assertTrue(f.extra.containsKey("otp"))
        assertFalse(f.extra.containsKey("TOTP Seed"))
        // The change keeps the old version in the entry's history.
        assertEquals(1, Vault.find(back, f.uuid)!!.history.size)
        // Deleted: out of the list, into the recycle bin.
        assertFalse(items.any { it.uuid == mail.uuid })
        assertNotNull(Vault.find(back, mail.uuid))
    }

    @Test
    fun `fields this app does not show are kept`() {
        var db = open()
        val wiki = Vault.items(db).first { it.title == "Team Wiki" }
        db = Vault.update(db, wiki.uuid, Draft("Team Wiki", wiki.username, "p", wiki.url, "", ""))
        val back = roundTrip(db)
        val again = Vault.items(back).first { it.uuid == wiki.uuid }
        assertEquals(null, again.otp)
        assertEquals("p", Vault.password(back, wiki.uuid))
    }
}
