package com.wanderwildwood.aikotoba.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.ok1cdj.kauth.core.WrongPasswordException
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.importer.Found
import com.wanderwildwood.aikotoba.importer.Import
import com.wanderwildwood.aikotoba.otp.OtpSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Codes found in a file, a link or a picture, before they become entries. Each is ticked
 * unless the vault already has it.
 */
@Composable
fun ReviewScreen(found: Found, existing: List<OtpSpec>, onBack: () -> Unit, onAdd: (List<OtpSpec>) -> Unit) {
    val duplicate = remember(found) { found.codes.map { Import.isDuplicate(it, existing) } }
    val chosen = remember(found) { found.codes.indices.map { !duplicate[it] }.toMutableStateList() }
    val count = chosen.count { it }
    Frame(
        title = stringResource(R.string.review_title),
        onBack = onBack,
        actions = { BarWord(stringResource(R.string.review_add), ready = count > 0) { onAdd(found.codes.filterIndexed { i, _ -> chosen[i] }) } },
    ) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            if (found.fromGoogle) item { Say(stringResource(R.string.import_google), bold = true) }
            if (found.codes.isEmpty()) item { Say(stringResource(R.string.import_nothing)) }
            found.codes.forEachIndexed { i, spec ->
                item(key = "c$i") {
                    val a = spec.account
                    val note = buildList {
                        if (a.issuer.isNotBlank() && a.name.isNotBlank()) add(a.name)
                        if (spec.steam) add(stringResource(R.string.review_steam))
                        else if (spec.isCounter) add(stringResource(R.string.review_counter))
                        else if (a.period != 30 || a.digits != 6 || a.algorithm.name != "SHA1") {
                            add(stringResource(R.string.review_unusual, a.digits.toString(), a.period.toString(), a.algorithm.name))
                        }
                        if (duplicate[i]) add(stringResource(R.string.review_duplicate))
                    }.joinToString(" · ")
                    PlainRow(
                        title = Import.title(spec),
                        note = note.ifBlank { null },
                        trailing = { CheckboxMMD(checked = chosen[i], onCheckedChange = null) },
                        onPress = { chosen[i] = !chosen[i] },
                    )
                }
            }
            if (found.failures.isNotEmpty()) {
                item {
                    Say(pluralStringResource(R.plurals.review_failures, found.failures.size, found.failures.size.toString()), Modifier.padding(top = 12.dp), bold = true)
                }
                found.failures.forEachIndexed { i, f -> item(key = "f$i") { Say(f) } }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    FootButton(pluralStringResource(R.plurals.review_add_count, count, count.toString()), enabled = count > 0) {
                        onAdd(found.codes.filterIndexed { i, _ -> chosen[i] })
                    }
                }
            }
        }
    }
}

/** kAuth's encrypted backup: its own master password opens it, here and nowhere else. */
@Composable
fun KauthScreen(blob: String, onBack: () -> Unit, onOpened: (Found) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun open() {
        if (busy || password.isEmpty()) return
        busy = true
        problem = null
        scope.launch {
            val chars = password.toCharArray()
            val r = withContext(Dispatchers.IO) {
                try {
                    Import.openKauth(blob, chars)
                } catch (e: WrongPasswordException) {
                    null
                } catch (e: Exception) {
                    Found(failures = listOf(e.message ?: e.javaClass.simpleName))
                } finally {
                    chars.fill(' ')
                }
            }
            busy = false
            if (r == null) problem = context.getString(R.string.kauth_wrong)
            else {
                password = ""
                onOpened(r)
            }
        }
    }

    Frame(title = stringResource(R.string.kauth_title), onBack = onBack) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item { Say(stringResource(R.string.kauth_what), Modifier.padding(top = 8.dp)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    PasswordField(password, { password = it; problem = null }, stringResource(R.string.kauth_password), ImeAction.Done, Modifier.focusRequester(focus)) { open() }
                    if (busy) Say(stringResource(R.string.unlock_opening))
                    problem?.let { Say(it, bold = true) }
                    Spacer(Modifier.height(12.dp))
                    FootButton(stringResource(R.string.kauth_open), Modifier.fillMaxWidth(), enabled = !busy) { open() }
                }
            }
        }
    }
}
