package com.wanderwildwood.aikotoba.vault

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KdfTest {
    @Test
    fun `the costs real vaults use are within a phone`() {
        // KeePassXC's default, Passwords' own, kAuth's default, and a heavy one tuned on a computer.
        assertTrue(NativeKdf.withinPhone(64 * 1024, 10, 2))
        assertTrue(NativeKdf.withinPhone(64 * 1024, 2, 2))
        assertTrue(NativeKdf.withinPhone(32 * 1024, 3, 1))
        assertTrue(NativeKdf.withinPhone(1L shl 20, 16, 8))
    }

    @Test
    fun `a file asking for hours or gigabytes is refused`() {
        assertFalse(NativeKdf.withinPhone(64 * 1024, Int.MAX_VALUE.toLong(), 1))
        assertFalse(NativeKdf.withinPhone(Int.MAX_VALUE.toLong(), 1, 1))
        assertFalse(NativeKdf.withinPhone(2L shl 20, 1, 1))
        assertFalse(NativeKdf.withinPhone(64 * 1024, 3, 100_000))
        assertFalse(NativeKdf.withinPhone(64 * 1024, 0, 1))
        assertFalse(NativeKdf.withinPhone(0, 3, 1))
    }
}
