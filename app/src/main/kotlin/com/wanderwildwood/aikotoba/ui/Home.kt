package com.wanderwildwood.aikotoba.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val q = if (searching) query.trim() else ""
    val items = open.items.filter { item ->
        (!codes || item.otp != null) && (q.isEmpty() || item.findable(codes).any { it.contains(q, ignoreCase = true) })
    }
    BackHandler(enabled = searching) { searching = false; query = "" }
    // One clock for the codes and the seconds beside the magnifier, so the two never disagree.
    val now = if (codes) rememberNow() else 0L
    // The step the pinned seconds count: thirty when any code uses it (nearly every site does),
    // else the one step they all share. A code on another step says its own on its row.
    val timed = open.items.mapNotNull { it.otp }.filter { !it.isCounter }
    val pinned = when {
        timed.isEmpty() -> null
        timed.any { it.period == 30 } -> 30
        else -> timed.map { it.period }.distinct().singleOrNull() ?: 30
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
        // The search lives on the same line as Codes and All, so the list keeps every row it had:
        // the magnifier opens a field beside them, and what is typed narrows the list at once.
        // This line stays while the list scrolls, so the seconds until the codes change sit
        // here, before the magnifier, and are in view from the foot of a long list too.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp)) {
            Choice(stringResource(R.string.home_codes), codes) { codes = true; prefs.showCodes = true }
            Choice(stringResource(R.string.home_all), !codes) { codes = false; prefs.showCodes = false }
            if (searching) {
                SearchField(query, { query = it }, Modifier.weight(1f).padding(start = 8.dp))
                BarButton(Icons.Close, stringResource(R.string.cd_close_search)) { searching = false; query = "" }
            } else {
                Spacer(Modifier.weight(1f))
                if (codes && pinned != null) {
                    TextMMD(
                        text = stringResource(R.string.code_seconds, Otp.secondsRemaining(pinned, now).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    )
                }
                BarButton(Icons.Search, stringResource(R.string.cd_search)) { searching = true }
            }
        }
        val none = if (q.isNotEmpty() && items.isEmpty()) stringResource(R.string.home_search_none) else null
        if (codes) CodeList(items, onEntry, now, pinned, prefs.vibrateOnChange, empty = none ?: if (open.items.any { it.otp != null }) null else stringResource(R.string.home_no_codes))
        else EntryList(items, onEntry, empty = none ?: if (open.items.isEmpty()) stringResource(R.string.home_no_entries) else null)
    }
}

/** What a search is matched against: on the codes, the issuer and account the code names too. */
private fun Item.findable(codes: Boolean): List<String> =
    if (codes) listOfNotNull(title, username, otp?.account?.issuer, otp?.account?.name)
    else listOf(title, username, url, group)

/** The search, typed in place on the line it was opened from; the keyboard comes up with it. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = modifier.focusRequester(focus).textActions(),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) TextMMD(text = stringResource(R.string.home_search), style = MaterialTheme.typography.bodyLarge, color = Color(0xFF8A8A8A))
                inner()
            }
        },
    )
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

/**
 * One light tick each time the codes on screen change: every thirty seconds for most, and on
 * its own step for a code that has one, a single tick when several change together. Only while
 * this list is in front; coming back to it starts counting afresh, so returning never ticks.
 * The phone's own haptic, so the system's touch-vibration setting decides whether it is felt.
 */
@Composable
private fun TickOnChange(enabled: Boolean, periods: List<Int>) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, periods) {
        if (!enabled || periods.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            fun steps(t: Long) = periods.map { t / 1000 / it }
            var last = steps(System.currentTimeMillis())
            while (true) {
                val t = System.currentTimeMillis()
                delay(1000 - t % 1000 + 5)
                val next = steps(System.currentTimeMillis())
                if (next != last) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                last = next
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
 * Every code, each pressed to copy. The seconds left are said once, on the line above the
 * list, for the [pinned] step nearly every site uses; a code on another step says its own
 * here. On this panel one changing number is better than a column of them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeList(items: List<Item>, onEntry: (UUID) -> Unit, now: Long, pinned: Int?, vibrate: Boolean, empty: String?) {
    val context = LocalContext.current
    TickOnChange(vibrate, items.mapNotNull { it.otp }.filter { !it.isCounter }.map { it.period }.distinct().sorted())
    LazyColumnMMD(Modifier.fillMaxSize()) {
        if (empty != null) item { Say(empty, Modifier.padding(top = 8.dp)) }
        if (items.any { it.otp != null }) {
            item(key = "hint") {
                TextMMD(
                    text = stringResource(R.string.codes_press_to_copy),
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
                            spec.period != pinned -> stringResource(R.string.code_seconds, Otp.secondsRemaining(spec.period, now).toString())
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
