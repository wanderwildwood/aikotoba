package com.wanderwildwood.aikotoba.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class VaultFileTest {
    @Test
    fun `a stream is read whole up to the limit`() {
        val bytes = ByteArray(200_000) { it.toByte() }
        assertEquals(bytes.toList(), VaultFile.readAtMost(ByteArrayInputStream(bytes), 300_000).toList())
    }

    @Test
    fun `an endless stream stops one past the limit, so it is seen as too big`() {
        val endless = object : InputStream() {
            override fun read(): Int = 7
        }
        assertEquals(100_001, VaultFile.readAtMost(endless, 100_001).size)
    }

    private fun keptFor(dir: File, uri: String): File {
        dir.mkdirs()
        File(dir, "previous.kdbx").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "previous.from").writeText(uri)
        return dir
    }

    @Test
    fun `closing a vault forgets the copy kept for it`(@TempDir root: File) {
        val dir = keptFor(File(root, "previous"), "content://files/ada.kdbx")
        VaultFile.forgetPreviousIn(dir, "content://files/ada.kdbx")
        assertFalse(dir.exists())
    }

    @Test
    fun `closing a vault keeps the copy kept for another vault`(@TempDir root: File) {
        val dir = keptFor(File(root, "previous"), "content://files/ada.kdbx")
        VaultFile.forgetPreviousIn(dir, "content://files/tomas.kdbx")
        assertTrue(File(dir, "previous.kdbx").isFile)
        assertEquals("content://files/ada.kdbx", File(dir, "previous.from").readText())
    }
}
