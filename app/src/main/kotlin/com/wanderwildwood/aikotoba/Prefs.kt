package com.wanderwildwood.aikotoba

import android.content.Context
import android.net.Uri

/**
 * What the app remembers between opens: which file is the vault and how it likes to be shown.
 * No password, no key, nothing from inside the vault.
 */
class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var vault: Uri?
        get() = p.getString("vault", null)?.let(Uri::parse)
        set(value) { p.edit().putString("vault", value?.toString()).apply() }

    /** Minutes away from the app before it locks itself; 0 locks on leaving. */
    var lockMinutes: Int
        get() = p.getInt("lockMinutes", 5)
        set(value) { p.edit().putInt("lockMinutes", value).apply() }

    /** Which list opens after unlocking: the codes, or the whole vault. The last one used. */
    var showCodes: Boolean
        get() = p.getBoolean("showCodes", true)
        set(value) { p.edit().putBoolean("showCodes", value).apply() }

    /** Offer a sign-in picked by hand for an app the next time that app asks. */
    var rememberForApp: Boolean
        get() = p.getBoolean("rememberForApp", true)
        set(value) { p.edit().putBoolean("rememberForApp", value).apply() }

    /** The generator's last settings, so it makes the same kind of password next time. */
    var generator: Generator.Chars
        get() = Generator.Chars(
            length = p.getInt("genLength", Generator.LENGTH),
            lower = p.getBoolean("genLower", true),
            upper = p.getBoolean("genUpper", true),
            digits = p.getBoolean("genDigits", true),
            symbols = p.getBoolean("genSymbols", true),
            avoidLookAlikes = p.getBoolean("genAvoid", true),
        )
        set(v) {
            p.edit().putInt("genLength", v.length).putBoolean("genLower", v.lower).putBoolean("genUpper", v.upper)
                .putBoolean("genDigits", v.digits).putBoolean("genSymbols", v.symbols).putBoolean("genAvoid", v.avoidLookAlikes).apply()
        }

    var genWords: Boolean
        get() = p.getBoolean("genWordsMode", false)
        set(value) { p.edit().putBoolean("genWordsMode", value).apply() }

    var genWordCount: Int
        get() = p.getInt("genWordCount", Generator.WORDS)
        set(value) { p.edit().putInt("genWordCount", value).apply() }

    var genSeparator: String
        get() = p.getString("genSeparator", "-") ?: "-"
        set(value) { p.edit().putString("genSeparator", value).apply() }

    companion object {
        val LOCK_CHOICES = listOf(0, 1, 5, 15)
    }
}
