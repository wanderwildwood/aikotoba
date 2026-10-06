package com.wanderwildwood.aikotoba.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.aikotoba.Clipboard
import com.wanderwildwood.aikotoba.Generator
import com.wanderwildwood.aikotoba.Prefs
import com.wanderwildwood.aikotoba.R

/**
 * A new password or passphrase, with how strong it is said plainly. Reached from an entry's
 * password field ([onUse] puts it there) and on its own (copy it, and the clipboard empties
 * itself as after any copy).
 */
@Composable
fun GeneratorScreen(prefs: Prefs, onBack: () -> Unit, onUse: ((String) -> Unit)?) {
    val context = LocalContext.current
    var chars by remember { mutableStateOf(prefs.generator) }
    var words by remember { mutableStateOf(prefs.genWords) }
    var count by remember { mutableIntStateOf(prefs.genWordCount) }
    var separator by remember { mutableStateOf(prefs.genSeparator) }
    val list = remember { Generator.wordList(context) }
    var turn by remember { mutableIntStateOf(0) }
    val value = remember(chars, words, count, separator, turn) {
        if (words) Generator.passphrase(list, count, separator)
        else if (chars.alphabet.isEmpty()) "" else Generator.password(chars)
    }
    val bits = if (words) Generator.bits(list.size, count) else Generator.bits(chars)

    fun setChars(c: Generator.Chars) {
        // At least one set stays on: an empty alphabet makes nothing.
        if (c.alphabet.isEmpty()) return
        chars = c
        prefs.generator = c
    }

    androidx.activity.compose.BackHandler(onBack = onBack)
    Frame(
        title = stringResource(R.string.gen_title),
        onBack = onBack,
        actions = { if (onUse != null) BarWord(stringResource(R.string.gen_use), ready = value.isNotEmpty()) { onUse(value) } },
    ) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextMMD(text = value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    TextMMD(
                        text = stringResource(
                            R.string.gen_strength,
                            Generator.roundBits(bits).toString(),
                            stringResource(
                                when (Generator.verdict(bits)) {
                                    Generator.Verdict.WEAK -> R.string.gen_weak
                                    Generator.Verdict.FAIR -> R.string.gen_fair
                                    Generator.Verdict.STRONG -> R.string.gen_strong
                                    Generator.Verdict.VERY_STRONG -> R.string.gen_very_strong
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        FootButton(stringResource(R.string.gen_again), Modifier.weight(1f)) { turn++ }
                        Spacer(Modifier.width(8.dp))
                        val label = stringResource(R.string.label_password)
                        FootButton(stringResource(R.string.cd_copy), Modifier.weight(1f), enabled = value.isNotEmpty()) {
                            Clipboard.copy(context, label, value)
                        }
                    }
                }
                DottedRule()
            }
            item {
                PlainRow(stringResource(R.string.gen_kind), stringResource(if (words) R.string.gen_kind_words else R.string.gen_kind_chars)) {
                    words = !words
                    prefs.genWords = words
                }
            }
            if (words) {
                item {
                    PlainRow(stringResource(R.string.gen_words), count.toString()) {
                        count = if (count >= 10) 4 else count + 1
                        prefs.genWordCount = count
                    }
                }
                item {
                    val names = mapOf("-" to R.string.gen_sep_dash, " " to R.string.gen_sep_space, "." to R.string.gen_sep_dot, "_" to R.string.gen_sep_underscore, "" to R.string.gen_sep_none)
                    PlainRow(stringResource(R.string.gen_separator), stringResource(names[separator] ?: R.string.gen_sep_dash)) {
                        val keys = names.keys.toList()
                        separator = keys[(keys.indexOf(separator) + 1) % keys.size]
                        prefs.genSeparator = separator
                    }
                }
                item { Say(stringResource(R.string.gen_words_note)) }
            } else {
                item {
                    PlainRow(stringResource(R.string.gen_length), chars.length.toString()) {
                        val steps = listOf(12, 16, 20, 24, 32, 40, 64)
                        setChars(chars.copy(length = steps.firstOrNull { it > chars.length } ?: steps.first()))
                    }
                }
                item { Toggle(stringResource(R.string.gen_lower), chars.lower) { setChars(chars.copy(lower = it)) } }
                item { Toggle(stringResource(R.string.gen_upper), chars.upper) { setChars(chars.copy(upper = it)) } }
                item { Toggle(stringResource(R.string.gen_digits), chars.digits) { setChars(chars.copy(digits = it)) } }
                item { Toggle(stringResource(R.string.gen_symbols), chars.symbols) { setChars(chars.copy(symbols = it)) } }
                item { Toggle(stringResource(R.string.gen_avoid), chars.avoidLookAlikes) { setChars(chars.copy(avoidLookAlikes = it)) } }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    PlainRow(title = label, trailing = { SwitchMMD(checked = on, onCheckedChange = null) }, onPress = { onChange(!on) })
}
