package com.wanderwildwood.aikotoba.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.ok1cdj.kauth.core.Otp
import com.wanderwildwood.aikotoba.Clipboard
import com.wanderwildwood.aikotoba.Locker
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.otp.OtpField
import com.wanderwildwood.aikotoba.otp.OtpSpec
import com.wanderwildwood.aikotoba.vault.Item

/**
 * One entry. Pressing a value copies it; the password shows only when asked, and goes again
 * when the screen does.
 */
@Composable
fun EntryScreen(
    item: Item,
    password: () -> String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onNextCode: (OtpSpec) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var shown by remember(item.uuid) { mutableStateOf(false) }
    Frame(
        title = item.label,
        onBack = onBack,
        actions = { BarButton(Icons.Edit, stringResource(R.string.cd_edit), onEdit) },
    ) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            if (item.username.isNotEmpty()) {
                item {
                    val label = stringResource(R.string.label_username)
                    ValueRow(label, item.username, Icons.Copy, stringResource(R.string.cd_copy)) { Clipboard.copy(context, label, item.username) }
                }
            }
            if (item.hasPassword) {
                item {
                    val label = stringResource(R.string.label_password)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable { Clipboard.copy(context, label, password()) }
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
                            TextMMD(text = if (shown) password() else "••••••••••", style = MaterialTheme.typography.bodyLarge)
                        }
                        BarButton(if (shown) Icons.VisibilityOff else Icons.Visibility, stringResource(if (shown) R.string.cd_hide else R.string.cd_show)) { shown = !shown }
                        BarButton(Icons.Copy, stringResource(R.string.cd_copy)) { Clipboard.copy(context, label, password()) }
                    }
                    DottedRule()
                }
            }
            item.otp?.let { spec ->
                item {
                    val now = rememberNow()
                    val code = remember(spec, if (spec.isCounter) 0L else now / 1000 / spec.period) { runCatching { OtpField.code(spec, now) }.getOrNull() }
                    val label = stringResource(R.string.label_code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(enabled = code != null) { Clipboard.copy(context, label, code!!) }
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
                            TextMMD(
                                text = code?.let(OtpField::spaced) ?: stringResource(R.string.code_unreadable),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            TextMMD(
                                text = if (spec.isCounter) stringResource(R.string.code_counter)
                                else stringResource(R.string.code_seconds, Otp.secondsRemaining(spec.period, now).toString()),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        BarButton(Icons.Copy, stringResource(R.string.cd_copy)) { if (code != null) Clipboard.copy(context, label, code) }
                    }
                    if (spec.isCounter) {
                        Column(Modifier.padding(horizontal = 16.dp)) { FootButton(stringResource(R.string.code_next)) { onNextCode(spec) } }
                    }
                    DottedRule()
                }
            }
            if (item.url.isNotEmpty()) {
                item {
                    ValueRow(stringResource(R.string.label_url), item.url, Icons.OpenInNew, stringResource(R.string.cd_open)) {
                        val address = if (item.url.contains("://")) item.url else "https://${item.url}"
                        Locker.errand()
                        launch(context) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                    }
                }
            }
            if (item.notes.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        TextMMD(text = stringResource(R.string.label_notes), style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { TextMMD(text = item.notes, style = MaterialTheme.typography.bodyMedium) }
                    }
                    DottedRule()
                }
            }
            if (item.group.isNotEmpty()) {
                item { Say(stringResource(R.string.entry_in_group, item.group)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                val (armed, press) = rememberArmed(item.uuid, onDelete)
                PlainRow(
                    title = stringResource(if (armed) R.string.entry_delete_armed else R.string.entry_delete),
                    bold = armed,
                    onPress = press,
                )
            }
        }
    }
}

/** A labelled value: pressing the row does the same as the button at its end. */
@Composable
private fun ValueRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onPress: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onPress)) {
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp)) {
            TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
            TextMMD(text = value, style = MaterialTheme.typography.bodyLarge)
        }
        BarButton(icon, description, onPress)
    }
    DottedRule()
}
