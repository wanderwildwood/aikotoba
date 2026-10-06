package com.wanderwildwood.aikotoba.otp

import com.ok1cdj.kauth.core.Base32
import com.ok1cdj.kauth.core.Otp
import com.ok1cdj.kauth.core.OtpAccount
import com.ok1cdj.kauth.core.OtpAlgorithm
import com.ok1cdj.kauth.core.OtpType
import com.ok1cdj.kauth.core.OtpUri
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A sign-in's second step, as one entry of a KeePass file carries it.
 *
 * [steam] is Steam Guard's five-letter code, which shares TOTP's clock and HMAC-SHA1 and
 * differs only in how the number is written out.
 */
data class OtpSpec(val account: OtpAccount, val steam: Boolean = false) {
    val period: Int get() = account.period
    val isCounter: Boolean get() = account.type == OtpType.HOTP
}

/**
 * Reading and writing the field KeePassXC and KeePassDX keep a code in.
 *
 * The current form is one field, `otp`, holding an `otpauth://` link. Older KeePassXC files
 * (before 2.6) split it into `TOTP Seed` and `TOTP Settings` ("30;6", or "30;S" for Steam); those
 * are read, and the next save of that entry writes `otp` instead, as KeePassXC itself does.
 */
object OtpField {

    const val KEY = "otp"
    const val LEGACY_SEED = "TOTP Seed"
    const val LEGACY_SETTINGS = "TOTP Settings"

    /** The code an entry carries, from whichever field it is in, or null for none or unreadable. */
    fun read(fields: Map<String, String>): OtpSpec? {
        fields[KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let { return parse(it) }
        val seed = fields[LEGACY_SEED]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (OtpUri.isOtpAuth(seed)) return parse(seed)
        val secret = seed.replace(" ", "").uppercase()
        if (!Base32.isValid(secret)) return null
        val settings = fields[LEGACY_SETTINGS]?.split(';').orEmpty()
        val period = settings.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        val size = settings.getOrNull(1)?.trim().orEmpty()
        val steam = size.equals("S", ignoreCase = true)
        val digits = if (steam) 5 else size.toIntOrNull()?.takeIf { it in 1..9 } ?: 6
        return OtpSpec(OtpAccount(issuer = "", name = "", secret = secret, digits = digits, period = period), steam)
    }

    /** One `otpauth://` link, or null when it is not one this can make codes from. */
    fun parse(link: String): OtpSpec? = runCatching {
        val account = OtpUri.parse(link)
        val steam = query(link)["encoder"].equals("steam", ignoreCase = true)
        OtpSpec(if (steam) account.copy(digits = 5) else account, steam)
    }.getOrNull()

    /** The link to keep in an entry's `otp` field. Steam's is marked the way KeePassXC marks it. */
    fun link(spec: OtpSpec): String {
        val base = OtpUri.build(spec.account)
        return if (spec.steam) "$base&encoder=steam" else base
    }

    /** The code at [timeMillis], spaced for reading aloud ("123 456"). */
    fun code(spec: OtpSpec, timeMillis: Long = System.currentTimeMillis()): String {
        val raw = if (spec.steam) {
            steam(Base32.decode(spec.account.secret), Math.floorDiv(timeMillis / 1000, spec.period.toLong()))
        } else {
            Otp.code(spec.account, timeMillis)
        }
        return raw
    }

    /** "123456" as "123 456", "12345678" as "1234 5678"; anything else as it is. */
    fun spaced(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }

    /** The same account with its counter moved on one, for a counter-based code. */
    fun next(spec: OtpSpec): OtpSpec = spec.copy(account = spec.account.copy(counter = spec.account.counter + 1))

    /** Two specs make the same codes: same secret, same way of counting. */
    fun same(a: OtpSpec, b: OtpSpec): Boolean =
        a.account.secret.trimEnd('=').equals(b.account.secret.trimEnd('='), ignoreCase = true) &&
            a.account.type == b.account.type && a.steam == b.steam

    private const val STEAM_CHARS = "23456789BCDFGHJKMNPQRTVWXY"

    /**
     * Steam Guard: RFC 4226's HMAC-SHA1 and dynamic truncation, then the 31-bit number written
     * as five characters of Steam's alphabet, least significant first (as KeePassXC and Aegis do).
     */
    internal fun steam(key: ByteArray, counter: Long): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance(OtpAlgorithm.SHA1.macName)
        mac.init(SecretKeySpec(key, OtpAlgorithm.SHA1.macName))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        var value = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val out = StringBuilder(5)
        repeat(5) {
            out.append(STEAM_CHARS[value % STEAM_CHARS.length])
            value /= STEAM_CHARS.length
        }
        return out.toString()
    }

    private fun query(link: String): Map<String, String> {
        val q = link.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else pair.substring(0, eq).lowercase() to
                runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))
        }.toMap()
    }
}
