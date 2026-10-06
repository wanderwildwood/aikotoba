package com.wanderwildwood.aikotoba

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GeneratorTest {

    @Test
    fun `length and alphabet are what was asked`() {
        val o = Generator.Chars(length = 32, symbols = false)
        repeat(200) {
            val p = Generator.password(o)
            assertEquals(32, p.length)
            assertTrue(p.all { it in o.alphabet })
        }
    }

    @Test
    fun `look-alikes are left out only when asked`() {
        assertFalse(Generator.Chars().alphabet.any { it in Generator.LOOK_ALIKES })
        assertTrue(Generator.Chars(avoidLookAlikes = false).alphabet.contains('0'))
    }

    @Test
    fun `every character turns up`() {
        val o = Generator.Chars(length = 64)
        val seen = HashSet<Char>()
        repeat(400) { seen += Generator.password(o).toSet() }
        assertEquals(o.alphabet.toSet(), seen)
    }

    @Test
    fun `strength is the count of equally likely outcomes`() {
        // 20 places over 26 letters: 20 * log2(26) = 94.0 bits.
        assertEquals(94, Generator.roundBits(Generator.bits(Generator.Chars(20, upper = false, digits = false, symbols = false, avoidLookAlikes = false))))
        // Six words from 7776: 6 * log2(7776) = 77.5 bits.
        assertEquals(77, Generator.roundBits(Generator.bits(7776, 6)))
        assertEquals(Generator.Verdict.WEAK, Generator.verdict(40.0))
        assertEquals(Generator.Verdict.VERY_STRONG, Generator.verdict(120.0))
    }

    @Test
    fun `passphrase from the shipped word list`() {
        val list = File("src/main/assets/eff_large_wordlist.txt").readLines().filter { it.isNotBlank() }
        assertEquals(7776, list.size)
        assertEquals(7776, list.toSet().size)
        val p = Generator.passphrase(list, 5, ".")
        assertEquals(5, p.split('.').size)
        assertTrue(p.split('.').all { it in list })
    }
}
