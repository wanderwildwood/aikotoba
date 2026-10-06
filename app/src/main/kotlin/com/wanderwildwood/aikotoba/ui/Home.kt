package com.wanderwildwood.aikotoba.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.ok1cdj.kauth.core.Otp
import com.wanderwildwood.aikotoba.Clipboard
import com.wanderwildwood.aikotoba.Prefs
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.otp.OtpField
import com.wanderwildwood.aikotoba.vault.Item
import com.wanderwildwood.aikotoba.vault.Session
import java.util.UUID
import kotlinx.coroutines.delay
import com.wanderwildwood.aikotoba.vault.Session.Open as OpenVault

/**
 * The open vault: its codes, or everything in it. Unlocking opens on whichever was used last,
 * so someone who comes here for a code finds the codes.
 */
@Composable
fun HomeScreen(
    open: OpenVault,
    prefs: Prefs,
    onEntry: (UUID) -> Unit,
    onNewEntry: () -> Unit,
    onNewCode: () -> Unit,
    onRetrySave: () -> Unit,
    onSaveTo: (android.net.Uri) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    var codes by remember { mutableStateOf(prefs.showCodes) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val items = open.items.filter { item ->
        (!codes || item.otp != null) && (q.isEmpty() || listOf(item.title, item.username, item.url, item.group).any { it.contains(q, ignoreCase = true) })
    }
    Frame(
        title = stringResource(R.string.app_name),
        actions = {
            BarButton(Icons.Lock, stringResource(R.string.cd_lock)) { Session.lock() }
            BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
            BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout)
        },
        floating = {
            FloatingActionButtonMMD(
                onClick = if (codes) onNewCode else onNewEntry,
                shape = androidx.compose.foundation.shape.CircleShape,
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ) {
                Icon(Icons.Add, contentDescription = stringResource(if (codes) R.string.cd_new_code else R.string.cd_new_entry), modifier = Modifier.size(30.dp))
            }
        },
    ) {
        val saveTo = rememberFileMaker(onSaveTo)
        // A vault that came read-only (a server's file through Files) says so from the start,
        // and offers where its changes can go, rather than failing at the first save.
        if (!open.writable || open.unsaved) {
            TextMMD(
                text = stringResource(
                    when {
                        !open.writable && open.unsaved -> R.string.unsaved_read_only
                        !open.writable -> R.string.read_only_strip
                        else -> R.string.unsaved_retry
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .clickable { if (open.writable) onRetrySave() else saveTo(open.name.ifBlank { "Passwords.kdbx" }) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDividerMMD()
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
            Choice(stringResource(R.string.home_codes), codes) { codes = true; prefs.showCodes = true }
            Choice(stringResource(R.string.home_all), !codes) { codes = false; prefs.showCodes = false }
            Spacer(Modifier.weight(1f))
        }
        TextFieldMMD(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { TextMMD(text = stringResource(R.string.home_search), style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            trailingIcon = { if (query.isNotEmpty()) BarButton(Icons.Close, stringResource(R.string.cd_clear)) { query = "" } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (codes) CodeList(items, onEntry, empty = if (open.items.any { it.otp != null }) null else stringResource(R.string.home_no_codes))
        else EntryList(items, onEntry, empty = if (open.items.isEmpty()) stringResource(R.string.home_no_entries) else null)
    }
}

@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
    TextMMD(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (chosen) FontWeight.Bold else null,
        textDecoration = if (chosen) TextDecoration.Underline else null,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
private fun EntryList(items: List<Item>, onEntry: (UUID) -> Unit, empty: String?) {
    LazyColumnMMD(Modifier.fillMaxSize()) {
        if (empty != null) item { Say(empty, Modifier.padding(top = 8.dp)) }
        for (item in items) {
            item(key = item.uuid.toString()) {
                PlainRow(
                    title = item.label,
                    note = listOf(item.username, item.group).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    noteLines = 1,
                    onPress = { onEntry(item.uuid) },
                )
            }
        }
    }
}

/** The clock the codes are read against, ticking once a second while the list is up. */
@Composable
internal fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(1000 - t % 1000)
        }
    }
    return now
}

/**
 * Every code, each pressed to copy. The seconds left are said once, at the top, for the
 * thirty-second codes nearly every site uses; a code on another step says its own. On this
 * panel one changing number is better than a column of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeList(items: List<Item>, onEntry: (UUID) -> Unit, empty: String?) {
    val context = LocalContext.current
    val now = rememberNow()
    LazyColumnMMD(Modifier.fillMaxSize()) {
        if (empty != null) item { Say(empty, Modifier.padding(top = 8.dp)) }
        if (items.any { it.otp?.let { s -> !s.isCounter && s.period == 30 } == true }) {
            item(key = "clock") {
                TextMMD(
                    text = stringResource(R.string.codes_change_in, Otp.secondsRemaining(30, now).toString()),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
        }
        for (item in items) {
            val spec = item.otp ?: continue
            item(key = item.uuid.toString()) {
                val code = remember(spec, if (spec.isCounter) 0L else now / 1000 / spec.period) {
                    runCatching { OtpField.code(spec, now) }.getOrNull()
                }
                val copyLabel = stringResource(R.string.label_code)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (code != null) Clipboard.copy(context, copyLabel, code) },
                            onLongClick = { onEntry(item.uuid) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        TextMMD(text = item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val note = item.username.ifBlank { spec.account.name }
                        if (note.isNotBlank() && note != item.label) {
                            TextMMD(text = note, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        TextMMD(
                            text = code?.let(OtpField::spaced) ?: stringResource(R.string.code_unreadable),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val own = when {
                            spec.isCounter -> stringResource(R.string.code_counter)
                            spec.period != 30 -> stringResource(R.string.code_seconds, Otp.secondsRemaining(spec.period, now).toString())
                            else -> null
                        }
                        if (own != null) TextMMD(text = own, style = MaterialTheme.typography.labelSmall)
                    }
                }
                DottedRule()
            }
        }
        item(key = "foot") { Spacer(Modifier.padding(bottom = 72.dp)) }
    }
}
