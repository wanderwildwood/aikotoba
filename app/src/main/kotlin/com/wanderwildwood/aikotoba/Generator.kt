package com.wanderwildwood.aikotoba

import android.content.Context
import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.ln

/**
 * New passwords and passphrases, every choice drawn with [SecureRandom].
 *
 * The strength shown is what can honestly be said of a generated secret: the number of equally
 * likely outcomes, in bits. That is exact for these, because the attacker is assumed to know how
 * it was made and only the random draws are secret. It says nothing about a password typed by
 * hand, which is why it is shown only here.
 */
object Generator {

    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "-_.!@#%+=?*&$^~"
    /** Letters and digits mistaken for each other on this screen and on paper. */
    const val LOOK_ALIKES = "lI1O0o"

    const val LENGTH = 20
    const val WORDS = 6

    private val random = SecureRandom()

    data class Chars(
        val length: Int = LENGTH,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidLookAlikes: Boolean = true,
    ) {
        /** The characters each place is drawn from. Empty when every set is switched off. */
        val alphabet: String
            get() = buildString {
                if (lower) append(LOWER)
                if (upper) append(UPPER)
                if (digits) append(DIGITS)
                if (symbols) append(SYMBOLS)
            }.let { all -> if (avoidLookAlikes) all.filterNot { it in LOOK_ALIKES } else all }
    }

    /**
     * [length] characters, each drawn uniformly from the chosen sets. No set is forced to appear:
     * forcing one makes the outcomes unequal and the strength figure wrong, and at these lengths
     * a set is missing so rarely that a second press is the simpler answer.
     */
    fun password(options: Chars = Chars()): String {
        val alphabet = options.alphabet
        require(alphabet.isNotEmpty()) { "no characters to choose from" }
        return String(CharArray(options.length) { alphabet[random.nextInt(alphabet.length)] })
    }

    /** [words] words from [list], each drawn uniformly, joined by [separator]. */
    fun passphrase(list: List<String>, words: Int = WORDS, separator: String = "-"): String {
        require(list.isNotEmpty())
        return (1..words).joinToString(separator) { list[random.nextInt(list.size)] }
    }

    fun bits(options: Chars): Double =
        options.alphabet.length.takeIf { it > 0 }?.let { options.length * log2(it.toDouble()) } ?: 0.0

    fun bits(listSize: Int, words: Int): Double = words * log2(listSize.toDouble())

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** The rough verdict beside the number: these thresholds are for an offline attack on a stolen file. */
    enum class Verdict { WEAK, FAIR, STRONG, VERY_STRONG }

    fun verdict(bits: Double): Verdict = when {
        bits < 50 -> Verdict.WEAK
        bits < 70 -> Verdict.FAIR
        bits < 100 -> Verdict.STRONG
        else -> Verdict.VERY_STRONG
    }

    fun roundBits(bits: Double): Int = floor(bits).toInt()

    @Volatile private var words: List<String>? = null

    /**
     * The EFF's large word list (7,776 words, CC BY 3.0): every word distinct, none a prefix
     * trap, chosen to be typed and remembered. Read from the app once.
     */
    fun wordList(context: Context): List<String> = words ?: synchronized(this) {
        words ?: context.assets.open("eff_large_wordlist.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.also { words = it }
    }
}
