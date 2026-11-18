package com.wanderwildwood.aikotoba.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
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
}
